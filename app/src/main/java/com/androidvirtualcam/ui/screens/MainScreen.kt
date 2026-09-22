package com.androidvirtualcam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androidvirtualcam.centerstage.CenterStageState
import com.androidvirtualcam.screen.ScreenCaptureManager
import com.androidvirtualcam.transition.TransitionConfig
import com.androidvirtualcam.transition.TransitionEasing
import com.androidvirtualcam.transition.TransitionState
import com.androidvirtualcam.transition.TransitionType
import com.androidvirtualcam.ui.components.RawCameraPreview
import com.androidvirtualcam.ui.components.EffectsPreviewToggleCard
import com.androidvirtualcam.ui.components.TeleprompterControlCard
import com.androidvirtualcam.ui.viewmodels.AudioSourceViewModel
import com.androidvirtualcam.ui.viewmodels.BeautyViewModel
import com.androidvirtualcam.ui.viewmodels.InstantReplayViewModel
import com.androidvirtualcam.ui.viewmodels.MainViewModel
import com.androidvirtualcam.ui.viewmodels.ScreenCaptureViewModel
import com.androidvirtualcam.ui.viewmodels.TeleprompterViewModel
import com.androidvirtualcam.ui.viewmodels.WebControlViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    screenCaptureViewModel: ScreenCaptureViewModel,
    teleprompterViewModel: TeleprompterViewModel,
    webControlViewModel: WebControlViewModel,
    instantReplayViewModel: InstantReplayViewModel,
    beautyViewModel: BeautyViewModel,
    audioSourceViewModel: AudioSourceViewModel,
    onNavigateToNodeEditor: () -> Unit,
    onNavigateToVoice: () -> Unit,
    onNavigateToStream: () -> Unit,
    onNavigateToScenes: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sceneCollection by mainViewModel.sceneCollection.collectAsState()
    val isRecording by mainViewModel.isRecording.collectAsState()
    val isStreaming by mainViewModel.isStreaming.collectAsState()
    val isVirtualCamActive by mainViewModel.isVirtualCamActive.collectAsState()
    val performanceOverlay by mainViewModel.performanceOverlayEnabled.collectAsState()
    val centerStageState by mainViewModel.centerStageState.collectAsState()
    val transitionState by mainViewModel.transitionState.collectAsState()
    val transitionConfig by mainViewModel.transitionConfig.collectAsState()
    val isScreenCapturing by screenCaptureViewModel.isCapturing.collectAsState()
    val screenCaptureState by screenCaptureViewModel.captureState.collectAsState()
    val screenError by screenCaptureViewModel.errorMessage.collectAsState()
    val teleprompterState by teleprompterViewModel.state.collectAsState()
    val isWebControlRunning by webControlViewModel.isServerRunning.collectAsState()
    val webControlUrl by webControlViewModel.serverUrl.collectAsState()
    val isReplaying by instantReplayViewModel.isReplaying.collectAsState()
    val replayBufferDuration by instantReplayViewModel.bufferDurationSec.collectAsState()
    val replayBufferSize by instantReplayViewModel.bufferSizeMB.collectAsState()
    val isReplaySaving by instantReplayViewModel.isSaving.collectAsState()
    val lastSavedFile by instantReplayViewModel.lastSavedFile.collectAsState()
    val replayError by instantReplayViewModel.errorMessage.collectAsState()
    val isAutoStartEnabled by instantReplayViewModel.isAutoStartEnabled.collectAsState()
    val replayConfig by instantReplayViewModel.config.collectAsState()

    // Beauty
    val isBeautyEnabled by beautyViewModel.isBeautyEnabled.collectAsState()
    val skinSmoothing by beautyViewModel.skinSmoothing.collectAsState()
    val faceSlimming by beautyViewModel.faceSlimming.collectAsState()
    val eyeBrightening by beautyViewModel.eyeBrightening.collectAsState()
    val eyeEnlarge by beautyViewModel.eyeEnlarge.collectAsState()
    val faceCount by beautyViewModel.faceCount.collectAsState()
    val currentFilter by beautyViewModel.currentFilter.collectAsState()
    val filterIntensity by beautyViewModel.filterIntensity.collectAsState()
    val isFilterEnabled by beautyViewModel.isFilterEnabled.collectAsState()

    // Audio
    val audioDevices by audioSourceViewModel.devices.collectAsState()
    val selectedSourceType by audioSourceViewModel.selectedSourceType.collectAsState()
    val selectedDevice by audioSourceViewModel.selectedDevice.collectAsState()
    val isBluetoothScoOn by audioSourceViewModel.isBluetoothScoOn.collectAsState()
    val deviceDescription by audioSourceViewModel.deviceDescription.collectAsState()
    val isDuckingEnabled by audioSourceViewModel.isDuckingEnabled.collectAsState()
    val isMusicPlaying by audioSourceViewModel.isMusicPlaying.collectAsState()
    val musicVolume by audioSourceViewModel.musicVolume.collectAsState()
    val isDucked by audioSourceViewModel.isDucked.collectAsState()
    val vadLevel by audioSourceViewModel.vadLevel.collectAsState()
    val isSpeaking by audioSourceViewModel.isSpeaking.collectAsState()
    val uploadedImageUri by mainViewModel.uploadedImageUri.collectAsState()
    val uploadedVideoUri by mainViewModel.uploadedVideoUri.collectAsState()
    val uploadedMediaMessage by mainViewModel.uploadedMediaMessage.collectAsState()
    val compositorEnabled by mainViewModel.compositorEnabled.collectAsState()
    val effectsPreviewEnabled by mainViewModel.effectsPreviewEnabled.collectAsState()
    val compositorError by mainViewModel.compositorError.collectAsState()

    val activeScene = sceneCollection.activeScene()

    // Gallery pickers for Image/Video upload – most requested feature
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { mainViewModel.setUploadedImage(it.toString()) }
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { mainViewModel.setUploadedVideo(it.toString()) }
    }

    // STEP 5 – Show camera permission dialog properly before preview
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[android.Manifest.permission.CAMERA] == true
        android.util.Log.i("MainScreen", "Permission result: $permissions")
    }
    LaunchedEffect(Unit) {
        try {
            val missing = com.androidvirtualcam.platform.PermissionHelper.getMissingPermissions(context)
            hasCameraPermission = !missing.contains(android.Manifest.permission.CAMERA)
            if (!hasCameraPermission) {
                showPermissionDialog = true
            }
        } catch (e: Exception) {
            android.util.Log.w("MainScreen", "Permission check failed", e)
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Camera Permission Required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VirtualCam needs camera permission to show preview and inject into other apps.")
                    Text("This uses CameraX PreviewView WITHOUT custom shaders – cannot crash (fix for error 35633).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("Tap Allow to continue – preview will show immediately after.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showPermissionDialog = false
                    permissionLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.CAMERA,
                            android.Manifest.permission.RECORD_AUDIO
                        )
                    )
                }) { Text("Allow Camera") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("Later") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color(0xFF3DDC84))
                        Text("VCAM STUDIO", style = MaterialTheme.typography.titleMedium)
                        if (isRecording) Badge(containerColor = Color.Red) { Text("REC") }
                        if (isStreaming) Badge(containerColor = Color(0xFF3DDC84), contentColor = Color.Black) { Text("LIVE") }
                        if (isVirtualCamActive) Badge(containerColor = Color(0xFF03DAC6), contentColor = Color.Black) { Text("VCAM") }
                        if (centerStageState.isEnabled) Badge(
                            containerColor = if (centerStageState.isTracking) Color(0xFFFFC107) else Color(0xFF9E9E9E),
                            contentColor = Color.Black
                        ) { Text(if (centerStageState.isTracking) "CENTER STAGE" else "CS IDLE") }
                        if (transitionState.isTransitioning) Badge(
                            containerColor = Color(0xFF9C27B0),
                            contentColor = Color.White
                        ) { Text("${transitionConfig.type} ${(transitionState.progress*100).toInt()}%") }
                    }
                },
                actions = {
                    IconButton(onClick = { mainViewModel.togglePerformanceOverlay() }) {
                        Icon(Icons.Default.Speed, contentDescription = "Performance", tint = if (performanceOverlay) Color(0xFF3DDC84) else Color.Gray)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color.White,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolbarButton(icon = Icons.Default.FiberManualRecord, label = if (isRecording) "Stop" else "Record", tint = if (isRecording) Color.Red else Color.White, onClick = { mainViewModel.toggleRecording() })
                    ToolbarButton(icon = Icons.Default.LiveTv, label = "Stream", tint = if (isStreaming) Color(0xFF3DDC84) else Color.White, onClick = onNavigateToStream)
                    ToolbarButton(icon = Icons.Default.Layers, label = "Scenes", tint = Color.White, onClick = onNavigateToScenes)
                    ToolbarButton(icon = Icons.Default.AccountTree, label = "Nodes", tint = Color.White, onClick = onNavigateToNodeEditor)
                    ToolbarButton(icon = Icons.Default.Mic, label = "Voice", tint = Color.White, onClick = onNavigateToVoice)
                    ToolbarButton(icon = Icons.Default.Settings, label = "Settings", tint = Color.White, onClick = onNavigateToSettings)
                }
            }
        },
        containerColor = Color(0xFF0F0F0F)
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F0F0F))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // STEP 5 – Permission status card if missing
            if (!hasCameraPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3D1A1A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Camera Permission Required", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Text("Tap to grant – raw CameraX preview shows immediately, no crash, no shaders", color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DDC84), contentColor = Color.Black)
                        ) {
                            Text("Grant")
                        }
                    }
                }
            }

            // PURE CameraX PreviewView – ZERO GL, ZERO EGL, ZERO SHADERS in preview path
            // Recording/streaming uses ImageAnalysis ByteArray -> CPU -> MediaCodec
            RawCameraPreview(
                cameraManager = mainViewModel.cameraManager,
                modifier = Modifier.fillMaxWidth(),
                uploadedImageUri = uploadedImageUri,
                uploadedVideoUri = uploadedVideoUri,
                onClearImage = { mainViewModel.clearUploadedImage() },
                onClearVideo = { mainViewModel.clearUploadedVideo() }
            )

            // Image/Video Upload – most requested missing feature
            MediaUploadCard(
                uploadedImageUri = uploadedImageUri,
                uploadedVideoUri = uploadedVideoUri,
                message = uploadedMediaMessage,
                onPickImage = { imagePickerLauncher.launch("image/*") },
                onPickVideo = { videoPickerLauncher.launch("video/*") },
                onClearImage = { mainViewModel.clearUploadedImage() },
                onClearVideo = { mainViewModel.clearUploadedVideo() },
                onClearMessage = { mainViewModel.clearUploadedMediaMessage() }
            )

            // Screen Capture control – MediaProjection
            ScreenCaptureControlCard(
                isCapturing = isScreenCapturing,
                captureState = screenCaptureState,
                errorMessage = screenError,
                onStartCapture = { screenCaptureViewModel.requestPermission() },
                onStopCapture = { screenCaptureViewModel.stopCapture() },
                onSwitchPreset = { presetId ->
                    mainViewModel.switchPreset(presetId, withTransition = true)
                    screenCaptureViewModel.switchToPreset(presetId)
                },
                onClearError = { screenCaptureViewModel.clearError() }
            )

            // Private teleprompter overlay – preview only, NOT in output
            TeleprompterControlCard(
                state = teleprompterState,
                onToggle = { teleprompterViewModel.toggle() },
                onTextChange = { teleprompterViewModel.setText(it) },
                onSpeedChange = { teleprompterViewModel.setSpeed(it) },
                onFontSizeChange = { teleprompterViewModel.setFontSize(it) },
                onMirrorToggle = { teleprompterViewModel.toggleMirror() },
                onVerticalToggle = { teleprompterViewModel.setVerticalMode(!teleprompterState.verticalMode) },
                onOpacityChange = { teleprompterViewModel.setOpacity(it) },
                onBackgroundOpacityChange = { teleprompterViewModel.setBackgroundOpacity(it) },
                onShowBackgroundChange = { teleprompterViewModel.setShowBackground(it) },
                onLoopChange = { teleprompterViewModel.setLoop(it) },
                onAutoScrollChange = { teleprompterViewModel.setAutoScroll(it) }
            )

            // Web Remote Control – control from PC browser on WiFi
            WebControlCard(
                isRunning = isWebControlRunning,
                serverUrl = webControlUrl,
                onToggle = { webControlViewModel.toggleServer() },
                onStart = { webControlViewModel.startServer() },
                onStop = { webControlViewModel.stopServer() }
            )

            // ShadowPlay Instant Replay – always recording last 60s in memory, tap to save
            InstantReplayCard(
                isReplaying = isReplaying,
                bufferDurationSec = replayBufferDuration,
                bufferSizeMB = replayBufferSize,
                isSaving = isReplaySaving,
                lastSavedFile = lastSavedFile,
                errorMessage = replayError,
                isAutoStartEnabled = isAutoStartEnabled,
                config = replayConfig,
                onToggleReplay = { instantReplayViewModel.toggleReplay() },
                onSaveClip = { instantReplayViewModel.saveReplay() },
                onAutoStartChange = { instantReplayViewModel.setAutoStart(it) },
                onDurationChange = { instantReplayViewModel.setMaxDurationSec(it) },
                onBitrateChange = { instantReplayViewModel.setBitrate(it) },
                onClearError = { instantReplayViewModel.clearError() }
            )

            // Beauty filters – skin smoothing, face slimming, eye brightening using ML Kit landmarks
            BeautyControlCard(
                isBeautyEnabled = isBeautyEnabled,
                skinSmoothing = skinSmoothing,
                faceSlimming = faceSlimming,
                eyeBrightening = eyeBrightening,
                eyeEnlarge = eyeEnlarge,
                faceCount = faceCount,
                currentFilter = currentFilter,
                filterIntensity = filterIntensity,
                isFilterEnabled = isFilterEnabled,
                availableFilters = beautyViewModel.getAvailableFilters(),
                onBeautyToggle = { beautyViewModel.setBeautyEnabled(it) },
                onSkinSmoothingChange = { beautyViewModel.setSkinSmoothing(it) },
                onFaceSlimmingChange = { beautyViewModel.setFaceSlimming(it) },
                onEyeBrighteningChange = { beautyViewModel.setEyeBrightening(it) },
                onEyeEnlargeChange = { beautyViewModel.setEyeEnlarge(it) },
                onFilterSelected = { beautyViewModel.setFilter(it) },
                onFilterIntensityChange = { beautyViewModel.setFilterIntensity(it) },
                onFilterToggle = { beautyViewModel.setFilterEnabled(it) }
            )

            // Audio source – USB-C, Bluetooth mic, lavalier + music ducking
            AudioControlCard(
                devices = audioDevices,
                selectedSourceType = selectedSourceType,
                selectedDevice = selectedDevice,
                isBluetoothScoOn = isBluetoothScoOn,
                deviceDescription = deviceDescription,
                isDuckingEnabled = isDuckingEnabled,
                isMusicPlaying = isMusicPlaying,
                musicVolume = musicVolume,
                isDucked = isDucked,
                vadLevel = vadLevel,
                isSpeaking = isSpeaking,
                availableSourceTypes = audioSourceViewModel.getAvailableSourceTypes(),
                onRefreshDevices = { audioSourceViewModel.refreshDevices() },
                onSourceTypeSelected = { audioSourceViewModel.setAudioSourceType(it) },
                onDeviceSelected = { audioSourceViewModel.selectDevice(it) },
                onToggleBluetoothSco = { audioSourceViewModel.toggleBluetoothSco() },
                onDuckingToggle = { audioSourceViewModel.setDuckingEnabled(it) },
                onMusicVolumeChange = { audioSourceViewModel.setMusicVolume(it) },
                onStopMusic = { audioSourceViewModel.stopMusic() }
            )

            // Center Stage control
            CenterStageControlCard(
                state = centerStageState,
                onToggle = { mainViewModel.toggleCenterStage() },
                onTrackingModeChange = { mode -> mainViewModel.setCenterStageTrackingMode(mode) },
                onMaxZoomChange = { zoom -> mainViewModel.setCenterStageMaxZoom(zoom) },
                onPaddingChange = { pad -> mainViewModel.setCenterStagePadding(pad) }
            )

            // Transition selector – professional broadcast
            TransitionControlCard(
                config = transitionConfig,
                state = transitionState,
                onTypeChange = { type -> mainViewModel.setTransitionType(type) },
                onDurationChange = { dur -> mainViewModel.setTransitionDuration(dur) },
                onEasingChange = { easing -> mainViewModel.setTransitionEasing(easing) }
            )

            // Quick scene switch bar – now with animated transitions + screen capture + beauty + filters
            SceneQuickBar(
                presets = listOf("main_camera", "beauty", "face_filter", "beauty_filter_combo", "screen_share", "tutorial", "gaming_screen", "background_segmentation", "pip", "interview"),
                activePreset = "main_camera",
                onPresetSelected = { presetId -> mainViewModel.switchPreset(presetId, withTransition = true) },
                transitionConfig = transitionConfig
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard(title = "Resolution", value = "1280x720", icon = Icons.Default.AspectRatio, modifier = Modifier.weight(1f))
                StatusCard(title = "FPS", value = "30", icon = Icons.Default.Speed, modifier = Modifier.weight(1f))
                StatusCard(title = "Bitrate", value = "4 Mbps", icon = Icons.Default.DataUsage, modifier = Modifier.weight(1f))
            }

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
                        Text("System-Wide Virtual Camera", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (isVirtualCamActive) "Active – Works in WhatsApp, Zoom, etc." else "Inactive – Enable for system-wide injection",
                            color = if (isVirtualCamActive) Color(0xFF3DDC84) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = isVirtualCamActive,
                        onCheckedChange = { mainViewModel.toggleVirtualCamera() },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3DDC84))
                    )
                }
            }

            if (performanceOverlay) {
                PerformanceOverlay()
            }
        }
    }
}


