"""Descarga de los modelos YOLO entrenados por Roboflow (licencia MIT)."""

import os
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
CARPETA_DATOS = RAIZ / "data"

MODELOS = {
    "jugadores": (
        "football-player-detection.pt",
        "https://drive.google.com/uc?id=17PXFNlx-jI7VjVo_vQnB1sONjRyvoB-q",
    ),
    "cancha": (
        "football-pitch-detection.pt",
        "https://drive.google.com/uc?id=1Ma5Kt86tgpdjCTKfum79YMgNnSjcoOyf",
    ),
    "balon": (
        "football-ball-detection.pt",
        "https://drive.google.com/uc?id=1isw4wx-MK9h9LMr36VvIWlJD6ppUvw7V",
    ),
}

VIDEO_EJEMPLO = (
    "ejemplo.mp4",
    "https://drive.google.com/uc?id=12TqauVZ9tLAv8kWxTTBFWtgt2hNQ4_ZF",
)


def ruta_modelo(nombre: str) -> str:
    return str(CARPETA_DATOS / MODELOS[nombre][0])


def _descargar(archivo: str, url: str) -> str:
    import gdown

    CARPETA_DATOS.mkdir(parents=True, exist_ok=True)
    destino = CARPETA_DATOS / archivo
    if not destino.exists():
        print(f"Descargando {archivo}...")
        gdown.download(url, str(destino), quiet=False)
    return str(destino)


def descargar_modelos(incluir_ejemplo: bool = True) -> None:
    for archivo, url in MODELOS.values():
        _descargar(archivo, url)
    if incluir_ejemplo:
        _descargar(*VIDEO_EJEMPLO)


def ruta_video_ejemplo() -> str:
    return str(CARPETA_DATOS / VIDEO_EJEMPLO[0])


def modelos_listos() -> bool:
    return all(os.path.exists(ruta_modelo(n)) for n in MODELOS)


if __name__ == "__main__":
    descargar_modelos()
    print("Listo. Modelos en", CARPETA_DATOS)
