package com.androidvirtualcam.compositor.nodes

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.androidvirtualcam.compositor.*

/**
 * VideoFileNode – MediaPlayer → SurfaceTexture → OES, looping, seek support, audio mute option.
 * Production: manages MediaPlayer lifecycle, SurfaceTexture, OES shader, looping, mute via volume.
 */
class VideoFileNode(
    override val id: String,
    private val context: Context,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("uri", "")
        .bool("looping", true)
        .bool("muted", true)
        .float("speed", 1f)
        .int("width", 1280)
        .int("height", 720)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "VideoFile",
    inputSockets = emptyList(),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var oesTexture: GLTexture? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    private var transformMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private var mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var width: Int = 1280
    private var height: Int = 720
    private var isPrepared = false
    private var isLooping = true
    private var isMuted = true

    override fun onInitialize() {
        width = parameters.getInt("width", 1280)
        height = parameters.getInt("height", 720)
        isLooping = parameters.getBool("looping", true)
        isMuted = parameters.getBool("muted", true)

        oesTexture = GLTexture.createOes()
        surfaceTexture = SurfaceTexture(oesTexture!!.textureId).apply {
            setDefaultBufferSize(width, height)
        }
        surface = Surface(surfaceTexture)

        try {
            compileProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)
        } catch (e: Exception) {
            Log.e("VideoFileNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG_OES) } catch (_: Exception) { try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {} }
        }

        Matrix.setIdentityM(transformMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)

        val uriString = parameters.getString("uri", "")
        if (uriString.isNotEmpty()) {
            prepareMediaPlayer(uriString)
        }

        Log.d("VideoFileNode", "Initialized $id ${width}x${height} looping=$isLooping muted=$isMuted")
    }

    private fun prepareMediaPlayer(uriString: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setSurface(surface)
                isLooping = this@VideoFileNode.isLooping

                if (isMuted) {
                    setVolume(0f, 0f)
                } else {
                    setVolume(1f, 1f)
                }

                val speed = parameters.getFloat("speed", 1f)
                if (speed != 1f && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    playbackParams = playbackParams.setSpeed(speed)
                }

                setOnPreparedListener { mp ->
                    isPrepared = true
                    mp.start()
                    Log.d("VideoFileNode", "MediaPlayer prepared and started for $id")
                }

                setOnCompletionListener { mp ->
                    if (isLooping) {
                        mp.seekTo(0)
                        mp.start()
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Log.e("VideoFileNode", "MediaPlayer error what=$what extra=$extra")
                    false
                }

                try {
                    if (uriString.startsWith("http") || uriString.startsWith("content://") || uriString.startsWith("file://")) {
                        setDataSource(context, Uri.parse(uriString))
                    } else {
                        setDataSource(uriString)
                    }
                    prepareAsync()
                } catch (e: Exception) {
                    Log.e("VideoFileNode", "setDataSource failed for $uriString", e)
                }
            }
        } catch (e: Exception) {
            Log.e("VideoFileNode", "prepareMediaPlayer failed", e)
        }
    }

    fun seekTo(ms: Int) {
        try {
            mediaPlayer?.seekTo(ms)
        } catch (e: Exception) {
            Log.w("VideoFileNode", "seekTo failed", e)
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        parameters = parameters.withBool("muted", muted)
        try {
            if (muted) mediaPlayer?.setVolume(0f, 0f) else mediaPlayer?.setVolume(1f, 1f)
        } catch (e: Exception) {
            Log.w("VideoFileNode", "setMuted failed", e)
        }
    }

    fun setLooping(looping: Boolean) {
        isLooping = looping
        parameters = parameters.withBool("looping", looping)
        try { mediaPlayer?.isLooping = looping } catch (e: Exception) { Log.w("VideoFileNode", "setLooping failed", e) }
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "Node not initialized" }
        val tex = oesTexture ?: throw IllegalStateException("OES texture not created")

        try {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(transformMatrix)
        } catch (e: Exception) {
            // Not yet available
        }

        val fbo = framebuffer ?: GLFramebuffer.create(width, height).also { framebuffer = it }
        fbo.bind()

        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "sTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, "uMVP"), 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, "uTexMatrix"), 1, false, transformMatrix, 0)

        drawQuad()
        fbo.unbind()

        return GLTexture.wrapExisting(fbo.texture.textureId, fbo.texture.target, width, height, owned = false)
    }

    private var framebuffer: GLFramebuffer? = null

    override fun onRelease() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        oesTexture?.release()
        oesTexture = null
        framebuffer?.close()
        framebuffer = null
        isPrepared = false
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_OES = "#extension GL_OES_EGL_image_external : require\n" +
"precision mediump float;\n" +
"varying vec2 vTexCoord;\n" +
"uniform samplerExternalOES sTexture;\n" +
"void main() {\n" +
"  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
"}\n"
    }
}
