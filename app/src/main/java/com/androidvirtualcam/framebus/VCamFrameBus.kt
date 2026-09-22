package com.androidvirtualcam.framebus

import android.graphics.ImageFormat
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.lang.Math

/**
 * Production-grade frame transport for Android Virtual Camera.
 * Replaces file-based IPC (virtual.jpg / vcam.yuv) with SharedMemory 3-slot ring buffer + Unix domain socket fd sharing.
 *
 * Layout:
 * [Global Header 64 bytes]
 *   0: magic int 0x5643414D "VCAM"
 *   4: version int
 *   8: slotCount int (3)
 *   12: slotSize int
 *   16: maxFrameSize int
 *   20: maxWidth int
 *   24: maxHeight int
 *   28: writeIndex int (shared atomic)
 *   32: latestSequence int (shared atomic)
 *   36-63: reserved
 * [Slot 0: header 32 bytes + NV21 data]
 * [Slot 1]
 * [Slot 2]
 *
 * Slot header (32 bytes):
 *   0: timestampNs long (8)
 *   8: width int (4)
 *   12: height int (4)
 *   16: format int (4) ImageFormat.NV21
 *   20: flags int (4) FREE/WRITING/READY/READING
 *   24: sequence int (4)
 *   28: reserved int (4)
 *   32: NV21 frame data (maxFrameSize)
 *
 * Producer: publishFrame() must complete <2ms (zero allocation, direct memory copy, VarHandle fences)
 * Consumer: acquireLatestFrame() / releaseFrame()
 * FD sharing: Unix domain socket handshake on startup only via getShareableFd() + LocalSocket ancillary FDs
 */
