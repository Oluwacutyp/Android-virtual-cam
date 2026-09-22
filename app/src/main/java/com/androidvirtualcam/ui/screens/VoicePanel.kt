package com.androidvirtualcam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.androidvirtualcam.ui.components.VUMeter
import com.androidvirtualcam.ui.components.WaveformVisualizer
import com.androidvirtualcam.ui.viewmodels.VoiceViewModel
import com.androidvirtualcam.voice.VoiceChangerEngine
import com.androidvirtualcam.voice.VoiceCloneManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePanelScreen(
    viewModel: VoiceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive by viewModel.isActive.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val pitch by viewModel.pitch.collectAsState()
    val tempo by viewModel.tempo.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val vuLevel by viewModel.vuLevel.collectAsState()
    val cloneProfiles by viewModel.cloneProfiles.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isModelAvailable by viewModel.isModelAvailable.collectAsState()
    val isModelDownloading by viewModel.isModelDownloading.collectAsState()
    val modelDownloadProgress by viewModel.modelDownloadProgress.collectAsState()
    val modelDownloadError by viewModel.modelDownloadError.collectAsState()
    val showModelDownloadDialog by viewModel.showModelDownloadDialog.collectAsState()
    val isRecordingClone by viewModel.isRecordingClone.collectAsState()
    val cloneRecordingProgress by viewModel.cloneRecordingProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Studio – 60fps Visualizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Switch(
                        checked = isActive,
                        onCheckedChange = { viewModel.toggleVoiceChanger() },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3DDC84))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isActive) "ON" else "OFF", color = if (isActive) Color(0xFF3DDC84) else Color.Gray)
                    Spacer(Modifier.width(12.dp))
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
            if (errorMessage != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFCF6679).copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(errorMessage!!, color = Color(0xFFCF6679), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.clearError() }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color(0xFFCF6679))
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
                            Text("Waveform – 60fps", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                VUMeter(level = vuLevel, modifier = Modifier.height(80.dp))
                                Column {
                                    Text("VU", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                    Text("${(vuLevel * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        WaveformVisualizer(isActive = isActive, waveformData = waveformData, modifier = Modifier.fillMaxWidth())
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
                        Text("Presets – ManyCam-like Voice Changer", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.height(200.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(VoiceChangerEngine.VoiceProfile.presets()) { preset ->
                                val isSelected = selectedPreset.name == preset.name
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0xFF3DDC84) else Color(0xFF2D2D2D)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    onClick = { viewModel.selectPreset(preset) }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            when (preset.name) {
                                                "Chipmunk" -> Icons.Default.ChildCare
                                                "Deep" -> Icons.Default.RecordVoiceOver
                                                "Robot" -> Icons.Default.SmartToy
                                                "Echo" -> Icons.Default.Speaker
                                                else -> Icons.Default.Mic
                                            },
                                            contentDescription = null,
                                            tint = if (isSelected) Color.Black else Color.White
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(preset.name, color = if (isSelected) Color.Black else Color.White, style = MaterialTheme.typography.labelMedium)
                                        Text("${preset.pitchSemitones} st", color = if (isSelected) Color.Black.copy(alpha = 0.7f) else Color.Gray, style = MaterialTheme.typography.labelSmall)
                                    }
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
                        Text("Pitch / Tempo – Customizable", color = Color.White, style = MaterialTheme.typography.titleSmall)

                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Pitch: ${"%.1f".format(pitch)}x", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Text("Deep ← → Chipmunk", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                            Slider(
                                value = pitch,
                                onValueChange = { viewModel.setPitch(it) },
                                valueRange = -12f..12f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF3DDC84), activeTrackColor = Color(0xFF3DDC84))
                            )
                        }

                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tempo: ${"%.1f".format(tempo)}x", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Text("Slow ← → Fast", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                            Slider(
                                value = tempo,
                                onValueChange = { viewModel.setTempo(it) },
                                valueRange = 0.5f..2.0f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFBB86FC), activeTrackColor = Color(0xFFBB86FC))
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.testVoice() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DDC84), contentColor = Color.Black)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Test Voice")
                            }
                            OutlinedButton(onClick = { viewModel.toggleVoiceChanger() }) {
                                Icon(if (isActive) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(if (isActive) "Stop" else "Enable")
                            }
                        }
                        Text(
                            "Test Voice uses fallback AudioTrack if native lib fails – always plays 440Hz tone with current pitch",
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
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
                            Text("Clone Profiles", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = {
                                viewModel.onAddCloneProfileClicked()
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add Clone Profile", tint = Color(0xFF3DDC84))
                            }
                        }

                        Text(
                            if (isModelAvailable) "Model ready – tap + to record 10s sample" else "Model not downloaded – tap + to download 95MB ONNX",
                            color = if (isModelAvailable) Color(0xFF3DDC84) else Color(0xFFCF6679),
                            style = MaterialTheme.typography.labelSmall
                        )

                        if (isModelDownloading) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Downloading model... ${(modelDownloadProgress * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(
                                    progress = { modelDownloadProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF3DDC84)
                                )
                                Text("${(modelDownloadProgress * 95).toInt()}MB / 95MB", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                if (modelDownloadError != null) {
                                    Text("Error: $modelDownloadError", color = Color(0xFFCF6679), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (isRecordingClone) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Recording clone sample... ${(cloneRecordingProgress * 10).toInt()}s / 10s", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(
                                    progress = { cloneRecordingProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFFBB86FC)
                                )
                            }
                        }

                        if (cloneProfiles.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF2D2D2D)).padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(32.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text("No clone profiles yet", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    Text("Record 10s sample to clone voice via ONNX", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                cloneProfiles.forEach { profile ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(profile.name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                                Text("Created: ${profile.createdAt}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                            }
                                            Row {
                                                IconButton(onClick = {
                                                    android.util.Log.i("VoicePanel", "Play clone ${profile.id}")
                                                }) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play Clone", tint = Color(0xFF3DDC84))
                                                }
                                                IconButton(onClick = { viewModel.deleteCloneProfile(profile.id) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showModelDownloadDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissModelDownloadDialog() },
                title = { Text("Voice Clone Model Required") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Voice Clone requires downloading a 95MB model file. Download now?")
                        Text("Model: ECAPA-TDNN speaker encoder (256-dim d-vector)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text("URL: ${VoiceCloneManager.MODEL_DOWNLOAD_URL}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text("Manual: adb push speaker_encoder.onnx /data/data/com.androidvirtualcam/files/", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        if (modelDownloadError != null) {
                            Text("Last error: $modelDownloadError", color = Color(0xFFCF6679), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.downloadModel() },
                        enabled = !isModelDownloading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DDC84), contentColor = Color.Black)
                    ) {
                        Text(if (isModelDownloading) "Downloading..." else "Download Now (95MB)")
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            viewModel.dismissModelDownloadDialog()
                            viewModel.startCloneRecording()
                        }) {
                            Text("Use Fallback")
                        }
                        TextButton(onClick = { viewModel.dismissModelDownloadDialog() }) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }
    }
}
