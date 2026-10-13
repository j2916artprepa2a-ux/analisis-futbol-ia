package com.arturo.radarfutbol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Servicio que mira la pantalla (con permiso del usuario), manda capturas al cerebro
 * y pinta el resultado en las ventanas flotantes.
 */
class ServicioRadar : Service() {

    companion object {
        const val EXTRA_CODIGO = "codigo"
        const val EXTRA_DATOS = "datos"
        const val EXTRA_URL = "url"
        const val EXTRA_MARCAS = "marcas"
        const val ACCION_DETENER = "com.arturo.radarfutbol.DETENER"
        private const val CANAL = "radar"
        private const val LADO_MAXIMO = 1280f
        private const val INTERVALO_MINIMO_MS = 120L

        @Volatile
        var activo = false
            private set
    }

    private val principal = Handler(Looper.getMainLooper())
    private var proyeccion: MediaProjection? = null
    private var pantallaVirtual: VirtualDisplay? = null
    private var lector: ImageReader? = null
    private val candado = Any()
    private var overlay: Overlay? = null
    private var hilo: Thread? = null

    @Volatile
    private var corriendo = false
    private var url = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACCION_DETENER) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (corriendo) liberar()

        iniciarPrimerPlano()

        val codigo = intent.getIntExtra(EXTRA_CODIGO, 0)
        val datos: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATOS, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATOS)
        }
        if (datos == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        url = intent.getStringExtra(EXTRA_URL) ?: ""

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(codigo, datos)
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        proyeccion = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                principal.post { stopSelf() }
            }
        }, principal)

        val ov = Overlay(this, intent.getBooleanExtra(EXTRA_MARCAS, true)) { reiniciarPartido() }
        ov.mostrar()
        overlay = ov

        crearCaptura()
        corriendo = true
        activo = true
        hilo = Thread({ bucle() }, "radar").also { it.start() }
        return START_NOT_STICKY
    }

    private fun iniciarPrimerPlano() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Radar activo", NotificationManager.IMPORTANCE_LOW)
        )
        val detener = PendingIntent.getService(
            this, 1,
            Intent(this, ServicioRadar::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE
        )
        val abrir = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("Radar Fútbol activo")
            .setContentText("Analizando lo que ves en pantalla")
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notificacion), "Detener", detener
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    /** Crea (o ajusta tras girar el celular) la pantalla virtual que copia lo que ves. */
    private fun crearCaptura() {
        val m = Pantalla.real(this)
        val escala = minOf(1f, LADO_MAXIMO / max(m.widthPixels, m.heightPixels))
        val w = ((m.widthPixels * escala).toInt() / 2) * 2
        val h = ((m.heightPixels * escala).toInt() / 2) * 2
        synchronized(candado) {
            val viejo = lector
            val nuevo = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val vd = pantallaVirtual
            if (vd == null) {
                pantallaVirtual = proyeccion?.createVirtualDisplay(
                    "radar", w, h, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    nuevo.surface, null, null
                )
            } else {
                vd.resize(w, h, m.densityDpi)
                vd.surface = nuevo.surface
            }
            lector = nuevo
            viejo?.close()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (corriendo) {
            crearCaptura()
            overlay?.alRotar()
        }
    }

    private fun capturarJpeg(): ByteArray? {
        synchronized(candado) {
            val img = lector?.acquireLatestImage() ?: return null
            try {
                val plano = img.planes[0]
                val anchoConRelleno = plano.rowStride / plano.pixelStride
                val bmp = Bitmap.createBitmap(anchoConRelleno, img.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plano.buffer)
                val recorte = if (anchoConRelleno != img.width) {
                    Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
                } else {
                    bmp
                }
                val salida = ByteArrayOutputStream()
                recorte.compress(Bitmap.CompressFormat.JPEG, 72, salida)
                if (recorte !== bmp) recorte.recycle()
                bmp.recycle()
                return salida.toByteArray()
            } finally {
                img.close()
            }
        }
    }

    private fun bucle() {
        var ultimo = 0L
        var fallos = 0
        while (corriendo) {
            try {
                val ov = overlay
                if (ov == null || !ov.activo) {
                    Thread.sleep(250)
                    continue
                }
                val espera = INTERVALO_MINIMO_MS - (SystemClock.uptimeMillis() - ultimo)
                if (espera > 0) Thread.sleep(espera)
                val jpeg = capturarJpeg()
                if (jpeg == null) {
                    Thread.sleep(40)
                    continue
                }
                ultimo = SystemClock.uptimeMillis()
                val texto = Red.enviarCuadro(url, jpeg, ov.mascaras)
                val r = Resultado.desdeJson(texto)
                fallos = 0
                principal.post { overlay?.actualizar(r) }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                fallos++
                val mensaje = when {
                    e is java.net.UnknownHostException || e is java.net.ConnectException ->
                        "Sin conexión con el cerebro. ¿Sigue corriendo Colab?"
                    e is java.net.SocketTimeoutException -> "El cerebro tardó mucho, reintentando…"
                    else -> "Error: ${e.message ?: e.javaClass.simpleName}"
                }
                principal.post { overlay?.error(mensaje) }
                try {
                    Thread.sleep(if (fallos > 3) 3000 else 1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun reiniciarPartido() {
        val base = url
        Thread {
            try {
                Red.reiniciar(base)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun liberar() {
        corriendo = false
        activo = false
        hilo?.interrupt()
        hilo = null
        overlay?.quitar()
        overlay = null
        synchronized(candado) {
            pantallaVirtual?.release()
            pantallaVirtual = null
            lector?.close()
            lector = null
        }
        proyeccion?.stop()
        proyeccion = null
    }

    override fun onDestroy() {
        liberar()
        super.onDestroy()
    }
}
