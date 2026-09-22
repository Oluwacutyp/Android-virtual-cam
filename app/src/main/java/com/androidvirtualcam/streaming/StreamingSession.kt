package com.androidvirtualcam.streaming

import kotlinx.serialization.Serializable

/**
 * StreamingSession data class tracking: destination URL, status (connecting/live/reconnecting/failed),
 * current bitrate, dropped frames, duration, bytes sent.
 */

enum class StreamingStatus {
    IDLE,
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
    DISCONNECTED
}

@Serializable
data class StreamingSession(
    val id: String,
    val url: String,
    val protocol: StreamingProtocol = StreamingProtocol.RTMP,
    var status: StreamingStatus = StreamingStatus.IDLE,
    var currentBitrate: Int = 4_000_000,
    var requestedBitrate: Int = 4_000_000,
    var width: Int = 1280,
    var height: Int = 720,
    var fps: Int = 30,
    var droppedFrames: Long = 0,
    var durationMs: Long = 0,
    var bytesSent: Long = 0,
    var startTimeMs: Long = 0,
    var lastError: String? = null,
    var reconnectAttempts: Int = 0,
    var isAudioOnly: Boolean = false,
    var rttMs: Long = 0,
    var packetLoss: Float = 0f
) {
    fun getDuration(): Long {
        return if (status == StreamingStatus.LIVE && startTimeMs > 0) {
            System.currentTimeMillis() - startTimeMs
        } else durationMs
    }

    fun toConfig(): StreamConfig {
        return StreamConfig(
            url = url,
            protocol = protocol,
            videoWidth = width,
            videoHeight = height,
            videoBitrate = currentBitrate,
            frameRate = fps
        )
    }
}
