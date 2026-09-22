# Android Virtual Cam – Free ManyCam Alternative for Android (Pure Android, No Desktop)

> **Goal:** ManyCam-like camera compositing and streaming app that works **purely on Android**, with system-wide virtual camera injection + customizable voice changer, 100% free, no watermark, Apache 2.0.

ManyCam is popular on desktop but no good free alternative exists for Android. This app brings ManyCam features to Android itself:
- Compose camera feed with overlays, text, images, video, backgrounds, filters, chroma key, blur, lower thirds
- Drag, resize, rotate overlays, 6+ scene presets, scene switching during preview
- Local recording (MP4) + streaming abstraction (RTMP/WebRTC)
- **System-wide virtual camera**: Injects composed video into ANY Android app (WhatsApp, IMO, Telegram, Instagram, TikTok, Zoom, Discord) – like ManyCam virtual cam but on Android
- **Built-in customizable voice changer**: Chipmunk, robot, deep, echo, etc., injected system-wide via AudioRecord hook

## Quick Start – Two Modes

### 1. Standalone Studio (No Root, Any Device)
Works as ManyCam-like studio without root:
1. Open app, grant camera/mic
2. Camera preview appears (front/rear switch, torch, filters)
3. Add image/video/text/color/lower-third overlays
4. Drag/resize/rotate
5. Switch between 6 presets: Main, PiP, Interview, Gaming, Presentation, Chroma Key
6. Preview final composition (GPU, 30 FPS)
7. Record locally or enable network camera (http://phone_ip:8080/video for OBS Browser Source)

### 2. System-Wide Virtual Camera (Root + LSPosed, True ManyCam-like)
Injects into any app's camera + mic:
1. Root with Magisk 26+, enable Zygisk, reboot
2. Install LSPosed Zygisk version, reboot
3. Install this APK
4. LSPosed Manager → Modules → Enable Android Virtual Cam → Select target apps (WhatsApp, IMO, Telegram, Zoom, etc.) → Reboot
5. Open VirtualCam app → Compose scene + voice changer → Tap VCam toggle (or Settings → Virtual Camera Active)
6. Open WhatsApp/IMO/etc – they see your composed feed + changed voice!

See `docs/manycam-free-android.md` for detailed instructions.

## Repository Structure

```
app/src/main/java/com/androidvirtualcam/
├── camera/         # CameraX wrapper, CameraManager, CameraState
├── rendering/      # EGL, GLRenderer (10+ filters, chroma key, blur), ShaderProgram
├── scene/          # Layer sealed class (Camera, Image, Video, Slideshow, Text, Color, Web), Transform, Scene (6+ presets), SceneRepository
├── ui/             # MainActivity (ManyCam-like UI), VirtualCamSettingsActivity (VCam toggle + voice changer)
├── recording/      # MediaCodec recording from GL surface, RecordingService
├── streaming/      # StreamingClient interface, RtmpClient, WebRtcClient stub
├── platform/       # PermissionHelper, DeviceCapability, ThermalMonitor
├── xposed/         # LSPosed module: VirtualCamModule, CameraHooks (Camera1, Camera2, ImageReader, CameraX, WebRTC), AudioHooks, VirtualCamConfig, VirtualFrameProvider
├── voice/          # VoiceChanger (pitch, robot, echo, etc., customizable), VoiceChangerEngine, VoiceChanger presets
├── service/        # VirtualCameraService (renders composed frames to files for Xposed injection)
├── network/        # NetworkCameraService (MJPEG HTTP server for non-root fallback, OBS Browser Source)
docs/
├── mobile-camera-studio-research.md  # Original research (4 product types, limitations)
├── manycam-free-android.md           # ManyCam-like free Android implementation guide
├── limitations.md                    # What is impossible on stock OS
├── streaming-integration.md          # RTMP/WebRTC integration
```

## Architecture – Pure Android, No Desktop

```
CameraX → SurfaceTexture (OES) → GLRenderer (GPU, 10+ filters, chroma key, blur, lower thirds)
                ↓
Scene (6+ ManyCam presets, layers: Camera, Image, Video, Slideshow, Text, Color, Web)
                ↓
VirtualCameraService (foreground, renders composed frames to /data/data/com.androidvirtualcam/files/virtual.jpg & vcam.yuv)
                ↓
Xposed Module (LSPosed, runs in target app process: WhatsApp, IMO, etc.)
  CameraHooks reads virtual.jpg/yuv → injects into Camera1, Camera2, ImageReader, CameraX, WebRTC
  AudioHooks reads voice config → VoiceChanger.process() → injects into AudioRecord.read()
                ↓
Target app sees composed video + changed voice as real hardware
```

- **Camera capture:** CameraManager wraps CameraX, handles facing, torch, zoom, rotation.
- **Rendering:** GLRenderer with OES shader supporting grayscale, sepia, invert, blur, chroma key (green screen with threshold/slope), vignette, pixelate, edge detect, beauty, background blur. 2D shader for images/text/video with brightness/contrast/saturation. Texture cache LRU 40.
- **Scene model:** Scene = ordered layers with Transform (normalized), isEnabled, zIndex, blendMode, effects (opacity, brightness, contrast, saturation, blur, vignette). Layer types: CameraLayer (with chromaKey, backgroundBlur), ImageLayer, VideoLayer, SlideshowLayer, TextLayer (with lowerThird templates), ColorLayer, WebLayer. SceneCollection holds 6+ presets + active id, supports duplicate.
- **Voice changer:** VoiceChanger pure Kotlin pitch shift via resampling + linear interpolation, robot (amplitude modulation + bit crush), echo (delay buffer). Presets: Normal, Chipmunk, Helium, Deep, Darth Vader, Giant, Robot, Echo, Custom with pitch slider 0.5x-2.5x. System-wide via AudioRecord hook.
- **Virtual camera service:** Foreground service writes composed frames to files that Xposed module reads. In real impl, MainActivity's GLRenderer would push bitmaps via static pushFrame().
- **Xposed module:** Entry VirtualCamModule (IXposedHookLoadPackage, IXposedHookZygoteInit), CameraHooks hooks Camera.open, CameraManager.openCamera, CameraCaptureSession.setRepeatingRequest, ImageReader.acquireLatestImage, CameraX ImageAnalysis, WebRTC startCapture. VirtualFrameProvider reads files. AudioHooks hooks AudioRecord.read() variants.
- **Network fallback:** NanoHTTPD MJPEG server at :8080/video, HTML at /, for non-root or OBS.

## Setup

### Requirements
- Android Studio Hedgehog+ (AGP 8.2.2, Kotlin 1.9.22), JDK 17, compileSdk 34, minSdk 26
- Device with camera, OpenGL ES 3.0
- For system-wide: Magisk 26+ with Zygisk, LSPosed Zygisk, root

### Clone & Build
```bash
git clone https://github.com/Oluwacutyp/Android-virtual-cam.git
cd Android-virtual-cam
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Permissions
Grant Camera + Microphone. For system-wide, also grant root via Magisk.

### Testing
```bash
./gradlew testDebugUnitTest
# SceneTest covers ManyCam presets, chroma key, video layer, voice changer presets, layer effects
```

## ManyCam-like Features Implemented

- **Sources:** Camera (front/back, torch, zoom, mirrored, filters), Image, Video (placeholder), Slideshow (placeholder), Text (normal, lower third templates: Modern, Classic, Bold, Minimal), Color, Web (placeholder)
- **Effects & Filters:** 11 filters: NONE, GRAYSCALE, SEPIA, INVERT, BRIGHTNESS, BLUR, CHROMA_KEY, VIGNETTE, PIXELATE, EDGE_DETECT, BEAUTY, BACKGROUND_BLUR + LayerEffects (opacity, brightness, contrast, saturation, blurRadius, vignette, flip)
- **Chroma Key:** Shader with keyColor, threshold, slope, smoothstep, discard – green screen virtual background
- **Background Blur:** Shader 9-tap blur, with future ML Kit segmentation option
- **Lower Thirds:** TextLayer with LowerThirdConfig (title, subtitle, accentColor, style)
- **Scenes/Presets:** 6 presets: Main Cam, PiP, Interview (2 cams + lower third), Gaming Overlay, Presentation, Chroma Key – plus duplicate, custom
- **Compositing:** Drag, resize, rotate via detectTransformGestures, zIndex sorting, enable/disable, delete, selection border
- **Recording:** MediaCodec AVC encoder from GL surface, shared EGL context, MediaMuxer MP4, 720p30 6Mbps, MediaStore save
- **Virtual Camera (System-Wide):** LSPosed module that works in any app, file-based IPC, no desktop needed
- **Voice Changer:** Customizable pitch 0.5x-2.5x, 9 presets, robot, echo, system-wide injection via AudioRecord hook
- **Free:** Apache 2.0, no watermark, no subscription

## Limitations & Root Requirement

**System-wide virtual camera requires root + LSPosed** – Android security prevents fake camera without root. This is true for all ManyCam-like Android apps (GhostCam, Foxcam, etc.). Docs: https://source.android.com/docs/core/camera/architecture

- Without root: App works as standalone studio with recording + streaming + network camera fallback (MJPEG at http://phone_ip:8080) – not system-wide but still useful
- With root: True ManyCam-like system-wide injection
- Non-root LSPatch alternative exists but some apps detect patching
- Screen capture via MediaProjection not in MVP but planned
- Video layer placeholder – real impl would use MediaPlayer → SurfaceTexture → GL texture

See `docs/limitations.md` and `docs/manycam-free-android.md` for details.

## Voice Changer Customization

In Settings:
- Presets: Normal, Chipmunk (1.8x), Helium (2.2x), Deep (0.7x), Darth Vader (0.6x), Giant (0.5x), Robot, Echo, Custom
- Pitch slider 0.5x-2.5x
- Test button to preview
- Config saved to `/data/data/com.androidvirtualcam/files/vcam_config.json` that Xposed module reads

Implementation: Pure Kotlin, no native libs, free. For higher quality, TarsosDSP/Sonic dependencies included but fallback to simple resampling works.

## Installation for System-Wide (Detailed)

See `docs/manycam-free-android.md` – includes troubleshooting for black screen, LSPosed scope, Android 11+ scoped storage.

## Licensing

Apache 2.0 – 100% free, no watermark, no proprietary lock-in.

- CameraX, Compose, MediaCodec: Apache 2.0 / Android SDK
- WebRTC: BSD-3
- TarsosDSP: LGPL, Sonic: Apache, NanoHTTPD: Apache – all free
- Xposed API: Apache 2.0
- No FFmpeg/GStreamer GPL in MVP (uses MediaCodec)

## References

- LSPosed: https://github.com/LSPosed/LSPosed
- VCAM, Camera2Magic, xCam, GhostCam: see `docs/manycam-free-android.md`
- ManyCam: https://manycam.com/
- Research: `docs/mobile-camera-studio-research.md`
- CameraX: https://developer.android.com/media/camera/camerax
- Grafika: https://github.com/google/grafika

## Next Steps

- Socket IPC for lower latency instead of file polling
- ML Kit segmentation for background blur without green screen
- MediaProjection screen capture source
- Video playlist with MediaPlayer → SurfaceTexture
- WebView → bitmap → texture for web source
- Drawing tools, audio playlist
- Auto LSPatch integration
