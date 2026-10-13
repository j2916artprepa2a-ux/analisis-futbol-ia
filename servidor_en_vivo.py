"""Servidor del modo en vivo: la app de Android manda capturas y recibe el análisis.

    python servidor_en_vivo.py            # local en el puerto 8000
    python servidor_en_vivo.py --tunel    # además abre un link público (Colab)

Endpoints:
    GET  /            -> estado del servidor
    POST /cuadro      -> cuerpo: JPEG. Header opcional X-Mascaras: "x0,y0,x1,y1;..." (0-1)
    POST /reiniciar   -> olvida equipos y posesión (nuevo partido)
"""

import argparse
import os
import re
import subprocess
import threading
import time
import urllib.request

import cv2
import numpy as np
import torch
import uvicorn
from fastapi import FastAPI, HTTPException, Request

from futbol_ia.modelos import descargar_modelos, modelos_listos

if not modelos_listos():
    descargar_modelos(incluir_ejemplo=False)

from futbol_ia.en_vivo import AnalizadorEnVivo  # noqa: E402

DISPOSITIVO = "cuda" if torch.cuda.is_available() else "cpu"
app = FastAPI(title="Radar de fútbol en vivo")
_analizador = None


def analizador() -> AnalizadorEnVivo:
    global _analizador
    if _analizador is None:
        _analizador = AnalizadorEnVivo(dispositivo=DISPOSITIVO)
    return _analizador


def _leer_mascaras(texto: str):
    mascaras = []
    for parte in (texto or "").split(";"):
        try:
            x0, y0, x1, y1 = (min(max(float(v), 0.0), 1.0) for v in parte.split(","))
            mascaras.append((x0, y0, x1, y1))
        except ValueError:
            continue
    return mascaras


@app.get("/")
def estado():
    return {"ok": True, "gpu": DISPOSITIVO == "cuda", "version": 1}


@app.post("/cuadro")
async def cuadro(request: Request):
    datos = await request.body()
    frame = cv2.imdecode(np.frombuffer(datos, np.uint8), cv2.IMREAD_COLOR)
    if frame is None:
        raise HTTPException(400, "La imagen no se pudo leer")
    mascaras = _leer_mascaras(request.headers.get("x-mascaras", ""))
    # La inferencia es pesada: se corre en otro hilo para no trabar el servidor
    import asyncio
    return await asyncio.get_running_loop().run_in_executor(
        None, analizador().analizar, frame, mascaras)


@app.post("/reiniciar")
def reiniciar():
    analizador().reiniciar()
    return {"ok": True}


# ---------------------------------------------------------------------------
# Túnel público gratis con Cloudflare (no necesita cuenta)
CLOUDFLARED = "/tmp/cloudflared"


def abrir_tunel(puerto: int = 8000, espera_s: int = 60) -> str:
    if not os.path.exists(CLOUDFLARED):
        urllib.request.urlretrieve(
            "https://github.com/cloudflare/cloudflared/releases/latest/download/"
            "cloudflared-linux-amd64", CLOUDFLARED)
        os.chmod(CLOUDFLARED, 0o755)
    proceso = subprocess.Popen(
        [CLOUDFLARED, "tunnel", "--no-autoupdate", "--url", f"http://localhost:{puerto}"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    limite = time.time() + espera_s
    for linea in proceso.stdout:
        m = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", linea)
        if m:
            # Seguir leyendo la salida en segundo plano para que no se llene el buffer
            threading.Thread(target=lambda: [None for _ in proceso.stdout], daemon=True).start()
            return m.group(0)
        if time.time() > limite:
            break
    raise RuntimeError("No se pudo abrir el link público. Vuelve a intentar.")


def iniciar_en_segundo_plano(puerto: int = 8000) -> None:
    """Arranca el servidor en un hilo (para usarlo dentro de Colab)."""
    config = uvicorn.Config(app, host="0.0.0.0", port=puerto, log_level="warning")
    servidor = uvicorn.Server(config)
    threading.Thread(target=servidor.run, daemon=True).start()
    for _ in range(100):
        if servidor.started:
            break
        time.sleep(0.1)
    analizador()  # carga los modelos de una vez


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--puerto", type=int, default=8000)
    p.add_argument("--tunel", action="store_true")
    a = p.parse_args()
    if a.tunel:
        iniciar_en_segundo_plano(a.puerto)
        print("\n  Pega este link en la app:", abrir_tunel(a.puerto), "\n")
        threading.Event().wait()
    else:
        analizador()
        uvicorn.run(app, host="0.0.0.0", port=a.puerto)
