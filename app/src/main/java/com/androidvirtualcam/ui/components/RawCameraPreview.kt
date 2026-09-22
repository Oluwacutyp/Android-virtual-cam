package com.androidvirtualcam.ui.components

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.androidvirtualcam.camera.CameraManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure CameraX PreviewView – ZERO GL code touching preview surface.
 * No EGL, no GLSurfaceView, no custom shaders in preview path at all.
 * Samsung S22 Ultra fix: PreviewView with COMPATIBLE mode always works.
 * Recording/streaming uses ImageAnalysis ByteArray -> CPU -> MediaCodec.
 */
@Composable
fun RawCameraPreview(
    cameraManager: CameraManager?,
    modifier: Modifier = Modifier,
    uploadedImageUri: String? = null,
    uploadedVideoUri: String? = null,
    onClearImage: (() -> Unit)? = null,
    onClearVideo: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(Unit) {
        try {
            val missing = com.androidvirtualcam.platform.PermissionHelper.getMissingPermissions(context)
            hasPermission = !missing.contains(android.Manifest.permission.CAMERA)
            Log.i("RawCameraPreview", "Permission check: hasPermission=$hasPermission")
        } catch (e: Exception) {
            hasPermission = true
        }
    }

    LaunchedEffect(hasPermission) {
        while (true) {
            delay(1500)
            try {
                val missing = com.androidvirtualcam.platform.PermissionHelper.getMissingPermissions(context)
                val nowHas = !missing.contains(android.Manifest.permission.CAMERA)
                if (nowHas != hasPermission) {
                    hasPermission = nowHas
                }
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
    ) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        Log.i("RawCameraPreview", "PreviewView created – PURE CameraX, NO GL, NO EGL, NO SHADERS")
                    }
                    previewViewRef = previewView
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    scope.launch {
                        try {
                            delay(100)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build()
                                    preview.setSurfaceProvider(previewView.surfaceProvider)
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_FRONT_CAMERA,
                                        preview
                                    )
                                    Log.i("RawCameraPreview", "CameraX PreviewView bound – PURE preview, ZERO GL code")
                                    errorMessage = null
                                    try {
                                        com.androidvirtualcam.voice.VoiceChangerEngine.getInstance()?.setCameraRunning(true)
                                    } catch (_: Exception) {}
                                } catch (e: Throwable) {
                                    Log.e("RawCameraPreview", "Failed to bind preview", e)
                                    errorMessage = "Camera bind failed: ${e.message}"
                                    scope.launch {
                                        try {
                                            cameraManager?.let { cm ->
                                                if (!cm.isProviderInitialized()) {
                                                    cm.initProvider()
                                                }
                                                val preview = Preview.Builder().build()
                                                preview.setSurfaceProvider(previewView.surfaceProvider)
                                                cm.cameraProvider?.let { provider ->
                                                    provider.unbindAll()
                                                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                                                    errorMessage = null
                                                }
                                            }
                                        } catch (fe: Exception) {
                                            Log.e("RawCameraPreview", "Fallback failed", fe)
                                        }
                                    }
                                }
                            }, ContextCompat.getMainExecutor(context))
                        } catch (e: Throwable) {
                            Log.e("RawCameraPreview", "Failed to bind raw preview", e)
                            errorMessage = "Camera bind failed: ${e.message}"
                        }
                    }
                }
            )

            // Media upload overlay – Compose only, no GL
            if (uploadedImageUri != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MediaImageOverlay(
                        uri = uploadedImageUri,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .fillMaxWidth(0.5f)
                            .aspectRatio(1f)
                            .padding(8.dp)
                    )
                    if (onClearImage != null) {
                        IconButton(
                            onClick = onClearImage,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                                .size(28.dp)
                        ) {
                            Text("✕", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (uploadedVideoUri != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MediaVideoOverlay(
                        uri = uploadedVideoUri,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF9C27B0).copy(alpha = 0.9f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("VIDEO BACKGROUND LOOPING", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    if (onClearVideo != null) {
                        IconButton(
                            onClick = onClearVideo,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                                .size(28.dp)
                        ) {
                            Text("✕", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                    Text("Camera permission required", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text("Pure CameraX PreviewView – no GL, no crash", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    errorMessage?.let {
                        Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PURE PREVIEW – ZERO GL",
                color = Color(0xFF3DDC84),
                style = MaterialTheme.typography.labelMedium
            )
            Badge(
                containerColor = Color(0xFF3DDC84),
                contentColor = Color.Black
            ) {
                Text("CameraX")
            }
        }

        errorMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xFF3D1A1A).copy(alpha = 0.9f))
                    .padding(8.dp)
            ) {
                Text(msg, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// Simple Compose overlays for media upload – no GL
@Composable
fun MediaImageOverlay(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        try {
            val input = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
            bitmap = android.graphics.BitmapFactory.decodeStream(input)
            input?.close()
        } catch (e: Exception) {
            Log.w("MediaImageOverlay", "Failed to load $uri", e)
        }
    }
    bitmap?.let { bmp ->
        androidx.compose.foundation.Image(
            bitmap = androidx.compose.ui.graphics.asImageBitmap(bmp),
            contentDescription = "Uploaded image overlay",
            modifier = modifier.clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
fun MediaVideoOverlay(uri: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("Video: ${uri.takeLast(30)}", color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

// Placeholder to keep old API compatible but no GL
@Composable
fun EffectsPreviewToggleCard(
    isEffectsEnabled: Boolean,
    compositorAvailable: Boolean,
    errorMessage: String?,
    onToggle: (Boolean) -> Unit,
    onClearError: () -> Unit
) {
    // No GL in preview path – show info card that preview is pure CameraX
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2A1A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(androidx.compose.material.icons.Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF3DDC84))
                Text("Pure CameraX Preview – Zero GL", color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Preview uses only PreviewView, no EGL, no GLSurfaceView, no shaders. Recording/streaming uses ImageAnalysis ByteArray -> CPU -> MediaCodec.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
            if (errorMessage != null) {
                Text(errorMessage, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClearError) { Text("Clear") }
            }
        }
    }
}
