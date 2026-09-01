package com.example.myapplication

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.myapplication.runtime.AiPressureRunner
import com.example.myapplication.runtime.MemoryPressure
import com.example.myapplication.runtime.MemoryPressureMonitor
import com.example.myapplication.runtime.PauseReason
import com.example.myapplication.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RuntimeController(
    private val benchmarkRunner: BenchmarkRunner,
    private val memoryPressureMonitor: MemoryPressureMonitor,
    private val aiPressureRunner: AiPressureRunner,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "RuntimeController"
        private const val MEMORY_SAMPLE_INTERVAL_MS = 1_000L
    }

    private val _state =
        MutableStateFlow<RuntimeState>(
            RuntimeState.Idle
        )

    val state: StateFlow<RuntimeState> =
        _state.asStateFlow()

    private var benchmarkJob: Job? = null
    private var memoryObserverJob: Job? = null
    private var memorySamplingJob: Job? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun start(iterations: Int) {
        if (benchmarkJob?.isActive == true) {
            Log.w(
                TAG,
                "Benchmark already running"
            )
            return
        }

        observeMemoryPressure()
        startMemorySampling()

        _state.value =
            RuntimeState.Running

        benchmarkJob =
            scope.launch {

                benchmarkRunner.run(
                    iterations
                )

                if (
                    _state.value
                            is RuntimeState.Running
                ) {
                    _state.value =
                        RuntimeState.Completed
                }
            }
    }

    fun resume() {

        benchmarkRunner.resume()

        _state.value =
            RuntimeState.Running

        Log.w(
            TAG,
            "Runtime resumed"
        )
    }

    fun stop() {

        benchmarkRunner.stop()

        benchmarkJob?.cancel()
        benchmarkJob = null

        memorySamplingJob?.cancel()
        memorySamplingJob = null

        memoryObserverJob?.cancel()
        memoryObserverJob = null

        _state.value =
            RuntimeState.Idle

        Log.w(
            TAG,
            "Runtime stopped"
        )
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun observeMemoryPressure() {

        if (
            memoryObserverJob?.isActive == true
        ) {
            return
        }

        memoryObserverJob =
            scope.launch {

                memoryPressureMonitor
                    .pressure
                    .collect { pressure ->

                        applyMemoryPolicy(
                            pressure
                        )
                    }
            }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun applyMemoryPolicy(
        pressure: MemoryPressure
    ) {

        Log.w(
            TAG,
            """
            ========================================
            APPLYING MEMORY POLICY
            pressure=$pressure
            runtimeState=${_state.value}
            engines=${aiPressureRunner.engineCount()}
            ========================================
            """.trimIndent()
        )

        when (pressure) {

            MemoryPressure.NORMAL -> {

                Log.d(
                    TAG,
                    "NORMAL -> continue normally"
                )
            }

            MemoryPressure.MODERATE -> {

                Log.w(
                    TAG,
                    "MODERATE -> monitoring, inference continues"
                )
            }

            MemoryPressure.LOW -> {

                Log.w(
                    TAG,
                    """
                    LOW MEMORY
                    action=pause inference
                    engines=${aiPressureRunner.engineCount()}
                    """.trimIndent()
                )

                benchmarkRunner.pause()

                _state.value =
                    RuntimeState.Paused(
                        PauseReason.SYSTEM_MEMORY_LOW
                    )
            }

            MemoryPressure.CRITICAL -> {

                Log.e(
                    TAG,
                    """
                    ========================================
                    CRITICAL MEMORY
                    enginesBefore=${aiPressureRunner.engineCount()}
                    action=pause + release MiniLM engine
                    ========================================
                    """.trimIndent()
                )

                benchmarkRunner.pause()

                aiPressureRunner
                    .releaseOneEngine()

                Log.e(
                    TAG,
                    """
                    RESOURCE RELEASE COMPLETE
                    enginesAfter=${aiPressureRunner.engineCount()}
                    """.trimIndent()
                )

                _state.value =
                    RuntimeState.Paused(
                        PauseReason.SYSTEM_MEMORY_CRITICAL
                    )
            }
        }
    }

    private fun startMemorySampling() {

        if (
            memorySamplingJob?.isActive == true
        ) {
            return
        }

        memorySamplingJob =
            scope.launch {

                while (isActive) {

                    val snapshot =
                        memoryPressureMonitor
                            .refreshSystemMemory()

                    Log.d(
                        "MemorySample",
                        """
                        availableMb=${snapshot.availableBytes.toMb()}
                        totalMb=${snapshot.totalBytes.toMb()}
                        thresholdMb=${snapshot.thresholdBytes.toMb()}
                        lowMemory=${snapshot.lowMemory}
                        pressure=${snapshot.pressure}
                        runtimeState=${_state.value}
                        engines=${aiPressureRunner.engineCount()}
                        """.trimIndent()
                    )

                    delay(
                        MEMORY_SAMPLE_INTERVAL_MS
                    )
                }
            }
    }

    private fun Long.toMb(): Long =
        this / 1024 / 1024
}