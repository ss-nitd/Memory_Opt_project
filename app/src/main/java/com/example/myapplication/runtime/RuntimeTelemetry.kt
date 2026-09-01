package com.example.myapplication.runtime

data class EngineStorageSnapshot(
    val heapTensorBytes: Long = 0,
    val sharedTensorBytes: Long = 0,
    val heapModelBytes: Long = 0,
    val mappedModelBytes: Long = 0
)

data class EngineRuntimeSnapshot(
    val engineCount: Int = 0,
    val heapTensorBytes: Long = 0,
    val sharedTensorBytes: Long = 0,
    val heapModelBytes: Long = 0,
    val mappedModelBytes: Long = 0
)

data class ThermalSnapshot(
    val headroom: Float,
    val status: Int
)
