package com.example.myapplication.runtime

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi

class ThermalMonitor(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @RequiresApi(Build.VERSION_CODES.R)
    fun getThermalStatus(engineCount: Int): Float {
        val rawHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.getThermalHeadroom(10)
        } else Float.NaN

        // Emulates thermal decay as engine count rises (perfect for emulator recording)
        return if (rawHeadroom.isNaN() || rawHeadroom == 1.0f) {
            (1.0f - (engineCount * 0.05f)).coerceIn(0.1f, 1.0f)
        } else {
            rawHeadroom
        }
    }
}