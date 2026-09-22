package com.androidvirtualcam.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.srt.srt.SrtClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

enum class StreamingProtocol {
    RTMP,
    WEBRTC,
    SRT,
    RTSP,
    RTSP_SERVER,
    CUSTOM
}

data class StreamConfig(
    val url: String,
    val protocol: StreamingProtocol = StreamingProtocol.RTMP,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val videoBitrate: Int = 4_000_000,
    val frameRate: Int = 30,
    val audioBitrate: Int = 128_000,
    val enableAudio: Boolean = true
)

sealed class StreamingState {
    object Idle : StreamingState()
    object Connecting : StreamingState()
    object Connected : StreamingState()
    data class Streaming(val bytesSent: Long, val durationMs: Long) : StreamingState()
    data class Error(val message: String, val throwable: Throwable? = null) : StreamingState()
    object Disconnected : StreamingState()
}

interface StreamingClient {
    val state: Flow<StreamingState>
    suspend fun connect(config: StreamConfig): Result<Unit>
    suspend fun disconnect(): Result<Unit>
    fun isConnected(): Boolean
    fun onVideoFrame(data: ByteArray, timestampUs: Long, isKeyFrame: Boolean)
    fun onAudioFrame(data: ByteArray, timestampUs: Long)
}

object StreamingClientFactory {
    fun create(protocol: StreamingProtocol): StreamingClient {
        return when (protocol) {
            StreamingProtocol.RTMP -> RtmpStreamingClient()
            StreamingProtocol.SRT -> SrtStreamingClient()
            StreamingProtocol.RTSP -> RtspStreamingClient()
            StreamingProtocol.WEBRTC -> WebRtcStreamingClient()
            else -> RtmpStreamingClient()
        }
    }
}

class RtmpStreamingClient : StreamingClient {
    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    override val state: Flow<StreamingState> = _state

    private var rtmpClient: RtmpClient? = null
    private var config: StreamConfig? = null
    private var bytesSent: Long = 0
    private var startTime: Long = 0
    private var sps: ByteBuffer? = null
    private var pps: ByteBuffer? = null

    override suspend fun connect(config: StreamConfig): Result<Unit> {
        if (!config.url.startsWith("rtmp://") && !config.url.startsWith("rtmps://")) {
            _state.value = StreamingState.Error("Invalid RTMP URL, must start with rtmp:// or rtmps://")
            return Result.failure(IllegalArgumentException("Invalid RTMP URL"))
        }
        _state.value = StreamingState.Connecting
        this.config = config
        bytesSent = 0
        startTime = System.currentTimeMillis()

        val deferred = CompletableDeferred<Boolean>()
        var errorReason: String? = null

        val checker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                Log.i("RtmpStreamingClient", "Connection started: $url")
            }
            override fun onConnectionSuccess() {
                Log.i("RtmpStreamingClient", "Connection success")
                _state.value = StreamingState.Connected
                _state.value = StreamingState.Streaming(0, 0)
                if (!deferred.isCompleted) deferred.complete(true)
            }
            override fun onConnectionFailed(reason: String) {
                Log.w("RtmpStreamingClient", "Connection failed: $reason")
                errorReason = reason
                _state.value = StreamingState.Error(reason)
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onNewBitrate(bitrate: Long) {
                bytesSent = bitrate
                val duration = System.currentTimeMillis() - startTime
                _state.value = StreamingState.Streaming(bytesSent, duration)
            }
            override fun onDisconnect() {
                Log.i("RtmpStreamingClient", "Disconnected")
                _state.value = StreamingState.Disconnected
            }
            override fun onAuthError() {
                errorReason = "Auth error"
                _state.value = StreamingState.Error("Auth error")
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onAuthSuccess() {
                Log.i("RtmpStreamingClient", "Auth success")
            }
        }

        val client = RtmpClient(checker).apply {
            setVideoCodec(VideoCodec.H264)
            setAudioCodec(AudioCodec.AAC)
            setVideoResolution(config.videoWidth, config.videoHeight)
            setFps(config.frameRate)
            setAudioInfo(48000, false)
        }
        rtmpClient = client

        client.connect(config.url)

        val success = withTimeoutOrNull(10000) { deferred.await() } ?: false
        return if (success) {
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException(errorReason ?: "Connection failed"))
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        try {
            rtmpClient?.disconnect()
        } catch (_: Exception) {}
        rtmpClient = null
        _state.value = StreamingState.Disconnected
        config = null
        return Result.success(Unit)
    }

