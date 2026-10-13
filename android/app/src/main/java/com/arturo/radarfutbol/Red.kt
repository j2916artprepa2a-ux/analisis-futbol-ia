package com.arturo.radarfutbol

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Comunicación con el "cerebro" (el servidor que corre en Colab). */
object Red {

    fun limpiarUrl(texto: String): String {
        var u = texto.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        return u
    }

    private fun leer(c: HttpURLConnection): String {
        val codigo = c.responseCode
        val flujo = if (codigo in 200..299) c.inputStream else c.errorStream
        val texto = flujo?.bufferedReader()?.use { it.readText() } ?: ""
        if (codigo !in 200..299) throw IOException("El cerebro respondió $codigo")
        return texto
    }

    fun enviarCuadro(base: String, jpeg: ByteArray, mascaras: String): String {
        val c = URL("$base/cuadro").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.setRequestProperty("X-Mascaras", mascaras)
        c.setFixedLengthStreamingMode(jpeg.size)
        c.outputStream.use { it.write(jpeg) }
        return leer(c)
    }

    fun probar(base: String): String {
        val c = URL("$base/").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        return leer(c)
    }

    fun reiniciar(base: String) {
        val c = URL("$base/reiniciar").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 10000
        leer(c)
    }
}
