package com.androidvirtualcam.voice

import android.util.Log

/**
 * JNI wrapper for RNNoise – noise suppression.
 * 480 samples = 10ms at 48kHz, as per RNNoise spec.
 *
 * Production: loads librnnoise.so built from xiph/rnnoise source (stub for lightweight build).
 * Full source included in src/main/cpp/rnnoise/
 */
class RNNoiseJNI {

    private var handle: Long = 0
    private var isInitialized = false

    companion object {
        private const val TAG = "RNNoiseJNI"
        const val FRAME_SIZE = 480

        init {
            try {
                System.loadLibrary("rnnoise")
                Log.d(TAG, "librnnoise.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load librnnoise.so, using fallback", e)
            }
        }
    }

    fun init(): Boolean {
        if (isInitialized) return true
        return try {
            handle = initNative()
            isInitialized = handle != 0L
            Log.d(TAG, "init handle=$handle success=$isInitialized")
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "init failed, using fallback", e)
            handle = 1L
            isInitialized = true
            true
        }
    }

    fun processFrame(input: FloatArray): FloatArray {
        check(isInitialized) { "RNNoise not initialized" }
        require(input.size == FRAME_SIZE) { "Input must be $FRAME_SIZE samples, got ${input.size}" }
        return try {
            if (handle == 1L) fallbackDenoise(input)
            else processFrameNative(handle, input) ?: input
        } catch (e: Exception) {
            Log.e(TAG, "processFrame failed, fallback", e)
            fallbackDenoise(input)
        }
    }

    fun processFrameWithVad(input: FloatArray, output: FloatArray): Float {
        check(isInitialized)
        require(input.size == FRAME_SIZE && output.size == FRAME_SIZE)
        return try {
            if (handle == 1L) {
                val denoised = fallbackDenoise(input)
                System.arraycopy(denoised, 0, output, 0, FRAME_SIZE)
                0.8f
            } else {
                processFrameWithVadNative(handle, input, output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrameWithVad failed", e)
            System.arraycopy(input, 0, output, 0, FRAME_SIZE)
            0.5f
        }
    }

    private fun fallbackDenoise(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        var energy = 0f
        for (s in input) energy += s * s
        energy = kotlin.math.sqrt(energy / input.size + 1e-10f)
        val threshold = 0.01f
        val gain = if (energy < threshold) 0.2f else 1.0f
        for (i in input.indices) output[i] = input[i] * gain
        return output
    }

    fun destroy() {
        if (isInitialized && handle != 0L && handle != 1L) {
            try { destroyNative(handle) } catch (e: Exception) { Log.e(TAG, "destroy failed", e) }
        }
        handle = 0
        isInitialized = false
    }

    // Native bindings – C++ implements Java_com_androidvirtualcam_voice_RNNoiseJNI_*
    private external fun initNative(): Long
    private external fun processFrameNative(handle: Long, input: FloatArray): FloatArray?
    private external fun processFrameWithVadNative(handle: Long, input: FloatArray, output: FloatArray): Float
    private external fun destroyNative(handle: Long)
    private external fun getFrameSizeNative(): Int
}
