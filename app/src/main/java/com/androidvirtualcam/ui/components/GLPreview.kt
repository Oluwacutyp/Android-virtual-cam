package com.androidvirtualcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.androidvirtualcam.camera.CameraManager
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.replay.InstantReplayManager
import com.androidvirtualcam.ui.viewmodels.TeleprompterViewModel

/**
 * GLPreviewCanvas REMOVED from preview path – ZERO GL touching preview surface.
 * Now pure placeholder – preview uses only CameraX PreviewView.
 * Recording/streaming uses ImageAnalysis ByteArray -> CPU -> MediaCodec, no EGL, no GLSurfaceView, no shaders.
 */
@Composable
fun GLPreviewCanvas(
    compositorRenderer: CompositorRenderer?,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
    isStreaming: Boolean = false,
    isVirtualCamActive: Boolean = false,
    sceneName: String = "None",
    cameraManager: CameraManager? = null,
    teleprompterState: TeleprompterViewModel.TeleprompterState? = null,
    instantReplayManager: InstantReplayManager? = null,
    onSurfaceCreated: (() -> Unit)? = null
) {
    // Pure placeholder – NO GL, NO EGL, NO GLSurfaceView, NO shaders in preview path
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GL Preview Removed", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(
                "Preview uses only CameraX PreviewView – ZERO GL, ZERO EGL, ZERO shaders touching preview surface",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "Recording/streaming: ImageAnalysis ByteArray -> CPU -> MediaCodec encoder",
                color = Color(0xFF3DDC84),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
