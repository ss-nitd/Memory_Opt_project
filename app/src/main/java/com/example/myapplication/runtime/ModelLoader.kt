package com.example.myapplication.runtime

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class ModelLoader(
    private val context: Context
) {

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("embedding_model.tflite")
        val inputStream = fileDescriptor.createInputStream()
        val fileChannel = inputStream.channel

        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    fun loadInterpreter(): Interpreter {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }

        val interpreter = Interpreter(loadModelFile(), options)

        // Tell TFLite we'll use 128 tokens
        interpreter.resizeInput(0, intArrayOf(1, 128))
        interpreter.resizeInput(1, intArrayOf(1, 128))

        interpreter.allocateTensors()

        return interpreter
    }
}