package com.androidvirtualcam.centerstage

import android.util.Log
import kotlin.math.abs
import kotlin.math.exp

/**
 * Smooths pan/zoom to avoid jitter – exponential decay with deadzone.
 * Apple Center Stage uses smooth spring animation; we approximate with lerp + velocity.
 */
class CenterStageSmoother(
    var panSpeed: Float = 2.2f,      // higher = faster pan
    var zoomSpeed: Float = 1.2f,     // higher = faster zoom
    var deadzone: Float = 0.015f,    // ignore movements smaller than this (normalized)
    var zoomDeadzone: Float = 0.03f
) {
    private var currentX = 0.5f
    private var currentY = 0.5f
    private var currentZoom = 1f

    private var targetX = 0.5f
    private var targetY = 0.5f
    private var targetZoom = 1f

    private var lastUpdateNs = System.nanoTime()
    private var velocityX = 0f
    private var velocityY = 0f
    private var velocityZoom = 0f

    // For face lost handling
    private var lastFaceDetectedNs = System.nanoTime()
    private var isFaceLost = false

    data class SmoothedTransform(
        val centerX: Float,
        val centerY: Float,
        val zoom: Float,
        val velocityX: Float,
        val velocityY: Float
    )

    fun setTarget(x: Float, y: Float, zoom: Float) {
        // Clamp targets
        val clampedZoom = zoom.coerceIn(1f, 5f)
        // Clamp center so crop stays within bounds
        val halfW = 0.5f / clampedZoom
        val halfH = 0.5f / clampedZoom
        val clampedX = x.coerceIn(halfW, 1f - halfW)
        val clampedY = y.coerceIn(halfH, 1f - halfH)

        // Deadzone check – avoid micro-jitter
        if (abs(clampedX - targetX) < deadzone && abs(clampedY - targetY) < deadzone && abs(clampedZoom - targetZoom) < zoomDeadzone) {
            return
        }

        targetX = clampedX
        targetY = clampedY
        targetZoom = clampedZoom
        lastFaceDetectedNs = System.nanoTime()
        isFaceLost = false
    }

    fun setFaceLost() {
        if (!isFaceLost) {
            // When face lost, slowly return to center 0.5,0.5 zoom 1
            targetX = 0.5f
            targetY = 0.5f
            targetZoom = 1f
            isFaceLost = true
        }
    }

    fun update(): SmoothedTransform {
        val nowNs = System.nanoTime()
        val dt = ((nowNs - lastUpdateNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastUpdateNs = nowNs

        // If no face for >2s, ease back to center
        val timeSinceFaceMs = (nowNs - lastFaceDetectedNs) / 1_000_000
        if (timeSinceFaceMs > 2000 && !isFaceLost) {
            setFaceLost()
        }

        // Exponential smoothing: factor = 1 - exp(-speed * dt)
        val panFactor = 1f - exp(-panSpeed * dt)
        val zoomFactor = 1f - exp(-zoomSpeed * dt)

        // Smooth X/Y
        val prevX = currentX
        val prevY = currentY
        val prevZoom = currentZoom

        currentX += (targetX - currentX) * panFactor
        currentY += (targetY - currentY) * panFactor
        currentZoom += (targetZoom - currentZoom) * zoomFactor

        // Velocity for UI / prediction
        velocityX = (currentX - prevX) / dt.coerceAtLeast(0.001f)
        velocityY = (currentY - prevY) / dt.coerceAtLeast(0.001f)
        velocityZoom = (currentZoom - prevZoom) / dt.coerceAtLeast(0.001f)

        // Re-clamp after smoothing
        val halfW = 0.5f / currentZoom
        val halfH = 0.5f / currentZoom
        currentX = currentX.coerceIn(halfW, 1f - halfW)
        currentY = currentY.coerceIn(halfH, 1f - halfH)

        return SmoothedTransform(currentX, currentY, currentZoom, velocityX, velocityY)
    }

    fun getCurrent(): SmoothedTransform = SmoothedTransform(currentX, currentY, currentZoom, velocityX, velocityY)

    fun reset() {
        currentX = 0.5f
        currentY = 0.5f
        currentZoom = 1f
        targetX = 0.5f
        targetY = 0.5f
        targetZoom = 1f
        velocityX = 0f
        velocityY = 0f
        velocityZoom = 0f
        lastUpdateNs = System.nanoTime()
        lastFaceDetectedNs = System.nanoTime()
        isFaceLost = false
    }

    fun isAtRest(threshold: Float = 0.001f): Boolean {
        return abs(currentX - targetX) < threshold &&
                abs(currentY - targetY) < threshold &&
                abs(currentZoom - targetZoom) < threshold * 2
    }
}
