package com.androidvirtualcam.replay

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CPU-based Instant Replay – NO EGL, NO GL, NO shaders.
 * ImageAnalysis ByteArray -> CPU -> MediaCodec -> ReplayBuffer (60s RAM) -> MP4
 * Pure CameraX PreviewView for display, ZERO GL touching preview surface.
 */
class InstantReplayManager(private val context: Context) {

    private val tag = "InstantReplayManager"

    private var mediaCodec: MediaCodec? = null
    private val replayBuffer = ReplayBuffer(maxDurationUs = 60_000_000L)
    private var drainThread: Thread? = null
    @Volatile private var stopDrain = false
    private val frameQueue = LinkedBlockingQueue<FrameData>()

    private val _isReplaying = MutableStateFlow(false)
    val isReplaying: StateFlow<Boolean> = _isReplaying

    private val _bufferDurationSec = MutableStateFlow(0f)
    val bufferDurationSec: StateFlow<Float> = _bufferDurationSec

    private val _bufferSizeMB = MutableStateFlow(0f)
    val bufferSizeMB: StateFlow<Float> = _bufferSizeMB

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _lastSavedFile = MutableStateFlow<File?>(null)
    val lastSavedFile: StateFlow<File?> = _lastSavedFile

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var outputWidth = 1280
    private var outputHeight = 720
    private var bitRate = 4_000_000
    private var frameRate = 30
    private var presentationTimeUs = 0L

