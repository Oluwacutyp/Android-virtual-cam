package com.androidvirtualcam.streaming

import com.androidvirtualcam.processing.CpuFrameProcessor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Base64
import android.util.Log
import android.view.Surface
import com.androidvirtualcam.compositor.GLTexture
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.srt.srt.SrtClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class StreamingManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "StreamingManager"
        const val MAX_DESTINATIONS = 3
        const val RTSP_SERVER_PORT = 8554
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sessions = ConcurrentHashMap<String, Destination>()
    private val _sessionsFlow = MutableStateFlow<List<StreamingSession>>(emptyList())
    val sessionsFlow: StateFlow<List<StreamingSession>> = _sessionsFlow

    private var sharedEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var sharedEglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY

    private var recordingManager: MkvRecordingManager? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private var rtspServer: RtspServerWrapper? = null

    data class Destination(
        val session: StreamingSession,
        var videoEncoder: VideoEncoderWrapper? = null,
        var audioEncoder: AudioEncoderWrapper? = null,
        var rtmpClient: RtmpClientWrapper? = null,
        var srtClient: SrtClientWrapper? = null,
        var rtspClient: RtspClientWrapper? = null,
        var adaptiveController: AdaptiveBitrateController? = null,
        var reconnectJob: Job? = null,
        var isConnected: AtomicBoolean = AtomicBoolean(false)
    )

    fun setSharedEglContext(eglContext: EGLContext, eglDisplay: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)) {
        // CPU pipeline – NO EGL, NO GL in preview path, ByteArray -> MediaCodec for recording/streaming
        sharedEglContext = eglContext
        sharedEglDisplay = eglDisplay
        Log.i(TAG, "setSharedEglContext called but now CPU pipeline – ignoring EGL, using ByteArray -> MediaCodec (pure CameraX PreviewView)")
    }

    fun addDestination(
        url: String,
        protocol: StreamingProtocol = StreamingProtocol.RTMP,
        bitrate: Int = 4_000_000,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30
    ): Result<String> {
        if (sessions.size >= MAX_DESTINATIONS) {
            return Result.failure(IllegalStateException("Max $MAX_DESTINATIONS destinations reached"))
        }
        if (url.isBlank() && protocol != StreamingProtocol.RTSP_SERVER) {
            return Result.failure(IllegalArgumentException("URL cannot be blank"))
        }
        val id = UUID.randomUUID().toString()
        val session = StreamingSession(
            id = id,
            url = url,
            protocol = protocol,
            status = StreamingStatus.IDLE,
            currentBitrate = bitrate,
            requestedBitrate = bitrate,
            width = width,
            height = height,
            fps = fps
        )
        val adaptiveController = AdaptiveBitrateController(
            sessionId = id,
            initialBitrate = bitrate,
            onBitrateChanged = { newBitrate, isAudioOnly ->
                handleBitrateChange(id, newBitrate, isAudioOnly)
            }
        )
        val destination = Destination(
            session = session,
            adaptiveController = adaptiveController
        )
        sessions[id] = destination
        updateSessionsFlow()
        Log.i(TAG, "Added destination $id: $protocol $url ${width}x${height} ${bitrate / 1000}kbps")
        return Result.success(id)
    }

    fun removeDestination(id: String): Result<Unit> {
        val dest = sessions[id] ?: return Result.failure(IllegalArgumentException("Destination $id not found"))
        scope.launch {
            disconnectInternal(dest)
        }
        dest.reconnectJob?.cancel()
        dest.adaptiveController?.stopMonitoring()
        sessions.remove(id)
        updateSessionsFlow()
        Log.i(TAG, "Removed destination $id")
        return Result.success(Unit)
    }

    fun startStreaming(id: String): Result<Unit> {
        val dest = sessions[id] ?: return Result.failure(IllegalArgumentException("Destination $id not found"))
        if (dest.isConnected.get()) {
            return Result.failure(IllegalStateException("Already connected"))
        }
        val job = scope.launch {
            connectWithBackoff(dest)
        }
        dest.reconnectJob = job
        return Result.success(Unit)
    }

    fun stopStreaming(id: String): Result<Unit> {
        val dest = sessions[id] ?: return Result.failure(IllegalArgumentException("Destination $id not found"))
        scope.launch {
            dest.reconnectJob?.cancel()
            disconnectInternal(dest)
            dest.session.status = StreamingStatus.DISCONNECTED
            dest.session.reconnectAttempts = 0
            updateSessionsFlow()
        }
        return Result.success(Unit)
    }

    fun startAll() {
        for ((id, _) in sessions) {
            startStreaming(id)
        }
    }

    fun stopAll() {
        for ((id, _) in sessions) {
            stopStreaming(id)
        }
    }

    private suspend fun connectWithBackoff(dest: Destination) {
        val session = dest.session
        var attempt = 0
        val backoffSteps = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)

        while (currentCoroutineContext().isActive) {
            attempt++
            session.reconnectAttempts = attempt
            session.status = if (attempt == 1) StreamingStatus.CONNECTING else StreamingStatus.RECONNECTING
            updateSessionsFlow()

            Log.i(TAG, "[${session.id}] Connecting attempt $attempt to ${session.url} (${session.protocol})")

            val result = connectInternal(dest)

            if (result.isSuccess) {
                session.status = StreamingStatus.LIVE
                session.startTimeMs = System.currentTimeMillis()
                session.reconnectAttempts = 0
                dest.isConnected.set(true)
                dest.adaptiveController?.startMonitoring()
                updateSessionsFlow()
                Log.i(TAG, "[${session.id}] Connected LIVE to ${session.url}")
                break
            } else {
                val error = result.exceptionOrNull()?.message ?: "unknown"
                session.lastError = error
                session.status = StreamingStatus.FAILED
                updateSessionsFlow()

                Log.w(TAG, "[${session.id}] Connection failed attempt $attempt: $error")

                dest.adaptiveController?.onConnectionFailed(error)

                if (attempt >= 10) {
                    Log.e(TAG, "[${session.id}] Max reconnect attempts reached")
                    session.status = StreamingStatus.FAILED
                    updateSessionsFlow()
                    break
                }

                val backoffMs = backoffSteps.getOrElse(attempt - 1) { 30000L }
                Log.i(TAG, "[${session.id}] Reconnecting in ${backoffMs}ms")
                session.status = StreamingStatus.RECONNECTING
                updateSessionsFlow()
                delay(backoffMs)
            }
        }
    }

    private suspend fun connectInternal(dest: Destination): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val session = dest.session

                val videoEncoder = VideoEncoderWrapper(
                    width = session.width,
                    height = session.height,
                    bitrate = session.currentBitrate,
                    fps = session.fps,
                    sharedEglContext = sharedEglContext,
                    sharedEglDisplay = sharedEglDisplay
                )
                val audioEncoder = AudioEncoderWrapper(
                    bitrate = 128_000,
                    sampleRate = 48000,
                    channels = 1
                )

                dest.videoEncoder = videoEncoder
                dest.audioEncoder = audioEncoder

                if (!videoEncoder.prepare()) {
                    return@withContext Result.failure(RuntimeException("Failed to prepare video encoder"))
                }
                if (!audioEncoder.prepare()) {
                    return@withContext Result.failure(RuntimeException("Failed to prepare audio encoder"))
                }

                when (session.protocol) {
                    StreamingProtocol.RTMP -> {
                        val checker = object : ConnectChecker {
                            override fun onConnectionStarted(url: String) {
                                Log.i(TAG, "[${session.id}] RTMP connection started: $url")
                            }
                            override fun onConnectionSuccess() {
                                Log.i(TAG, "[${session.id}] RTMP connection success")
                                dest.isConnected.set(true)
                                session.status = StreamingStatus.LIVE
                                scope.launch { updateSessionsFlow() }
                            }
                            override fun onConnectionFailed(reason: String) {
                                Log.w(TAG, "[${session.id}] RTMP connection failed: $reason")
                                session.lastError = reason
                                dest.adaptiveController?.onConnectionFailed(reason)
                            }
                            override fun onNewBitrate(bitrate: Long) {
                                dest.adaptiveController?.onBitrateMeasured(bitrate.toInt())
                                session.bytesSent = bitrate
                            }
                            override fun onDisconnect() {
                                Log.i(TAG, "[${session.id}] RTMP disconnected")
                                dest.isConnected.set(false)
                                if (session.status == StreamingStatus.LIVE) {
                                    session.status = StreamingStatus.RECONNECTING
                                    scope.launch { updateSessionsFlow() }
                                }
                            }
                            override fun onAuthError() {
                                Log.w(TAG, "[${session.id}] RTMP auth error")
                                session.lastError = "Auth error"
                            }
                            override fun onAuthSuccess() {
                                Log.i(TAG, "[${session.id}] RTMP auth success")
                            }
                        }
                        val client = RtmpClientWrapper(checker, session.width, session.height, session.fps)
                        dest.rtmpClient = client

                        val deferred = CompletableDeferred<Boolean>()
                        val originalChecker = checker
                        val wrappingChecker = object : ConnectChecker {
                            override fun onConnectionStarted(url: String) {
                                originalChecker.onConnectionStarted(url)
                            }
                            override fun onConnectionSuccess() {
                                originalChecker.onConnectionSuccess()
                                if (!deferred.isCompleted) deferred.complete(true)
                            }
                            override fun onConnectionFailed(reason: String) {
                                originalChecker.onConnectionFailed(reason)
                                if (!deferred.isCompleted) deferred.complete(false)
                            }
                            override fun onNewBitrate(bitrate: Long) {
                                originalChecker.onNewBitrate(bitrate)
                            }
                            override fun onDisconnect() {
                                originalChecker.onDisconnect()
                            }
                            override fun onAuthError() {
                                originalChecker.onAuthError()
                                if (!deferred.isCompleted) deferred.complete(false)
                            }
                            override fun onAuthSuccess() {
                                originalChecker.onAuthSuccess()
                            }
                        }
                        client.updateChecker(wrappingChecker)

                        videoEncoder.start(
                            onFormat = { format ->
                                try {
                                    val sps = format.getByteBuffer("csd-0")
                                    val pps = format.getByteBuffer("csd-1")
                                    if (sps != null && pps != null) {
                                        val spsDup = ByteBuffer.allocate(sps.remaining()).put(sps).flip() as ByteBuffer
                                        val ppsDup = ByteBuffer.allocate(pps.remaining()).put(pps).flip() as ByteBuffer
                                        client.setVideoInfo(spsDup, ppsDup, null)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to set video info: ${e.message}")
                                }
                            },
                            onEncoded = { buffer, info ->
                                client.sendVideo(buffer, info)
                                session.bytesSent += info.size
                            }
                        )
                        audioEncoder.start(
                            onFormat = { format ->
                                try {
                                    client.setAudioInfo(48000, false)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to set audio info: ${e.message}")
                                }
                            },
                            onEncoded = { buffer, info ->
                                client.sendAudio(buffer, info)
                                session.bytesSent += info.size
                            }
                        )

                        client.connect(session.url)
                        val success = withTimeoutOrNull(10000) { deferred.await() } ?: false
                        if (!success && !client.isStreaming()) {
                            return@withContext Result.failure(RuntimeException("RTMP connect failed to ${session.url}: ${session.lastError}"))
                        }
                    }
                    StreamingProtocol.SRT -> {
                        val checker = object : ConnectChecker {
                            override fun onConnectionStarted(url: String) {
                                Log.i(TAG, "[${session.id}] SRT connection started: $url")
                            }
                            override fun onConnectionSuccess() {
                                Log.i(TAG, "[${session.id}] SRT connection success")
                                dest.isConnected.set(true)
                                session.status = StreamingStatus.LIVE
                                scope.launch { updateSessionsFlow() }
                            }
                            override fun onConnectionFailed(reason: String) {
                                Log.w(TAG, "[${session.id}] SRT connection failed: $reason")
                                session.lastError = reason
                                dest.adaptiveController?.onConnectionFailed(reason)
                            }
                            override fun onNewBitrate(bitrate: Long) {
                                dest.adaptiveController?.onBitrateMeasured(bitrate.toInt())
                            }
                            override fun onDisconnect() {
                                Log.i(TAG, "[${session.id}] SRT disconnected")
                                dest.isConnected.set(false)
                            }
                            override fun onAuthError() {
                                Log.w(TAG, "[${session.id}] SRT auth error")
                            }
                            override fun onAuthSuccess() {
                                Log.i(TAG, "[${session.id}] SRT auth success")
                            }
                        }
                        val client = SrtClientWrapper(checker, session.width, session.height, session.fps)
                        dest.srtClient = client

                        val deferred = CompletableDeferred<Boolean>()
                        val wrappingChecker = object : ConnectChecker {
                            override fun onConnectionStarted(url: String) = checker.onConnectionStarted(url)
                            override fun onConnectionSuccess() {
                                checker.onConnectionSuccess()
                                if (!deferred.isCompleted) deferred.complete(true)
                            }
                            override fun onConnectionFailed(reason: String) {
                                checker.onConnectionFailed(reason)
                                if (!deferred.isCompleted) deferred.complete(false)
                            }
                            override fun onNewBitrate(bitrate: Long) = checker.onNewBitrate(bitrate)
                            override fun onDisconnect() = checker.onDisconnect()
                            override fun onAuthError() {
                                checker.onAuthError()
                                if (!deferred.isCompleted) deferred.complete(false)
                            }
                            override fun onAuthSuccess() = checker.onAuthSuccess()
                        }
                        client.updateChecker(wrappingChecker)

                        videoEncoder.start(
                            onFormat = { format ->
                                try {
                                    val sps = format.getByteBuffer("csd-0")
                                    val pps = format.getByteBuffer("csd-1")
                                    if (sps != null && pps != null) {
                                        val spsDup = ByteBuffer.allocate(sps.remaining()).put(sps).flip() as ByteBuffer
                                        val ppsDup = ByteBuffer.allocate(pps.remaining()).put(pps).flip() as ByteBuffer
                                        client.setVideoInfo(spsDup, ppsDup, null)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to set video info SRT: ${e.message}")
                                }
                            },
                            onEncoded = { buffer, info ->
                                client.sendVideo(buffer, info)
                                session.bytesSent += info.size
                            }
                        )
                        audioEncoder.start(
                            onFormat = { _ ->
                                client.setAudioInfo(48000, false)
                            },
                            onEncoded = { buffer, info ->
                                client.sendAudio(buffer, info)
                                session.bytesSent += info.size
                            }
                        )

                        client.connect(session.url)
                        val success = withTimeoutOrNull(10000) { deferred.await() } ?: false
                        if (!success && !client.isStreaming()) {
                            return@withContext Result.failure(RuntimeException("SRT connect failed: ${session.lastError}"))
                        }
                    }
                    StreamingProtocol.RTSP -> {
                        val checker = object : ConnectChecker {
                            override fun onConnectionStarted(url: String) {
                                Log.i(TAG, "[${session.id}] RTSP connection started: $url")
                            }
                            override fun onConnectionSuccess() {
                                Log.i(TAG, "[${session.id}] RTSP connection success")
                                dest.isConnected.set(true)
                                session.status = StreamingStatus.LIVE
                                scope.launch { updateSessionsFlow() }
                            }
                            override fun onConnectionFailed(reason: String) {
                                Log.w(TAG, "[${session.id}] RTSP connection failed: $reason")
                                session.lastError = reason
                                dest.adaptiveController?.onConnectionFailed(reason)
                            }
                            override fun onNewBitrate(bitrate: Long) {
                                dest.adaptiveController?.onBitrateMeasured(bitrate.toInt())
                            }
                            override fun onDisconnect() {
                                Log.i(TAG, "[${session.id}] RTSP disconnected")
                                dest.isConnected.set(false)
                            }
                            override fun onAuthError() {
                                Log.w(TAG, "[${session.id}] RTSP auth error")
                            }
                            override fun onAuthSuccess() {
                                Log.i(TAG, "[${session.id}] RTSP auth success")
                            }
                        }
                        val client = RtspClientWrapper(checker, session.width, session.height, session.fps)
                        dest.rtspClient = client

                        val deferred = CompletableDeferred<Boolean>()
                        val wrappingChecker = object : ConnectChecker {
                            override fun onConnectionStarted(url: String) = checker.onConnectionStarted(url)
                            override fun onConnectionSuccess() {
                                checker.onConnectionSuccess()
                                if (!deferred.isCompleted) deferred.complete(true)
                            }
                            override fun onConnectionFailed(reason: String) {
                                checker.onConnectionFailed(reason)
                                if (!deferred.isCompleted) deferred.complete(false)
                            }
                            override fun onNewBitrate(bitrate: Long) = checker.onNewBitrate(bitrate)
                            override fun onDisconnect() = checker.onDisconnect()
                            override fun onAuthError() {
                                checker.onAuthError()
                                if (!deferred.isCompleted) deferred.complete(false)
                            }
                            override fun onAuthSuccess() = checker.onAuthSuccess()
                        }
                        client.updateChecker(wrappingChecker)

                        videoEncoder.start(
                            onFormat = { format ->
                                try {
                                    val sps = format.getByteBuffer("csd-0")
                                    val pps = format.getByteBuffer("csd-1")
                                    if (sps != null && pps != null) {
                                        val spsDup = ByteBuffer.allocate(sps.remaining()).put(sps).flip() as ByteBuffer
                                        val ppsDup = ByteBuffer.allocate(pps.remaining()).put(pps).flip() as ByteBuffer
                                        client.setVideoInfo(spsDup, ppsDup, null)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to set video info RTSP: ${e.message}")
                                }
                            },
                            onEncoded = { buffer, info ->
                                client.sendVideo(buffer, info)
                                session.bytesSent += info.size
                            }
                        )
                        audioEncoder.start(
                            onFormat = { _ ->
                                client.setAudioInfo(48000, false)
                            },
                            onEncoded = { buffer, info ->
                                client.sendAudio(buffer, info)
                                session.bytesSent += info.size
                            }
                        )

                        client.connect(session.url)
                        val success = withTimeoutOrNull(10000) { deferred.await() } ?: false
                        if (!success && !client.isStreaming()) {
                            return@withContext Result.failure(RuntimeException("RTSP connect failed: ${session.lastError}"))
                        }
                    }
                    StreamingProtocol.RTSP_SERVER -> {
                        if (rtspServer == null) {
                            rtspServer = RtspServerWrapper(port = RTSP_SERVER_PORT)
                            rtspServer?.start()
                        }
                        val server = rtspServer!!

                        videoEncoder.start(
                            onFormat = { format ->
                                try {
                                    val sps = format.getByteBuffer("csd-0")
                                    val pps = format.getByteBuffer("csd-1")
                                    if (sps != null && pps != null) {
                                        val spsBytes = ByteArray(sps.remaining()).also { sps.get(it) }
                                        val ppsBytes = ByteArray(pps.remaining()).also { pps.get(it) }
                                        server.setVideoInfo(spsBytes, ppsBytes)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to set server video info: ${e.message}")
                                }
                            },
                            onEncoded = { buffer, info ->
                                val data = ByteArray(info.size)
                                val dup = buffer.duplicate()
                                dup.position(info.offset)
                                dup.limit(info.offset + info.size)
                                dup.get(data)
                                server.sendVideo(data, info.presentationTimeUs, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0)
                                session.bytesSent += info.size
                            }
                        )
                        audioEncoder.start(
                            onFormat = { format ->
                                try {
                                    val csd = format.getByteBuffer("csd-0")
                                    if (csd != null) {
                                        val bytes = ByteArray(csd.remaining()).also { csd.get(it) }
                                        server.setAudioInfo(bytes)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to set server audio info: ${e.message}")
                                }
                            },
                            onEncoded = { buffer, info ->
                                val data = ByteArray(info.size)
                                val dup = buffer.duplicate()
                                dup.position(info.offset)
                                dup.limit(info.offset + info.size)
                                dup.get(data)
                                server.sendAudio(data, info.presentationTimeUs)
                                session.bytesSent += info.size
                            }
                        )
                    }
                    else -> {
                        return@withContext Result.failure(UnsupportedOperationException("Protocol ${session.protocol} not implemented"))
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "connectInternal failed for ${dest.session.id}: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun disconnectInternal(dest: Destination) {
        withContext(Dispatchers.IO) {
            try {
                dest.isConnected.set(false)
                dest.adaptiveController?.stopMonitoring()

                dest.videoEncoder?.stop()
                dest.audioEncoder?.stop()

                dest.rtmpClient?.disconnect()
                dest.srtClient?.disconnect()
                dest.rtspClient?.disconnect()

                dest.videoEncoder = null
                dest.audioEncoder = null
                dest.rtmpClient = null
                dest.srtClient = null
                dest.rtspClient = null

                dest.session.status = StreamingStatus.DISCONNECTED
                dest.session.durationMs = dest.session.getDuration()

                Log.i(TAG, "[${dest.session.id}] Disconnected, duration ${dest.session.durationMs}ms, bytes ${dest.session.bytesSent}")
            } catch (e: Exception) {
                Log.e(TAG, "disconnectInternal failed: ${e.message}", e)
            }
        }
    }

    private fun handleBitrateChange(sessionId: String, newBitrate: Int, isAudioOnly: Boolean) {
        val dest = sessions[sessionId] ?: return
        dest.session.currentBitrate = newBitrate
        dest.session.isAudioOnly = isAudioOnly
        try {
            dest.videoEncoder?.updateBitrate(newBitrate)
            dest.videoEncoder?.setVideoEnabled(!isAudioOnly)
            if (isAudioOnly) {
                Log.w(TAG, "[$sessionId] Audio-only mode enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set bitrate: ${e.message}")
        }
        updateSessionsFlow()
    }

    // Legacy GL path – no longer used, preview is pure CameraX PreviewView, ZERO GL
    fun onFrameAvailable(texture: GLTexture, timestampNs: Long) {
        Log.w(TAG, "onFrameAvailable(GLTexture) called but now CPU pipeline – ignoring, use ByteArray path")
        for ((_, dest) in sessions) {
            if (!dest.isConnected.get()) continue
            if (dest.session.isAudioOnly) continue
            try {
                dest.videoEncoder?.renderFrame(texture, timestampNs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to render frame to ${dest.session.id}: ${e.message}")
                dest.session.droppedFrames++
            }
        }
    }

    // New CPU path – ByteArray (NV21) from ImageAnalysis -> CPU -> MediaCodec for streaming/recording
    fun onFrameAvailable(nv21Data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        for ((_, dest) in sessions) {
            if (!dest.isConnected.get()) continue
            if (dest.session.isAudioOnly) continue
            try {
                dest.videoEncoder?.onFrameAvailable(nv21Data, timestampNs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to feed CPU frame to ${dest.session.id}: ${e.message}")
                dest.session.droppedFrames++
            }
        }
        if (_isRecording.value) {
            try {
                recordingManager?.onFrameAvailable(nv21Data, width, height, timestampNs)
            } catch (e: Exception) {
                Log.w(TAG, "Recording CPU frame failed: ${e.message}")
            }
        }
    }

    fun onAudioFrame(data: ByteArray, timestampUs: Long) {
        for ((_, dest) in sessions) {
            if (!dest.isConnected.get()) continue
            try {
                dest.audioEncoder?.feedAudio(data, timestampUs)
            } catch (e: Exception) {
                Log.w(TAG, "Audio feed failed for ${dest.session.id}: ${e.message}")
            }
        }
        recordingManager?.feedAudio(data, timestampUs)
    }

    fun startRecording(outputPath: String? = null, width: Int = 1280, height: Int = 720, bitrate: Int = 6_000_000): Result<String> {
        if (_isRecording.value) return Result.failure(IllegalStateException("Already recording"))
        return try {
            val filePath = outputPath ?: run {
                val fileName = "vcam_record_${System.currentTimeMillis()}.mkv"
                val file = File(context.cacheDir, fileName)
                file.absolutePath
            }
            val manager = MkvRecordingManager(context, filePath, width, height, bitrate)
            if (sharedEglContext != EGL14.EGL_NO_CONTEXT) {
                manager.setSharedEglContext(sharedEglContext)
            }
            recordingManager = manager
            val started = manager.start()
            if (!started) {
                return Result.failure(RuntimeException("Failed to start recording"))
            }
            _isRecording.value = true
            Log.i(TAG, "Recording started to $filePath")
            Result.success(filePath)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            Result.failure(e)
        }
    }

    fun stopRecording(): Result<String> {
        if (!_isRecording.value) return Result.failure(IllegalStateException("Not recording"))
        return try {
            val path = recordingManager?.stop()
            recordingManager = null
            _isRecording.value = false
            Log.i(TAG, "Recording stopped: $path")
            Result.success(path ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            Result.failure(e)
        }
    }

    fun getSessions(): List<StreamingSession> = sessions.values.map { it.session }

    private fun updateSessionsFlow() {
        _sessionsFlow.value = getSessions()
    }

    fun release() {
        scope.launch {
            stopAll()
            stopRecording()
            rtspServer?.stop()
            rtspServer = null
        }
        scope.cancel()
        Log.i(TAG, "StreamingManager released")
    }

    class VideoEncoderWrapper(
        val width: Int,
        val height: Int,
        var bitrate: Int,
        val fps: Int,
        val sharedEglContext: EGLContext,
        val sharedEglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    ) {
        companion object {
            private const val TAG = "VideoEncoderWrapper"
        }

        // CPU-based encoder – NO EGL, NO GL, NO shaders, ByteArray -> MediaCodec
        private var mediaCodec: MediaCodec? = null
        private val frameQueue = java.util.concurrent.LinkedBlockingQueue<FrameData>()
        private var drainJob: Job? = null
        private val drainScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var isVideoEnabled = true
        private var isRunning = AtomicBoolean(false)
        private var presentationTimeUs = 0L
        private var cpuProcessor: CpuFrameProcessor? = null

        private data class FrameData(
            val data: ByteArray,
            val timestampNs: Long
        )

        fun prepare(): Boolean {
            return try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                }

                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }

                cpuProcessor = CpuFrameProcessor(width, height)
                Log.i(TAG, "CPU VideoEncoder prepared ${width}x${height} bitrate=${bitrate/1000}kbps fps=$fps – NO EGL, NO GL, ByteArray -> MediaCodec – Pure CameraX PreviewView for display")
                true
            } catch (e: Exception) {
                Log.e(TAG, "prepare failed", e)
                false
            }
        }

        fun start(
            onFormat: (MediaFormat) -> Unit,
            onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
        ) {
            isRunning.set(true)
            presentationTimeUs = 0L
            drainJob = drainScope.launch {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRunning.get()) {
                    try {
                        val codec = mediaCodec ?: break

                        // Feed input from queue – CPU ByteArray frames
                        var frame = frameQueue.poll()
                        while (frame != null) {
                            if (!isVideoEnabled) {
                                frame = frameQueue.poll()
                                continue
                            }
                            val inputIndex = codec.dequeueInputBuffer(10000)
                            if (inputIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inputIndex)
                                if (inputBuffer != null) {
                                    inputBuffer.clear()
                                    val data = frame.data
                                    if (data.size <= inputBuffer.remaining()) {
                                        inputBuffer.put(data)
                                        val pts = presentationTimeUs
                                        presentationTimeUs += 1_000_000L / fps
                                        codec.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
                                    } else {
                                        Log.w(TAG, "Frame too large ${data.size} vs ${inputBuffer.remaining()}")
                                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                                    }
                                }
                            } else {
                                frameQueue.offer(frame)
                                break
                            }
                            frame = frameQueue.poll()
                        }

                        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        when {
                            outIndex >= 0 -> {
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    bufferInfo.size = 0
                                }
                                if (bufferInfo.size > 0) {
                                    val outBuf = codec.getOutputBuffer(outIndex)
                                    outBuf?.let {
                                        val dup = it.duplicate()
                                        dup.position(bufferInfo.offset)
                                        dup.limit(bufferInfo.offset + bufferInfo.size)
                                        onEncoded(dup, bufferInfo)
                                    }
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                            }
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val format = codec.outputFormat
                                onFormat(format)
                                Log.d(TAG, "Output format changed: $format – CPU pipeline")
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Drain error: ${e.message}")
                        }
                    }
                }
            }
        }

        // New CPU path – ByteArray (YUV420) from ImageAnalysis -> CPU -> MediaCodec
        // Preview is pure CameraX PreviewView, ZERO GL touching preview surface
        fun onFrameAvailable(nv21Data: ByteArray, timestampNs: Long) {
            if (!isRunning.get() || !isVideoEnabled) return
            try {
                val yuv420 = cpuProcessor?.processFrame(nv21Data, width, height) ?: nv21ToYuv420(nv21Data, width, height)
                frameQueue.offer(FrameData(yuv420, timestampNs))
            } catch (e: Exception) {
                Log.w(TAG, "onFrameAvailable CPU failed", e)
            }
        }

        private fun nv21ToYuv420(nv21: ByteArray, width: Int, height: Int): ByteArray {
            val ySize = width * height
            val uvSize = ySize / 4
            if (nv21.size < ySize) return nv21
            val yuv420 = ByteArray(ySize + uvSize * 2)
            System.arraycopy(nv21, 0, yuv420, 0, ySize.coerceAtMost(nv21.size))
            if (nv21.size >= ySize + uvSize * 2) {
                // Convert VU interleaved to U + V planar
                var uPos = ySize
                var vPos = ySize + uvSize
                var i = ySize
                while (i < nv21.size - 1 && uPos < ySize + uvSize && vPos < yuv420.size) {
                    val v = nv21[i]
                    val u = nv21[i + 1]
                    yuv420[uPos++] = u
                    yuv420[vPos++] = v
                    i += 2
                }
            }
            return yuv420
        }

        // Legacy GL method kept for compatibility but no-op – preview is pure CameraX, no GL
        fun renderFrame(texture: GLTexture, timestampNs: Long) {
            Log.w(TAG, "renderFrame(GLTexture) called but now CPU pipeline – ignoring GL texture, use onFrameAvailable(ByteArray)")
        }

        fun updateBitrate(newBitrate: Int) {
            try {
                bitrate = newBitrate
                val bundle = android.os.Bundle()
                bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                mediaCodec?.setParameters(bundle)
                Log.d(TAG, "Bitrate changed to ${newBitrate / 1000}kbps – CPU pipeline")
            } catch (e: Exception) {
                Log.e(TAG, "updateBitrate failed", e)
            }
        }

        @JvmName("setBitrateCompat")
        fun setBitrateCompat(newBitrate: Int) {
            updateBitrate(newBitrate)
        }

        fun setVideoEnabled(enabled: Boolean) {
            isVideoEnabled = enabled
        }

        fun stop() {
            isRunning.set(false)
            drainJob?.cancel()
            runBlocking { drainJob?.join() }
            try {
                mediaCodec?.signalEndOfInputStream()
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (_: Exception) {}
            mediaCodec = null
            frameQueue.clear()
        }
    }

        class AudioEncoderWrapper(
        val bitrate: Int,
        val sampleRate: Int,
        val channels: Int
    ) {
        companion object {
            private const val TAG = "AudioEncoderWrapper"
        }

        private var mediaCodec: MediaCodec? = null
        private var drainJob: Job? = null
        private val drainScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val isRunning = AtomicBoolean(false)
        private var presentationTimeUs = 0L

        fun prepare(): Boolean {
            return try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "prepare failed", e)
                false
            }
        }

        fun start(
            onFormat: (MediaFormat) -> Unit,
            onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
        ) {
            isRunning.set(true)
            drainJob = drainScope.launch {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRunning.get()) {
                    try {
                        val codec = mediaCodec ?: break
                        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        when {
                            outIndex >= 0 -> {
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    bufferInfo.size = 0
                                }
                                if (bufferInfo.size > 0) {
                                    val outBuf = codec.getOutputBuffer(outIndex)
                                    outBuf?.let {
                                        val dup = it.duplicate()
                                        dup.position(bufferInfo.offset)
                                        dup.limit(bufferInfo.offset + bufferInfo.size)
                                        onEncoded(dup, bufferInfo)
                                    }
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                            }
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val format = codec.outputFormat
                                onFormat(format)
                                Log.d(TAG, "Audio format changed: $format")
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Audio drain error: ${e.message}")
                        }
                    }
                }
            }
        }

        fun feedAudio(data: ByteArray, timestampUs: Long) {
            try {
                val codec = mediaCodec ?: return
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(data)
                    val pts = if (timestampUs > 0) timestampUs else presentationTimeUs
                    presentationTimeUs = pts + (data.size * 1_000_000L / (sampleRate * channels * 2))
                    codec.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "feedAudio failed: ${e.message}")
            }
        }

        fun stop() {
            isRunning.set(false)
            drainJob?.cancel()
            runBlocking { drainJob?.join() }
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (_: Exception) {}
            mediaCodec = null
        }
    }

    class RtmpClientWrapper(
        private var checker: ConnectChecker,
        private val width: Int,
        private val height: Int,
        private val fps: Int
    ) {
        private var client: RtmpClient = RtmpClient(checker)

        fun updateChecker(newChecker: ConnectChecker) {
            checker = newChecker
            client = RtmpClient(newChecker).apply {
                setVideoCodec(VideoCodec.H264)
                setAudioCodec(AudioCodec.AAC)
                setVideoResolution(width, height)
                setFps(fps)
            }
        }

        init {
            client.setVideoCodec(VideoCodec.H264)
            client.setAudioCodec(AudioCodec.AAC)
            client.setVideoResolution(width, height)
            client.setFps(fps)
        }

        fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
            client.setVideoInfo(sps, pps, vps)
        }

        fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
            client.setAudioInfo(sampleRate, isStereo)
        }

        fun connect(url: String) {
            client.connect(url)
        }

        fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            client.sendVideo(buffer, info)
        }

        fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            client.sendAudio(buffer, info)
        }

        fun disconnect() {
            try {
                client.disconnect()
            } catch (_: Exception) {}
        }

        fun isStreaming(): Boolean = client.isStreaming
    }

    class SrtClientWrapper(
        private var checker: ConnectChecker,
        private val width: Int,
        private val height: Int,
        private val fps: Int
    ) {
        private var client: SrtClient = SrtClient(checker)

        fun updateChecker(newChecker: ConnectChecker) {
            checker = newChecker
            client = SrtClient(newChecker)
        }

        fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
            client.setVideoInfo(sps, pps, vps)
        }

        fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
            client.setAudioInfo(sampleRate, isStereo)
        }

        fun connect(url: String) {
            client.connect(url)
        }

        fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            client.sendVideo(buffer, info)
        }

        fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            client.sendAudio(buffer, info)
        }

        fun disconnect() {
            try {
                client.disconnect()
            } catch (_: Exception) {}
        }

        fun isStreaming(): Boolean = client.isStreaming
    }

    class RtspClientWrapper(
        private var checker: ConnectChecker,
        private val width: Int,
        private val height: Int,
        private val fps: Int
    ) {
        private var client: RtspClient = RtspClient(checker)

        fun updateChecker(newChecker: ConnectChecker) {
            checker = newChecker
            client = RtspClient(newChecker)
        }

        fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
            client.setVideoInfo(sps, pps, vps)
        }

        fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
            client.setAudioInfo(sampleRate, isStereo)
        }

        fun connect(url: String) {
            client.connect(url)
        }

        fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            client.sendVideo(buffer, info)
        }

        fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            client.sendAudio(buffer, info)
        }

        fun disconnect() {
            try {
                client.disconnect()
            } catch (_: Exception) {}
        }

        fun isStreaming(): Boolean = client.isStreaming
    }

    class RtspServerWrapper(val port: Int = RTSP_SERVER_PORT) {
        companion object {
            private const val TAG = "RtspServer"
            private const val MTU = 1400
        }

        private var serverSocket: ServerSocket? = null
        private var serverJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val clients = Collections.synchronizedList(mutableListOf<RtspClientSession>())
        private val isRunning = AtomicBoolean(false)

        @Volatile private var spsBytes: ByteArray? = null
        @Volatile private var ppsBytes: ByteArray? = null
        @Volatile private var aacConfig: ByteArray? = null

        private val videoSeq = AtomicInteger(0)
        private val audioSeq = AtomicInteger(0)
        private val ssrcVideo = Random().nextInt()
        private val ssrcAudio = Random().nextInt()

        fun setVideoInfo(sps: ByteArray, pps: ByteArray) {
            spsBytes = sps
            ppsBytes = pps
            Log.i(TAG, "Video info set SPS=${sps.size} PPS=${pps.size}")
        }

        fun setAudioInfo(config: ByteArray) {
            aacConfig = config
            Log.i(TAG, "Audio config set size=${config.size}")
        }

        fun start(): Boolean {
            if (isRunning.get()) return true
            return try {
                serverSocket = ServerSocket(port)
                isRunning.set(true)
                serverJob = scope.launch {
                    Log.i(TAG, "RTSP server listening on port $port for OBS/VLC")
                    while (isRunning.get()) {
                        try {
                            val socket = serverSocket?.accept() ?: break
                            val session = RtspClientSession(socket)
                            clients.add(session)
                            launch { handleClient(session) }
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                Log.e(TAG, "Accept failed: ${e.message}")
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RTSP server on $port: ${e.message}", e)
                false
            }
        }

        fun stop() {
            isRunning.set(false)
            try { serverSocket?.close() } catch (_: Exception) {}
            serverJob?.cancel()
            synchronized(clients) {
                for (c in clients) {
                    try { c.socket.close() } catch (_: Exception) {}
                }
                clients.clear()
            }
            Log.i(TAG, "RTSP server stopped")
        }

        fun sendVideo(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
            if (!isRunning.get()) return
            val sps = spsBytes
            val pps = ppsBytes
            if (sps == null || pps == null) return

            val nalUnits = parseNalUnits(data)
            for (nal in nalUnits) {
                sendRtpH264(nal, ptsUs)
            }
        }

        fun sendAudio(data: ByteArray, ptsUs: Long) {
            if (!isRunning.get()) return
            sendRtpAac(data, ptsUs)
        }

        private fun parseNalUnits(data: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            if (data.size < 4) {
                result.add(data)
                return result
            }
            if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
                var start = 0
                for (i in 0 until data.size - 3) {
                    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        if (i != start) {
                            result.add(data.copyOfRange(start, i))
                        }
                        start = i
                    }
                }
                if (start < data.size) {
                    result.add(data.copyOfRange(start, data.size))
                }
            } else if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
                var start = 0
                for (i in 0 until data.size - 2) {
                    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                        if (i != start) {
                            result.add(data.copyOfRange(start, i))
                        }
                        start = i
                    }
                }
                if (start < data.size) {
                    result.add(data.copyOfRange(start, data.size))
                }
            } else {
                var offset = 0
                while (offset + 4 <= data.size) {
                    val length = ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                    if (length <= 0 || offset + 4 + length > data.size) {
                        break
                    }
                    result.add(data.copyOfRange(offset + 4, offset + 4 + length))
                    offset += 4 + length
                }
                if (result.isEmpty()) {
                    result.add(data)
                }
            }
            return result
        }

        private fun sendRtpH264(nal: ByteArray, ptsUs: Long) {
            val pts = (ptsUs * 90) / 1000
            val nalWithoutStart = if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
                nal.copyOfRange(4, nal.size)
            } else if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
                nal.copyOfRange(3, nal.size)
            } else {
                nal
            }
            if (nalWithoutStart.isEmpty()) return

            synchronized(clients) {
                for (client in clients) {
                    if (!client.isPlaying) continue
                    try {
                        if (nalWithoutStart.size <= MTU) {
                            val rtpPacket = createRtpPacket(nalWithoutStart, pts, videoSeq.getAndIncrement(), ssrcVideo, true)
                            client.sendInterleaved(0, rtpPacket)
                        } else {
                            val fragments = fragmentH264(nalWithoutStart)
                            for ((idx, frag) in fragments.withIndex()) {
                                val isLast = idx == fragments.size - 1
                                val rtpPacket = createRtpPacket(frag, pts, videoSeq.getAndIncrement(), ssrcVideo, isLast)
                                client.sendInterleaved(0, rtpPacket)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send video to client: ${e.message}")
                    }
                }
            }
        }

        private fun fragmentH264(nal: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            val nalHeader = nal[0]
            val nri = nalHeader.toInt() and 0x60
            val type = nalHeader.toInt() and 0x1F
            val payload = nal.copyOfRange(1, nal.size)
            var offset = 0
            while (offset < payload.size) {
                val remaining = payload.size - offset
                val chunkSize = min(MTU - 2, remaining)
                val isFirst = offset == 0
                val isLast = offset + chunkSize >= payload.size
                val fuIndicator = (nri or 28).toByte()
                var fuHeader = type.toByte()
                if (isFirst) fuHeader = (fuHeader.toInt() or 0x80).toByte()
                if (isLast) fuHeader = (fuHeader.toInt() or 0x40).toByte()
                val fragment = ByteArray(2 + chunkSize)
                fragment[0] = fuIndicator
                fragment[1] = fuHeader
                System.arraycopy(payload, offset, fragment, 2, chunkSize)
                result.add(fragment)
                offset += chunkSize
            }
            return result
        }

        private fun sendRtpAac(data: ByteArray, ptsUs: Long) {
            val pts = (ptsUs * 48000) / 1000000
            val auHeader = ByteArray(4)
            auHeader[0] = 0x00
            auHeader[1] = 0x10
            auHeader[2] = ((data.size shr 5) and 0xFF).toByte()
            auHeader[3] = ((data.size and 0x1F) shl 3).toByte()

            val payload = ByteArray(auHeader.size + data.size)
            System.arraycopy(auHeader, 0, payload, 0, auHeader.size)
            System.arraycopy(data, 0, payload, auHeader.size, data.size)

            synchronized(clients) {
                for (client in clients) {
                    if (!client.isPlaying) continue
                    try {
                        val rtpPacket = createRtpPacket(payload, pts, audioSeq.getAndIncrement(), ssrcAudio, true)
                        client.sendInterleaved(2, rtpPacket)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send audio to client: ${e.message}")
                    }
                }
            }
        }

        private fun createRtpPacket(payload: ByteArray, timestamp: Long, seq: Int, ssrc: Int, marker: Boolean): ByteArray {
            val packet = ByteArray(12 + payload.size)
            packet[0] = 0x80.toByte()
            packet[1] = (if (marker) 0x80 else 0x00 or 96).toByte()
            packet[2] = ((seq shr 8) and 0xFF).toByte()
            packet[3] = (seq and 0xFF).toByte()
            packet[4] = ((timestamp shr 24) and 0xFF).toByte()
            packet[5] = ((timestamp shr 16) and 0xFF).toByte()
            packet[6] = ((timestamp shr 8) and 0xFF).toByte()
            packet[7] = (timestamp and 0xFF).toByte()
            packet[8] = ((ssrc shr 24) and 0xFF).toByte()
            packet[9] = ((ssrc shr 16) and 0xFF).toByte()
            packet[10] = ((ssrc shr 8) and 0xFF).toByte()
            packet[11] = (ssrc and 0xFF).toByte()
            System.arraycopy(payload, 0, packet, 12, payload.size)
            return packet
        }

        private suspend fun handleClient(session: RtspClientSession) {
            try {
                val reader = BufferedReader(InputStreamReader(session.socket.getInputStream()))
                val writer = BufferedWriter(OutputStreamWriter(session.socket.getOutputStream()))

                while (isRunning.get() && !session.socket.isClosed) {
                    val requestLine = reader.readLine() ?: break
                    if (requestLine.isBlank()) continue

                    val headers = mutableMapOf<String, String>()
                    var line: String?
                    var cseq = "1"
                    var contentLength = 0
                    while (true) {
                        line = reader.readLine() ?: break
                        if (line.isBlank()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            val key = line.substring(0, idx).trim()
                            val value = line.substring(idx + 1).trim()
                            headers[key] = value
                            if (key.equals("CSeq", ignoreCase = true)) cseq = value
                            if (key.equals("Content-Length", ignoreCase = true)) contentLength = value.toIntOrNull() ?: 0
                        }
                    }

                    Log.d(TAG, "RTSP request: $requestLine CSeq:$cseq")

                    when {
                        requestLine.startsWith("OPTIONS") -> {
                            val response = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nPublic: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE\r\n\r\n"
                            writer.write(response)
                            writer.flush()
                        }
                        requestLine.startsWith("DESCRIBE") -> {
                            val sdp = buildSdp()
                            val response = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Base: rtsp://0.0.0.0:$port/\r\nContent-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp"
                            writer.write(response)
                            writer.flush()
                        }
                        requestLine.startsWith("SETUP") -> {
                            val track = if (requestLine.contains("track0") || requestLine.contains("video")) 0 else 1
                            val transport = headers["Transport"] ?: ""
                            val isTcp = transport.contains("TCP") || transport.contains("interleaved")
                            session.isTcp = isTcp
                            val response = if (isTcp) {
                                val interleaved = if (track == 0) "0-1" else "2-3"
                                "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nTransport: RTP/AVP/TCP;unicast;interleaved=$interleaved\r\nSession: ${session.sessionId}\r\n\r\n"
                            } else {
                                val clientPort = Regex("client_port=(\\d+)-(\\d+)").find(transport)?.groupValues
                                val serverPort = if (track == 0) "5000-5001" else "5002-5003"
                                "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nTransport: RTP/AVP;unicast;client_port=${clientPort?.get(1) ?: "5000"}-${clientPort?.get(2) ?: "5001"};server_port=$serverPort\r\nSession: ${session.sessionId}\r\n\r\n"
                            }
                            writer.write(response)
                            writer.flush()
                        }
                        requestLine.startsWith("PLAY") -> {
                            session.isPlaying = true
                            val response = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nSession: ${session.sessionId}\r\nRTP-Info: url=rtsp://0.0.0.0:$port/track0;seq=${videoSeq.get()};rtptime=0,url=rtsp://0.0.0.0:$port/track1;seq=${audioSeq.get()};rtptime=0\r\n\r\n"
                            writer.write(response)
                            writer.flush()
                        }
                        requestLine.startsWith("TEARDOWN") -> {
                            val response = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n"
                            writer.write(response)
                            writer.flush()
                            break
                        }
                        requestLine.startsWith("PAUSE") -> {
                            session.isPlaying = false
                            val response = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nSession: ${session.sessionId}\r\n\r\n"
                            writer.write(response)
                            writer.flush()
                        }
                        else -> {
                            val response = "RTSP/1.0 501 Not Implemented\r\nCSeq: $cseq\r\n\r\n"
                            writer.write(response)
                            writer.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client handler error: ${e.message}")
            } finally {
                try { session.socket.close() } catch (_: Exception) {}
                clients.remove(session)
                Log.i(TAG, "RTSP client disconnected, remaining: ${clients.size}")
            }
        }

        private fun buildSdp(): String {
            val sps = spsBytes
            val pps = ppsBytes
            val sprop = if (sps != null && pps != null) {
                val spsB64 = Base64.encodeToString(sps, Base64.NO_WRAP)
                val ppsB64 = Base64.encodeToString(pps, Base64.NO_WRAP)
                "$spsB64,$ppsB64"
            } else {
                ""
            }
            val profileLevelId = if (sps != null && sps.size >= 4) {
                String.format("%02X%02X%02X", sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF)
            } else {
                "42E01F"
            }

            val aacConfigB64 = aacConfig?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "1210"
            val sampleRate = 48000
            val channels = 1

            return """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=VCam
                c=IN IP4 0.0.0.0
                t=0 0
                a=control:*
                a=range:npt=0-
                m=video 0 RTP/AVP 96
                a=rtpmap:96 H264/90000
                a=fmtp:96 packetization-mode=1; sprop-parameter-sets=$sprop; profile-level-id=$profileLevelId
                a=control:track0
                m=audio 0 RTP/AVP 97
                a=rtpmap:97 MPEG4-GENERIC/$sampleRate/$channels
                a=fmtp:97 streamtype=5; profile-level-id=1; mode=AAC-hbr; config=$aacConfigB64; sizeLength=13; indexLength=3; indexDeltaLength=3
                a=control:track1
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
        }

        private class RtspClientSession(val socket: Socket) {
            val sessionId: String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            var isTcp: Boolean = true
            var isPlaying: Boolean = false

            fun sendInterleaved(channel: Int, rtpPacket: ByteArray) {
                val out = socket.getOutputStream()
                out.write(0x24)
                out.write(channel)
                out.write((rtpPacket.size shr 8) and 0xFF)
                out.write(rtpPacket.size and 0xFF)
                out.write(rtpPacket)
                out.flush()
            }
        }
    }
}
