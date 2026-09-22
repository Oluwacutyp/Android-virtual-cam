package com.androidvirtualcam.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.androidvirtualcam.streaming.StreamingProtocol
import com.androidvirtualcam.streaming.StreamingStatus
import com.androidvirtualcam.ui.components.BitrateGraph
import com.androidvirtualcam.ui.components.HealthDot
import com.androidvirtualcam.ui.components.StreamHealth
import com.androidvirtualcam.ui.viewmodels.DestinationUiState
import com.androidvirtualcam.ui.viewmodels.StreamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPanelScreen(
    viewModel: StreamViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val destinations by viewModel.destinations.collectAsState()
    val isAdding by viewModel.isAdding.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stream Manager – Multi-Destination") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startAll() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start All", tint = Color(0xFF3DDC84))
                    }
                    IconButton(onClick = { viewModel.stopAll() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop All", tint = Color.Red)
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Destination")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Color(0xFF3DDC84),
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        },
        containerColor = Color(0xFF0F0F0F)
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Multi-Destination Streaming", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Text("Up to 3 simultaneous RTMP/SRT/RTSP + RTSP server 8554", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${destinations.size}/3", color = Color(0xFF3DDC84), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            if (destinations.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1E1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                            Text("No destinations", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Text("Add RTMP, SRT, or RTSP server for OBS/VLC", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = { showAddDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DDC84), contentColor = Color.Black)
                            ) {
                                Text("Add Destination")
                            }
                        }
                    }
                }
            }

            items(destinations) { dest ->
                DestinationStatusCard(
                    uiState = dest,
                    onStart = { viewModel.startStreaming(dest.session.id) },
                    onStop = { viewModel.stopStreaming(dest.session.id) },
                    onRemove = { viewModel.removeDestination(dest.session.id) }
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Adaptive Bitrate – 6M→4M→2.5M→1.5M→800k", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text("Monitors network every 2s (RTT + packet loss), drops on congestion, probes up every 30s, switches to audio-only below 400k", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("Reconnect: exponential backoff 1s,2s,4s,8s,16s cap 30s", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (showAddDialog) {
            AddDestinationDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { url, protocol, bitrate, width, height ->
                    viewModel.addDestination(url, protocol, bitrate, width, height)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
private fun DestinationStatusCard(
    uiState: DestinationUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit
) {
    val session = uiState.session

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HealthDot(status = uiState.health, modifier = Modifier.size(12.dp))
                    Column {
                        Text(
                            text = when (session.protocol) {
                                StreamingProtocol.RTMP -> "RTMP"
                                StreamingProtocol.SRT -> "SRT"
                                StreamingProtocol.RTSP -> "RTSP"
                                StreamingProtocol.RTSP_SERVER -> "RTSP Server :8554"
                                else -> session.protocol.name
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (session.protocol == StreamingProtocol.RTSP_SERVER) "LAN – OBS/VLC" else session.url.take(40),
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = if (session.status == StreamingStatus.LIVE) onStop else onStart, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (session.status == StreamingStatus.LIVE) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (session.status == StreamingStatus.LIVE) "Stop" else "Start",
                            tint = if (session.status == StreamingStatus.LIVE) Color.Red else Color(0xFF3DDC84)
                        )
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Gray)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusChip(label = session.status.name, color = when (session.status) {
                    StreamingStatus.LIVE -> Color(0xFF4CAF50)
                    StreamingStatus.CONNECTING, StreamingStatus.RECONNECTING -> Color(0xFFFFC107)
                    StreamingStatus.FAILED -> Color(0xFFF44336)
                    else -> Color.Gray
                })
                StatusChip(label = "${session.width}x${session.height}", color = Color(0xFF2196F3))
                StatusChip(label = "${session.currentBitrate / 1000}k", color = Color(0xFFBB86FC))
                if (session.isAudioOnly) {
                    StatusChip(label = "Audio Only", color = Color(0xFFFF9800))
                }
            }

            BitrateGraph(
                bitrateHistory = uiState.bitrateHistory,
                currentBitrate = session.currentBitrate / 1000,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Duration: ${formatDuration(session.getDuration())}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    Text("Bytes: ${formatBytes(session.bytesSent)}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("RTT: ${session.rttMs}ms", color = if (session.rttMs > 300) Color.Yellow else Color.Gray, style = MaterialTheme.typography.labelSmall)
                    Text("Loss: ${"%.1f".format(session.packetLoss * 100)}%", color = if (session.packetLoss > 0.05f) Color.Red else Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (session.lastError != null) {
                Text("Error: ${session.lastError}", color = Color(0xFFF44336), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
    }
}

@Composable
private fun AddDestinationDialog(
    onDismiss: () -> Unit,
    onAdd: (String, StreamingProtocol, Int, Int, Int) -> Unit
) {
    var url by remember { mutableStateOf("rtmp://") }
    var protocol by remember { mutableStateOf(StreamingProtocol.RTMP) }
    var bitrate by remember { mutableStateOf("4000") }
    var width by remember { mutableStateOf("1280") }
    var height by remember { mutableStateOf("720") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Streaming Destination") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (rtmp://, srt://, rtsp:// or empty for RTSP server)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = protocol == StreamingProtocol.RTMP, onClick = { protocol = StreamingProtocol.RTMP }, label = { Text("RTMP") })
                    FilterChip(selected = protocol == StreamingProtocol.SRT, onClick = { protocol = StreamingProtocol.SRT }, label = { Text("SRT") })
                    FilterChip(selected = protocol == StreamingProtocol.RTSP, onClick = { protocol = StreamingProtocol.RTSP }, label = { Text("RTSP") })
                    FilterChip(selected = protocol == StreamingProtocol.RTSP_SERVER, onClick = { protocol = StreamingProtocol.RTSP_SERVER }, label = { Text("Server 8554") })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = bitrate, onValueChange = { bitrate = it }, label = { Text("Bitrate kbps") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = width, onValueChange = { width = it }, label = { Text("Width") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text("Height") }, modifier = Modifier.weight(1f))
                }

                Text("Up to 3 simultaneous, separate encoders, shared EGL, independent bitrate/resolution", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val br = (bitrate.toIntOrNull() ?: 4000) * 1000
                    val w = width.toIntOrNull() ?: 1280
                    val h = height.toIntOrNull() ?: 720
                    onAdd(url, protocol, br, w, h)
                }
            ) { Text("Add & Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
