package com.androidvirtualcam.voice

import android.util.Log
import kotlin.math.*

/**
 * Free, customizable voice changer – ManyCam-like audio effects for Android.
 *
 * Now delegates to native DSP pipeline (VoiceChangerEngine) when available,
 * falls back to pure Kotlin for Xposed path without service.
 *
 * This is used by Xposed AudioHooks to inject changed voice into any app.
 */
object VoiceChanger {
    private const val TAG = "VoiceChanger"

    // Cache for echo (fallback)
    private var echoBuffer = ShortArray(44100) // 1 sec at 44.1kHz
    private var echoPos = 0

    /**
     * Process PCM byte array (16-bit little endian)
     * Tries to use VoiceChangerEngine's circular buffer first, fallback to direct processing
     */
    fun process(
        data: ByteArray,
        offset: Int,
        size: Int,
        pitch: Float = 1f,
        effect: String = "none",
        sampleRate: Int = 44100
    ): ByteArray {
        // Try to get processed audio from engine's circular buffer (native pipeline)
        try {
            val engineBuffer = VoiceChangerEngine.getProcessedBytesFromBuffer(size)
            if (engineBuffer != null && engineBuffer.size >= size) {
                return engineBuffer.copyOf(size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get from circular buffer, fallback", e)
        }

        // Fallback: convert byte[] (16-bit PCM) to short[] and process
        val shortSize = size / 2
        val shortData = ShortArray(shortSize)
        for (i in 0 until shortSize) {
            val low = data[offset + i * 2].toInt() and 0xFF
            val high = data[offset + i * 2 + 1].toInt()
            shortData[i] = ((high shl 8) or low).toShort()
        }

        val processedShort = processShort(shortData, 0, shortSize, pitch, effect, sampleRate)

        // Convert back to byte[]
        val out = ByteArray(processedShort.size * 2)
        for (i in processedShort.indices) {
            out[i * 2] = (processedShort[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((processedShort[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun processShort(
        data: ShortArray,
        offset: Int,
        size: Int,
        pitch: Float = 1f,
        effect: String = "none",
        sampleRate: Int = 44100
    ): ShortArray {
        // Try circular buffer first
        try {
            val engineBuffer = VoiceChangerEngine.getProcessedAudioFromBuffer(size)
            if (engineBuffer != null && engineBuffer.isNotEmpty()) {
                // If buffer smaller than requested, pad or repeat
                return if (engineBuffer.size < size) {
                    val stretched = ShortArray(size)
                    for (i in stretched.indices) {
                        stretched[i] = engineBuffer[i % engineBuffer.size]
                    }
                    stretched
                } else {
                    engineBuffer.copyOf(size)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Circular buffer read failed, fallback", e)
        }

        // Try direct engine processing if available
        try {
            val engine = VoiceChangerEngine.getInstance()
            if (engine != null) {
                val input = data.copyOfRange(offset, offset + size)
                return engine.processDirect(input)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct engine processing failed, fallback to Kotlin", e)
        }

        // Fallback pure Kotlin
        val input = data.copyOfRange(offset, offset + size)
        return when (effect.lowercase()) {
            "none", "normal" -> applyPitch(input, pitch)
            "chipmunk" -> applyPitch(input, 1.8f)
            "helium" -> applyPitch(input, 2.2f)
            "deep", "darth", "vader", "darth_vader", "darth vader" -> applyPitch(input, 0.6f)
            "giant" -> applyPitch(input, 0.5f)
            "robot" -> applyRobot(input)
            "echo" -> applyEcho(input, sampleRate)
            "cave" -> applyCave(input, sampleRate)
            "radio" -> applyRadio(input)
            "custom" -> applyPitch(input, pitch)
            else -> {
                if (effect.startsWith("pitch:")) {
                    val p = effect.substringAfter("pitch:").toFloatOrNull() ?: pitch
                    applyPitch(input, p)
                } else {
                    // Try to map new preset names to old behavior
                    when (effect.lowercase()) {
                        "chipmunk" -> applyPitch(input, 1.8f)
                        "helium" -> applyPitch(input, 2.2f)
                        "deep" -> applyPitch(input, 0.6f)
                        "darth vader" -> applyPitch(input, 0.6f)
                        "giant" -> applyPitch(input, 0.5f)
                        "robot" -> applyRobot(input)
                        "echo" -> applyEcho(input, sampleRate)
                        "cave" -> applyCave(input, sampleRate)
                        "radio" -> applyRadio(input)
                        else -> applyPitch(input, pitch)
                    }
                }
            }
        }
    }

    fun applyPitch(input: ShortArray, pitch: Float): ShortArray {
        if (pitch == 1f) return input
        if (pitch <= 0.1f || pitch > 4f) return input
        val outputSize = (input.size / pitch).toInt().coerceAtLeast(1)
        val output = ShortArray(outputSize)
        for (i in output.indices) {
            val srcIndex = i * pitch
            val indexInt = srcIndex.toInt()
            val frac = srcIndex - indexInt
            if (indexInt + 1 < input.size) {
                val s1 = input[indexInt].toFloat()
                val s2 = input[indexInt + 1].toFloat()
                output[i] = (s1 * (1 - frac) + s2 * frac).toInt().toShort()
            } else if (indexInt < input.size) {
                output[i] = input[indexInt]
            }
        }
        return if (output.size < input.size) {
            val stretched = ShortArray(input.size)
            for (i in stretched.indices) stretched[i] = output[i % output.size]
            stretched
        } else {
            output.copyOf(input.size)
        }
    }

    fun applyRobot(input: ShortArray): ShortArray {
        val output = ShortArray(input.size)
        val modFreq = 30f
        val sampleRate = 44100f
        for (i in input.indices) {
            val mod = sin(2 * PI * modFreq * i / sampleRate).toFloat()
            val carrier = if (mod > 0) 1f else 0.3f
            val crushed = (input[i] / 1000) * 1000
            output[i] = (crushed * carrier).toInt().toShort()
        }
        return output
    }

    fun applyEcho(input: ShortArray, sampleRate: Int): ShortArray {
        val output = ShortArray(input.size)
        val delaySamples = (sampleRate * 0.3f).toInt()
        val decay = 0.5f
        for (i in input.indices) {
            val echoIndex = (echoPos + i) % echoBuffer.size
            val delayed = echoBuffer[echoIndex].toFloat() * decay
            val current = input[i].toFloat()
            val mixed = (current + delayed).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            output[i] = mixed.toInt().toShort()
            echoBuffer[echoIndex] = input[i]
        }
        echoPos = (echoPos + input.size) % echoBuffer.size
        return output
    }

    fun applyCave(input: ShortArray, sampleRate: Int): ShortArray {
        // Heavy reverb simulation via multiple echoes
        val output = ShortArray(input.size)
        val delays = listOf((sampleRate * 0.08f).toInt(), (sampleRate * 0.15f).toInt(), (sampleRate * 0.25f).toInt())
        val decays = listOf(0.6f, 0.4f, 0.25f)
        for (i in input.indices) {
            var mixed = input[i].toFloat()
            for (j in delays.indices) {
                val idx = (echoPos + i - delays[j] + echoBuffer.size) % echoBuffer.size
                mixed += echoBuffer[idx] * decays[j]
            }
            output[i] = mixed.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
            echoBuffer[(echoPos + i) % echoBuffer.size] = input[i]
        }
        echoPos = (echoPos + input.size) % echoBuffer.size
        return output
    }

    fun applyRadio(input: ShortArray): ShortArray {
        // Simple bandpass + overdrive simulation
        val output = ShortArray(input.size)
        // Very basic high-pass (remove low) and low-pass (remove high) + overdrive
        var prev = 0f
        for (i in input.indices) {
            var s = input[i].toFloat()
            // High-pass at 300Hz approximation (simple diff)
            val hp = s - prev
            prev = s * 0.995f
            // Low-pass at 3400Hz approximation (simple smoothing)
            var lp = hp
            if (i > 0) lp = (hp + output[i-1]) * 0.5f
            // Overdrive
            var over = lp * 2.0f
            over = over / (1.0f + kotlin.math.abs(over) / 10000f)
            output[i] = over.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
        return output
    }

    fun applyDeepVoice(input: ShortArray): ShortArray = applyPitch(input, 0.7f)
    fun applyChipmunk(input: ShortArray): ShortArray = applyPitch(input, 1.7f)
}

// Top-level typealias – VoiceChanger VoiceProfile typealias to VoiceChangerEngine.VoiceProfile
// Must be at top level, outside any class/object (nested typealiases not supported)
typealias VoiceProfile = VoiceChangerEngine.VoiceProfile
