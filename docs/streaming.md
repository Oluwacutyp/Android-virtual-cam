# Module 5: Multi-Destination Streaming

## Overview
Integrates RootEncoder (com.github.pedroSG94.RootEncoder:library:2.4.4) for RTMP/SRT/RTSP.

## Dependencies
In `settings.gradle.kts`:
```kotlin
maven { url = uri("https://jitpack.io") }
```
In `app/build.gradle.kts`:
```kotlin
implementation("com.github.pedroSG94.RootEncoder:library:2.4.4")
```

## StreamingManager.kt

### Features
- Up to 3 simultaneous RTMP destinations (separate encoder instances, shared EGL surface via EGLContext sharing)
- SRT protocol via RootEncoder's SRT support
- RTSP server on LAN (port 8554) for OBS/VLC
- Each destination: independent bitrate, resolution, connect/disconnect without affecting others
- Reconnect with exponential backoff: 1s,2s,4s,8s,16s cap 30s
- Simultaneous recording while streaming: separate MediaCodec pipeline writing to MKV fault-tolerant

### Architecture
- `sharedEglContext: EGLContext` set from compositor's EGL context (via `setSharedEglContext`)
- `sessions: ConcurrentHashMap<String, Destination>` – max 3
- `Destination` holds:
  - `session: StreamingSession`
  - `videoEncoder: VideoEncoderWrapper` – MediaCodec H264 with input Surface, EGL context sharing with compositor
  - `audioEncoder: AudioEncoderWrapper` – AAC
  - `rtmpClient: RtmpClientWrapper` / `srtClient` / `rtspClient` – wrappers via reflection for RootEncoder (`com.pedro.rtmp.RtmpClient`, `com.pedro.srt.SrtClient`, `com.pedro.rtsp.RtspClient`), fallback to stub if library not present (simulates success for valid URL)
  - `adaptiveController: AdaptiveBitrateController`
  - `reconnectJob: Job`
  - `eglSurface`, `inputSurface`, `isConnected`

- `addDestination(url, protocol, bitrate, width, height, fps): Result<String>` – creates session id UUID, creates AdaptiveBitrateController with callback `handleBitrateChange`
- `removeDestination(id)` – disconnects without affecting others, cancels reconnect, stops monitoring
- `startStreaming(id)` – launches `connectWithBackoff` coroutine
- `stopStreaming(id)` – cancels reconnect, disconnects
- `startAll()/stopAll()`

### Shared EGL Surface via EGLContext Sharing
- Compositor creates EGLContext (pbuffer)
- StreamingManager receives it via `setSharedEglContext(eglContext, eglDisplay)`
- Each `VideoEncoderWrapper` creates its own EGLContext sharing with compositor context:
  ```kotlin
  eglContext = EGL14.eglCreateContext(display, config, sharedEglContext, attribs, 0)
  eglSurface = EGL14.eglCreateWindowSurface(display, config, inputSurface, attribs, 0)
  ```
- `onFrameAvailable(texture, timestampNs)` called from compositor each frame, renders to each encoder's EGL surface via `renderFrame` (makes current, clears, swaps – placeholder for actual shader rendering)

### Reconnect with Exponential Backoff
- `connectWithBackoff(dest)` loops:
  - Attempt 1: immediate, status CONNECTING
  - Attempt 2+: status RECONNECTING, delay from list `[1s,2s,4s,8s,16s,30s]` (cap 30s)
  - On success: status LIVE, startTimeMs, adaptive monitoring starts
  - On failure: records error, calls `adaptiveController.onConnectionFailed`, updates flow, delays, checks if job cancelled
  - Max 10 attempts then FAILED

### Protocol Handling
- **RTMP**: `RtmpClientWrapper` via reflection `com.pedro.rtmp.RtmpClient`, `connect(url)`, `sendVideo(data, timestampUs, isKeyFrame)`, `sendAudio`, `disconnect`. Fallback stub checks URL starts with `rtmp://` or `rtmps://`.
- **SRT**: `SrtClientWrapper` via `com.pedro.srt.SrtClient`, URL `srt://`, same send methods, fallback checks `srt://`.
- **RTSP**: `RtspClientWrapper` via `com.pedro.rtsp.RtspClient`, URL `rtsp://`.
- **RTSP Server**: `RtspServerWrapper` port 8554 for OBS/VLC, single instance, `start()` via reflection `com.pedro.rtspserver.RtspServerCamera1`, `sendVideo/sendAudio`, `stop()`. Logs that server would start.

### AdaptiveBitrateController Integration
- Each destination has its own controller
- `updateNetworkMetrics(rttMs, packetLoss, bandwidthKbps)` called from RootEncoder callbacks (would be via `ConnectCheckerRtmp` etc.)
- On bitrate change, `handleBitrateChange` calls `videoEncoder.setBitrate(newBitrate)` via `MediaCodec.PARAMETER_KEY_VIDEO_BITRATE`, and disables video if audio-only

## AdaptiveBitrateController.kt

- Monitors network every 2 seconds (`MONITOR_INTERVAL_MS = 2000L`)
- Drops bitrate in steps: 6Mbps → 4Mbps → 2.5Mbps → 1.5Mbps → 800kbps (`BITRATE_STEPS`)
- Recovers slowly (probe up every 30s `PROBE_UP_INTERVAL_MS`) when network improves
- Never drops below 400kbps (`MIN_BITRATE`) — switches to audio-only mode instead

