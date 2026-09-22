package com.androidvirtualcam.rendering

import android.opengl.GLES20
import android.util.Log

/**
 * Helper for compiling and linking GLSL programs.
 * ManyCam-like free effects included.
 */
class ShaderProgram(private val vertexSource: String, private val fragmentSource: String) {
    var programId: Int = 0
        private set

    fun compile(): Boolean {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return false
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return false
        }
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e("ShaderProgram", "Link failed: ${GLES20.glGetProgramInfoLog(programId)}")
            GLES20.glDeleteProgram(programId)
            programId = 0
            return false
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return true
    }

    fun use() { GLES20.glUseProgram(programId) }
    fun getAttribLocation(name: String): Int = GLES20.glGetAttribLocation(programId, name)
    fun getUniformLocation(name: String): Int = GLES20.glGetUniformLocation(programId, name)
    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("ShaderProgram", "Compile failed type=$type: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = uMVPMatrix * a_Position;
                v_TexCoord = (uTexMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;
            }
        """

        // ManyCam-like OES shader: filters + chroma key + blur + vignette + beauty
        const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uAlpha;
            uniform int uFilter; // 0 none, 1 gray, 2 sepia, 3 invert, 4 blur, 5 chroma, 6 vignette, 7 pixelate, 8 edge, 9 beauty, 10 bgBlur
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uBlurRadius;
            uniform float uVignette;
            uniform vec3 uKeyColor; // chroma key color 0..1
            uniform float uChromaThreshold;
            uniform float uChromaSlope;
            uniform vec2 uTexSize; // for blur/pixelate
            // Simple 9-tap blur
            vec4 blur(samplerExternalOES tex, vec2 uv, float radius) {
                vec4 sum = vec4(0.0);
                float r = radius / 100.0;
                sum += texture2D(tex, uv + vec2(-r, -r)) * 0.0625;
                sum += texture2D(tex, uv + vec2(0.0, -r)) * 0.125;
                sum += texture2D(tex, uv + vec2(r, -r)) * 0.0625;
                sum += texture2D(tex, uv + vec2(-r, 0.0)) * 0.125;
                sum += texture2D(tex, uv) * 0.25;
                sum += texture2D(tex, uv + vec2(r, 0.0)) * 0.125;
                sum += texture2D(tex, uv + vec2(-r, r)) * 0.0625;
                sum += texture2D(tex, uv + vec2(0.0, r)) * 0.125;
                sum += texture2D(tex, uv + vec2(r, r)) * 0.0625;
                return sum;
            }
            float luminance(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            void main() {
                vec4 color;
                if (uFilter == 4 || uFilter == 10) {
                    color = blur(sTexture, v_TexCoord, uBlurRadius);
                } else {
                    color = texture2D(sTexture, v_TexCoord);
                }
                // Filters
                if (uFilter == 1) {
                    float gray = luminance(color.rgb);
                    color = vec4(gray, gray, gray, color.a);
                } else if (uFilter == 2) {
                    float r = dot(color.rgb, vec3(0.393, 0.769, 0.189));
                    float g = dot(color.rgb, vec3(0.349, 0.686, 0.168));
                    float b = dot(color.rgb, vec3(0.272, 0.534, 0.131));
                    color = vec4(r,g,b,color.a);
                } else if (uFilter == 3) {
                    color = vec4(1.0 - color.rgb, color.a);
                } else if (uFilter == 5) {
                    // Chroma key: distance from key color
                    float dist = distance(color.rgb, uKeyColor);
                    float edge = smoothstep(uChromaThreshold, uChromaThreshold + uChromaSlope, dist);
                    color.a *= edge;
                    if (color.a < 0.1) discard;
                } else if (uFilter == 7) {
                    float pixelSize = 20.0;
                    vec2 uv = floor(v_TexCoord * pixelSize) / pixelSize;
                    color = texture2D(sTexture, uv);
                } else if (uFilter == 8) {
                    vec2 texel = 1.0 / uTexSize;
                    float tl = luminance(texture2D(sTexture, v_TexCoord + vec2(-texel.x, -texel.y)).rgb);
                    float t = luminance(texture2D(sTexture, v_TexCoord + vec2(0.0, -texel.y)).rgb);
                    float tr = luminance(texture2D(sTexture, v_TexCoord + vec2(texel.x, -texel.y)).rgb);
                    float l = luminance(texture2D(sTexture, v_TexCoord + vec2(-texel.x, 0.0)).rgb);
                    float r = luminance(texture2D(sTexture, v_TexCoord + vec2(texel.x, 0.0)).rgb);
                    float bl = luminance(texture2D(sTexture, v_TexCoord + vec2(-texel.x, texel.y)).rgb);
                    float b = luminance(texture2D(sTexture, v_TexCoord + vec2(0.0, texel.y)).rgb);
                    float br = luminance(texture2D(sTexture, v_TexCoord + vec2(texel.x, texel.y)).rgb);
                    float sobelX = -tl -2.0*l -bl + tr +2.0*r + br;
                    float sobelY = -tl -2.0*t -tr + bl +2.0*b + br;
                    float edge = sqrt(sobelX*sobelX + sobelY*sobelY);
                    color = vec4(vec3(edge), color.a);
                } else if (uFilter == 9) {
                    // Beauty: simple bilateral-like blur + blend
                    vec4 blurred = blur(sTexture, v_TexCoord, 10.0);
                    color = mix(color, blurred, 0.5);
                }
                // Vignette
                if (uVignette > 0.0) {
                    vec2 uv = v_TexCoord * 2.0 - 1.0;
                    float vign = 1.0 - dot(uv, uv) * uVignette * 0.3;
                    color.rgb *= vign;
                }
                // Brightness / Contrast / Saturation
                color.rgb += uBrightness;
                color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
                float gray = luminance(color.rgb);
                color.rgb = mix(vec3(gray), color.rgb, uSaturation);
                color.rgb *= 1.0; // keep
                color.a *= uAlpha;
                gl_FragColor = color;
            }
        """

        const val FRAGMENT_SHADER_2D = """
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform sampler2D sTexture;
            uniform float uAlpha;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uBlurRadius;
            uniform float uVignette;
            uniform vec2 uTexSize;
            uniform int uFilter;
            float luminance(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            vec4 blur2D(sampler2D tex, vec2 uv, float radius) {
                vec4 sum = vec4(0.0);
                float r = radius / 100.0;
                sum += texture2D(tex, uv + vec2(-r, -r)) * 0.0625;
                sum += texture2D(tex, uv + vec2(0.0, -r)) * 0.125;
                sum += texture2D(tex, uv + vec2(r, -r)) * 0.0625;
                sum += texture2D(tex, uv + vec2(-r, 0.0)) * 0.125;
                sum += texture2D(tex, uv) * 0.25;
                sum += texture2D(tex, uv + vec2(r, 0.0)) * 0.125;
                sum += texture2D(tex, uv + vec2(-r, r)) * 0.0625;
                sum += texture2D(tex, uv + vec2(0.0, r)) * 0.125;
                sum += texture2D(tex, uv + vec2(r, r)) * 0.0625;
                return sum;
            }
            void main() {
                vec4 color;
                if (uBlurRadius > 0.5) {
                    color = blur2D(sTexture, v_TexCoord, uBlurRadius);
                } else {
                    color = texture2D(sTexture, v_TexCoord);
                }
                if (uFilter == 1) {
                    float gray = luminance(color.rgb);
                    color = vec4(gray, gray, gray, color.a);
                } else if (uFilter == 3) {
                    color = vec4(1.0 - color.rgb, color.a);
                }
                if (uVignette > 0.0) {
                    vec2 uv = v_TexCoord * 2.0 - 1.0;
                    float vign = 1.0 - dot(uv, uv) * uVignette * 0.3;
                    color.rgb *= vign;
                }
                color.rgb += uBrightness;
                color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
                float gray = luminance(color.rgb);
                color.rgb = mix(vec3(gray), color.rgb, uSaturation);
                color.a *= uAlpha;
                gl_FragColor = color;
            }
        """

        const val FRAGMENT_SHADER_COLOR = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uAlpha;
            void main() {
                gl_FragColor = vec4(uColor.rgb, uColor.a * uAlpha);
            }
        """
    }
}
