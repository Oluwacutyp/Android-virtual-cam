package com.androidvirtualcam.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.ui.components.RawCameraPreview
import com.androidvirtualcam.camera.CameraManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

enum class CaptureMode {
    IMAGE_CAPTURE,
    VIDEO_CAPTURE,
    STILL_IMAGE_CAMERA,
    NONE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureIntentScreen(
    mode: CaptureMode,
    compositorRenderer: CompositorRenderer?,
    isRecording: Boolean,
    recordingDurationMs: Long,
    onCaptureImage: () -> Unit,
    onStartVideo: () -> Unit,
    onStopVideo: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (mode) {
                            CaptureMode.IMAGE_CAPTURE -> "Capture Photo – All Effects"
                            CaptureMode.VIDEO_CAPTURE -> "Record Video – All Effects"
                            CaptureMode.STILL_IMAGE_CAMERA -> "VirtualCam Camera"
                            else -> "Camera"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
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
            // PURE CameraX PreviewView – ZERO GL, ZERO EGL, ZERO shaders in preview path
            // Recording uses ImageAnalysis ByteArray -> CPU -> MediaCodec
            val context = LocalContext.current
            val cameraManager = remember { CameraManager(context) }
            RawCameraPreview(
                cameraManager = cameraManager,
                modifier = Modifier.fillMaxWidth()
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (mode) {
                            CaptureMode.IMAGE_CAPTURE -> "Photo will be saved with all compositor effects: segmentation, blur, filters, overlays"
                            CaptureMode.VIDEO_CAPTURE -> "Video will be recorded with all effects applied via shared EGL context"
                            else -> "Professional broadcast camera with 10 presets, segmentation, voice changer"
                        },
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (mode == CaptureMode.VIDEO_CAPTURE && isRecording) {
                        Text(
                            "Recording: ${formatDuration(recordingDurationMs)}",
                            color = Color.Red,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Bottom capture controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF2D2D2D))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.size(28.dp))
                }

                when (mode) {
                    CaptureMode.IMAGE_CAPTURE -> {
                        Button(
                            onClick = onCaptureImage,
                            modifier = Modifier.size(80.dp).clip(CircleShape),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Capture", modifier = Modifier.size(40.dp))
                        }
                    }
                    CaptureMode.VIDEO_CAPTURE -> {
                        if (isRecording) {
                            Button(
                                onClick = onStopVideo,
                                modifier = Modifier.size(80.dp).clip(CircleShape),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(40.dp))
                            }
                        } else {
                            Button(
                                onClick = onStartVideo,
                                modifier = Modifier.size(80.dp).clip(CircleShape),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.FiberManualRecord, contentDescription = "Record", modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                    CaptureMode.STILL_IMAGE_CAMERA -> {
                        Button(
                            onClick = onCaptureImage,
                            modifier = Modifier.size(80.dp).clip(CircleShape),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Capture", modifier = Modifier.size(40.dp))
                        }
                    }
                    else -> {}
                }

                IconButton(
                    onClick = { /* switch camera */ },
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF2D2D2D))
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}
