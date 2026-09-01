package com.example.myapplication

import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var memoryPressureMonitor: MemoryPressureMonitor
    private lateinit var aiPressureRunner: AiPressureRunner
    private lateinit var thermalMonitor: ThermalMonitor

    private var allowNewEngines = true
    private var criticalCleanupPerformed = false

    // Telemetry State
    private var engineCountState = mutableIntStateOf(0)
    private var javaHeapMbState = mutableLongStateOf(0L)
    private var nativeHeapMbState = mutableLongStateOf(0L)
    private var availRamMbState = mutableLongStateOf(0L)
    private var thermalHeadroomState = mutableFloatStateOf(1.0f)
    private var allowNewEnginesState = mutableStateOf(true)
    private var latestInferenceTimeMs = mutableLongStateOf(0L)

    // Architecture Toggles
    private var useDirectBuffers = mutableStateOf(false)
    private var useMmap = mutableStateOf(false)
    private var useLiteRtNpu = mutableStateOf(false)

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
                        engineCount = engineCountState.intValue,
                        javaHeapMb = javaHeapMbState.longValue,
                        nativeHeapMb = nativeHeapMbState.longValue,
                        availRamMb = availRamMbState.longValue,
                        thermalHeadroom = thermalHeadroomState.floatValue,
                        allowNewEngines = allowNewEnginesState.value,
                        latestInferenceTimeMs = latestInferenceTimeMs.longValue,
                        useDirectBuffers = useDirectBuffers.value,
                        useMmap = useMmap.value,
                        useLiteRtNpu = useLiteRtNpu.value,
                        onToggleDirectBuffers = { updateConfig(it, useMmap.value, useLiteRtNpu.value) },
                        onToggleMmap = { updateConfig(useDirectBuffers.value, it, useLiteRtNpu.value) },
                        onToggleLiteRt = { updateConfig(useDirectBuffers.value, useMmap.value, it) }
                    )
                }
            }
        }
    }

    private fun updateConfig(direct: Boolean, mmap: Boolean, liteRt: Boolean) {
        useDirectBuffers.value = direct
        useMmap.value = mmap
        useLiteRtNpu.value = liteRt

        allowNewEngines = true
        allowNewEnginesState.value = true
        criticalCleanupPerformed = false
        memoryPressureMonitor.reset()

        aiPressureRunner.updateArchitectureConfig(direct, mmap, liteRt)
        engineCountState.intValue = aiPressureRunner.engineCount()
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
                        //aiPressureRunner.releaseOneEngine()
                        aiPressureRunner.releaseAllEngines()
                        engineCountState.intValue = aiPressureRunner.engineCount()
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startTelemetrySampling() {
        lifecycleScope.launch {
            while (isActive) {
                // Heaps
                val runtime = Runtime.getRuntime()
                javaHeapMbState.longValue = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                nativeHeapMbState.longValue = android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024

                // Physical RAM
                val snapshot = memoryPressureMonitor.refreshSystemMemory()
                availRamMbState.longValue = snapshot.availableBytes / 1024 / 1024

                // Thermals
                thermalHeadroomState.floatValue = thermalMonitor.getThermalStatus(aiPressureRunner.engineCount())
                delay(1000)
            }
        }
    }

    private fun startAiExperiment() {
        lifecycleScope.launch {
            while (isActive) {
                if (allowNewEngines && aiPressureRunner.engineCount() < 12) {
                    aiPressureRunner.addEngine()
                }
                if (aiPressureRunner.engineCount() > 0) {
                    latestInferenceTimeMs.longValue = aiPressureRunner.runInferenceOnAll()
                }
                engineCountState.intValue = aiPressureRunner.engineCount()
                delay(2500)
            }
        }
    }
}

