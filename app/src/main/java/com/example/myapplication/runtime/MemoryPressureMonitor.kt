// package com.example.myapplication.runtime

package com.example.myapplication.runtime

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MemoryPressureMonitor(
    context: Context
) : ComponentCallbacks2 {

    companion object {
        private const val LOW_AVAILABLE_RATIO = 0.15
        private const val CRITICAL_AVAILABLE_RATIO = 0.08
    }

    private val applicationContext = context.applicationContext
    private val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val _pressure = MutableStateFlow(MemoryPressure.NORMAL)
    val pressure: StateFlow<MemoryPressure> = _pressure.asStateFlow()

    fun register() {
        applicationContext.registerComponentCallbacks(this)
        refreshSystemMemory()
    }

    fun unregister() {
        applicationContext.unregisterComponentCallbacks(this)
    }

    fun refreshSystemMemory(): MemorySnapshot {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val availableRatio = memoryInfo.availMem.toDouble() / memoryInfo.totalMem.toDouble()

        val detectedPressure = when {
            memoryInfo.lowMemory || availableRatio <= CRITICAL_AVAILABLE_RATIO -> MemoryPressure.CRITICAL
            availableRatio <= LOW_AVAILABLE_RATIO -> MemoryPressure.LOW
            else -> MemoryPressure.NORMAL
        }

        return MemorySnapshot(
            availableBytes = memoryInfo.availMem,
            totalBytes = memoryInfo.totalMem,
            thresholdBytes = memoryInfo.threshold,
            lowMemory = memoryInfo.lowMemory,
            pressure = detectedPressure
        )
    }

    override fun onTrimMemory(level: Int) {
        val mappedPressure = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressure.CRITICAL
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressure.LOW
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressure.CRITICAL
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MemoryPressure.LOW
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressure.LOW
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressure.MODERATE
            else -> MemoryPressure.NORMAL
        }

        updatePressure(mappedPressure)
    }

    override fun onLowMemory() {
        updatePressure(MemoryPressure.CRITICAL)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun updatePressure(pressure: MemoryPressure) {
        if (_pressure.value == pressure) return
        _pressure.value = pressure
    }

    fun reset() {
        _pressure.value = MemoryPressure.NORMAL
    }
}