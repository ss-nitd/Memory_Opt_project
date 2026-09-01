package com.example.myapplication.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class EmbeddingEngine(
    context: Context,
    private val useLiteRtNpu: Boolean,
    private val useDirectBuffers: Boolean,
    private val useMmap: Boolean
) {
    // 4 MiB tensor allocation (demonstrating allocateDirect)
    private val tensorSizeBytes = 4 * 1024 * 1024
    private val nativeFootprint: ByteBuffer? = if (useDirectBuffers) ByteBuffer.allocateDirect(tensorSizeBytes) else null
    private val jvmFootprint: ByteArray? = if (!useDirectBuffers) ByteArray(tensorSizeBytes) else null

    // 6 MiB model file allocation (demonstrating mmap)
    private var randomAccessFile: RandomAccessFile? = null
    private var fileChannel: FileChannel? = null
    private var mappedModelBuffer: MappedByteBuffer? = null
    private var legacyModelBuffer: ByteArray? = null
    @Volatile
    private var modelPageChecksum = 0

    init {
        // SLIDE 15: LiteRT Initialization Target
        Log.i("EmbeddingEngine", if (useLiteRtNpu) "LiteRT: Target set to Accelerator.NPU" else "LiteRT: Target set to CPU")
        loadModel(context, useMmap)
    }

    private fun loadModel(context: Context, mmap: Boolean) {
        val modelFile = File(context.cacheDir, "dummy_slm_model.bin")
        if (!modelFile.exists()) {
            modelFile.writeBytes(ByteArray(6 * 1024 * 1024))
        }

        if (mmap) {
            // SLIDE 12: Virtual Memory Map (RAM stays stable)
            randomAccessFile = RandomAccessFile(modelFile, "r")
            fileChannel = randomAccessFile?.channel
            mappedModelBuffer = fileChannel?.map(
                FileChannel.MapMode.READ_ONLY,
                0,
                fileChannel!!.size()
            )
        } else {
            // Legacy Heap Load (Eats Physical RAM & Java Heap)
            legacyModelBuffer = modelFile.readBytes()
        }
    }

    fun runDummyInference(): InferenceResult {
        val start = SystemClock.elapsedRealtime()
        touchModelPages()
        Thread.sleep(if (useLiteRtNpu) 15L else 85L) // Simulate NPU speedup
        val end = SystemClock.elapsedRealtime()

        // Return a dummy float array so BenchmarkRunner compiles and retains memory
        return InferenceResult(timeMs = end - start, embedding = FloatArray(384))
    }

    /**
     * Simulates inference reading model weights. Sampling one byte per page is
     * enough to fault mmap-backed pages in on demand without copying the model
     * into the Java heap. The checksum keeps the reads observable to the JVM.
     */
    private fun touchModelPages() {
        var checksum = 0

        mappedModelBuffer?.let { buffer ->
            for (offset in 0 until buffer.limit() step PAGE_SIZE_BYTES) {
                checksum = checksum xor buffer.get(offset).toInt()
            }
        }

        legacyModelBuffer?.let { buffer ->
            for (offset in buffer.indices step PAGE_SIZE_BYTES) {
                checksum = checksum xor buffer[offset].toInt()
            }
        }

        modelPageChecksum = checksum
    }

    fun close() {
        nativeFootprint?.clear()
        mappedModelBuffer = null
        fileChannel?.close()
        randomAccessFile?.close()
        legacyModelBuffer = null
    }

    private companion object {
        const val PAGE_SIZE_BYTES = 4 * 1024
    }
}
