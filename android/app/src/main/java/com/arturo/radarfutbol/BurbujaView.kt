package com.arturo.radarfutbol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Botón redondo flotante. Verde = radar prendido, gris = apagado. */
class BurbujaView(ctx: Context) : View(ctx) {

    private val dp = resources.displayMetrics.density
    var activo = true
        set(valor) {
            field = valor
            invalidate()
        }

    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * dp
    }
    private val pIcono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * dp
    }
    private val pPunto = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val r = c - 2f * dp
        pFondo.color = if (activo) 0xFF16A34A.toInt() else 0xFF4B5563.toInt()
        canvas.drawCircle(c, c, r, pFondo)
        canvas.drawCircle(c, c, r, pBorde)

        // Mini cancha
        val w = r * 1.1f
        val h = w * 0.62f
        canvas.drawRect(c - w / 2, c - h / 2, c + w / 2, c + h / 2, pIcono)
        canvas.drawLine(c, c - h / 2, c, c + h / 2, pIcono)
        canvas.drawCircle(c, c, h * 0.22f, pIcono)
        if (activo) {
            pPunto.color = Colores.ROSA
            canvas.drawCircle(c - w * 0.25f, c - h * 0.15f, 3f * dp, pPunto)
            pPunto.color = Colores.AZUL
            canvas.drawCircle(c + w * 0.22f, c + h * 0.18f, 3f * dp, pPunto)
        }
    }
}
