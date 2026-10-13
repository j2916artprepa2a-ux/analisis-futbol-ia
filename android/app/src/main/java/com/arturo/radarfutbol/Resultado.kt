package com.arturo.radarfutbol

import org.json.JSONObject

/** Un jugador detectado. px/py van de 0 a 1 sobre la pantalla; x/y son cm en la cancha. */
data class Jugador(
    val id: Int,
    val equipo: Int, // -1 sin equipo aún, 0 rosa, 1 azul, 2 árbitro
    val px: Float,
    val py: Float,
    val ancho: Float,
    val x: Float?,
    val y: Float?,
)

data class Balon(val px: Float, val py: Float, val x: Float?, val y: Float?)

data class Resultado(
    val estado: String, // calibrando | sin_cancha | ok
    val calibracion: Float,
    val jugadores: List<Jugador>,
    val balon: Balon?,
    val posesionRosa: Float,
    val posesionAzul: Float,
    val ms: Int,
) {
    companion object {
        private fun JSONObject.flotanteOpcional(clave: String): Float? =
            if (has(clave) && !isNull(clave)) getDouble(clave).toFloat() else null

        fun desdeJson(texto: String): Resultado {
            val o = JSONObject(texto)
            val lista = mutableListOf<Jugador>()
            val arr = o.optJSONArray("jugadores")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    lista.add(
                        Jugador(
                            id = j.optInt("id"),
                            equipo = j.optInt("equipo", -1),
                            px = j.optDouble("px").toFloat(),
                            py = j.optDouble("py").toFloat(),
                            ancho = j.optDouble("ancho", 0.02).toFloat(),
                            x = j.flotanteOpcional("x"),
                            y = j.flotanteOpcional("y"),
                        )
                    )
                }
            }
            val balon = o.optJSONObject("balon")?.let {
                Balon(
                    it.optDouble("px").toFloat(),
                    it.optDouble("py").toFloat(),
                    it.flotanteOpcional("x"),
                    it.flotanteOpcional("y"),
                )
            }
            val pos = o.optJSONArray("posesion")
            return Resultado(
                estado = o.optString("estado", "ok"),
                calibracion = o.optDouble("calibracion", 1.0).toFloat(),
                jugadores = lista,
                balon = balon,
                posesionRosa = pos?.optDouble(0, 50.0)?.toFloat() ?: 50f,
                posesionAzul = pos?.optDouble(1, 50.0)?.toFloat() ?: 50f,
                ms = o.optInt("ms"),
            )
        }
    }
}
