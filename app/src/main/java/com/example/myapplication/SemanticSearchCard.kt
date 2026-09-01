package com.example.myapplication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.runtime.SemanticSearchResult
import java.util.Locale

data class SemanticSearchUiState(
    val isRunning: Boolean = false,
    val enginesReady: Int = 0,
    val engineTarget: Int = 0,
    val processedChunks: Int = 0,
    val totalChunks: Int = 0,
    val result: SemanticSearchResult? = null,
    val error: String? = null
)

@Composable
fun SemanticSearchCard(
    state: SemanticSearchUiState,
    onSearch: (document: String, query: String) -> Unit
) {
    var document by rememberSaveable { mutableStateOf(SAMPLE_DOCUMENT) }
    var query by rememberSaveable { mutableStateOf(SAMPLE_QUERY) }
    val wordCount = document.trim().split(Regex("\\s+")).count(String::isNotEmpty)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "On-device semantic search",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Twelve on-device MiniLM engines search in parallel using the selected memory architecture.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = document,
                onValueChange = { document = it },
                label = { Text("Document ($wordCount words)") },
                minLines = 4,
                maxLines = 6,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Question") },
                maxLines = 2,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { onSearch(document, query) },
                    enabled = !state.isRunning && document.isNotBlank() && query.isNotBlank()
                ) {
                    Text("Find relevant passage")
                }

                if (state.isRunning) {
                    CircularProgressIndicator()
                }
            }

            if (state.isRunning) {
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        state.totalChunks > 0 -> {
                            "Embedded ${state.processedChunks} of ${state.totalChunks} chunks " +
                                "across ${state.engineTarget} engines"
                        }
                        state.engineTarget > 0 -> {
                            "Initializing engine ${state.enginesReady} of ${state.engineTarget}…"
                        }
                        else -> "Loading the local model…"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(10.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            state.result?.let { result ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Best matching passage", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(result.bestPassage, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Cosine similarity: ${formatSimilarity(result.cosineSimilarity)}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            "${result.enginesUsed}/${result.enginePoolSize} engines • " +
                                "${result.chunksScanned}/${result.totalChunks} chunks • " +
                                "${result.tokensProcessed} tokens • " +
                                "${result.embeddingDimensions}-D • " +
                                formatSearchDuration(result.endToEndNanos),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            if (result.mappedModelBytes > 0) {
                                "mmap: ${formatStorageBytes(result.mappedModelBytes)} virtual / " +
                                    "${formatStorageBytes(result.uniqueMappedModelBytes)} unique model file"
                            } else {
                                "Copied model buffers: ${formatStorageBytes(result.copiedModelBytes)}"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            if (result.sharedTensorBytes > 0) {
                                "Shared tensor I/O: ${formatStorageBytes(result.sharedTensorBytes)}"
                            } else {
                                "Heap tensor I/O: ${formatStorageBytes(result.heapTensorBytes)}"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (result.poolInitializationNanos > 0) {
                            Text(
                                "First-run pool initialization: " +
                                    formatSearchDuration(result.poolInitializationNanos),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "App PSS includes LiteRT arenas and resident mapped pages; mmap bytes are virtual capacity.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun formatSimilarity(similarity: Float): String = String.format(
    Locale.getDefault(),
    "%.3f",
    similarity
)

private fun formatSearchDuration(nanos: Long): String = String.format(
    Locale.getDefault(),
    "%.1f ms",
    nanos / 1_000_000.0
)

private fun formatStorageBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> String.format(
        Locale.getDefault(),
        "%.1f KiB",
        bytes / 1_024.0
    )
    else -> String.format(
        Locale.getDefault(),
        "%.1f MiB",
        bytes / (1_024.0 * 1_024.0)
    )
}

private const val SAMPLE_QUERY = "How can Android avoid loading every model copy into memory?"

private val SAMPLE_PASSAGES = listOf(
    """
    Android applications often run several AI features in one process. Loading a separate model byte array for every engine increases managed heap use and makes garbage collection more expensive. File-backed memory mapping lets those engines reference the same model pages. Android faults pages in when inference touches them and can reclaim clean pages later.
    """.trimIndent(),
    """
    Tensor buffers have a different lifecycle because inference writes into them. Shared memory makes those buffers directly addressable outside the managed Java heap. If inference is sequential, a reusable tensor arena can reduce the number of simultaneously resident scratch buffers even further.
    """.trimIndent(),
    """
    Thermal throttling is a separate concern. A fixed amount of CPU work makes slowdown observable as increasing latency, while Android thermal headroom reports how close the device is to severe throttling. Emulator values validate API plumbing, but sustained thermal behavior must be measured on physical hardware.
    """.trimIndent(),
    """
    A small restaurant near the coast makes fresh pasta every morning. The chef mixes flour and eggs, rests the dough, and rolls it into thin sheets before service. Seasonal vegetables and local herbs determine the menu.
    """.trimIndent(),
    """
    Long-distance runners manage effort by tracking pace, hydration, and recovery. Training plans alternate difficult sessions with easier days so athletes can adapt without accumulating excessive fatigue.
    """.trimIndent(),
    """
    Mobile inference pools must balance throughput with memory and CPU limits. Assigning independent document chunks to thread-confined interpreters allows real parallel work without invoking one LiteRT interpreter concurrently from multiple threads.
    """.trimIndent()
)

private val SAMPLE_DOCUMENT = List(4) { pass ->
    SAMPLE_PASSAGES.mapIndexed { index, passage ->
        "Benchmark pass ${pass + 1}, passage ${index + 1}. $passage"
    }.joinToString("\n\n")
}.joinToString("\n\n")
