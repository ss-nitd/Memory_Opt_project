package com.example.myapplication.runtime

import android.content.Context
import android.os.SystemClock

class NaiveEmbeddingEngine(context: Context) {
    // Load the model only once
    private val interpreter = ModelLoader(context).loadInterpreter()

    // Allocate all buffers only once
    private val inputIds = Array(1) { IntArray(128) { 0 } }

    private val attentionMask = Array(1) { IntArray(128) { 1 } }

    private val output = Array(1) { FloatArray(384) }

    private val inputs = arrayOf(
        inputIds,
        attentionMask
    )

    private val outputs = mutableMapOf<Int, Any>(
        0 to output
    )

    val scratch = ByteArray(5 * 1024 * 1024) // 5 MB

//    fun runDummyInference(): InferenceResult {
//
//        // Allocate every object for every inference (intentionally bad)
//        val inputIds = Array(1) { IntArray(128) { 0 } }
//
//        val attentionMask = Array(1) { IntArray(128) { 1 } }
//
//        val output = Array(1) { FloatArray(384) }
//
//        val inputs = arrayOf(
//            inputIds,
//            attentionMask
//        )
//
//        val outputs = mutableMapOf<Int, Any>(
//            0 to output
//        )
//
//        val start = SystemClock.elapsedRealtime()
//
//        interpreter.runForMultipleInputsOutputs(
//            inputs,
//            outputs
//        )
//
//        val end = SystemClock.elapsedRealtime()
//
//        return InferenceResult(
//            timeMs = end - start,
//            embedding = output[0].clone()
//        )
//    }

    fun runDummyInference(): InferenceResult {

        // Simulate a large temporary preprocessing buffer

        // Touch every page so the OS actually commits the memory
        for (i in scratch.indices step 4096) {
            scratch[i] = 1
        }

        val inputIds = Array(1) { IntArray(128) { 0 } }
        val attentionMask = Array(1) { IntArray(128) { 1 } }
        val output = Array(1) { FloatArray(384) }

        val inputs = arrayOf(inputIds, attentionMask)
        val outputs = mutableMapOf<Int, Any>(
            0 to output
        )

        val start = SystemClock.elapsedRealtime()

        interpreter.runForMultipleInputsOutputs(
            inputs,
            outputs
        )

        val end = SystemClock.elapsedRealtime()

        return InferenceResult(
            timeMs = end - start,
            embedding = output[0].clone()
        )
    }
}