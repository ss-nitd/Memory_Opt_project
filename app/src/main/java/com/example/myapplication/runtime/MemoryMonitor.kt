package com.example.myapplication.runtime

import android.os.Debug
import android.util.Log

object MemoryMonitor {

    fun logMemory(tag: String = "MEMORY") {

        val runtime = Runtime.getRuntime()

        val javaUsed =
            (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024

        val javaMax =
            runtime.maxMemory() / 1024 / 1024

        val nativeUsed =
            Debug.getNativeHeapAllocatedSize() / 1024 / 1024

        Log.d(tag, "Java Heap Used : ${javaUsed} MB")
        Log.d(tag, "Java Heap Max  : ${javaMax} MB")
        Log.d(tag, "Native Heap    : ${nativeUsed} MB")
    }
}