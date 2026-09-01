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

    fun addEngine() = engines.add(EmbeddingEngine(appContext, useLiteRtNpu, useDirectBuffers, useMmap))

    fun runInferenceOnAll(): Long {
        var lastTimeMs = 0L
        engines.forEach { lastTimeMs = it.runDummyInference().timeMs }
        return lastTimeMs
    }

    fun engineCount(): Int = engines.size
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun releaseOneEngine() { if (engines.isNotEmpty()) engines.removeLast().close() }

    fun releaseAllEngines() {
        engines.forEach { it.close() }
        engines.clear()
    }
}
