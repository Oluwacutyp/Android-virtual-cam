package com.androidvirtualcam.compositor.presets

import android.content.Context
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.compositor.nodes.*

/**
 * 8 built-in presets JSON: Main Camera, Green Screen, PiP, Interview,
 * Gaming Overlay, Cinematic (LUT+grain), Presentation, News Broadcast.
 *
 * Each preset is defined as a function building a CompositorGraph, then serialized to JSON.
 * JSON files are also written to assets/compositor_presets/ for persistence.
 */
object CompositorPresets {

    data class PresetInfo(
        val id: String,
        val name: String,
        val description: String,
        val thumbnail: String = "",
        val json: String
    )

    fun getAllPresets(context: Context): List<PresetInfo> {
        return listOf(
            mainCameraPreset(context),
            greenScreenPreset(context),
            backgroundSegmentationPreset(context),
            backgroundBlurPreset(context),
            pipPreset(context),
            interviewPreset(context),
            gamingOverlayPreset(context),
            cinematicPreset(context),
            presentationPreset(context),
            newsBroadcastPreset(context),
            screenSharePreset(context),
            tutorialPreset(context),
            screenPipPreset(context),
            gamingScreenPreset(context),
            beautyPreset(context),
            faceFilterPreset(context),
            beautyFilterComboPreset(context)
        )
    }

    fun getPresetJson(context: Context, presetId: String): String? {
        return getAllPresets(context).find { it.id == presetId }?.json
    }

