package com.example.myapplication.runtime

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi

class ThermalMonitor(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @RequiresApi(Build.VERSION_CODES.R)
    fun snapshot(): ThermalSnapshot = ThermalSnapshot(
        headroom = powerManager.getThermalHeadroom(FORECAST_SECONDS),
        status = powerManager.currentThermalStatus
    )

    private companion object {
        const val FORECAST_SECONDS = 10
    }
}
