# ⚽ Análisis táctico de fútbol con IA

Subes un clip de un partido y te regresa:

- **Video anotado**: cada jugador con su ID y el color de su equipo, árbitros en amarillo y el balón marcado.
- **Radar 2D**: la cancha vista desde arriba con la posición de todos en tiempo real.
- **Posesión de balón** en vivo (barra arriba a la derecha) y el porcentaje final.
- **Mapa de calor** de cada equipo.
- **Distancia recorrida** aproximada por jugador (JSON y CSV).

[![Abrir en Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/j2916artprepa2a-ux/analisis-futbol-ia/blob/main/notebooks/Analisis_Futbol_IA.ipynb)

## Cómo usarlo (sin instalar nada)

1. Dale clic al botón **Abrir en Colab** de arriba.
2. Toca el ▶️ del cuadro **"TOCA AQUÍ PARA INICIAR LA APP"** y espera unos 3 minutos.
3. Toca el botón verde **ABRIR LA APP**. Funciona desde el celular.

## 📱 Radar en vivo sobre cualquier app (Android)

Una burbuja flotante que analiza el partido que estás viendo (TikTok, YouTube, etc.) y te pone encima el radar, la posesión y una marca en cada jugador.

1. Descarga la app en tu Android: **[RadarFutbol.apk](https://github.com/j2916artprepa2a-ux/analisis-futbol-ia/releases/latest/download/RadarFutbol.apk)**
2. Abre Colab (botón de arriba) y toca ▶️ en **RADAR EN VIVO**. Copia el link que te da.
3. En la app: pega el link, toca **Iniciar radar** y acepta los permisos.
4. Abre el partido y toca la burbuja verde para prender o apagar el radar.

La app solo captura la pantalla y la manda al "cerebro" en Colab, que corre la IA con GPU. Va con unos segundos de retraso. Las apps de paga que bloquean capturas (Netflix, ViX, etc.) se ven en negro y no se pueden analizar.

## Cómo funciona

| Paso | Qué hace | Herramienta |
|---|---|---|
| 1 | Detecta jugadores, porteros, árbitros y balón | YOLOv8 (modelo de Roboflow) |
| 2 | Le pone un ID a cada jugador y lo sigue | ByteTrack |
| 3 | Separa los dos equipos por el uniforme, sin decirle colores | SigLIP + UMAP + KMeans |
| 4 | Encuentra 32 puntos de la cancha (esquinas, áreas, círculo central) | YOLOv8-pose |
| 5 | Convierte la vista de la cámara a vista desde arriba | Homografía (OpenCV) |
| 6 | Posesión: el balón es del jugador más cercano a menos de 3 m | `futbol_ia/estadisticas.py` |

## En tu compu (con GPU NVIDIA)

```bash
git clone https://github.com/j2916artprepa2a-ux/analisis-futbol-ia.git
cd analisis-futbol-ia
pip install -r requirements.txt
python -m futbol_ia.modelos          # descarga modelos (~200 MB)

python app.py                        # app web en http://localhost:7860
# o por línea de comandos:
python analizar.py --entrada partido.mp4 --segundos 30
```

Opciones de `analizar.py`:

- `--segundos N` analiza solo los primeros N segundos.
- `--vista superpuesta` pone el radar encima del video en vez de abajo.
- `--balon-preciso` usa el modelo especial de balón (más lento, pero lo pierde menos).
- `--cpu` fuerza CPU.

## Limitaciones

- Funciona mejor con **video de transmisión** (cámara alta y amplia). Con celular desde la banda falla más.
- Los nombres "Rosa" y "Azul" son solo etiquetas: el modelo agrupa por uniforme, no sabe qué equipo es cuál.
- Si un jugador sale de cuadro y vuelve, puede recibir un ID nuevo, por eso las distancias son aproximadas.
- Es pesado: cada frame pasa por 2 o 3 modelos. En Colab con GPU calcula varios minutos por cada 30 s de video. Empieza con clips cortos.

## Créditos

Los modelos y la base del pipeline vienen de [roboflow/sports](https://github.com/roboflow/sports) (licencia MIT, ver `LICENSE_ROBOFLOW`). Este proyecto agrega la app web, posesión, mapas de calor, distancias y la vista dividida.
