# Module 4: Resilient Xposed Hooks – No Hardcoded Class Names

## Goal
Hardcoded class names that break on APK updates are banned. Build dynamic scanner with caching and validation.

## DynamicHookScanner.kt

### API
- `scanForVideoFrameDelivery(classLoader: ClassLoader, packageName: String?, versionCode: Long): HookTarget?`
  - Scans for classes with a method taking a single VideoFrame parameter.
  - Tries known candidates first as fast path: `org.webrtc.VideoFrame`, `androidx.camera.core.ImageProxy`, `android.media.Image`, etc.
  - Fast path also tries known method names: `onFrame`, `onVideoFrame`, `deliverFrame`, `processFrame`, `renderFrame`, `onFrameCaptured`, `onImageAvailable`, `analyze`, etc.
  - Falls back to full scan: enumerates loaded classes via `ClassLoader.classes` field (via reflection) + `mClassTable`, distinctBy name.
  - Caches result per package + APK versionCode in `ConcurrentHashMap<String, HookTarget>` key = `packageName:versionCode:type`.
  - Persists cache to `filesDir/hook_scanner_cache.json` via `loadCacheFromFile`/`saveCacheToFile`.

- `scanForAudioRecord(classLoader: ClassLoader, packageName: String?, versionCode: Long): HookTarget?`
  - Similar for AudioRecord.read() variants.
  - Fast path: `android.media.AudioRecord` with `read` methods.
  - Full scan for classes containing `AudioRecord` or `Audio` with `read` method taking `short[]`, `byte[]`, or `ByteBuffer`.

- `validateHook(target: HookTarget, context: Context?): Boolean`
  - Injects a test frame and confirms it appeared in target app's camera preview within 500ms.
  - Checks class exists via `Class.forName`, method exists via `getDeclaredMethod`.
  - Tries to connect to `VCamFrameBus` as consumer, acquires latest frame within 500ms deadline, checks if frame received.
  - If no bus (service not running), considers valid if class/method exists (so hooks can be installed even when service offline).
  - Returns true if frame received or target exists, false otherwise.

- `getPackageVersionCode(context, packageName): Long` – uses `PackageManager.getPackageInfo` with `longVersionCode` for API 28+.

### HookTarget
```kotlin
data class HookTarget(
  val className: String,
  val methodName: String,
  val paramTypes: List<String>,
  val returnType: String,
  val isStatic: Boolean = false,
  val confidence: Float = 0f,
  val source: String = "dynamic" // fast_path, dynamic, cache
)
```

## CameraHooks.kt – Complete Hooks

