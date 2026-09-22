package com.androidvirtualcam.transition

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.CompositorGraph
import com.androidvirtualcam.compositor.CompositorNodeFactory
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.compositor.GLFramebuffer
import com.androidvirtualcam.compositor.GLTexture
import com.androidvirtualcam.compositor.nodes.OutputNode
import com.androidvirtualcam.compositor.nodes.TransitionNode
import com.androidvirtualcam.compositor.nodes.VideoFileNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Professional broadcast transition manager – OBS-like.
 *
 * Manages transition between two compositor graphs:
 * - Keeps primary graph (current scene) and secondary graph (next scene)
 * - Freezes current frame as texture A at transition start
 * - Renders next scene live as texture B
 * - Blends via TransitionNode with easing over duration
 * - Supports Fade, Slide (4 dirs), Zoom, Stinger (video clip)
 *
 * Integrated into CompositorRenderer – renderFrame() checks isTransitioning.
 */
class TransitionManager(
    private val context: Context,
    private val renderer: CompositorRenderer
) {
    companion object {
        private const val TAG = "TransitionManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(TransitionState())
    val state: StateFlow<TransitionState> = _state

    private val _currentConfig = MutableStateFlow(TransitionConfig.default())
    val currentConfig: StateFlow<TransitionConfig> = _currentConfig

    private var secondaryGraph: CompositorGraph? = null
    private var transitionNode: TransitionNode? = null
    private var frozenFramebuffer: GLFramebuffer? = null
    private var frozenTexture: GLTexture? = null

    private var stingerNode: VideoFileNode? = null
    private var stingerTexture: GLTexture? = null

    private var transitionJob: Job? = null
    private var isTransitioning = false
    private var fromPresetId = ""
    private var toPresetId = ""

    private val frameBus get() = renderer.frameBus

    fun setConfig(config: TransitionConfig) {
        _currentConfig.value = config
        Log.i(TAG, "Transition config set: ${config.type} ${config.durationMs}ms ${config.easing}")
    }

    fun isTransitioning(): Boolean = isTransitioning

    /**
     * Start transition to new preset JSON.
     * @param toPresetId id of destination preset
     * @param toJson JSON of destination graph
     * @param config transition config (if null, uses current config)
     */
    fun startTransition(
        toPresetId: String,
        toJson: String,
        config: TransitionConfig? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val cfg = config ?: _currentConfig.value

        // If CUT or duration 0, do instant switch
        if (cfg.transitionType == TransitionType.CUT || cfg.durationMs <= 50L) {
            Log.i(TAG, "CUT transition to $toPresetId")
            try {
                renderer.loadGraphFromJson(toJson)
                _state.value = TransitionState(
                    isTransitioning = false,
                    progress = 1f,
                    fromPresetId = fromPresetId,
                    toPresetId = toPresetId,
                    config = cfg
                )
                fromPresetId = toPresetId
                onComplete?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "CUT transition failed", e)
            }
            return
        }

        if (isTransitioning) {
            Log.w(TAG, "Already transitioning, cancelling previous")
            cancelTransition()
        }

        this.fromPresetId = renderer.graph.getNodes().firstOrNull()?.id ?: "unknown"
        this.toPresetId = toPresetId

        Log.i(TAG, "Starting ${cfg.type} transition to $toPresetId duration=${cfg.durationMs}ms easing=${cfg.easing}")

        try {
            // Freeze current frame
            freezeCurrentFrame()

            // Load secondary graph
            val factory = CompositorNodeFactory(context)
            val newGraph = CompositorGraph()
            newGraph.fromJson(toJson, factory)

            // Share frameBus with secondary graph's output node
            renderer.makeCurrent()
            newGraph.initializeAll()
            for (node in newGraph.getNodes()) {
                if (node is OutputNode) {
                    node.frameBus = null // Don't publish from secondary during transition, we publish blended
                }
            }

            // Share camera texture from primary to secondary to keep both scenes live during transition
            try {
                val primaryCams = renderer.getCameraInputNodes()
                val secondaryCams = newGraph.getNodes().filterIsInstance<com.androidvirtualcam.compositor.nodes.CameraInputNode>()
                if (primaryCams.isNotEmpty() && secondaryCams.isNotEmpty()) {
                    // Share first primary cam texture to all secondary cams
                    val primaryCam = primaryCams[0]
                    for (secCam in secondaryCams) {
                        secCam.shareTextureFrom(primaryCam)
                    }
                    Log.i(TAG, "Shared camera texture from primary to secondary (${secondaryCams.size} nodes)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to share camera texture: ${e.message}")
            }

            secondaryGraph = newGraph

            // Create transition node
            val transitionParams = com.androidvirtualcam.compositor.NodeParameters.builder()
                .string("type", cfg.type)
                .float("progress", 0f)
                .float("easedProgress", 0f)
                .string("direction", cfg.slideDirection)
                .float("stingerCutPoint", cfg.stingerCutPoint)
                .bool("hasStinger", cfg.stingerVideoPath.isNotEmpty())
                .build()

            val tNode = TransitionNode(id = "transition_1", parameters = transitionParams)
            renderer.makeCurrent()
            tNode.initialize()
            transitionNode = tNode

            // Setup stinger if needed
            if (cfg.transitionType == TransitionType.STINGER && cfg.stingerVideoPath.isNotEmpty()) {
                setupStinger(cfg.stingerVideoPath)
            }

            isTransitioning = true
            _state.value = TransitionState(
                isTransitioning = true,
                progress = 0f,
                easedProgress = 0f,
                fromPresetId = this.fromPresetId,
                toPresetId = toPresetId,
                config = cfg,
                elapsedMs = 0L
            )

            // Start progress animation
            transitionJob?.cancel()
            transitionJob = scope.launch {
                val startTime = System.currentTimeMillis()
                val duration = cfg.durationMs
                var elapsed: Long

                while (true) {
                    elapsed = System.currentTimeMillis() - startTime
                    val rawProgress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                    val easedProgress = cfg.easingType.apply(rawProgress)

                    // Update transition node params
                    try {
                        renderer.makeCurrent()
                        tNode.parameters = tNode.parameters
                            .withFloat("progress", rawProgress)
                            .withFloat("easedProgress", easedProgress)

                        _state.value = TransitionState(
                            isTransitioning = rawProgress < 1f,
                            progress = rawProgress,
                            easedProgress = easedProgress,
                            fromPresetId = fromPresetId,
                            toPresetId = toPresetId,
                            config = cfg,
                            elapsedMs = elapsed
                        )

                        if (rawProgress >= 1f) {
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Transition update failed: ${e.message}")
                    }

                    delay(16L) // ~60fps
                }

                // Transition complete – make secondary primary
                withContext(Dispatchers.Main) {
                    completeTransition(toJson, cfg, onComplete)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start transition", e)
            cancelTransition()
            // Fallback to hard cut
            try {
                renderer.loadGraphFromJson(toJson)
                onComplete?.invoke()
            } catch (re: Exception) {
                Log.e(TAG, "Fallback CUT also failed", re)
            }
        }
    }

    private fun freezeCurrentFrame() {
        try {
            renderer.makeCurrent()
            val currentTex = renderer.renderFrame()
            if (currentTex != null) {
                val w = currentTex.width
                val h = currentTex.height

                if (frozenFramebuffer == null || frozenFramebuffer!!.width != w || frozenFramebuffer!!.height != h) {
                    frozenFramebuffer?.close()
                    frozenFramebuffer = GLFramebuffer.create(w, h)
                }

                // Copy current texture to frozen framebuffer via blit
                frozenFramebuffer!!.bind()
                GLES20.glViewport(0, 0, w, h)

                // Simple copy using TransitionNode's passthrough or direct draw
                // We'll use a simple shader copy – for now just blit via framebuffer blit if possible
                // Since currentTex may be from FBO, we can blit

                // For simplicity, render currentTex to frozen FBO using a copy program
                // Create temporary program if needed – reuse transition node's simple copy via FADE 0
                // We'll do manual copy: bind from texture and draw quad with simple shader

                // Use GLES30 to copy – we need a program, we can use a simple copy shader
                val program = createCopyProgram()
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(currentTex.target, currentTex.textureId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

                // Draw quad – need VAO/VBO, we can create simple ones
                drawSimpleQuad(program)

                GLES20.glDeleteProgram(program)

                frozenFramebuffer!!.unbind()

                frozenTexture = GLTexture.wrapExisting(
                    frozenFramebuffer!!.texture.textureId,
                    frozenFramebuffer!!.texture.target,
                    w, h,
                    owned = false
                )

                Log.d(TAG, "Frozen current frame ${w}x${h}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to freeze frame", e)
        }
    }

    private fun createCopyProgram(): Int {
        val vs = """
            #version 100
            precision mediump float;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        val fs = """
            #version 100
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """.trimIndent()

        val vsId = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vsId, vs)
        GLES20.glCompileShader(vsId)
        val fsId = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fsId, fs)
        GLES20.glCompileShader(fsId)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vsId)
        GLES20.glAttachShader(prog, fsId)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vsId)
        GLES20.glDeleteShader(fsId)
        return prog
    }

    private fun drawSimpleQuad(program: Int) {
        // Simple quad vertices
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        val vboIds = IntArray(1)
        GLES20.glGenBuffers(1, vboIds, 0)
        val vbo = vboIds[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val buf = java.nio.ByteBuffer.allocateDirect(vertices.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(vertices)
        buf.position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, buf, GLES20.GL_STATIC_DRAW)

        val posLoc = GLES20.glGetAttribLocation(program, "a_Position")
        val texLoc = GLES20.glGetAttribLocation(program, "a_TexCoord")

        if (posLoc >= 0) {
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, 0)
        }
        if (texLoc >= 0) {
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, 8)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        if (texLoc >= 0) GLES20.glDisableVertexAttribArray(texLoc)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }

    private fun setupStinger(videoPath: String) {
        try {
            Log.i(TAG, "Setting up stinger video: $videoPath")
            val params = com.androidvirtualcam.compositor.NodeParameters.builder()
                .string("path", videoPath)
                .bool("loop", false)
                .bool("autoPlay", true)
                .build()
            val factory = CompositorNodeFactory(context)
            val node = factory.createNode("VideoFile", "stinger_video", params) as VideoFileNode
            renderer.makeCurrent()
            node.initialize()
            stingerNode = node
        } catch (e: Exception) {
            Log.e(TAG, "Stinger setup failed, using procedural", e)
        }
    }

    /**
     * Called from CompositorRenderer.renderFrame() when transitioning.
     * Returns blended texture.
     */
    fun renderTransition(): GLTexture? {
        if (!isTransitioning) return null

        val secondary = secondaryGraph ?: return null
        val tNode = transitionNode ?: return null
        val frozen = frozenTexture

        if (frozen == null) {
            Log.w(TAG, "Frozen texture null during transition")
            return null
        }

        return try {
            renderer.makeCurrent()

            // Evaluate secondary graph to get B texture
            val secondaryOutputs = secondary.evaluate()
            val outputNode = secondary.getNodes().find { it.type == "Output" }
            val textureB = outputNode?.let { secondaryOutputs[it.id] }
                ?: secondaryOutputs.values.firstOrNull()

            if (textureB == null) {
                Log.w(TAG, "Secondary graph produced no texture")
                return null
            }

            // Evaluate stinger if present
            var stingerTex: GLTexture? = null
            stingerNode?.let { sNode ->
                try {
                    val stingerOut = sNode.process(emptyMap())
                    stingerTex = stingerOut
                    stingerTexture = stingerOut
                } catch (e: Exception) {
                    Log.w(TAG, "Stinger node process failed: ${e.message}")
                }
            }

            // Blend via transition node
            val inputs = mutableMapOf<String, GLTexture>(
                "from" to frozen,
                "to" to textureB
            )
            if (stingerTex != null) {
                inputs["stinger"] = stingerTex!!
            }

            val blended = tNode.process(inputs)

            // Publish blended to framebus (like OutputNode does)
            publishBlendedToBus(blended)

            blended
        } catch (e: Exception) {
            Log.e(TAG, "renderTransition failed", e)
            null
        }
    }

    private fun publishBlendedToBus(texture: GLTexture) {
        try {
            // Similar to OutputNode publish logic – we need to read pixels and publish to VCamFrameBus
            // For performance, we reuse renderer's framebus if available
            // We'll do glReadPixels from current FBO that contains blended texture
            // The blended texture is in transitionNode's framebuffer – we need to read from it

            // The transitionNode's framebuffer is already bound during process, but now unbound
            // We need to bind it and read
            val fb = getTransitionFramebuffer()
            fb?.let { framebuffer ->
                framebuffer.bind()
                val w = framebuffer.width
                val h = framebuffer.height

                val rgbaBuffer = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBuffer)

                // Convert to NV21 and publish via frameBus
                // Simplified – we publish RGBA as is? For now, try to publish via OutputNode's conversion if available
                // We'll try to get OutputNode from primary graph and use its publish method? Or use VCamFrameBus directly

                // For MVP, we will publish via frameBus if it expects RGBA? Actually frameBus expects NV21
                // We'll do simple conversion similar to OutputNode

                framebuffer.unbind()

                // Convert and publish – reuse logic from OutputNode (simplified)
                val nv21 = java.nio.ByteBuffer.allocateDirect(w * h * 3 / 2).order(java.nio.ByteOrder.nativeOrder())
                convertRgbaToNv21(rgbaBuffer, nv21, w, h)

                frameBus?.publishFrame(nv21, System.nanoTime(), w, h)
            }
        } catch (e: Exception) {
            Log.w(TAG, "publishBlendedToBus failed: ${e.message}")
        }
    }

    private fun getTransitionFramebuffer(): GLFramebuffer? {
        return try {
            transitionNode?.getFramebuffer()
        } catch (_: Exception) {
            null
        }
    }

    private fun convertRgbaToNv21(rgba: java.nio.ByteBuffer, nv21: java.nio.ByteBuffer, width: Int, height: Int) {
        rgba.position(0)
        val ySize = width * height
        val row = ByteArray(width * 4)

        for (y in 0 until height) {
            val srcY = height - 1 - y
            rgba.position(srcY * width * 4)
            rgba.get(row, 0, width * 4)
            for (x in 0 until width) {
                val r = row[x * 4].toInt() and 0xFF
                val g = row[x * 4 + 1].toInt() and 0xFF
                val b = row[x * 4 + 2].toInt() and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv21.put(y * width + x, yVal.coerceIn(0, 255).toByte())
            }
        }

        var uvIndex = ySize
        for (y in 0 until height step 2) {
            val srcY = height - 1 - y
            for (x in 0 until width step 2) {
                val pos = srcY * width * 4 + x * 4
                rgba.position(pos)
                val r = rgba.get().toInt() and 0xFF
                val g = rgba.get().toInt() and 0xFF
                val b = rgba.get().toInt() and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                if (uvIndex + 1 < nv21.capacity()) {
                    nv21.put(uvIndex++, v.coerceIn(0, 255).toByte())
                    nv21.put(uvIndex++, u.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private fun completeTransition(toJson: String, cfg: TransitionConfig, onComplete: (() -> Unit)?) {
        try {
            Log.i(TAG, "Transition to $toPresetId complete")
            // Load final graph as primary
            renderer.loadGraphFromJson(toJson)

            // Cleanup
            secondaryGraph?.let {
                try { it.releaseAll() } catch (_: Exception) {}
            }
            secondaryGraph = null

            transitionNode?.let {
                try { it.release() } catch (_: Exception) {}
            }
            transitionNode = null

            stingerNode?.let {
                try { it.release() } catch (_: Exception) {}
            }
            stingerNode = null

            frozenFramebuffer?.close()
            frozenFramebuffer = null
            frozenTexture = null
            stingerTexture = null

            isTransitioning = false
            _state.value = TransitionState(
                isTransitioning = false,
                progress = 1f,
                easedProgress = 1f,
                fromPresetId = fromPresetId,
                toPresetId = toPresetId,
                config = cfg,
                elapsedMs = cfg.durationMs
            )

            fromPresetId = toPresetId

            onComplete?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "completeTransition failed", e)
            isTransitioning = false
        }
    }

    fun cancelTransition() {
        transitionJob?.cancel()
        transitionJob = null
        isTransitioning = false

        secondaryGraph?.let {
            try { it.releaseAll() } catch (_: Exception) {}
        }
        secondaryGraph = null

        transitionNode?.let {
            try { it.release() } catch (_: Exception) {}
        }
        transitionNode = null

        stingerNode?.let {
            try { it.release() } catch (_: Exception) {}
        }
        stingerNode = null

        frozenFramebuffer?.close()
        frozenFramebuffer = null
        frozenTexture = null

        _state.value = _state.value.copy(isTransitioning = false)
    }

    fun getSecondaryCameraNodes(): List<com.androidvirtualcam.compositor.nodes.CameraInputNode> {
        return secondaryGraph?.getNodes()?.filterIsInstance<com.androidvirtualcam.compositor.nodes.CameraInputNode>() ?: emptyList()
    }

    fun release() {
        cancelTransition()
        scope.cancel()
    }
}
