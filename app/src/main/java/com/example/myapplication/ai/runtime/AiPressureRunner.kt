package com.example.myapplication.runtime

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

class AiPressureRunner(context: Context) {
    private val appContext = context.applicationContext
    private val engines = mutableListOf<EmbeddingEngine>()
    private var useDirectBuffers = false
    private var useMmap = false
    private var useLiteRtNpu = false

    @Synchronized
    fun updateArchitectureConfig(direct: Boolean, mmap: Boolean, liteRtNpu: Boolean) {
        useDirectBuffers = direct
        useMmap = mmap
        useLiteRtNpu = liteRtNpu
        releaseAllEngines()

        // Configuration switches are an explicit A/B benchmark boundary. Ask
        // ART to reclaim the previous engines before telemetry records the new
        // configuration; production inference loops should not force GC.
        Runtime.getRuntime().gc()
    }

    @Synchronized
    fun addEngine() = engines.add(EmbeddingEngine(appContext, useLiteRtNpu, useDirectBuffers, useMmap))

    @Synchronized
    fun runInferenceOnAll(): Long {
        var lastTimeMs = 0L
        engines.forEach { lastTimeMs = it.runInference().timeMs }
        return lastTimeMs
    }

    @Synchronized
    fun engineCount(): Int = engines.size

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Synchronized
    fun releaseOneEngine() { if (engines.isNotEmpty()) engines.removeLast().close() }

    @Synchronized
    fun releaseAllEngines() {
        engines.forEach { it.close() }
        engines.clear()
    }
}
