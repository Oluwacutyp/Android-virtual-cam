package com.androidvirtualcam.compositor.nodes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.androidvirtualcam.compositor.*

class ImageNode(
    override val id: String,
    private val context: Context,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("uri", "")
        .string("resName", "")
        .bool("mipmap", true)
        .int("maxSize", 1024)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "Image",
    inputSockets = emptyList(),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var texture: GLTexture? = null
    private var width: Int = 0
    private var height: Int = 0
    private var bitmap: Bitmap? = null

    override fun onInitialize() {
        try {
            compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_2D)
        } catch (e: Exception) {
            Log.e("ImageNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }

        val uriString = parameters.getString("uri", "")
        val resName = parameters.getString("resName", "")
        val maxSize = parameters.getInt("maxSize", 1024)
        val useMipmap = parameters.getBool("mipmap", true)

        bitmap = loadBitmap(uriString, resName, maxSize)

        bitmap?.let { bmp ->
            width = bmp.width
            height = bmp.height

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val texId = texIds[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (useMipmap) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)

            if (useMipmap) {
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            texture = GLTexture(texId, GLES20.GL_TEXTURE_2D, width, height, GLES20.GL_RGBA, isOes = false, isOwned = true)

            Log.d("ImageNode", "Loaded bitmap ${width}x${height} for $id, mipmap=$useMipmap")
        } ?: run {
            Log.w("ImageNode", "Failed to load bitmap for $id, using fallback")
            texture = GLTexture.create2D(1, 1)
            width = 1
            height = 1
        }
    }

    private fun loadBitmap(uriString: String, resName: String, maxSize: Int): Bitmap? {
        return try {
            when {
                uriString.isNotEmpty() -> {
                    val uri = Uri.parse(uriString)
                    val input = context.contentResolver.openInputStream(uri) ?: return null

                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, options)
                    input.close()

                    var sampleSize = 1
                    while (options.outWidth / sampleSize > maxSize || options.outHeight / sampleSize > maxSize) {
                        sampleSize *= 2
                    }

                    val input2 = context.contentResolver.openInputStream(uri) ?: return null
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bmp = BitmapFactory.decodeStream(input2, null, decodeOptions)
                    input2.close()
                    bmp
                }
                resName.isNotEmpty() -> {
                    val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
                    if (resId != 0) {
                        BitmapFactory.decodeResource(context.resources, resId)
                    } else null
                }
                else -> {
                    val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.MAGENTA)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 48f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.drawText("IMG", 256f, 256f, paint)
                    bmp
                }
            }
        } catch (e: Exception) {
            Log.e("ImageNode", "loadBitmap failed", e)
            null
        }
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        return texture?.let { GLTexture.wrapExisting(it.textureId, it.target, it.width, it.height, owned = false) }
            ?: throw IllegalStateException("Texture not created")
    }

    override fun onRelease() {
        texture?.release()
        texture = null
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        bitmap = null
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_2D = "precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform sampler2D sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"
    }
}
