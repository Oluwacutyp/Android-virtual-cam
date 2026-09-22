package com.androidvirtualcam.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.androidvirtualcam.service.VirtualCameraService
import com.androidvirtualcam.voice.VoiceChangerEngine

/**
 * Settings for ManyCam-like free virtual camera on Android.
 * - Toggle system-wide virtual camera (requires root + LSPosed)
 * - Voice changer customization
 * - Hook target apps selection
 * - Instructions
 */
@OptIn(ExperimentalMaterial3Api::class)
class VirtualCamSettingsActivity : ComponentActivity() {

    private val voiceEngine = VoiceChangerEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val context = LocalContext.current
                var isVirtualCamEnabled by remember { mutableStateOf(false) }
                var selectedVoice by remember { mutableStateOf(VoiceChangerEngine.VoiceProfile.presets().first()) }
                var pitch by remember { mutableStateOf(0f) } // semitones, 0 = normal
                var showRootInstructions by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Virtual Cam Settings – ManyCam Free") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("System-Wide Virtual Camera", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Injects composed video (camera + overlays + text) into ANY app that uses camera – WhatsApp, IMO, Telegram, Zoom, TikTok, etc. Like ManyCam but 100% free and open source.",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Switch(
                                            checked = isVirtualCamEnabled,
                                            onCheckedChange = { enabled ->
                                                isVirtualCamEnabled = enabled
                                                if (enabled) {
                                                    val intent = Intent(context, VirtualCameraService::class.java).apply {
                                                        action = VirtualCameraService.ACTION_START
                                                    }
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                        context.startForegroundService(intent)
                                                    } else {
                                                        context.startService(intent)
                                                    }
                                                    Toast.makeText(context, "Virtual Camera Active – Works system-wide with LSPosed", Toast.LENGTH_LONG).show()
                                                } else {
                                                    val intent = Intent(context, VirtualCameraService::class.java).apply {
                                                        action = VirtualCameraService.ACTION_STOP
                                                    }
                                                    context.startService(intent)
                                                    Toast.makeText(context, "Virtual Camera Stopped", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(if (isVirtualCamEnabled) "ACTIVE" else "INACTIVE", color = if (isVirtualCamEnabled) Color.Green else Color.Red)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { showRootInstructions = true }) {
                                        Icon(Icons.Default.Info, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Root Setup Instructions")
                                    }
                                }
                            }
                        }

                        item {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Voice Changer – Customizable", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Built-in voice changer that works system-wide. Your changed voice is injected into any app's mic – like ManyCam voice changer but free.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(12.dp))

                                    // Voice presets – safe Kotlin with enabled/value parsing
                                    Text("Presets:", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(8.dp))
                                    val presets = VoiceChangerEngine.VoiceProfile.presets()
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        presets.forEach { preset ->
                                            FilterChip(
                                                selected = selectedVoice.name == preset.name,
                                                onClick = {
                                                    selectedVoice = preset
                                                    pitch = preset.pitchSemitones
                                                    val safePitch = pitch.coerceIn(-12f, 12f)
                                                    voiceEngine.setProfile(preset.copy(pitchSemitones = safePitch))
                                                },
                                                label = { Text(preset.name) },
                                                leadingIcon = {
                                                    if (selectedVoice.name == preset.name) Icon(Icons.Default.Check, contentDescription = null)
                                                }
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(16.dp))
                                    Text("Pitch: ${"%.1f".format(pitch)} semitones", color = Color.White)
                                    Slider(
                                        value = pitch,
                                        onValueChange = { pitch = it },
                                        valueRange = -12f..12f,
                                        steps = 23,
                                        onValueChangeFinished = {
                                            val safePitch = pitch.coerceIn(-12f, 12f)
                                            val custom = selectedVoice.copy(name = "Custom", pitchSemitones = safePitch)
                                            selectedVoice = custom
                                            voiceEngine.setProfile(custom)
                                        }
                                    )
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("Deep", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                        Text("Normal", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                        Text("Chipmunk", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            voiceEngine.start()
                                            Toast.makeText(context, "Voice changer preview started", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.Mic, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Test Voice")
                                        }
                                        OutlinedButton(onClick = {
                                            voiceEngine.stop()
                                        }) {
                                            Icon(Icons.Default.Stop, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Stop")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("How It Works – Free ManyCam for Android", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        """
                                        • Standalone Studio: Compose camera + overlays, text, backgrounds, filters, chroma key, blur – GPU accelerated.

                                        • System-Wide Injection (Root): Xposed module hooks Camera1, Camera2, CameraX, ImageReader, WebRTC, AudioRecord. Your composed feed appears in ANY app.

                                        • Voice Changer: Pitch, robot, echo, deep, chipmunk – customizable, injected system-wide via AudioRecord hook.

                                        • No Desktop Needed: Pure Android solution. No PC companion required.

                                        • 100% Free: Apache 2.0, no watermark, no subscription, no proprietary libs.

                                        Root Required for system-wide? Yes – Android security prevents fake camera without root. For non-root, app works as standalone studio with recording & streaming.

                                        Installation for system-wide:
                                        1. Root with Magisk, enable Zygisk
                                        2. Install LSPosed Zygisk version
                                        3. Install this APK
                                        4. Enable in LSPosed Manager, select target apps
                                        5. Reboot
                                        6. Open this app, enable Virtual Camera toggle
                                        7. Open WhatsApp/IMO/etc – they see virtual feed!

                                        Non-root alternative: Use LSPatch to patch target APK (some apps detect).
                                        """.trimIndent(),
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                if (showRootInstructions) {
                    AlertDialog(
                        onDismissRequest = { showRootInstructions = false },
                        title = { Text("Root Setup – Free ManyCam Android") },
                        text = {
                            Text(
                                """
                                Requirements:
                                • Magisk 26+ with Zygisk enabled
                                • LSPosed Zygisk (latest)
                                • Android 8.0+ (minSdk 26)

                                Steps:
                                1. Install Magisk, patch boot.img, flash, enable Zygisk in Magisk settings
                                2. Install LSPosed APK (Zygisk version), reboot
                                3. Open LSPosed Manager → Modules → Enable Android Virtual Cam
                                4. Select apps: WhatsApp, Telegram, IMO, Instagram, TikTok, Zoom, Discord, etc.
                                5. Reboot again
                                6. Open VirtualCam app → Settings → Enable Virtual Camera
                                7. Compose your scene (add overlays, text, voice changer)
                                8. Open target app – virtual feed appears!

                                Troubleshooting:
                                • Black screen? Grant root, check logcat for VCamFrameBus – SharedMemory bus active, FD server listening on abstract vcam_frame_bus
                                • App not hooked? Ensure LSPosed scope includes it, check getOrCreateBus() logs
                                • Voice not changed? Enable voice changer toggle and select effect, check AudioHooks logs
                                • Android 11+ restrictions: Use LSPosed with Zygisk, not Riru, ensure SELinux allows LocalSocket
                                • Frame transport: SharedMemory 3-slot ring buffer, Unix socket handshake only on startup – no file polling

                                Free & Open Source – No watermark! Production-grade SharedMemory bus.
                                """.trimIndent()
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showRootInstructions = false }) { Text("Got it") }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine.stop()
    }
}
