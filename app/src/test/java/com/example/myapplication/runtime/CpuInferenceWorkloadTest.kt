package com.example.myapplication.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuInferenceWorkloadTest {
    @Test
    fun run_returnsFiniteEmbeddingOfRequestedSize() {
        val embedding = CpuInferenceWorkload(embeddingSize = 16, rounds = 3).run()

        assertEquals(16, embedding.size)
        assertTrue(embedding.all(Float::isFinite))
        assertTrue(embedding.any { it != 0.0f })
    }

    @Test
    fun run_isDeterministicForFreshWorkloads() {
        val first = CpuInferenceWorkload(embeddingSize = 16, rounds = 3).run()
        val second = CpuInferenceWorkload(embeddingSize = 16, rounds = 3).run()

        assertArrayEquals(first, second, 0.0f)
    }
}