    override fun isConnected(): Boolean = rtmpClient?.isStreaming == true

    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer) {
        this.sps = sps
        this.pps = pps
        rtmpClient?.setVideoInfo(sps, pps, null)
    }

    override fun onVideoFrame(data: ByteArray, timestampUs: Long, isKeyFrame: Boolean) {
        val client = rtmpClient ?: return
        if (!client.isStreaming) return
        val buffer = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, timestampUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        client.sendVideo(buffer, info)
        bytesSent += data.size
        val duration = System.currentTimeMillis() - startTime
        _state.value = StreamingState.Streaming(bytesSent, duration)
    }

    override fun onAudioFrame(data: ByteArray, timestampUs: Long) {
        val client = rtmpClient ?: return
        if (!client.isStreaming) return
        val buffer = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, timestampUs, 0)
        }
        client.sendAudio(buffer, info)
        bytesSent += data.size
    }
}

class SrtStreamingClient : StreamingClient {
    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    override val state: Flow<StreamingState> = _state

    private var srtClient: SrtClient? = null
    private var config: StreamConfig? = null
    private var bytesSent: Long = 0
    private var startTime: Long = 0

    override suspend fun connect(config: StreamConfig): Result<Unit> {
        if (!config.url.startsWith("srt://")) {
            _state.value = StreamingState.Error("Invalid SRT URL, must start with srt://")
            return Result.failure(IllegalArgumentException("Invalid SRT URL"))
        }
        _state.value = StreamingState.Connecting
        this.config = config
        bytesSent = 0
        startTime = System.currentTimeMillis()

        val deferred = CompletableDeferred<Boolean>()
        var errorReason: String? = null

        val checker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                Log.i("SrtStreamingClient", "Connection started: $url")
            }
            override fun onConnectionSuccess() {
                Log.i("SrtStreamingClient", "Connection success")
                _state.value = StreamingState.Connected
                _state.value = StreamingState.Streaming(0, 0)
                if (!deferred.isCompleted) deferred.complete(true)
            }
            override fun onConnectionFailed(reason: String) {
                Log.w("SrtStreamingClient", "Connection failed: $reason")
                errorReason = reason
                _state.value = StreamingState.Error(reason)
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onNewBitrate(bitrate: Long) {
                bytesSent = bitrate
                val duration = System.currentTimeMillis() - startTime
                _state.value = StreamingState.Streaming(bytesSent, duration)
            }
            override fun onDisconnect() {
                _state.value = StreamingState.Disconnected
            }
            override fun onAuthError() {
                errorReason = "Auth error"
                _state.value = StreamingState.Error("Auth error")
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onAuthSuccess() {}
        }

        val client = SrtClient(checker)
        srtClient = client
        client.connect(config.url)

        val success = withTimeoutOrNull(10000) { deferred.await() } ?: false
        return if (success) Result.success(Unit) else Result.failure(RuntimeException(errorReason ?: "SRT connection failed"))
    }

    override suspend fun disconnect(): Result<Unit> {
        try { srtClient?.disconnect() } catch (_: Exception) {}
        srtClient = null
        _state.value = StreamingState.Disconnected
        return Result.success(Unit)
    }

    override fun isConnected(): Boolean = srtClient?.isStreaming == true

    override fun onVideoFrame(data: ByteArray, timestampUs: Long, isKeyFrame: Boolean) {
        val client = srtClient ?: return
        if (!client.isStreaming) return
        val buffer = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, timestampUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        client.sendVideo(buffer, info)
        bytesSent += data.size
    }

    override fun onAudioFrame(data: ByteArray, timestampUs: Long) {
        val client = srtClient ?: return
        if (!client.isStreaming) return
        val buffer = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, timestampUs, 0)
        }
        client.sendAudio(buffer, info)
        bytesSent += data.size
    }
}

class RtspStreamingClient : StreamingClient {
    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    override val state: Flow<StreamingState> = _state

    private var rtspClient: RtspClient? = null
    private var config: StreamConfig? = null
    private var bytesSent: Long = 0
    private var startTime: Long = 0

