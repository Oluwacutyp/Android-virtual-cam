# Module 7: Background Segmentation (No Green Screen Required)

## Overview
Implements `BackgroundSegmentationEngine.kt` using MediaPipe Selfie Segmentation with GPU delegate at 30fps, outputs alpha mask as GL texture directly (no CPU readback), feeds into `BackgroundReplaceNode`, supports multiple background types, temporal filtering 0.7/0.3, fallback to ML Kit Subject Segmentation, works without green screen on front and rear cameras.

## Dependencies
In `app/build.gradle.kts`:
```kotlin
implementation("com.google.mediapipe:tasks-vision:0.10.14")
implementation("com.google.mediapipe:tasks-vision-gpu:0.10.14")
implementation("com.google.mlkit:subject-segmentation:16.0.0-beta1")
implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
```

Model assets in `app/src/main/assets/`:
- `selfie_segmenter.tflite` or `selfie_multiclass_256x256.tflite` (download from MediaPipe)

## BackgroundSegmentationEngine.kt

### Location
`com.androidvirtualcam.segmentation.BackgroundSegmentationEngine`

### Features
- **MediaPipe Selfie Segmentation** with `Delegate.GPU` for 30fps
- **GPU delegate**: `BaseOptions.builder().setModelAssetPath(...).setDelegate(Delegate.GPU)`
- **RunningMode.LIVE_STREAM** with `outputConfidenceMasks=true`
- **Outputs alpha mask as GL texture directly** – mask stays on GPU, no `glReadPixels` readback of mask. Input can be Bitmap from camera ImageAnalysis, avoiding GL texture readback.
- **Temporal filtering** 0.7/0.3 blend current mask with previous frame to reduce flickering – implemented via GL shader `FRAGMENT_SHADER_TEMPORAL_BLEND`
- **Fallback to ML Kit** Subject Segmentation if MediaPipe fails (model not found, GPU error, inference error)
- **Works without green screen** on both front and rear cameras – segmentation model detects person, not color
- **Background options**: solid color, image, video, gaussian blur of original, custom GL texture

### Architecture

#### EGL and GL resources
- Shared EGL context from compositor via `setSharedEglContext(eglContext)`
- Creates pbuffer EGL surface for offscreen processing
- 4 GL textures: `uploadTextureId` (current raw mask), `maskTextureId`, `prevMaskTextureId`, `filteredMaskTextureId`
- 4 FBOs: mask, prevMask, filteredMask, blur
- 4 shader programs: mask upload, temporal blend, blur, solid color
- Vertex buffers for full-screen quad

#### Initialization
```kotlin
fun initialize(w: Int, h: Int): Boolean
```
- Tries to load `selfie_multiclass_256x256.tflite` first, then `selfie_segmenter.tflite` from assets
- If not found, throws and falls back to ML Kit
- `initMediaPipe()`: creates `ImageSegmenter` with GPU delegate, LIVE_STREAM mode, result listener `handleMediaPipeResult`, error listener that switches to ML Kit fallback
- `initMlKit()`: creates `SubjectSegmentation.getClient(options)` with foreground bitmap + confidence mask + multiple subjects
- `initEGL()`: creates display, config, context sharing compositor context, pbuffer surface
- `initGL()`: compiles programs, creates textures with `GL_R8` format (single channel mask), FBOs

#### Segmentation

**MediaPipe path:**
```kotlin
fun processBitmap(bitmap: Bitmap, timestampMs: Long): Boolean
```
- Throttles to 30fps via `FRAME_INTERVAL_MS = 1000/30`
- Checks `isProcessing` atomic to avoid overlapping
- `processWithMediaPipe()`: `BitmapImageBuilder(bitmap).build()` -> `segmentAsync(mpImage, timestampMs)`
- Result listener `handleMediaPipeResult()`: extracts `confidenceMasks[0]` as `ByteBuffer`, converts float 0..1 to byte 0..255, launches `uploadMaskToGL` on main dispatcher (GL thread)

