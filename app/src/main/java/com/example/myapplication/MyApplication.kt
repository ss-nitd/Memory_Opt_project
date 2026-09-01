package com.example.myapplication

import android.app.Application
import com.example.myapplication.runtime.MemoryPressureMonitor

class MyApplication : Application() {

    lateinit var memoryPressureMonitor: MemoryPressureMonitor
        private set

    override fun onCreate() {
        super.onCreate()

        memoryPressureMonitor = MemoryPressureMonitor(this)
        memoryPressureMonitor.register()
    }
}