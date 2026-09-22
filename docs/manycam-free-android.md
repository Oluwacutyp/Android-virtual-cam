# Android Virtual Cam – Free ManyCam Alternative for Android (Pure Android, No Desktop)

## Overview

This project is a **100% free, open-source, no-watermark ManyCam alternative that works purely on Android** – no desktop/PC companion required.

ManyCam is popular on desktop for:
- Multiple scenes/presets
- Camera + overlays, text, images, backgrounds
- Chroma key, blur, filters
- Lower thirds
- Virtual camera that appears in any app
- Voice changer

This app brings all of that to **Android itself**, so your composed video appears as the camera in **any Android app** (WhatsApp, IMO, Telegram, Instagram, TikTok, Zoom, Discord, etc.) with **customizable voice changer**.

## Two Modes

### 1. Standalone Studio (No Root, Works on Any Device)
- Compose camera feed with:
  - Image overlays, video overlays, slideshow, web source placeholder
  - Text overlays with lower-third templates
  - Solid color & image backgrounds
  - Chroma key (green screen) with adjustable threshold
  - Background blur, filters (grayscale, sepia, invert, blur, vignette, pixelate, edge detect, beauty)
  - Drag, resize, rotate, enable/disable layers
  - 6+ presets: Main Cam, PiP, Interview, Gaming, Presentation, Chroma Key
