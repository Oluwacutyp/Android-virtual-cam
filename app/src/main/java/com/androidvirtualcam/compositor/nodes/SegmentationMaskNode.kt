package com.androidvirtualcam.compositor.nodes

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.util.Log
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.segmentation.BackgroundSegmentationEngine

/**
 * SegmentationMaskNode – runs BackgroundSegmentationEngine (MediaPipe Selfie Segmentation GPU 30fps)
 * and outputs alpha mask as GL texture directly (no CPU readback).
 *
 * Inputs:
 * - input: foreground texture2D(camera)
 *
 * Parameters:
 * - enableTemporalFiltering: bool (default true)
 * - blendCurrent: float 0.7
 * - blendPrevious: float 0.3
 * - useGpuDelegate: bool true
 *
 * Outputs:
 * - mask texture2D(R channel contains confidence 0..1)
 *
 * Feeds into BackgroundReplaceNode.
 * Works without green screen on both front and rear cameras.
 * Fallback to ML Kit Subject Segmentation if MediaPipe fails.
 */
class SegmentationMaskNode(
    override val id: String,
    private val context: Context,
    override var parameters: NodeParameters = NodeParameters.builder()
        .bool("enableTemporalFiltering", true)
        .float("blendCurrent", 0.7f)
        .float("blendPrevious", 0.3f)
        .bool("useGpuDelegate", true)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "SegmentationMask",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var segmentationEngine: BackgroundSegmentationEngine? = null
    private var lastBitmap: Bitmap? = null
    private var width = 0
    private var height = 0

    override fun onInitialize() {
        Log.d("SegmentationMaskNode", "Initialized $id")
    }

    fun initializeEngine(w: Int, h: Int, sharedEglContext: android.opengl.EGLContext? = null) {
        width = w
        height = h
        if (segmentationEngine == null) {
            segmentationEngine = BackgroundSegmentationEngine(context).apply {
                if (sharedEglContext != null) {
                    setSharedEglContext(sharedEglContext)
                }
                initialize(w, h)
            }
            Log.i("SegmentationMaskNode", "Segmentation engine initialized ${w}x${h} GPU delegate")
        }
    }

    fun processBitmap(bitmap: Bitmap, timestampMs: Long) {
        if (width == 0 || height == 0) {
            width = bitmap.width
            height = bitmap.height
            initializeEngine(width, height)
        }
        segmentationEngine?.processBitmap(bitmap, timestampMs)
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("SegmentationMaskNode $id requires input")

        if (segmentationEngine == null || width != input.width || height != input.height) {
            initializeEngine(input.width, input.height)
        }

        val engine = segmentationEngine ?: throw IllegalStateException("Segmentation engine not initialized")

        return engine.processGLTexture(input, System.currentTimeMillis())
    }

    fun getMaskTexture(): GLTexture {
        return segmentationEngine?.getMaskTexture()
            ?: throw IllegalStateException("Mask texture not available")
    }

    fun setBackgroundType(type: BackgroundSegmentationEngine.BackgroundType) {
        segmentationEngine?.setBackgroundType(type)
    }

    override fun onRelease() {
        try {
            segmentationEngine?.release()
        } catch (e: Exception) {
            Log.w("SegmentationMaskNode", "Release failed: ${e.message}")
        }
        segmentationEngine = null
        lastBitmap?.recycle()
        lastBitmap = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}
}
