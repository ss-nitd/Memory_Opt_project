package com.example.myapplication

import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.runtime.EmbeddingEngine
import com.example.myapplication.runtime.ThermalMonitor

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.myapplication", appContext.packageName)
    }

    @Test
    fun engineStorageSnapshotReflectsActualBackingStores() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val heapEngine = EmbeddingEngine(context, useDirectBuffers = false, useMmap = false)
        val mappedEngine = EmbeddingEngine(context, useDirectBuffers = true, useMmap = true)

        try {
            val heap = heapEngine.storageSnapshot()
            val mapped = mappedEngine.storageSnapshot()

            assertTrue(heap.heapTensorBytes > 0)
            assertEquals(0L, heap.sharedTensorBytes)
            assertTrue(heap.heapModelBytes > 0)
            assertEquals(0L, heap.mappedModelBytes)

            assertEquals(0L, mapped.heapTensorBytes)
            assertEquals(heap.heapTensorBytes, mapped.sharedTensorBytes)
            assertEquals(0L, mapped.heapModelBytes)
            assertEquals(heap.heapModelBytes, mapped.mappedModelBytes)
        } finally {
            heapEngine.close()
            mappedEngine.close()
        }
    }

    @Test
    fun thermalSnapshotContainsPlatformValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val thermal = ThermalMonitor(context).snapshot()

        assertTrue(thermal.headroom.isNaN() || thermal.headroom >= 0.0f)
        assertTrue(
            thermal.status in PowerManager.THERMAL_STATUS_NONE..PowerManager.THERMAL_STATUS_SHUTDOWN
        )
    }
}