- Preview final composition (GPU accelerated, 30 FPS target)
- Local recording (MediaCodec + MediaMuxer, MP4, 720p30, 6 Mbps)
- Streaming abstraction (RTMP prototype, WebRTC stub)
- Network camera fallback (MJPEG HTTP server at http://phone_ip:8080 – use in OBS Browser Source if needed)

### 2. System-Wide Virtual Camera (Root + LSPosed, True ManyCam-like)
- **Requires root + LSPosed (Magisk + Zygisk)**
- Xposed module hooks:
  - `android.hardware.Camera` (legacy)
  - `android.hardware.camera2.CameraManager, CameraDevice, CameraCaptureSession, ImageReader`
  - `androidx.camera` (CameraX)
  - `org.webrtc` (WebRTC – used by many video call apps)
  - `android.media.AudioRecord` (for voice changer injection)
- Main app renders composed scene to `/data/data/com.androidvirtualcam/files/virtual.jpg` and `vcam.yuv`
- Xposed module running inside target app process reads those files and injects as camera feed
- Result: **Any app sees your composed video as real camera**, like ManyCam virtual camera on desktop, but on Android.

## Voice Changer – Customizable, System-Wide

ManyCam has voice changer – this app has free customizable one:

- **Effects**: Normal, Chipmunk, Helium, Deep Voice, Darth Vader, Giant, Robot (amplitude modulation + bit crush), Echo (delay), Custom pitch
- **Customizable**: Slider 0.5x to 2.5x pitch, presets, echo toggle
- **How it works**:
  - Standalone: `VoiceChangerEngine` captures mic, applies pitch shift via resampling + interpolation, plays preview
  - System-wide: Xposed `AudioHooks` hooks `AudioRecord.read()` in target app, replaces buffer with processed PCM
  - Config saved to `/data/data/com.androidvirtualcam/files/vcam_config.json` that Xposed module reads
- **Pure Kotlin, no native libs required** for basic effects (free, no proprietary). Optional TarsosDSP/Sonic for higher quality.

## Architecture – Pure Android

```
Main App (Studio)
  CameraX → SurfaceTexture (OES) → GLRenderer (GPU, filters, chroma key, blur)
                ↓
  Scene (6+ presets, layers: Camera, Image, Video, Slideshow, Text, Color, Web)
                ↓
  VirtualCameraService (foreground, renders composed frames to files)
                ↓
  /data/data/com.androidvirtualcam/files/
    virtual.jpg (JPEG for takePicture)
    vcam.yuv (NV21 for preview)
    vcam_config.json (config for Xposed)

Xposed Module (LSPosed, runs in target app process)
  CameraHooks → reads virtual.jpg / vcam.yuv → injects into Camera1, Camera2, ImageReader, CameraX, WebRTC
  AudioHooks → reads voice config → VoiceChanger.process() → injects into AudioRecord.read()

Result: WhatsApp, IMO, etc. see composed video + changed voice as if real hardware.
```

## Installation – System-Wide (ManyCam-like)

### Requirements
- Android 8.0+ (minSdk 26)
- Root with Magisk 26+ and Zygisk enabled
- LSPosed Zygisk version (latest)
- This APK

### Steps
1. Root device with Magisk, enable Zygisk in Magisk settings, reboot
2. Install LSPosed APK (Zygisk version), reboot
3. Install this APK (Android Virtual Cam)
4. Open LSPosed Manager → Modules → Enable Android Virtual Cam
5. Select scope: WhatsApp, Telegram, IMO, Instagram, TikTok, Zoom, Discord, etc. (or enable all for ManyCam-like)
6. Reboot
7. Open VirtualCam app → grant camera/mic permissions
8. Compose scene (add overlays, lower thirds, chroma key, voice changer)
9. Tap VCam toggle (or Settings → Virtual Camera Active)
10. Open target app – it will show your composed feed + changed voice!

### Troubleshooting
- Black screen? Check LSPosed logs, ensure /data/data/com.androidvirtualcam/files/virtual.jpg exists, grant root, check target app in LSPosed scope
- Voice not changed? Enable voice changer in settings, select effect, ensure target app uses AudioRecord (most do)
- Android 11+ file restrictions? Module uses private dir, not /sdcard/DCIM, to comply with scoped storage
- Non-root? Use standalone studio + network camera fallback (http://phone_ip:8080/video) – not system-wide but still useful

### Non-Root Alternative (LSPatch)
- Use LSPatch to patch target APK to embed Xposed module
- Some apps detect patching and may not work
- For free ManyCam-like without root, standalone + recording + streaming is recommended

## Free & Open Source – No Watermark

- License: Apache 2.0
- No watermark, no subscription, no proprietary libs
- All filters, chroma key, voice changer, presets are free
- ManyCam free version has watermark – this has none

## Comparison with ManyCam

| Feature | ManyCam (Desktop) | This App (Android Free) |
|---------|-------------------|-------------------------|
| Virtual Camera | Yes (desktop) | Yes (Android system-wide with root) |
| Overlays | Yes | Yes (image, video, text, color, web) |
| Chroma Key | Yes | Yes (shader with threshold/slope) |
| Background Blur | Yes | Yes (shader + ML Kit future) |
| Lower Thirds | Yes | Yes (templates) |
| Filters | Yes | Yes (10+ filters, GPU) |
| Voice Changer | Yes (paid) | Yes (free, customizable, system-wide) |
| Scenes/Presets | Yes | Yes (6+ presets, duplicate) |
| Recording | Yes | Yes (MP4, MediaCodec) |
| Streaming | Yes (RTMP) | Yes (RTMP prototype, WebRTC stub) |
| Price | Free with watermark / Paid | 100% free, no watermark, Apache 2.0 |
| Platform | Windows/macOS | Pure Android, no desktop needed |

## Technical Details

### Camera Injection
- Based on open-source projects: VCAM, Camera2Magic, xCam, GhostCam, Foxcam
- Hooks use Xposed API 82 and libxposed API 100 (LSPosed modern)
- Handles Camera1, Camera2, CameraX, ImageReader, WebRTC
- File-based IPC: main app writes JPEG/YUV, Xposed module reads – simple, reliable, low latency for MVP
- Future: local socket or shared memory for lower latency

### Voice Changer
- Pure Kotlin pitch shift via resampling + linear interpolation
- Robot: amplitude modulation + bit crushing
- Echo: delay buffer
- Presets: Normal, Chipmunk, Helium, Deep, Darth Vader, Giant, Robot, Echo, Custom
- Customizable pitch slider 0.5x-2.5x
- System-wide via AudioRecord hook

### Rendering
- OpenGL ES 3.0, GLSurfaceView, zero-copy camera OES texture
- Shaders: OES with 10+ filters, chroma key (distance from key color, smoothstep), blur (9-tap), vignette, etc.
- 2D shader for images/text with brightness/contrast/saturation
- Texture cache LRU 40, bitmap cache
- 30 FPS target, renderMode WHEN_DIRTY driven by camera frame

## Limitations (Stock Android)

- System-wide virtual camera requires root + LSPosed – Android security prevents fake camera without root (same as all ManyCam-like Android apps: GhostCam, Foxcam, etc.)
- Non-root fallback is standalone studio + network camera, not system-wide
- Screen capture via MediaProjection not in MVP but planned (requires foreground service + consent dialog)
- Video layer is placeholder – real implementation would use MediaPlayer → SurfaceTexture → GL texture

## Future Work

- Real-time socket IPC instead of file polling for lower latency
- ML Kit segmentation for background blur without green screen
- Screen capture source (MediaProjection)
- Video playlist with MediaPlayer integration
- Web source via WebView → bitmap → texture
- Drawing tools
- Audio playlist
- LSPatch auto-patcher built-in

## References

- LSPosed: https://github.com/LSPosed/LSPosed
- VCAM: https://github.com/Xposed-Modules-Repo/com.example.vcam
- Camera2Magic: https://github.com/wudixxqq/Camera2Magic
- xCam: https://github.com/hazbu/xCam
- ManyCam: https://manycam.com/
