package com.androidvirtualcam.scene

import kotlinx.serialization.Serializable

/**
 * Represents position, scale, rotation of a layer.
 * Coordinates are normalized 0..1 relative to output surface (0,0 = top-left, 1,1 = bottom-right).
 * This allows resolution independence.
 */
@Serializable
data class Transform(
    val x: Float = 0.5f,          // center X normalized
    val y: Float = 0.5f,          // center Y normalized
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val alpha: Float = 1f
) {
    init {
        require(alpha in 0f..1f) { "alpha must be 0..1" }
        require(scaleX > 0 && scaleY > 0) { "scale must be positive" }
    }

    fun moved(dx: Float, dy: Float): Transform = copy(x = (x + dx).coerceIn(0f, 1f), y = (y + dy).coerceIn(0f, 1f))

    fun scaled(factor: Float): Transform {
        val newScaleX = (scaleX * factor).coerceIn(0.1f, 5f)
        val newScaleY = (scaleY * factor).coerceIn(0.1f, 5f)
        return copy(scaleX = newScaleX, scaleY = newScaleY)
    }

    fun rotated(delta: Float): Transform = copy(rotationDegrees = (rotationDegrees + delta) % 360f)

    fun withAlpha(newAlpha: Float): Transform = copy(alpha = newAlpha.coerceIn(0f, 1f))

    /**
     * Convert normalized center to pixel rect for hit testing.
     * Assumes layer base size normalized as 0.3 width by default, scaled.
     */
    fun toPixelRect(containerWidth: Int, containerHeight: Int, baseWidth: Float = 0.3f, baseHeight: Float = 0.3f): android.graphics.RectF {
        val w = containerWidth * baseWidth * scaleX
        val h = containerHeight * baseHeight * scaleY
        val cx = containerWidth * x
        val cy = containerHeight * y
        return android.graphics.RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
}
