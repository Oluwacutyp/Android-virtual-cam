package com.androidvirtualcam.processing

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * CPU Frame Processor – processes ImageAnalysis ByteArray frames on CPU, no GL, no EGL, no shaders.
 * 
 * Pipeline:
 * - CameraX ImageAnalysis (YUV_420_888) -> yuv420ToNv21 ByteArray
 * - CPU effects (optional blur, overlay, beauty) on ByteArray
 * - NV21 -> YUV420 planar for MediaCodec (COLOR_FormatYUV420Flexible)
 * - queueInputBuffer to MediaCodec encoder
 * 
 * Preview path is PURE CameraX PreviewView – ZERO GL touching preview surface.
 * This processor is ONLY for recording/streaming output, not preview.
 */
class CpuFrameProcessor(
    private val width: Int,
    private val height: Int
) {
    private val tag = "CpuFrameProcessor"

    // Optional CPU effect: simple brightness/contrast, or overlay
    var enableCpuEffect: Boolean = false
    var brightness: Float = 0f // -1..1
    var contrast: Float = 1f // 0..2

    /**
     * Process NV21 ByteArray frame on CPU – apply optional effects, then convert to YUV420 for encoder.
     * No GL, pure CPU.
     */
    fun processFrame(nv21: ByteArray, inputWidth: Int, inputHeight: Int): ByteArray {
        return try {
            // If input size differs from output, we would resize on CPU – for now assume same
            val processedNv21 = if (enableCpuEffect) {
                applyCpuEffects(nv21, inputWidth, inputHeight)
            } else {
                nv21
            }
            // Convert NV21 to YUV420 planar for MediaCodec COLOR_FormatYUV420Flexible
            nv21ToYuv420Planar(processedNv21, inputWidth, inputHeight)
        } catch (e: Exception) {
            Log.w(tag, "processFrame failed, returning original", e)
            nv21
        }
    }

    private fun applyCpuEffects(nv21: ByteArray, width: Int, height: Int): ByteArray {
        // Simple CPU brightness/contrast on Y plane only – no GL
        // Y plane is first width*height bytes in NV21
        return try {
            val ySize = width * height
            if (nv21.size < ySize) return nv21

            val result = nv21.clone()
            // Apply brightness/contrast to Y only for performance
            val b = (brightness * 30).toInt() // scale to -30..30
            for (i in 0 until ySize) {
                var y = (result[i].toInt() and 0xFF)
                y = ((y - 128) * contrast + 128 + b).toInt()
                y = y.coerceIn(0, 255)
                result[i] = y.toByte()
            }
            result
        } catch (e: Exception) {
            Log.w(tag, "applyCpuEffects failed", e)
            nv21
        }
    }

    /**
     * NV21 (Y + VU interleaved) -> YUV420 planar (Y + U + V)
     * For MediaCodec COLOR_FormatYUV420Flexible
     */
    fun nv21ToYuv420Planar(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        if (nv21.size < ySize) return nv21

        val yuv420 = ByteArray(ySize + uvSize * 2)
        // Y
        System.arraycopy(nv21, 0, yuv420, 0, ySize.coerceAtMost(nv21.size))

        // UV: NV21 has VU VU..., YUV420 has U + V planar
        if (nv21.size >= ySize + 2) {
            var uPos = ySize
            var vPos = ySize + uvSize
            var i = ySize
            while (i < nv21.size - 1 && uPos < ySize + uvSize && vPos < yuv420.size) {
                val v = nv21[i]
                val u = nv21[i + 1]
                yuv420[uPos++] = u
                yuv420[vPos++] = v
                i += 2
            }
        }
        return yuv420
    }

    /**
     * YUV_420_888 ImageProxy -> NV21 ByteArray
     * This is called from CameraManager's ImageAnalysis analyzer
     */
    fun yuv420ToNv21(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val nv21 = ByteArray(width * height + width * height / 2)
        try {
            // Y
            var pos = 0
            if (yRowStride == width) {
                yBuffer.get(nv21, 0, width * height)
                pos = width * height
            } else {
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(nv21, pos, width)
                    pos += width
                }
            }

            // UV – NV21 VU interleaved
            val uvHeight = height / 2
            val uvWidth = width / 2
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uPos = row * uvRowStride + col * uvPixelStride
                    val vPos = row * uvRowStride + col * uvPixelStride // Simplified, assumes same stride for U and V
                    if (vPos < vBuffer.capacity() && uPos < uBuffer.capacity()) {
                        nv21[pos++] = vBuffer.get(vPos)
                        nv21[pos++] = uBuffer.get(uPos)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "yuv420ToNv21 failed", e)
        }
        return nv21
    }

    /**
     * Optional: Convert NV21 to Bitmap for preview overlay or saving – CPU only
     */
    fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val jpeg = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            Log.w(tag, "nv21ToBitmap failed", e)
            null
        }
    }
}
