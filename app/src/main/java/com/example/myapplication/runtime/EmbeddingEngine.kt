package com.example.myapplication.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class EmbeddingEngine(
    context: Context,
    private val useLiteRtNpu: Boolean,
    private val useDirectBuffers: Boolean,
    private val useMmap: Boolean
) {
    // 8MB Tensor Allocation (Demoing allocateDirect)
    private val tensorSizeBytes = 4 * 1024 * 1024
    private val nativeFootprint: ByteBuffer? = if (useDirectBuffers) ByteBuffer.allocateDirect(tensorSizeBytes) else null
    private val jvmFootprint: ByteArray? = if (!useDirectBuffers) ByteArray(tensorSizeBytes) else null

    // 10MB Model File Allocation (Demoing mmap)
    private var randomAccessFile: RandomAccessFile? = null
    private var fileChannel: FileChannel? = null
    private var legacyModelBuffer: ByteArray? = null

    init {
        // SLIDE 15: LiteRT Initialization Target
        Log.i("EmbeddingEngine", if (useLiteRtNpu) "LiteRT: Target set to Accelerator.NPU" else "LiteRT: Target set to CPU")
        loadModel(context, useMmap)
    }

    private fun loadModel(context: Context, mmap: Boolean) {
        val modelFile = File(context.cacheDir, "dummy_slm_model.bin")
        if (!modelFile.exists()) {
            modelFile.writeBytes(ByteArray(6 * 1024 * 1024)) // 10MB Model
        }

        if (mmap) {
            // SLIDE 12: Virtual Memory Map (RAM stays stable)
            randomAccessFile = RandomAccessFile(modelFile, "r")
            fileChannel = randomAccessFile?.channel
            fileChannel?.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel!!.size())
        } else {
            // Legacy Heap Load (Eats Physical RAM & Java Heap)
            legacyModelBuffer = modelFile.readBytes()
        }
    }

    fun runDummyInference(): InferenceResult {
        val start = SystemClock.elapsedRealtime()
        Thread.sleep(if (useLiteRtNpu) 15L else 85L) // Simulate NPU speedup
        val end = SystemClock.elapsedRealtime()

        // Return a dummy float array so BenchmarkRunner compiles and retains memory
        return InferenceResult(timeMs = end - start, embedding = FloatArray(384))
    }

    fun close() {
        nativeFootprint?.clear()
        fileChannel?.close()
        randomAccessFile?.close()
        legacyModelBuffer = null
    }
}
