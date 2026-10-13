package com.arturo.radarfutbol

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        private const val PEDIR_CAPTURA = 10
        private const val PEDIR_NOTIFICACIONES = 11
    }

    private val prefs by lazy { getSharedPreferences("radar", MODE_PRIVATE) }
    private lateinit var campoUrl: EditText
    private lateinit var estado: TextView
    private lateinit var marcas: CheckBox
    private var dp = 1f

    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(36), px(22), px(28))
            setBackgroundColor(0xFF0F172A.toInt())
        }

        raiz.addView(texto("⚽ Radar Fútbol", 28f, Color.WHITE, negrita = true))
        raiz.addView(texto("Analiza el partido que estás viendo en vivo", 15f, 0xFF94A3B8.toInt()).apply {
            setPadding(0, px(4), 0, px(22))
        })

        raiz.addView(paso("1", "En Colab, corre la celda \"RADAR EN VIVO\" y copia el link que te da."))
        raiz.addView(paso("2", "Pégalo aquí abajo y toca \"Iniciar radar\"."))
        raiz.addView(paso("3", "Abre el partido (TikTok, YouTube…). Toca la burbuja verde para prender o apagar el radar."))

        campoUrl = EditText(this).apply {
            hint = "https://algo.trycloudflare.com"
            setText(prefs.getString("url", ""))
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF64748B.toInt())
            textSize = 15f
            background = fondo(0xFF1E293B.toInt(), 10)
            setPadding(px(14), px(12), px(14), px(12))
        }
        raiz.addView(campoUrl, margen(top = 18))

        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("Pegar link", 0xFF334155.toInt()) { pegar() }, LinearLayout.LayoutParams(0, px(48), 1f))
        fila.addView(View(this), LinearLayout.LayoutParams(px(10), 1))
        fila.addView(boton("Probar conexión", 0xFF334155.toInt()) { probar() }, LinearLayout.LayoutParams(0, px(48), 1f))
        raiz.addView(fila, margen(top = 10))

        marcas = CheckBox(this).apply {
            text = "Marcar a los jugadores sobre el video"
            setTextColor(Color.WHITE)
            textSize = 15f
            isChecked = prefs.getBoolean("marcas", true)
        }
        raiz.addView(marcas, margen(top = 14))

        raiz.addView(boton("▶  Iniciar radar", 0xFF16A34A.toInt()) { iniciar() }.apply {
            textSize = 18f
        }, margen(top = 16, alto = 58))
        raiz.addView(boton("■  Detener", 0xFF7F1D1D.toInt()) { detener() }, margen(top = 10, alto = 48))

        estado = texto("", 15f, 0xFFFDE68A.toInt()).apply { setPadding(0, px(18), 0, 0) }
        raiz.addView(estado)

        raiz.addView(texto(
            "Tips: funciona mejor con la toma abierta de transmisión. " +
                "Las primeras capturas sirven para aprender los colores de cada equipo. " +
                "Si cambias de partido, toca \"Reiniciar\" en el panel.",
            13f, 0xFF94A3B8.toInt()
        ).apply { setPadding(0, px(22), 0, 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0F172A.toInt())
            addView(raiz)
        })
    }

    override fun onResume() {
        super.onResume()
        if (ServicioRadar.activo) mostrarEstado("El radar está activo. Abre tu partido.")
    }

    // ---------------------------------------------------------------- acciones
    private fun urlLimpia(): String {
        val u = Red.limpiarUrl(campoUrl.text.toString())
        prefs.edit().putString("url", u).putBoolean("marcas", marcas.isChecked).apply()
        return u
    }

    private fun pegar() {
        val cm = getSystemService(ClipboardManager::class.java)
        val texto = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (texto.isNullOrBlank()) {
            mostrarEstado("No hay nada copiado. Copia el link en Colab primero.")
            return
        }
        val link = Regex("https://[A-Za-z0-9.-]+").find(texto)?.value ?: texto.trim()
        campoUrl.setText(link)
        urlLimpia()
        mostrarEstado("Link pegado. Ahora toca \"Probar conexión\".")
    }

    private fun probar() {
        val u = urlLimpia()
        if (u.isEmpty()) {
            mostrarEstado("Primero pega el link de Colab.")
            return
        }
        mostrarEstado("Probando…")
        Thread {
            val mensaje = try {
                val o = JSONObject(Red.probar(u))
                if (o.optBoolean("gpu")) "✅ Conectado al cerebro (con GPU). ¡Listo!"
                else "⚠️ Conectado, pero Colab no tiene GPU: va a ir muy lento."
            } catch (e: Exception) {
                "❌ No conecta. Revisa que la celda de Colab siga corriendo y que el link esté completo."
            }
            runOnUiThread { mostrarEstado(mensaje) }
        }.start()
    }

    private fun iniciar() {
        if (urlLimpia().isEmpty()) {
            mostrarEstado("Primero pega el link de Colab.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIR_NOTIFICACIONES)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            mostrarEstado("Activa \"Mostrar sobre otras apps\" para Radar Fútbol y regresa aquí.")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), PEDIR_CAPTURA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PEDIR_NOTIFICACIONES) iniciar()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PEDIR_CAPTURA) return
        if (resultCode != RESULT_OK || data == null) {
            mostrarEstado("Sin el permiso de pantalla no puedo ver el partido.")
            return
        }
        val i = Intent(this, ServicioRadar::class.java)
            .putExtra(ServicioRadar.EXTRA_CODIGO, resultCode)
            .putExtra(ServicioRadar.EXTRA_DATOS, data)
            .putExtra(ServicioRadar.EXTRA_URL, urlLimpia())
            .putExtra(ServicioRadar.EXTRA_MARCAS, marcas.isChecked)
        startForegroundService(i)
        mostrarEstado("Radar activo. Ahora abre tu partido.")
        moveTaskToBack(true)
    }

    private fun detener() {
        startService(Intent(this, ServicioRadar::class.java).setAction(ServicioRadar.ACCION_DETENER))
        mostrarEstado("Radar detenido.")
    }

    // ---------------------------------------------------------------- vista
    private fun mostrarEstado(t: String) {
        estado.text = t
    }

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean = false) = TextView(this).apply {
        text = t
        textSize = tam
        setTextColor(color)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
    }

    private fun paso(numero: String, t: String): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, px(6), 0, px(6))
        }
        fila.addView(TextView(this).apply {
            text = numero
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = fondo(0xFF16A34A.toInt(), 13)
        }, LinearLayout.LayoutParams(px(26), px(26)))
        fila.addView(texto(t, 15f, 0xFFE2E8F0.toInt()).apply {
            setPadding(px(12), px(2), 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return fila
    }

    private fun boton(t: String, color: Int, accion: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = fondo(color, 12)
        setOnClickListener { accion() }
    }

    private fun fondo(color: Int, radioDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radioDp * dp
    }

    private fun margen(top: Int = 0, alto: Int = -1): LinearLayout.LayoutParams {
        val h = if (alto > 0) px(alto) else ViewGroup.LayoutParams.WRAP_CONTENT
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
            topMargin = px(top)
        }
    }
}
