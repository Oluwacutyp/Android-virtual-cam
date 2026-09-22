package com.androidvirtualcam.xposed

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed / Xposed entry point – ManyCam-like system-wide virtual camera for Android.
 *
 * Resilient hooks – no hardcoded class names that break on APK updates.
 * Uses DynamicHookScanner with caching per package + versionCode.
 *
 * Validates hooks via HookValidator and stores log file main app can read.
 */
class VirtualCamModule : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private val tag = "VCamModule"

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        Log.i(tag, "initZygote – VirtualCam free ManyCam alternative initializing – resilient hooks")
        XposedBridge.log("AndroidVirtualCam: initZygote – resilient hooks, dynamic scanning")
        try {
            // Clear previous validation results
            HookValidator.clear()
        } catch (e: Throwable) {
            Log.w(tag, "Failed to clear validator: ${e.message}")
        }
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return
        val packageName = lpparam.packageName
        if (packageName == "com.androidvirtualcam") {
            Log.d(tag, "Skipping self package")
            return
        }

        val config = VirtualCamConfig.load()
        if (!config.isHookEnabledFor(packageName) && !config.hookAllApps) {
            if (!isCameraApp(packageName)) return
        }

        val versionCode = getVersionCode(lpparam)
        Log.i(tag, "Hooking package: $packageName v$versionCode")
        XposedBridge.log("AndroidVirtualCam: hooking $packageName v$versionCode – resilient")

        HookValidator.logValidationStart(packageName, versionCode)
        HookValidator.clear()

        try {
            // Load cached hook targets if available
            try {
                val context = VirtualCamConfig.getContext()
                if (context != null) {
                    DynamicHookScanner.loadCacheFromFile(context)
                }
            } catch (e: Throwable) {
                Log.w(tag, "Failed to load hook cache: ${e.message}")
            }

            // Core camera hooks – complete as per Module 4 spec
            // Camera1: open(), setPreviewCallback(), setPreviewCallbackWithBuffer(), takePicture(), setPreviewTexture(), setPreviewDisplay()
            CameraHooks.hookCamera1(lpparam)

            // Camera2: CameraManager.openCamera(), CameraDeviceImpl.createCaptureSession(), CameraCaptureSessionImpl.setRepeatingRequest(), ImageReader.acquireLatestImage(), acquireNextImage(), SurfaceImage.getPlanes()
            CameraHooks.hookCamera2(lpparam)

            // ImageReader already hooked via hookCamera2, but also call explicitly for safety
            CameraHooks.hookImageReader(lpparam)

            // CameraX: ProcessCameraProvider.bindToLifecycle()
            CameraHooks.hookCameraX(lpparam)

            // WebRTC: dynamic scan via DynamicHookScanner
            CameraHooks.hookWebRtc(lpparam)

            // Audio: all AudioRecord.read() overloads via dynamic scan
            if (config.voiceChangerEnabled) {
                AudioHooks.hookAudioRecord(lpparam, config)
            }

            Log.i(tag, "Hooks installed for $packageName v$versionCode")
            XposedBridge.log("AndroidVirtualCam: hooks installed for $packageName")

            // Save cache
            try {
                val context = VirtualCamConfig.getContext()
                if (context != null) {
                    DynamicHookScanner.saveCacheToFile(context)
                }
            } catch (e: Throwable) {
                Log.w(tag, "Failed to save hook cache: ${e.message}")
            }

        } catch (e: Throwable) {
            Log.e(tag, "Failed to hook $packageName", e)
            XposedBridge.log("AndroidVirtualCam: hook failed for $packageName: ${e.message}")
            HookValidator.recordFailure("Module.handleLoadPackage", "VirtualCamModule", "handleLoadPackage", e.message ?: "unknown", 0, packageName, versionCode)
        } finally {
            // Write validation status file for main app to read
            try {
                val context = VirtualCamConfig.getContext()
                HookValidator.writeStatusFile(context, packageName, versionCode)
                HookValidator.logValidationEnd(packageName, versionCode)

                val results = HookValidator.getResults()
                val successCount = results.count { it.success }
                val failCount = results.count { !it.success }
                Log.i(tag, "Hook validation for $packageName: $successCount success, $failCount failed")
                XposedBridge.log("AndroidVirtualCam: validation $packageName – $successCount success, $failCount failed")
                for (result in results) {
                    XposedBridge.log("  ${if (result.success) "OK" else "FAIL"} ${result.hookName} ${result.className}#${result.methodName} ${result.error ?: ""}")
                }
            } catch (e: Throwable) {
                Log.w(tag, "Failed to write validation status: ${e.message}")
            }
        }
    }

    private fun getVersionCode(lpparam: XC_LoadPackage.LoadPackageParam): Long {
        return try {
            val context = VirtualCamConfig.getContext() ?: return 0L
            val pm = context.packageManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pm.getPackageInfo(lpparam.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(lpparam.packageName, 0).versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun isCameraApp(packageName: String): Boolean {
        return true // Hook all for ManyCam-like free version
    }
}
