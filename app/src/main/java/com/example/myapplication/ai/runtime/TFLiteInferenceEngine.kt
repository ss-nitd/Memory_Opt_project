package com.example.myapplication.ai.runtime

import org.tensorflow.lite.Interpreter

class TFLiteInferenceEngine(
    private val interpreter: Interpreter
) : InferenceEngine {


    override fun load() {
        // model already loaded
    }


    override fun run(
        inputIds: IntArray,
        attentionMask: IntArray
    ): FloatArray {


        val output = Array(1) {
            FloatArray(384)
        }


        val inputs = arrayOf(
            inputIds,
            attentionMask
        )


        interpreter.runForMultipleInputsOutputs(
            inputs,
            mapOf(
                0 to output
            )
        )


        return output[0]
    }


    override fun close() {
        interpreter.close()
    }
}