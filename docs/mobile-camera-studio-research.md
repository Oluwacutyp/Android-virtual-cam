# Mobile Camera Studio – Research Document

Date: 2026-09-21
Target: Android-first MVP (name indicates Android), with notes on iOS parity.

## 1. Clarifying Four Distinct Product Types

### 1.1 Standalone mobile streaming app
- **Definition:** App captures camera/mic, composites locally, encodes and pushes to RTMP/SRT/WebRTC server. Viewer watches via player, not via fake camera.
- **Feasibility on Android:** ✅ Fully supported. CameraX / Camera2 + MediaCodec + RTMP/WebRTC libraries. No special permissions.
- **Examples:** Streamlabs, Larix Broadcaster, OBS-like mobile apps.

### 1.2 Camera-compositing SDK
- **Definition:** Library embedded in other apps to provide studio features.
- **Feasibility:** ✅ Supported. Can be packaged as AAR (Android) / XCFramework (iOS). Must expose Camera, Renderer, Scene model. GPU rendering via OpenGL ES.
- **Risk:** Maintaining ABI, threading, and lifecycle is non-trivial.

### 1.3 Network camera source that sends video to a desktop
- **Definition:** Phone acts as remote camera for PC (like DroidCam, EpocCam). Phone streams over USB/Wi-Fi; desktop driver appears as webcam.
- **Feasibility:** ✅ Partial. Mobile side easy (capture + encode + network). Desktop side requires driver:
  - Windows: DirectShow filter or Media Foundation virtual camera (Windows 10 2004+ supports user-mode virtual camera via IMFVirtualCamera). Requires installer.
  - macOS: CoreMediaIO DAL plugin (deprecated in macOS 12+), now Camera Extension with Continuity. Requires companion app + system extension.
  - Linux: v4l2loopback.
- **Limitation:** Mobile app alone cannot create desktop virtual cam; companion desktop software is mandatory.

### 1.4 True system-wide virtual camera on mobile
- **Definition:** App exposes a fake camera device that any other Android/iOS app sees as a camera choice (like OBS Virtual Camera on desktop).
- **Feasibility on unmodified Android/iOS:** ❌ **Not possible.**
  - **Android:** Camera service is privileged. Apps cannot register CameraProvider. Camera2/CameraX only consumes, not provides. No public API for `ICameraService` injection. Root + Magisk module or custom ROM could hook HAL, but not Play Store viable. Android 14 restricts further background camera access.
  - **iOS:** App sandbox prohibits registering AVCaptureDevice. Only Apple can provide virtual cameras (Continuity Camera). No App Store app can inject into other apps' camera picker.
  - **Documentation:** Android camera architecture https://source.android.com/docs/core/camera , iOS AVFoundation capture setup https://developer.apple.com/documentation/avfoundation/capture_setup
- **Conclusion:** Must NOT promise system-wide virtual camera on stock devices. Focus on (1) and optionally (3).

---

## 2. Platform Choice for MVP

**Decision: Android-first, Kotlin, CameraX + OpenGL ES, minSdk 26 (Android 8.0)**

Rationale:
- Repository name `Android-virtual-cam` already signals Android.
- CameraX provides lifecycle-aware wrapper over Camera2, handles rotation, use-cases (Preview, ImageAnalysis, VideoCapture).
- Larger device diversity for testing permission/thermal edge cases.
- No Xcode/macOS requirement for CI.
- Cross-platform alternatives evaluated:
  - Flutter + camera plugin: Limited to basic preview, no GPU custom compositing without native code.
  - React Native + VisionCamera: Better, supports frame processors (JSI), but still needs native modules for OpenGL.
  - Kotlin Multiplatform Mobile: Camera still platform-specific.
- Therefore: Native Android MVP first, then extract compositing engine to KMP or Flutter plugin later.

iOS parity notes included, but implementation postponed.

---

## 3. Camera APIs and Real-time Frame Processing

