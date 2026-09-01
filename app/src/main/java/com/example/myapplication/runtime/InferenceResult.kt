package com.example.myapplication.runtime

data class InferenceResult(
    val timeMs: Long,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InferenceResult

        if (timeMs != other.timeMs) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timeMs.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}