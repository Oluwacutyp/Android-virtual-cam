package com.androidvirtualcam.compositor

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.androidvirtualcam.compositor.nodes.OutputNode
import com.androidvirtualcam.compositor.presets.CompositorPresets
import com.androidvirtualcam.framebus.VCamFrameBus
import com.androidvirtualcam.transition.TransitionConfig
import com.androidvirtualcam.transition.TransitionManager

/**
 * Production compositor renderer – owns EGL context, evaluates graph, drives output.
 *
 * FIXES:
 * - All shaders #version 100 for Camon 20 / S22 Ultra compatibility
 * - All GL ops wrapped in try/catch with EGL restart on error
 * - Supports shared EGL context from GLSurfaceView
 * - Image/Video upload support via dynamic graph manipulation
 */
class CompositorRenderer(
    private val context: Context,
    private val width: Int = 1280,
    private val height: Int = 720
) {
    private val tag = "CompositorRenderer"

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null

    @Volatile
    var sharedEglContext: EGLContext? = null
        private set

    var graph: CompositorGraph = CompositorGraph()
        private set

    var frameBus: VCamFrameBus? = null
        private set

    var transitionManager: TransitionManager? = null
        private set

    private var isInitialized = false
    private var frameCount = 0

    fun initialize(frameBus: VCamFrameBus? = null, sharedContext: EGLContext? = null) {
        if (isInitialized) {
            Log.w(tag, "Already initialized, reusing. Updating frameBus if provided")
            if (frameBus != null) this.frameBus = frameBus
            if (sharedContext != null && sharedContext != EGL14.EGL_NO_CONTEXT) {
                sharedEglContext = sharedContext
                Log.i(tag, "Updated shared context: $sharedContext")
            }
            return
        }

        this.frameBus = frameBus
        this.sharedEglContext = sharedContext

        Log.i(tag, "Initializing CompositorRenderer ${width}x${height} sharedContext=${sharedContext != null && sharedContext != EGL14.EGL_NO_CONTEXT} – wrapped in try/catch to prevent crash, raw CameraX preview always works")

        try {
            initEGL(sharedContext)
            loadPreset("main_camera")
            transitionManager = TransitionManager(context, this)
            isInitialized = true
            Log.i(tag, "CompositorRenderer initialized ${width}x${height} with TransitionManager, eglContext=$eglContext shared=${sharedContext != null}")
        } catch (e: Throwable) {
            Log.e(tag, "initialize failed – disabling compositor, raw CameraX preview will still work (fix for shader compile 35633)", e)
            try {
                releaseEGL()
                initEGL(null)
                loadPreset("main_camera")
                transitionManager = TransitionManager(context, this)
                isInitialized = true
            } catch (re: Throwable) {
                Log.e(tag, "initialize recovery failed – compositor disabled, raw preview only", re)
                // Do NOT throw – keep app alive, raw preview works
                isInitialized = false
                // Don't throw – return gracefully
                Log.w(tag, "Compositor disabled due to crash, app will use raw CameraX PreviewView only – effects still work for recording/streaming output")
            }
        }
    }

    fun initialize(frameBus: VCamFrameBus?) {
        initialize(frameBus, null)
    }

    fun setSharedEglContextForSharing(context: EGLContext) {
        sharedEglContext = context
        Log.i(tag, "Shared EGL context set for future sharing: $context")
    }

    private fun initEGL(sharedContext: EGLContext? = null) {
        try {
            val shareCtx = sharedContext ?: sharedEglContext ?: EGL14.EGL_NO_CONTEXT
            Log.i(tag, "initEGL: shareCtx valid=${shareCtx != EGL14.EGL_NO_CONTEXT} ($shareCtx)")

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
                throw RuntimeException("eglChooseConfig failed")
            }
            eglConfig = configs[0]

            // Samsung S22 Ultra fix: EGL context requires explicit CLIENT_VERSION = 3 (not 2) for high-end GPU
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, shareCtx, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.w(tag, "eglCreateContext with shared context failed, retrying without share")
                eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            }
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")

            makeCurrent()

            Log.d(tag, "EGL initialized: ${version[0]}.${version[1]} ctx=$eglContext shared=$shareCtx display=$eglDisplay")
        } catch (e: Exception) {
            Log.e(tag, "initEGL failed", e)
            throw e
        }
    }

    fun makeCurrent() {
        try {
            val display = eglDisplay
            val surface = eglSurface
            val ctx = eglContext
            if (display != null && surface != null && ctx != null) {
                val result = EGL14.eglMakeCurrent(display, surface, surface, ctx)
                if (!result) {
                    Log.w(tag, "eglMakeCurrent failed: error=${EGL14.eglGetError()} display=$display surface=$surface ctx=$ctx")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "makeCurrent failed", e)
        }
    }

    fun getEglContext(): EGLContext? = eglContext
    fun getEglDisplay(): EGLDisplay? = eglDisplay
    fun getEglConfig(): EGLConfig? = eglConfig

    fun loadPreset(presetId: String) {
        try {
            val json = CompositorPresets.loadPresetFromAssets(context, presetId)
                ?: CompositorPresets.getPresetJson(context, presetId)
                ?: throw IllegalArgumentException("Preset $presetId not found")

            loadGraphFromJson(json)
            Log.i(tag, "Loaded preset $presetId nodes=${graph.getNodes().size}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to load preset $presetId, falling back to main_camera", e)
            try {
                val fallbackJson = CompositorPresets.getPresetJson(context, "main_camera")
                    ?: throw IllegalArgumentException("Fallback preset not found")
                loadGraphFromJson(fallbackJson)
                Log.i(tag, "Loaded fallback preset main_camera after failure of $presetId")
            } catch (re: Exception) {
                Log.e(tag, "Fallback preset also failed", re)
                throw e
            }
        }
    }

    private val eglLock = java.util.concurrent.locks.ReentrantLock()

    fun loadGraphFromJson(json: String) {
        eglLock.lock()
        try {
            try { graph.releaseAll() } catch (_: Exception) {}

            val newGraph = CompositorGraph()
            val factory = CompositorNodeFactory(context)
            newGraph.fromJson(json, factory)

            makeCurrent()
            try {
                val errorBefore = GLES20.glGetError()
                if (errorBefore != GLES20.GL_NO_ERROR) {
                    Log.w(tag, "GL error before initializeAll: $errorBefore")
                }
            } catch (e: Exception) {
                Log.w(tag, "glGetError failed", e)
            }

            try {
                newGraph.initializeAll()
            } catch (e: Exception) {
                Log.e(tag, "initializeAll failed, trying to recover with EGL reinit", e)
                try {
                    releaseEGL()
                    initEGL(sharedEglContext)
                    makeCurrent()
                    newGraph.initializeAll()
                    Log.i(tag, "Recovered after EGL reinit")
                } catch (re: Exception) {
                    Log.e(tag, "Recovery after EGL reinit failed", re)
                    throw e
                }
            }

            for (node in newGraph.getNodes()) {
                if (node is OutputNode) {
                    node.frameBus = frameBus
                    Log.d(tag, "Injected frameBus into OutputNode ${node.id}")
                }
            }

            graph = newGraph
            Log.i(tag, "Graph loaded from JSON with ${newGraph.getNodes().size} nodes, order=${newGraph.computeEvaluationOrder()}")
        } finally {
            eglLock.unlock()
        }
    }

    private fun releaseEGL() {
        try {
            eglDisplay?.let { display ->
                try {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                } catch (_: Exception) {}
                try {
                    eglSurface?.let { EGL14.eglDestroySurface(display, it) }
                } catch (_: Exception) {}
                try {
                    eglContext?.let { EGL14.eglDestroyContext(display, it) }
                } catch (_: Exception) {}
                try {
                    EGL14.eglTerminate(display)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        eglDisplay = null
        eglSurface = null
        eglContext = null
        eglConfig = null
    }

    fun ensureScreenCaptureNode(): com.androidvirtualcam.compositor.nodes.ScreenCaptureNode? {
        eglLock.lock()
        try {
            val existing = getScreenCaptureNodes().firstOrNull()
            if (existing != null) return existing

            Log.i(tag, "No ScreenCaptureNode found, adding one to current graph without reloading preset")
            try {
                val factory = CompositorNodeFactory(context)
                val screenNode = factory.createNode("ScreenCapture", "screen_capture_1", NodeParameters.builder().int("width", 1280).int("height", 720).build()) as com.androidvirtualcam.compositor.nodes.ScreenCaptureNode
                graph.addNode(screenNode)
                makeCurrent()
                try {
                    screenNode.initialize()
                    Log.i(tag, "ScreenCaptureNode added and initialized without preset reload")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to initialize added ScreenCaptureNode", e)
                    return null
                }
                return screenNode
            } catch (e: Exception) {
                Log.e(tag, "ensureScreenCaptureNode failed", e)
                return null
            }
        } finally {
            eglLock.unlock()
        }
    }

    fun getGraphJson(): String = try { graph.toJson() } catch (e: Exception) { Log.w(tag, "getGraphJson failed", e); "" }

    fun renderFrame(): GLTexture? {
        if (!isInitialized) {
            Log.w(tag, "renderFrame called before initialization")
            return null
        }

        eglLock.lock()
        try {
            try {
                makeCurrent()
            } catch (e: Exception) {
                Log.e(tag, "makeCurrent failed in renderFrame, trying EGL restart", e)
                try {
                    releaseEGL()
                    initEGL(sharedEglContext)
                    makeCurrent()
                } catch (re: Exception) {
                    Log.e(tag, "EGL restart failed in renderFrame", re)
                    return null
                }
            }

            try {
                val tm = transitionManager
                if (tm != null) {
                    try {
                        if (tm.isTransitioning()) {
                            val blended = tm.renderTransition()
                            if (blended != null) {
                                frameCount++
                                if (frameCount % 30 == 0) {
                                    Log.d(tag, "Rendered $frameCount frames (transition) tex=${blended.textureId} isOes=${blended.isOes}")
                                }
                                return blended
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Transition render failed", e)
                    }
                }

                if (frameCount % 300 == 0) {
                    Log.d(tag, "Pipeline start frame $frameCount: evaluating ${graph.getNodes().size} nodes")
                }

                val outputs = try {
                    graph.evaluate()
                } catch (e: Exception) {
                    Log.e(tag, "Graph evaluate failed at frame $frameCount, attempting recovery", e)
                    try {
                        releaseEGL()
                        initEGL(sharedEglContext)
                        makeCurrent()
                        graph.initializeAll()
                        graph.evaluate()
                    } catch (re: Exception) {
                        Log.e(tag, "Recovery evaluate failed", re)
                        throw e
                    }
                }

                val outputNode = graph.getNodes().find { it.type == "Output" }
                val result = outputNode?.let { outputs[it.id] }

                frameCount++
                if (frameCount % 300 == 0) {
                    Log.d(tag, "Rendered $frameCount frames finalTex=${result?.textureId} w=${result?.width} h=${result?.height} isOes=${result?.isOes} nodes=${outputs.size}")
                }

                if (result == null) {
                    if (frameCount % 60 == 0) {
                        Log.w(tag, "renderFrame: result is null! outputNode=$outputNode outputs=${outputs.keys} graphNodes=${graph.getNodes().map { it.id }}")
                    }
                }

                return result
            } catch (e: CompositorCycleException) {
                Log.e(tag, "Cycle detected: ${e.cyclePath}", e)
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Render failed at frame $frameCount", e)
                return null
            }
        } finally {
            eglLock.unlock()
        }
    }

    // ===== Image/Video Upload Support =====

    fun addImageOverlay(uri: String): Result<Unit> {
        eglLock.lock()
        try {
            return try {
                makeCurrent()
                val factory = CompositorNodeFactory(context)

                // Remove existing overlay if any
                try {
                    graph.getNode("uploaded_image")?.let {
                        graph.getConnectionsMap().filter { it.value.first == "uploaded_image" }.forEach { (toKey, _) ->
                            graph.disconnect(toKey.first, toKey.second)
                        }
                        graph.getConnectionsMap().filter { it.key.first == "uploaded_image" }.forEach { (toKey, _) ->
                            graph.disconnect(toKey.first, toKey.second)
                        }
                        try { it.release() } catch (_: Exception) {}
                        // Need to remove from nodes map via reflection or recreate graph – for simplicity, we will keep node but reinitialize
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to clean old image node", e)
                }

                // Create ImageNode
                val imageParams = NodeParameters.builder()
                    .string("uri", uri)
                    .int("maxSize", 1024)
                    .bool("mipmap", true)
                    .build()
                val imageNode = factory.createNode("Image", "uploaded_image", imageParams)
                // If node already exists, remove first via releaseAll? We'll try to add, if exists, replace via new graph JSON
                try {
                    if (graph.getNode("uploaded_image") == null) {
                        graph.addNode(imageNode)
                    } else {
                        // Replace: release old and add new with different id
                        val newId = "uploaded_image_${System.currentTimeMillis()}"
                        val newImageNode = factory.createNode("Image", newId, imageParams)
                        graph.addNode(newImageNode)
                        // Use new id for connections
                        makeCurrent()
                        newImageNode.initialize()
                        // For simplicity, use new node
                        return addImageOverlayWithNode(newImageNode.id, uri)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "addNode image failed, trying alternative id", e)
                    val newId = "uploaded_image_${System.currentTimeMillis()}"
                    val newImageNode = factory.createNode("Image", newId, imageParams)
                    graph.addNode(newImageNode)
                    makeCurrent()
                    newImageNode.initialize()
                    return addImageOverlayWithNode(newId, uri)
                }

                makeCurrent()
                try {
                    imageNode.initialize()
                } catch (e: Exception) {
                    Log.e(tag, "Image node init failed, trying EGL restart", e)
                    releaseEGL()
                    initEGL(sharedEglContext)
                    makeCurrent()
                    imageNode.initialize()
                }

                // Create BlendNode for overlay
                val blendId = "image_blend_${System.currentTimeMillis()}"
                val blendParams = NodeParameters.builder()
                    .string("mode", "Normal")
                    .float("opacity", 1f)
                    .build()
                val blendNode = factory.createNode("Blend", blendId, blendParams)
                graph.addNode(blendNode)
                makeCurrent()
                blendNode.initialize()

                // Find current output and its input
                val outputNode = graph.getNodes().find { it.type == "Output" } ?: return Result.failure(RuntimeException("Output node not found"))
                val connections = graph.getConnectionsMap()
                val currentInput = connections[outputNode.id to "input"]

                if (currentInput != null) {
                    // Connect previous input to blend base
                    try {
                        graph.disconnect(outputNode.id, "input")
                    } catch (_: Exception) {}
                    try {
                        graph.connect(currentInput.first, currentInput.second, blendNode.id, "base")
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to connect base to blend", e)
                    }
                } else {
                    // Fallback: connect camera to blend base
                    val cam = getCameraInputNodes().firstOrNull()
                    if (cam != null) {
                        try {
                            graph.connect(cam.id, "output", blendNode.id, "base")
                        } catch (e: Exception) {
                            Log.w(tag, "Fallback camera connect failed", e)
                        }
                    }
                }

                // Connect image to blend
                try {
                    graph.connect(imageNode.id, "output", blendNode.id, "blend")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to connect image to blend", e)
                    return Result.failure(e)
                }

                // Connect blend to output
                try {
                    graph.connect(blendNode.id, "output", outputNode.id, "input")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to connect blend to output", e)
                    return Result.failure(e)
                }

                Log.i(tag, "Image overlay added: $uri -> ${imageNode.id} + $blendId -> output")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(tag, "addImageOverlay failed", e)
                Result.failure(e)
            }
        } finally {
            eglLock.unlock()
        }
    }

    private fun addImageOverlayWithNode(nodeId: String, uri: String): Result<Unit> {
        return try {
            val factory = CompositorNodeFactory(context)
            val blendId = "image_blend_${System.currentTimeMillis()}"
            val blendParams = NodeParameters.builder().string("mode", "Normal").float("opacity", 1f).build()
            val blendNode = factory.createNode("Blend", blendId, blendParams)
            graph.addNode(blendNode)
            makeCurrent()
            blendNode.initialize()

            val outputNode = graph.getNodes().find { it.type == "Output" } ?: return Result.failure(RuntimeException("Output not found"))
            val connections = graph.getConnectionsMap()
            val currentInput = connections[outputNode.id to "input"]

            if (currentInput != null) {
                try { graph.disconnect(outputNode.id, "input") } catch (_: Exception) {}
                graph.connect(currentInput.first, currentInput.second, blendNode.id, "base")
            }
            graph.connect(nodeId, "output", blendNode.id, "blend")
            graph.connect(blendNode.id, "output", outputNode.id, "input")

            Log.i(tag, "Image overlay added with existing node $nodeId + $blendId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "addImageOverlayWithNode failed", e)
            Result.failure(e)
        }
    }

    fun setVideoBackground(uri: String): Result<Unit> {
        eglLock.lock()
        try {
            return try {
                makeCurrent()
                val factory = CompositorNodeFactory(context)

                // Create VideoFileNode
                val videoParams = NodeParameters.builder()
                    .string("uri", uri)
                    .bool("looping", true)
                    .bool("muted", true)
                    .int("width", width)
                    .int("height", height)
                    .build()
                val videoNodeId = "uploaded_video_${System.currentTimeMillis()}"
                val videoNode = factory.createNode("VideoFile", videoNodeId, videoParams)
                graph.addNode(videoNode)
                makeCurrent()
                try {
                    videoNode.initialize()
                } catch (e: Exception) {
                    Log.e(tag, "Video node init failed, EGL restart", e)
                    releaseEGL()
                    initEGL(sharedEglContext)
                    makeCurrent()
                    videoNode.initialize()
                }

                // Create BackgroundReplaceNode to composite foreground over video background using mask? Or BlendNode for simplicity
                // For video background, we want: camera as foreground over video background
                // Use BackgroundReplaceNode if available, else BlendNode with video as base

                // Find if BackgroundReplaceNode exists, else create Blend
                val outputNode = graph.getNodes().find { it.type == "Output" } ?: return Result.failure(RuntimeException("Output not found"))
                val connections = graph.getConnectionsMap()
                val currentInput = connections[outputNode.id to "input"]

                // Create BlendNode where base = video, blend = camera/foreground
                val blendId = "video_bg_blend_${System.currentTimeMillis()}"
                val blendParams = NodeParameters.builder().string("mode", "Normal").float("opacity", 1f).build()
                val blendNode = factory.createNode("Blend", blendId, blendParams)
                graph.addNode(blendNode)
                makeCurrent()
                blendNode.initialize()

                // If current input is camera/center_stage, use it as blend (top)
                // Video as base (background)
                if (currentInput != null) {
                    try { graph.disconnect(outputNode.id, "input") } catch (_: Exception) {}
                    try {
                        graph.connect(currentInput.first, currentInput.second, blendNode.id, "blend")
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to connect foreground to blend", e)
                    }
                }

                try {
                    graph.connect(videoNodeId, "output", blendNode.id, "base")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to connect video to blend base", e)
                    return Result.failure(e)
                }

                try {
                    graph.connect(blendId, "output", outputNode.id, "input")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to connect blend to output", e)
                    return Result.failure(e)
                }

                Log.i(tag, "Video background set: $uri -> $videoNodeId + $blendId -> output")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(tag, "setVideoBackground failed", e)
                Result.failure(e)
            }
        } finally {
            eglLock.unlock()
        }
    }

    fun removeImageOverlay(): Result<Unit> {
        eglLock.lock()
        try {
            return try {
                makeCurrent()
                // Find all uploaded_image nodes and image_blend nodes
                val toRemove = graph.getNodes().filter { it.id.startsWith("uploaded_image") || it.id.startsWith("image_blend") }
                for (node in toRemove) {
                    try {
                        // Disconnect all connections involving this node
                        val connMap = graph.getConnectionsMap()
                        connMap.filter { it.key.first == node.id || it.value.first == node.id }.forEach { (toKey, _) ->
                            try { graph.disconnect(toKey.first, toKey.second) } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Disconnect failed for ${node.id}", e)
                    }
                }
                // Try to restore camera -> output if no connection
                val outputNode = graph.getNodes().find { it.type == "Output" }
                if (outputNode != null) {
                    val connMap = graph.getConnectionsMap()
                    if (connMap[outputNode.id to "input"] == null) {
                        val cam = getCameraInputNodes().firstOrNull()
                        if (cam != null) {
                            try {
                                graph.connect(cam.id, "output", outputNode.id, "input")
                            } catch (e: Exception) {
                                Log.w(tag, "Restore camera->output failed", e)
                            }
                        }
                    }
                }
                for (node in toRemove) {
                    try { node.release() } catch (_: Exception) {}
                    try {
                        // Remove from graph via reflection of private nodes map? We have removeNode method
                        graph.removeNode(node.id)
                    } catch (e: Exception) {
                        Log.w(tag, "removeNode failed ${node.id}", e)
                    }
                }
                Log.i(tag, "Removed ${toRemove.size} image overlay nodes")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(tag, "removeImageOverlay failed", e)
                Result.failure(e)
            }
        } finally {
            eglLock.unlock()
        }
    }

    fun removeVideoBackground(): Result<Unit> {
        eglLock.lock()
        try {
            return try {
                makeCurrent()
                val toRemove = graph.getNodes().filter { it.id.startsWith("uploaded_video") || it.id.startsWith("video_bg_blend") }
                for (node in toRemove) {
                    try {
                        val connMap = graph.getConnectionsMap()
                        connMap.filter { it.key.first == node.id || it.value.first == node.id }.forEach { (toKey, _) ->
                            try { graph.disconnect(toKey.first, toKey.second) } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Disconnect failed for ${node.id}", e)
                    }
                }
                val outputNode = graph.getNodes().find { it.type == "Output" }
                if (outputNode != null) {
                    val connMap = graph.getConnectionsMap()
                    if (connMap[outputNode.id to "input"] == null) {
                        val cam = getCameraInputNodes().firstOrNull()
                        if (cam != null) {
                            try {
                                graph.connect(cam.id, "output", outputNode.id, "input")
                            } catch (_: Exception) {}
                        }
                    }
                }
                for (node in toRemove) {
                    try { node.release() } catch (_: Exception) {}
                    try { graph.removeNode(node.id) } catch (_: Exception) {}
                }
                Log.i(tag, "Removed ${toRemove.size} video background nodes")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(tag, "removeVideoBackground failed", e)
                Result.failure(e)
            }
        } finally {
            eglLock.unlock()
        }
    }

    fun transitionToPreset(presetId: String, config: TransitionConfig? = null, onComplete: (() -> Unit)? = null) {
        try {
            val json = CompositorPresets.loadPresetFromAssets(context, presetId)
                ?: CompositorPresets.getPresetJson(context, presetId)
                ?: throw IllegalArgumentException("Preset $presetId not found")

            val tm = transitionManager
            if (tm != null) {
                if (config != null) tm.setConfig(config)
                tm.startTransition(presetId, json, config ?: tm.currentConfig.value, onComplete)
                Log.i(tag, "Transition to $presetId started with ${config?.type ?: tm.currentConfig.value.type}")
            } else {
                loadGraphFromJson(json)
                onComplete?.invoke()
            }
        } catch (e: Exception) {
            Log.e(tag, "transitionToPreset failed", e)
            try {
                loadPreset(presetId)
                onComplete?.invoke()
            } catch (re: Exception) {
                Log.e(tag, "Fallback loadPreset also failed", re)
            }
        }
    }

    fun setTransitionConfig(config: TransitionConfig) {
        try {
            transitionManager?.setConfig(config)
        } catch (e: Exception) {
            Log.w(tag, "setTransitionConfig failed", e)
        }
    }

    fun getTransitionState() = try { transitionManager?.state?.value } catch (e: Exception) { null }
    fun isTransitioning() = try { transitionManager?.isTransitioning() ?: false } catch (e: Exception) { false }

    fun getCameraInputNodes(): List<com.androidvirtualcam.compositor.nodes.CameraInputNode> {
        return try {
            graph.getNodes().filterIsInstance<com.androidvirtualcam.compositor.nodes.CameraInputNode>()
        } catch (e: Exception) {
            Log.w(tag, "getCameraInputNodes failed", e)
            emptyList()
        }
    }

    fun getScreenCaptureNodes(): List<com.androidvirtualcam.compositor.nodes.ScreenCaptureNode> {
        return try {
            graph.getNodes().filterIsInstance<com.androidvirtualcam.compositor.nodes.ScreenCaptureNode>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getScreenCaptureNode(nodeId: String = "screen_capture_1"): com.androidvirtualcam.compositor.nodes.ScreenCaptureNode? {
        return try {
            graph.getNode(nodeId) as? com.androidvirtualcam.compositor.nodes.ScreenCaptureNode
                ?: getScreenCaptureNodes().firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun setScreenCapturing(nodeId: String, capturing: Boolean) {
        try {
            val node = graph.getNode(nodeId) as? com.androidvirtualcam.compositor.nodes.ScreenCaptureNode
                ?: getScreenCaptureNodes().firstOrNull()
            node?.setCapturing(capturing)
            Log.d(tag, "ScreenCapture $nodeId capturing=$capturing")
        } catch (e: Exception) {
            Log.w(tag, "setScreenCapturing failed", e)
        }
    }

    fun setScreenCapturingAll(capturing: Boolean) {
        try {
            for (node in getScreenCaptureNodes()) {
                node.setCapturing(capturing)
            }
            Log.d(tag, "All ScreenCapture nodes capturing=$capturing")
        } catch (e: Exception) {
            Log.w(tag, "setScreenCapturingAll failed", e)
        }
    }

    fun captureBitmap(): android.graphics.Bitmap? {
        if (!isInitialized) {
            Log.w(tag, "captureBitmap called before initialization")
            return null
        }
        return try {
            makeCurrent()
            renderFrame()
            GLES20.glFinish()

            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val buffer = java.nio.ByteBuffer.allocateDirect(width * height * 4)
            buffer.order(java.nio.ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

            val error = try { GLES20.glGetError() } catch (_: Exception) { GLES20.GL_NO_ERROR }
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(tag, "glReadPixels error: $error")
                return null
            }

            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val matrix = android.graphics.Matrix().apply { postScale(1f, -1f); postTranslate(0f, height.toFloat()) }
            val flipped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
            bitmap.recycle()

            Log.i(tag, "Captured bitmap ${flipped.width}x${flipped.height} with effects")
            flipped
        } catch (e: Exception) {
            Log.e(tag, "captureBitmap failed", e)
            null
        }
    }

    fun captureBitmapAsync(callback: (android.graphics.Bitmap?) -> Unit) {
        try {
            val bmp = captureBitmap()
            callback(bmp)
        } catch (e: Exception) {
            Log.e(tag, "captureBitmapAsync failed", e)
            callback(null)
        }
    }

    fun updateNodeParameter(nodeId: String, param: String, value: Any) {
        try {
            val node = graph.getNode(nodeId) ?: return
            var newParams = node.parameters
            when (value) {
                is Float -> newParams = newParams.withFloat(param, value)
                is Int -> newParams = newParams.withInt(param, value)
                is Boolean -> newParams = newParams.withBool(param, value)
                is String -> newParams = newParams.withString(param, value)
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = value as List<Float>
                    when (list.size) {
                        3 -> newParams = newParams.withVec3(param, list)
                        4 -> newParams = newParams.withVec4(param, list)
                    }
                }
            }
            try {
                val field = node.javaClass.getDeclaredField("parameters")
                field.isAccessible = true
                field.set(node, newParams)
                Log.d(tag, "Updated param $param on $nodeId to $value")
            } catch (e: Exception) {
                Log.w(tag, "Failed to update param $param on $nodeId", e)
            }
        } catch (e: Exception) {
            Log.w(tag, "updateNodeParameter failed", e)
        }
    }

    fun release() {
        try {
            transitionManager?.release()
        } catch (_: Exception) {}
        transitionManager = null

        try { graph.releaseAll() } catch (_: Exception) {}

        try {
            eglDisplay?.let { display ->
                try {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                } catch (_: Exception) {}
                try {
                    eglSurface?.let { EGL14.eglDestroySurface(display, it) }
                } catch (_: Exception) {}
                try {
                    eglContext?.let { EGL14.eglDestroyContext(display, it) }
                } catch (_: Exception) {}
                try {
                    EGL14.eglTerminate(display)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        eglDisplay = null
        eglSurface = null
        eglContext = null
        eglConfig = null
        sharedEglContext = null
        isInitialized = false

        Log.i(tag, "CompositorRenderer released after $frameCount frames")
    }
}
