package com.arturo.radarfutbol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/** Capa transparente a pantalla completa: dibuja un óvalo con número bajo cada jugador. */
class MarcasView(ctx: Context) : View(ctx) {

    private val dp = resources.displayMetrics.density
    private var resultado: Resultado? = null
    private val ubicacion = IntArray(2)
    private val ovalo = RectF()
    private val triangulo = Path()

    private val pOvalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
    }
    private val pNumero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * dp
        isFakeBoldText = true
    }
    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBalon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colores.ARBITRO }

    private val limpiar = Runnable {
        resultado = null
        invalidate()
    }

    fun actualizar(r: Resultado) {
        resultado = r
        invalidate()
        removeCallbacks(limpiar)
        postDelayed(limpiar, 2500)
    }

    fun borrar() {
        removeCallbacks(limpiar)
        resultado = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = resultado ?: return
        val m = Pantalla.real(context)
        getLocationOnScreen(ubicacion)
        val anchoP = m.widthPixels.toFloat()
        val altoP = m.heightPixels.toFloat()

        for (j in r.jugadores) {
            val cx = j.px * anchoP - ubicacion[0]
            val cy = j.py * altoP - ubicacion[1]
            val ancho = max(j.ancho * anchoP * 1.15f, 16f * dp)
            val color = Colores.deEquipo(j.equipo)
            pOvalo.color = color
            ovalo.set(cx - ancho / 2, cy - ancho * 0.17f, cx + ancho / 2, cy + ancho * 0.17f)
            canvas.drawOval(ovalo, pOvalo)

            val etiqueta = j.id.toString()
            val tw = pNumero.measureText(etiqueta)
            val top = cy + ancho * 0.17f + 2f * dp
            pFondo.color = color
            canvas.drawRoundRect(
                RectF(cx - tw / 2 - 4f * dp, top, cx + tw / 2 + 4f * dp, top + 13f * dp),
                4f * dp, 4f * dp, pFondo
            )
            pNumero.color = Color.BLACK
            canvas.drawText(etiqueta, cx - tw / 2, top + 10f * dp, pNumero)
        }

        val b = r.balon ?: return
        val bx = b.px * anchoP - ubicacion[0]
        val by = b.py * altoP - ubicacion[1]
        triangulo.reset()
        triangulo.moveTo(bx - 7f * dp, by - 20f * dp)
        triangulo.lineTo(bx + 7f * dp, by - 20f * dp)
        triangulo.lineTo(bx, by - 9f * dp)
        triangulo.close()
        canvas.drawPath(triangulo, pBalon)
    }
}
