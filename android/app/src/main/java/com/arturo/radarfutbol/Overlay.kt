package com.arturo.radarfutbol

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Las ventanas flotantes: burbuja, panel del radar y (opcional) marcas sobre los jugadores. */
class Overlay(
    private val ctx: Context,
    conMarcas: Boolean,
    alReiniciar: () -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density

    private val panel = PanelView(ctx, alReiniciar) { cambiarActivo(false) }
    private val burbuja = BurbujaView(ctx)
    private val marcas: MarcasView? = if (conMarcas) MarcasView(ctx) else null

    private lateinit var pPanel: WindowManager.LayoutParams
    private lateinit var pBurbuja: WindowManager.LayoutParams

    /** Si el radar está prendido. Cuando está apagado no se manda nada al cerebro. */
    @Volatile
    var activo = true
        private set

    /** Zonas de la pantalla que tapa la app ("x0,y0,x1,y1;..."), para que el cerebro las ignore. */
    @Volatile
    var mascaras: String = ""
        private set

    private fun params(ancho: Int, alto: Int, tocable: Boolean): WindowManager.LayoutParams {
        var banderas = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!tocable) {
            banderas = banderas or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams(
            ancho, alto,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            banderas,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    fun mostrar() {
        val m = Pantalla.real(ctx)
        val corto = min(m.widthPixels, m.heightPixels)

        marcas?.let {
            val p = params(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                tocable = false
            )
            // Android 12+ solo deja pasar los toques a TikTok si la capa es un poco transparente
            p.alpha = 0.8f
            wm.addView(it, p)
        }

        val anchoPanel = min((corto * 0.8f).toInt(), (440 * dp).toInt())
        pPanel = params(anchoPanel, WindowManager.LayoutParams.WRAP_CONTENT, tocable = true).apply {
            x = (m.widthPixels - anchoPanel) / 2
            y = (64 * dp).toInt()
        }
        wm.addView(panel, pPanel)

        val tamBurbuja = (58 * dp).toInt()
        pBurbuja = params(tamBurbuja, tamBurbuja, tocable = true).apply {
            x = m.widthPixels - tamBurbuja - (10 * dp).toInt()
            y = (m.heightPixels * 0.55f).toInt()
        }
        wm.addView(burbuja, pBurbuja)

        arrastrable(panel, pPanel) { x, y -> panel.tocar(x, y) }
        arrastrable(burbuja, pBurbuja) { _, _ -> cambiarActivo(!activo) }

        val alCambiar = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> calcularMascaras() }
        panel.addOnLayoutChangeListener(alCambiar)
        burbuja.addOnLayoutChangeListener(alCambiar)
    }

    fun cambiarActivo(a: Boolean) {
        activo = a
        panel.visibility = if (a) View.VISIBLE else View.GONE
        if (!a) marcas?.borrar()
        burbuja.activo = a
        panel.post { calcularMascaras() }
    }

    fun actualizar(r: Resultado) {
        if (!activo) return
        panel.actualizar(r)
        marcas?.actualizar(r)
    }

    fun error(mensaje: String) {
        panel.mostrarError(mensaje)
        marcas?.borrar()
    }

    fun alRotar() {
        panel.post { calcularMascaras() }
    }

    fun quitar() {
        for (v in listOfNotNull(marcas, panel, burbuja)) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun arrastrable(v: View, p: WindowManager.LayoutParams, alTocar: (Float, Float) -> Unit) {
        val umbral = ViewConfiguration.get(ctx).scaledTouchSlop
        var x0 = 0
        var y0 = 0
        var tx = 0f
        var ty = 0f
        var movido = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = p.x
                    y0 = p.y
                    tx = e.rawX
                    ty = e.rawY
                    movido = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (!movido && abs(dx) + abs(dy) > umbral) movido = true
                    if (movido) {
                        p.x = x0 + dx.toInt()
                        p.y = y0 + dy.toInt()
                        wm.updateViewLayout(v, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!movido) alTocar(e.x, e.y) else calcularMascaras()
                    true
                }
                else -> false
            }
        }
    }

    private fun calcularMascaras() {
        val m = Pantalla.real(ctx)
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val ubicacion = IntArray(2)
        val partes = mutableListOf<String>()
        for (v in listOf(panel, burbuja)) {
            if (v.visibility != View.VISIBLE || v.width == 0) continue
            v.getLocationOnScreen(ubicacion)
            partes.add(
                String.format(
                    Locale.US, "%.4f,%.4f,%.4f,%.4f",
                    ubicacion[0] / w, ubicacion[1] / h,
                    (ubicacion[0] + v.width) / w, (ubicacion[1] + v.height) / h
                )
            )
        }
        mascaras = partes.joinToString(";")
    }
}
