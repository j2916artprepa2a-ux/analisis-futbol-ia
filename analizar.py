"""Uso por línea de comandos.

    python analizar.py --entrada partido.mp4 --salida resultados/partido.mp4 --segundos 30
"""

import argparse
import json

import torch

from futbol_ia.modelos import descargar_modelos, modelos_listos


def main() -> None:
    p = argparse.ArgumentParser(description="Análisis táctico de fútbol con IA")
    p.add_argument("--entrada", required=True, help="video de entrada")
    p.add_argument("--salida", default="resultados/analisis.mp4", help="video de salida")
    p.add_argument("--segundos", type=float, default=None,
                   help="procesar solo los primeros N segundos")
    p.add_argument("--vista", choices=["dividida", "superpuesta"], default="dividida")
    p.add_argument("--balon-preciso", action="store_true",
                   help="modelo especial de balón (más lento, más exacto)")
    p.add_argument("--cpu", action="store_true", help="forzar CPU aunque haya GPU")
    a = p.parse_args()

    if not modelos_listos():
        descargar_modelos(incluir_ejemplo=False)

    from futbol_ia.analizador import analizar_video

    dispositivo = "cuda" if torch.cuda.is_available() and not a.cpu else "cpu"
    if dispositivo == "cpu":
        print("Aviso: sin GPU esto va a tardar bastante. Prueba con --segundos 10.")

    r = analizar_video(
        a.entrada, a.salida, dispositivo=dispositivo, max_segundos=a.segundos,
        vista=a.vista, balon_preciso=a.balon_preciso)
    print(json.dumps(r["resumen"], ensure_ascii=False, indent=2))
    print("\nArchivos:", r["video"], *r["mapas_de_calor"], r["json"], r["csv"], sep="\n  ")


if __name__ == "__main__":
    main()