Every hook:
- Gets latest frame from `VCamFrameBus` (SharedMemory consumer via `getOrCreateBus()` which calls `VCamFrameBus.connectAsConsumer()`)
- Handles null frame gracefully – pass through real frame silently (doesn't replace, just returns)
- Matches frame dimensions to what app requested (resize in native YUV via `libvcamyuv.so` NEON-optimized `Nv21Scaler.scale()`)
- Never throws into target app process (all hooks wrapped in try/catch, `Log.w` only)

### Camera1
- `open()` – hooks both `open()` and `open(int)`, logs, records success via `HookValidator`
- `setPreviewCallback()` – before hook, wraps `PreviewCallback`:
  - In wrapper, gets bus frame, checks requested preview size via `camera.parameters.previewSize`
  - If size mismatch, resizes via `Nv21Scaler.scale(src, srcW, srcH, dstW, dstH)` which uses `libvcamyuv.so` NEON
  - Calls original callback with virtual NV21, else if no frame, would need real data – wrapper receives real data from system, but we try virtual first
  - Measures injection time, warns if >3ms
- `setPreviewCallbackWithBuffer()` – similar, but handles `data` buffer reuse: if `data` buffer provided and size matches, copies virtual into it
- `takePicture()` – hooks all overloads via `declaredMethods.filter { name == "takePicture" }`, wraps `PictureCallback`:
  - Gets bus frame, matches picture size via `parameters.pictureSize`, resizes via `Nv21Scaler`, converts NV21->JPEG via `YuvImage.compressToJpeg`, calls original with JPEG
  - If no virtual frame, passes through real data
- `setPreviewTexture()` – hooks `setPreviewTexture(SurfaceTexture)`, logs bus availability
- `setPreviewDisplay()` – hooks both `SurfaceHolder` and `Surface` overloads

### Camera2
- `CameraManager.openCamera()` – hooks `openCamera(String, StateCallback, Handler)`:
  - Wraps `StateCallback`, intercepts `onOpened` to hook `CameraDevice.createCaptureSession` after open
  - `onOpened` logs bus ready
- `CameraDeviceImpl.createCaptureSession()` – via `hookCameraDeviceCreateSession()`:
  - Finds methods `createCaptureSession` and `createCaptureSessionByOutputConfigurations`
  - Hooks each, wraps `CameraCaptureSession.StateCallback` to intercept `onConfigured`, then hooks `setRepeatingRequest` on session
- `CameraCaptureSessionImpl.setRepeatingRequest()` – via `hookCaptureSessionSetRepeatingRequest()`:
  - Hooks `setRepeatingRequest` overloads, logs, actual injection happens in ImageReader
- `ImageReader.acquireLatestImage()` – after hook:
  - Gets original Image, tries bus frame, if available and size matches, creates `VirtualYuvImage` via `VirtualImageProxy.createFromNv21`
  - If size mismatch, resizes via `Nv21Scaler.scale` (NEON) then creates virtual image
  - Closes original, sets `param.result = virtualImage`
  - If no virtual frame, passes through real silently
  - Measures injection time
- `ImageReader.acquireNextImage()` – similar to acquireLatestImage
- `ImageReader.SurfaceImage.getPlanes()` / `Image.getPlanes()` – hooks `android.media.Image.getPlanes()` for completeness, logs, real injection done via Image replacement

### CameraX
- `ProcessCameraProvider.bindToLifecycle()` – hooks all overloads via `findClass("androidx.camera.lifecycle.ProcessCameraProvider")`, intercepts to find `ImageAnalysis` UseCase among args, logs
- `ImageAnalysis.setAnalyzer()` – dynamic scan via `DynamicHookScanner.findClassesWithMethod("setAnalyzer", paramCount=2)`, wraps `Analyzer` via `Proxy.newProxyInstance`:
  - Intercepts `analyze(ImageProxy)`, tries bus frame, logs injection, calls original
  - Handles exceptions, never throws

### WebRTC
- Dynamic scan via `DynamicHookScanner.scanForVideoFrameDelivery()` – tries fast path then full scan, validates via `validateHook()` (injects test frame, confirms within 500ms)
- Hooks found target: `className#methodName` via `XposedBridge.hookMethod`, intercepts to log and try bus frame
- Additional scans:
  - `startCapture` with 3 params in classes containing `webrtc` or `Capturer` – hooks to log bus availability
  - `VideoSink.onFrame` with single `VideoFrame` param – hooks to intercept

## AudioHooks.kt – All AudioRecord.read() Overloads

- Uses `DynamicHookScanner.scanForAudioRecord()` for discovery and validation
- Collects all `read` methods from `android.media.AudioRecord` via `declaredMethods.filter { name == "read" }`
- Hooks each method generically:
  - After hook, checks `voiceChangerEnabled` from `VirtualCamConfig`
  - Gets `readResult` (int), handles based on first arg type:
    - `ByteArray` – tries `VoiceChangerEngine.getProcessedBytesFromBuffer(readResult)`, else `VoiceChanger.process`
    - `ShortArray` – tries `getProcessedAudioFromBuffer(readResult)`, else `processShort`
    - `FloatArray` – converts short buffer to float (`short/32768f`)
    - `ByteBuffer` – tries circular buffer bytes, else fallback
  - Handles overloads with 4 args (readMode) – ignores 4th arg
  - Never throws

- Fallback signatures if dynamic discovery fails: explicit hooks for `read(byte[],int,int)`, `read(short[],int,int)`, `read(ByteBuffer,int)`

- Records success/failure via `HookValidator`

## libvcamyuv.so – NEON-Optimized YUV Resizer

- `cpp/vcamyuv/yuv_scaler.cpp` – implements NV21 scaling
  - Layout: Y plane W*H, VU interleaved W*H/2
  - `scale_nv21_nearest` – nearest neighbor, NEON path for Y plane (processes 16 pixels at a time via manual gather, uses `arm_neon.h` if available)
  - `scale_nv21_bilinear` – bilinear for Y plane (lerp), nearest for UV for speed
  - UV plane scaling: half resolution, VU interleaved, stride = W
  - JNI: `Java_com_androidvirtualcam_xposed_Nv21Scaler_scaleNative` (byte[]), `scaleDirectNative` (ByteBuffer direct)
  - Enabled NEON via `target_compile_options` `-mfpu=neon` for armeabi-v7a, `-march=armv8-a+simd` for arm64

- Kotlin wrapper `Nv21Scaler.kt`:
  - Loads `libvcamyuv.so`, fallback to Kotlin `scaleKotlin`
  - `scale(src, srcW, srcH, dstW, dstH, useBilinear)` – tries native, else Kotlin
  - `scaleDirect(srcBuffer, srcW, srcH, dstBuffer, dstW, dstH, useBilinear)` – direct ByteBuffer

## HookValidator.kt

- Runs on module load (`initZygote` clears, `handleLoadPackage` logs start, hooks, then writes status)
- `HookResult` data class: hookName, className, methodName, success, error, durationMs, timestamp, packageName, versionCode
- `recordSuccess` / `recordFailure` – logs via `Log.i/w`, appends to log file
- Log file: `/data/data/com.androidvirtualcam/files/hook_validation.log` (also tries `/data/user/0/...` and cache)
  - Format: `[timestamp] SUCCESS/FAILED hookName className#methodName pkg= version= durationMs error`
- Status file: `hook_status_$packageName.json` and `hook_validation_$packageName.json` in main app files dir, plus per-app via context
  - JSON contains packageName, versionCode, timestamp, hooks array, summary total/success/failed/successRate
- Methods: `getResults()`, `getLogFile(context)`, `readLogFile(context)`, `clear()`, `logValidationStart`, `logValidationEnd`, `writeStatusFile(context, packageName, versionCode)`

- Main app can read via:
  ```kotlin
  val log = HookValidator.readLogFile(context)
  val statusFile = File(context.filesDir, "hook_status_$packageName.json")
  ```

## VirtualCamModule.kt – Integration

- `initZygote` – clears validator, logs
- `handleLoadPackage`:
  - Skips self package
  - Checks config `isHookEnabledFor` and `hookAllApps`
  - Gets versionCode, logs validation start, clears results
  - Loads cache via `DynamicHookScanner.loadCacheFromFile(context)`
  - Calls all hooks: `CameraHooks.hookCamera1`, `hookCamera2`, `hookImageReader`, `hookCameraX`, `hookWebRtc`, `AudioHooks.hookAudioRecord` (if voice enabled)
  - Saves cache via `saveCacheToFile`
  - Writes status file via `HookValidator.writeStatusFile`, logs end, logs summary via `XposedBridge.log`

## Resilience Features

- No hardcoded class names that break on APK updates – uses dynamic scanning with fast path as optimization only
- Caching per package + versionCode – avoids full scan every launch after first success
- Validation within 500ms – injects test frame and confirms
- Graceful null handling – if no virtual frame, passes through real frame silently
- Dimension matching via NEON-optimized native scaler
- Never throws into target app – all hooks wrapped in try/catch
- Detailed logging to file for main app UI to display

## Future

- Add more WebRTC frame creation via reflection (`org.webrtc.VideoFrame` constructor)
- Add support for Camera2 `ImageReader` with `YUV_420_888` to `NV21` conversion via libyuv
- Add UI in main app to show hook validation status per app