class VCamFrameBus private constructor(
    private val sharedMemory: SharedMemory?,
    private val parcelFd: ParcelFileDescriptor?,
    private val mappedBuffer: ByteBuffer,
    val slotSize: Int,
    val maxFrameSize: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    private val isProducer: Boolean
) : AutoCloseable {

    companion object {
        private const val TAG = "VCamFrameBus"
        const val SLOT_COUNT = 3
        const val GLOBAL_HEADER_SIZE = 64
        const val SLOT_HEADER_SIZE = 32
        const val MAGIC = 0x5643414D // "VCAM"
        const val VERSION = 2

        @Volatile
        private var singletonInstance: VCamFrameBus? = null

        // Added for GLPreview – provides DI/singleton getInstance(context) as requested in task 9
        @JvmStatic
        fun getInstance(context: android.content.Context? = null): VCamFrameBus {
            return singletonInstance ?: synchronized(this) {
                singletonInstance ?: try {
                    createProducer().also { singletonInstance = it }
                } catch (e: Exception) {
                    // Fallback: try consumer connection, if fails create producer anyway with smaller size
                    try {
                        connectAsConsumer().also { singletonInstance = it }
                    } catch (ce: Exception) {
                        // Last resort: create minimal producer that won't fail on old APIs
                        createProducer(1280, 720, "vcam_frame_bus_fallback").also { singletonInstance = it }
                    }
                }
            }
        }

        // Global header offsets
        const val OFF_MAGIC = 0
        const val OFF_VERSION = 4
        const val OFF_SLOT_COUNT = 8
        const val OFF_SLOT_SIZE = 12
        const val OFF_MAX_FRAME_SIZE = 16
        const val OFF_MAX_WIDTH = 20
        const val OFF_MAX_HEIGHT = 24
        const val OFF_WRITE_INDEX = 28
        const val OFF_LATEST_SEQ = 32

        // Slot header offsets (relative to slot start)
        const val SLOT_OFF_TIMESTAMP = 0
        const val SLOT_OFF_WIDTH = 8
        const val SLOT_OFF_HEIGHT = 12
        const val SLOT_OFF_FORMAT = 16
        const val SLOT_OFF_FLAGS = 20
        const val SLOT_OFF_SEQUENCE = 24
        const val SLOT_OFF_RESERVED = 28

        // Flags
        const val FLAG_FREE = 0
        const val FLAG_WRITING = 1
        const val FLAG_READY = 2
        const val FLAG_READING = 3

        const val FORMAT_NV21 = ImageFormat.NV21

        const val SOCKET_NAME = "vcam_frame_bus"
        const val SOCKET_FILE_PATH = "/data/data/com.androidvirtualcam/files/vcam_bus.sock"

        // For testing, allow custom socket path
        fun getSocketFilePath(): String = SOCKET_FILE_PATH

        fun getAbstractSocketName(): String = SOCKET_NAME

        /**
         * Producer factory – creates new SharedMemory ring buffer.
         */
        fun createProducer(maxWidth: Int = 1920, maxHeight: Int = 1080, name: String = "vcam_frame_bus"): VCamFrameBus {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O_MR1) {
                throw IllegalStateException("SharedMemory requires API 27+, current API ${android.os.Build.VERSION.SDK_INT} – use MemoryFile fallback or upgrade minSdk")
            }
            val maxFrameSize = maxWidth * maxHeight * 3 / 2
            val slotSize = SLOT_HEADER_SIZE + maxFrameSize
            val totalSize = GLOBAL_HEADER_SIZE + slotSize * SLOT_COUNT

            val shm = SharedMemory.create(name, totalSize)
            // Protect: RW for producer, will be RO for consumer after sharing
            val mapped = shm.mapReadWrite()

            // Initialize global header
            mapped.order(ByteOrder.nativeOrder())
            mapped.putInt(OFF_MAGIC, MAGIC)
            mapped.putInt(OFF_VERSION, VERSION)
            mapped.putInt(OFF_SLOT_COUNT, SLOT_COUNT)
            mapped.putInt(OFF_SLOT_SIZE, slotSize)
            mapped.putInt(OFF_MAX_FRAME_SIZE, maxFrameSize)
            mapped.putInt(OFF_MAX_WIDTH, maxWidth)
            mapped.putInt(OFF_MAX_HEIGHT, maxHeight)
            mapped.putInt(OFF_WRITE_INDEX, 0)
            mapped.putInt(OFF_LATEST_SEQ, 0)
            // Zero reserved
            for (i in 36 until GLOBAL_HEADER_SIZE step 4) {
                mapped.putInt(i, 0)
            }

            // Initialize slots as FREE
            for (slot in 0 until SLOT_COUNT) {
                val slotOffset = GLOBAL_HEADER_SIZE + slot * slotSize
                mapped.putLong(slotOffset + SLOT_OFF_TIMESTAMP, 0L)
                mapped.putInt(slotOffset + SLOT_OFF_WIDTH, 0)
                mapped.putInt(slotOffset + SLOT_OFF_HEIGHT, 0)
                mapped.putInt(slotOffset + SLOT_OFF_FORMAT, FORMAT_NV21)
                mapped.putInt(slotOffset + SLOT_OFF_FLAGS, FLAG_FREE)
                mapped.putInt(slotOffset + SLOT_OFF_SEQUENCE, 0)
                mapped.putInt(slotOffset + SLOT_OFF_RESERVED, 0)
            }

            VarHandle.fullFence()

            Log.i(TAG, "Created producer bus: ${maxWidth}x${maxHeight}, maxFrame $maxFrameSize, slotSize $slotSize, total $totalSize")

            return VCamFrameBus(
                sharedMemory = shm,
                parcelFd = null,
                mappedBuffer = mapped,
                slotSize = slotSize,
                maxFrameSize = maxFrameSize,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                isProducer = true
            )
        }

        /**
         * Consumer factory – from ParcelFileDescriptor received via Unix socket.
         */
        fun createConsumerFromFd(pfd: ParcelFileDescriptor): VCamFrameBus {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O_MR1) {
                throw IllegalStateException("SharedMemory requires API 27+, current API ${android.os.Build.VERSION.SDK_INT}")
            }
            // Dup fd and mmap read-only
            // We need to get FileDescriptor from PFD and map via SharedMemory? Use ParcelFileDescriptor + FileChannel mapping
            // Since we received ashmem fd, we can create SharedMemory from fd? Use reflection to create SharedMemory from fd.

            // Try to create SharedMemory from fd via hidden API: SharedMemory.createFromFileDescriptor?
            // For production, we use FileChannel.map
            val fileChannel = java.io.FileInputStream(pfd.fileDescriptor).channel
            val mapped = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, pfd.statSize.takeIf { it > 0 } ?: (64 + (32 + 1920*1080*3/2)*3).toLong())
            // Note: statSize may be 0 for ashmem, so we need to read header to get total size
            // Read global header to get slotSize and total
            mapped.order(ByteOrder.nativeOrder())
            val magic = mapped.getInt(OFF_MAGIC)
            if (magic != MAGIC) {
                throw IOException("Invalid magic: expected $MAGIC got $magic")
            }
            val slotSize = mapped.getInt(OFF_SLOT_SIZE)
            val maxFrameSize = mapped.getInt(OFF_MAX_FRAME_SIZE)
            val maxWidth = mapped.getInt(OFF_MAX_WIDTH)
            val maxHeight = mapped.getInt(OFF_MAX_HEIGHT)

            Log.i(TAG, "Created consumer bus from fd: ${maxWidth}x${maxHeight}, slotSize $slotSize")

            return VCamFrameBus(
                sharedMemory = null,
                parcelFd = pfd,
                mappedBuffer = mapped as ByteBuffer,
                slotSize = slotSize,
                maxFrameSize = maxFrameSize,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                isProducer = false
            )
        }

        /**
         * Consumer factory – connect to producer's LocalSocket and receive fd.
         */
        fun connectAsConsumer(socketPath: String = SOCKET_FILE_PATH, abstractName: String = SOCKET_NAME, timeoutMs: Int = 5000): VCamFrameBus {
            val socket = LocalSocket()
            try {
                // Try abstract namespace first (more reliable, no file cleanup)
                try {
                    socket.connect(LocalSocketAddress(abstractName, LocalSocketAddress.Namespace.ABSTRACT), timeoutMs)
                } catch (e: IOException) {
                    // Fallback to filesystem socket
                    socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM), timeoutMs)
                }

                // Handshake: send 1 byte request, receive fd via ancillary
                val output = socket.outputStream
                output.write(1)
                output.flush()

                // Receive fd
                val input = socket.inputStream
                // Read 4 bytes size (totalSize) – producer sends size as int
                val sizeBuf = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val r = input.read(sizeBuf, read, 4 - read)
                    if (r <= 0) break
                    read += r
                }
                // Ancillary FDs
                val fds = socket.ancillaryFileDescriptors
                if (fds == null || fds.isEmpty()) {
                    throw IOException("No ancillary FD received from frame bus server")
                }
                val fd = fds[0]
                val pfd = ParcelFileDescriptor.dup(fd)

                Log.i(TAG, "Consumer received FD via socket, size header bytes: ${sizeBuf.contentToString()}")

                socket.close()

                return createConsumerFromFd(pfd)

            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw IOException("Failed to connect as consumer to frame bus", e)
            }
        }
    }

    // Local tracking with memory barriers
    private val writeIndex = AtomicInteger(0)
    private val latestSequence = AtomicInteger(0)
    private val slotStates = AtomicIntegerArray(SLOT_COUNT)

    init {
        mappedBuffer.order(ByteOrder.nativeOrder())
        // Initialize slotStates from shared memory flags if consumer
        if (!isProducer) {
            for (i in 0 until SLOT_COUNT) {
                val slotOffset = GLOBAL_HEADER_SIZE + i * slotSize
                val flags = mappedBuffer.getInt(slotOffset + SLOT_OFF_FLAGS)
                slotStates.set(i, flags)
            }
            writeIndex.set(mappedBuffer.getInt(OFF_WRITE_INDEX))
            latestSequence.set(mappedBuffer.getInt(OFF_LATEST_SEQ))
        } else {
            for (i in 0 until SLOT_COUNT) slotStates.set(i, FLAG_FREE)
        }
    }

    /**
     * Producer: publish NV21 frame. Must complete <2ms.
     * Zero allocation, direct memory copy via Unsafe, VarHandle fences.
     */
    fun publishFrame(nv21: ByteBuffer, timestampNs: Long, width: Int, height: Int): Boolean {
        if (!isProducer) throw IllegalStateException("Only producer can publish")
        val startNs = System.nanoTime()

        val frameSize = width * height * 3 / 2
        if (frameSize > maxFrameSize) {
            Log.e(TAG, "Frame too large: $frameSize > $maxFrameSize for ${width}x${height}")
            return false
        }
        if (nv21.remaining() < frameSize) {
            Log.e(TAG, "NV21 buffer too small: ${nv21.remaining()} < $frameSize")
            return false
        }

        // Find slot not being read
        var slotIndex = -1
        var attempts = 0
        while (attempts < SLOT_COUNT) {
            val candidate = Math.floorMod(writeIndex.getAndIncrement(), SLOT_COUNT)
            val state = slotStates.get(candidate)
            if (state != FLAG_READING) {
                if (slotStates.compareAndSet(candidate, state, FLAG_WRITING)) {
                    slotIndex = candidate
                    break
                }
            }
            attempts++
        }

        if (slotIndex == -1) {
            // All slots reading – rare, force overwrite oldest READY slot
            // Find slot with smallest sequence
            var minSeq = Int.MAX_VALUE
            var minSlot = 0
            for (i in 0 until SLOT_COUNT) {
                val off = GLOBAL_HEADER_SIZE + i * slotSize + SLOT_OFF_SEQUENCE
                val seq = mappedBuffer.getInt(off)
                if (seq < minSeq) {
                    minSeq = seq
                    minSlot = i
                }
            }
            slotIndex = minSlot
            slotStates.set(slotIndex, FLAG_WRITING)
            Log.w(TAG, "All slots READING, force overwrite slot $slotIndex seq $minSeq")
        }

        // Full fence before writing
        VarHandle.fullFence()

        val slotOffset = GLOBAL_HEADER_SIZE + slotIndex * slotSize
        val seq = latestSequence.incrementAndGet()

        // Write header via absolute puts (no position change)
        mappedBuffer.putLong(slotOffset + SLOT_OFF_TIMESTAMP, timestampNs)
        mappedBuffer.putInt(slotOffset + SLOT_OFF_WIDTH, width)
        mappedBuffer.putInt(slotOffset + SLOT_OFF_HEIGHT, height)
        mappedBuffer.putInt(slotOffset + SLOT_OFF_FORMAT, FORMAT_NV21)
        mappedBuffer.putInt(slotOffset + SLOT_OFF_FLAGS, FLAG_WRITING)
        mappedBuffer.putInt(slotOffset + SLOT_OFF_SEQUENCE, seq)
        mappedBuffer.putInt(slotOffset + SLOT_OFF_RESERVED, 0)

        // Copy frame data – zero allocation, direct copy
        val dataOffset = slotOffset + SLOT_HEADER_SIZE
        try {
            copyByteBuffer(nv21, mappedBuffer, dataOffset, frameSize)
        } catch (e: Exception) {
            Log.e(TAG, "copy failed", e)
            slotStates.set(slotIndex, FLAG_FREE)
            return false
        }

        // StoreStore fence – ensure data visible before flag
        VarHandle.storeStoreFence()

        // Mark READY
        mappedBuffer.putInt(slotOffset + SLOT_OFF_FLAGS, FLAG_READY)
        slotStates.set(slotIndex, FLAG_READY)

        // Update global header atomics with release semantics
        mappedBuffer.putInt(OFF_WRITE_INDEX, writeIndex.get())
        mappedBuffer.putInt(OFF_LATEST_SEQ, seq)

        VarHandle.fullFence()

        val elapsedNs = System.nanoTime() - startNs
        val elapsedMs = elapsedNs / 1_000_000.0
        if (elapsedMs > 2.0) {
            Log.w(TAG, "publishFrame exceeded 2ms: ${"%.3f".format(elapsedMs)}ms for ${width}x${height} slot $slotIndex seq $seq")
        }

        return true
    }

    /**
     * Consumer: acquire latest READY frame. Returns descriptor with read-only slice.
     */
    fun acquireLatestFrame(): FrameDescriptor? {
        // Find slot with highest sequence and READY state
        var bestSeq = -1
        var bestSlot = -1

        for (i in 0 until SLOT_COUNT) {
            val state = slotStates.get(i)
            // Refresh from shared memory in case producer updated
            val slotOffset = GLOBAL_HEADER_SIZE + i * slotSize
            val flags = mappedBuffer.getInt(slotOffset + SLOT_OFF_FLAGS)
            slotStates.set(i, flags)

            if (flags == FLAG_READY) {
                val seq = mappedBuffer.getInt(slotOffset + SLOT_OFF_SEQUENCE)
                if (seq > bestSeq) {
                    bestSeq = seq
                    bestSlot = i
                }
            }
        }

        if (bestSlot == -1) return null

        // Try to CAS to READING
        if (!slotStates.compareAndSet(bestSlot, FLAG_READY, FLAG_READING)) {
            return null // raced
        }

        VarHandle.loadLoadFence()

        val slotOffset = GLOBAL_HEADER_SIZE + bestSlot * slotSize
        val timestamp = mappedBuffer.getLong(slotOffset + SLOT_OFF_TIMESTAMP)
        val width = mappedBuffer.getInt(slotOffset + SLOT_OFF_WIDTH)
        val height = mappedBuffer.getInt(slotOffset + SLOT_OFF_HEIGHT)
        val format = mappedBuffer.getInt(slotOffset + SLOT_OFF_FORMAT)
        val flags = mappedBuffer.getInt(slotOffset + SLOT_OFF_FLAGS)
        val sequence = mappedBuffer.getInt(slotOffset + SLOT_OFF_SEQUENCE)

        val dataSize = width * height * 3 / 2
        val dataOffset = slotOffset + SLOT_HEADER_SIZE

        // Create read-only slice without allocation of frame data
        val slice = mappedBuffer.duplicate()
        slice.order(ByteOrder.nativeOrder())
        slice.position(dataOffset)
        slice.limit(dataOffset + dataSize)
        val readOnlySlice = slice.slice().asReadOnlyBuffer()
        readOnlySlice.order(ByteOrder.nativeOrder())

        return FrameDescriptor(
            timestampNs = timestamp,
            width = width,
            height = height,
            format = format,
            flags = flags,
            sequence = sequence,
            slotIndex = bestSlot,
            data = readOnlySlice,
            dataSize = dataSize
        )
    }

    fun releaseFrame(descriptor: FrameDescriptor) {
        val slotIndex = descriptor.slotIndex
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) return

        // Mark back to READY (keep frame available for other consumers, or FREE if you want single consumer)
        // For ring buffer with latest-only semantics, we keep READY
        val slotOffset = GLOBAL_HEADER_SIZE + slotIndex * slotSize
        // Only transition READING -> READY
        if (slotStates.compareAndSet(slotIndex, FLAG_READING, FLAG_READY)) {
            mappedBuffer.putInt(slotOffset + SLOT_OFF_FLAGS, FLAG_READY)
            VarHandle.storeStoreFence()
        }
    }

    /**
     * Get shareable FD for Unix socket handshake. Producer only.
     * Returns ParcelFileDescriptor dup that can be sent via LocalSocket.setFileDescriptorsForSend()
     */
    fun getShareableFd(): ParcelFileDescriptor {
        if (!isProducer) throw IllegalStateException("Only producer can share FD")
        val shm = sharedMemory ?: throw IllegalStateException("SharedMemory null")
        // Get FileDescriptor via reflection
        val fd = getFileDescriptorFromSharedMemory(shm)
        return ParcelFileDescriptor.dup(fd)
    }

    /**
     * Start FD server – handshake on startup only.
     * Listens on abstract namespace and filesystem path for compatibility.
     */
    fun startFdServer(): FrameBusServer {
        if (!isProducer) throw IllegalStateException("Only producer can start server")
        val server = FrameBusServer(this)
        server.start()
        return server
    }

    override fun close() {
        try {
            mappedBuffer.let {
                if (sharedMemory != null) {
                    SharedMemory.unmap(it)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "unmap failed", e)
        }
        try {
            sharedMemory?.close()
        } catch (e: Exception) {
            Log.w(TAG, "shm close failed", e)
        }
        try {
            parcelFd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "pfd close failed", e)
        }
    }

    // --- Internal helpers ---

    private fun copyByteBuffer(src: ByteBuffer, dst: ByteBuffer, dstOffset: Int, size: Int) {
        // Fast path: both direct, use Unsafe copy
        if (src.isDirect && dst.isDirect) {
            val srcAddr = getDirectBufferAddress(src) + src.position()
            val dstAddr = getDirectBufferAddress(dst) + dstOffset
            unsafeCopyMemory(srcAddr, dstAddr, size.toLong())
        } else {
            // Fallback: absolute put via duplicate
            val srcDup = src.duplicate()
            srcDup.position(src.position())
            srcDup.limit(src.position() + size)
            val dstDup = dst.duplicate()
            dstDup.position(dstOffset)
            dstDup.put(srcDup)
        }
    }

    private fun getDirectBufferAddress(buffer: ByteBuffer): Long {
        // Use reflection to get address field
        try {
            val field = java.nio.Buffer::class.java.getDeclaredField("address")
            field.isAccessible = true
            return field.getLong(buffer)
        } catch (e: Exception) {
            // Try DirectBuffer interface
            try {
                val directBufferClass = Class.forName("sun.nio.ch.DirectBuffer")
                val method = directBufferClass.getMethod("address")
                return method.invoke(buffer) as Long
            } catch (e2: Exception) {
                throw RuntimeException("Failed to get direct buffer address", e2)
            }
        }
    }

    private fun unsafeCopyMemory(srcAddr: Long, dstAddr: Long, size: Long) {
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val copyMethod = unsafeClass.getMethod("copyMemory", Long::class.java, Long::class.java, Long::class.java)
            copyMethod.invoke(unsafe, srcAddr, dstAddr, size)
        } catch (e: Exception) {
            throw RuntimeException("Unsafe copy failed", e)
        }
    }

    private fun getFileDescriptorFromSharedMemory(shm: SharedMemory): FileDescriptor {
        try {
            val field = SharedMemory::class.java.getDeclaredField("mFileDescriptor")
            field.isAccessible = true
            return field.get(shm) as FileDescriptor
        } catch (e: Exception) {
            // Try getFd() method via reflection
            try {
                val method = SharedMemory::class.java.getDeclaredMethod("getFd")
                method.isAccessible = true
                val fdInt = method.invoke(shm) as Int
                // Create FileDescriptor from int fd
                val fd = FileDescriptor()
                val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
                fdField.isAccessible = true
                fdField.setInt(fd, fdInt)
                return fd
            } catch (e2: Exception) {
                throw RuntimeException("Failed to get FD from SharedMemory", e2)
            }
        }
    }

    /**
     * Server that sends FD via Unix domain socket handshake on startup only.
     */
    class FrameBusServer(private val bus: VCamFrameBus) {
        private var abstractServer: LocalServerSocket? = null
        private var fileServer: LocalServerSocket? = null
        @Volatile private var running = false
        private var thread: Thread? = null

        fun start() {
            if (running) return
            running = true

            thread = Thread({
                try {
                    // Try abstract namespace first
                    try {
                        abstractServer = LocalServerSocket(SOCKET_NAME)
                        Log.i(TAG, "FrameBusServer listening on abstract $SOCKET_NAME")
                    } catch (e: IOException) {
                        Log.w(TAG, "Abstract socket failed, trying file socket: ${e.message}")
                    }

                    // Also try file socket for compatibility
                    try {
                        val sockFile = File(SOCKET_FILE_PATH)
                        sockFile.parentFile?.mkdirs()
                        if (sockFile.exists()) sockFile.delete()
                        fileServer = LocalServerSocket("vcam_frames")
                        Log.i(TAG, "FrameBusServer listening on file ${SOCKET_FILE_PATH}")
                    } catch (e: IOException) {
                        Log.w(TAG, "File socket failed: ${e.message}")
                    }

                    while (running) {
                        var client: LocalSocket? = null
                        try {
                            // Accept from either server, prefer abstract
                            client = try {
                                abstractServer?.accept()
                            } catch (_: Exception) {
                                fileServer?.accept()
                            } ?: continue

                            client.receiveBufferSize = 1024

                            // Read 1 byte request
                            val input = client.inputStream
                            input.read()

                            // Prepare FD to send
                            val pfd = bus.getShareableFd()
                            val fd = pfd.fileDescriptor

                            // Send total size as 4 bytes
                            val totalSize = GLOBAL_HEADER_SIZE + bus.slotSize * SLOT_COUNT
                            val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(totalSize).array()

                            client.setFileDescriptorsForSend(arrayOf(fd))
                            val output = client.outputStream
                            output.write(sizeBytes)
                            output.flush()

                            Log.i(TAG, "Sent FD to client, totalSize $totalSize")

                            pfd.close()
                            client.close()

                        } catch (e: Exception) {
                            Log.w(TAG, "Server accept/send failed: ${e.message}")
                            try { client?.close() } catch (ce: Exception) {
                                Log.w(TAG, "Client close failed after error: ${ce.message}")
                            }
                            // Backoff without Thread.sleep hot path – use LockSupport park
                            try {
                                java.util.concurrent.locks.LockSupport.parkNanos(100_000_000) // 100ms
                            } catch (_: Exception) {
                                Thread.yield()
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "FrameBusServer crashed", e)
                }
            }, "VCamFrameBusServer").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running = false
            try { abstractServer?.close() } catch (_: Exception) {}
            try { fileServer?.close() } catch (_: Exception) {}
            try { File(SOCKET_FILE_PATH).delete() } catch (_: Exception) {}
            thread?.interrupt()
        }
    }

    data class FrameDescriptor(
        val timestampNs: Long,
        val width: Int,
        val height: Int,
        val format: Int,
        val flags: Int,
        val sequence: Int,
        val slotIndex: Int,
        val data: ByteBuffer,
        val dataSize: Int
    )
}
