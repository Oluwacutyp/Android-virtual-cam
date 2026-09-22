package com.androidvirtualcam.compositor

import android.content.Context
import com.androidvirtualcam.compositor.nodes.*

/**
 * Production NodeFactory – creates nodes by type string.
 * Handles all 12 node types for deserialization.
 */
class CompositorNodeFactory(private val context: Context) : CompositorGraph.NodeFactory {

    override fun createNode(type: String, id: String, parameters: NodeParameters): CompositorNode {
        return when (type) {
            "CameraInput" -> CameraInputNode(id = id, parameters = parameters)
            "ScreenCapture" -> ScreenCaptureNode(id = id, parameters = parameters)
            "VideoFile" -> VideoFileNode(id = id, context = context, parameters = parameters)
            "Image" -> ImageNode(id = id, context = context, parameters = parameters)
            "ChromaKey" -> ChromaKeyNode(id = id, parameters = parameters)
            "LUT" -> LUTNode(id = id, context = context, parameters = parameters)
            "Blur" -> BlurNode(id = id, parameters = parameters)
            "Blend" -> BlendNode(id = id, parameters = parameters)
            "ColorCorrection" -> ColorCorrectionNode(id = id, parameters = parameters)
            "TextOverlay" -> TextOverlayNode(id = id, parameters = parameters)
            "LowerThird" -> LowerThirdNode(id = id, parameters = parameters)
            "BackgroundReplace" -> BackgroundReplaceNode(id = id, context = context, parameters = parameters)
            "SegmentationMask" -> SegmentationMaskNode(id = id, context = context, parameters = parameters)
            "CenterStage" -> CenterStageNode(id = id, parameters = parameters)
            "Transition" -> TransitionNode(id = id, parameters = parameters)
            "SkinSmoothing" -> SkinSmoothingNode(id = id, parameters = parameters)
            "FaceSlimming" -> FaceSlimmingNode(id = id, parameters = parameters)
            "EyeBrightening" -> EyeBrighteningNode(id = id, parameters = parameters)
            "FaceFilter" -> FaceFilterNode(id = id, context = context, parameters = parameters)
            "Output" -> OutputNode(id = id, parameters = parameters)
            else -> throw IllegalArgumentException("Unknown node type: $type")
        }
    }

    companion object {
        val SUPPORTED_TYPES = listOf(
            "CameraInput", "ScreenCapture", "VideoFile", "Image", "ChromaKey", "LUT", "Blur",
            "Blend", "ColorCorrection", "TextOverlay", "LowerThird",
            "BackgroundReplace", "SegmentationMask", "CenterStage", "Transition",
            "SkinSmoothing", "FaceSlimming", "EyeBrightening", "FaceFilter", "Output"
        )
    }
}
