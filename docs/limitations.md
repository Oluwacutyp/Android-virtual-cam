# Limitations – What Cannot Be Done on Stock Mobile OS

This document explicitly lists features that are impossible or require workarounds, to avoid false claims.

## 1. System-Wide Virtual Camera

**Claim:** "App provides virtual camera that appears in any other app's camera picker (WhatsApp, Zoom, etc.)"

**Reality:** ❌ Impossible on unmodified Android/iOS.

- **Android:** Camera HAL is in `cameraserver` process, only system can register providers. No public API. Docs: https://source.android.com/docs/core/camera/architecture
  - Workarounds that DO NOT work on Play Store:
    - Root + Xposed module hooking `CameraManager`
    - Custom ROM with extra CameraProvider reading socket
    - Using Accessibility or MediaProjection to inject – blocked
- **iOS:** App sandbox cannot register `AVCaptureDevice`. Only Apple can. Continuity Camera is system feature.
- **What we do instead:** Standalone studio app + network camera to desktop companion (which then creates virtual cam on PC).

## 2. Background Camera Access

- Android 12+ forbids background camera. App must be foreground with visible preview or foreground service with notification and `foregroundServiceType="camera"`. Docs: https://developer.android.com/about/versions/12/behavior-changes-12#foreground-service-launch-restrictions
- iOS similar.

## 3. Screen Capture of Other Apps

- Android `MediaProjection` can capture whole screen but:
  - Requires user consent dialog each session
  - Requires foreground service from Android 10+
  - Cannot capture secure content (DRM)
  - Cannot capture single window of other app – only full screen
  - From Android 14, stricter: must be visible activity
- iOS ReplayKit: only in-app or broadcast extension, not silent.

## 4. Desktop Virtual Camera Without Companion

- Phone alone cannot create virtual webcam on PC. Need desktop app:
  - Windows: IMFVirtualCamera or DirectShow filter, installer required
  - macOS: CMIOExtension (macOS 12+), system extension
  - Linux: v4l2loopback kernel module

## 5. Thermal & Battery

- Continuous camera + encoding drains 10-20% per 15 min, device heats.
- System may throttle, kill app, or reduce FPS. Must monitor `PowerManager` thermal status.

## 6. Codec & Resolution Variance

- Not all devices support 1080p60. Must query `StreamConfigurationMap`.
- H.265 not universally supported for RTMP. Use H.264 baseline for compatibility.

## 7. Privacy Indicators

- Android 12+ shows green dot when camera/mic active – cannot hide. User always knows.

## 8. Licensing Pitfalls

- FFmpeg GPL: if you include GPL components (x264), must open source. Use LGPL build or MediaCodec to avoid.
- H.264 patent: MPEG-LA may require license for commercial broadcast.

## Summary for MVP

We deliver working standalone camera studio, not system virtual cam. We document companion as future work and provide streaming abstraction ready for desktop integration.