### Android
- **CameraX (recommended):** https://developer.android.com/media/camera/camerax
  - Use cases: Preview (Surface), VideoCapture (MediaRecorder/MediaStore), ImageAnalysis (YUV_420_888 frames for ML/effects).
  - Can process frames in real time via `ImageAnalysis.setAnalyzer(Executor, Analyzer)` at ~30 FPS if analysis is GPU-offloaded.
  - Supports front/rear switching, torch via `CameraControl.enableTorch()`, zoom, exposure.
- **Camera2 (lower level):** https://developer.android.com/media/camera/camera2
  - Full manual control, concurrent streams. Required if CameraX insufficient (e.g., need RAW, high-speed).
  - Real-time: Yes, via `ImageReader` + `SurfaceTexture`.
- **Camera1:** Deprecated.

**Real-time feasibility:** Yes, but must avoid CPU copy of YUV. Use GPU path: Camera → SurfaceTexture (OES texture) → OpenGL shader → encoder input Surface.

### iOS (for reference)
- AVFoundation `AVCaptureSession` + `AVCaptureVideoDataOutput`: https://developer.apple.com/documentation/avfoundation/avcapturesession
- Real-time via `sampleBufferDelegate` on dedicated queue, plus Metal rendering.

---

## 4. GPU-Accelerated Rendering Options

### Android
- **OpenGL ES 2.0/3.0:** Mature, widely supported. `GLSurfaceView` or custom `TextureView` + `EGL`. Can render camera OES texture, compose layers, apply filters via fragment shaders.
  - Doc: https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl
  - Doc: https://developer.android.com/reference/android/graphics/SurfaceTexture
- **Vulkan:** Higher performance but more boilerplate, not needed for MVP. minSdk 24+ but driver variance.
- **MediaCodec input Surface:** Encoder can consume OpenGL-rendered Surface directly, zero-copy: https://developer.android.com/reference/android/media/MediaCodec#createInputSurface()
- **RenderScript / Vulkan Compute / OpenGL compute:** Deprecated or overkill. Use fragment shaders for filters.

**Recommended MVP stack:**
```
CameraX Preview → SurfaceTexture (OES) → GL Renderer (fullscreen quad + layer quads) → GLSurfaceView display
                                                ↓
                                        MediaCodec input Surface (for recording)
```

Alternative: Use `Grafika` (Google) as reference for continuous capture + recording: https://github.com/google/grafika

### iOS equivalent
- Metal + Core Image + `AVVideoComposition`: https://developer.apple.com/documentation/metal
- GPUImage3 is common but Metal native preferred.

---

## 5. Composing Camera Frames, Images, Text, Effects Efficiently

**Architecture:**
- Scene = ordered list of Layers. Each layer: type, transform (x,y,scale,rotation), alpha, enabled, zIndex.
- Layer types:
  - CameraLayer (OES texture)
  - ImageLayer (Bitmap → GL texture)
  - TextLayer (Canvas → Bitmap → texture, or SDF via FreeType for better quality)
  - ColorLayer (solid color)
  - Future: VideoLayer, BrowserLayer, etc.
- Rendering: Single GL thread. For each frame:
  1. Update camera texture via `SurfaceTexture.updateTexImage()`
  2. Bind FBO if recording (or render twice: display + encoder).
  3. Draw background, then layers back-to-front with MVP matrix.
  4. Apply filter shader if enabled (grayscale, sepia, etc.).
  5. Swap buffers to display; also render to encoder surface via shared EGL context.

**Efficiency rules:**
- Keep textures on GPU; avoid `glReadPixels` per frame.
- Text rasterization only when text changes; cache bitmap.
- Image loading via `BitmapFactory` downsampled to max 1024x1024 for overlay.
- Use VBO/VAO, not immediate mode.
- 30 FPS target = ~33ms/frame budget. Shader should be <5ms.

**Libraries:**
- No need for FFmpeg for rendering; GL is enough.
- For advanced: Use `android.graphics.Canvas` on `Bitmap` for text, then upload.

---

## 6. Screen Capture Possibilities

