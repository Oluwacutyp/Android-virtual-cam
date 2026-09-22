package com.androidvirtualcam.centerstage

import android.graphics.RectF

/**
 * Center Stage state – Apple Center Stage style auto-framing.
 * Keeps face centered via smooth pan/zoom.
 */
data class CenterStageState(
    val isEnabled: Boolean = false,
    val isTracking: Boolean = false,
    val centerX: Float = 0.5f,          // current smoothed center (0..1)
    val centerY: Float = 0.5f,
    val zoom: Float = 1f,               // current smoothed zoom (1..maxZoom)
    val targetCenterX: Float = 0.5f,    // raw target from detector
    val targetCenterY: Float = 0.5f,
    val targetZoom: Float = 1f,
    val faceCount: Int = 0,
    val boundingBox: RectF? = null,     // current face group box normalized
    val confidence: Float = 0f,
    val trackingMode: TrackingMode = TrackingMode.GROUP,
    val maxZoom: Float = 3f,
    val minZoom: Float = 1f,
    val paddingFactor: Float = 1.8f,    // how much padding around face
    val smoothingEnabled: Boolean = true
) {
    enum class TrackingMode {
        SINGLE,   // track largest face
        GROUP     // track all faces bounding box
    }

    fun withEnabled(enabled: Boolean) = copy(isEnabled = enabled)
    fun withTrackingMode(mode: TrackingMode) = copy(trackingMode = mode)
    fun withMaxZoom(zoom: Float) = copy(maxZoom = zoom.coerceIn(1f, 5f))
    fun withPadding(padding: Float) = copy(paddingFactor = padding.coerceIn(1.2f, 3.5f))
}

data class FaceDetectionResult(
    val faces: List<DetectedFace>,
    val imageWidth: Int,
    val imageHeight: Int,
    val isMirrored: Boolean,
    val timestampMs: Long
)

data class DetectedFace(
    val boundingBox: RectF, // normalized 0..1
    val rawBox: android.graphics.Rect, // pixel coords
    val trackingId: Int?,
    val headEulerY: Float,
    val headEulerZ: Float,
    val confidence: Float
)
