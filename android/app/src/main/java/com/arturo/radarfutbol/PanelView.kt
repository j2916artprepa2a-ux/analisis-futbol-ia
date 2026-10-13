package com.arturo.radarfutbol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs

object Colores {
    const val ROSA = 0xFFEC4899.toInt()
    const val AZUL = 0xFF0EA5E9.toInt()
    const val ARBITRO = 0xFFFACC15.toInt()
    const val SIN_EQUIPO = 0xFFB0B7C3.toInt()
    const val CESPED = 0xFF2E7D32.toInt()
    const val CESPED_OSCURO = 0xFF256B29.toInt()
    const val FONDO = 0xE0101418.toInt()

    fun deEquipo(e: Int) = when (e) {
        0 -> ROSA
        1 -> AZUL
        2 -> ARBITRO
        else -> SIN_EQUIPO
    }
}

/** Panel flotante: barra de posesión + radar 2D de la cancha + estado. */
class PanelView(
    ctx: Context,
    private val alReiniciar: () -> Unit,
    private val alOcultar: () -> Unit,
) : View(ctx) {

    private val dp = resources.displayMetrics.density
    private var resultado: Resultado? = null
    private var error: String? = null
    private var ultimaActualizacion = 0L
    private var cuadrosPorSeg = 0f

    // Posiciones en cm: las actuales se deslizan hacia las nuevas para que se vea fluido
    private val actual = HashMap<Int, PointF>()
    private val objetivo = HashMap<Int, PointF>()
    private val equipoDe = HashMap<Int, Int>()
    private var balonActual: PointF? = null
    private var balonObjetivo: PointF? = null

    private val rectReiniciar = RectF()
    private val rectOcultar = RectF()

    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.2f * dp
    }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1f * dp
    }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * dp
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val pChico = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * dp
        color = 0xFFCBD5E1.toInt()
    }

    private val pad get() = 8f * dp
    private val altoCabecera get() = 38f * dp
    private val altoEstado get() = 24f * dp

    fun actualizar(r: Resultado) {
        val ahora = SystemClock.uptimeMillis()
        if (ultimaActualizacion > 0) {
            val inst = 1000f / (ahora - ultimaActualizacion).coerceAtLeast(1)
            cuadrosPorSeg = if (cuadrosPorSeg == 0f) inst else cuadrosPorSeg * 0.7f + inst * 0.3f
        }
        ultimaActualizacion = ahora
        resultado = r
        error = null

        objetivo.clear()
        equipoDe.clear()
        for (j in r.jugadores) {
            if (j.x != null && j.y != null) {
                objetivo[j.id] = PointF(j.x, j.y)
                equipoDe[j.id] = j.equipo
            }
        }
        actual.keys.retainAll(objetivo.keys)
        for ((id, p) in objetivo) {
            if (!actual.containsKey(id)) actual[id] = PointF(p.x, p.y)
        }
        val b = r.balon
        balonObjetivo = if (b?.x != null && b.y != null) PointF(b.x, b.y) else null
        if (balonObjetivo == null) balonActual = null
        else if (balonActual == null) balonActual = PointF(balonObjetivo!!.x, balonObjetivo!!.y)
        invalidate()
    }

    fun mostrarError(mensaje: String) {
        error = mensaje
        invalidate()
    }

    /** Toque sin arrastrar dentro del panel. */
    fun tocar(x: Float, y: Float) {
        when {
            rectReiniciar.contains(x, y) -> alReiniciar()
            rectOcultar.contains(x, y) -> alOcultar()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val anchoCancha = w - 2 * pad
        val altoCancha = anchoCancha * 7400f / 12400f
        val h = pad + altoCabecera + altoCancha + altoEstado + pad
        setMeasuredDimension(w, h.toInt())
    }

    private fun mover(a: PointF, b: PointF): Boolean {
        a.x += (b.x - a.x) * 0.22f
        a.y += (b.y - a.y) * 0.22f
        return abs(b.x - a.x) + abs(b.y - a.y) > 15f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        pRelleno.color = Colores.FONDO
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 14f * dp, 14f * dp, pRelleno)

        val r = resultado
        dibujarCabecera(canvas, r, w)

        // --- Cancha (12000 x 7000 cm, con 2 m de margen alrededor) ---
        val x0 = pad
        val y0 = pad + altoCabecera
        val anchoCancha = w - 2 * pad
        val s = anchoCancha / 12400f
        val altoCancha = 7400f * s
        pRelleno.color = Colores.CESPED_OSCURO
        canvas.drawRoundRect(RectF(x0, y0, x0 + anchoCancha, y0 + altoCancha), 6f * dp, 6f * dp, pRelleno)
        val cx = x0 + 200f * s
        val cy = y0 + 200f * s
        fun X(cm: Float) = cx + cm * s
        fun Y(cm: Float) = cy + cm * s
        pRelleno.color = Colores.CESPED
        canvas.drawRect(X(0f), Y(0f), X(12000f), Y(7000f), pRelleno)
        canvas.drawRect(X(0f), Y(0f), X(12000f), Y(7000f), pLinea)
        canvas.drawLine(X(6000f), Y(0f), X(6000f), Y(7000f), pLinea)
        canvas.drawCircle(X(6000f), Y(3500f), 915f * s, pLinea)
        canvas.drawRect(X(0f), Y(1450f), X(2015f), Y(5550f), pLinea)
        canvas.drawRect(X(10000f - 15f), Y(1450f), X(12000f), Y(5550f), pLinea)
        canvas.drawRect(X(0f), Y(2584f), X(550f), Y(4416f), pLinea)
        canvas.drawRect(X(11450f), Y(2584f), X(12000f), Y(4416f), pLinea)
        pRelleno.color = Color.WHITE
        canvas.drawCircle(X(1100f), Y(3500f), 1.6f * dp, pRelleno)
        canvas.drawCircle(X(10900f), Y(3500f), 1.6f * dp, pRelleno)
        canvas.drawCircle(X(6000f), Y(3500f), 1.6f * dp, pRelleno)

        var animando = false
        val radio = 5f * dp
        for ((id, a) in actual) {
            val b = objetivo[id] ?: continue
            if (mover(a, b)) animando = true
            pRelleno.color = Colores.deEquipo(equipoDe[id] ?: -1)
            val px = X(a.x.coerceIn(-150f, 12150f))
            val py = Y(a.y.coerceIn(-150f, 7150f))
            canvas.drawCircle(px, py, radio, pRelleno)
            canvas.drawCircle(px, py, radio, pBorde)
        }
        val ba = balonActual
        val bo = balonObjetivo
        if (ba != null && bo != null) {
            if (mover(ba, bo)) animando = true
            pRelleno.color = Color.WHITE
            val px = X(ba.x.coerceIn(-150f, 12150f))
            val py = Y(ba.y.coerceIn(-150f, 7150f))
            canvas.drawCircle(px, py, 3.2f * dp, pRelleno)
            canvas.drawCircle(px, py, 3.2f * dp, pBorde)
        }

        if (r != null && r.estado == "sin_cancha") {
            pRelleno.color = 0xAA000000.toInt()
            canvas.drawRect(X(0f), Y(2800f), X(12000f), Y(4200f), pRelleno)
            centrado(canvas, "Esperando la toma abierta de la cancha", X(6000f), Y(3500f) + 4f * dp, pChico)
        }

        dibujarEstado(canvas, r, w, y0 + altoCancha)
        if (animando) postInvalidateOnAnimation()
    }

    private fun dibujarCabecera(canvas: Canvas, r: Resultado?, w: Float) {
        val base = pad + 15f * dp
        val barraY = pad + 24f * dp
        val barraAlto = 7f * dp
        val barra = RectF(pad, barraY, w - pad, barraY + barraAlto)
        if (r == null || r.estado == "calibrando") {
            val pct = ((r?.calibracion ?: 0f) * 100).toInt()
            canvas.drawText("Aprendiendo los uniformes… $pct%", pad, base, pTexto)
            pRelleno.color = 0x33FFFFFF
            canvas.drawRoundRect(barra, barraAlto / 2, barraAlto / 2, pRelleno)
            pRelleno.color = Color.WHITE
            val lleno = RectF(barra.left, barra.top, barra.left + barra.width() * pct / 100f, barra.bottom)
            canvas.drawRoundRect(lleno, barraAlto / 2, barraAlto / 2, pRelleno)
            return
        }
        pTexto.color = Colores.ROSA
        canvas.drawText("Rosa ${r.posesionRosa.toInt()}%", pad, base, pTexto)
        pTexto.color = Colores.AZUL
        val textoAzul = "${r.posesionAzul.toInt()}% Azul"
        canvas.drawText(textoAzul, w - pad - pTexto.measureText(textoAzul), base, pTexto)
        pTexto.color = Color.WHITE
        centrado(canvas, "Posesión", w / 2, base, pChico)

        val corte = barra.left + barra.width() * (r.posesionRosa / 100f).coerceIn(0f, 1f)
        pRelleno.color = Colores.AZUL
        canvas.drawRoundRect(barra, barraAlto / 2, barraAlto / 2, pRelleno)
        pRelleno.color = Colores.ROSA
        canvas.drawRoundRect(RectF(barra.left, barra.top, corte, barra.bottom), barraAlto / 2, barraAlto / 2, pRelleno)
    }

    private fun dibujarEstado(canvas: Canvas, r: Resultado?, w: Float, arriba: Float) {
        val base = arriba + 17f * dp
        val ocultar = "Ocultar"
        val reiniciar = "Reiniciar"
        val anchoOcultar = pChico.measureText(ocultar)
        val anchoReiniciar = pChico.measureText(reiniciar)
        val xOcultar = w - pad - anchoOcultar
        val xReiniciar = xOcultar - 14f * dp - anchoReiniciar
        val colorAntes = pChico.color
        pChico.color = Color.WHITE
        canvas.drawText(reiniciar, xReiniciar, base, pChico)
        canvas.drawText(ocultar, xOcultar, base, pChico)
        pChico.color = colorAntes
        rectReiniciar.set(xReiniciar - 8f * dp, arriba, xReiniciar + anchoReiniciar + 6f * dp, arriba + altoEstado + pad)
        rectOcultar.set(xOcultar - 6f * dp, arriba, w, arriba + altoEstado + pad)

        val err = error
        val texto: String
        if (err != null) {
            pChico.color = 0xFFF87171.toInt()
            texto = err
        } else if (r == null) {
            texto = "Conectando con el cerebro…"
        } else if (SystemClock.uptimeMillis() - ultimaActualizacion > 4000) {
            texto = "Esperando respuesta…"
        } else {
            texto = String.format("%.1f/s · IA %d ms", cuadrosPorSeg, r.ms)
        }
        val maximo = xReiniciar - pad - 10f * dp
        var mostrado = texto
        while (mostrado.length > 4 && pChico.measureText(mostrado) > maximo) {
            mostrado = mostrado.dropLast(2)
        }
        if (mostrado != texto) mostrado = mostrado.trimEnd() + "…"
        canvas.drawText(mostrado, pad, base, pChico)
        pChico.color = colorAntes
    }

    private fun centrado(canvas: Canvas, t: String, x: Float, y: Float, p: Paint) {
        canvas.drawText(t, x - p.measureText(t) / 2, y, p)
    }
}
