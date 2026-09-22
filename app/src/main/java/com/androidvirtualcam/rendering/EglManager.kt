package com.androidvirtualcam.rendering

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * Manages EGL display, context, and surfaces for shared rendering.
 * Allows one context to render to both display (GLSurfaceView) and encoder input surface.
 *
 * Based on Grafika's EglCore pattern: https://github.com/google/grafika
 */
class EglManager {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    var isInitialized = false
        private set

    fun initialize(sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e("EglManager", "Unable to get EGL14 display")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e("EglManager", "Unable to initialize EGL14")
            return false
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            Log.e("EglManager", "Unable to find suitable EGL config")
            return false
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedContext, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e("EglManager", "Failed to create EGL context")
            return false
        }
        isInitialized = true
        return true
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
        return eglSurface
    }

    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        return EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
    }

    fun makeCurrent(surface: EGLSurface) {
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
        checkEglError("eglMakeCurrent")
    }

    fun makeCurrent(draw: EGLSurface, read: EGLSurface) {
        EGL14.eglMakeCurrent(eglDisplay, draw, read, eglContext)
    }

    fun swapBuffers(surface: EGLSurface): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, surface)
    }

    fun setPresentationTime(surface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, nsecs)
    }

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, surface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        isInitialized = false
    }

    fun getEglContext(): EGLContext = eglContext

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            Log.e("EglManager", "$msg: EGL error 0x${Integer.toHexString(error)}")
        }
    }

    /**
     * Wrapper for Android-specific EGL extension for presentation time.
     */
    object EGLExt {
        fun eglPresentationTimeANDROID(display: EGLDisplay, surface: EGLSurface, nsecs: Long) {
            // Use reflection or direct if available via EGL14? For simplicity we try via EGL14 extension.
            try {
                val method = EGL14::class.java.getMethod("eglPresentationTimeANDROID", EGLDisplay::class.java, EGLSurface::class.java, Long::class.java)
                method.invoke(null, display, surface, nsecs)
            } catch (e: Exception) {
                // Not critical, ignore
            }
        }
    }
}
