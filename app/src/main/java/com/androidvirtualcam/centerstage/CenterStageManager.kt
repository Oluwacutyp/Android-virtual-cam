package com.androidvirtualcam.centerstage

import android.content.Context
import android.util.Log
import com.androidvirtualcam.compositor.CompositorRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manager that owns CenterStageEngine and updates CompositorRenderer's CenterStageNode
 * at 30fps with smoothed transform.
 *
 * Usage:
 * - Call initialize(renderer) after renderer is created
 * - CameraManager feeds InputImages via processInputImage()
 * - Manager auto-updates node params on GL thread via renderer's update method
 */
class CenterStageManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "CenterStageManager"
        private const val UPDATE_INTERVAL_MS = 33L // ~30fps update to GL
    }

    private val engine = CenterStageEngine()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null
    private var compositorRenderer: CompositorRenderer? = null

    val state: StateFlow<CenterStageState> = engine.state

    private var isInitialized = false

    fun initialize(renderer: CompositorRenderer) {
        compositorRenderer = renderer
        isInitialized = true
        startUpdateLoop()
        Log.i(TAG, "CenterStageManager initialized")
    }

    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (true) {
                try {
                    engine.tick()
                    val current = engine.getCurrentTransform()
                    updateRendererNode(current.first, current.second, current.third)
                } catch (e: Exception) {
                    Log.w(TAG, "Update loop error: ${e.message}")
                }
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun updateRendererNode(centerX: Float, centerY: Float, zoom: Float) {
        val renderer = compositorRenderer ?: return
        val enabled = engine.state.value.isEnabled
        try {
            // Update via renderer – this will use reflection to set parameters field
            // We update both via parameter API and direct node method if available
            renderer.updateNodeParameter("center_stage_1", "centerX", centerX)
            renderer.updateNodeParameter("center_stage_1", "centerY", centerY)
            renderer.updateNodeParameter("center_stage_1", "zoom", zoom)
            renderer.updateNodeParameter("center_stage_1", "enabled", enabled)

            // Also try direct method for lower latency (if node has setTransform)
            val node = renderer.graph.getNode("center_stage_1") as? com.androidvirtualcam.compositor.nodes.CenterStageNode
            node?.setTransform(centerX, centerY, zoom, enabled)

        } catch (e: Exception) {
            // Node may not exist yet – try to insert it
            tryInsertCenterStageNode()
        }
    }

    private fun tryInsertCenterStageNode() {
        val renderer = compositorRenderer ?: return
        try {
            val graph = renderer.graph
            if (graph.getNode("center_stage_1") != null) return

            // Find camera and output nodes to insert between
            val cameraNode = graph.getNodes().find { it.type == "CameraInput" }
            val outputNode = graph.getNodes().find { it.type == "Output" }

            if (cameraNode == null || outputNode == null) {
                Log.w(TAG, "Cannot insert CenterStage – camera or output not found")
                return
            }

            // Create node
            val factory = com.androidvirtualcam.compositor.CompositorNodeFactory(context)
            val params = com.androidvirtualcam.compositor.NodeParameters.builder()
                .float("centerX", 0.5f)
                .float("centerY", 0.5f)
                .float("zoom", 1f)
                .float("maxZoom", engine.state.value.maxZoom)
                .float("padding", engine.state.value.paddingFactor)
                .bool("enabled", engine.state.value.isEnabled)
                .bool("smoothing", true)
                .string("trackingMode", engine.state.value.trackingMode.name)
                .build()

            val centerStageNode = factory.createNode("CenterStage", "center_stage_1", params)

            // We need to rewire: camera -> centerStage -> output (or whatever was between)
            // Find what output's input is currently connected to – now getConnections returns List<Connection>
            val connections = graph.getConnections()
            val outputInputConn = connections.find { it.toNodeId == outputNode.id }

            // Get all connections where from is camera
            val cameraDownstreams = connections.filter { it.fromNodeId == cameraNode.id }

            // Check if output is directly connected to camera
            val isDirect = outputInputConn?.fromNodeId == cameraNode.id
            if (isDirect) {
                graph.disconnect(outputNode.id, outputInputConn!!.toInputId)
                graph.addNode(centerStageNode)
                graph.connect(cameraNode.id, "output", "center_stage_1", "input")
                graph.connect("center_stage_1", "output", outputNode.id, outputInputConn.toInputId)
                Log.i(TAG, "Inserted CenterStage node between camera and output")
            } else {
                val firstAfterCamera = cameraDownstreams.firstOrNull()
                if (firstAfterCamera != null) {
                    val toNodeId = firstAfterCamera.toNodeId
                    val toInputId = firstAfterCamera.toInputId
                    graph.disconnect(toNodeId, toInputId)
                    graph.addNode(centerStageNode)
                    graph.connect(cameraNode.id, "output", "center_stage_1", "input")
                    graph.connect("center_stage_1", "output", toNodeId, toInputId)
                    Log.i(TAG, "Inserted CenterStage node between camera and $toNodeId")
                } else {
                    graph.addNode(centerStageNode)
                    Log.i(TAG, "Added CenterStage node without auto-connect")
                }
            }

            // Initialize new node
            renderer.makeCurrent()
            centerStageNode.initialize()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert CenterStage node", e)
        }
    }

    fun processInputImage(image: com.google.mlkit.vision.common.InputImage, isMirrored: Boolean) {
        engine.processInputImage(image, isMirrored)
    }

    fun processBitmap(bitmap: android.graphics.Bitmap, isMirrored: Boolean, rotation: Int = 0) {
        engine.processBitmap(bitmap, isMirrored, rotation)
    }

    fun setEnabled(enabled: Boolean) {
        engine.setEnabled(enabled)
        Log.i(TAG, "CenterStage enabled=$enabled")
    }

    fun toggle() {
        setEnabled(!engine.state.value.isEnabled)
    }

    fun setTrackingMode(mode: CenterStageState.TrackingMode) {
        engine.setTrackingMode(mode)
    }

    fun setMaxZoom(zoom: Float) {
        engine.setMaxZoom(zoom)
        compositorRenderer?.updateNodeParameter("center_stage_1", "maxZoom", zoom)
    }

    fun setPadding(padding: Float) {
        engine.setPaddingFactor(padding)
        compositorRenderer?.updateNodeParameter("center_stage_1", "padding", padding)
    }

    fun setSmoothing(panSpeed: Float, zoomSpeed: Float) {
        engine.setSmoothingSpeed(panSpeed, zoomSpeed)
    }

    fun isEnabled(): Boolean = engine.state.value.isEnabled

    fun release() {
        updateJob?.cancel()
        updateJob = null
        engine.release()
        compositorRenderer = null
        isInitialized = false
        Log.i(TAG, "CenterStageManager released")
    }
}
