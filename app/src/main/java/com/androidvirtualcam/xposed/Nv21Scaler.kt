package com.androidvirtualcam.xposed

import android.util.Log
import java.nio.ByteBuffer

/**
 * Production-grade NV21 scaler – NEON-optimized native via libvcamyuv.so,
 * with Kotlin fallback.
 *
 * NV21 layout: Y plane W*H, then VU interleaved W*H/2
 */
object Nv21Scaler {

    private const val TAG = "Nv21Scaler"

    init {
        try {
            System.loadLibrary("vcamyuv")
            Log.d(TAG, "libvcamyuv.so loaded – NEON-optimized")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load libvcamyuv.so, using Kotlin fallback: ${e.message}")
        }
    }

    fun scale(src: ByteArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int, useBilinear: Boolean = false): ByteArray {
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src

        return try {
            // Try native
            val result = scaleNative(src, srcWidth, srcHeight, dstWidth, dstHeight, useBilinear)
            result ?: scaleKotlin(src, srcWidth, srcHeight, dstWidth, dstHeight)
        } catch (e: Exception) {
            Log.w(TAG, "Native scale failed, fallback to Kotlin: ${e.message}")
            scaleKotlin(src, srcWidth, srcHeight, dstWidth, dstHeight)
        }
    }

    fun scaleDirect(srcBuffer: ByteBuffer, srcWidth: Int, srcHeight: Int, dstBuffer: ByteBuffer, dstWidth: Int, dstHeight: Int, useBilinear: Boolean = false) {
        try {
            scaleDirectNative(srcBuffer, srcWidth, srcH = srcHeight, dstBuffer, dstW = dstWidth, dstH = dstHeight, useBilinear = useBilinear)
        } catch (e: Exception) {
            Log.w(TAG, "Native direct scale failed, fallback", e)
            // Fallback: copy via byte array
            val srcArray = ByteArray(srcBuffer.remaining())
            srcBuffer.duplicate().get(srcArray)
            val scaled = scaleKotlin(srcArray, srcWidth, srcHeight, dstWidth, dstHeight)
            dstBuffer.put(scaled)
        }
    }

    private fun scaleKotlin(src: ByteArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): ByteArray {
        val dstSize = dstWidth * dstHeight * 3 / 2
        val dst = ByteArray(dstSize)

        val srcYSize = srcWidth * srcHeight
        val dstYSize = dstWidth * dstHeight

        val xRatio = srcWidth.toFloat() / dstWidth
        val yRatio = srcHeight.toFloat() / dstHeight

        for (y in 0 until dstHeight) {
            val srcY = (y * yRatio).toInt().coerceIn(0, srcHeight - 1)
            val srcRowOffset = srcY * srcWidth
            val dstRowOffset = y * dstWidth
            for (x in 0 until dstWidth) {
                val srcX = (x * xRatio).toInt().coerceIn(0, srcWidth - 1)
                dst[dstRowOffset + x] = src[srcRowOffset + srcX]
            }
        }

        val srcUvHeight = srcHeight / 2
        val dstUvHeight = dstHeight / 2
        val srcUvWidth = srcWidth
        val dstUvWidth = dstWidth

        val uvXRatio = srcUvWidth.toFloat() / dstUvWidth
        val uvYRatio = srcUvHeight.toFloat() / dstUvHeight

        for (y in 0 until dstUvHeight) {
            val srcY = (y * uvYRatio).toInt().coerceIn(0, srcUvHeight - 1)
            val srcRowOffset = srcYSize + srcY * srcUvWidth
            val dstRowOffset = dstYSize + y * dstUvWidth
            for (x in 0 until dstUvWidth step 2) {
                val srcX = (x * uvXRatio).toInt().coerceIn(0, srcUvWidth - 2)
                val srcXEven = srcX - (srcX % 2)
                dst[dstRowOffset + x] = src[srcRowOffset + srcXEven]
                if (x + 1 < dstUvWidth) {
                    dst[dstRowOffset + x + 1] = src[srcRowOffset + srcXEven + 1]
                }
            }
        }

        return dst
    }

    // Native methods – implemented in cpp/vcamyuv/yuv_scaler.cpp
    private external fun scaleNative(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, useBilinear: Boolean): ByteArray?
    private external fun scaleDirectNative(srcBuffer: ByteBuffer, srcW: Int, srcH: Int, dstBuffer: ByteBuffer, dstW: Int, dstH: Int, useBilinear: Boolean)
}
