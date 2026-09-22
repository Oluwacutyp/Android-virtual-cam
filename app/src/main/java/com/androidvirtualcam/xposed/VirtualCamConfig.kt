package com.androidvirtualcam.xposed

import android.os.Environment
import java.io.File

/**
 * Production-grade config for virtual camera – stored in file for settings, but frame transport is SharedMemory ring buffer (not files).
 *
 * Frame transport: VCamFrameBus via SharedMemory + Unix domain socket (abstract "vcam_frame_bus" + file fallback)
 * This config file only holds settings (enabled, voice changer, etc.), not frame data.
 *
 * Path: /data/data/com.androidvirtualcam/files/vcam_config.json
 * Fallback: /sdcard/Android/data/com.androidvirtualcam/files/vcam_config.json
 */
data class VirtualCamConfig(
    val isVirtualCamEnabled: Boolean = true,
    val hookAllApps: Boolean = true,
    val hookedApps: Set<String> = emptySet(),
    val videoSource: VideoSource = VideoSource.COMPOSED,
    val frameBusSocketName: String = "vcam_frame_bus",
    val frameBusSocketPath: String = "/data/data/com.androidvirtualcam/files/vcam_bus.sock",
    val resolutionWidth: Int = 1280,
    val resolutionHeight: Int = 720,
    val fps: Int = 30,
    val enableChromaKey: Boolean = false,
    val loopVideo: Boolean = true,
    val voiceChangerEnabled: Boolean = false,
    val voiceEffect: String = "none",
    val voicePitch: Float = 1f,
    val voiceSpeed: Float = 1f
) {
    enum class VideoSource {
        COMPOSED,
        VIDEO_FILE,
        IMAGE_FILE,
        CAMERA_PASSTHROUGH
    }

    fun isHookEnabledFor(packageName: String): Boolean {
        if (!isVirtualCamEnabled) return false
        if (hookAllApps) return true
        return hookedApps.contains(packageName)
    }

    companion object {
        private const val CONFIG_FILE_NAME = "vcam_config.json"
        private const val PACKAGE = "com.androidvirtualcam"

        fun getContext(): android.content.Context? {
            return try {
                // Try to get context via ActivityThread
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
                val app = currentApplicationMethod.invoke(null) as? android.content.Context
                app
            } catch (e: Exception) {
                try {
                    // Fallback via AppGlobals
                    val appGlobalsClass = Class.forName("android.app.AppGlobals")
                    val app = appGlobalsClass.getMethod("getInitialApplication").invoke(null) as? android.content.Context
                    app
                } catch (_: Exception) {
                    null
                }
            }
        }

        fun load(): VirtualCamConfig {
            return try {
                val candidates = listOf(
                    File("/data/data/$PACKAGE/files/$CONFIG_FILE_NAME"),
                    File("/data/user/0/$PACKAGE/files/$CONFIG_FILE_NAME"),
                    File(Environment.getExternalStorageDirectory(), "Android/data/$PACKAGE/files/$CONFIG_FILE_NAME"),
                    File("/sdcard/Android/data/$PACKAGE/files/$CONFIG_FILE_NAME")
                )
                for (file in candidates) {
                    if (file.exists()) {
                        val json = file.readText()
                        return parseJson(json)
                    }
                }
                VirtualCamConfig()
            } catch (e: Exception) {
                VirtualCamConfig()
            }
        }

        private fun parseJson(json: String): VirtualCamConfig {
            return try {
                val enabled = json.contains("\"isVirtualCamEnabled\":true")
                val hookAll = !json.contains("\"hookAllApps\":false")
                val voiceEnabled = json.contains("\"voiceChangerEnabled\":true")
                val pitch = Regex("\"voicePitch\":([0-9.]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
                val effect = Regex("\"voiceEffect\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "none"
                val socketName = Regex("\"frameBusSocketName\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "vcam_frame_bus"
                val socketPath = Regex("\"frameBusSocketPath\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "/data/data/com.androidvirtualcam/files/vcam_bus.sock"
                val width = Regex("\"resolutionWidth\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 1280
                val height = Regex("\"resolutionHeight\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 720

                VirtualCamConfig(
                    isVirtualCamEnabled = enabled,
                    hookAllApps = hookAll,
                    voiceChangerEnabled = voiceEnabled,
                    voicePitch = pitch,
                    voiceEffect = effect,
                    frameBusSocketName = socketName,
                    frameBusSocketPath = socketPath,
                    resolutionWidth = width,
                    resolutionHeight = height
                )
            } catch (e: Exception) {
                VirtualCamConfig()
            }
        }
    }
}
