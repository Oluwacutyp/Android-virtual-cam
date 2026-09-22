package com.androidvirtualcam.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Fault-tolerant MKV muxer.
 *
 * Writes EBML header, Segment with unknown size (0x01FFFFFFFFFFFFFF) so truncated file remains playable.
 * Info, Tracks with video AVC and audio AAC, then Clusters with SimpleBlocks.
 * Each Cluster flushed to disk, ensuring crash recovery.
 *
 * Matroska spec: https://www.matroska.org/technical/specs/index.html
 */
class MkvMuxer(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30
) {
    companion object {
        private const val TAG = "MkvMuxer"
    }

    private var fos: FileOutputStream? = null
    private var isStarted = false
    private var clusterTimecode = 0L
    private var hasVideo = false
    private var hasAudio = false
    private var videoCodecPrivate: ByteArray? = null
    private var audioCodecPrivate: ByteArray? = null
    private var audioSampleRate = 48000
    private var audioChannels = 1
    private val startTimeMs = AtomicLong(0)

    fun start(videoFormat: MediaFormat? = null, audioFormat: MediaFormat? = null): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            fos = FileOutputStream(outputFile)

            if (videoFormat != null) {
                extractVideoCodecPrivate(videoFormat)
            }
            if (audioFormat != null) {
                extractAudioCodecPrivate(audioFormat)
            }

            writeEbmlHeader()
            writeSegmentHeader()
            writeInfo()
            writeTracks(videoFormat, audioFormat)

            isStarted = true
            startTimeMs.set(System.currentTimeMillis())
            Log.i(TAG, "MKV muxer started: ${outputFile.path} ${width}x${height} videoPrivate=${videoCodecPrivate?.size} audioPrivate=${audioCodecPrivate?.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start muxer", e)
            false
        }
    }

    private fun extractVideoCodecPrivate(format: MediaFormat) {
        try {
            val spsBuf = format.getByteBuffer("csd-0")
            val ppsBuf = format.getByteBuffer("csd-1")
            if (spsBuf != null && ppsBuf != null) {
                val sps = ByteArray(spsBuf.remaining()).also { spsBuf.get(it) }
                val pps = ByteArray(ppsBuf.remaining()).also { ppsBuf.get(it) }
                val avcc = ByteBuffer.allocate(11 + sps.size + pps.size)
                avcc.order(ByteOrder.BIG_ENDIAN)
                avcc.put(0x01)
                avcc.put(sps.getOrElse(1) { 0x64.toByte() })
                avcc.put(sps.getOrElse(2) { 0x00.toByte() })
                avcc.put(sps.getOrElse(3) { 0x1F.toByte() })
                avcc.put(0xFF.toByte())
                avcc.put(0xE1.toByte())
                avcc.putShort(sps.size.toShort())
                avcc.put(sps)
                avcc.put(0x01)
                avcc.putShort(pps.size.toShort())
                avcc.put(pps)
                videoCodecPrivate = avcc.array().copyOf(avcc.position())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract video codec private: ${e.message}")
        }
    }

    private fun extractAudioCodecPrivate(format: MediaFormat) {
        try {
            val csd0 = format.getByteBuffer("csd-0")
            if (csd0 != null) {
                val bytes = ByteArray(csd0.remaining()).also { csd0.get(it) }
                audioCodecPrivate = bytes
            }
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract audio codec private: ${e.message}")
        }
    }

    // EBML helpers

    private fun writeId(out: FileOutputStream, idBytes: ByteArray) {
        out.write(idBytes)
    }

    private fun writeVintSize(out: FileOutputStream, size: Long) {
        when {
            size < 0x7F -> {
                out.write((0x80 or size.toInt()))
            }
            size < 0x3FFF -> {
                out.write((0x40 or ((size shr 8) and 0x3F).toInt()))
                out.write((size and 0xFF).toInt())
            }
            size < 0x1FFFFF -> {
                out.write((0x20 or ((size shr 16) and 0x1F).toInt()))
                out.write(((size shr 8) and 0xFF).toInt())
                out.write((size and 0xFF).toInt())
            }
            size < 0xFFFFFFF -> {
                out.write((0x10 or ((size shr 24) and 0x0F).toInt()))
                out.write(((size shr 16) and 0xFF).toInt())
                out.write(((size shr 8) and 0xFF).toInt())
                out.write((size and 0xFF).toInt())
            }
            else -> {
                out.write(0x08)
                out.write(((size shr 24) and 0xFF).toInt())
                out.write(((size shr 16) and 0xFF).toInt())
                out.write(((size shr 8) and 0xFF).toInt())
                out.write((size and 0xFF).toInt())
            }
        }
    }

    private fun writeVintSize(out: ByteArrayOutputStream, size: Long) {
        when {
            size < 0x7F -> out.write(0x80 or size.toInt())
            size < 0x3FFF -> {
                out.write(0x40 or ((size shr 8) and 0x3F).toInt())
                out.write((size and 0xFF).toInt())
            }
            size < 0x1FFFFF -> {
                out.write(0x20 or ((size shr 16) and 0x1F).toInt())
                out.write(((size shr 8) and 0xFF).toInt())
                out.write((size and 0xFF).toInt())
            }
            else -> {
                out.write(0x10 or ((size shr 24) and 0x0F).toInt())
                out.write(((size shr 16) and 0xFF).toInt())
                out.write(((size shr 8) and 0xFF).toInt())
                out.write((size and 0xFF).toInt())
            }
        }
    }

    private fun encodeUInt(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        var v = value
        var len = 0
        var tmp = v
        while (tmp != 0L) {
            len++
            tmp = tmp shr 8
        }
        val bytes = ByteArray(len)
        for (i in len - 1 downTo 0) {
            bytes[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return bytes
    }

    private fun writeElement(out: FileOutputStream, id: ByteArray, data: ByteArray) {
        writeId(out, id)
        writeVintSize(out, data.size.toLong())
        out.write(data)
    }

    private fun writeElement(out: ByteArrayOutputStream, id: ByteArray, data: ByteArray) {
        out.write(id)
        writeVintSize(out, data.size.toLong())
        out.write(data)
    }

    private fun writeUIntElement(out: ByteArrayOutputStream, id: ByteArray, value: Long) {
        val data = encodeUInt(value)
        writeElement(out, id, data)
    }

    private fun writeStringElement(out: ByteArrayOutputStream, id: ByteArray, str: String) {
        writeElement(out, id, str.toByteArray(Charsets.UTF_8))
    }

    private fun writeFloatElement(out: ByteArrayOutputStream, id: ByteArray, value: Double) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array()
        writeElement(out, id, buf)
    }

    private fun writeEbmlHeader() {
        val fos = fos!!
        val inner = ByteArrayOutputStream()

        writeUIntElement(inner, byteArrayOf(0x42.toByte(), 0x86.toByte()), 1)
        writeUIntElement(inner, byteArrayOf(0x42.toByte(), 0xF7.toByte()), 1)
        writeUIntElement(inner, byteArrayOf(0x42.toByte(), 0xF2.toByte()), 4)
        writeUIntElement(inner, byteArrayOf(0x42.toByte(), 0xF3.toByte()), 8)
        writeStringElement(inner, byteArrayOf(0x42.toByte(), 0x82.toByte()), "matroska")
        writeUIntElement(inner, byteArrayOf(0x42.toByte(), 0x87.toByte()), 4)
        writeUIntElement(inner, byteArrayOf(0x42.toByte(), 0x85.toByte()), 2)

        val innerBytes = inner.toByteArray()
        writeId(fos, byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()))
        writeVintSize(fos, innerBytes.size.toLong())
        fos.write(innerBytes)
    }

    private fun writeSegmentHeader() {
        val fos = fos!!
        writeId(fos, byteArrayOf(0x18.toByte(), 0x53.toByte(), 0x80.toByte(), 0x67.toByte()))
        fos.write(0x01)
        fos.write(0xFF)
        fos.write(0xFF)
        fos.write(0xFF)
        fos.write(0xFF)
        fos.write(0xFF)
        fos.write(0xFF)
        fos.write(0xFF)
    }

    private fun writeInfo() {
        val fos = fos!!
        val inner = ByteArrayOutputStream()

        writeUIntElement(inner, byteArrayOf(0x2A.toByte(), 0xD7.toByte(), 0xB1.toByte()), 1_000_000)
        writeStringElement(inner, byteArrayOf(0x4D.toByte(), 0x80.toByte()), "Android Virtual Cam")
        writeStringElement(inner, byteArrayOf(0x57.toByte(), 0x41.toByte()), "VCam MKV Muxer")
        val duration = 0.0
        writeFloatElement(inner, byteArrayOf(0x44.toByte(), 0x89.toByte()), duration)

        val innerBytes = inner.toByteArray()
        writeId(fos, byteArrayOf(0x15.toByte(), 0x49.toByte(), 0xA9.toByte(), 0x66.toByte()))
        writeVintSize(fos, innerBytes.size.toLong())
        fos.write(innerBytes)
    }

    private fun writeTracks(videoFormat: MediaFormat?, audioFormat: MediaFormat?) {
        val fos = fos!!
        val tracksInner = ByteArrayOutputStream()

        // Video track
        run {
            val trackEntry = ByteArrayOutputStream()
            writeUIntElement(trackEntry, byteArrayOf(0xD7.toByte()), 1)
            writeUIntElement(trackEntry, byteArrayOf(0x73.toByte(), 0xC5.toByte()), 0x12345678L)
            writeUIntElement(trackEntry, byteArrayOf(0x83.toByte()), 1)
            writeUIntElement(trackEntry, byteArrayOf(0x88.toByte()), 0)
            writeStringElement(trackEntry, byteArrayOf(0x86.toByte()), "V_MPEG4/ISO/AVC")

            videoCodecPrivate?.let { avcc ->
                writeElement(trackEntry, byteArrayOf(0x63.toByte(), 0xA2.toByte()), avcc)
            }

            val videoInner = ByteArrayOutputStream()
            writeUIntElement(videoInner, byteArrayOf(0xB0.toByte()), width.toLong())
            writeUIntElement(videoInner, byteArrayOf(0xBA.toByte()), height.toLong())
            writeUIntElement(videoInner, byteArrayOf(0x54.toByte(), 0xB0.toByte()), width.toLong())
            writeUIntElement(videoInner, byteArrayOf(0x54.toByte(), 0xBA.toByte()), height.toLong())
            writeElement(trackEntry, byteArrayOf(0xE0.toByte()), videoInner.toByteArray())

            writeElement(tracksInner, byteArrayOf(0xAE.toByte()), trackEntry.toByteArray())
            hasVideo = true
        }

        // Audio track
        run {
            val trackEntry = ByteArrayOutputStream()
            writeUIntElement(trackEntry, byteArrayOf(0xD7.toByte()), 2)
            writeUIntElement(trackEntry, byteArrayOf(0x73.toByte(), 0xC5.toByte()), 0x87654321L)
            writeUIntElement(trackEntry, byteArrayOf(0x83.toByte()), 2)
            writeStringElement(trackEntry, byteArrayOf(0x86.toByte()), "A_AAC")

            audioCodecPrivate?.let { priv ->
                writeElement(trackEntry, byteArrayOf(0x63.toByte(), 0xA2.toByte()), priv)
            }

            val audioInner = ByteArrayOutputStream()
            writeFloatElement(audioInner, byteArrayOf(0xB5.toByte()), audioSampleRate.toDouble())
            writeUIntElement(audioInner, byteArrayOf(0x9F.toByte()), audioChannels.toLong())
            writeElement(trackEntry, byteArrayOf(0xE1.toByte()), audioInner.toByteArray())

            writeElement(tracksInner, byteArrayOf(0xAE.toByte()), trackEntry.toByteArray())
            hasAudio = true
        }

        val tracksBytes = tracksInner.toByteArray()
        writeId(fos, byteArrayOf(0x16.toByte(), 0x54.toByte(), 0xAE.toByte(), 0x6B.toByte()))
        writeVintSize(fos, tracksBytes.size.toLong())
        fos.write(tracksBytes)
    }

    fun writeSample(trackNumber: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!isStarted) return
        try {
            val fos = fos!!
            val timecodeMs = info.presentationTimeUs / 1000
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

            val shouldStartNewCluster = clusterTimecode == 0L || timecodeMs - clusterTimecode >= 1000 || (isKeyFrame && trackNumber == 1)
            if (shouldStartNewCluster) {
                clusterTimecode = timecodeMs

                writeId(fos, byteArrayOf(0x1F.toByte(), 0x43.toByte(), 0xB6.toByte(), 0x75.toByte()))
                fos.write(0x01)
                fos.write(0xFF)
                fos.write(0xFF)
                fos.write(0xFF)
                fos.write(0xFF)
                fos.write(0xFF)
                fos.write(0xFF)
                fos.write(0xFF)

                val timecodeInner = ByteArrayOutputStream()
                writeUIntElement(timecodeInner, byteArrayOf(0xE7.toByte()), clusterTimecode)
                val tcBytes = timecodeInner.toByteArray()
                fos.write(tcBytes)
            }

            val dataSize = info.size
            val blockData = ByteArray(dataSize)
            val dup = data.duplicate()
            dup.position(info.offset)
            dup.limit(info.offset + info.size)
            dup.get(blockData)

            val blockHeader = ByteArrayOutputStream()
            blockHeader.write(0x80 or trackNumber)
            val relativeTimecode = (timecodeMs - clusterTimecode).toInt().coerceIn(-32768, 32767)
            val relBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(relativeTimecode.toShort()).array()
            blockHeader.write(relBuf)
            var flags = 0
            if (isKeyFrame) flags = flags or 0x80
            blockHeader.write(flags)
            val headerBytes = blockHeader.toByteArray()

            val simpleBlockSize = headerBytes.size + blockData.size
            writeId(fos, byteArrayOf(0xA3.toByte()))
            writeVintSize(fos, simpleBlockSize.toLong())
            fos.write(headerBytes)
            fos.write(blockData)
            fos.flush()

        } catch (e: Exception) {
            Log.e(TAG, "writeSample failed track $trackNumber", e)
        }
    }

    fun stop(): Boolean {
        return try {
            fos?.flush()
            fos?.close()
            isStarted = false
            Log.i(TAG, "MKV muxer stopped: ${outputFile.path}, size=${outputFile.length()} bytes, fault-tolerant playable")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop muxer", e)
            false
        }
    }

    fun getFile(): File = outputFile
}