    data class ReplayConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val bitRate: Int = 4_000_000,
        val frameRate: Int = 30,
        val maxDurationSec: Int = 60,
        val iFrameInterval: Int = 2
    )

    private data class FrameData(
        val data: ByteArray,
        val timestampNs: Long
    )

    fun setSharedEglContext(context: android.opengl.EGLContext) {
        Log.i(tag, "setSharedEglContext called but now CPU pipeline – ignoring EGL")
    }

    fun isReplaying(): Boolean = _isReplaying.value
    fun getBufferDuration(): Float = _bufferDurationSec.value

    suspend fun startReplay(config: ReplayConfig = ReplayConfig()): Result<Unit> = withContext(Dispatchers.IO) {
        if (_isReplaying.value) {
            return@withContext Result.failure(IllegalStateException("Already replaying"))
        }

        outputWidth = config.width
        outputHeight = config.height
        bitRate = config.bitRate
        frameRate = config.frameRate

        Log.i(tag, "Starting CPU instant replay ${config.width}x${config.height} ${config.bitRate/1000}kbps ${config.maxDurationSec}s buffer – NO EGL, NO GL")

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameInterval)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            replayBuffer.clear()
            replayBuffer.updateMaxDuration(config.maxDurationSec * 1_000_000L)
            frameQueue.clear()
            presentationTimeUs = 0L
            startDrainThread()

            _isReplaying.value = true
            Log.i(tag, "CPU Instant replay started – buffering last ${config.maxDurationSec}s in memory – NO EGL")

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(tag, "startReplay failed", e)
            _errorMessage.value = "Failed to start replay: ${e.message}"
            cleanup()
            Result.failure(e)
        }
    }

    private fun startDrainThread() {
        stopDrain = false
        drainThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (!stopDrain) {
                try {
                    val codec = mediaCodec ?: break

                    // Feed frames from queue
                    var frame = frameQueue.poll()
                    while (frame != null) {
                        val inputIndex = codec.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(frame.data)
                            val pts = presentationTimeUs
                            presentationTimeUs += 1_000_000L / frameRate
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
                            val data = ByteArray(bufferInfo.size)
                            val dup = outBuf.duplicate()
                            dup.position(bufferInfo.offset)
                            dup.limit(bufferInfo.offset + bufferInfo.size)
                            dup.get(data)
                            val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            replayBuffer.addSample(data, bufferInfo.presentationTimeUs, isKey, bufferInfo)
                            _bufferDurationSec.value = replayBuffer.getDurationSec()
                            _bufferSizeMB.value = replayBuffer.getSizeMB()
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Drain error", e)
                }
            }
        }.apply { start() }
    }

    // New CPU path – ByteArray from ImageAnalysis
    fun onFrameAvailable(nv21Data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (!_isReplaying.value) return
        try {
            val yuv420 = nv21ToYuv420(nv21Data, width, height)
            frameQueue.offer(FrameData(yuv420, timestampNs))
        } catch (e: Exception) {
            Log.w(tag, "onFrameAvailable failed", e)
        }
    }

    // Legacy GL path – no longer used
    fun renderTexture(texture: com.androidvirtualcam.compositor.GLTexture, timestampNs: Long) {
        Log.w(tag, "renderTexture(GLTexture) called but now CPU pipeline – ignoring")
    }

    fun renderFrameToEncoder(renderer: com.androidvirtualcam.rendering.GLRenderer, presentationTimeNs: Long) {
        Log.w(tag, "renderFrameToEncoder called but now CPU pipeline – ignoring")
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

    suspend fun saveReplay(): Result<File> = withContext(Dispatchers.IO) {
        if (!_isReplaying.value && replayBuffer.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No replay buffer"))
        }

        _isSaving.value = true
        try {
            val fileName = "replay_${System.currentTimeMillis()}.mp4"
            val file = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                File(context.cacheDir, fileName)
            } else {
                File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)
            }
            file.parentFile?.mkdirs()

            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val samples = replayBuffer.getSamples()
            if (samples.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Buffer empty"))
            }

            // Need format from encoder – use first sample's format approximated
            // For simplicity, create format and add track
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            }
            // We need actual csd from buffer – first sample may contain it, but we approximate
            // Real implementation should store format – for CPU path we just write samples
            var trackIndex = -1
            try {
                // Try to get format from codec
                val codecFormat = mediaCodec?.outputFormat
                if (codecFormat != null) {
                    trackIndex = muxer.addTrack(codecFormat)
                } else {
                    trackIndex = muxer.addTrack(format)
                }
            } catch (e: Exception) {
                trackIndex = muxer.addTrack(format)
            }

            muxer.start()
            var ptsOffset = samples.firstOrNull()?.presentationTimeUs ?: 0L
            for (sample in samples) {
                val buffer = ByteBuffer.wrap(sample.data)
                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = sample.data.size
                    presentationTimeUs = sample.presentationTimeUs - ptsOffset
                    flags = if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                muxer.writeSampleData(trackIndex, buffer, info)
            }
            muxer.stop()
            muxer.release()

            val finalFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(file)
            } else {
                file
            }

            _lastSavedFile.value = finalFile ?: file
            Log.i(tag, "Replay saved to ${finalFile?.absolutePath ?: file.absolutePath} – CPU pipeline, NO EGL")
            Result.success(finalFile ?: file)
        } catch (e: Exception) {
            Log.e(tag, "saveReplay failed", e)
            _errorMessage.value = "Save failed: ${e.message}"
            Result.failure(e)
        } finally {
            _isSaving.value = false
        }
    }

    private fun saveToMediaStore(tempFile: File): File? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, tempFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VirtualCam/Replays")
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
                tempFile
            } else null
        } catch (e: Exception) {
            Log.e(tag, "saveToMediaStore failed", e)
            null
        }
    }

    fun stopReplay(): Result<Unit> {
        return try {
            stopDrain = true
            drainThread?.join(1000)
            cleanup()
            _isReplaying.value = false
            _bufferDurationSec.value = 0f
            _bufferSizeMB.value = 0f
            Log.i(tag, "CPU Replay stopped – NO EGL")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cleanup() {
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        frameQueue.clear()
    }

    fun release() {
        try {
            stopDrain = true
            drainThread?.join(500)
        } catch (_: Exception) {}
        cleanup()
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

class ReplayBuffer(private var maxDurationUs: Long) {
    private val samples = mutableListOf<ReplaySample>()
    private var totalSizeBytes = 0L

    data class ReplaySample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean,
        val bufferInfo: MediaCodec.BufferInfo
    )

    @Synchronized
    fun addSample(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean, info: MediaCodec.BufferInfo) {
        val sample = ReplaySample(data, ptsUs, isKeyFrame, MediaCodec.BufferInfo().apply {
            set(0, data.size, ptsUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        })
        samples.add(sample)
        totalSizeBytes += data.size

        // Evict old samples beyond max duration, but keep starting with keyframe
        while (samples.size > 1) {
            val duration = samples.last().presentationTimeUs - samples.first().presentationTimeUs
            if (duration <= maxDurationUs) break
            val removed = samples.removeAt(0)
            totalSizeBytes -= removed.data.size
        }

        // Ensure first is keyframe
        while (samples.size > 1 && !samples.first().isKeyFrame) {
            val removed = samples.removeAt(0)
            totalSizeBytes -= removed.data.size
        }
    }

    @Synchronized
    fun getSamples(): List<ReplaySample> = samples.toList()

    @Synchronized
    fun clear() {
        samples.clear()
        totalSizeBytes = 0L
    }

    @Synchronized
    fun isEmpty(): Boolean = samples.isEmpty()

    @Synchronized
    fun getDurationSec(): Float {
        if (samples.size < 2) return 0f
        return (samples.last().presentationTimeUs - samples.first().presentationTimeUs) / 1_000_000f
    }

    @Synchronized
    fun getSizeMB(): Float = totalSizeBytes / (1024f * 1024f)

    @Synchronized
    fun updateMaxDuration(durationUs: Long) {
        maxDurationUs = durationUs
    }
}
