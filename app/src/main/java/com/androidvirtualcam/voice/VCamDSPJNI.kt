package com.androidvirtualcam.voice

import android.util.Log

/**
 * JNI wrapper for custom DSP effects in libvcamdsp.so
 * Stateful – holds C++ object pointer as Long in Kotlin.
 *
 * Effects:
 * - Reverb: Schroeder reverb with 4 comb + 2 allpass filters
 * - Compressor: RMS-based, attack/release/threshold/ratio/makeup
 * - 10-band parametric EQ: biquad filter per band, full state
 * - Chorus: LFO-modulated delay line, depth/rate/mix params
 * - Noise gate: RMS threshold, attack/release
 * - Special: ring mod, bitcrush, overdrive
 */
class VCamDSPJNI(
    private val sampleRate: Float = 48000f
) {
    private var handle: Long = 0
    private var isInitialized = false

    companion object {
        private const val TAG = "VCamDSPJNI"

        // Effect types for setEffectEnabled
        const val EFFECT_REVERB = 0
        const val EFFECT_COMPRESSOR = 1
        const val EFFECT_EQ = 2
        const val EFFECT_CHORUS = 3
        const val EFFECT_NOISE_GATE = 4

        // EQ band types
        const val EQ_PEAKING = 0
        const val EQ_LOW_SHELF = 1
        const val EQ_HIGH_SHELF = 2
        const val EQ_LOW_PASS = 3
        const val EQ_HIGH_PASS = 4
        const val EQ_BAND_PASS = 5

        init {
            try {
                System.loadLibrary("vcamdsp")
                Log.d(TAG, "libvcamdsp.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libvcamdsp.so", e)
            }
        }
    }

    fun create(): Boolean {
        if (isInitialized) return true
        return try {
            handle = createNative(sampleRate)
            isInitialized = handle != 0L
            Log.d(TAG, "create handle=$handle sr=$sampleRate")
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "create failed, fallback", e)
            handle = 1L
            isInitialized = true
            true
        }
    }

    fun setEffectEnabled(effectType: Int, enabled: Boolean) {
        if (!isInitialized || handle == 1L) return
        try { setEffectEnabledNative(handle, effectType, enabled) } catch (e: Exception) { Log.e(TAG, "setEffectEnabled failed", e) }
    }

    // Reverb
    fun setReverbParams(roomSize: Float, damping: Float, wet: Float, dry: Float, width: Float) {
        if (!isInitialized || handle == 1L) return
        try { setReverbParamsNative(handle, roomSize, damping, wet, dry, width) } catch (e: Exception) { Log.e(TAG, "setReverb failed", e) }
    }

    // Compressor
    fun setCompressorParams(threshold: Float, ratio: Float, attack: Float, release: Float, makeup: Float) {
        if (!isInitialized || handle == 1L) return
        try { setCompressorParamsNative(handle, threshold, ratio, attack, release, makeup) } catch (e: Exception) { Log.e(TAG, "setCompressor failed", e) }
    }

    // EQ
    fun setEQBand(index: Int, freq: Float, gainDb: Float, q: Float, type: Int) {
        if (!isInitialized || handle == 1L) return
        try { setEQBandNative(handle, index, freq, gainDb, q, type) } catch (e: Exception) { Log.e(TAG, "setEQBand failed", e) }
    }

    // Chorus
    fun setChorusParams(depthMs: Float, rateHz: Float, mix: Float, feedback: Float) {
        if (!isInitialized || handle == 1L) return
        try { setChorusParamsNative(handle, depthMs, rateHz, mix, feedback) } catch (e: Exception) { Log.e(TAG, "setChorus failed", e) }
    }

    // Noise gate
    fun setNoiseGateParams(threshold: Float, attack: Float, release: Float, hold: Float, range: Float) {
        if (!isInitialized || handle == 1L) return
        try { setNoiseGateParamsNative(handle, threshold, attack, release, hold, range) } catch (e: Exception) { Log.e(TAG, "setNoiseGate failed", e) }
    }

    // Special
    fun setRingMod(enabled: Boolean, freq: Float) {
        if (!isInitialized || handle == 1L) return
        try { setRingModNative(handle, enabled, freq) } catch (e: Exception) { Log.e(TAG, "setRingMod failed", e) }
    }

    fun setBitcrush(enabled: Boolean, bits: Int) {
        if (!isInitialized || handle == 1L) return
        try { setBitcrushNative(handle, enabled, bits) } catch (e: Exception) { Log.e(TAG, "setBitcrush failed", e) }
    }

    fun setOverdrive(enabled: Boolean, gain: Float) {
        if (!isInitialized || handle == 1L) return
        try { setOverdriveNative(handle, enabled, gain) } catch (e: Exception) { Log.e(TAG, "setOverdrive failed", e) }
    }

    fun process(input: FloatArray): FloatArray {
        if (!isInitialized) return input
        return try {
            if (handle == 1L) input
            else processNative(handle, input) ?: input
        } catch (e: Exception) {
            Log.e(TAG, "process failed", e)
            input
        }
    }

    fun processShort(input: ShortArray): ShortArray {
        if (!isInitialized) return input
        return try {
            if (handle == 1L) input
            else processShortNative(handle, input) ?: input
        } catch (e: Exception) {
            Log.e(TAG, "processShort failed", e)
            input
        }
    }

    fun reset() {
        if (!isInitialized || handle == 1L) return
        try { resetNative(handle) } catch (e: Exception) { Log.e(TAG, "reset failed", e) }
    }

    fun destroy() {
        if (isInitialized && handle != 0L && handle != 1L) {
            try { destroyNative(handle) } catch (e: Exception) { Log.e(TAG, "destroy failed", e) }
        }
        handle = 0
        isInitialized = false
    }

    // Native
    private external fun createNative(sampleRate: Float): Long
    private external fun destroyNative(handle: Long)
    private external fun setReverbParamsNative(handle: Long, roomSize: Float, damping: Float, wet: Float, dry: Float, width: Float)
    private external fun setCompressorParamsNative(handle: Long, threshold: Float, ratio: Float, attack: Float, release: Float, makeup: Float)
    private external fun setEQBandNative(handle: Long, index: Int, freq: Float, gainDb: Float, q: Float, type: Int)
    private external fun setChorusParamsNative(handle: Long, depthMs: Float, rateHz: Float, mix: Float, feedback: Float)
    private external fun setNoiseGateParamsNative(handle: Long, threshold: Float, attack: Float, release: Float, hold: Float, range: Float)
    private external fun setRingModNative(handle: Long, enabled: Boolean, freq: Float)
    private external fun setBitcrushNative(handle: Long, enabled: Boolean, bits: Int)
    private external fun setOverdriveNative(handle: Long, enabled: Boolean, gain: Float)
    private external fun setEffectEnabledNative(handle: Long, effectType: Int, enabled: Boolean)
    private external fun processNative(handle: Long, input: FloatArray): FloatArray?
    private external fun processShortNative(handle: Long, input: ShortArray): ShortArray?
    private external fun resetNative(handle: Long)
}