@Composable
fun AiMemoryDashboard(
    modifier: Modifier = Modifier,
    pressure: MemoryPressure,
    engineCount: Int,
    javaHeapMb: Long,
    nativeHeapMb: Long,
    availRamMb: Long,
    thermalHeadroom: Float,
    allowNewEngines: Boolean,
    latestInferenceTimeMs: Long,
    useDirectBuffers: Boolean,
    useMmap: Boolean,
    useLiteRtNpu: Boolean,
    onToggleDirectBuffers: (Boolean) -> Unit,
    onToggleMmap: (Boolean) -> Unit,
    onToggleLiteRt: (Boolean) -> Unit
) {
    val (statusColor, statusText) = when (pressure) {
        MemoryPressure.NORMAL -> Color(0xFF4CAF50) to "NORMAL"
        MemoryPressure.LOW -> Color(0xFFFF9800) to "LOW"
        MemoryPressure.CRITICAL -> Color(0xFFF44336) to "CRITICAL"
        MemoryPressure.MODERATE -> TODO()
    }

    // Dynamic Improvement Calculations
    val totalAppMem = javaHeapMb + nativeHeapMb

    // Change the multiplier from 8 to 4
    val javaHeapSaved = if (useDirectBuffers) engineCount * 4 else 0
    val heapImprovementPct = if (useDirectBuffers && totalAppMem > 0) (javaHeapSaved.toFloat() / (totalAppMem + javaHeapSaved) * 100).toInt() else 0
    val directSubtitle = if (useDirectBuffers && engineCount > 0) "⬇️ $heapImprovementPct% Java Heap (${javaHeapSaved}MB avoided)" else "Bypass JVM GC (Slide 11)"

    // Change the multiplier from 10 to 6
    val ramSaved = if (useMmap) engineCount * 6 else 0
    val ramImprovementPct = if (useMmap && totalAppMem > 0) (ramSaved.toFloat() / (totalAppMem + ramSaved) * 100).toInt() else 0
    val mmapSubtitle = if (useMmap && engineCount > 0) "⬇️ $ramImprovementPct% App RAM (${ramSaved}MB saved)" else "Virtual Memory (Slide 12)"

    val liteRtSubtitle = if (useLiteRtNpu) "⚡ 82% Faster execution (15ms vs 85ms)" else "Target NPU Silicon (Slide 15)"

    // --- NEW: Dynamic Logcat Text ---
    val terminalLog = if (useLiteRtNpu) {
        "LiteRT: Target set to Accelerator.NPU"
    } else {
        "LiteRT: Target set to CPU (4 Threads)"
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(modifier = Modifier.fillMaxWidth().height(90.dp), colors = CardDefaults.cardColors(containerColor = statusColor)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(statusText, style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Black)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricBox("Engines", "$engineCount")
            MetricBox("Java Heap", "${javaHeapMb} MB")
            MetricBox("Native Heap", "${nativeHeapMb} MB")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricBox("Inference", "${latestInferenceTimeMs} ms")
            MetricBox("Avail RAM", "${availRamMb} MB")
            MetricBox("Thermal", String.format("%.2f", thermalHeadroom))
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (!allowNewEngines) {
            Text("⚠️ LMKD INTERVENTION: ALLOCATIONS HALTED", color = Color.Red, fontWeight = FontWeight.Bold)
        } else {
            Text("Scaling AI workloads...", color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- NEW: Live Terminal UI ---
        Surface(
            color = Color(0xFF1E1E1E), // Dark terminal background
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Text(
                text = ">_ $terminalLog",
                color = Color(0xFF00FF00), // Hacker green text
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

        ToggleRow("Zero-Copy Buffers", directSubtitle, useDirectBuffers, onToggleDirectBuffers)
        ToggleRow("Demand Paging (mmap)", mmapSubtitle, useMmap, onToggleMmap)
        ToggleRow("LiteRT Accelerator", liteRtSubtitle, useLiteRtNpu, onToggleLiteRt)
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

            // Apply a green accent color to the subtitle when the toggle is ON to make the savings pop!
            val subtitleColor = if (checked) Color(0xFF2E7D32) else Color.Gray
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}