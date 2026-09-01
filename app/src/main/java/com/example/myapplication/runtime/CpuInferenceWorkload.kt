package com.example.myapplication.runtime

import kotlin.math.abs

/**
 * A deterministic, fixed-work CPU inference surrogate.
 *
 * Using a fixed operation count is important for thermal benchmarks: if the
 * device throttles, the measured duration increases instead of the workload
 * silently doing less work to meet a time target.
 */
internal class CpuInferenceWorkload(
    private val embeddingSize: Int = DEFAULT_EMBEDDING_SIZE,
    private val rounds: Int = DEFAULT_ROUNDS
) {
    private val input = FloatArray(embeddingSize) { index ->
        ((index % 31) - 15) / 16.0f
    }

    fun run(): FloatArray {
        val output = FloatArray(embeddingSize)

        repeat(rounds) { round ->
            var outputIndex = 0
            while (outputIndex < embeddingSize) {
                var accumulator = 0.0f
                var inputIndex = 0

                while (inputIndex < embeddingSize) {
                    val weightCode = (
                        outputIndex * 31 +
                            inputIndex * 17 +
                            round * 13
                        ) and 0xff
                    val weight = (weightCode - 127) / 128.0f
                    accumulator += input[inputIndex] * weight
                    inputIndex++
                }

                // A bounded activation keeps repeated runs numerically stable.
                output[outputIndex] = accumulator / (embeddingSize + abs(accumulator))
                outputIndex++
            }

            // Feed the output into the next layer without changing the amount
            // of work performed on each invocation.
            output.copyInto(input)
        }

        return output
    }

    private companion object {
        const val DEFAULT_EMBEDDING_SIZE = 384
        const val DEFAULT_ROUNDS = 240
    }
}