### Android
- **MediaProjection API:** https://developer.android.com/media/grow/media-projection
  - Can capture screen content, but requires user consent dialog each time (`createScreenCaptureIntent()`).
  - Returns `VirtualDisplay` with Surface. Can composite like camera.
  - Restrictions:
    - Cannot capture secure content (DRM, some banking apps) – black.
    - Background capture from Android 10+ requires foreground service type `mediaProjection`.
    - From Android 14, even stricter: must be foreground and user visible.
    - Cannot capture other app's window only – full screen only (unless using `MediaProjection` + cropping, or Accessibility? Not allowed).
- **Screen recording within own app:** Easy – just record your own GL surface.

### iOS
- ReplayKit: https://developer.apple.com/documentation/replaykit
  - `RPScreenRecorder` for in-app capture, or `RPBroadcastSampleHandler` for system broadcast (requires broadcast extension).
  - App Store apps cannot silently capture screen outside app.

**MVP decision:** Postpone screen/window capture to Phase 6. Document but not implement, because UX complexity + permission dialog + foreground service.

---

## 7. Can App Output Camera Feed to Other Apps?

**Short answer:** No on stock OS.

- Android: No public API to publish virtual camera. `android.hardware.camera2` is client only. HAL is in `cameraserver` process. Only system can add providers.
- There are hacks: 
  - Root + Xposed/LSPosed module that hooks `CameraManager`.
  - Custom ROM building own camera provider that reads socket.
  - On Android 11+, `Camera2` can expose `isPrivacySensitive` but not virtual.
- iOS: No.

**What IS possible:**
- **In-app virtual camera:** Other apps that integrate your SDK can use your composed feed.
- **Network camera:** Stream via RTSP/WebRTC; desktop companion creates virtual cam on PC (requires desktop app).
- **Intent sharing:** Save video and share, but not live.

**Documentation:**
- Android camera service: https://source.android.com/docs/core/camera/architecture
- Android 14 foreground service types: https://developer.android.com/about/versions/14/changes/fgs-types-required

---

## 8. Desktop Companion for Virtual-Camera Functionality

**Required if goal is to appear as webcam on PC.**

Architecture:
- Mobile app: Captures, composes, encodes H.264, sends via WebRTC (low latency) or custom TCP/UDP.
- Desktop app:
  - Windows: Implements `IMFVirtualCamera` (Windows 10+) or DirectShow source filter. Receives stream, decodes, provides frames. Doc: https://learn.microsoft.com/en-us/windows/win32/medfound/virtual-camera
  - macOS: `CMIOExtension` (macOS 12+ replacement for DAL). Doc: https://developer.apple.com/documentation/coremediaio
  - Linux: Write to `/dev/videoN` via `v4l2loopback` https://github.com/umlaeute/v4l2loopback

**MVP:** No desktop companion. Document as future work. Provide network streaming abstraction so companion can be added later.

---

## 9. Recommended Video Formats, Resolutions, Frame Rates, Codecs

- **Preview:** 1280x720 (720p) @ 30 FPS is best baseline. Works on low-end devices, low thermal.
- **Recording:** 1280x720 @ 30 FPS, H.264 AVC, AAC audio. Use `MediaRecorder` or `MediaCodec` + `MediaMuxer`.
  - Container: MP4 (MPEG-4).
  - Bitrate: 4-6 Mbps for 720p, 8-10 Mbps for 1080p.
  - Profile: Baseline or Main for compatibility; High if quality needed.
- **Higher:** 1920x1080 @ 30 FPS if device supports, but check `StreamConfigurationMap`.
- **Codec:** 
  - Android HW encoder: `MediaCodec` with `MIMETYPE_VIDEO_AVC` (H.264) universally supported. H.265 (HEVC) supported from Android 5.0+ but not universal for RTMP.
  - VP8/VP9 for WebRTC.
  - Doc: https://developer.android.com/media/platform/media-codec
  - Doc: https://developer.android.com/media/platform/supported-formats
