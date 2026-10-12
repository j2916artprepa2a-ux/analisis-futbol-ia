"""Estadísticas calculadas sobre las posiciones en la cancha (en centímetros).

Coordenadas de la cancha: x de 0 a 12000 (largo), y de 0 a 7000 (ancho),
igual que SoccerPitchConfiguration de roboflow/sports.
"""

from collections import defaultdict
from typing import Dict, List, Optional

import cv2
import numpy as np

LARGO_CM = 12000
ANCHO_CM = 7000


class Posesion:
    """Cuenta cuántos frames tuvo el balón cada equipo.

    En cada frame el balón es del jugador más cercano si está a menos de
    `distancia_max_cm`. Si nadie está cerca (pase, balón en el aire), la
    posesión sigue siendo del último equipo que lo tuvo.
    """

    def __init__(self, distancia_max_cm: float = 300.0):
        self.distancia_max_cm = distancia_max_cm
        self.frames = np.zeros(2, dtype=np.int64)
        self.equipo_actual: Optional[int] = None

    def actualizar(
        self,
        balon_xy: Optional[np.ndarray],
        jugadores_xy: np.ndarray,
        jugadores_equipo: np.ndarray,
    ) -> Optional[int]:
        if balon_xy is not None and len(jugadores_xy) > 0:
            distancias = np.linalg.norm(jugadores_xy - balon_xy, axis=1)
            i = int(np.argmin(distancias))
            if distancias[i] <= self.distancia_max_cm:
                self.equipo_actual = int(jugadores_equipo[i])
        if self.equipo_actual is not None:
            self.frames[self.equipo_actual] += 1
        return self.equipo_actual

    def porcentajes(self) -> np.ndarray:
        total = self.frames.sum()
        if total == 0:
            return np.array([50.0, 50.0])
        return self.frames / total * 100.0


class Distancias:
    """Distancia recorrida por jugador (por ID de seguimiento).

    Suaviza la posición y descarta saltos imposibles (más de `vel_max_ms`)
    porque la proyección a la cancha tiembla de un frame a otro.
    """

    def __init__(self, fps: float, vel_max_ms: float = 11.0, suavizado: float = 0.3):
        self.fps = fps
        self.vel_max_cm_por_frame = vel_max_ms * 100.0 / fps
        self.suavizado = suavizado
        self.ultima_pos: Dict[int, np.ndarray] = {}
        self.ultimo_frame: Dict[int, int] = {}
        self.recorrido_cm: Dict[int, float] = defaultdict(float)
        self.votos_equipo: Dict[int, np.ndarray] = defaultdict(lambda: np.zeros(2))

    def actualizar(
        self, frame_idx: int, ids: np.ndarray, xy: np.ndarray, equipos: np.ndarray
    ) -> None:
        for tid, punto, equipo in zip(ids, xy, equipos):
            tid = int(tid)
            if equipo in (0, 1):
                self.votos_equipo[tid][int(equipo)] += 1
            if tid not in self.ultima_pos:
                self.ultima_pos[tid] = punto.astype(float)
                self.ultimo_frame[tid] = frame_idx
                continue
            anterior = self.ultima_pos[tid]
            nuevo = anterior + self.suavizado * (punto - anterior)
            frames = max(frame_idx - self.ultimo_frame[tid], 1)
            paso = float(np.linalg.norm(nuevo - anterior))
            if paso <= self.vel_max_cm_por_frame * frames:
                self.recorrido_cm[tid] += paso
            self.ultima_pos[tid] = nuevo
            self.ultimo_frame[tid] = frame_idx

    def tabla(self, minimo_m: float = 5.0) -> List[dict]:
        filas = []
        for tid, cm in self.recorrido_cm.items():
            metros = cm / 100.0
            if metros < minimo_m:
                continue
            votos = self.votos_equipo[tid]
            equipo = int(np.argmax(votos)) if votos.sum() > 0 else -1
            filas.append({"id": tid, "equipo": equipo, "metros": round(metros, 1)})
        return sorted(filas, key=lambda f: f["metros"], reverse=True)


