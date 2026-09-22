# Module 2: Node Graph Compositor – OBS-like DAG

## Overview
Replaces flat Layer list with DAG like OBS. Built for pure Android, no desktop/PC dependency.

## Architecture

### Core Interfaces

**CompositorNode**
- `id: String` – unique node identifier
- `type: String` – node type (CameraInput, Blur, etc)
- `inputSockets: List<InputSocket>` – typed inputs (TEXTURE, FLOAT, VEC3, BOOL, etc)
- `outputSocket: OutputSocket` – single typed output (TEXTURE)
- `parameters: NodeParameters` – serializable typed params
- `initialize()` – create GL resources (textures, FBOs, shaders)
- `process(inputs: Map<String, GLTexture>): GLTexture` – process and return output texture
- `release()` – cleanup GL resources

**GLTexture & GLFramebuffer**
- Wraps `GL_TEXTURE_2D` and `GL_TEXTURE_EXTERNAL_OES`
- Ownership tracking, AutoCloseable
- FBO completeness check

**CompositorGraph**
- DAG with Kahn's topological sort
- Cycle detection via DFS with cycle path in `CompositorCycleException`
- Connections map: `(toNode,input) -> (fromNode,output)`
- `initializeAll()` / `evaluate()` / `releaseAll()`
- `toJson()` / `fromJson()` with `NodeFactory`
- Serializable via kotlinx.serialization

## 12 Nodes – Complete GLSL

### 1. CameraInputNode
- CameraX OES texture, SurfaceTexture
- Handles rotation matrix + mirrored via mvpMatrix
- OES fragment shader: `samplerExternalOES`
- Zero-copy if identity matrix, else FBO render

### 2. VideoFileNode
- MediaPlayer → Surface → SurfaceTexture → OES
- Looping, seekTo(), mute via volume, speed param
- prepareAsync, onCompletion looping

### 3. ImageNode
- Bitmap → GL_TEXTURE_2D, mipmap generation
- Handles content:// URIs, downsampling via inSampleSize
- Fallback 1x1 white texture

### 4. ChromaKeyNode
- Inputs: foreground texture
- Params: keyColor (vec3), threshold, slope (smoothing), spill
- GLSL: distance from keyColor, smoothstep alpha, spill suppression (desaturate green spill, remove fringing)
- Production: removes green fringing on edges

### 5. LUTNode
- Parses .cube format (TITLE, LUT_3D_SIZE, DOMAIN_MIN/MAX, data)
- Parses 512x512 Hald CLUT PNG (8x8 grid of 64x64 blocks)
- Creates 3D texture `GL_TEXTURE_3D` with trilinear filtering
- Shader: half-texel offset, hardware trilinear interpolation, intensity mix

### 6. BlurNode
- Dual-pass Gaussian, sigma FBO
- Params: sigma 0.1..25, radius auto = sigma*3
- Two programs: horizontal + vertical separable
- Weights calculated via Gaussian `exp(-i²/2σ²)`, normalized
- Intermediate FBO + final FBO

### 7. BlendNode
- 15 modes: Normal, Multiply, Screen, Overlay, SoftLight, HardLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity, Add, Subtract, Divide
- Inputs: base, blend
- Params: mode (string), opacity 0..1
- GLSL includes RGB↔HSL conversion for Hue/Saturation/Color/Luminosity

### 8. ColorCorrectionNode
- Lift/Gamma/Gain per channel + HSL
- Params: lift vec3, gamma vec3 (0.1..4), gain vec3, saturation, hueShift -1..1, brightness, contrast, temperature
- ASC CDL approximation: (color+lift)^(1/gamma)*gain
- Temperature warm/cool shift, HSL via rgbToHsl/hslToRgb

