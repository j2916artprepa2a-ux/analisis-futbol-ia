"""App web: subes un video y te regresa el análisis.

    python app.py            # local
    python app.py --share    # link público temporal (útil en Colab)
"""

import argparse
import time
from pathlib import Path

import gradio as gr
import torch

from futbol_ia.modelos import descargar_modelos, modelos_listos, ruta_video_ejemplo

if not modelos_listos():
    descargar_modelos()

from futbol_ia.analizador import analizar_video  # noqa: E402

DISPOSITIVO = "cuda" if torch.cuda.is_available() else "cpu"
CARPETA = Path("resultados")


def procesar(video, segundos, vista, balon_preciso, progress=gr.Progress()):
    if not video:
        raise gr.Error("Sube un video primero.")
    salida = CARPETA / f"analisis_{int(time.time())}.mp4"
    try:
        r = analizar_video(
            video, str(salida), dispositivo=DISPOSITIVO,
            max_segundos=float(segundos) if segundos else None,
            vista="dividida" if vista.startswith("Dividida") else "superpuesta",
            balon_preciso=balon_preciso,
            progreso=lambda f, t: progress(f, desc=t),
        )
    except ValueError as e:
        raise gr.Error(str(e))

    res = r["resumen"]
    pos = res["posesion"]
    texto = (
        f"### Posesión\n"
        f"**Rosa {pos['Rosa']:.0f}%**  vs  **Azul {pos['Azul']:.0f}%**\n\n"
        f"Segundos analizados: {res['segundos_analizados']}"
    )
    tabla = [[f["id"], f["equipo"], f["metros"]] for f in res["distancia_por_jugador"][:15]]
    return (r["video"], texto, tabla, r["mapas_de_calor"][0], r["mapas_de_calor"][1],
            [r["json"], r["csv"]])


with gr.Blocks(title="Análisis de fútbol con IA", theme=gr.themes.Soft()) as demo:
    gr.Markdown(
        "# ⚽ Análisis táctico con IA\n"
        "Sube un clip de un partido (mejor de transmisión, cámara alta). "
        f"Corriendo en **{'GPU' if DISPOSITIVO == 'cuda' else 'CPU (lento)'}**."
    )
    with gr.Row():
        with gr.Column(scale=1):
            entrada = gr.Video(label="Video del partido", sources=["upload"])
            segundos = gr.Slider(5, 120, value=20, step=5,
                                 label="Segundos a analizar (más = más lento)")
            vista = gr.Radio(["Dividida (video + radar abajo)", "Superpuesta (radar encima)"],
                             value="Dividida (video + radar abajo)", label="Vista")
            balon = gr.Checkbox(label="Balón preciso (más lento, detecta mejor el balón)")
            boton = gr.Button("Analizar", variant="primary")
            if Path(ruta_video_ejemplo()).exists():
                gr.Examples([[ruta_video_ejemplo()]], inputs=[entrada], label="Video de ejemplo")
        with gr.Column(scale=2):
            salida = gr.Video(label="Resultado")
            resumen = gr.Markdown()
            tabla = gr.Dataframe(headers=["ID", "Equipo", "Metros"],
                                 label="Distancia recorrida (aprox.)")
            with gr.Row():
                calor_rosa = gr.Image(label="Mapa de calor · Rosa")
                calor_azul = gr.Image(label="Mapa de calor · Azul")
            archivos = gr.File(label="Descargar estadísticas", file_count="multiple")

    boton.click(procesar, [entrada, segundos, vista, balon],
                [salida, resumen, tabla, calor_rosa, calor_azul, archivos])


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--share", action="store_true")
    demo.queue().launch(share=p.parse_args().share)