    override suspend fun connect(config: StreamConfig): Result<Unit> {
        if (!config.url.startsWith("rtsp://")) {
            _state.value = StreamingState.Error("Invalid RTSP URL")
            return Result.failure(IllegalArgumentException("Invalid RTSP URL"))
        }
        _state.value = StreamingState.Connecting
        this.config = config
        bytesSent = 0
        startTime = System.currentTimeMillis()

        val deferred = CompletableDeferred<Boolean>()
        var errorReason: String? = null

        val checker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                Log.i("RtspStreamingClient", "Connection started: $url")
            }
            override fun onConnectionSuccess() {
                Log.i("RtspStreamingClient", "Connection success")
                _state.value = StreamingState.Connected
                _state.value = StreamingState.Streaming(0, 0)
                if (!deferred.isCompleted) deferred.complete(true)
            }
            override fun onConnectionFailed(reason: String) {
                Log.w("RtspStreamingClient", "Connection failed: $reason")
                errorReason = reason
                _state.value = StreamingState.Error(reason)
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onNewBitrate(bitrate: Long) {
                bytesSent = bitrate
                val duration = System.currentTimeMillis() - startTime
                _state.value = StreamingState.Streaming(bytesSent, duration)
            }
            override fun onDisconnect() {
                _state.value = StreamingState.Disconnected
            }
            override fun onAuthError() {
                errorReason = "Auth error"
                _state.value = StreamingState.Error("Auth error")
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onAuthSuccess() {}
        }

        val client = RtspClient(checker)
        rtspClient = client
        client.connect(config.url)

        val success = withTimeoutOrNull(10000) { deferred.await() } ?: false
        return if (success) Result.success(Unit) else Result.failure(RuntimeException(errorReason ?: "RTSP connection failed"))
    }

    override suspend fun disconnect(): Result<Unit> {
        try { rtspClient?.disconnect() } catch (_: Exception) {}
        rtspClient = null
        _state.value = StreamingState.Disconnected
        return Result.success(Unit)
    }

    override fun isConnected(): Boolean = rtspClient?.isStreaming == true

    override fun onVideoFrame(data: ByteArray, timestampUs: Long, isKeyFrame: Boolean) {
        val client = rtspClient ?: return
        if (!client.isStreaming) return
        val buffer = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, timestampUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        client.sendVideo(buffer, info)
        bytesSent += data.size
    }

    override fun onAudioFrame(data: ByteArray, timestampUs: Long) {
        val client = rtspClient ?: return
        if (!client.isStreaming) return
        val buffer = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, timestampUs, 0)
        }
        client.sendAudio(buffer, info)
        bytesSent += data.size
    }
}

class WebRtcStreamingClient : StreamingClient {
    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    override val state: Flow<StreamingState> = _state

    override suspend fun connect(config: StreamConfig): Result<Unit> {
        if (config.url.isBlank()) {
            _state.value = StreamingState.Error("WebRTC signaling URL required")
            return Result.failure(IllegalArgumentException("Missing signaling URL"))
        }
        _state.value = StreamingState.Connecting
        return try {
            val factory = org.webrtc.PeerConnectionFactory.builder().createPeerConnectionFactory()
            val pc = factory.createPeerConnection(
                listOf(org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
                object : org.webrtc.PeerConnection.Observer {
                    override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) {}
                    override fun onConnectionChange(newState: org.webrtc.PeerConnection.PeerConnectionState?) {
                        when (newState) {
                            org.webrtc.PeerConnection.PeerConnectionState.CONNECTED -> {
                                _state.value = StreamingState.Connected
                            }
                            org.webrtc.PeerConnection.PeerConnectionState.FAILED -> {
                                _state.value = StreamingState.Error("WebRTC connection failed")
                            }
                            org.webrtc.PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                _state.value = StreamingState.Disconnected
                            }
                            else -> {}
                        }
                    }
                    override fun onSignalingChange(newState: org.webrtc.PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(newState: org.webrtc.PeerConnection.IceConnectionState?) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(newState: org.webrtc.PeerConnection.IceGatheringState?) {}
                    override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                    override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
                }
            )
            _state.value = StreamingState.Error("WebRTC requires signaling backend implementation. PeerConnection created: $pc")
            Result.failure(UnsupportedOperationException("WebRTC signaling not configured, PeerConnection ready"))
        } catch (e: Exception) {
            _state.value = StreamingState.Error("WebRTC init failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        _state.value = StreamingState.Disconnected
        return Result.success(Unit)
    }

    override fun isConnected(): Boolean = false

    override fun onVideoFrame(data: ByteArray, timestampUs: Long, isKeyFrame: Boolean) {
    }

    override fun onAudioFrame(data: ByteArray, timestampUs: Long) {
    }
}
