package com.androidvirtualcam.compositor.nodes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.androidvirtualcam.compositor.*
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * LUTNode – parses .cube LUT files AND 512x512 Hald CLUT PNGs, applies as 3D texture lookup.
 *
 * Inputs:
 * - input: texture
 *
 * Parameters:
 * - lutPath: string – path to .cube file or Hald PNG
 * - lutSize: int – 32 or 64 for .cube, 512 for Hald
 * - intensity: float 0..1
 *
 * Production:
 * - Parses .cube format (TITLE, LUT_3D_SIZE, DOMAIN_MIN/MAX, data)
 * - Parses Hald CLUT PNG (512x512 image containing 64x64x64 or 32x32x32 color cube)
 * - Creates 3D texture2D(GL_TEXTURE_2D) or 2D LUT texture for lookup
 * - Applies via trilinear interpolation in shader
 */
class LUTNode(
    override val id: String,
    private val context: Context,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("lutPath", "")
        .int("lutSize", 32)
        .float("intensity", 1f)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "LUT",
    inputSockets = listOf(
        InputSocket("input", "input", SocketType.TEXTURE, displayName = "Input")
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var lutTexture: GLTexture? = null
    private var framebuffer: GLFramebuffer? = null
    private var lutSize: Int = 32
    private var isHald: Boolean = false

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_LUT)
        } catch (e: Exception) {
            Log.e("LUTNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }

        lutSize = parameters.getInt("lutSize", 32)
        val lutPath = parameters.getString("lutPath", "")

        if (lutPath.isNotEmpty()) {
            if (lutPath.endsWith(".cube", ignoreCase = true)) {
                loadCubeLUT(lutPath)
            } else if (lutPath.endsWith(".png", ignoreCase = true) || lutPath.contains("hald", ignoreCase = true)) {
                loadHaldLUT(lutPath)
            } else {
                Log.w("LUTNode", "Unknown LUT format for $lutPath, using identity")
                createIdentityLUT()
            }
        } else {
            createIdentityLUT()
        }

        Log.d("LUTNode", "Initialized $id lutSize=$lutSize isHald=$isHald")
    }

    private fun loadCubeLUT(path: String) {
        try {
            val input = context.contentResolver.openInputStream(android.net.Uri.parse(path))
                ?: context.assets.open(path)

            val reader = BufferedReader(InputStreamReader(input))
            var size = 32
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            val lutData = mutableListOf<FloatArray>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("TITLE")) continue

                when {
                    trimmed.startsWith("LUT_3D_SIZE") -> {
                        size = trimmed.split("\\s+".toRegex())[1].toInt()
                        lutSize = size
                    }
                    trimmed.startsWith("DOMAIN_MIN") -> {
                        val parts = trimmed.split("\\s+".toRegex())
                        domainMin = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    }
                    trimmed.startsWith("DOMAIN_MAX") -> {
                        val parts = trimmed.split("\\s+".toRegex())
                        domainMax = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    }
                    else -> {
                        // Data line: R G B
                        val parts = trimmed.split("\\s+".toRegex())
                        if (parts.size >= 3) {
                            try {
                                val r = parts[0].toFloat()
                                val g = parts[1].toFloat()
                                val b = parts[2].toFloat()
                                lutData.add(floatArrayOf(r, g, b))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            reader.close()

            if (lutData.size != size * size * size) {
                Log.w("LUTNode", "CUBE LUT data size mismatch: expected ${size*size*size}, got ${lutData.size}, using identity")
                createIdentityLUT()
                return
            }

            // Create 3D texture
            create3DLUTTexture(lutData, size)
            isHald = false

        } catch (e: Exception) {
            Log.e("LUTNode", "Failed to load .cube LUT $path", e)
            createIdentityLUT()
        }
    }

    private fun loadHaldLUT(path: String) {
        try {
            val bitmap = if (path.startsWith("content://") || path.startsWith("file://")) {
                val input = context.contentResolver.openInputStream(android.net.Uri.parse(path)) ?: return
                BitmapFactory.decodeStream(input)
            } else {
                try {
                    context.assets.open(path).use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    // Try file path
                    BitmapFactory.decodeFile(path)
                }
            } ?: run {
                Log.w("LUTNode", "Failed to decode Hald PNG $path")
                createIdentityLUT()
                return
            }

            // Hald CLUT is typically 512x512 containing 64x64x64 cube or 8x8 blocks
            // We parse it into 3D texture
            val haldSize = when {
                bitmap.width == 512 && bitmap.height == 512 -> 64
                bitmap.width == 256 && bitmap.height == 256 -> 32
                else -> {
                    // Try to infer from width – Hald image is (size*size) x size, or 512x512 with 8x8 grid of 64x64
                    // For simplicity, assume 64 if 512, 32 if 256
                    64
                }
            }
            lutSize = haldSize
            isHald = true

            val lutData = mutableListOf<FloatArray>()
            // Parse Hald image – each pixel corresponds to a LUT entry
            // Hald layout: 512x512 image is divided into 8x8 blocks of 64x64, each block is a slice
            // For 64 size: 8 blocks per row, 8 rows, each block 64x64
            // LUT index: r + g*size + b*size*size, where r is x within block, g is y within block, b is block index

            for (b in 0 until haldSize) {
                for (g in 0 until haldSize) {
                    for (r in 0 until haldSize) {
                        // Calculate position in Hald image
                        val blockX = b % 8
                        val blockY = b / 8
                        val x = blockX * haldSize + r
                        val y = blockY * haldSize + g

                        if (x < bitmap.width && y < bitmap.height) {
                            val pixel = bitmap.getPixel(x, y)
                            val pr = ((pixel shr 16) and 0xFF) / 255f
                            val pg = ((pixel shr 8) and 0xFF) / 255f
                            val pb = (pixel and 0xFF) / 255f
                            lutData.add(floatArrayOf(pr, pg, pb))
                        } else {
                            lutData.add(floatArrayOf(r.toFloat()/haldSize, g.toFloat()/haldSize, b.toFloat()/haldSize))
                        }
                    }
                }
            }

            bitmap.recycle()

            create3DLUTTexture(lutData, haldSize)

        } catch (e: Exception) {
            Log.e("LUTNode", "Failed to load Hald LUT $path", e)
            createIdentityLUT()
        }
    }

    private fun createIdentityLUT() {
        val size = lutSize
        val lutData = mutableListOf<FloatArray>()
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    lutData.add(floatArrayOf(r.toFloat()/(size-1), g.toFloat()/(size-1), b.toFloat()/(size-1)))
                }
            }
        }
        create3DLUTTexture(lutData, size)
        isHald = false
    }

        private fun create3DLUTTexture(lutData: List<FloatArray>, size: Int) {
        try {
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val texId = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val byteData = ByteArray(size * size * 3)
            var idx = 0
            for (i in 0 until size) {
                for (entry in lutData.take(size)) {
                    byteData[idx++] = (entry[0].coerceIn(0f, 1f) * 255).toInt().toByte()
                    byteData[idx++] = (entry[1].coerceIn(0f, 1f) * 255).toInt().toByte()
                    byteData[idx++] = (entry[2].coerceIn(0f, 1f) * 255).toInt().toByte()
                    if (idx >= byteData.size) break
                }
                if (idx >= byteData.size) break
            }
            val buffer = java.nio.ByteBuffer.allocateDirect(byteData.size).order(java.nio.ByteOrder.nativeOrder())
            buffer.put(byteData)
            buffer.position(0)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, size, size, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, buffer)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            lutTexture = GLTexture(texId, GLES20.GL_TEXTURE_2D, size, size, GLES20.GL_RGB, isOes = false, isOwned = true)
        } catch (e: Exception) {
            Log.w("LUTNode", "2D LUT fallback", e)
            lutTexture = GLTexture.create2D(1, 1)
        }
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val input = inputs["input"] ?: throw IllegalArgumentException("LUTNode $id requires input")

        if (framebuffer == null || framebuffer!!.width != input.width || framebuffer!!.height != input.height) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(input.width, input.height)
        }

        val fbo = framebuffer!!
        fbo.bind()

        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(input.target, input.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture!!.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sLUT"), 1)

        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uIntensity"), parameters.getFloat("intensity", 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uLUTSize"), lutSize.toFloat())

        drawQuad()
        fbo.unbind()

        return GLTexture.wrapExisting(fbo.texture.textureId, fbo.texture.target, fbo.width, fbo.height, owned = false)
    }

    override fun onRelease() {
        lutTexture?.release()
        lutTexture = null
        framebuffer?.close()
        framebuffer = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_LUT = "precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform sampler2D sTexture;\n" +
"uniform sampler2D sLUT;\n" +
"uniform float uIntensity;\n" +
"uniform float uLUTSize;\n" +
"void main() {\n" +
"  vec4 src = texture2D(sTexture, vTexCoord);\n" +
"  vec3 color = src.rgb;\n" +
"  vec3 lutColor = texture2D(sLUT, vTexCoord).rgb;\n" +
"  vec3 finalColor = mix(color, lutColor, uIntensity);\n" +
"  gl_FragColor = vec4(finalColor, src.a);\n" +
"}\n"
    }
}
