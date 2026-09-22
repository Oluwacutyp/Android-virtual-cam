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
import com.androidvirtualcam.ui.viewmodels.AudioSourceViewModel
import com.androidvirtualcam.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    audioSourceViewModel: AudioSourceViewModel? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVirtualCamActive by viewModel.isVirtualCamActive.collectAsState()
    val lsposedStatus by viewModel.lsposedStatus.collectAsState()
    val hookLogs by viewModel.hookLogs.collectAsState()
    val performanceOverlay by viewModel.performanceOverlayEnabled.collectAsState()
    val selectedResolution by viewModel.selectedResolution.collectAsState()
    val selectedBitrate by viewModel.selectedBitrate.collectAsState()
    val selectedFps by viewModel.selectedFps.collectAsState()

    var showResolutionPicker by remember { mutableStateOf(false) }
    var showBitratePicker by remember { mutableStateOf(false) }
    var showFpsPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings – Broadcast Studio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
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
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Virtual Camera", color = Color.White, style = MaterialTheme.typography.titleSmall)
                                Text("System-wide injection via LSPosed", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = isVirtualCamActive,
                                onCheckedChange = { viewModel.toggleVirtualCamera() },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3DDC84))
                            )
                        }

                        Divider(color = Color.White.copy(alpha = 0.1f))

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (lsposedStatus.isModuleEnabled) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (lsposedStatus.isModuleEnabled) Color(0xFF4CAF50) else Color(0xFFF44336),
                                modifier = Modifier.size(20.dp)
                            )
                            Text("LSPosed Module: ${if (lsposedStatus.isModuleEnabled) "Enabled" else "Disabled"}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (lsposedStatus.isZygiskEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (lsposedStatus.isZygiskEnabled) Color(0xFF4CAF50) else Color(0xFFFFC107),
                                modifier = Modifier.size(20.dp)
                            )
                            Text("Zygisk: ${if (lsposedStatus.isZygiskEnabled) "Enabled" else "Disabled – Enable in Magisk"}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }

                        Text("Version: ${lsposedStatus.version}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)

                        Text("Hooked Apps:", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            lsposedStatus.hookedApps.forEach { app ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(16.dp))
                                    Text(app, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Performance", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Switch(
                                checked = performanceOverlay,
                                onCheckedChange = { viewModel.togglePerformanceOverlay() },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3DDC84))
                            )
                        }

                        Text("Resolution / Bitrate / FPS", color = Color.White, style = MaterialTheme.typography.labelMedium)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showResolutionPicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(selectedResolution)
                            }
                            OutlinedButton(
                                onClick = { showBitratePicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DataUsage, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(selectedBitrate)
                            }
                            OutlinedButton(
                                onClick = { showFpsPicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(selectedFps)
                            }
                        }

                        Text("Encoder: H264 High Profile Level 4.1 | Audio: AAC 48kHz Mono 128k | EGL Context Sharing", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2410)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Face, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(24.dp))
                                Column {
                                    Text("Center Stage", color = Color.White, style = MaterialTheme.typography.titleSmall)
                                    Text("Apple Center Stage on Android", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFFFC107))
                        }

                        Divider(color = Color.White.copy(alpha = 0.1f))

                        Text("Auto-pans and zooms to keep your face centered as you move", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Text("• ML Kit Face Detection 15fps FAST mode + tracking", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• Smooth exponential decay panSpeed 2.2 zoomSpeed 1.2 with deadzone", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• GROUP mode: keeps all faces in frame • SINGLE: tracks largest face", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• Digital zoom via GPU crop shader – no optical zoom needed", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• Works front & rear camera, handles mirroring, clamps to bounds", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• Face lost: slowly returns to center 0.5,0.5 zoom 1.0 after 2s", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• Compositor node: Camera -> CenterStage -> Segmentation/Effects -> Output", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Technical Details", color = Color(0xFFFFC107), style = MaterialTheme.typography.labelMedium)
                                Text("Engine: CenterStageEngine (ML Kit) + CenterStageSmoother + CenterStageManager + CenterStageNode", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                Text("Shader: uv = center + (vTexCoord-0.5)/zoom, clamp 0..1, OES+2D support", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                Text("Camera: ImageAnalysis 640x480 YUV_420_888 KEEP_ONLY_LATEST + InputImage", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                Text("Target calc: paddedBox = faceBox * 1.8 * headroom 1.2, zoom = 1/max(paddedW,paddedH) clamped 1..3", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                Text("Presets: all 10 presets include center_stage_1 disabled by default", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Text("Control Center Stage from Main Screen – toggle, tracking mode, max zoom, padding sliders", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Hook Validator – Log Viewer", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { viewModel.refreshHookLogs() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF3DDC84))
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF121212))
                                .padding(8.dp)
                        ) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(hookLogs) { log ->
                                    Text(
                                        text = log,
                                        color = when {
                                            log.contains("ERROR", ignoreCase = true) || log.contains("failed", ignoreCase = true) -> Color(0xFFF44336)
                                            log.contains("WARN", ignoreCase = true) -> Color(0xFFFFC107)
                                            log.contains("success", ignoreCase = true) || log.contains("hooked", ignoreCase = true) -> Color(0xFF4CAF50)
                                            else -> Color.Gray
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (hookLogs.isEmpty()) {
                                    item {
                                        Text("No logs – Ensure LSPosed module enabled and rebooted", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        Text("Validates Camera1, Camera2, CameraX, ImageReader, WebRTC, AudioRecord hooks", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            item {
                if (audioSourceViewModel != null) {
                    AudioSourceSettingsCard(viewModel = audioSourceViewModel)
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("About – Free ManyCam Alternative", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text("Android Virtual Cam – 100% free, no watermark, Apache 2.0", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("Features: Compositor graph, segmentation (no green screen), multi-destination RTMP/SRT/RTSP server 8554, voice changer, system-wide injection via LSPosed", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("Modules: Compositor, Segmentation, Streaming, Voice, Xposed Hooks, Broadcast UI", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("New: Beauty filters (skin smoothing, face slimming, eye brightening via ML Kit landmarks), Face filters (sunglasses, hats, masks), Music ducking (VAD), USB-C/Bluetooth/lavalier mic, Quick widget", color = Color(0xFF3DDC84), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (showResolutionPicker) {
            PickerDialog(
                title = "Resolution",
                options = viewModel.getResolutionOptions(),
                selected = selectedResolution,
                onDismiss = { showResolutionPicker = false },
                onSelected = { viewModel.setResolution(it); showResolutionPicker = false }
            )
        }
        if (showBitratePicker) {
            PickerDialog(
                title = "Bitrate",
                options = viewModel.getBitrateOptions(),
                selected = selectedBitrate,
                onDismiss = { showBitratePicker = false },
                onSelected = { viewModel.setBitrate(it); showBitratePicker = false }
            )
        }
        if (showFpsPicker) {
            PickerDialog(
                title = "FPS",
                options = viewModel.getFpsOptions(),
                selected = selectedFps,
                onDismiss = { showFpsPicker = false },
                onSelected = { viewModel.setFps(it); showFpsPicker = false }
            )
        }
    }
}

@Composable
private fun AudioSourceSettingsCard(viewModel: AudioSourceViewModel) {
    val devices by viewModel.devices.collectAsState()
    val selectedSourceType by viewModel.selectedSourceType.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val deviceDescription by viewModel.deviceDescription.collectAsState()
    val isBluetoothScoOn by viewModel.isBluetoothScoOn.collectAsState()
    val isDuckingEnabled by viewModel.isDuckingEnabled.collectAsState()
    val vadLevel by viewModel.vadLevel.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Audio Source – USB-C / Bluetooth / Lavalier", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(deviceDescription, color = Color(0xFF3DDC84), style = MaterialTheme.typography.bodySmall)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.refreshDevices() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DDC84), contentColor = Color.Black)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh")
                }
                Button(onClick = { viewModel.toggleBluetoothSco() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (isBluetoothScoOn) Color(0xFF3DDC84) else Color(0xFF2D2D2D))) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isBluetoothScoOn) "SCO ON" else "SCO OFF")
                }
            }

            Text("Source Types – MediaRecorder.AudioSource", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                viewModel.getAvailableSourceTypes().forEach { type ->
                    val isSelected = type == selectedSourceType
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF3DDC84) else Color(0xFF2D2D2D)),
                        shape = RoundedCornerShape(8.dp),
                        onClick = { viewModel.setAudioSourceType(type) }
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(type.displayName, color = if (isSelected) Color.Black else Color.White, style = MaterialTheme.typography.bodyMedium)
                                Text("Source=${type.mediaRecorderSource}", color = if (isSelected) Color.Black.copy(alpha=0.7f) else Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                            if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black)
                        }
                    }
                }
            }

            Text("Detected Input Devices – AudioManager.getDevices()", color = Color.White, style = MaterialTheme.typography.labelMedium)
            if (devices.isEmpty()) {
                Text("No external devices – using built-in mic", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { device ->
                        val isSelected = device.id == selectedDevice?.id
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF03DAC6) else Color(0xFF2D2D2D)),
                            shape = RoundedCornerShape(8.dp),
                            onClick = { viewModel.selectDevice(device) }
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${device.type.displayName}: ${device.name}", color = if (isSelected) Color.Black else Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Text("ID=${device.id} available=${device.isAvailable}", color = if (isSelected) Color.Black.copy(alpha=0.7f) else Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                                if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black)
                            }
                        }
                    }
                }
            }

            Divider(color = Color.White.copy(alpha=0.1f))

            Text("Music Ducking – VAD auto lowers music when speaking", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Ducking Enabled", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Switch(checked = isDuckingEnabled, onCheckedChange = { viewModel.setDuckingEnabled(it) }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3DDC84)))
            }
            Text("VAD Level: ${String.format("%.2f", vadLevel)} speaking=$isSpeaking threshold 0.6 – music ducks to 15% over 300ms when speaking, rises over 800ms after 500ms silence", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(progress = { vadLevel }, modifier = Modifier.fillMaxWidth(), color = if (isSpeaking) Color(0xFF3DDC84) else Color.Gray)
        }
    }
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options) { option ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (option == selected) Color(0xFF3DDC84) else Color(0xFF2D2D2D)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        onClick = { onSelected(option) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(option, color = if (option == selected) Color.Black else Color.White)
                            if (option == selected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
