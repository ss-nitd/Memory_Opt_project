package com.example.myapplication.runtime

import android.content.Context
import android.os.SharedMemory
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

data class SemanticSearchResult(
    val bestPassage: String,
    val cosineSimilarity: Float,
    val chunksScanned: Int,
    val totalChunks: Int,
    val tokensProcessed: Int,
    val documentWordCount: Int,
    val embeddingDimensions: Int,
    val enginePoolSize: Int,
    val enginesUsed: Int,
    val mappedModelBytes: Long,
    val copiedModelBytes: Long,
    val uniqueMappedModelBytes: Long,
    val sharedTensorBytes: Long,
    val heapTensorBytes: Long,
    val poolInitializationNanos: Long = 0,
    val endToEndNanos: Long = 0
)

class SemanticSearchEngine(
    context: Context,
    engineCount: Int = DEFAULT_ENGINE_COUNT,
    useMmap: Boolean = true,
    useSharedMemory: Boolean = true,
    onEngineReady: (ready: Int, total: Int) -> Unit = { _, _ -> }
) : Closeable {
    private val workers: List<EmbeddingWorker>
    private val executor = Executors.newFixedThreadPool(engineCount) { runnable ->
        Thread(runnable, "semantic-search-worker").apply { isDaemon = true }
    }
    private val tokenizer: WordPieceTokenizer
    private val sequenceLength: Int
    private val embeddingDimensions: Int

    init {
        require(engineCount > 0) { "Engine count must be positive" }

        val createdWorkers = mutableListOf<EmbeddingWorker>()
        try {
            repeat(engineCount) { index ->
                val handle = ModelLoader(context).loadInterpreterHandle(
                    useMmap = useMmap,
                    // One LiteRT thread per interpreter prevents a 12-engine pool
                    // from silently expanding into 48 runnable inference threads.
                    numThreads = 1
                )
                try {
                    createdWorkers += EmbeddingWorker(
                        id = index,
                        handle = handle,
                        useSharedMemory = useSharedMemory
                    )
                } catch (failure: Throwable) {
                    handle.close()
                    throw failure
                }
                onEngineReady(index + 1, engineCount)
            }
        } catch (failure: Throwable) {
            createdWorkers.forEach(EmbeddingWorker::close)
            executor.shutdownNow()
            throw failure
        }

        workers = createdWorkers
        sequenceLength = workers.first().sequenceLength
        embeddingDimensions = workers.first().embeddingDimensions
        require(workers.all { it.sequenceLength == sequenceLength }) {
            "Every interpreter must expose the same input shape"
        }
        require(workers.all { it.embeddingDimensions == embeddingDimensions }) {
            "Every interpreter must expose the same output shape"
        }
        tokenizer = WordPieceTokenizer.fromAssets(context, sequenceLength)
    }

    fun runtimeSnapshot(): EngineRuntimeSnapshot = EngineRuntimeSnapshot(
        engineCount = workers.size,
        heapTensorBytes = workers.sumOf { it.heapTensorBytes },
        sharedTensorBytes = workers.sumOf { it.sharedTensorBytes },
        heapModelBytes = workers.filterNot { it.modelIsMapped }.sumOf { it.modelBytes },
        mappedModelBytes = workers.filter { it.modelIsMapped }.sumOf { it.modelBytes }
    )

    @Synchronized
    fun search(
        document: String,
        query: String,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): SemanticSearchResult {
        require(document.isNotBlank()) { "Document cannot be empty" }
        require(query.isNotBlank()) { "Query cannot be empty" }

        val allChunks = chunkDocument(document)
        require(allChunks.isNotEmpty()) { "Document does not contain searchable text" }
        val chunks = allChunks.take(MAX_SEARCH_CHUNKS)
        onProgress(0, chunks.size)

        val queryTokens = tokenizer.encode(query)
        val queryEmbedding = workers.first().embed(queryTokens)
        val completedChunks = AtomicInteger(0)
        val assignments = chunks.withIndex().groupBy { it.index % workers.size }

        val futures = assignments.map { (workerIndex, indexedChunks) ->
            executor.submit(Callable {
                val worker = workers[workerIndex]
                indexedChunks.map { indexedPassage ->
                    val tokens = tokenizer.encode(indexedPassage.value)
                    val embedding = worker.embed(tokens)
                    val score = cosineSimilarity(queryEmbedding, embedding)
                    onProgress(completedChunks.incrementAndGet(), chunks.size)
                    ScoredPassage(
                        text = indexedPassage.value,
                        similarity = score,
                        tokenCount = tokens.tokenCount
                    )
                }
            })
        }

        val scoredPassages = futures.flatMap { it.get() }
        val best = scoredPassages.maxBy { it.similarity }
        val storage = runtimeSnapshot()

        return SemanticSearchResult(
            bestPassage = best.text,
            cosineSimilarity = best.similarity,
            chunksScanned = chunks.size,
            totalChunks = allChunks.size,
            tokensProcessed = queryTokens.tokenCount + scoredPassages.sumOf { it.tokenCount },
            documentWordCount = document.trim().split(WHITESPACE).count(String::isNotEmpty),
            embeddingDimensions = embeddingDimensions,
            enginePoolSize = workers.size,
            enginesUsed = assignments.size,
            mappedModelBytes = storage.mappedModelBytes,
            copiedModelBytes = storage.heapModelBytes,
            uniqueMappedModelBytes = if (storage.mappedModelBytes > 0) {
                workers.first().modelBytes
            } else {
                0L
            },
            sharedTensorBytes = storage.sharedTensorBytes,
            heapTensorBytes = storage.heapTensorBytes
        )
    }

    @Synchronized
    override fun close() {
        executor.shutdownNow()
        workers.forEach(EmbeddingWorker::close)
    }

    private fun chunkDocument(document: String): List<String> {
        val paragraphs = document
            .trim()
            .split(PARAGRAPH_BREAK)
            .map(String::trim)
            .filter(String::isNotEmpty)

        return paragraphs.flatMap { paragraph ->
            val words = paragraph.split(WHITESPACE).filter(String::isNotEmpty)
            words.chunked(MAX_WORDS_PER_CHUNK).map { it.joinToString(" ") }
        }
    }

    private data class ScoredPassage(
        val text: String,
        val similarity: Float,
        val tokenCount: Int
    )

    private class EmbeddingWorker(
        id: Int,
        private val handle: LoadedInterpreter,
        useSharedMemory: Boolean
    ) : Closeable {
        val sequenceLength = handle.interpreter.getInputTensor(0).shape().last()
        val embeddingDimensions = handle.interpreter.getOutputTensor(0).shape().last()
        val modelBytes = handle.modelBytes
        val modelIsMapped = handle.isMapped
        private val io: TensorIo = if (useSharedMemory) {
            SharedTensorIo(id, sequenceLength, embeddingDimensions)
        } else {
            HeapTensorIo(sequenceLength, embeddingDimensions)
        }
        val sharedTensorBytes get() = io.sharedBytes
        val heapTensorBytes get() = io.heapBytes

        fun embed(tokens: TokenizedText): FloatArray = normalize(
            io.run(handle.interpreter, tokens)
        )

        override fun close() {
            io.close()
            handle.close()
        }
    }

    private interface TensorIo : Closeable {
        val sharedBytes: Long
        val heapBytes: Long
        fun run(interpreter: Interpreter, tokens: TokenizedText): FloatArray
    }

    private class HeapTensorIo(
        sequenceLength: Int,
        embeddingDimensions: Int
    ) : TensorIo {
        private val inputIds = Array(1) { IntArray(sequenceLength) }
        private val attentionMask = Array(1) { IntArray(sequenceLength) }
        private val output = Array(1) { FloatArray(embeddingDimensions) }
        override val sharedBytes = 0L
        override val heapBytes = (
            sequenceLength * INT_BYTES * 2L + embeddingDimensions * FLOAT_BYTES
        )

        override fun run(interpreter: Interpreter, tokens: TokenizedText): FloatArray {
            tokens.inputIds.copyInto(inputIds[0])
            tokens.attentionMask.copyInto(attentionMask[0])
            interpreter.runForMultipleInputsOutputs(
                arrayOf(inputIds, attentionMask),
                mutableMapOf<Int, Any>(0 to output)
            )
            return output[0].clone()
        }

        override fun close() = Unit
    }

    private class SharedTensorIo(
        id: Int,
        sequenceLength: Int,
        private val embeddingDimensions: Int
    ) : TensorIo {
        private val inputBytes = sequenceLength * INT_BYTES
        private val outputBytes = embeddingDimensions * FLOAT_BYTES
        private val regionBytes = inputBytes * 2 + outputBytes
        private val sharedMemory = SharedMemory.create("semantic_tensor_$id", regionBytes)
        private val mapping = sharedMemory.mapReadWrite().order(ByteOrder.nativeOrder())
        private val inputIds = mapping.sliceAt(0, inputBytes)
        private val attentionMask = mapping.sliceAt(inputBytes, inputBytes)
        private val output = mapping.sliceAt(inputBytes * 2, outputBytes)
        override val sharedBytes = regionBytes.toLong()
        override val heapBytes = 0L

        override fun run(interpreter: Interpreter, tokens: TokenizedText): FloatArray {
            inputIds.clear()
            tokens.inputIds.forEach(inputIds::putInt)
            inputIds.rewind()

            attentionMask.clear()
            tokens.attentionMask.forEach(attentionMask::putInt)
            attentionMask.rewind()
            output.clear()

            interpreter.runForMultipleInputsOutputs(
                arrayOf(inputIds, attentionMask),
                mutableMapOf<Int, Any>(0 to output)
            )

            return FloatArray(embeddingDimensions) { index ->
                output.getFloat(index * FLOAT_BYTES)
            }
        }

        override fun close() {
            SharedMemory.unmap(mapping)
            sharedMemory.close()
        }
    }

    private companion object {
        const val DEFAULT_ENGINE_COUNT = 12
        const val MAX_WORDS_PER_CHUNK = 80
        const val MAX_SEARCH_CHUNKS = 32
        const val INT_BYTES = Int.SIZE_BYTES
        const val FLOAT_BYTES = Float.SIZE_BYTES
        val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
        val WHITESPACE = Regex("\\s+")

        fun normalize(values: FloatArray): FloatArray {
            var squaredMagnitude = 0.0
            values.forEach { value -> squaredMagnitude += value * value }
            val magnitude = sqrt(squaredMagnitude).toFloat()
            if (magnitude == 0.0f) return values
            return FloatArray(values.size) { index -> values[index] / magnitude }
        }

        fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
            var dotProduct = 0.0f
            for (index in first.indices) dotProduct += first[index] * second[index]
            return dotProduct
        }

        fun ByteBuffer.sliceAt(offset: Int, byteCount: Int): ByteBuffer {
            return duplicate()
                .order(ByteOrder.nativeOrder())
                .apply {
                    position(offset)
                    limit(offset + byteCount)
                }
                .slice()
                .order(ByteOrder.nativeOrder())
        }
    }
}