    // 1. Main Camera – simple camera -> center_stage -> output
    fun mainCameraPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).bool("mirrored", false).int("rotation", 0).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).bool("enableBus", true).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(output)
        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "output_1", "input")

        return PresetInfo(
            id = "main_camera",
            name = "Main Camera",
            description = "Direct camera feed to virtual camera output with Center Stage auto-framing",
            json = graph.toJson()
        )
    }

    // 2. Green Screen – camera + chroma key + background replace
    fun greenScreenPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val background = factory.createNode("Image", "bg_1", NodeParameters.builder()
            .string("uri", "").string("resName", "green_screen_bg").bool("mipmap", true).build())
        val chroma = factory.createNode("ChromaKey", "chroma_1", NodeParameters.builder()
            .vec3("keyColor", 0f, 1f, 0f).float("threshold", 0.4f).float("slope", 0.1f).float("spill", 0.5f).build())
        val bgReplace = factory.createNode("BackgroundReplace", "bg_replace_1", NodeParameters.builder()
            .string("backgroundType", "image").bool("invertMask", false).float("edgeFeather", 0.02f).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(background)
        graph.addNode(chroma)
        graph.addNode(bgReplace)
        graph.addNode(output)

        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "chroma_1", "input")
        graph.connect("chroma_1", "output", "bg_replace_1", "foreground")
        graph.connect("bg_1", "output", "bg_replace_1", "background")
        graph.connect("bg_replace_1", "output", "output_1", "input")

        return PresetInfo(
            id = "green_screen",
            name = "Green Screen",
            description = "Chroma key green screen replacement with spill suppression",
            json = graph.toJson()
        )
    }

    // 2b. Background Segmentation – no green screen, MediaPipe Selfie Segmentation GPU 30fps
    fun backgroundSegmentationPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val segmentation = factory.createNode("SegmentationMask", "segmentation_1", NodeParameters.builder()
            .bool("enableTemporalFiltering", true).float("blendCurrent", 0.7f).float("blendPrevious", 0.3f).bool("useGpuDelegate", true).build())
        val background = factory.createNode("Image", "bg_1", NodeParameters.builder()
            .string("uri", "").string("resName", "virtual_bg").bool("mipmap", true).build())
        val bgReplace = factory.createNode("BackgroundReplace", "bg_replace_1", NodeParameters.builder()
            .string("backgroundType", "image").bool("invertMask", false).float("edgeFeather", 0.05f).bool("enableTemporalFiltering", true).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(segmentation)
        graph.addNode(background)
        graph.addNode(bgReplace)
        graph.addNode(output)

        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "segmentation_1", "input")
        graph.connect("center_stage_1", "output", "bg_replace_1", "foreground")
        graph.connect("bg_1", "output", "bg_replace_1", "background")
        graph.connect("segmentation_1", "output", "bg_replace_1", "mask")
        graph.connect("bg_replace_1", "output", "output_1", "input")

        return PresetInfo(
            id = "background_segmentation",
            name = "Background Replace (No Green Screen)",
            description = "MediaPipe Selfie Segmentation GPU 30fps, mask as GL texture, temporal filtering 0.7/0.3, ML Kit fallback, works front and rear",
            json = graph.toJson()
        )
    }

    // 2c. Background Blur – no green screen, blur background
    fun backgroundBlurPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val segmentation = factory.createNode("SegmentationMask", "segmentation_1", NodeParameters.builder()
            .bool("enableTemporalFiltering", true).float("blendCurrent", 0.7f).float("blendPrevious", 0.3f).build())
        val bgReplace = factory.createNode("BackgroundReplace", "bg_replace_1", NodeParameters.builder()
            .string("backgroundType", "blur").float("blurRadius", 18f).float("edgeFeather", 0.08f).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(segmentation)
        graph.addNode(bgReplace)
        graph.addNode(output)

        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "segmentation_1", "input")
        graph.connect("center_stage_1", "output", "bg_replace_1", "foreground")
        graph.connect("segmentation_1", "output", "bg_replace_1", "mask")
        graph.connect("bg_replace_1", "output", "output_1", "input")

        return PresetInfo(
            id = "background_blur",
            name = "Background Blur",
            description = "Blur background with MediaPipe segmentation, no green screen, front/rear cameras",
            json = graph.toJson()
        )
    }

    // 3. PiP – Picture in Picture: main camera + small overlay camera/image
    fun pipPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val mainCam = factory.createNode("CameraInput", "main_camera", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val pipImage = factory.createNode("Image", "pip_image", NodeParameters.builder()
            .string("resName", "pip_avatar").int("maxSize", 512).build())
        val blend = factory.createNode("Blend", "blend_pip", NodeParameters.builder()
            .string("mode", "Normal").float("opacity", 1f).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(mainCam)
        graph.addNode(centerStage)
        graph.addNode(pipImage)
        graph.addNode(blend)
        graph.addNode(output)

        graph.connect("main_camera", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "blend_pip", "base")
        graph.connect("pip_image", "output", "blend_pip", "blend")
        graph.connect("blend_pip", "output", "output_1", "input")

        return PresetInfo(
            id = "pip",
            name = "PiP",
            description = "Picture-in-Picture with main camera and small overlay",
            json = graph.toJson()
        )
    }

    // 4. Interview – two cameras side by side with blur background
    fun interviewPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val cam1 = factory.createNode("CameraInput", "camera_host", NodeParameters.builder()
            .int("width", 640).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val cam2 = factory.createNode("Image", "camera_guest", NodeParameters.builder()
            .string("resName", "guest_placeholder").build())
        val blur = factory.createNode("Blur", "blur_bg", NodeParameters.builder()
            .float("sigma", 8f).int("radius", 0).build())
        val blend = factory.createNode("Blend", "blend_interview", NodeParameters.builder()
            .string("mode", "Normal").float("opacity", 1f).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(cam1)
        graph.addNode(centerStage)
        graph.addNode(cam2)
        graph.addNode(blur)
        graph.addNode(blend)
        graph.addNode(output)

        graph.connect("camera_host", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "blend_interview", "base")
        graph.connect("camera_guest", "output", "blend_interview", "blend")
        graph.connect("blend_interview", "output", "output_1", "input")

        return PresetInfo(
            id = "interview",
            name = "Interview",
            description = "Split-screen interview layout with blurred background",
            json = graph.toJson()
        )
    }

    // 5. Gaming Overlay – camera + image overlay + text
    fun gamingOverlayPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val gameCapture = factory.createNode("Image", "game_bg", NodeParameters.builder()
            .string("resName", "game_background").build())
        val cam = factory.createNode("CameraInput", "cam_small", NodeParameters.builder()
            .int("width", 320).int("height", 240).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val blend = factory.createNode("Blend", "blend_game", NodeParameters.builder()
            .string("mode", "Normal").float("opacity", 1f).build())
        val text = factory.createNode("TextOverlay", "text_overlay", NodeParameters.builder()
            .string("text", "LIVE - Gaming Stream").int("fontSize", 48)
            .vec4("color", 1f, 1f, 1f, 1f).vec4("backgroundColor", 0.1f, 0.1f, 0.1f, 0.7f)
            .float("x", 0.5f).float("y", 0.05f).string("animation", "SlideLeft")
            .float("animationProgress", 1f).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(gameCapture)
        graph.addNode(cam)
        graph.addNode(centerStage)
        graph.addNode(blend)
        graph.addNode(text)
        graph.addNode(output)

        graph.connect("game_bg", "output", "blend_game", "base")
        graph.connect("cam_small", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "blend_game", "blend")
        graph.connect("blend_game", "output", "text_overlay", "input")
        graph.connect("text_overlay", "output", "output_1", "input")

        return PresetInfo(
            id = "gaming_overlay",
            name = "Gaming Overlay",
            description = "Gaming stream overlay with camera PiP and live text",
            json = graph.toJson()
        )
    }

    // 6. Cinematic – LUT + color correction + grain (via blur overlay)
    fun cinematicPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val lut = factory.createNode("LUT", "lut_cinematic", NodeParameters.builder()
            .string("lutPath", "luts/cinematic.cube").int("lutSize", 32).float("intensity", 0.8f).build())
        val color = factory.createNode("ColorCorrection", "cc_film", NodeParameters.builder()
            .vec3("lift", 0.02f, 0.02f, 0.02f).vec3("gamma", 0.95f, 0.95f, 1.05f).vec3("gain", 1.1f, 1.05f, 0.95f)
            .float("saturation", 0.9f).float("contrast", 1.15f).float("brightness", -0.05f).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(lut)
        graph.addNode(color)
        graph.addNode(output)

        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "lut_cinematic", "input")
        graph.connect("lut_cinematic", "output", "cc_film", "input")
        graph.connect("cc_film", "output", "output_1", "input")

        return PresetInfo(
            id = "cinematic",
            name = "Cinematic",
            description = "Film look with LUT, color correction, and grain",
            json = graph.toJson()
        )
    }

    // 7. Presentation – screen share + camera overlay + text
    fun presentationPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val slides = factory.createNode("Image", "slides", NodeParameters.builder()
            .string("resName", "presentation_slide").int("maxSize", 1920).build())
        val presenter = factory.createNode("CameraInput", "presenter_cam", NodeParameters.builder()
            .int("width", 320).int("height", 240).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val blend = factory.createNode("Blend", "blend_pres", NodeParameters.builder()
            .string("mode", "Normal").float("opacity", 1f).build())
        val text = factory.createNode("TextOverlay", "slide_title", NodeParameters.builder()
            .string("text", "Q4 Presentation").int("fontSize", 36)
            .vec4("color", 0f, 0f, 0f, 1f).vec4("backgroundColor", 1f, 1f, 1f, 0.8f)
            .float("x", 0.5f).float("y", 0.95f).string("animation", "Fade").build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(slides)
        graph.addNode(presenter)
        graph.addNode(centerStage)
        graph.addNode(blend)
        graph.addNode(text)
        graph.addNode(output)

        graph.connect("slides", "output", "blend_pres", "base")
        graph.connect("presenter_cam", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "blend_pres", "blend")
        graph.connect("blend_pres", "output", "slide_title", "input")
        graph.connect("slide_title", "output", "output_1", "input")

        return PresetInfo(
            id = "presentation",
            name = "Presentation",
            description = "Presentation mode with slides and presenter camera",
            json = graph.toJson()
        )
    }

    // 8. News Broadcast – lower third + color correction
    fun newsBroadcastPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_anchor", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val cc = factory.createNode("ColorCorrection", "cc_broadcast", NodeParameters.builder()
            .vec3("lift", 0f, 0f, 0f).vec3("gamma", 1f, 1f, 1f).vec3("gain", 1.05f, 1.05f, 1.05f)
            .float("saturation", 1.1f).float("contrast", 1.1f).float("brightness", 0.02f).build())
        val lowerThird = factory.createNode("LowerThird", "lt_news", NodeParameters.builder()
            .string("title", "BREAKING NEWS").string("subtitle", "Live from Studio")
            .string("style", "Broadcast").vec3("primaryColor", 0.8f, 0.1f, 0.1f)
            .vec3("secondaryColor", 0.1f, 0.1f, 0.8f).vec3("textColor", 1f, 1f, 1f)
            .bool("show", true).float("animationProgress", 1f).string("position", "Bottom").build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(cc)
        graph.addNode(lowerThird)
        graph.addNode(output)

        graph.connect("camera_anchor", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "cc_broadcast", "input")
        graph.connect("cc_broadcast", "output", "lt_news", "input")
        graph.connect("lt_news", "output", "output_1", "input")

        return PresetInfo(
            id = "news_broadcast",
            name = "News Broadcast",
            description = "News anchor with broadcast lower third and color correction",
            json = graph.toJson()
        )
    }

    // 9. Screen Share – MediaProjection screen capture as base, camera PiP overlay (tutorials)
    fun screenSharePreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val screen = factory.createNode("ScreenCapture", "screen_capture_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).int("dpi", 320)
            .bool("showCursor", true).bool("isCapturing", false).build())

        val camera = factory.createNode("CameraInput", "camera_pip", NodeParameters.builder()
            .int("width", 320).int("height", 240).bool("mirrored", true).build())

        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())

        val blend = factory.createNode("Blend", "blend_screen", NodeParameters.builder()
            .string("mode", "Normal").float("opacity", 1f).build())

        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(screen)
        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(blend)
        graph.addNode(output)

        graph.connect("camera_pip", "output", "center_stage_1", "input")
        graph.connect("screen_capture_1", "output", "blend_screen", "base")
        graph.connect("center_stage_1", "output", "blend_screen", "blend")
        graph.connect("blend_screen", "output", "output_1", "input")

        return PresetInfo(
            id = "screen_share",
            name = "Screen Share",
            description = "Phone screen via MediaProjection + camera PiP – perfect for tutorials, demos, app reviews",
            json = graph.toJson()
        )
    }

    // 10. Tutorial – screen capture + camera + text overlay (huge for tutorials and gaming)
    fun tutorialPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val screen = factory.createNode("ScreenCapture", "screen_capture_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).int("dpi", 320)
            .bool("isCapturing", false).build())

        val camera = factory.createNode("CameraInput", "camera_pip", NodeParameters.builder()
            .int("width", 320).int("height", 240).bool("mirrored", true).build())

        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", true).bool("smoothing", true)
            .string("trackingMode", "SINGLE").build())

        val blend = factory.createNode("Blend", "blend_tutorial", NodeParameters.builder()
            .string("mode", "Normal").float("opacity", 1f).build())

        val text = factory.createNode("TextOverlay", "tutorial_title", NodeParameters.builder()
            .string("text", "Tutorial – Screen + Camera").int("fontSize", 32)
            .vec4("color", 1f, 1f, 1f, 1f).vec4("backgroundColor", 0f, 0f, 0f, 0.6f)
            .float("x", 0.5f).float("y", 0.05f).string("animation", "Fade").build())

        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(screen)
        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(blend)
        graph.addNode(text)
        graph.addNode(output)

        graph.connect("camera_pip", "output", "center_stage_1", "input")
        graph.connect("screen_capture_1", "output", "blend_tutorial", "base")
        graph.connect("center_stage_1", "output", "blend_tutorial", "blend")
        graph.connect("blend_tutorial", "output", "tutorial_title", "input")
        graph.connect("tutorial_title", "output", "output_1", "input")

        return PresetInfo(
            id = "tutorial",
            name = "Tutorial",
            description = "Screen capture + camera PiP + title – show your screen while on camera for tutorials",
            json = graph.toJson()
        )
    }

    // 11. Screen PiP – camera as base, screen as small overlay (gaming content)
    fun screenPipPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())

        val screen = factory.createNode("ScreenCapture", "screen_capture_1", NodeParameters.builder()
            .int("width", 640).int("height", 360).int("dpi", 320)
            .bool("isCapturing", false).build())

        val blend = factory.createNode("Blend", "blend_pip", NodeParameters.builder()
            .string("mode", "Normal").float("opacity", 1f).build())

        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(screen)
        graph.addNode(blend)
        graph.addNode(output)

        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "blend_pip", "base")
        graph.connect("screen_capture_1", "output", "blend_pip", "blend")
        graph.connect("blend_pip", "output", "output_1", "input")

        return PresetInfo(
            id = "screen_pip",
            name = "Screen PiP",
            description = "Camera full-screen + screen capture PiP – gaming commentary style",
            json = graph.toJson()
        )
    }

    // 12. Gaming Screen – screen capture full, camera small, lower third (gaming content)
    fun gamingScreenPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val screen = factory.createNode("ScreenCapture", "screen_capture_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).int("dpi", 320)
            .bool("isCapturing", false).build())

        val camera = factory.createNode("CameraInput", "cam_small", NodeParameters.builder()
            .int("width", 320).int("height", 240).bool("mirrored", true).build())

        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())

        val blend = factory.createNode("Blend", "blend_game", NodeParameters.builder()
            .string("mode", "Normal").float("opacity", 1f).build())

        val lowerThird = factory.createNode("LowerThird", "lt_gaming", NodeParameters.builder()
            .string("title", "LIVE GAMEPLAY").string("subtitle", "Screen + Camera")
            .string("style", "Gaming").vec3("primaryColor", 0.2f, 0.8f, 0.2f)
            .vec3("secondaryColor", 0.1f, 0.1f, 0.1f).vec3("textColor", 1f, 1f, 1f)
            .bool("show", true).float("animationProgress", 1f).string("position", "Bottom").build())

        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(screen)
        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(blend)
        graph.addNode(lowerThird)
        graph.addNode(output)

        graph.connect("cam_small", "output", "center_stage_1", "input")
        graph.connect("screen_capture_1", "output", "blend_game", "base")
        graph.connect("center_stage_1", "output", "blend_game", "blend")
        graph.connect("blend_game", "output", "lt_gaming", "input")
        graph.connect("lt_gaming", "output", "output_1", "input")

        return PresetInfo(
            id = "gaming_screen",
            name = "Gaming Screen",
            description = "Phone screen capture for gaming + camera PiP + lower third – huge for gaming content",
            json = graph.toJson()
        )
    }

    // Beauty preset – skin smoothing, face slimming, eye brightening using ML Kit landmarks
    fun beautyPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val skinSmoothing = factory.createNode("SkinSmoothing", "skin_smoothing_1", NodeParameters.builder()
            .float("smoothing", 0.6f).float("blurRadius", 1.5f).bool("skinMask", true).bool("faceOnly", true).float("edgeThreshold", 0.2f).build())
        val faceSlimming = factory.createNode("FaceSlimming", "face_slimming_1", NodeParameters.builder()
            .float("slimming", 0.5f).float("vShape", 0.3f).bool("faceOnly", true).build())
        val eyeBrightening = factory.createNode("EyeBrightening", "eye_brightening_1", NodeParameters.builder()
            .float("brightening", 0.6f).float("eyeSize", 0.1f).bool("catchlight", true).float("saturation", 0.2f).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(skinSmoothing)
        graph.addNode(faceSlimming)
        graph.addNode(eyeBrightening)
        graph.addNode(output)

        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "skin_smoothing_1", "input")
        graph.connect("skin_smoothing_1", "output", "face_slimming_1", "input")
        graph.connect("face_slimming_1", "output", "eye_brightening_1", "input")
        graph.connect("eye_brightening_1", "output", "output_1", "input")

        return PresetInfo(
            id = "beauty",
            name = "Beauty – Skin Smooth + Slim + Eye Bright",
            description = "Beauty filters using ML Kit face landmarks: skin smoothing (bilateral + skin mask), face slimming (V-shape warp), eye brightening (whites + catchlight)",
            json = graph.toJson()
        )
    }

    // Face filter preset – Snapchat-style sunglasses, hats, masks
    fun faceFilterPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val faceFilter = factory.createNode("FaceFilter", "face_filter_1", NodeParameters.builder()
            .string("filterType", "sunglasses").float("intensity", 1f).bool("autoScale", true).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(faceFilter)
        graph.addNode(output)

        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "face_filter_1", "input")
        graph.connect("face_filter_1", "output", "output_1", "input")

        return PresetInfo(
            id = "face_filter",
            name = "Face Filter – Sunglasses, Hats, Masks",
            description = "Snapchat-style face filters using ML Kit Face Mesh: sunglasses at eyes, hat above head, mask covering nose-mouth, dog/cat ears, mustache, crown",
            json = graph.toJson()
        )
    }

    // Beauty + Filter combo – full pipeline
    fun beautyFilterComboPreset(context: Context): PresetInfo {
        val graph = CompositorGraph()
        val factory = CompositorNodeFactory(context)

        val camera = factory.createNode("CameraInput", "camera_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val centerStage = factory.createNode("CenterStage", "center_stage_1", NodeParameters.builder()
            .float("centerX", 0.5f).float("centerY", 0.5f).float("zoom", 1f)
            .float("maxZoom", 3f).float("padding", 1.8f)
            .bool("enabled", false).bool("smoothing", true)
            .string("trackingMode", "GROUP").build())
        val skinSmoothing = factory.createNode("SkinSmoothing", "skin_smoothing_1", NodeParameters.builder()
            .float("smoothing", 0.5f).float("blurRadius", 1.2f).bool("skinMask", true).bool("faceOnly", true).build())
        val eyeBrightening = factory.createNode("EyeBrightening", "eye_brightening_1", NodeParameters.builder()
            .float("brightening", 0.5f).float("eyeSize", 0.08f).bool("catchlight", true).build())
        val faceFilter = factory.createNode("FaceFilter", "face_filter_1", NodeParameters.builder()
            .string("filterType", "sunglasses").float("intensity", 1f).build())
        val output = factory.createNode("Output", "output_1", NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())

        graph.addNode(camera)
        graph.addNode(centerStage)
        graph.addNode(skinSmoothing)
        graph.addNode(eyeBrightening)
        graph.addNode(faceFilter)
        graph.addNode(output)

        graph.connect("camera_1", "output", "center_stage_1", "input")
        graph.connect("center_stage_1", "output", "skin_smoothing_1", "input")
        graph.connect("skin_smoothing_1", "output", "eye_brightening_1", "input")
        graph.connect("eye_brightening_1", "output", "face_filter_1", "input")
        graph.connect("face_filter_1", "output", "output_1", "input")

        return PresetInfo(
            id = "beauty_filter_combo",
            name = "Beauty + Face Filter Combo",
            description = "Full beauty pipeline: CenterStage + skin smoothing + eye brightening + Snapchat filter (sunglasses/hat/mask) – ML Kit landmarks",
            json = graph.toJson()
        )
    }

    /**
     * Write all preset JSONs to files for debugging / external use.
     */
    fun writePresetsToFiles(context: Context) {
        try {
            val dir = context.filesDir.resolve("compositor_presets")
            dir.mkdirs()
            for (preset in getAllPresets(context)) {
                val file = dir.resolve("${preset.id}.json")
                file.writeText(preset.json)
            }
        } catch (e: Exception) {
            android.util.Log.w("CompositorPresets", "Failed to write presets", e)
        }
    }

    /**
     * Load preset JSON from assets if available, fallback to generated.
     */
    fun loadPresetFromAssets(context: Context, presetId: String): String? {
        return try {
            context.assets.open("compositor_presets/${presetId}.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            getPresetJson(context, presetId)
        }
    }
}
