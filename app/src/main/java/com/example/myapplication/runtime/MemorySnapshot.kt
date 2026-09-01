package com.example.myapplication.runtime

data class MemorySnapshot(
    val availableBytes: Long,
    val totalBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
    val pressure: MemoryPressure
)