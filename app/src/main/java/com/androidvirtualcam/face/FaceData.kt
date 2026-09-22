package com.androidvirtualcam.face

import android.graphics.PointF
import android.graphics.RectF

/**
 * FaceData – result from ML Kit Face Detection / MediaPipe Face Landmarker
 * Used by beauty nodes (skin smoothing, slimming, eye brightening) and face filters (sunglasses, hats, masks)
 */
data class FaceData(
    val id: Int,
    val boundingBox: RectF, // in 0..1 normalized coordinates (relative to frame)
    val leftEye: PointF?,
    val rightEye: PointF?,
    val nose: PointF?,
    val mouthLeft: PointF?,
    val mouthRight: PointF?,
    val mouthBottom: PointF?,
    val leftEar: PointF?,
    val rightEar: PointF?,
    val leftCheek: PointF?,
    val rightCheek: PointF?,
    val faceContour: List<PointF> = emptyList(), // oval
    val leftEyeContour: List<PointF> = emptyList(),
    val rightEyeContour: List<PointF> = emptyList(),
    val upperLipContour: List<PointF> = emptyList(),
    val lowerLipContour: List<PointF> = emptyList(),
    val noseBridge: List<PointF> = emptyList(),
    val leftEyebrow: List<PointF> = emptyList(),
    val rightEyebrow: List<PointF> = emptyList(),
    val smileProb: Float = 0f,
    val leftEyeOpenProb: Float = 1f,
    val rightEyeOpenProb: Float = 1f,
    val headEulerX: Float = 0f, // pitch
    val headEulerY: Float = 0f, // yaw
    val headEulerZ: Float = 0f, // roll
    val confidence: Float = 1f,
    val width: Float = boundingBox.width(),
    val height: Float = boundingBox.height()
) {
    val center: PointF
        get() = PointF(boundingBox.centerX(), boundingBox.centerY())

    val eyeDistance: Float
        get() {
            if (leftEye == null || rightEye == null) return width * 0.3f
            val dx = rightEye.x - leftEye.x
            val dy = rightEye.y - leftEye.y
            return kotlin.math.sqrt(dx*dx + dy*dy)
        }

    val eyeCenter: PointF
        get() {
            if (leftEye != null && rightEye != null) {
                return PointF((leftEye.x + rightEye.x)/2f, (leftEye.y + rightEye.y)/2f)
            }
            return PointF(center.x, center.y - height*0.1f)
        }

    fun toNormalizedArray(): FloatArray {
        // Pack for shader: boundingBox x,y,w,h, leftEye x,y, rightEye x,y, nose x,y, mouth x,y
        return floatArrayOf(
            boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height(),
            leftEye?.x ?: 0f, leftEye?.y ?: 0f,
            rightEye?.x ?: 0f, rightEye?.y ?: 0f,
            nose?.x ?: center.x, nose?.y ?: center.y,
            mouthLeft?.x ?: center.x - width*0.1f, mouthLeft?.y ?: center.y + height*0.2f,
            mouthRight?.x ?: center.x + width*0.1f, mouthRight?.y ?: center.y + height*0.2f,
            eyeDistance, headEulerY, headEulerZ
        )
    }
}
