package com.arturo.radarfutbol

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/** Tamaño físico real de la pantalla (incluye barras), tal como lo ve la captura. */
object Pantalla {
    fun real(ctx: Context): DisplayMetrics {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val pantalla = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        pantalla.getRealMetrics(m)
        return m
    }
}
