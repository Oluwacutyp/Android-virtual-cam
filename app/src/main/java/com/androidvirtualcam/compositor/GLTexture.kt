package com.androidvirtualcam.compositor

import android.opengl.GLES11Ext
import android.opengl.GLES20

/**
 * Production GL texture wrapper – tracks id, target, size, format, and lifecycle.
 * Supports both TEXTURE_2D and TEXTURE_EXTERNAL_OES.
 */
class GLTexture(
    val textureId: Int,
    val target: Int = GLES20.GL_TEXTURE_2D,
    val width: Int = 0,
    val height: Int = 0,
    val format: Int = GLES20.GL_RGBA,
    val isOes: Boolean = target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
    val isOwned: Boolean = true
) : AutoCloseable {

    @Volatile private var released = false

    fun bind(unit: Int = 0) {
        check(!released) { "Texture already released" }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(target, textureId)
    }

    fun unbind() {
        GLES20.glBindTexture(target, 0)
    }

    override fun close() {
        release()
    }

    fun release() {
        if (!released && isOwned && textureId != 0) {
            try {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            } catch (e: Exception) {
                // Ignore if GL context not current
            }
            released = true
        }
    }

    companion object {
        fun create2D(width: Int, height: Int, format: Int = GLES20.GL_RGBA, useMipmap: Boolean = false): GLTexture {
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val texId = texIds[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (useMipmap) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, format,
                width, height, 0,
                format, GLES20.GL_UNSIGNED_BYTE, null
            )

            if (useMipmap) {
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            return GLTexture(texId, GLES20.GL_TEXTURE_2D, width, height, format, isOes = false, isOwned = true)
        }

        fun createOes(): GLTexture {
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val texId = texIds[0]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            return GLTexture(texId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, 0, 0, isOes = true, isOwned = true)
        }

        fun wrapExisting(textureId: Int, target: Int, width: Int, height: Int, owned: Boolean = false): GLTexture {
            return GLTexture(textureId, target, width, height, GLES20.GL_RGBA, target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES, owned)
        }
    }
}

/**
 * FBO wrapper for offscreen rendering – used by BlurNode, LUTNode, etc.
 */
class GLFramebuffer(
    val fboId: Int,
    val texture: GLTexture,
    val width: Int,
    val height: Int
) : AutoCloseable {

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)
    }

    fun unbind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    override fun close() {
        try {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        } catch (_: Exception) {}
        texture.release()
    }

    companion object {
        fun create(width: Int, height: Int, format: Int = GLES20.GL_RGBA, useMipmap: Boolean = false): GLFramebuffer {
            val texture = GLTexture.create2D(width, height, format, useMipmap)

            val fboIds = IntArray(1)
            GLES20.glGenFramebuffers(1, fboIds, 0)
            val fboId = fboIds[0]

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                texture.textureId,
                0
            )

            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glDeleteFramebuffers(1, fboIds, 0)
                texture.release()
                throw RuntimeException("Framebuffer incomplete: $status")
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            return GLFramebuffer(fboId, texture, width, height)
        }
    }
}
