package com.example.myapplication.ai.runtime

interface InferenceEngine {

    fun load()

    fun run(
        inputIds: IntArray,
        attentionMask: IntArray
    ): FloatArray

    fun close()
}