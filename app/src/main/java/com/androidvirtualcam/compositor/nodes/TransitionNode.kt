package com.androidvirtualcam.compositor.nodes

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.androidvirtualcam.compositor.*
import com.androidvirtualcam.transition.TransitionType

/**
 * TransitionNode – professional broadcast transitions between two scenes.
 *
 * Inputs:
 * - from: old scene texture
 * - to: new scene texture
 * - stinger (optional): video clip texture for stinger transition
 *
 * Parameters:
 * - type: string TransitionType name
 * - progress: float 0..1
 * - easedProgress: float 0..1 (after easing)
 * - direction: string LEFT/RIGHT/UP/DOWN
 * - stingerCutPoint: float 0..1 where hard cut happens
 *
 * Shaders implement:
 * - FADE: mix(from,to,progress)
 * - SLIDE_*: push slide with cut line
 * - ZOOM: old zoom out + fade, new zoom in
 * - STINGER: circle wipe + flash + optional video texture luma matte
 */
class TransitionNode(
    override val id: String,
    override var parameters: NodeParameters = NodeParameters.builder()
        .string("type", TransitionType.FADE.name)
        .float("progress", 0f)
        .float("easedProgress", 0f)
        .string("direction", "LEFT")
        .float("stingerCutPoint", 0.5f)
        .bool("hasStinger", false)
        .build()
) : BaseCompositorNode(
    id = id,
    type = "Transition",
    inputSockets = listOf(
        InputSocket("from", "from", SocketType.TEXTURE, displayName = "From Scene"),
        InputSocket("to", "to", SocketType.TEXTURE, displayName = "To Scene"),
        InputSocket("stinger", "stinger", SocketType.TEXTURE, displayName = "Stinger Video", isRequired = false)
    ),
    outputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    parameters = parameters
), CompositorGraph.MutableConnectionsNode {

    private var framebuffer: GLFramebuffer? = null
    private var program: Int = 0

    override fun onInitialize() {
        try {
            program = compileProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_TRANSITION)
        } catch (e: Exception) {
            Log.e("TransitionNode", "Shader compile failed, fallback to passthrough", e)
            try { compileProgram(PASSTHROUGH_VERT, PASSTHROUGH_FRAG) } catch (_: Exception) {}
        }
        Log.d("TransitionNode", "Initialized $id type=${parameters.getString("type")}")
    }

    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        check(isInitialized) { "TransitionNode not initialized" }
        val fromTex = inputs["from"] ?: throw IllegalArgumentException("TransitionNode $id requires from")
        val toTex = inputs["to"] ?: throw IllegalArgumentException("TransitionNode $id requires to")
        val stingerTex = inputs["stinger"] // optional

        val w = fromTex.width
        val h = fromTex.height

        if (framebuffer == null || framebuffer!!.width != w || framebuffer!!.height != h) {
            framebuffer?.close()
            framebuffer = GLFramebuffer.create(w, h)
        }

        val typeStr = parameters.getString("type", TransitionType.FADE.name)
        val type = try { TransitionType.valueOf(typeStr) } catch (_: Exception) { TransitionType.FADE }
        val progress = parameters.getFloat("progress", 0f).coerceIn(0f, 1f)
        val eased = parameters.getFloat("easedProgress", progress).coerceIn(0f, 1f)
        val directionStr = parameters.getString("direction", "LEFT")
        val stingerCut = parameters.getFloat("stingerCutPoint", 0.5f)
        val hasStinger = parameters.getBool("hasStinger", false) && stingerTex != null

        framebuffer!!.bind()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)

        // Bind from
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(fromTex.target, fromTex.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sFrom"), 0)

        // Bind to
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(toTex.target, toTex.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTo"), 1)

        // Bind stinger if available
        if (hasStinger && stingerTex != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(stingerTex.target, stingerTex.textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sStinger"), 2)
        }

        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uType"), type.ordinal)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uProgress"), progress)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uEasedProgress"), eased)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uStingerCut"), stingerCut)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uHasStinger"), if (hasStinger) 1 else 0)

        val dirInt = when (directionStr) {
            "LEFT" -> 0
            "RIGHT" -> 1
            "UP" -> 2
            "DOWN" -> 3
            else -> 0
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uDirection"), dirInt)

        drawQuad()

        framebuffer!!.unbind()

        return GLTexture.wrapExisting(framebuffer!!.texture.textureId, framebuffer!!.texture.target, w, h, owned = false)
    }

    fun setProgress(progress: Float, eased: Float) {
        // Update volatile for thread safety – also update parameters via reflection handled by renderer
        parameters = parameters.withFloat("progress", progress).withFloat("easedProgress", eased)
    }

    fun setType(type: TransitionType, direction: String = "LEFT") {
        parameters = parameters.withString("type", type.name).withString("direction", direction)
    }

    fun getFramebuffer(): GLFramebuffer? = framebuffer

    override fun onRelease() {
        framebuffer?.close()
        framebuffer = null
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}

    companion object {
        private const val FRAGMENT_SHADER_TRANSITION = "precision mediump float;\n" +
"            varying vec2 vTexCoord;\n" +
"            \n" +
"\n" +
"            uniform sampler2D sFrom;\n" +
"            uniform sampler2D sTo;\n" +
"            uniform sampler2D sStinger;\n" +
"\n" +
"            uniform int uType; // TransitionType ordinal\n" +
"            uniform float uProgress; // 0..1 linear\n" +
"            uniform float uEasedProgress; // eased\n" +
"            uniform int uDirection; // 0 LEFT, 1 RIGHT, 2 UP, 3 DOWN\n" +
"            uniform float uStingerCut;\n" +
"            uniform int uHasStinger;\n" +
"\n" +
"            // Easing helpers already applied on CPU, but we have both\n" +
"\n" +
"            // Slide helpers\n" +
"            vec4 slideTransition(sampler2D fromTex, sampler2D toTex, vec2 uv, float p, int dir) {\n" +
"                // p 0..1, dir 0 LEFT (to slides from right), 1 RIGHT, 2 UP, 3 DOWN\n" +
"                if (dir == 0) { // LEFT\n" +
"                    float cut = 1.0 - p;\n" +
"                    if (uv.x < cut) {\n" +
"                        // from\n" +
"                        vec2 uvFrom = vec2(uv.x + p, uv.y);\n" +
"                        return texture2D(fromTex, uvFrom);\n" +
"                    } else {\n" +
"                        vec2 uvTo = vec2(uv.x - cut, uv.y);\n" +
"                        return texture2D(toTex, uvTo);\n" +
"                    }\n" +
"                } else if (dir == 1) { // RIGHT\n" +
"                    float cut = p;\n" +
"                    if (uv.x < cut) {\n" +
"                        vec2 uvTo = vec2(uv.x + (1.0 - p), uv.y);\n" +
"                        return texture2D(toTex, uvTo);\n" +
"                    } else {\n" +
"                        vec2 uvFrom = vec2(uv.x - p, uv.y);\n" +
"                        return texture2D(fromTex, uvFrom);\n" +
"                    }\n" +
"                } else if (dir == 2) { // UP\n" +
"                    float cut = 1.0 - p;\n" +
"                    if (uv.y < cut) {\n" +
"                        vec2 uvFrom = vec2(uv.x, uv.y + p);\n" +
"                        return texture2D(fromTex, uvFrom);\n" +
"                    } else {\n" +
"                        vec2 uvTo = vec2(uv.x, uv.y - cut);\n" +
"                        return texture2D(toTex, uvTo);\n" +
"                    }\n" +
"                } else { // DOWN\n" +
"                    float cut = p;\n" +
"                    if (uv.y < cut) {\n" +
"                        vec2 uvTo = vec2(uv.x, uv.y + (1.0 - p));\n" +
"                        return texture2D(toTex, uvTo);\n" +
"                    } else {\n" +
"                        vec2 uvFrom = vec2(uv.x, uv.y - p);\n" +
"                        return texture2D(fromTex, uvFrom);\n" +
"                    }\n" +
"                }\n" +
"            }\n" +
"\n" +
"            vec4 zoomTransition(sampler2D fromTex, sampler2D toTex, vec2 uv, float p) {\n" +
"                // Old zooms out 1 -> 1.5 + fade, new zooms in 0.5 -> 1\n" +
"                float scaleFrom = 1.0 + p * 0.5;\n" +
"                float scaleTo = 0.5 + p * 0.5;\n" +
"                vec2 uvFrom = (uv - 0.5) / scaleFrom + 0.5;\n" +
"                vec2 uvTo = (uv - 0.5) / scaleTo + 0.5;\n" +
"                vec4 cFrom = texture2D(fromTex, clamp(uvFrom, 0.0, 1.0));\n" +
"                vec4 cTo = texture2D(toTex, clamp(uvTo, 0.0, 1.0));\n" +
"                // Add slight fade\n" +
"                return mix(cFrom, cTo, p);\n" +
"            }\n" +
"\n" +
"            vec4 zoomInTransition(sampler2D fromTex, sampler2D toTex, vec2 uv, float p) {\n" +
"                float scale = 1.0 - p * 0.5; // from 1 to 0.5 zoom in effect\n" +
"                vec2 uvTo = (uv - 0.5) / (0.5 + p*0.5) + 0.5;\n" +
"                vec4 cFrom = texture2D(fromTex, uv);\n" +
"                vec4 cTo = texture2D(toTex, clamp(uvTo, 0.0, 1.0));\n" +
"                return mix(cFrom, cTo, p);\n" +
"            }\n" +
"\n" +
"            vec4 zoomOutTransition(sampler2D fromTex, sampler2D toTex, vec2 uv, float p) {\n" +
"                float scaleFrom = 1.0 + p;\n" +
"                vec2 uvFrom = (uv - 0.5) / scaleFrom + 0.5;\n" +
"                vec4 cFrom = texture2D(fromTex, clamp(uvFrom, 0.0, 1.0));\n" +
"                vec4 cTo = texture2D(toTex, uv);\n" +
"                return mix(cFrom, cTo, p);\n" +
"            }\n" +
"\n" +
"            vec4 stingerTransition(sampler2D fromTex, sampler2D toTex, sampler2D stingerTex, vec2 uv, float p, float cutPoint, int hasStinger) {\n" +
"                // Procedural stinger if no video: circle wipe + flash\n" +
"                // Circle expands from center\n" +
"                vec2 center = vec2(0.5, 0.5);\n" +
"                float dist = distance(uv, center);\n" +
"                float maxDist = 0.85; // max distance to corner ~0.707, use 0.85 for full coverage\n" +
"                float radius = p * 1.5; // expands beyond screen\n" +
"\n" +
"                // For stinger video, use luma matte if available\n" +
"                vec4 stingerColor = vec4(1.0);\n" +
"                float stingerLuma = 1.0;\n" +
"                float stingerAlpha = 0.0;\n" +
"\n" +
"                if (hasStinger == 1) {\n" +
"                    stingerColor = texture2D(stingerTex, uv);\n" +
"                    stingerLuma = dot(stingerColor.rgb, vec3(0.299, 0.587, 0.114));\n" +
"                    stingerAlpha = stingerColor.a;\n" +
"                    // If video has no alpha, use luma as alpha\n" +
"                    if (stingerAlpha < 0.01) {\n" +
"                        stingerAlpha = stingerLuma;\n" +
"                    }\n" +
"                }\n" +
"\n" +
"                // Procedural circle wipe\n" +
"                float circle = smoothstep(radius - 0.1, radius + 0.1, dist);\n" +
"                // Invert: inside circle = new scene, outside = old, with stinger overlay\n" +
"                // But for stinger, we want: first half, stinger scales up covering, then cut, then scales down\n" +
"\n" +
"                float cut = cutPoint;\n" +
"                vec4 cFrom = texture2D(fromTex, uv);\n" +
"                vec4 cTo = texture2D(toTex, uv);\n" +
"\n" +
"                if (hasStinger == 1) {\n" +
"                    // Use stinger alpha as wipe\n" +
"                    // When stinger alpha > threshold, show new scene, else old\n" +
"                    // Threshold moves with progress\n" +
"                    float threshold = 1.0 - p; // as progress increases, threshold decreases, revealing more new scene\n" +
"                    // For video stinger, luma matte\n" +
"                    float matte = step(threshold, stingerLuma);\n" +
"                    vec4 base = mix(cFrom, cTo, matte);\n" +
"\n" +
"                    // Overlay stinger with its alpha, but fade out after cut\n" +
"                    float stingerFade = 1.0 - abs(p - cut) * 2.0; // peak at cut point\n" +
"                    stingerFade = clamp(stingerFade, 0.0, 1.0);\n" +
"                    stingerFade = pow(stingerFade, 0.5);\n" +
"\n" +
"                    // Blend stinger over base\n" +
"                    vec3 finalRgb = mix(base.rgb, stingerColor.rgb, stingerColor.a * stingerFade * 0.8);\n" +
"                    return vec4(finalRgb, 1.0);\n" +
"                } else {\n" +
"                    // Procedural stinger: white flash + scale\n" +
"                    if (p < cut) {\n" +
"                        // First half: old scene + expanding white circle that will cover\n" +
"                        float flash = smoothstep(cut - 0.15, cut, p); // 0..1 as approaches cut\n" +
"                        float circleAlpha = 1.0 - circle; // inside circle = 1\n" +
"                        // White flash expands\n" +
"                        vec3 flashColor = vec3(1.0);\n" +
"                        float flashIntensity = flash * circleAlpha * 0.9;\n" +
"                        vec3 col = mix(cFrom.rgb, flashColor, flashIntensity);\n" +
"                        return vec4(col, 1.0);\n" +
"                    } else {\n" +
"                        // Second half: new scene + contracting white circle\n" +
"                        float flash = 1.0 - smoothstep(cut, cut + 0.15, p);\n" +
"                        float circleAlpha = circle; // outside circle after cut?\n" +
"                        // Actually after cut, we want white to contract revealing new scene\n" +
"                        float reveal = smoothstep(cut, 1.0, p);\n" +
"                        float innerRadius = (1.0 - p) * 1.5;\n" +
"                        float innerCircle = smoothstep(innerRadius - 0.1, innerRadius + 0.1, dist);\n" +
"                        vec3 flashColor = vec3(1.0);\n" +
"                        float flashIntensity = flash * (1.0 - innerCircle) * 0.9;\n" +
"                        vec3 col = mix(cTo.rgb, flashColor, flashIntensity);\n" +
"                        return vec4(col, 1.0);\n" +
"                    }\n" +
"                }\n" +
"            }\n" +
"\n" +
"            void main() {\n" +
"                float p = uEasedProgress;\n" +
"                float linearP = uProgress;\n" +
"\n" +
"                vec4 result;\n" +
"\n" +
"                if (uType == 0) { // CUT\n" +
"                    if (linearP < 0.5) {\n" +
"                        result = texture2D(sFrom, vTexCoord);\n" +
"                    } else {\n" +
"                        result = texture2D(sTo, vTexCoord);\n" +
"                    }\n" +
"                } else if (uType == 1) { // FADE\n" +
"                    vec4 cFrom = texture2D(sFrom, vTexCoord);\n" +
"                    vec4 cTo = texture2D(sTo, vTexCoord);\n" +
"                    result = mix(cFrom, cTo, p);\n" +
"                } else if (uType == 2 || uType == 3 || uType == 4 || uType == 5) { // SLIDE_*\n" +
"                    int dir = 0;\n" +
"                    if (uType == 2) dir = 0; // LEFT\n" +
"                    else if (uType == 3) dir = 1; // RIGHT\n" +
"                    else if (uType == 4) dir = 2; // UP (actually SLIDE_UP)\n" +
"                    else if (uType == 5) dir = 3; // DOWN\n" +
"                    // Override with uDirection if set\n" +
"                    if (uDirection >= 0 && uDirection <= 3) dir = uDirection;\n" +
"                    result = slideTransition(sFrom, sTo, vTexCoord, p, dir);\n" +
"                } else if (uType == 6) { // ZOOM\n" +
"                    result = zoomTransition(sFrom, sTo, vTexCoord, p);\n" +
"                } else if (uType == 7) { // ZOOM_IN\n" +
"                    result = zoomInTransition(sFrom, sTo, vTexCoord, p);\n" +
"                } else if (uType == 8) { // ZOOM_OUT\n" +
"                    result = zoomOutTransition(sFrom, sTo, vTexCoord, p);\n" +
"                } else if (uType == 9) { // STINGER\n" +
"                    result = stingerTransition(sFrom, sTo, sStinger, vTexCoord, p, uStingerCut, uHasStinger);\n" +
"                } else {\n" +
"                    // Fallback fade\n" +
"                    result = mix(texture2D(sFrom, vTexCoord), texture2D(sTo, vTexCoord), p);\n" +
"                }\n" +
"\n" +
"                gl_FragColor = result;\n" +
"            }\n"
    }
}
