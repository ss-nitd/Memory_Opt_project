package com.example.myapplication.runtime

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

internal class LoadedInterpreter(
    val interpreter: Interpreter,
    // Retain the model buffer for exactly as long as the native interpreter.
    // This is required for both mapped and copied model storage.
    val modelBuffer: ByteBuffer,
    val isMapped: Boolean
) : Closeable {
    val modelBytes: Long = modelBuffer.capacity().toLong()

    override fun close() {
        interpreter.close()
    }
}

class ModelLoader(
    private val context: Context
) {
    internal fun loadInterpreterHandle(
        useMmap: Boolean = true,
        numThreads: Int = 1
    ): LoadedInterpreter {
        require(numThreads > 0) { "Interpreter thread count must be positive" }

        val modelBuffer = if (useMmap) loadMappedModelFile() else loadCopiedModelFile()
        val options = Interpreter.Options().apply {
            setNumThreads(numThreads)
        }
        val interpreter = Interpreter(modelBuffer, options)

        // The bundled MiniLM model accepts two 128-token integer inputs.
        interpreter.resizeInput(0, intArrayOf(1, SEQUENCE_LENGTH))
        interpreter.resizeInput(1, intArrayOf(1, SEQUENCE_LENGTH))
        interpreter.allocateTensors()

        return LoadedInterpreter(interpreter, modelBuffer, useMmap)
    }

    private fun loadMappedModelFile(): MappedByteBuffer {
        return context.assets.openFd(MODEL_ASSET).use { asset ->
            asset.createInputStream().use { inputStream ->
                inputStream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    asset.startOffset,
                    asset.declaredLength
                ).apply {
                    order(ByteOrder.nativeOrder())
                }
            }
        }
    }

    private fun loadCopiedModelFile(): ByteBuffer {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }

    private companion object {
        const val MODEL_ASSET = "embedding_model.tflite"
        const val SEQUENCE_LENGTH = 128
    }
}
