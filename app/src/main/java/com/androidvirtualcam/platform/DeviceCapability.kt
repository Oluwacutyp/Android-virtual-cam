package com.androidvirtualcam.platform

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.os.Build
import android.util.Log

object DeviceCapability {

    data class CapabilityReport(
        val hasCamera: Boolean,
        val hasFrontCamera: Boolean,
        val hasFlash: Boolean,
        val supportsOpenGLES3: Boolean,
        val supports1080p: Boolean,
        val sdkVersion: Int,
        val isEmulator: Boolean,
        val warnings: List<String>
    )

    fun check(context: Context): CapabilityReport {
        val pm = context.packageManager
        val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasFront = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        val hasFlash = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

        val supportsGLES3 = try {
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            version.contains("OpenGL ES 3")
        } catch (e: Exception) {
            // If context not ready, assume true for modern devices
            true
        }

        val isEmulator = (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("google_sdk") || Build.MODEL.contains("Emulator"))

        val warnings = mutableListOf<String>()
        if (!hasCamera) warnings.add("No camera detected")
        if (Build.VERSION.SDK_INT < 26) warnings.add("Android version below minSdk 26 - may have limited features")
        if (isEmulator) warnings.add("Running on emulator - camera may be simulated")
        if (!supportsGLES3) warnings.add("OpenGL ES 3.0 not detected - fallback to ES 2.0")

        return CapabilityReport(
            hasCamera = hasCamera,
            hasFrontCamera = hasFront,
            hasFlash = hasFlash,
            supportsOpenGLES3 = supportsGLES3,
            supports1080p = true, // assume, will check via CameraX StreamConfigurationMap at runtime
            sdkVersion = Build.VERSION.SDK_INT,
            isEmulator = isEmulator,
            warnings = warnings
        )
    }
}
