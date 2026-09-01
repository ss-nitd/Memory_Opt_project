package com.example.myapplication

import android.util.Log
import com.example.myapplication.runtime.EmbeddingEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class BenchmarkRunner(
    private val embeddingEngine: EmbeddingEngine
) {

    companion object {
        private const val TAG = "BenchmarkRunner"
    }

    private val retainedEmbeddings =
        mutableListOf<FloatArray>()

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var stopRequested = false

    private var completedIterations = 0

    suspend fun run(
        totalIterations: Int
    ) {
        stopRequested = false
        while (
            completedIterations < totalIterations &&
            !stopRequested
        ) {
            currentCoroutineContext().ensureActive()
            waitWhilePaused()
            if (stopRequested) break
            val iteration = completedIterations + 1
            val result =
                embeddingEngine.runInference()
            retainedEmbeddings.add(
                result.embedding
            )
            completedIterations = iteration
            Log.d(
                TAG,
                """
                Iteration=$iteration
                Time=${result.timeMs}ms
                Stored=${retainedEmbeddings.size}
                """.trimIndent()
            )
        }
        Log.d(
            TAG,
            "Benchmark stopped at iteration=$completedIterations"
        )
    }

    fun pause() {
        pauseRequested = true
        Log.w(
            TAG,
            "Pause requested at iteration=$completedIterations"
        )
    }

    fun resume() {
        pauseRequested = false
        Log.w(
            TAG,
            "Resume requested at iteration=$completedIterations"
        )
    }

    fun stop() {
        stopRequested = true
        pauseRequested = false
    }

    fun clearRetainedEmbeddings(): Int {
        val count = retainedEmbeddings.size
        retainedEmbeddings.clear()
        Log.w(
            TAG,
            "Cleared $count retained embeddings"
        )
        return count
    }

    fun completedIterations(): Int =
        completedIterations

    private suspend fun waitWhilePaused() {
        while (pauseRequested && !stopRequested) {
            delay(100)
        }
    }
}
