package com.androidvirtualcam.camera

import com.androidvirtualcam.scene.CameraFacing

data class CameraState(
    val facing: CameraFacing = CameraFacing.BACK,
    val isTorchOn: Boolean = false,
    val isPreviewActive: Boolean = false,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val hasFlash: Boolean = false,
    val error: String? = null
)

sealed class CameraEvent {
    data class SwitchCamera(val facing: com.androidvirtualcam.scene.CameraFacing) : CameraEvent()
    object ToggleTorch : CameraEvent()
    data class SetZoom(val ratio: Float) : CameraEvent()
    object StartPreview : CameraEvent()
    object StopPreview : CameraEvent()
}
