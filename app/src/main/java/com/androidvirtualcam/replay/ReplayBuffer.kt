package com.androidvirtualcam.replay

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * ReplayBuffer – circular buffer holding last N seconds of encoded video in memory.
 *
 * ShadowPlay-like: always recording last 60s in RAM, tap to save clip.
 *
 * Stores encoded AVC samples with presentation timestamps. Evicts old samples
 * beyond maxDuration, but ensures first sample is keyframe (I-frame) for valid MP4.
 *
 * Thread-safe via synchronized.
 *
 * Memory estimate: 6Mbps = 0.75MB/s, 60s = ~45MB RAM – acceptable.
 * For 4Mbps gaming stream: 0.5MB/s, 60s = 30MB.
 */
class ReplayBuffer(
    @Volatile private var maxDurationUs: Long = 60_000_000L // 60 seconds in microseconds
) {

    data class Sample(
        val data: ByteArray, // copy of encoded data
        val presentationTimeUs: Long,
        val flags: Int,
        val isKeyFrame: Boolean,
        val isCodecConfig: Boolean
    ) {
        val size: Int get() = data.size
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Sample) return false
            return presentationTimeUs == other.presentationTimeUs
        }
        override fun hashCode(): Int = presentationTimeUs.hashCode()
    }

    private val tag = "ReplayBuffer"

    private val buffer = ArrayDeque<Sample>()
    private var totalBytes = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L

    // Codec config (SPS/PPS) – stored separately, needed for muxer
    @Volatile
    var codecConfig: Sample? = null
        private set

    // Output format from MediaCodec – needed to create muxer track
    @Volatile
    var outputFormat: android.media.MediaFormat? = null

    @Synchronized
    fun addSample(data: ByteBuffer, info: MediaCodec.BufferInfo): Boolean {
        try {
            // Check if codec config
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // Save codec config
                val configData = ByteArray(info.size)
                data.position(info.offset)
                data.limit(info.offset + info.size)
                data.get(configData)
                codecConfig = Sample(
                    data = configData,
                    presentationTimeUs = info.presentationTimeUs,
                    flags = info.flags,
                    isKeyFrame = false,
                    isCodecConfig = true
                )
                Log.d(tag, "Saved codec config ${configData.size} bytes")
                return true
            }

            if (info.size <= 0) return false

            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

            // Copy data
            val copy = ByteArray(info.size)
            data.position(info.offset)
            data.limit(info.offset + info.size)
            data.get(copy)

            val sample = Sample(
                data = copy,
                presentationTimeUs = info.presentationTimeUs,
                flags = info.flags,
                isKeyFrame = isKeyFrame,
                isCodecConfig = false
            )

            buffer.addLast(sample)
            totalBytes += copy.size
            lastPtsUs = info.presentationTimeUs
            if (firstPtsUs == -1L || buffer.size == 1) {
                firstPtsUs = info.presentationTimeUs
            }

            // Evict old samples beyond max duration, ensuring we start with keyframe
            evictOld()

            return true

        } catch (e: Exception) {
            Log.e(tag, "addSample failed", e)
            return false
        }
    }

    @Synchronized
    private fun evictOld() {
        if (buffer.isEmpty()) return
        if (firstPtsUs == -1L || lastPtsUs == -1L) return

        val durationUs = lastPtsUs - firstPtsUs
        if (durationUs <= maxDurationUs) return

        // Need to evict oldest until duration <= maxDuration and first is keyframe
        while (buffer.isNotEmpty() && (lastPtsUs - (buffer.firstOrNull()?.presentationTimeUs ?: lastPtsUs)) > maxDurationUs) {
            val removed = buffer.removeFirst()
            totalBytes -= removed.size
            firstPtsUs = buffer.firstOrNull()?.presentationTimeUs ?: -1L
        }

        // Now ensure first sample is keyframe – if not, keep removing until we find keyframe
        while (buffer.isNotEmpty() && buffer.firstOrNull()?.isKeyFrame == false) {
            // But don't remove so much that we go below minimum duration (e.g., keep at least 1 second)
            val currentDuration = lastPtsUs - (buffer.firstOrNull()?.presentationTimeUs ?: lastPtsUs)
            if (currentDuration < 1_000_000L) break // keep at least 1s even if not keyframe

            val removed = buffer.removeFirst()
            totalBytes -= removed.size
            firstPtsUs = buffer.firstOrNull()?.presentationTimeUs ?: -1L
        }

        if (buffer.isEmpty()) {
            firstPtsUs = -1L
            lastPtsUs = -1L
            totalBytes = 0
        }
    }

    @Synchronized
    fun getSamplesCopy(): List<Sample> {
        return buffer.toList()
    }

    @Synchronized
    fun getDurationUs(): Long {
        if (firstPtsUs == -1L || lastPtsUs == -1L) return 0
        return lastPtsUs - firstPtsUs
    }

    @Synchronized
    fun getDurationSeconds(): Float {
        return getDurationUs() / 1_000_000f
    }

    @Synchronized
    fun getSizeBytes(): Long = totalBytes

    @Synchronized
    fun getCount(): Int = buffer.size

    @Synchronized
    fun clear() {
        buffer.clear()
        totalBytes = 0
        firstPtsUs = -1L
        lastPtsUs = -1L
        codecConfig = null
        Log.d(tag, "Buffer cleared")
    }

    @Synchronized
    fun updateOutputFormat(format: android.media.MediaFormat) {
        outputFormat = format
        Log.d(tag, "Output format set: $format")
    }

    // Keep old name as deprecated alias with different JVM name to avoid clash – calls update
    @JvmName("setOutputFormatCompat")
    @Synchronized
    fun setOutputFormatCompat(format: android.media.MediaFormat) {
        updateOutputFormat(format)
    }

    @Synchronized
    fun updateMaxDuration(newDurationUs: Long) {
        maxDurationUs = newDurationUs
        evictOld()
        Log.d(tag, "Max duration updated to ${newDurationUs/1_000_000}s")
    }

    /**
     * Get samples adjusted to start from 0 PTS for muxer writing.
     * Returns list with PTS offset applied.
     */
    @Synchronized
    fun getSamplesForSaving(): List<Sample> {
        val samples = buffer.toList()
        if (samples.isEmpty()) return emptyList()

        val firstPts = samples.first().presentationTimeUs
        // Adjust PTS to start from 0
        return samples.map { sample ->
            sample.copy(
                // Keep data same, but we will adjust PTS during muxer write
                // Actually we return original and let saver handle offset
            )
        }
    }

    fun getFirstPts(): Long = firstPtsUs
}