**ML Kit fallback path:**
```kotlin
private fun processWithMlKit(bitmap: Bitmap)
```
- `InputImage.fromBitmap(bitmap, 0)` -> `segmenter.process(inputImage)`
- On success: gets `foregroundConfidenceMask` FloatBuffer, converts to byte array, `uploadMaskToGL`
- Also handles `subjects[0].confidenceMask` for multiple subjects

**Upload to GL (no CPU readback of mask texture):**
```kotlin
private fun uploadMaskToGL(maskData: ByteArray, maskWidth: Int, maskHeight: Int)
```
- Makes EGL current
- `glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED, UNSIGNED_BYTE, ByteBuffer.wrap(maskData))`
- If mask size != target size, scales via `scaleMask()` nearest neighbor
- Calls `applyTemporalFiltering()`

**Temporal filtering 0.7/0.3:**
```kotlin
private fun applyTemporalFiltering()
```
- Blends current mask (uploadTexture) with previous mask (prevMaskTexture) into filteredMaskFramebuffer using shader:
  ```
  float filtered = current * 0.7 + previous * 0.3
  ```
- Then blits filtered to maskTexture and prevMaskTexture via `glBlitFramebuffer` for next frame
- Reduces flickering, smooths edges

**Mask output as GL texture directly:**
```kotlin
fun getMaskTexture(): GLTexture
```
- Returns `GLTexture.wrapExisting(filteredMaskTextureId, GL_TEXTURE_2D, width, height, owned=false)`
- No CPU readback – mask stays on GPU, directly usable in compositor

**Background options:**
```kotlin
sealed class BackgroundType {
  SolidColor(color: Int)
  Image(bitmap: Bitmap)
  Video(textureProvider: () -> GLTexture?)
  Blur(radius: Float)
  CustomTexture(texture: GLTexture)
}

fun getBackgroundTexture(foregroundTexture: GLTexture, customBackground: GLTexture?): GLTexture
```
- Solid: fills blurFramebuffer with solid color via `programSolid`
- Image: creates texture from bitmap via `GLUtils.texImage2D`, caches
- Video: uses provider lambda returning current video frame texture
- Blur: gaussian blur of original foreground via 9-tap blur shader `FRAGMENT_SHADER_BLUR` with `uTexelSize` and `uBlurRadius`
- Custom: returns provided custom texture

**GL texture processing:**
```kotlin
fun processGLTexture(inputTexture: GLTexture, timestampMs: Long): GLTexture
```
- Returns mask texture (for node graph)

**Release:**
- Closes MediaPipe and ML Kit clients, destroys EGL surface/context, deletes programs and textures, closes FBOs

### Performance
- GPU delegate at 30fps – throttled via `FRAME_INTERVAL_MS`
- No CPU readback of mask texture – mask stays on GPU
- Temporal filtering on GPU via shader, negligible cost
- Works on both front and rear cameras – model is camera-agnostic

## SegmentationMaskNode.kt

### Location
`com.androidvirtualcam.compositor.nodes.SegmentationMaskNode`

### Role
Compositor node that wraps `BackgroundSegmentationEngine` and outputs mask texture.

- Type: `SegmentationMask`
- Inputs: `input` texture (camera)
- Output: `output` mask texture (R channel confidence)
- Parameters: `enableTemporalFiltering` bool, `blendCurrent` 0.7, `blendPrevious` 0.3, `useGpuDelegate` true

- `initializeEngine(w, h, sharedEglContext)` – creates engine
- `processBitmap(bitmap, timestampMs)` – for CameraX ImageAnalysis path
- `process(inputs)` – calls `engine.processGLTexture(input, timestamp)` and returns mask
- `getMaskTexture()` – returns current filtered mask
- `setBackgroundType()` – forwards to engine

Feeds into `BackgroundReplaceNode`.

## BackgroundReplaceNode.kt – Enhanced

### Background options support

Parameters:
- `backgroundType`: string "solid", "image", "video", "blur", "custom" (default "blur")
- `solidColor`: int ARGB (default black)
- `blurRadius`: float (default 15)
- `imagePath`: string asset or file path
- `invertMask`: bool
- `edgeFeather`: float 0..1 (default 0.05)
- `enableTemporalFiltering`: bool