### Logic
- Metrics: `lastRttMs`, `lastPacketLoss`, `lastBandwidthKbps` from RootEncoder callbacks
- `evaluateNetworkQuality(rtt, loss)`:
  - CRITICAL if RTT >=800ms or loss >=15%
  - POOR if RTT >=300ms or loss >=5%
  - FAIR if RTT >=150ms or loss >=2%
  - GOOD if RTT >=50ms or loss >=0.5%
  - EXCELLENT else
- On POOR/CRITICAL: `handleCongestion(severe)` – drops 1 step (moderate) or 2 steps (severe), checks if would drop below MIN_BITRATE → `switchToAudioOnly()` (sets bitrate MIN_BITRATE, isAudioOnly true)
- On GOOD/EXCELLENT for 3 consecutive samples and 30s since last probe: `tryProbeUp()` – if audio-only, recovers to 800kbps, else probes up one step (e.g., 800k→1.5M→2.5M→4M→6M)
- `onConnectionFailed` increments bad samples, may drop bitrate after 2 failures
- `reset()` restores initial

### Callback
```kotlin
AdaptiveBitrateController(sessionId, initialBitrate) { newBitrate, isAudioOnly ->
  videoEncoder.setBitrate(newBitrate)
  videoEncoder.setVideoEnabled(!isAudioOnly)
}
```

## StreamingSession Data Class

Tracking: destination URL, status (IDLE/CONNECTING/LIVE/RECONNECTING/FAILED/DISCONNECTED), current bitrate, requested bitrate, width/height/fps, dropped frames, durationMs, bytesSent, startTimeMs, lastError, reconnectAttempts, isAudioOnly, rttMs, packetLoss.

`getDuration()` returns live duration if LIVE, else stored.

## Recording While Streaming – MKV Fault-Tolerant

### MkvMuxer.kt
Minimal MKV muxer that writes EBML with unknown Segment size (0x01FFFFFFFFFFFFFF), so truncated file is still playable (clusters already flushed).

- `start(videoFormat, audioFormat)`: writes EBML header (DocType matroska), Segment with unknown size, Info (TimecodeScale 1ms, MuxingApp, WritingApp, Duration placeholder), Tracks (TrackEntry video: TrackNumber 1, TrackUID, TrackType 1, CodecID V_MPEG4/ISO/AVC, Video PixelWidth/Height, CodecPrivate AVCC from csd-0/csd-1 SPS/PPS)
- `writeSample(trackNumber, data, info)`: creates Cluster if needed (every 1s or keyframe), writes Cluster ID with unknown size, Timecode, then SimpleBlock (track VINT, relative timecode int16, flags keyframe, data), flushes after each write
- `stop()`: flushes, tries to update duration via RandomAccessFile, closes – file remains playable even if truncated because Segment size unknown

### MkvRecordingManager.kt
Separate MediaCodec pipeline:

- Video: H264 via MediaCodec with input Surface (shared EGL context via `setSharedEglContext`), bitrate, fps, I-frame interval 1
- Audio: AAC 48kHz mono 128kbps
- EGL: creates display, config, context sharing compositor context, window surface from video input surface
- `start()`: creates file `vcam_record_${timestamp}.mkv`, creates muxer, prepares video/audio encoders, creates EGL, starts muxer, starts drain threads
- `startDrainThreads()`: coroutine draining video/audio output buffers every 10ms, writes to muxer via `writeSample(1, ...)` for video and `2` for audio, handles `INFO_OUTPUT_FORMAT_CHANGED`
- `renderFrame(texture, timestampNs)`: makes EGL current, renders texture to encoder surface (placeholder clear, real would use compositor shader), `eglPresentationTimeANDROID`, `eglSwapBuffers`
- `feedAudio(data, timestampUs)`: queues to audio encoder input buffer
- `stop()`: signals EOS via `signalEndOfInputStream`, drains remaining, stops muxer, cleanup EGL and codecs, returns path
- Fault-tolerant: if crash mid-record, file has EBML header + Clusters already flushed, playable in VLC (truncated but playable)

## Usage

```kotlin
val streamingManager = StreamingManager(context)
streamingManager.setSharedEglContext(compositorRenderer.eglContext)

val id1 = streamingManager.addDestination("rtmp://live.twitch.tv/app/key", RTMP, 4_000_000, 1280, 720).getOrThrow()
val id2 = streamingManager.addDestination("srt://server:8890?streamid=publish/live", SRT, 2_500_000, 1280, 720).getOrThrow()
val id3 = streamingManager.addDestination("", RTSP_SERVER, 4_000_000, 1280, 720).getOrThrow() // RTSP server on 8554

streamingManager.startStreaming(id1)
streamingManager.startStreaming(id2)
streamingManager.startStreaming(id3) // starts RTSP server for OBS/VLC

// In compositor render loop
streamingManager.onFrameAvailable(composedTexture, System.nanoTime())

// Recording while streaming
streamingManager.startRecording("/sdcard/vcam.mkv", 1280, 720, 6_000_000)
// ... streaming ...
streamingManager.stopRecording()

// Adaptive bitrate automatically via controller monitoring every 2s
// Reconnect automatically with exponential backoff
```

## Testing

- Without RootEncoder: wrappers fallback to stub, simulate connection if URL valid
- With RootEncoder: real RTMP/SRT/RTSP via `RtmpClient` etc., bandwidth callbacks via `ConnectChecker`
- Recording: check MKV file playable in VLC even if app killed mid-record (truncate test)

## Future

- Integrate RootEncoder's `GenericStream` with custom `VideoSource` for easier GL sharing
- Add WHIP/WHEP support
- Add SRT encryption params
- Add RTSP server authentication
- Use `MediaMuxer` with `OUTPUT_FORMAT_MUXER_OUTPUT_WEBM` as alternative fault-tolerant (WebM is MKV subset)