- **Audio:** AAC-LC, 48 kHz, 128 kbps stereo, via `AudioRecord` or `MediaRecorder`.
- **Frame rate:** Target 30 FPS, allow 24/30/60 if device reports via `getAvailableFpsRanges()`.
- **Orientation:** Handle via `Surface.ROTATION` and `CameraInfo`. Encode always landscape or portrait with rotation metadata. Use `MediaMuxer.setOrientationHint()`.

---

## 10. Performance, Battery, Thermal, Privacy

### Performance
- GPU path avoids CPU copy; keep on GL thread.
- Use `HandlerThread` for camera, separate `GLThread`.
- Avoid allocations in render loop (no new Bitmap per frame).
- Profile with Android GPU Inspector, Systrace.
- Thermal: https://developer.android.com/topic/performance/thermal

### Battery
- Camera + encoding is heavy (~300-600 mA). Expect battery drain 10-20% per 15 min.
- Mitigations: 720p, 30 FPS, lower bitrate, pause when backgrounded, use `setRepeatingRequest` not high-speed.
- Use `WorkManager`? No, foreground service for long recording.

### Thermal
- Register `PowerManager.OnThermalStatusChangedListener` (API 29+): https://developer.android.com/reference/android/os/PowerManager.OnThermalStatusChangedListener
- If `THERMAL_STATUS_SEVERE`, drop to 24 FPS or lower resolution.

### Privacy
- Camera/mic permissions: Must show rationale, handle `shouldShowRequestPermissionRationale`.
- Background camera banned from Android 12+: https://developer.android.com/about/versions/12/behavior-changes-12#foreground-service-launch-restrictions
- Must show foreground notification with camera icon when recording.
- Data: If streaming, inform user. No covert recording.
- Privacy indicators (green dot) OS-enforced from Android 12.
- Docs: https://developer.android.com/privacy-and-security/privacy

---

## 11. Licensing Requirements

### FFmpeg
- LGPL/GPL. If used via `ffmpeg` binary or LGPL build (no GPL components like x264), can be used commercially with dynamic linking and attribution. If GPL, must open source.
- For Android, common wrapper: `mobile-ffmpeg` (now `ffmpeg-kit`) – LGPL.
- Consider alternative: Use platform MediaCodec instead to avoid FFmpeg.

### GStreamer
- Core LGPL, some plugins GPL. Similar to FFmpeg. Requires careful plugin selection.
- Android bindings exist but heavy.

### WebRTC
- BSD-3 license (libwebrtc). Free for commercial. Includes VP8/VP9/H.264.
- Official Android SDK: https://webrtc.github.io/webrtc-org/native-code/android/
- No patent royalty for VP8/VP9 per Google, but H.264 may require MPEG-LA in some jurisdictions.

### RTMP (librtmp / rtmp-rtmp)
- librtmp: LGPL, or custom RTMP client MIT.
- Recommend: `HaishinKit` (Android) or `rtmp-rtmp` pure Java – MIT/Apache.

### Other
- CameraX, OpenGL ES, MediaCodec: Part of Android, Apache 2.0 / no extra license.
- **Recommendation for MVP:** Avoid FFmpeg/GStreamer. Use MediaCodec + MediaMuxer (no licensing burden) + WebRTC (BSD) or RTMP (MIT) for streaming. Add FFmpeg only if need filters that GL cannot do.

---

## 12. Recommended Architecture for MVP

### Modules
- `camera`: CameraX wrapper, lifecycle, torch, zoom, facing.
- `rendering`: EGL context manager, GL renderer, shader programs, texture cache.
- `scene`: Data models – Scene, Layer (sealed class), Transform, persistence (JSON via Room or DataStore).
- `ui`: Jetpack Compose or Views – permission screens, preview, layer list, drag/resize handles, scene switcher.
- `recording`: MediaCodec encoder from GL surface + MediaMuxer, handles orientation.
- `streaming`: Interface `StreamingClient` with implementations `RtmpClient`, `WebRtcClient` (stub), `FileRecordingClient`. Factory.
- `platform`: Permission helper, device capability checker, thermal listener.

