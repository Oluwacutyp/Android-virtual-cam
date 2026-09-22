package com.androidvirtualcam.xposed

import android.media.AudioRecord
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.androidvirtualcam.voice.VoiceChanger
import com.androidvirtualcam.voice.VoiceChangerEngine
import java.nio.ByteBuffer

/**
 * Voice changer hooks – resilient, hooks ALL AudioRecord.read() overloads,
 * pulling audio from VoiceChangerEngine's circular output buffer (native DSP pipeline).
 *
 * Pipeline:
 * AudioRecord 48kHz mono 480 frames → RNNoise → SoundTouch → VCamDSP → circular buffer
 *
 * Hooks:
 * - read(byte[], int, int)
 * - read(byte[], int, int, int) – with readMode (API 23+)
 * - read(short[], int, int)
 * - read(short[], int, int, int)
 * - read(float[], int, int, int) – API 23+
 * - read(ByteBuffer, int)
 * - read(ByteBuffer, int, int) – with readMode
 * - Any other read variant found via DynamicHookScanner
 *
 * Every hook:
 * - Gets latest processed audio from circular buffer
 * - Handles null/empty gracefully – pass through real audio silently
 * - Never throws into target app
 */
object AudioHooks {
    private const val TAG = "VCamAudioHooks"

    fun hookAudioRecord(lpparam: XC_LoadPackage.LoadPackageParam, config: VirtualCamConfig) {
        val packageName = lpparam.packageName
        val versionCode = getVersionCode(lpparam)

        // Use DynamicHookScanner to find AudioRecord targets as per spec
        try {
            val scanner = DynamicHookScanner(lpparam.classLoader)
            val target = scanner.scanForAudioRecord(lpparam.classLoader, packageName, versionCode)
            if (target != null) {
                Log.i(TAG, "DynamicHookScanner found AudioRecord target: ${target.className}#${target.methodName}")
                val isValid = try { scanner.validateHook(target) } catch (e: Exception) { false }
                Log.i(TAG, "AudioRecord hook validation for ${target.className}: $isValid")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic scan for AudioRecord failed: ${e.message}")
        }

        // Hook all known overloads via direct hooking + dynamic discovery

        // Collect all read methods from AudioRecord class
        try {
            val audioRecordClass = XposedHelpers.findClass("android.media.AudioRecord", lpparam.classLoader)
            val readMethods = audioRecordClass.declaredMethods.filter { it.name == "read" }

            Log.i(TAG, "Found ${readMethods.size} AudioRecord.read overloads in $packageName: ${readMethods.map { it.parameterTypes.joinToString(",") { pt -> pt.simpleName } }}")

            for (method in readMethods) {
                try {
                    val start = System.currentTimeMillis()
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val cfg = VirtualCamConfig.load()
                                if (!cfg.voiceChangerEnabled) return

                                val readResult = param.result as? Int ?: return
                                if (readResult <= 0) return

                                // Handle different overloads based on first param type – null check fix
                                val firstArg = param.args.getOrNull(0) ?: return
                                if (firstArg == null) return
                                when (firstArg) {
                                    is ByteArray -> {
                                        val offset = param.args.getOrNull(1) as? Int ?: 0
                                        val size = param.args.getOrNull(2) as? Int ?: readResult
                                        // Check if 4th arg is readMode – ignore
                                        try {
                                            val buffered = VoiceChangerEngine.getProcessedBytesFromBuffer(readResult)
                                            if (buffered != null && buffered.size >= readResult) {
                                                System.arraycopy(buffered, 0, firstArg, offset, readResult)
                                                Log.d(TAG, "AudioRecord.read(byte[]) from circular buffer, size=$readResult")
                                            } else {
                                                val processed = VoiceChanger.process(firstArg, offset, readResult, pitch = cfg.voicePitch, effect = cfg.voiceEffect)
                                                System.arraycopy(processed, 0, firstArg, offset, processed.size.coerceAtMost(size))
                                            }
                                        } catch (e: Throwable) {
                                            Log.w(TAG, "byte[] processing failed: ${e.message}")
                                        }
                                    }
                                    is ShortArray -> {
                                        val offset = param.args.getOrNull(1) as? Int ?: 0
                                        val size = param.args.getOrNull(2) as? Int ?: readResult
                                        try {
                                            val buffered = VoiceChangerEngine.getProcessedAudioFromBuffer(readResult)
                                            if (buffered != null && buffered.size >= readResult) {
                                                System.arraycopy(buffered, 0, firstArg, offset, readResult)
                                            } else {
                                                val processed = VoiceChanger.processShort(firstArg, offset, readResult, pitch = cfg.voicePitch, effect = cfg.voiceEffect)
                                                System.arraycopy(processed, 0, firstArg, offset, processed.size.coerceAtMost(size))
                                            }
                                        } catch (e: Throwable) {
                                            Log.w(TAG, "short[] processing failed: ${e.message}")
                                        }
                                    }
                                    is FloatArray -> {
                                        val offset = param.args.getOrNull(1) as? Int ?: 0
                                        val size = param.args.getOrNull(2) as? Int ?: readResult
                                        try {
                                            // For float, we need to get short buffer and convert
                                            val shortBuffered = VoiceChangerEngine.getProcessedAudioFromBuffer(readResult)
                                            if (shortBuffered != null && shortBuffered.size >= readResult) {
                                                for (i in 0 until readResult) {
                                                    firstArg[offset + i] = shortBuffered[i] / 32768.0f
                                                }
                                            } else {
                                                // No conversion, pass through real
                                            }
                                        } catch (e: Throwable) {
                                            Log.w(TAG, "float[] processing failed: ${e.message}")
                                        }
                                    }
                                    is ByteBuffer -> {
                                        try {
                                            val pos = firstArg.position()
                                            val buffered = VoiceChangerEngine.getProcessedBytesFromBuffer(readResult)
                                            if (buffered != null && buffered.size >= readResult) {
                                                firstArg.position(pos)
                                                firstArg.put(buffered, 0, readResult.coerceAtMost(firstArg.remaining()))
                                            } else {
                                                // Fallback: read from buffer and process
                                                val array = ByteArray(readResult)
                                                firstArg.position(pos)
                                                firstArg.get(array)
                                                firstArg.position(pos)
                                                val processed = VoiceChanger.process(array, 0, readResult, pitch = cfg.voicePitch, effect = cfg.voiceEffect)
                                                firstArg.position(pos)
                                                firstArg.put(processed, 0, processed.size.coerceAtMost(firstArg.remaining()))
                                            }
                                        } catch (e: Throwable) {
                                            Log.w(TAG, "ByteBuffer processing failed: ${e.message}")
                                        }
                                    }
                                    else -> {
                                        // Unknown overload – log
                                        Log.d(TAG, "Unknown AudioRecord.read overload: ${method.parameterTypes.joinToString()}")
                                    }
                                }
                            } catch (e: Throwable) {
                                // Never throw into target app
                                Log.w(TAG, "AudioRecord.read hook error for ${method}: ${e.message}")
                            }
                        }
                    })
                    val elapsed = System.currentTimeMillis() - start
                    HookValidator.recordSuccess("AudioRecord.read(${method.parameterTypes.joinToString { it.simpleName }})", "android.media.AudioRecord", "read", elapsed, packageName, versionCode)
                } catch (e: Throwable) {
                    HookValidator.recordFailure("AudioRecord.read(${method.parameterTypes.joinToString { it.simpleName }})", "android.media.AudioRecord", "read", e.message ?: "hook failed", 0, packageName, versionCode)
                    Log.w(TAG, "Failed to hook AudioRecord.read ${method}: ${e.message}")
                }
            }

            Log.i(TAG, "AudioRecord hooks installed for $packageName – ${readMethods.size} overloads, native DSP pipeline (RNNoise+SoundTouch+VCamDSP)")

        } catch (e: Throwable) {
            Log.w(TAG, "AudioRecord hook failed for $packageName: ${e.message}")

            // Fallback: try individual known signatures if dynamic discovery failed
            hookFallbackSignatures(lpparam, packageName, versionCode)
        }
    }

    private fun hookFallbackSignatures(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String, versionCode: Long) {
        // Fallback to explicit signatures if class enumeration failed
        val signatures = listOf(
            arrayOf(ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            arrayOf(ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            arrayOf(ByteBuffer::class.java, Int::class.javaPrimitiveType)
        )

        for (sig in signatures) {
            try {
                XposedHelpers.findAndHookMethod("android.media.AudioRecord", lpparam.classLoader, "read", *sig, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val cfg = VirtualCamConfig.load()
                            if (!cfg.voiceChangerEnabled) return
                            val readResult = param.result as? Int ?: return
                            if (readResult <= 0) return

                            val firstArg = param.args.getOrNull(0) ?: return
                            if (firstArg == null) return
                            when (firstArg) {
                                is ByteArray -> {
                                    val offset = param.args.getOrNull(1) as? Int ?: 0
                                    val buffered = VoiceChangerEngine.getProcessedBytesFromBuffer(readResult)
                                    if (buffered != null) {
                                        System.arraycopy(buffered, 0, firstArg, offset, readResult.coerceAtMost(buffered.size))
                                    }
                                }
                                is ShortArray -> {
                                    val offset = param.args.getOrNull(1) as? Int ?: 0
                                    val buffered = VoiceChangerEngine.getProcessedAudioFromBuffer(readResult)
                                    if (buffered != null) {
                                        System.arraycopy(buffered, 0, firstArg, offset, readResult.coerceAtMost(buffered.size))
                                    }
                                }
                                is ByteBuffer -> {
                                    val pos = firstArg.position()
                                    val buffered = VoiceChangerEngine.getProcessedBytesFromBuffer(readResult)
                                    if (buffered != null) {
                                        firstArg.position(pos)
                                        firstArg.put(buffered, 0, readResult.coerceAtMost(firstArg.remaining()))
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Fallback hook error: ${e.message}")
                        }
                    }
                })
                HookValidator.recordSuccess("AudioRecord.read(fallback ${sig.joinToString { it?.simpleName ?: "?" }})", "android.media.AudioRecord", "read", 0, packageName, versionCode)
            } catch (e: Throwable) {
                HookValidator.recordFailure("AudioRecord.read(fallback)", "android.media.AudioRecord", "read", e.message ?: "not found", 0, packageName, versionCode)
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
}
