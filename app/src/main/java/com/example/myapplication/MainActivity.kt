package com.example.myapplication

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    private lateinit var aiPressureRunner: AiPressureRunner
    private lateinit var thermalMonitor: ThermalMonitor

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

    // Architecture Toggles
    private var useDirectBuffers = mutableStateOf(false)
    private var useMmap = mutableStateOf(false)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        memoryPressureMonitor = MemoryPressureMonitor(this).apply { register() }
        aiPressureRunner = AiPressureRunner(this)
        thermalMonitor = ThermalMonitor(this)

        observeMemoryPressure()
        startTelemetrySampling()
        startAiExperiment()
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
                        onToggleDirectBuffers = { updateConfig(it, useMmap.value) },
                        onToggleMmap = { updateConfig(useDirectBuffers.value, it) }
                    )
                }
            }
        }
    }

    private fun updateConfig(direct: Boolean, mmap: Boolean) {
        useDirectBuffers.value = direct
        useMmap.value = mmap

        allowNewEngines = true
        allowNewEnginesState.value = true
        criticalCleanupPerformed = false

        aiPressureRunner.updateArchitectureConfig(direct, mmap)
        engineRuntimeState.value = aiPressureRunner.runtimeSnapshot()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun observeMemoryPressure() {
        lifecycleScope.launch {
            memoryPressureMonitor.pressure.collect { pressure ->
                if (pressure == MemoryPressure.CRITICAL) {
                    allowNewEngines = false
                    allowNewEnginesState.value = false
                    if (!criticalCleanupPerformed && aiPressureRunner.engineCount() > 0) {
                        criticalCleanupPerformed = true
                        aiPressureRunner.releaseAllEngines()
                        engineRuntimeState.value = aiPressureRunner.runtimeSnapshot()
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

    private fun startAiExperiment() {
        lifecycleScope.launch {
            while (isActive) {
                if (allowNewEngines && aiPressureRunner.engineCount() < MAX_ENGINE_COUNT) {
                    aiPressureRunner.addEngine()
                }
                if (aiPressureRunner.engineCount() > 0) {
                    latestBatchTimeNanos.longValue = withContext(Dispatchers.Default) {
                        aiPressureRunner.runInferenceOnAllNanos()
                    }
                }
                engineRuntimeState.value = aiPressureRunner.runtimeSnapshot()
                delay(WORKLOAD_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val MAX_ENGINE_COUNT = 12
        const val TELEMETRY_INTERVAL_MS = 1_000L
        const val WORKLOAD_INTERVAL_MS = 250L
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
    onToggleDirectBuffers: (Boolean) -> Unit,
    onToggleMmap: (Boolean) -> Unit
) {
    val (statusColor, statusText) = when (pressure) {
        MemoryPressure.NORMAL -> Color(0xFF4CAF50) to "NORMAL"
        MemoryPressure.LOW -> Color(0xFFFF9800) to "LOW"
        MemoryPressure.CRITICAL -> Color(0xFFF44336) to "CRITICAL"
        MemoryPressure.MODERATE -> Color(0xFFFFC107) to "MODERATE"
    }

    val directSubtitle = when {
        engineRuntime.sharedTensorBytes > 0 -> {
            "${formatBytes(engineRuntime.sharedTensorBytes)} shared-memory mapping"
        }
        engineRuntime.heapTensorBytes > 0 -> {
            "${formatBytes(engineRuntime.heapTensorBytes)} managed-heap storage"
        }
        else -> "No tensor storage allocated"
    }

    val mmapSubtitle = when {
        engineRuntime.mappedModelBytes > 0 -> {
            "${formatBytes(engineRuntime.mappedModelBytes)} file-backed mapping"
        }
        engineRuntime.heapModelBytes > 0 -> {
            "${formatBytes(engineRuntime.heapModelBytes)} managed-heap model"
        }
        else -> "No model storage allocated"
    }

    val thermalStatus = thermalStatusLabel(thermal.status)
    val workloadStatus = when {
        !allowNewEngines -> "Critical memory callback: allocations halted"
        engineRuntime.engineCount == 0 -> "Starting CPU workload"
        else -> "CPU workload active: ${engineRuntime.engineCount} engines"
    }
    val runtimeDetails = buildString {
        append("Thermal status: $thermalStatus")
        systemMemory?.let { memory ->
            append("\nSystem available: ${formatBytes(memory.availableBytes)} / ${formatBytes(memory.totalBytes)}")
            append("\nSystem low-memory threshold: ${formatBytes(memory.thresholdBytes)}")
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            MetricBox("Batch", formatDuration(latestBatchTimeNanos))
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
        HorizontalDivider(color = Color.LightGray)
        Spacer(modifier = Modifier.height(12.dp))

        Text("Architecture Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        ToggleRow("Shared Tensor Memory", directSubtitle, useDirectBuffers, onToggleDirectBuffers)
        ToggleRow("File-backed Model Mapping", mmapSubtitle, useMmap, onToggleMmap)
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
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)

            val subtitleColor = if (checked) Color(0xFF2E7D32) else Color.Gray
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatBytes(bytes: Long): String = String.format(
    Locale.getDefault(),
    "%.1f MiB",
    bytes.toDouble() / (1_024.0 * 1_024.0)
)

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