### Tech Stack
- Language: Kotlin
- Min SDK 26, Target SDK 34
- UI: Jetpack Compose (modern) + Material3
- Camera: CameraX 1.3.x
- GL: OpenGL ES 3.0 via `GLSurfaceView` or custom `TextureView` + EGL.
- DI: No DI framework for MVP (or Hilt if needed later).
- Persistence: DataStore (JSON) for scenes.
- Tests: JUnit + Robolectric for scene model, instrumented tests for GL if needed.
- Build: Gradle 8.x, Android Gradle Plugin 8.x

### Data Flow
```
[CameraX] -> SurfaceTexture -> [GLRenderer] -> [GLSurfaceView]
                                    |
                                    +-> Shared EGLContext -> MediaCodec Input Surface -> MediaMuxer -> MP4
                                    +-> StreamingClient (future)
[UI Compose] <-> [SceneRepository] <-> [Layer list + transform gestures]
```

---

## 13. Assumptions, Risks, Postponed Features

### Assumptions
- Device has at least one camera and supports 720p.
- OpenGL ES 3.0 available (99%+ devices from Android 5+).
- User grants camera + mic + storage permissions.
- Network streaming credentials provided later.

### Risks
- **Thermal throttling:** Long sessions may drop frames. Mitigation: monitor thermal status.
- **Device fragmentation:** CameraX helps but some OEMs have buggy Camera2 implementations. Need fallback.
- **GL context loss:** Must handle onPause/onResume.
- **Memory:** Loading large image overlays may OOM. Downsample.
- **Permissions denied:** Must show graceful error.

### Must Postpone (not in MVP)
- System-wide virtual camera (impossible without root/companion).
- Screen capture via MediaProjection (add in Phase 6).
- Desktop companion app (future work).
- Advanced filters (LUTs, chroma key) – basic grayscale/sepia only.
- Multi-camera simultaneous (Android 9+ limited support).
- Picture-in-picture or background streaming.
- Cloud storage or RTMP ingest auth UI (provide interface only).

---

## 14. References (Authoritative)

- CameraX: https://developer.android.com/media/camera/camerax
- CameraX architecture: https://developer.android.com/media/camera/camerax/architecture
- Camera2: https://developer.android.com/media/camera/camera2
- SurfaceTexture: https://developer.android.com/reference/android/graphics/SurfaceTexture
- OpenGL ES: https://developer.android.com/develop/ui/views/graphics/opengl
- MediaCodec: https://developer.android.com/reference/android/media/MediaCodec
- MediaRecorder: https://developer.android.com/reference/android/media/MediaRecorder
- MediaMuxer: https://developer.android.com/reference/android/media/MediaMuxer
- MediaProjection: https://developer.android.com/media/grow/media-projection
- Thermal API: https://developer.android.com/topic/performance/thermal
- Permissions: https://developer.android.com/privacy-and-security/permissions
- Foreground service types: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Supported media formats: https://developer.android.com/media/platform/supported-formats
- AVFoundation iOS: https://developer.apple.com/documentation/avfoundation/capture_setup
- ReplayKit: https://developer.apple.com/documentation/replaykit
- WebRTC Native: https://webrtc.org/native-code/
- Windows Virtual Camera: https://learn.microsoft.com/en-us/windows/win32/medfound/virtual-camera
- CoreMediaIO: https://developer.apple.com/documentation/coremediaio
- v4l2loopback: https://github.com/umlaeute/v4l2loopback
- Grafika (Google samples): https://github.com/google/grafika

---

## 15. Implementation Plan Summary

- **Phase 1 (this doc):** Done.
- **Phase 2:** Create Android project, permissions, CameraX preview, front/rear switch, torch, orientation.
- **Phase 3:** Add GL surface, layer model (sealed classes), text/image/color layers, drag/resize via Compose gestures, scene save/load, scene switching.
- **Phase 4:** Implement MediaCodec recording from GL surface, save MP4 to MediaStore, show playback.
- **Phase 5:** Define `StreamingClient` interface, implement RTMP stub (using HaishinKit or rtmp lib) and WebRTC placeholder, document integration steps.

End of research.