### 9. TextOverlayNode
- Compose Canvas → Bitmap → GL each frame slide/fade
- Params: text, fontSize, color vec4, backgroundColor vec4, x/y normalized, animation None/SlideLeft/SlideRight/Fade/SlideUp/SlideDown, animationProgress 0..1, bold, shadow
- Production: Canvas draw with Paint, shadow layer, rounded rect background, GLUtils.texSubImage2D upload each frame
- Shader composites text quad over background with animation offset

### 10. LowerThirdNode
- 4 styles: Modern, Broadcast, Minimal, Sports, animate on/off duration
- Params: title, subtitle, style, primaryColor, secondaryColor, textColor, show bool, animationProgress, duration, position Bottom/Top
- Canvas drawing per style:
  - Modern: rounded bar + accent line
  - Broadcast: two-tone news style + live dot
  - Minimal: line + text fade
  - Sports: angled skewed path + number box
- Upload to GL each frame, composite to bottom/top 400px

### 11. BackgroundReplaceNode
- Mask+bg composite
- Inputs: foreground (with alpha from chroma key), background, mask optional
- Params: invertMask bool, edgeFeather float
- If mask present, use red channel as alpha; invert if needed; feather via smoothstep

### 12. OutputNode
- Writes SharedMemory via VCamFrameBus + EGL Surface for encoder
- Inputs: final texture
- Params: width, height, enableBus, enableSurface
- Handles OES→2D conversion via FBO if needed
- glReadPixels RGBA → CPU convert to NV21 (BT.601, flip vertically)
- Publishes via VCamFrameBus.publishFrame() <2ms
- Optional EGL window surface for MediaCodec input surface (EGL14.eglCreateWindowSurface)
- Callback onFramePublished for tests

## Graph Evaluation

```kotlin
val graph = CompositorGraph()
graph.addNode(camera)
graph.addNode(blur)
graph.connect("camera", "output", "blur", "input")
val order = graph.computeEvaluationOrder() // Kahn's
val outputs = graph.evaluate() // Map<nodeId, GLTexture>
```

Cycle detection:
```kotlin
try {
  graph.computeEvaluationOrder()
} catch (e: CompositorCycleException) {
  Log.e("Cycle: ${e.cyclePath.joinToString(" -> ")}")
}
```

## Serialization

```kotlin
val json = graph.toJson()
val newGraph = CompositorGraph()
newGraph.fromJson(json, CompositorNodeFactory(context))
```

NodeParameters serializable: floats, ints, bools, strings, vec3s, vec4s.

## 8 Built-in Presets JSON

Located in `app/src/main/assets/compositor_presets/` and via `CompositorPresets` object:

1. **Main Camera** – camera → output
2. **Green Screen** – camera → chromaKey → backgroundReplace (image bg) → output, with spill suppression
3. **PiP** – main camera + pip image → blend → output
4. **Interview** – host cam + guest image → blend (split) → output
5. **Gaming Overlay** – game bg + small cam → blend → text overlay (LIVE) → output
6. **Cinematic** – camera → LUT (cinematic.cube) → color correction (film) → output
7. **Presentation** – slides image + presenter cam → blend → text → output
8. **News Broadcast** – camera → color correction (broadcast) → lower third (Broadcast style) → output

Each preset JSON contains nodes array with parameters and connections array.

Load preset:
```kotlin
val renderer = CompositorRenderer(context, 1280, 720)
renderer.initialize(frameBus)
renderer.loadPreset("green_screen")
renderer.renderFrame() // each frame
```

## EGL Management

CompositorRenderer creates pbuffer EGL context (OpenGL ES 3.0), makes current, initializes graph, evaluates each frame.

For encoder: OutputNode.setEncoderSurface(MediaCodec.createInputSurface()) creates EGL window surface.

## Testing

- `CompositorGraphTest`: topological sort, branching, cycle detection with path, serialization round-trip, preset JSON validity, NodeParameters serialization.

## Future

- TransformNode (scale/position/rotation) for PiP positioning
- GrainNode for cinematic grain
- Audio integration with voice changer
- UI for graph editing (Compose nodes)
