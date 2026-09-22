# Streaming Integration Guide

## Abstraction

`StreamingClient` interface decouples UI/recording from protocol.

```kotlin
interface StreamingClient {
  val state: Flow<StreamingState>
  suspend fun connect(config: StreamConfig): Result<Unit>
  suspend fun disconnect()
  fun onVideoFrame(data: ByteArray, tsUs: Long, isKey: Boolean)
}
```

## RTMP (Recommended for MVP)

- **Library:** HaishinKit (RTMP) – https://github.com/shogo4405/HaishinKit.kt – MIT, or `com.github.antmedia:rtmp-client`
- **Steps:**
  1. Add dependency: `implementation("com.haishinkit:haishinkit:1.6.5")`
  2. In `RtmpStreamingClient.connect()`:
     - Create `RtmpConnection`, `RtmpStream(connection)`
     - `connection.connect(url)` where url = `rtmp://server/app`
     - `stream.publish(streamKey)`
  3. Feed video: `RecordingManager` already encodes AVC. Instead of MediaMuxer, get encoded ByteBuffer and call `onVideoFrame`.
  4. For audio: create `AudioRecord`, encode AAC via MediaCodec, feed.
  5. Handle reconnect with exponential backoff.

- **Testing:** Use `rtmp://a.rtmp.youtube.com/live2/KEY` or local nginx-rtmp: `docker run -p 1935:1935 alfg/nginx-rtmp`

## WebRTC

- **Library:** `org.webrtc:google-webrtc:1.0.32006`
- **Why complex:** Needs signaling server (WebSocket) to exchange SDP.
- **Steps:**
  1. `PeerConnectionFactory.initialize()`
  2. Custom `VideoCapturer` that receives our GL frames:
     ```kotlin
     class ComposedFrameCapturer : VideoCapturer {
       fun onComposedFrame(bitmap) { /* convert to VideoFrame and deliver */ }
     }
     ```
  3. Create `PeerConnection`, add video track from capturer, audio track from `AudioSource`
  4. Create offer, setLocalDescription, send SDP via signaling, receive answer.
  5. ICE candidates exchange.

- **Signaling:** Minimal Node.js server with `ws`, or use Firebase, or existing SFU like Janus, mediasoup.
- **MVP stub explains this.**

## SRT (Future)

- Use `srt` library via NDK or `com.github.kaltura:SRT` – better for lossy networks.

## How to switch protocols

```kotlin
val client = StreamingClientFactory.create(StreamingProtocol.RTMP)
client.connect(StreamConfig(url = "rtmp://..."))
```

## Security

- Never hardcode stream keys. Use `EncryptedSharedPreferences` or user input.
- Use `rtmps://` for TLS.

## Performance

- Reuse encoder: MediaCodec can feed both muxer and streaming (duplicate ByteBuffer).
- Bitrate adaptation: monitor `StreamingState.Streaming.bytesSent` and thermal status, lower bitrate if needed.