Inputs:
- `foreground`: camera texture
- `background`: optional background texture (if not provided, generated from `backgroundType`)
- `mask`: optional mask from `SegmentationMaskNode`

Processing:
- Generates background texture if not provided via `generateBackgroundTexture()`:
  - solid: `generateSolidColor()` fills FBO with solid color shader
  - image: `generateImageBackground()` loads bitmap from assets/file, creates texture, caches
  - blur: `generateBlurBackground()` 9-tap gaussian blur of foreground via `FRAGMENT_SHADER_BLUR`
  - video/custom: uses provided texture or foreground as fallback
- Composites foreground over background using mask:
  ```
  float alpha = useMask ? texture(sMask, uv).r : fg.a
  if (invertMask) alpha = 1.0 - alpha
  if (edgeFeather > 0) alpha = smoothstep(0.0, 0.5, alpha)
  finalRgb = mix(bg.rgb, fg.rgb, alpha)
  ```

Works without green screen because mask comes from segmentation engine, not chroma key.

## Compositor Presets

Two new presets added in `CompositorPresets.kt`:

### background_segmentation
```
CameraInput -> SegmentationMask
CameraInput + Image(bg) + SegmentationMask -> BackgroundReplace -> Output
```
- Background type image, edgeFeather 0.05, temporal filtering 0.7/0.3
- Description: MediaPipe Selfie Segmentation GPU 30fps, mask as GL texture, temporal filtering, ML Kit fallback, front/rear

### background_blur
```
CameraInput -> SegmentationMask
CameraInput + SegmentationMask -> BackgroundReplace (blur 18) -> Output
```
- Background type blur, radius 18, edgeFeather 0.08
- Description: Blur background with segmentation, no green screen

Both presets work without green screen on front and rear cameras.

## Integration with CompositorRenderer

`CompositorRenderer` owns EGL context. `SegmentationMaskNode.initializeEngine()` receives shared EGL context via `setSharedEglContext()` for EGL context sharing (similar to streaming manager).

In render loop:
```kotlin
val maskTexture = segmentationMaskNode.process(mapOf("input" to cameraTexture))
val bgTexture = backgroundImageNode.process(...)
val output = backgroundReplaceNode.process(mapOf(
  "foreground" to cameraTexture,
  "background" to bgTexture,
  "mask" to maskTexture
))
```

Mask stays on GPU – no `glReadPixels` of mask texture.

## Fallback to ML Kit

If MediaPipe fails:
- Model not found in assets -> `initMediaPipe()` throws -> `useMlKitFallback=true` -> `initMlKit()`
- GPU delegate error -> error listener triggers fallback
- Inference error -> catch and switch to ML Kit

ML Kit Subject Segmentation:
- Uses `SubjectSegmentation.getClient(options)` with foreground bitmap + confidence mask
- Processes `InputImage.fromBitmap(bitmap, 0)`
- Outputs confidence mask as FloatBuffer, converted to byte array, uploaded to GL texture same path as MediaPipe
- Same temporal filtering and background options apply

## Front and Rear Camera Support

Segmentation model is trained on person segmentation, not dependent on camera facing. Works for:
- Front camera: selfie segmentation, mirrored handling via `CameraInputNode` mirrored param
- Rear camera: person in scene, same mask generation

No green screen required – model detects person via ML, not color keying.

## Testing

- Without model asset: automatically uses ML Kit fallback
- With model asset: uses MediaPipe GPU delegate, 30fps throttling
- Mask output verified as GL texture (no CPU readback)
- Background options tested: solid color, image, video, blur, custom
- Temporal filtering verified: flicker reduced, blend 0.7/0.3
- Front and rear cameras tested via CameraX

## Future

- Use MediaPipe `ImageSegmenter` with `RunningMode.VIDEO` and `MPImage` from `HardwareBuffer` for zero-copy GPU input
- Add edge refinement via guided filter
- Add hair detail preservation via alpha matting
- Add background video loop and custom texture via user gallery
- Add segmentation for multiple persons (multiclass model already supports hair, clothes, etc.)
