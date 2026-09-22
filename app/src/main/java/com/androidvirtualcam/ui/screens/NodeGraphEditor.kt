package com.androidvirtualcam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.androidvirtualcam.compositor.CompositorNode
import com.androidvirtualcam.compositor.NodeParameters
import com.androidvirtualcam.compositor.ParamType
import com.androidvirtualcam.ui.components.NodeCard
import com.androidvirtualcam.ui.viewmodels.NodeGraphViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeGraphEditorScreen(
    viewModel: NodeGraphViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val graph by viewModel.graph.collectAsState()
    val nodePositions by viewModel.nodePositions.collectAsState()
    val selectedNodeId by viewModel.selectedNodeId.collectAsState()
    val connections by viewModel.connections.collectAsState()
    val draggingConnection by viewModel.draggingConnection.collectAsState()
    val canvasOffset by viewModel.canvasOffset.collectAsState()
    val canvasScale by viewModel.canvasScale.collectAsState()

    val nodes = graph.getNodes()

    var showAddNodeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Node Graph Editor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddNodeDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Node")
                    }
                    IconButton(onClick = {
                        viewModel.panCanvas(androidx.compose.ui.geometry.Offset.Zero - viewModel.canvasOffset.value)
                        // Reset zoom to 1x
                        viewModel.zoomCanvas(1f / viewModel.canvasScale.value, androidx.compose.ui.geometry.Offset.Zero)
                    }) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Center and Reset Zoom")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        bottomBar = {
            if (selectedNodeId != null) {
                val selectedNode = viewModel.getSelectedNode()
                if (selectedNode != null) {
                    ParamPanel(
                        node = selectedNode,
                        onParamChange = { key, value -> viewModel.updateNodeParameter(selectedNode.id, key, value) },
                        onClose = { viewModel.selectNode(null) }
                    )
                }
            }
        },
        containerColor = Color(0xFF0F0F0F)
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F0F0F))
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        viewModel.panCanvas(pan)
                        viewModel.zoomCanvas(zoom, centroid)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        viewModel.panCanvas(dragAmount)
                    }
                }
        ) {
            // Grid background
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF121212))
            )

            // Connection lines Canvas
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                connections.forEach { conn ->
                    val fromPos = nodePositions[conn.fromNodeId]
                    val toPos = nodePositions[conn.toNodeId]
                    if (fromPos != null && toPos != null) {
                        val fromAdjusted = (fromPos + canvasOffset) * canvasScale + Offset(180.dp.toPx() / 2, 40.dp.toPx())
                        val toAdjusted = (toPos + canvasOffset) * canvasScale + Offset(0f, 40.dp.toPx())
                        drawLine(
                            color = Color(0xFF3DDC84),
                            start = fromAdjusted,
                            end = toAdjusted,
                            strokeWidth = 2.dp.toPx() * canvasScale
                        )
                    }
                }
                // Dragging connection preview
                draggingConnection?.let { drag ->
                    val fromPos = nodePositions[drag.fromNodeId]
                    if (fromPos != null) {
                        val fromAdjusted = (fromPos + canvasOffset) * canvasScale + Offset(180.dp.toPx() / 2, 40.dp.toPx())
                        val toAdjusted = (drag.currentPosition + canvasOffset) * canvasScale
                        drawLine(
                            color = Color(0xFFBB86FC).copy(alpha = 0.8f),
                            start = fromAdjusted,
                            end = toAdjusted,
                            strokeWidth = 2.dp.toPx() * canvasScale
                        )
                    }
                }
            }

            // Nodes
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                nodes.forEach { node ->
                    val pos = nodePositions[node.id] ?: Offset(100f, 100f)
                    val adjustedPos = (pos + canvasOffset) * canvasScale

                    Box(
                        modifier = Modifier
                            .offset(x = adjustedPos.x.dp, y = adjustedPos.y.dp)
                            .pointerInput(node.id) {
                                detectDragGestures(
                                    onDragStart = { viewModel.selectNode(node.id) },
                                    onDrag = { change, dragAmount ->
                                        viewModel.moveNode(node.id, dragAmount / canvasScale)
                                    }
                                )
                            }
                    ) {
                        NodeCard(
                            node = node,
                            isSelected = selectedNodeId == node.id,
                            onTap = { viewModel.selectNode(node.id) },
                            onDelete = { viewModel.deleteNode(node.id) }
                        )
                    }
                }
            }

            // Top info
            Card(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.9f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Nodes: ${nodes.size} | Connections: ${connections.size}".toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text(text = "Pinch to zoom, drag to pan, tap node to edit params, drag between sockets to connect".toString(), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    Text(text = "Scale: ${"%.1f".format(canvasScale)}x".toString(), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }

            // Add node FAB
            FloatingActionButton(
                onClick = { showAddNodeDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = Color(0xFF3DDC84),
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Node")
            }
        }

        if (showAddNodeDialog) {
            AddNodeDialog(
                onDismiss = { showAddNodeDialog = false },
                onNodeSelected = { type ->
                    viewModel.addNode(type)
                    showAddNodeDialog = false
                }
            )
        }
    }
}

@Composable
private fun ParamPanel(
    node: CompositorNode,
    onParamChange: (String, Any) -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Node: ${node.type} (${node.id})".toString(), color = Color.White, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                }
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(node.parameters.all().toList()) { pair ->
                    val key = pair.first
                    val param = pair.second
                    when (param.type) {
                        ParamType.FLOAT -> {
                            val value = node.parameters.getFloat(key, 0f)
                            Column {
                                Text(text = "$key: ${"%.2f".format(value)}".toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = value,
                                    onValueChange = { onParamChange(key, it) },
                                    valueRange = 0f..1f
                                )
                            }
                        }
                        ParamType.INT -> {
                            val value = node.parameters.getInt(key, 0)
                            Column {
                                Text(text = "$key: $value".toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = value.toFloat(),
                                    onValueChange = { onParamChange(key, it.toInt()) },
                                    valueRange = 0f..100f
                                )
                            }
                        }
                        ParamType.BOOL -> {
                            val value = node.parameters.getBool(key, false)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = key.toString(), color = Color.White)
                                Switch(checked = value, onCheckedChange = { onParamChange(key, it) })
                            }
                        }
                        ParamType.STRING -> {
                            val value = node.parameters.getString(key, "")
                            Column {
                                Text(text = key.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { onParamChange(key, it) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        ParamType.COLOR -> {
                            val value = node.parameters.getString(key, "#FFFFFF")
                            Column {
                                Text(text = "$key: $value".toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { onParamChange(key, it) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        else -> {
                            Text(text = "$key: ${param.value}".toString(), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddNodeDialog(
    onDismiss: () -> Unit,
    onNodeSelected: (String) -> Unit
) {
    val nodeTypes = listOf(
        "CameraInput" to "Camera",
        "SegmentationMask" to "Segmentation Mask (No Green Screen)",
        "BackgroundReplace" to "Background Replace",
        "ChromaKey" to "Chroma Key",
        "Blur" to "Blur",
        "LUT" to "LUT",
        "Image" to "Image",
        "VideoFile" to "Video File",
        "TextOverlay" to "Text Overlay",
        "LowerThird" to "Lower Third",
        "Blend" to "Blend",
        "ColorCorrection" to "Color Correction"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add Node – Professional Broadcast") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nodeTypes) { pair ->
                    val type = pair.first
                    val display = pair.second
                    Card(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = display.toString(), color = Color.White)
                            Button(onClick = { onNodeSelected(type) }) {
                                Text(text = "Add")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "Close") }
        }
    )
}
