"""Análisis cuadro por cuadro para el modo en vivo (la app de Android manda capturas).

Mantiene estado entre llamadas: seguimiento de jugadores, colores de equipo y posesión.
"""

import threading
import time
from typing import List, Optional, Sequence

import numpy as np
import supervision as sv
from ultralytics import YOLO

from sports.common.ball import BallTracker
from sports.common.team import TeamClassifier

from .analizador import (ARBITRO, BALON, JUGADOR, PORTERO, _equipo_porteros,
                         _recortes, _transformador)
from .estadisticas import Posesion
from .modelos import ruta_modelo

RECORTES_PARA_CALIBRAR = 80
CUADROS_PARA_CALIBRAR = 6


class AnalizadorEnVivo:
    def __init__(self, dispositivo: str = "cuda"):
        self.dispositivo = dispositivo
        self.modelo_jugadores = YOLO(ruta_modelo("jugadores")).to(device=dispositivo)
        self.modelo_cancha = YOLO(ruta_modelo("cancha")).to(device=dispositivo)
        self.candado = threading.Lock()
        self.reiniciar()

    def reiniciar(self) -> None:
        self.clasificador: Optional[TeamClassifier] = None
        self.recortes: List[np.ndarray] = []
        self.cuadros_calibracion = 0
        self.seguidor = sv.ByteTrack(frame_rate=5, lost_track_buffer=15,
                                     minimum_consecutive_frames=1)
        self.seguidor_balon = BallTracker(buffer_size=6)
        self.posesion = Posesion(distancia_max_cm=350.0)
        self.ultimo_transformador = None
        self.sin_cancha = 0
        self.cuadros = 0

    # ------------------------------------------------------------------
    def _calibrar(self, frame: np.ndarray, jugadores: sv.Detections) -> float:
        """Junta recortes de jugadores y, cuando hay suficientes, aprende los uniformes."""
        if self.cuadros % 2 == 0 and len(jugadores) >= 4:
            self.recortes += _recortes(frame, jugadores)
            self.cuadros_calibracion += 1
        listo = min(len(self.recortes) / RECORTES_PARA_CALIBRAR,
                    self.cuadros_calibracion / CUADROS_PARA_CALIBRAR)
        if listo >= 1.0:
            clasificador = TeamClassifier(device=self.dispositivo)
            clasificador.fit(self.recortes)
            self.clasificador = clasificador
            self.recortes = []
        return min(listo, 1.0)

    def analizar(self, frame: np.ndarray,
                 mascaras: Sequence[Sequence[float]] = ()) -> dict:
        with self.candado:
            return self._analizar(frame, mascaras)

    def _analizar(self, frame: np.ndarray, mascaras) -> dict:
        t0 = time.time()
        self.cuadros += 1
        h, w = frame.shape[:2]

        # Tapar la burbuja y el panel de la app para que la IA no los confunda con la cancha
        for x0, y0, x1, y1 in mascaras:
            frame[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)] = 0

        t = _transformador(sv.KeyPoints.from_ultralytics(
            self.modelo_cancha(frame, verbose=False)[0]))
        if t is not None:
            self.ultimo_transformador = t
            self.sin_cancha = 0
        else:
            self.sin_cancha += 1
            if self.sin_cancha > 4:  # repetición, acercamiento o tribuna
                self.ultimo_transformador = None
        t = self.ultimo_transformador

        todo = sv.Detections.from_ultralytics(
            self.modelo_jugadores(frame, imgsz=1280, verbose=False)[0])
        balon = self.seguidor_balon.update(todo[todo.class_id == BALON])
        personas = todo[todo.class_id != BALON].with_nms(threshold=0.5, class_agnostic=True)
        personas = self.seguidor.update_with_detections(personas)

        jugadores = personas[personas.class_id == JUGADOR]
        porteros = personas[personas.class_id == PORTERO]
        arbitros = personas[personas.class_id == ARBITRO]

        calibracion = 1.0
        if self.clasificador is None:
            calibracion = self._calibrar(frame, jugadores)
        if self.clasificador is not None:
            eq_j = np.asarray(self.clasificador.predict(_recortes(frame, jugadores)), dtype=int)
            eq_p = _equipo_porteros(jugadores, eq_j, porteros)
        else:
            eq_j = np.full(len(jugadores), -1, dtype=int)
            eq_p = np.full(len(porteros), -1, dtype=int)

        personas = sv.Detections.merge([jugadores, porteros, arbitros])
        equipos = np.array(eq_j.tolist() + eq_p.tolist() + [2] * len(arbitros), dtype=int)
        pies = personas.get_anchors_coordinates(sv.Position.BOTTOM_CENTER)

        xy = None
        balon_xy = None
        if t is not None and len(personas):
            xy = t.transform_points(pies)
        if t is not None and len(balon):
            balon_xy = t.transform_points(
                balon.get_anchors_coordinates(sv.Position.BOTTOM_CENTER))[0]
        if xy is not None and self.clasificador is not None:
            de_equipo = (equipos == 0) | (equipos == 1)
            self.posesion.actualizar(balon_xy, xy[de_equipo], equipos[de_equipo])

        ids = personas.tracker_id if personas.tracker_id is not None else np.zeros(len(personas))
        lista = []
        for i in range(len(personas)):
            x0, y0, x1, y1 = personas.xyxy[i]
            item = {
                "id": int(ids[i]),
                "equipo": int(equipos[i]),
                "px": round(float(pies[i][0] / w), 4),
                "py": round(float(pies[i][1] / h), 4),
                "ancho": round(float((x1 - x0) / w), 4),
            }
            if xy is not None:
                item["x"] = round(float(xy[i][0]))
                item["y"] = round(float(xy[i][1]))
            lista.append(item)

        info_balon = None
        if len(balon):
            bx, by = balon.get_anchors_coordinates(sv.Position.CENTER)[0]
            info_balon = {"px": round(float(bx / w), 4), "py": round(float(by / h), 4)}
            if balon_xy is not None:
                info_balon["x"] = round(float(balon_xy[0]))
                info_balon["y"] = round(float(balon_xy[1]))

        if self.clasificador is None:
            estado = "calibrando"
        elif t is None:
            estado = "sin_cancha"
        else:
            estado = "ok"

        pct = self.posesion.porcentajes()
        return {
            "estado": estado,
            "calibracion": round(calibracion, 2),
            "jugadores": lista,
            "balon": info_balon,
            "posesion": [round(float(pct[0]), 1), round(float(pct[1]), 1)],
            "ms": int((time.time() - t0) * 1000),
        }
