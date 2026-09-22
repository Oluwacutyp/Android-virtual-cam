package com.androidvirtualcam.streaming

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CPU-based MKV recording – NO EGL, NO GL, NO shaders.
 * Uses ImageAnalysis ByteArray (NV21) -> CPU processing -> MediaCodec -> MkvMuxer
 * Pure CameraX PreviewView for display, ZERO GL touching preview surface.
 */
class MkvRecordingManager(
    private val context: Context,
    private val outputPath: String,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val bitrate: Int = 6_000_000,
    private val fps: Int = 30
) {
    companion object {
        private const val TAG = "MkvRecording"
    }

    private var videoCodec: MediaCodec? = null
    private var muxer: MkvMuxer? = null

    private val isRecording = AtomicBoolean(false)
    private var drainJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val frameQueue = LinkedBlockingQueue<FrameData>()
    private var presentationTimeUs = 0L
    private var muxerStarted = false

    private data class FrameData(
        val data: ByteArray,
        val timestampNs: Long
    )

    fun setSharedEglContext(eglContext: android.opengl.EGLContext) {
        Log.i(TAG, "setSharedEglContext called but now CPU pipeline – ignoring EGL")
    }

    fun start(): Boolean {
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            val file = File(outputPath)
            file.parentFile?.mkdirs()
            muxer = MkvMuxer(file)

            isRecording.set(true)
            presentationTimeUs = 0L
            muxerStarted = false
            frameQueue.clear()

            drainJob = scope.launch {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRecording.get()) {
                    try {
                        val codec = videoCodec ?: break
                        var frame = frameQueue.poll()
                        while (frame != null) {
                            val inputIndex = codec.dequeueInputBuffer(10000)
                            if (inputIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inputIndex)
                                inputBuffer?.clear()
                                inputBuffer?.put(frame.data)
                                val pts = presentationTimeUs
                                presentationTimeUs += 1_000_000L / fps
                                codec.queueInputBuffer(inputIndex, 0, frame.data.size, pts, 0)
                            } else {
                                frameQueue.offer(frame)
                                break
                            }
                            frame = frameQueue.poll()
                        }

                        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outIndex >= 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && bufferInfo.size > 0) {
                                if (!muxerStarted) {
                                    muxer?.addVideoTrack(codec.outputFormat)
                                    muxer?.start()
                                    muxerStarted = true
                                }
                                val dup = outBuf.duplicate()
                                dup.position(bufferInfo.offset)
                                dup.limit(bufferInfo.offset + bufferInfo.size)
                                val data = ByteArray(bufferInfo.size)
                                dup.get(data)
                                muxer?.writeVideoSample(data, bufferInfo.presentationTimeUs, (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            Log.i(TAG, "Format changed: ${codec.outputFormat}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Drain error", e)
                    }
                }
            }

            Log.i(TAG, "CPU MkvRecording started $outputPath ${width}x${height} – NO EGL, NO GL, ByteArray -> MediaCodec")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            false
        }
    }

    // New CPU path
    fun onFrameAvailable(nv21Data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (!isRecording.get()) return
        try {
            val yuv420 = nv21ToYuv420(nv21Data, width, height)
            frameQueue.offer(FrameData(yuv420, timestampNs))
        } catch (e: Exception) {
            Log.w(TAG, "onFrameAvailable failed", e)
        }
    }

    fun renderFrame(texture: com.androidvirtualcam.compositor.GLTexture, timestampNs: Long) {
        Log.w(TAG, "renderFrame(GLTexture) called but now CPU pipeline – ignoring")
    }

    private fun nv21ToYuv420(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        if (nv21.size < ySize) return nv21
        val yuv420 = ByteArray(ySize + uvSize * 2)
        System.arraycopy(nv21, 0, yuv420, 0, ySize.coerceAtMost(nv21.size))
        if (nv21.size >= ySize + uvSize * 2) {
            var uPos = ySize
            var vPos = ySize + uvSize
            var i = ySize
            while (i < nv21.size - 1 && uPos < ySize + uvSize && vPos < yuv420.size) {
                yuv420[uPos++] = nv21[i + 1]
                yuv420[vPos++] = nv21[i]
                i += 2
            }
        }
        return yuv420
    }

    fun feedAudio(data: ByteArray, timestampUs: Long) {
        // Audio not implemented in CPU MKV for now
    }

    fun stop(): String? {
        return try {
            isRecording.set(false)
            runBlocking { drainJob?.join() }
            videoCodec?.stop()
            videoCodec?.release()
            muxer?.stop()
            muxer?.release()
            Log.i(TAG, "CPU MkvRecording stopped $outputPath – NO EGL")
            outputPath
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            null
        } finally {
            videoCodec = null
            muxer = null
        }
    }

    fun release() {
        try {
            isRecording.set(false)
            drainJob?.cancel()
        } catch (_: Exception) {}
    }
}
