package com.example.myapplication

import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.runtime.EmbeddingEngine
import com.example.myapplication.runtime.SemanticSearchEngine
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

    @Test
    fun semanticSearchFindsRelevantPassageWithBundledModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = SemanticSearchEngine(
            context = context,
            engineCount = 2,
            useMmap = true,
            useSharedMemory = true
        )

        try {
            val result = engine.search(
                document = """
                    Android memory mapping loads model pages from a file only when they are needed.

                    Fresh pasta is prepared by mixing flour with eggs before rolling the dough.
                """.trimIndent(),
                query = "How can Android load model pages on demand?"
            )

            assertTrue(result.bestPassage.startsWith("Android memory mapping"))
            assertEquals(2, result.chunksScanned)
            assertEquals(384, result.embeddingDimensions)
            assertEquals(2, result.enginePoolSize)
            assertEquals(2, result.enginesUsed)
            assertTrue(result.mappedModelBytes > result.uniqueMappedModelBytes)
            assertEquals(0L, result.copiedModelBytes)
            assertTrue(result.sharedTensorBytes > 0L)
            assertEquals(0L, result.heapTensorBytes)
        } finally {
            engine.close()
        }
    }

    @Test
    fun semanticSearchSupportsCopiedModelAndHeapTensorIo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = SemanticSearchEngine(
            context = context,
            engineCount = 1,
            useMmap = false,
            useSharedMemory = false
        )

        try {
            val storage = engine.runtimeSnapshot()
            val result = engine.search(
                document = "Android maps model files to share read-only pages.",
                query = "How are model files shared?"
            )

            assertEquals(1, storage.engineCount)
            assertTrue(storage.heapModelBytes > 0L)
            assertEquals(0L, storage.mappedModelBytes)
            assertTrue(storage.heapTensorBytes > 0L)
            assertEquals(0L, storage.sharedTensorBytes)
            assertEquals(1, result.enginePoolSize)
            assertEquals(1, result.enginesUsed)
            assertTrue(result.copiedModelBytes > 0L)
            assertTrue(result.heapTensorBytes > 0L)
        } finally {
            engine.close()
        }
    }
}
