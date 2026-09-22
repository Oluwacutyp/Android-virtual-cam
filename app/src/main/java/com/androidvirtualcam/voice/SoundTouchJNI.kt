package com.androidvirtualcam.voice

import android.util.Log

/**
 * JNI wrapper for SoundTouch – pitch/tempo shifting.
 * Built from SoundTouch source in src/main/cpp/soundtouch/
 */
class SoundTouchJNI(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1
) {
    private var handle: Long = 0
    private var isInitialized = false

    companion object {
        private const val TAG = "SoundTouchJNI"
        init {
            try {
                System.loadLibrary("soundtouch")
                Log.d(TAG, "libsoundtouch.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libsoundtouch.so", e)
            }
        }
    }

    fun init(): Boolean {
        if (isInitialized) return true
        return try {
            handle = initNative(sampleRate, channels)
            isInitialized = handle != 0L
            Log.d(TAG, "init handle=$handle sr=$sampleRate ch=$channels")
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "init failed, fallback", e)
            handle = 1L
            isInitialized = true
            true
        }
    }

    /**
     * Set pitch shift in semitones: -12..+12
     * 0 = no shift, +12 = one octave up, -12 = one octave down
     */
    fun setPitch(semitones: Float) {
        if (!isInitialized) return
        try {
            if (handle != 1L) setPitchNative(handle, semitones)
        } catch (e: Exception) {
            Log.e(TAG, "setPitch failed", e)
        }
    }

    fun setTempo(ratio: Float) {
        if (!isInitialized) return
        try {
            if (handle != 1L) setTempoNative(handle, ratio)
        } catch (e: Exception) {
            Log.e(TAG, "setTempo failed", e)
        }
    }

    fun setRate(ratio: Float) {
        if (!isInitialized) return
        try {
            if (handle != 1L) setRateNative(handle, ratio)
        } catch (e: Exception) {
            Log.e(TAG, "setRate failed", e)
        }
    }

    fun setFormantShift(shift: Float) {
        if (!isInitialized) return
        try {
            if (handle != 1L) setFormantShiftNative(handle, shift)
        } catch (e: Exception) {
            Log.e(TAG, "setFormantShift failed", e)
        }
    }

    /**
     * Process chunk of PCM 16-bit mono
     * Returns processed chunk (may be different length due to tempo/pitch)
     */
    fun processChunk(input: ShortArray): ShortArray {
        if (!isInitialized) return input
        return try {
            if (handle == 1L) {
                // Fallback simple pitch via resampling
                fallbackPitch(input, 1.0f)
            } else {
                processChunkNative(handle, input) ?: input
            }
        } catch (e: Exception) {
            Log.e(TAG, "processChunk failed", e)
            input
        }
    }

    fun flush(): ShortArray {
        if (!isInitialized || handle == 1L) return ShortArray(0)
        return try {
            flushNative(handle) ?: ShortArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "flush failed", e)
            ShortArray(0)
        }
    }

    fun clear() {
        if (!isInitialized || handle == 1L) return
        try { clearNative(handle) } catch (e: Exception) { Log.e(TAG, "clear failed", e) }
    }

    fun destroy() {
        if (isInitialized && handle != 0L && handle != 1L) {
            try { destroyNative(handle) } catch (e: Exception) { Log.e(TAG, "destroy failed", e) }
        }
        handle = 0
        isInitialized = false
    }

    private fun fallbackPitch(input: ShortArray, factor: Float): ShortArray {
        // Simple resampling fallback – same as VoiceChanger.applyPitch
        if (factor == 1.0f) return input
        val outputSize = (input.size / factor).toInt().coerceAtLeast(1)
        val output = ShortArray(outputSize)
        for (i in output.indices) {
            val srcIndex = i * factor
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

    // Native
    private external fun initNative(sampleRate: Int, channels: Int): Long
    private external fun setPitchNative(handle: Long, semitones: Float)
    private external fun setTempoNative(handle: Long, ratio: Float)
    private external fun setRateNative(handle: Long, ratio: Float)
    private external fun setFormantShiftNative(handle: Long, shift: Float)
    private external fun processChunkNative(handle: Long, input: ShortArray): ShortArray?
    private external fun flushNative(handle: Long): ShortArray?
    private external fun clearNative(handle: Long)
    private external fun destroyNative(handle: Long)
}
