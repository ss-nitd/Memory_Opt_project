package com.example.myapplication

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var memoryPressureMonitor: MemoryPressureMonitor
    private lateinit var thermalMonitor: ThermalMonitor
    private var semanticSearchEngine: SemanticSearchEngine? = null

    private var allowNewEngines = true
    private var criticalCleanupPerformed = false

    // Telemetry State
    private var engineRuntimeState = mutableStateOf(EngineRuntimeSnapshot())
    private var javaHeapBytesState = mutableLongStateOf(0L)
    private var appPssBytesState = mutableLongStateOf(0L)
    private var systemMemoryState = mutableStateOf<MemorySnapshot?>(null)
    private var thermalState = mutableStateOf(
        ThermalSnapshot(Float.NaN, PowerManager.THERMAL_STATUS_NONE)
    )
    private var allowNewEnginesState = mutableStateOf(true)
    private var latestBatchTimeNanos = mutableLongStateOf(0L)
    private var semanticSearchState = mutableStateOf(SemanticSearchUiState())

    // Architecture Toggles
    private var useDirectBuffers = mutableStateOf(true)
    private var useMmap = mutableStateOf(true)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        memoryPressureMonitor = MemoryPressureMonitor(this).apply { register() }
        thermalMonitor = ThermalMonitor(this)

        observeMemoryPressure()
        startTelemetrySampling()
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                val pressure by memoryPressureMonitor.pressure.collectAsState(initial = MemoryPressure.NORMAL)

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AiMemoryDashboard(
                        modifier = Modifier.padding(innerPadding),
                        pressure = pressure,
                        engineRuntime = engineRuntimeState.value,
                        javaHeapBytes = javaHeapBytesState.longValue,
                        appPssBytes = appPssBytesState.longValue,
                        systemMemory = systemMemoryState.value,
                        thermal = thermalState.value,
                        allowNewEngines = allowNewEnginesState.value,
                        latestBatchTimeNanos = latestBatchTimeNanos.longValue,
                        useDirectBuffers = useDirectBuffers.value,
                        useMmap = useMmap.value,
                        semanticSearchState = semanticSearchState.value,
                        onToggleDirectBuffers = { updateConfig(it, useMmap.value) },
                        onToggleMmap = { updateConfig(useDirectBuffers.value, it) },
                        onRunSemanticSearch = ::runSemanticSearch
                    )
                }
            }
        }
    }

    private fun updateConfig(direct: Boolean, mmap: Boolean) {
        if (semanticSearchState.value.isRunning) return

        semanticSearchEngine?.close()
        semanticSearchEngine = null
        useDirectBuffers.value = direct
        useMmap.value = mmap

        allowNewEngines = true
        allowNewEnginesState.value = true
        criticalCleanupPerformed = false
        engineRuntimeState.value = EngineRuntimeSnapshot()
        latestBatchTimeNanos.longValue = 0L
        semanticSearchState.value = SemanticSearchUiState()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun observeMemoryPressure() {
        lifecycleScope.launch {
            memoryPressureMonitor.pressure.collect { pressure ->
                if (pressure == MemoryPressure.CRITICAL) {
                    allowNewEngines = false
                    allowNewEnginesState.value = false
                    if (!criticalCleanupPerformed && !semanticSearchState.value.isRunning) {
                        criticalCleanupPerformed = true
                        semanticSearchEngine?.close()
                        semanticSearchEngine = null
                        engineRuntimeState.value = EngineRuntimeSnapshot()
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startTelemetrySampling() {
        lifecycleScope.launch {
            while (isActive) {
                val runtime = Runtime.getRuntime()
                javaHeapBytesState.longValue = runtime.totalMemory() - runtime.freeMemory()

                val appMemoryInfo = Debug.MemoryInfo()
                Debug.getMemoryInfo(appMemoryInfo)
                appPssBytesState.longValue = appMemoryInfo.totalPss.toLong() * BYTES_PER_KIBIBYTE

                systemMemoryState.value = memoryPressureMonitor.refreshSystemMemory()
                thermalState.value = thermalMonitor.snapshot()

                delay(TELEMETRY_INTERVAL_MS)
            }
        }
    }

    private fun runSemanticSearch(document: String, query: String) {
        if (semanticSearchState.value.isRunning || !allowNewEngines) return
        semanticSearchState.value = SemanticSearchUiState(
            isRunning = true,
            enginesReady = engineRuntimeState.value.engineCount,
            engineTarget = MAX_ENGINE_COUNT
        )
        val mmapEnabled = useMmap.value
        val sharedMemoryEnabled = useDirectBuffers.value

        lifecycleScope.launch {
            val startNanos = SystemClock.elapsedRealtimeNanos()
            try {
                val result = withContext(Dispatchers.Default) {
                    var poolInitializationNanos = 0L
                    val engine = semanticSearchEngine ?: run {
                        val initializationStart = SystemClock.elapsedRealtimeNanos()
                        SemanticSearchEngine(
                            context = applicationContext,
                            engineCount = MAX_ENGINE_COUNT,
                            useMmap = mmapEnabled,
                            useSharedMemory = sharedMemoryEnabled
                        ) { ready, total ->
                            runOnUiThread {
                                semanticSearchState.value = semanticSearchState.value.copy(
                                    enginesReady = ready,
                                    engineTarget = total
                                )
                            }
                        }.also {
                            poolInitializationNanos =
                                SystemClock.elapsedRealtimeNanos() - initializationStart
                            semanticSearchEngine = it
                            runOnUiThread {
                                engineRuntimeState.value = it.runtimeSnapshot()
                            }
                        }
                    }
                    engine.search(document, query) { processed, total ->
                        runOnUiThread {
                            semanticSearchState.value = semanticSearchState.value.copy(
                                processedChunks = processed,
                                totalChunks = total
                            )
                        }
                    }.copy(
                        poolInitializationNanos = poolInitializationNanos,
                        endToEndNanos = SystemClock.elapsedRealtimeNanos() - startNanos
                    )
                }
                latestBatchTimeNanos.longValue = result.endToEndNanos
                semanticSearchState.value = SemanticSearchUiState(result = result)
            } catch (outOfMemory: OutOfMemoryError) {
                semanticSearchEngine = null
                engineRuntimeState.value = EngineRuntimeSnapshot()
                Runtime.getRuntime().gc()
                semanticSearchState.value = SemanticSearchUiState(
                    error = "The copied-model configuration could not fit a 12-engine pool. " +
                        "Enable file-backed model mapping and try again."
                )
            } catch (exception: Exception) {
                semanticSearchState.value = SemanticSearchUiState(
                    error = exception.message ?: exception.javaClass.simpleName
                )
            }
        }
    }

    override fun onDestroy() {
        semanticSearchEngine?.close()
        memoryPressureMonitor.unregister()
        super.onDestroy()
    }

    private companion object {
        const val MAX_ENGINE_COUNT = 12
        const val TELEMETRY_INTERVAL_MS = 1_000L
        const val BYTES_PER_KIBIBYTE = 1_024L
    }
}

@Composable
fun AiMemoryDashboard(
    modifier: Modifier = Modifier,
    pressure: MemoryPressure,
    engineRuntime: EngineRuntimeSnapshot,
    javaHeapBytes: Long,
    appPssBytes: Long,
    systemMemory: MemorySnapshot?,
    thermal: ThermalSnapshot,
    allowNewEngines: Boolean,
    latestBatchTimeNanos: Long,
    useDirectBuffers: Boolean,
    useMmap: Boolean,
    semanticSearchState: SemanticSearchUiState,
    onToggleDirectBuffers: (Boolean) -> Unit,
    onToggleMmap: (Boolean) -> Unit,
    onRunSemanticSearch: (document: String, query: String) -> Unit
) {
    val (statusColor, statusText) = when (pressure) {
        MemoryPressure.NORMAL -> Color(0xFF4CAF50) to "NORMAL"
        MemoryPressure.LOW -> Color(0xFFFF9800) to "LOW"
        MemoryPressure.CRITICAL -> Color(0xFFF44336) to "CRITICAL"
        MemoryPressure.MODERATE -> Color(0xFFFFC107) to "MODERATE"
    }

    val directSubtitle = when {
        engineRuntime.sharedTensorBytes > 0 -> {
            "${formatStorageBytes(engineRuntime.sharedTensorBytes)} shared I/O mapping"
        }
        engineRuntime.heapTensorBytes > 0 -> {
            "${formatStorageBytes(engineRuntime.heapTensorBytes)} managed I/O arrays"
        }
        else -> "Used by the next 12-engine search"
    }

    val mmapSubtitle = when {
        engineRuntime.mappedModelBytes > 0 -> {
            "${formatBytes(engineRuntime.mappedModelBytes)} file-backed mapping"
        }
        engineRuntime.heapModelBytes > 0 -> {
            "${formatBytes(engineRuntime.heapModelBytes)} copied model buffers"
        }
        else -> "Used by the next 12-engine search"
    }

    val thermalStatus = thermalStatusLabel(thermal.status)
    val workloadStatus = when {
        !allowNewEngines -> "Critical memory callback: allocations halted"
        semanticSearchState.isRunning -> {
            "Parallel search active: ${semanticSearchState.enginesReady}/${semanticSearchState.engineTarget} engines ready"
        }
        engineRuntime.engineCount == 0 -> "Run a search to initialize the 12-engine MiniLM pool"
        else -> "MiniLM pool ready: ${engineRuntime.engineCount} engines"
    }
    val runtimeDetails = buildString {
        append("Thermal status: $thermalStatus")
        systemMemory?.let { memory ->
            append("\nSystem available: ${formatBytes(memory.availableBytes)} / ${formatBytes(memory.totalBytes)}")
            append("\nSystem low-memory threshold: ${formatBytes(memory.thresholdBytes)}")
        }
        if (engineRuntime.engineCount > 0) {
            append("\nModel mappings: ${formatBytes(engineRuntime.mappedModelBytes)}")
            append("\nCopied model buffers: ${formatBytes(engineRuntime.heapModelBytes)}")
            append("\nShared tensor I/O: ${formatStorageBytes(engineRuntime.sharedTensorBytes)}")
            append("\nHeap tensor I/O: ${formatStorageBytes(engineRuntime.heapTensorBytes)}")
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(modifier = Modifier.fillMaxWidth().height(90.dp), colors = CardDefaults.cardColors(containerColor = statusColor)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(statusText, style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Black)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricBox("Engines", "${engineRuntime.engineCount}")
            MetricBox("Java Heap", formatBytes(javaHeapBytes))
            MetricBox("App PSS", formatBytes(appPssBytes))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricBox("Search", formatDuration(latestBatchTimeNanos))
            MetricBox("Avail RAM", systemMemory?.let { formatBytes(it.availableBytes) } ?: "N/A")
            MetricBox(
                "Headroom 10s",
                if (thermal.headroom.isNaN()) "N/A" else String.format(
                    Locale.getDefault(),
                    "%.3f",
                    thermal.headroom
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            workloadStatus,
            color = if (allowNewEngines) Color.Gray else Color.Red,
            fontWeight = if (allowNewEngines) FontWeight.Normal else FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Live Search") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Memory") }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (selectedTab == 0) {
            SemanticSearchCard(semanticSearchState, onRunSemanticSearch)
        } else {
            Surface(
                color = Color(0xFF1E1E1E),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Text(
                    text = runtimeDetails,
                    color = Color(0xFF00FF00),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Architecture Controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            ToggleRow(
                "Shared Tensor I/O",
                directSubtitle,
                useDirectBuffers,
                enabled = !semanticSearchState.isRunning,
                onCheckedChange = onToggleDirectBuffers
            )
            ToggleRow(
                "File-backed Model Mapping",
                mmapSubtitle,
                useMmap,
                enabled = !semanticSearchState.isRunning,
                onCheckedChange = onToggleMmap
            )
        }
    }
}

@Composable
fun MetricBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)

            val subtitleColor = if (checked) Color(0xFF2E7D32) else Color.Gray
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

private fun formatBytes(bytes: Long): String = String.format(
    Locale.getDefault(),
    "%.1f MiB",
    bytes.toDouble() / (1_024.0 * 1_024.0)
)

private fun formatStorageBytes(bytes: Long): String = if (bytes < 1_024L * 1_024L) {
    String.format(Locale.getDefault(), "%.1f KiB", bytes / 1_024.0)
} else {
    formatBytes(bytes)
}

private fun formatDuration(nanos: Long): String = if (nanos == 0L) {
    "N/A"
} else {
    String.format(Locale.getDefault(), "%.1f ms", nanos / 1_000_000.0)
}

private fun thermalStatusLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "NONE"
    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
    else -> "UNKNOWN ($status)"
}