class MapaDeCalor:
    """Acumula posiciones por equipo en una cuadrícula de 1 m x 1 m."""

    def __init__(self, celda_cm: int = 100):
        self.celda_cm = celda_cm
        self.forma = (ANCHO_CM // celda_cm, LARGO_CM // celda_cm)  # (filas, columnas)
        self.rejillas = [np.zeros(self.forma, dtype=np.float32) for _ in range(2)]

    def actualizar(self, xy: np.ndarray, equipos: np.ndarray) -> None:
        if len(xy) == 0:
            return
        cols = np.clip((xy[:, 0] // self.celda_cm).astype(int), 0, self.forma[1] - 1)
        filas = np.clip((xy[:, 1] // self.celda_cm).astype(int), 0, self.forma[0] - 1)
        dentro = (
            (xy[:, 0] >= 0) & (xy[:, 0] <= LARGO_CM)
            & (xy[:, 1] >= 0) & (xy[:, 1] <= ANCHO_CM)
        )
        for equipo in (0, 1):
            m = dentro & (equipos == equipo)
            np.add.at(self.rejillas[equipo], (filas[m], cols[m]), 1.0)

    def imagen(self, equipo: int, cancha: np.ndarray, padding: int = 50,
               escala: float = 0.1, opacidad: float = 0.65) -> np.ndarray:
        """Dibuja el mapa de calor de un equipo sobre una imagen de la cancha."""
        rejilla = self.rejillas[equipo]
        alto = int(ANCHO_CM * escala)
        ancho = int(LARGO_CM * escala)
        calor = cv2.resize(rejilla, (ancho, alto), interpolation=cv2.INTER_LINEAR)
        calor = cv2.GaussianBlur(calor, (0, 0), sigmaX=25)
        if calor.max() > 0:
            calor = calor / calor.max()
        color = cv2.applyColorMap((calor * 255).astype(np.uint8), cv2.COLORMAP_JET)
        salida = cancha.copy()
        zona = salida[padding:padding + alto, padding:padding + ancho]
        alfa = (np.clip(calor * 1.5, 0, 1) * opacidad)[..., None]
        zona[:] = (zona * (1 - alfa) + color * alfa).astype(np.uint8)
        return salida


def dibujar_marcador_posesion(
    frame: np.ndarray,
    porcentajes: np.ndarray,
    colores_bgr: List[tuple],
    nombres: List[str],
) -> np.ndarray:
    """Barra de posesión arriba a la derecha, estilo transmisión."""
    h, w = frame.shape[:2]
    escala = max(w / 1920, 0.8)
    ancho_barra = int(520 * escala)
    alto = int(70 * escala)
    x0 = w - ancho_barra - int(30 * escala)
    y0 = int(30 * escala)

    capa = frame.copy()
    cv2.rectangle(capa, (x0, y0), (x0 + ancho_barra, y0 + alto), (255, 255, 255), -1)
    frame = cv2.addWeighted(capa, 0.85, frame, 0.15, 0)

    fuente = cv2.FONT_HERSHEY_SIMPLEX
    t = max(int(2 * escala), 1)
    cv2.putText(frame, "Posesion", (x0 + int(12 * escala), y0 + int(28 * escala)),
                fuente, 0.7 * escala, (0, 0, 0), t, cv2.LINE_AA)

    by = y0 + int(40 * escala)
    bh = int(20 * escala)
    bx = x0 + int(12 * escala)
    bw = ancho_barra - int(24 * escala)
    corte = bx + int(bw * porcentajes[0] / 100)
    cv2.rectangle(frame, (bx, by), (corte, by + bh), colores_bgr[0], -1)
    cv2.rectangle(frame, (corte, by), (bx + bw, by + bh), colores_bgr[1], -1)

    for i, (pct, nombre) in enumerate(zip(porcentajes, nombres)):
        texto = f"{nombre} {pct:.0f}%"
        (tw, _), _ = cv2.getTextSize(texto, fuente, 0.6 * escala, t)
        if i == 0:
            tx = x0 + int(130 * escala)
        else:
            tx = x0 + ancho_barra - tw - int(12 * escala)
        cv2.putText(frame, texto, (tx, y0 + int(28 * escala)), fuente, 0.6 * escala,
                    colores_bgr[i], t, cv2.LINE_AA)
    return frame
