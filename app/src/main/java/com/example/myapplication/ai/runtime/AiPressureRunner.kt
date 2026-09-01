package com.example.myapplication.runtime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi

class AiPressureRunner(context: Context) {
    private val appContext = context.applicationContext
    private val engines = mutableListOf<EmbeddingEngine>()
    private var useDirectBuffers = false
    private var useMmap = false

    @Synchronized
    fun updateArchitectureConfig(direct: Boolean, mmap: Boolean) {
        useDirectBuffers = direct
        useMmap = mmap
        releaseAllEngines()

        // Configuration switches are an explicit A/B benchmark boundary. Ask
        // ART to reclaim the previous engines before telemetry records the new
        // configuration; production inference loops should not force GC.
        Runtime.getRuntime().gc()
    }

    @Synchronized
    fun addEngine() = engines.add(EmbeddingEngine(appContext, useDirectBuffers, useMmap))

    @Synchronized
    fun runInferenceOnAllNanos(): Long {
        val startNanos = SystemClock.elapsedRealtimeNanos()
        engines.forEach { it.runInference() }
        return SystemClock.elapsedRealtimeNanos() - startNanos
    }

    @Synchronized
    fun engineCount(): Int = engines.size

    @Synchronized
    fun runtimeSnapshot(): EngineRuntimeSnapshot {
        var heapTensorBytes = 0L
        var sharedTensorBytes = 0L
        var heapModelBytes = 0L
        var mappedModelBytes = 0L

        engines.forEach { engine ->
            val snapshot = engine.storageSnapshot()
            heapTensorBytes += snapshot.heapTensorBytes
            sharedTensorBytes += snapshot.sharedTensorBytes
            heapModelBytes += snapshot.heapModelBytes
            mappedModelBytes += snapshot.mappedModelBytes
        }

        return EngineRuntimeSnapshot(
            engineCount = engines.size,
            heapTensorBytes = heapTensorBytes,
            sharedTensorBytes = sharedTensorBytes,
            heapModelBytes = heapModelBytes,
            mappedModelBytes = mappedModelBytes
        )
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Synchronized
    fun releaseOneEngine() { if (engines.isNotEmpty()) engines.removeLast().close() }

    @Synchronized
    fun releaseAllEngines() {
        engines.forEach { it.close() }
        engines.clear()
    }
}