@Composable
private fun MediaUploadCard(
    uploadedImageUri: String?,
    uploadedVideoUri: String?,
    message: String?,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onClearImage: () -> Unit,
    onClearVideo: () -> Unit,
    onClearMessage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A3A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Collections, contentDescription = null, tint = Color(0xFF03DAC6), modifier = Modifier.size(24.dp))
                    Column {
                        Text("Media Upload – Image/Video", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Pick image → overlay, pick video → background – most requested",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Badge(containerColor = Color(0xFF03DAC6), contentColor = Color.Black) {
                    Text("NEW")
                }
            }

            if (message != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A2A)), shape = RoundedCornerShape(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(message, color = Color(0xFF03DAC6), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClearMessage) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPickImage,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03DAC6), contentColor = Color.Black),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pick Image")
                }
                Button(
                    onClick = onPickVideo,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0), contentColor = Color.White),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pick Video")
                }
            }

            if (uploadedImageUri != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A1A)), shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Image Overlay Active", color = Color(0xFF3DDC84), style = MaterialTheme.typography.labelMedium)
                            Text(uploadedImageUri.takeLast(50), color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text("Blended via BlendNode Normal mode over camera – live preview", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = onClearImage) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFCF6679))
                        }
                    }
                }
            }

            if (uploadedVideoUri != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A3A)), shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF9C27B0), modifier = Modifier.size(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Video Background Active", color = Color(0xFF9C27B0), style = MaterialTheme.typography.labelMedium)
                            Text(uploadedVideoUri.takeLast(50), color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text("VideoFileNode OES → Blend base, camera on top – looping muted", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = onClearVideo) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFCF6679))
                        }
                    }
                }
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            Text(
                "How it works: ImageNode loads URI via ContentResolver with downsampling max 1024, uploads to GL_TEXTURE_2D with mipmap. VideoFileNode uses MediaPlayer → SurfaceTexture OES, looping muted. Both injected dynamically into CompositorGraph without preset reload – BlendNode composites. GPU accelerated, #version 100 shaders.",
                color = Color.Gray.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SceneQuickBar(
    presets: List<String>,
    activePreset: String,
    onPresetSelected: (String) -> Unit,
    transitionConfig: TransitionConfig
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E1E1E)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Scenes – ${transitionConfig.type} ${transitionConfig.durationMs}ms", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Icon(Icons.Default.Animation, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(16.dp))
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presets) { preset ->
                val isActive = preset == activePreset
                FilterChip(
                    selected = isActive,
                    onClick = { onPresetSelected(preset) },
                    label = { Text(preset.replace("_", " ").uppercase()) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF3DDC84),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(20.dp))
            Column {
                Text(title, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PerformanceOverlay() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A).copy(alpha = 0.9f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Performance", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text("GPU: 78% | CPU: 45% | Memory: 320MB | Thermal: Normal", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Text("Compositor: 30fps | Segmentation: 30fps GPU | Encoder: 30fps | CenterStage: 15fps | Transitions: 60fps GPU", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransitionControlCard(
    config: TransitionConfig,
    state: TransitionState,
    onTypeChange: (TransitionType) -> Unit,
    onDurationChange: (Long) -> Unit,
    onEasingChange: (TransitionEasing) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isTransitioning) Color(0xFF2A1A2A) else Color(0xFF1E2A1E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Animation, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(24.dp))
                    Column {
                        Text("Transitions", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${config.type} • ${config.durationMs}ms • ${config.easing}",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (state.isTransitioning) {
                    Badge(containerColor = Color(0xFFFFC107), contentColor = Color.Black) {
                        Text("${(state.progress*100).toInt()}%")
                    }
                }
            }

            if (state.isTransitioning) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF3DDC84)
                )
                Text(
                    "${state.fromPresetId} → ${state.toPresetId} • Eased ${String.format("%.2f", state.easedProgress)}",
                    color = Color(0xFFFFC107),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            Text("Type", color = Color.White, style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TransitionType.values().toList()) { type ->
                    val isSelected = config.type == type.name
                    FilterChip(
                        selected = isSelected,
                        onClick = { onTypeChange(type) },
                        label = { Text(type.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF3DDC84),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Text(TransitionType.fromString(config.type).description, color = Color.Gray, style = MaterialTheme.typography.bodySmall)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Duration", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text("${config.durationMs}ms", color = Color(0xFF3DDC84), style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = config.durationMs.toFloat(),
                    onValueChange = { onDurationChange(it.toLong()) },
                    valueRange = 0f..2000f,
                    steps = 19,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF3DDC84), activeTrackColor = Color(0xFF3DDC84))
                )
            }

            Text("Easing", color = Color.White, style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TransitionEasing.values().toList()) { easing ->
                    val isSelected = config.easing == easing.name
                    FilterChip(
                        selected = isSelected,
                        onClick = { onEasingChange(easing) },
                        label = { Text(easing.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF03DAC6),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Text(
                "Fade, Slide (push), Zoom (scale+fade), Stinger (video clip + flash) – GPU shader 60fps, frozen A + live B",
                color = Color.Gray.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun CenterStageControlCard(
    state: CenterStageState,
    onToggle: () -> Unit,
    onTrackingModeChange: (CenterStageState.TrackingMode) -> Unit,
    onMaxZoomChange: (Float) -> Unit,
    onPaddingChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isEnabled) Color(0xFF2A2410) else Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Face,
                        contentDescription = null,
                        tint = if (state.isEnabled) Color(0xFFFFC107) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            "Center Stage",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            when {
                                !state.isEnabled -> "Auto-framing disabled"
                                state.isTracking -> "Tracking ${state.faceCount} face(s) • Zoom ${String.format("%.1fx", state.zoom)}"
                                else -> "Enabled – No face detected, returning to center"
                            },
                            color = if (state.isTracking) Color(0xFFFFC107) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Switch(
                    checked = state.isEnabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFFFC107),
                        checkedTrackColor = Color(0xFFFFC107).copy(alpha = 0.5f)
                    )
                )
            }

            if (state.isEnabled) {
                Divider(color = Color.White.copy(alpha = 0.1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Center", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            String.format("(%.2f, %.2f)", state.centerX, state.centerY),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column {
                        Text("Target", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            String.format("(%.2f, %.2f)", state.targetCenterX, state.targetCenterY),
                            color = Color(0xFFFFC107),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column {
                        Text("Zoom", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            String.format("%.2fx / %.1fx", state.zoom, state.maxZoom),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column {
                        Text("Faces", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${state.faceCount}",
                            color = if (state.faceCount > 0) Color(0xFF4CAF50) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mode:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    FilterChip(
                        selected = state.trackingMode == CenterStageState.TrackingMode.GROUP,
                        onClick = { onTrackingModeChange(CenterStageState.TrackingMode.GROUP) },
                        label = { Text("GROUP") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFC107),
                            selectedLabelColor = Color.Black
                        )
                    )
                    FilterChip(
                        selected = state.trackingMode == CenterStageState.TrackingMode.SINGLE,
                        onClick = { onTrackingModeChange(CenterStageState.TrackingMode.SINGLE) },
                        label = { Text("SINGLE") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFC107),
                            selectedLabelColor = Color.Black
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Zoom", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(
                            String.format("%.1fx", state.maxZoom),
                            color = Color(0xFFFFC107),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Slider(
                        value = state.maxZoom,
                        onValueChange = { onMaxZoomChange(it) },
                        valueRange = 1f..5f,
                        steps = 7,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFC107),
                            activeTrackColor = Color(0xFFFFC107)
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Padding", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(
                            String.format("%.1fx", state.paddingFactor),
                            color = Color(0xFFFFC107),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Slider(
                        value = state.paddingFactor,
                        onValueChange = { onPaddingChange(it) },
                        valueRange = 1.2f..3.5f,
                        steps = 5,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFC107),
                            activeTrackColor = Color(0xFFFFC107)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenCaptureControlCard(
    isCapturing: Boolean,
    captureState: ScreenCaptureManager.CaptureState,
    errorMessage: String?,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onSwitchPreset: (String) -> Unit,
    onClearError: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCapturing) Color(0xFF1A2A1A) else Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.ScreenShare,
                        contentDescription = null,
                        tint = if (isCapturing) Color(0xFF3DDC84) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text("Screen Capture", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (captureState) {
                                is ScreenCaptureManager.CaptureState.Capturing -> "Capturing ${captureState.width}x${captureState.height} dpi=${captureState.dpi}"
                                is ScreenCaptureManager.CaptureState.RequestingPermission -> "Requesting permission..."
                                is ScreenCaptureManager.CaptureState.Error -> "Error: ${captureState.message}"
                                is ScreenCaptureManager.CaptureState.Stopped -> "Stopped"
                                else -> "MediaProjection – show screen while on camera"
                            },
                            color = if (isCapturing) Color(0xFF3DDC84) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Switch(
                    checked = isCapturing,
                    onCheckedChange = { if (it) onStartCapture() else onStopCapture() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF3DDC84),
                        checkedTrackColor = Color(0xFF3DDC84).copy(alpha = 0.5f)
                    )
                )
            }

            if (errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3D1A1A)), shape = RoundedCornerShape(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(errorMessage, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClearError) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (isCapturing) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(16.dp))
                    Text("Screen + Camera composited via GPU – PiP blending", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF3DDC84),
                    trackColor = Color(0xFF2A2A2A)
                )
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            Text("Tutorial & Gaming Presets", color = Color.White, style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    listOf(
                        "screen_share" to "Screen Share",
                        "tutorial" to "Tutorial",
                        "screen_pip" to "Screen PiP",
                        "gaming_screen" to "Gaming Screen"
                    )
                ) { (id, name) ->
                    FilterChip(
                        selected = false,
                        onClick = { onSwitchPreset(id) },
                        label = { Text(name) },
                        leadingIcon = {
                            Icon(
                                when (id) {
                                    "screen_share" -> Icons.Default.ScreenShare
                                    "tutorial" -> Icons.Default.School
                                    "screen_pip" -> Icons.Default.PictureInPicture
                                    else -> Icons.Default.SportsEsports
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF3DDC84),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Text(
                "Captures phone screen via MediaProjection VirtualDisplay -> OES texture -> Blend node. Show your screen while on camera simultaneously. Huge for tutorials and gaming content. Requires permission dialog.",
                color = Color.Gray.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartCapture,
                    enabled = !isCapturing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DDC84), contentColor = Color.Black),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start Capture")
                }
                Button(
                    onClick = onStopCapture,
                    enabled = isCapturing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679), contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.StopScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun WebControlCard(
    isRunning: Boolean,
    serverUrl: String,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) Color(0xFF1A2A1A) else Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        tint = if (isRunning) Color(0xFF3DDC84) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text("Web Remote Control", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (isRunning) "Running at $serverUrl" else "Control from PC browser on WiFi",
                            color = if (isRunning) Color(0xFF3DDC84) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Switch(
                    checked = isRunning,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF3DDC84),
                        checkedTrackColor = Color(0xFF3DDC84).copy(alpha = 0.5f)
                    )
                )
            }

            if (isRunning) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A1A)), shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Server URL – open in PC browser", color = Color(0xFF3DDC84), style = MaterialTheme.typography.labelMedium)
                        Text(serverUrl, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Text("Video URL for OBS: $serverUrl/video", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("API: $serverUrl/api/status", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Computer, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(16.dp))
                    Text("Phone on tripod – control scenes, effects, stream from PC", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            Text("Remote Features", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = {}, label = { Text("Scenes") }, leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp)) })
                FilterChip(selected = false, onClick = {}, label = { Text("Effects") }, leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp)) })
                FilterChip(selected = false, onClick = {}, label = { Text("Stream") }, leadingIcon = { Icon(Icons.Default.LiveTv, contentDescription = null, modifier = Modifier.size(16.dp)) })
                FilterChip(selected = false, onClick = {}, label = { Text("Record") }, leadingIcon = { Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp)) })
            }

            Text(
                "NanoHTTPD web server – extend existing MJPEG server. Full HTML remote UI at /. REST API at /api/*. Control from any browser on same WiFi network. No internet needed.",
                color = Color.Gray.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DDC84), contentColor = Color.Black),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start Server")
                }
                Button(
                    onClick = onStop,
                    enabled = isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679), contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun InstantReplayCard(
    isReplaying: Boolean,
    bufferDurationSec: Float,
    bufferSizeMB: Float,
    isSaving: Boolean,
    lastSavedFile: java.io.File?,
    errorMessage: String?,
    isAutoStartEnabled: Boolean,
    config: com.androidvirtualcam.replay.InstantReplayManager.ReplayConfig,
    onToggleReplay: () -> Unit,
    onSaveClip: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onClearError: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReplaying) Color(0xFF2A1A1A) else Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Replay,
                        contentDescription = null,
                        tint = if (isReplaying) Color(0xFFFF5252) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text("Instant Replay – ShadowPlay", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                !isReplaying -> "Stopped – enable to buffer last ${config.maxDurationSec}s"
                                bufferDurationSec < 1f -> "Buffering... ${String.format("%.1f", bufferDurationSec)}s"
                                else -> "Buffering ${String.format("%.1f", bufferDurationSec)}s / ${config.maxDurationSec}s • ${String.format("%.1f", bufferSizeMB)} MB"
                            },
                            color = if (isReplaying) Color(0xFFFF5252) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isReplaying) {
                        Badge(containerColor = Color(0xFFFF5252), contentColor = Color.White) {
                            Text("● REC")
                        }
                    }
                    Switch(
                        checked = isReplaying,
                        onCheckedChange = { onToggleReplay() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFFF5252),
                            checkedTrackColor = Color(0xFFFF5252).copy(alpha = 0.5f)
                        )
                    )
                }
            }

            if (isReplaying) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Buffer", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text("${String.format("%.1f", bufferDurationSec)}s / ${config.maxDurationSec}s", color = Color(0xFFFF5252), style = MaterialTheme.typography.labelMedium)
                    }
                    LinearProgressIndicator(
                        progress = { (bufferDurationSec / config.maxDurationSec.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFFF5252),
                        trackColor = Color(0xFF2A2A2A)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${String.format("%.1f", bufferSizeMB)} MB • ${config.bitRate/1000} kbps • ${config.width}x${config.height} @${config.frameRate}fps", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("RAM", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3D1A1A)), shape = RoundedCornerShape(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(errorMessage, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClearError) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Big SAVE CLIP button – ShadowPlay core
            Button(
                onClick = onSaveClip,
                enabled = isReplaying && bufferDurationSec > 1f && !isSaving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF5252),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF3A2A2A),
                    disabledContentColor = Color.Gray
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("SAVING CLIP...", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("SAVE CLIP – Last ${String.format("%.0f", bufferDurationSec)}s", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (lastSavedFile != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A1A)), shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Last clip saved", color = Color(0xFF3DDC84), style = MaterialTheme.typography.labelMedium)
                            Text(lastSavedFile.name, color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.1f", lastSavedFile.length() / (1024f*1024f))} MB • Movies/VirtualCam/Replays", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-start on launch", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = isAutoStartEnabled,
                    onCheckedChange = onAutoStartChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3DDC84))
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Duration", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text("${config.maxDurationSec}s", color = Color(0xFFFF5252), style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = config.maxDurationSec.toFloat(),
                    onValueChange = { onDurationChange(it.toInt()) },
                    valueRange = 10f..120f,
                    steps = 10,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFF5252), activeTrackColor = Color(0xFFFF5252))
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bitrate", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text("${config.bitRate/1000} kbps", color = Color(0xFFFF5252), style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = config.bitRate.toFloat(),
                    onValueChange = { onBitrateChange(it.toInt()) },
                    valueRange = 1_000_000f..8_000_000f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFF5252), activeTrackColor = Color(0xFFFF5252))
                )
                Text("${config.maxDurationSec}s @ ${config.bitRate/1000}kbps ≈ ${String.format("%.1f", config.maxDurationSec * config.bitRate / 8f / 1024f / 1024f)} MB RAM – hardware AVC encoder low CPU", color = Color.Gray.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }

            Text(
                "ShadowPlay-like: always recording composed output (compositor + effects) to RAM circular buffer. Tap SAVE CLIP instantly dumps buffer to MP4 without having started manual recording. Perfect for gaming streams – capture epic moments retroactively. File saved to Movies/VirtualCam/Replays.",
                color = Color.Gray.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun BeautyControlCard(
    isBeautyEnabled: Boolean,
    skinSmoothing: Float,
    faceSlimming: Float,
    eyeBrightening: Float,
    eyeEnlarge: Float,
    faceCount: Int,
    currentFilter: String,
    filterIntensity: Float,
    isFilterEnabled: Boolean,
    availableFilters: List<String>,
    onBeautyToggle: (Boolean) -> Unit,
    onSkinSmoothingChange: (Float) -> Unit,
    onFaceSlimmingChange: (Float) -> Unit,
    onEyeBrighteningChange: (Float) -> Unit,
    onEyeEnlargeChange: (Float) -> Unit,
    onFilterSelected: (String) -> Unit,
    onFilterIntensityChange: (Float) -> Unit,
    onFilterToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isBeautyEnabled) Color(0xFF2A1A2A) else Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.FaceRetouchingNatural,
                        contentDescription = null,
                        tint = if (isBeautyEnabled) Color(0xFFFF80AB) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text("Beauty Filters – ML Kit Landmarks", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (faceCount > 0) "$faceCount face(s) detected – skin smoothing, slimming, eye brightening" else "No face – beauty uses face landmarks when detected",
                            color = if (faceCount > 0) Color(0xFFFF80AB) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Switch(
                    checked = isBeautyEnabled,
                    onCheckedChange = onBeautyToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF80AB))
                )
            }

            if (isBeautyEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Skin Smoothing", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(String.format("%.0f%%", skinSmoothing*100), color = Color(0xFFFF80AB), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = skinSmoothing,
                        onValueChange = onSkinSmoothingChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF80AB), activeTrackColor = Color(0xFFFF80AB))
                    )
                    Text("Bilateral-like 9-tap blur + YCrCb skin mask + edge preservation – only on skin inside face bounding box from ML Kit", color = Color.Gray.copy(alpha=0.7f), style = MaterialTheme.typography.labelSmall)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Face Slimming (V-shape)", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(String.format("%.0f%%", faceSlimming*100), color = Color(0xFFFF80AB), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = faceSlimming,
                        onValueChange = onFaceSlimmingChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF80AB), activeTrackColor = Color(0xFFFF80AB))
                    )
                    Text("Warps face oval inward – pushes x towards center, stronger at chin for V-shape, uses face center/radius from landmarks", color = Color.Gray.copy(alpha=0.7f), style = MaterialTheme.typography.labelSmall)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Eye Brightening", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(String.format("%.0f%%", eyeBrightening*100), color = Color(0xFFFF80AB), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = eyeBrightening,
                        onValueChange = onEyeBrighteningChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF80AB), activeTrackColor = Color(0xFFFF80AB))
                    )
                    Text("Brightens eye whites, enhances iris saturation, respects eye open probability from ML Kit classification", color = Color.Gray.copy(alpha=0.7f), style = MaterialTheme.typography.labelSmall)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Eye Enlarge", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(String.format("%.0f%%", eyeEnlarge*100), color = Color(0xFFFF80AB), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = eyeEnlarge,
                        onValueChange = onEyeEnlargeChange,
                        valueRange = 0f..0.5f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF80AB), activeTrackColor = Color(0xFFFF80AB))
                    )
                }
            }

            Divider(color = Color.White.copy(alpha=0.1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Face Filters – Snapchat Style", color = Color.White, style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = isFilterEnabled,
                    onCheckedChange = onFilterToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF9C27B0))
                )
            }

            if (isFilterEnabled) {
                Text("Sunglasses, hats, masks rendered as compositor nodes on top of face using ML Kit landmarks", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(availableFilters) { filter ->
                        val isSelected = filter == currentFilter
                        FilterChip(
                            selected = isSelected,
                            onClick = { onFilterSelected(filter) },
                            label = { Text(filter.uppercase()) },
                            leadingIcon = {
                                Icon(
                                    when(filter) {
                                        "sunglasses" -> Icons.Default.Face
                                        "hat" -> Icons.Default.Face
                                        "crown" -> Icons.Default.Star
                                        "mask" -> Icons.Default.Masks
                                        "dog" -> Icons.Default.Pets
                                        "cat" -> Icons.Default.Pets
                                        else -> Icons.Default.AutoAwesome
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF9C27B0),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Filter Intensity", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(String.format("%.0f%%", filterIntensity*100), color = Color(0xFF9C27B0), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = filterIntensity,
                        onValueChange = onFilterIntensityChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF9C27B0), activeTrackColor = Color(0xFF9C27B0))
                    )
                }

                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)), shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Current: ${currentFilter.uppercase()}", color = Color(0xFF9C27B0), style = MaterialTheme.typography.labelMedium)
                        Text("Uses leftEye/rightEye/nose/mouth landmarks + eyeDist + yaw/roll for positioning. Sunglasses at eyes, hat above head (center.y - h*0.6), mask covering nose-mouth, mustache at mouth. Procedural GLSL + generated bitmap textures via Canvas.", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text(
                "Beauty pipeline: Camera -> FaceDetectionEngine (ML Kit FAST + landmarks + contours + tracking 15fps) -> FaceLandmarkManager singleton -> Beauty nodes (SkinSmoothing, FaceSlimming, EyeBrightening) read face uniforms -> FaceFilterNode overlays. All GLSL compositor nodes, GPU accelerated.",
                color = Color.Gray.copy(alpha=0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun AudioControlCard(
    devices: List<com.androidvirtualcam.audio.AudioSourceManager.AudioDevice>,
    selectedSourceType: com.androidvirtualcam.audio.AudioSourceManager.AudioSourceType,
    selectedDevice: com.androidvirtualcam.audio.AudioSourceManager.AudioDevice?,
    isBluetoothScoOn: Boolean,
    deviceDescription: String,
    isDuckingEnabled: Boolean,
    isMusicPlaying: Boolean,
    musicVolume: Float,
    isDucked: Boolean,
    vadLevel: Float,
    isSpeaking: Boolean,
    availableSourceTypes: List<com.androidvirtualcam.audio.AudioSourceManager.AudioSourceType>,
    onRefreshDevices: () -> Unit,
    onSourceTypeSelected: (com.androidvirtualcam.audio.AudioSourceManager.AudioSourceType) -> Unit,
    onDeviceSelected: (com.androidvirtualcam.audio.AudioSourceManager.AudioDevice) -> Unit,
    onToggleBluetoothSco: () -> Unit,
    onDuckingToggle: (Boolean) -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onStopMusic: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(24.dp))
                    Column {
                        Text("Audio Source – USB / Bluetooth / Lavalier", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(deviceDescription, color = Color(0xFF3DDC84), style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = onRefreshDevices) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF3DDC84))
                }
            }

            Text("Source Type", color = Color.White, style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(availableSourceTypes) { type ->
                    val isSelected = type == selectedSourceType
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSourceTypeSelected(type) },
                        label = { Text(type.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF3DDC84),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Text("Input Devices – AudioManager.getDevices(GET_DEVICES_INPUTS)", color = Color.White, style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    val isSelected = device.id == selectedDevice?.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onDeviceSelected(device) },
                        label = { Text("${device.type.displayName}: ${device.name.take(20)}") },
                        leadingIcon = {
                            Icon(
                                when(device.type) {
                                    com.androidvirtualcam.audio.AudioSourceManager.InputDeviceType.USB -> Icons.Default.Usb
                                    com.androidvirtualcam.audio.AudioSourceManager.InputDeviceType.BLUETOOTH -> Icons.Default.Bluetooth
                                    com.androidvirtualcam.audio.AudioSourceManager.InputDeviceType.WIRED_HEADSET -> Icons.Default.Headset
                                    else -> Icons.Default.Mic
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF03DAC6),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Bluetooth SCO", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = isBluetoothScoOn,
                    onCheckedChange = { onToggleBluetoothSco() },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3DDC84))
                )
            }

            Text(
                "USB-C audio interface: select UNPROCESSED source + USB device. Bluetooth mic: VOICE_COMMUNICATION + enable SCO via AudioManager.startBluetoothSco() + MODE_IN_COMMUNICATION. Lavalier via headphone jack: CAMCORDER source + wired headset device. Automatically routed by Android, but we prefer device and set AudioRecord source.",
                color = Color.Gray.copy(alpha=0.7f),
                style = MaterialTheme.typography.labelSmall
            )

            Divider(color = Color.White.copy(alpha=0.1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = if (isDucked) Color(0xFFFFC107) else Color(0xFF3DDC84), modifier = Modifier.size(24.dp))
                    Column {
                        Text("Background Music Ducking", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                !isMusicPlaying -> "No music playing"
                                isDucked -> "Ducked to 15% – speaking detected VAD ${String.format("%.2f", vadLevel)}"
                                isSpeaking -> "Speaking VAD ${String.format("%.2f", vadLevel)} – will duck"
                                else -> "Playing at ${String.format("%.0f%%", musicVolume*100)} – VAD ${String.format("%.2f", vadLevel)} silent"
                            },
                            color = if (isDucked) Color(0xFFFFC107) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Switch(
                    checked = isDuckingEnabled,
                    onCheckedChange = onDuckingToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3DDC84))
                )
            }

            if (isMusicPlaying) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Music Volume", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(String.format("%.0f%%", musicVolume*100), color = if (isDucked) Color(0xFFFFC107) else Color(0xFF3DDC84), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = musicVolume,
                        onValueChange = onMusicVolumeChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF3DDC84), activeTrackColor = Color(0xFF3DDC84))
                    )
                    LinearProgressIndicator(
                        progress = { vadLevel },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isSpeaking) Color(0xFF3DDC84) else Color.Gray,
                        trackColor = Color(0xFF2A2A2A)
                    )
                    Text("VAD Level: ${String.format("%.2f", vadLevel)} – threshold 0.6 – speaking=${isSpeaking}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }

                Button(
                    onClick = onStopMusic,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop Music")
                }
            }

            Text(
                "Auto-ducking: MusicDuckingManager plays via MediaPlayer looping, monitors VAD from VoiceChangerEngine (RNNoise processFrameWithVad returns 0..1). When VAD>0.6 speaking, duck to 0.15 over 300ms. When silent 500ms, rise to 1.0 over 800ms. Uses MusicDuckingManagerHolder singleton so voice thread can call updateVad().",
                color = Color.Gray.copy(alpha=0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
