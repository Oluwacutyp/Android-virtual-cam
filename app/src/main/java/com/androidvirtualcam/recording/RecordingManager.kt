package com.androidvirtualcam.recording

import com.androidvirtualcam.processing.CpuFrameProcessor

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CPU-based recording – NO EGL, NO GL, NO GLSurfaceView, NO shaders in preview path.
 * Uses ImageAnalysis ByteArray (NV21/YUV) from CameraManager -> CPU processing -> MediaCodec encoder.
 * Pure CameraX PreviewView for display, ImageAnalysis for recording/streaming output.
 */
class RecordingManager(private val context: Context) {

    private val tag = "RecordingManager"

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private var isRecording = false
    private var muxerStarted = false
    private var videoTrackIndex = -1

    private var outputFile: File? = null
    private var outputWidth = 1280
    private var outputHeight = 720
    private var bitRate = 6_000_000
    private var frameRate = 30

    private var presentationTimeUs = 0L
    private val frameQueue = java.util.concurrent.LinkedBlockingQueue<FrameData>()
    private var cpuProcessor: CpuFrameProcessor? = null
    private var encoderThread: Thread? = null
    @Volatile private var stopEncoder = false

    data class RecordingConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val bitRate: Int = 6_000_000,
        val frameRate: Int = 30,
        val iFrameInterval: Int = 1
    )

    private data class FrameData(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val timestampNs: Long
    )

    fun isRecording(): Boolean = isRecording

    // Legacy method kept for compatibility – no longer uses EGL
    fun setSharedEglContext(context: android.opengl.EGLContext) {
        Log.i(tag, "setSharedEglContext called but now CPU pipeline – ignoring EGL context, using ByteArray -> MediaCodec")
    }

    suspend fun startRecording(config: RecordingConfig = RecordingConfig()): Result<File> = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext Result.failure(IllegalStateException("Already recording"))

        outputWidth = config.width
        outputHeight = config.height
        bitRate = config.bitRate
        frameRate = config.frameRate

        try {
            val fileName = "virtualcam_${System.currentTimeMillis()}.mp4"
            val file = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                File(context.cacheDir, fileName)
            } else {
                File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)
            }
            file.parentFile?.mkdirs()
            outputFile = file

            // MediaFormat with YUV input (not Surface) – CPU ByteArray
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
                // Use YUV420 flexible for CPU input – no EGL surface
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameInterval)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            isRecording = true
            muxerStarted = false
            presentationTimeUs = 0L
            stopEncoder = false
            frameQueue.clear()
            cpuProcessor = CpuFrameProcessor(outputWidth, outputHeight)

            Log.i(tag, "CPU Recording started to ${file.absolutePath} ${outputWidth}x${outputHeight} – ByteArray -> MediaCodec, NO EGL, NO GL")

            startEncoderThread()

            Result.success(file)
        } catch (e: Exception) {
            Log.e(tag, "startRecording failed", e)
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * New CPU path: feed ByteArray frame (NV21) from ImageAnalysis -> CPU processing -> encoder
     * This is called from CameraManager's cpuFrameListener on background thread.
     */
    fun onFrameAvailable(nv21Data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (!isRecording) return
        try {
            // CPU processing – simple resize/crop if needed, or apply effects on CPU
            // For now, just queue the frame – process on CPU thread
            val processed = processFrameCpu(nv21Data, width, height)
            if (processed != null) {
                frameQueue.offer(FrameData(processed, width, height, timestampNs))
            }
        } catch (e: Exception) {
            Log.w(tag, "onFrameAvailable failed", e)
        }
    }

    /**
     * CPU processing of frame – placeholder for effects, overlays, etc.
     * Currently does simple NV21 -> YUV420 conversion if needed, or just passes through.
     * No GL, pure CPU.
     */
    private fun processFrameCpu(nv21: ByteArray, width: Int, height: Int): ByteArray? {
        return try {
            // CPU processing via CpuFrameProcessor – NO GL, NO EGL, NO shaders
            // Preview is pure CameraX PreviewView, ZERO GL touching preview surface
            // Recording uses ImageAnalysis ByteArray -> CPU -> MediaCodec
            cpuProcessor?.processFrame(nv21, width, height) ?: nv21ToYuv420(nv21, width, height)
        } catch (e: Exception) {
            Log.w(tag, "processFrameCpu failed", e)
            nv21
        }
    }

    private fun nv21ToYuv420(nv21: ByteArray, width: Int, height: Int): ByteArray {
        // NV21: Y + VU interleaved
        // YUV420: Y + U + V planar
        val ySize = width * height
        val uvSize = ySize / 4
        val yuv420 = ByteArray(ySize + uvSize * 2)

        // Y
        System.arraycopy(nv21, 0, yuv420, 0, ySize)

        // UV: NV21 has VU VU, YUV420 has U + V
        val uvStart = ySize
        var uPos = ySize
        var vPos = ySize + uvSize
        var i = uvStart
        while (i < nv21.size - 1) {
            // NV21: V at i, U at i+1
            val v = nv21[i]
            val u = nv21[i + 1]
            if (uPos < ySize + uvSize) {
                yuv420[uPos++] = u
            }
            if (vPos < yuv420.size) {
                yuv420[vPos++] = v
            }
            i += 2
        }
        return yuv420
    }

    private fun startEncoderThread() {
        stopEncoder = false
        encoderThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            var frameCount = 0
            while (!stopEncoder) {
                try {
                    val encoder = mediaCodec ?: break

                    // Feed input frames from queue
                    var frame = frameQueue.poll()
                    while (frame != null) {
                        val inputIndex = encoder.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                // Ensure buffer large enough
                                val data = frame.data
                                if (data.size <= inputBuffer.remaining()) {
                                    inputBuffer.put(data)
                                    val pts = presentationTimeUs
                                    presentationTimeUs += 1_000_000L / frameRate
                                    encoder.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
                                    frameCount++
                                    if (frameCount % 30 == 0) {
                                        Log.d(tag, "Encoded $frameCount frames via CPU ByteArray -> MediaCodec")
                                    }
                                } else {
                                    Log.w(tag, "Frame too large for input buffer ${data.size} vs ${inputBuffer.remaining()}")
                                    encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                                }
                            }
                        } else {
                            // No input buffer available, re-queue frame
                            frameQueue.offer(frame)
                            break
                        }
                        frame = frameQueue.poll()
                    }

                    // Drain output
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw RuntimeException("Format changed twice")
                            val newFormat = encoder.outputFormat
                            videoTrackIndex = mediaMuxer!!.addTrack(newFormat)
                            mediaMuxer!!.start()
                            muxerStarted = true
                            Log.i(tag, "Muxer started track $videoTrackIndex – CPU pipeline")
                        }
                        outIndex >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(outIndex) ?: continue
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size != 0) {
                                if (!muxerStarted) throw RuntimeException("Muxer not started")
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer!!.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                break
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(tag, "Encoder thread exception", e)
                    break
                }
            }
            Log.i(tag, "Encoder thread finished after $frameCount frames")
        }.apply { start() }
    }

    // Legacy GL method kept for compatibility but now no-op – preview is pure CameraX
    fun renderFrameToEncoder(renderer: com.androidvirtualcam.rendering.GLRenderer, presentationTimeNs: Long) {
        Log.w(tag, "renderFrameToEncoder called but now CPU pipeline – ignoring GL renderer, using ByteArray frames")
    }

    suspend fun stopRecording(): Result<File> = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext Result.failure(IllegalStateException("Not recording"))

        try {
            stopEncoder = true
            encoderThread?.join(2000)

            // Signal EOS
            try {
                val encoder = mediaCodec
                if (encoder != null) {
                    val inputIndex = encoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Signal EOS failed", e)
            }

            // Drain remaining
            val bufferInfo = MediaCodec.BufferInfo()
            var attempts = 0
            while (attempts < 50) {
                val encoder = mediaCodec ?: break
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    attempts++
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    continue
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        val newFormat = encoder.outputFormat
                        videoTrackIndex = mediaMuxer!!.addTrack(newFormat)
                        mediaMuxer!!.start()
                        muxerStarted = true
                    }
                    continue
                }
                if (outIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer!!.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                attempts = 0
            }

            mediaCodec?.stop()
            mediaMuxer?.let {
                if (muxerStarted) {
                    try { it.stop() } catch (e: Exception) { Log.w(tag, "Muxer stop failed", e) }
                }
                try { it.release() } catch (_: Exception) {}
            }

            val file = outputFile
            if (file == null || !file.exists()) {
                return@withContext Result.failure(IllegalStateException("Output file missing"))
            }

            val finalFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(file)
            } else {
                file
            }

            Log.i(tag, "CPU Recording stopped, file=${finalFile?.absolutePath ?: file.absolutePath} size=${file.length()} – NO EGL")

            cleanup()
            if (finalFile != null) Result.success(finalFile) else Result.success(file)
        } catch (e: Exception) {
            Log.e(tag, "stopRecording failed", e)
            cleanup()
            Result.failure(e)
        } finally {
            isRecording = false
        }
    }

    private fun saveToMediaStore(tempFile: File): File? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, tempFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VirtualCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                Log.i(tag, "Saved to MediaStore $uri")
                tempFile
            } else null
        } catch (e: Exception) {
            Log.e(tag, "saveToMediaStore failed", e)
            null
        }
    }

    private fun cleanup() {
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        try { mediaMuxer?.release() } catch (_: Exception) {}
        mediaMuxer = null
        isRecording = false
        muxerStarted = false
        videoTrackIndex = -1
        frameQueue.clear()
        encoderThread = null
        stopEncoder = false
    }

    fun release() {
        try {
            stopEncoder = true
            encoderThread?.join(500)
        } catch (_: Exception) {}
        cleanup()
    }
}
