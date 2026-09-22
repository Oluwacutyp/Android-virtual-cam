package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidvirtualcam.compositor.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class NodePosition(
    val id: String,
    var offset: Offset
)

data class ConnectionDrag(
    val fromNodeId: String,
    val fromSocketId: String,
    var currentPosition: Offset
)

class NodeGraphViewModel(
    private val context: Context
) : ViewModel() {

    private val factory = CompositorNodeFactory(context)

    private val _graph = MutableStateFlow(CompositorGraph())
    val graph: StateFlow<CompositorGraph> = _graph

    private val _nodePositions = MutableStateFlow<Map<String, Offset>>(emptyMap())
    val nodePositions: StateFlow<Map<String, Offset>> = _nodePositions

    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId

    private val _connections = MutableStateFlow<List<CompositorGraph.Connection>>(emptyList())
    val connections: StateFlow<List<CompositorGraph.Connection>> = _connections

    private val _draggingConnection = MutableStateFlow<ConnectionDrag?>(null)
    val draggingConnection: StateFlow<ConnectionDrag?> = _draggingConnection

    private val _canvasOffset = MutableStateFlow(Offset.Zero)
    val canvasOffset: StateFlow<Offset> = _canvasOffset

    private val _canvasScale = MutableStateFlow(1f)
    val canvasScale: StateFlow<Float> = _canvasScale

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    init {
        loadDefaultGraph()
    }

    private fun loadDefaultGraph() {
        viewModelScope.launch {
            try {
                val json = com.androidvirtualcam.compositor.presets.CompositorPresets.loadPresetFromAssets(context, "main_camera")
                    ?: com.androidvirtualcam.compositor.presets.CompositorPresets.getPresetJson(context, "main_camera")
                if (json != null) {
                    val newGraph = CompositorGraph()
                    newGraph.fromJson(json, factory)
                    _graph.value = newGraph
                    _connections.value = newGraph.getConnections()
                    val positions = mutableMapOf<String, Offset>()
                    var x = 100f
                    for (node in newGraph.getNodes()) {
                        positions[node.id] = Offset(x, 200f)
                        x += 250f
                    }
                    _nodePositions.value = positions
                } else {
                    _errorMessage.value = "Default preset main_camera not found"
                    createEmptyGraph()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load graph: ${e.message}"
                android.util.Log.e("NodeGraphVM", "Failed to load graph", e)
                createEmptyGraph()
            }
        }
    }

    private fun createEmptyGraph() {
        try {
            val emptyGraph = CompositorGraph()
            val cameraNode = factory.createNode("CameraInput", "camera_1", NodeParameters.builder().int("width", 1280).int("height", 720).build())
            val outputNode = factory.createNode("Output", "output_1", NodeParameters.builder().int("width", 1280).int("height", 720).build())
            emptyGraph.addNode(cameraNode)
            emptyGraph.addNode(outputNode)
            emptyGraph.connect("camera_1", "output", "output_1", "input")
            _graph.value = emptyGraph
            _nodePositions.value = mapOf("camera_1" to Offset(100f, 200f), "output_1" to Offset(400f, 200f))
            _connections.value = emptyGraph.getConnections()
        } catch (re: Exception) {
            android.util.Log.w("NodeGraphVM", "Failed to create empty graph: ${re.message}")
        }
    }

    fun selectNode(nodeId: String?) {
        _selectedNodeId.value = nodeId
    }

    fun moveNode(nodeId: String, delta: Offset) {
        val current = _nodePositions.value.toMutableMap()
        val pos = current[nodeId] ?: Offset.Zero
        current[nodeId] = pos + delta
        _nodePositions.value = current
    }

    fun addNode(type: String) {
        val id = "${type.lowercase()}_${System.currentTimeMillis()}"
        val params = when (type) {
            "CameraInput" -> NodeParameters.builder().int("width", 1280).int("height", 720).build()
            "BackgroundReplace" -> NodeParameters.builder().string("backgroundType", "blur").float("blurRadius", 15f).build()
            "SegmentationMask" -> NodeParameters.builder().bool("enableTemporalFiltering", true).float("blendCurrent", 0.7f).build()
            "Blur" -> NodeParameters.builder().float("sigma", 8f).build()
            "Image" -> NodeParameters.builder().string("resName", "").build()
            else -> NodeParameters.builder().build()
        }
        try {
            val node = factory.createNode(type, id, params)
            val newGraph = _graph.value
            newGraph.addNode(node)
            _graph.value = newGraph
            _nodePositions.value = _nodePositions.value + (id to Offset(300f, 300f))
            _connections.value = newGraph.getConnections()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to add node $type: ${e.message}"
            android.util.Log.e("NodeGraphVM", "Failed to add node $type", e)
        }
    }

    fun deleteNode(nodeId: String) {
        try {
            val newGraph = _graph.value
            newGraph.removeNode(nodeId)
            _graph.value = newGraph
            _nodePositions.value = _nodePositions.value - nodeId
            _connections.value = newGraph.getConnections()
            if (_selectedNodeId.value == nodeId) _selectedNodeId.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Failed to delete node $nodeId: ${e.message}"
            android.util.Log.e("NodeGraphVM", "Failed to delete node $nodeId", e)
        }
    }

    fun startConnectionDrag(fromNodeId: String, fromSocketId: String, startPos: Offset) {
        _draggingConnection.value = ConnectionDrag(fromNodeId, fromSocketId, startPos)
    }

    fun updateConnectionDrag(position: Offset) {
        _draggingConnection.value?.let { drag ->
            _draggingConnection.value = drag.copy(currentPosition = position)
        }
    }

    fun endConnectionDrag(toNodeId: String?, toSocketId: String?) {
        val drag = _draggingConnection.value
        _draggingConnection.value = null
        if (drag != null && toNodeId != null && toSocketId != null) {
            try {
                val graph = _graph.value
                val fromNode = graph.getNode(drag.fromNodeId)
                val toNode = graph.getNode(toNodeId)
                if (fromNode != null && toNode != null) {
                    graph.connect(drag.fromNodeId, drag.fromSocketId, toNodeId, toSocketId)
                    _connections.value = graph.getConnections()
                } else {
                    _errorMessage.value = "Connection failed: node not found"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to connect: ${e.message}"
                android.util.Log.e("NodeGraphVM", "Failed to connect", e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun updateNodeParameter(nodeId: String, key: String, value: Any) {
        val graph = _graph.value
        val node = graph.getNode(nodeId) ?: return
        var newParams = node.parameters
        when (value) {
            is Float -> newParams = newParams.withFloat(key, value)
            is Int -> newParams = newParams.withInt(key, value)
            is Boolean -> newParams = newParams.withBool(key, value)
            is String -> newParams = newParams.withString(key, value)
            is List<*> -> {
                // Explicit cast with Suppress – fixes Kotlin unchecked cast error
                val list = value as List<Float>
                when (list.size) {
                    3 -> newParams = newParams.withVec3(key, list)
                    4 -> newParams = newParams.withVec4(key, list)
                }
            }
        }
        try {
            val field = node.javaClass.getDeclaredField("parameters")
            field.isAccessible = true
            field.set(node, newParams)
        } catch (e: Exception) {
            android.util.Log.w("NodeGraphVM", "Failed to update param $key on $nodeId", e)
        }
    }

    fun panCanvas(delta: Offset) {
        _canvasOffset.value = _canvasOffset.value + delta
    }

    fun zoomCanvas(scale: Float, focus: Offset) {
        val newScale = (_canvasScale.value * scale).coerceIn(0.3f, 3f)
        _canvasScale.value = newScale
    }

    fun getSelectedNode(): CompositorNode? {
        val id = _selectedNodeId.value ?: return null
        return _graph.value.getNode(id)
    }
}
