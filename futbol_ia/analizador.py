"""Pipeline principal: detecta, sigue, separa equipos y proyecta todo a un radar 2D.

Basado en los ejemplos de roboflow/sports (MIT). Añade posesión de balón,
mapas de calor, distancia recorrida y una vista dividida (video + radar).
"""

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Callable, List, Optional

import cv2
import numpy as np
import supervision as sv
from tqdm import tqdm
from ultralytics import YOLO

from sports.annotators.soccer import draw_pitch, draw_points_on_pitch
from sports.common.ball import BallTracker
from sports.common.team import TeamClassifier
from sports.common.view import ViewTransformer
from sports.configs.soccer import SoccerPitchConfiguration

from .estadisticas import Distancias, MapaDeCalor, Posesion, dibujar_marcador_posesion
from .modelos import ruta_modelo

BALON, PORTERO, JUGADOR, ARBITRO = 0, 1, 2, 3

COLORES_HEX = ["#FF1493", "#00BFFF", "#FFD700"]  # equipo 1, equipo 2, árbitro
NOMBRES_EQUIPOS = ["Rosa", "Azul"]
COLOR_BALON = "#FFFFFF"

CANCHA = SoccerPitchConfiguration()

ELIPSES = sv.EllipseAnnotator(color=sv.ColorPalette.from_hex(COLORES_HEX), thickness=2)
ETIQUETAS = sv.LabelAnnotator(
    color=sv.ColorPalette.from_hex(COLORES_HEX),
    text_color=sv.Color.from_hex("#000000"),
    text_padding=4,
    text_thickness=1,
    text_scale=0.45,
    text_position=sv.Position.BOTTOM_CENTER,
)
TRIANGULO_BALON = sv.TriangleAnnotator(color=sv.Color.from_hex("#FFD700"), base=20, height=17)

Progreso = Optional[Callable[[float, str], None]]


def _avisar(progreso: Progreso, fraccion: float, texto: str) -> None:
    if progreso is not None:
        progreso(min(max(fraccion, 0.0), 1.0), texto)


def _recortes(frame: np.ndarray, detecciones: sv.Detections) -> List[np.ndarray]:
    return [sv.crop_image(frame, xyxy) for xyxy in detecciones.xyxy]


def _equipo_porteros(jugadores, equipos_jugadores, porteros) -> np.ndarray:
    """Asigna cada portero al equipo cuyo centro de masa está más cerca."""
    if len(porteros) == 0:
        return np.array([], dtype=int)
    pos_porteros = porteros.get_anchors_coordinates(sv.Position.BOTTOM_CENTER)
    pos_jugadores = jugadores.get_anchors_coordinates(sv.Position.BOTTOM_CENTER)
    centros = []
    for equipo in (0, 1):
        puntos = pos_jugadores[equipos_jugadores == equipo]
        centros.append(puntos.mean(axis=0) if len(puntos) else None)
    resultado = []
    for p in pos_porteros:
        d = [np.linalg.norm(p - c) if c is not None else np.inf for c in centros]
        resultado.append(int(np.argmin(d)))
    return np.array(resultado, dtype=int)


def _transformador(puntos_clave: sv.KeyPoints) -> Optional[ViewTransformer]:
    """Homografía de la imagen a la cancha. Necesita al menos 4 puntos visibles."""
    if len(puntos_clave.xy) == 0:
        return None
    xy = puntos_clave.xy[0]
    mascara = (xy[:, 0] > 1) & (xy[:, 1] > 1)
    # supervision >= 0.29 renombró `confidence` a `keypoint_confidence`
    confianza = getattr(puntos_clave, "keypoint_confidence", None)
    if confianza is None:
        confianza = getattr(puntos_clave, "confidence", None)
    if confianza is not None:
        mascara &= confianza[0] > 0.5
    if mascara.sum() < 4:
        return None
    try:
        return ViewTransformer(
            source=xy[mascara].astype(np.float32),
            target=np.array(CANCHA.vertices)[mascara].astype(np.float32),
        )
    except ValueError:
        return None


def _a_h264(origen: str, destino: str) -> None:
    """Re-codifica a H.264 para que el video se vea en el navegador y en el celular."""
    if shutil.which("ffmpeg") is None:
        shutil.move(origen, destino)
        return
    subprocess.run(
        [
            "ffmpeg", "-y", "-loglevel", "error", "-i", origen,
            "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast",
            "-movflags", "+faststart", destino,
        ],
        check=True,
    )
    os.remove(origen)


def analizar_video(
    ruta_entrada: str,
    ruta_salida: str,
    dispositivo: str = "cuda",
    max_segundos: Optional[float] = None,
    vista: str = "dividida",
    balon_preciso: bool = False,
    progreso: Progreso = None,
) -> dict:
    """Analiza un video de fútbol y guarda el video anotado + estadísticas.

    Args:
        ruta_entrada: video original (mejor si es de transmisión, cámara alta).
        ruta_salida: .mp4 de salida. Junto a él se guardan PNG, JSON y CSV.
        dispositivo: "cuda" (GPU) o "cpu".
        max_segundos: procesar solo los primeros N segundos (None = todo).
        vista: "dividida" (video arriba, radar abajo) o "superpuesta".
        balon_preciso: usa el modelo especial de balón (más lento, detecta mejor).
        progreso: función opcional progreso(fraccion, texto).

    Returns:
        dict con rutas de archivos y estadísticas.
    """
    info = sv.VideoInfo.from_video_path(ruta_entrada)
    fps = float(info.fps or 25)
    fin = info.total_frames
    if max_segundos:
        fin = min(fin, int(max_segundos * fps))
    if not fin:
        raise ValueError("No pude leer frames del video.")

    modelo_jugadores = YOLO(ruta_modelo("jugadores")).to(device=dispositivo)
    modelo_cancha = YOLO(ruta_modelo("cancha")).to(device=dispositivo)

    # 1) Aprender los colores de los dos equipos con ~40 frames de muestra
    _avisar(progreso, 0.0, "Aprendiendo los uniformes de cada equipo")
    paso_muestra = max(fin // 40, 1)
    recortes = []
    for frame in sv.get_video_frames_generator(ruta_entrada, stride=paso_muestra, end=fin):
        r = modelo_jugadores(frame, imgsz=1280, verbose=False)[0]
        d = sv.Detections.from_ultralytics(r)
        recortes += _recortes(frame, d[d.class_id == JUGADOR])
    if len(recortes) < 10:
        raise ValueError(
            "Casi no detecté jugadores. Usa un video de transmisión con cámara alta y amplia."
        )
    clasificador = TeamClassifier(device=dispositivo)
    clasificador.fit(recortes)

    # 2) Detector de balón
    rebanador = None
    if balon_preciso:
        modelo_balon = YOLO(ruta_modelo("balon")).to(device=dispositivo)

        def _detectar(rebanada: np.ndarray) -> sv.Detections:
            return sv.Detections.from_ultralytics(
                modelo_balon(rebanada, imgsz=640, verbose=False)[0])

        rebanador = sv.InferenceSlicer(
            callback=_detectar,
            overlap_filter_strategy=sv.OverlapFilter.NONE,
            slice_wh=(640, 640),
        )
    seguidor_balon = BallTracker(buffer_size=20)
    seguidor = sv.ByteTrack(frame_rate=int(round(fps)), minimum_consecutive_frames=3)

    posesion = Posesion()
    distancias = Distancias(fps=fps)
    calor = MapaDeCalor()

    # 3) Preparar el video de salida
    w, h = info.width, info.height
    cancha_base = draw_pitch(CANCHA)
    if vista == "dividida":
        radar_w = w
        radar_h = int(round(cancha_base.shape[0] * w / cancha_base.shape[1]))
        salida_w, salida_h = w, h + radar_h
    else:
        salida_w, salida_h = w, h

    Path(ruta_salida).parent.mkdir(parents=True, exist_ok=True)
    temporal = tempfile.NamedTemporaryFile(suffix=".mp4", delete=False).name
    escritor = cv2.VideoWriter(
        temporal, cv2.VideoWriter_fourcc(*"mp4v"), fps, (salida_w, salida_h))

    colores_bgr = [sv.Color.from_hex(c).as_bgr() for c in COLORES_HEX]
    ultimo_transformador = None

    frames = sv.get_video_frames_generator(ruta_entrada, end=fin)
    for idx, frame in enumerate(tqdm(frames, total=fin, desc="Analizando")):
        if idx % 10 == 0:
            _avisar(progreso, idx / fin, f"Analizando frame {idx} de {fin}")

        # Cancha -> homografía (si en este frame no hay suficientes puntos, usa la anterior)
        r_cancha = modelo_cancha(frame, verbose=False)[0]
        t = _transformador(sv.KeyPoints.from_ultralytics(r_cancha))
        if t is not None:
            ultimo_transformador = t
        t = ultimo_transformador

        # Personas y balón
        r = modelo_jugadores(frame, imgsz=1280, verbose=False)[0]
        todo = sv.Detections.from_ultralytics(r)
        balon = rebanador(frame).with_nms(threshold=0.1) if rebanador else todo[todo.class_id == BALON]
        balon = seguidor_balon.update(balon)
        if len(balon):
            balon.xyxy = sv.pad_boxes(xyxy=balon.xyxy, px=10)

        personas = todo[todo.class_id != BALON].with_nms(threshold=0.5, class_agnostic=True)
        personas = seguidor.update_with_detections(personas)

        jugadores = personas[personas.class_id == JUGADOR]
        equipos_j = np.asarray(clasificador.predict(_recortes(frame, jugadores)), dtype=int)
        porteros = personas[personas.class_id == PORTERO]
        equipos_p = _equipo_porteros(jugadores, equipos_j, porteros)
        arbitros = personas[personas.class_id == ARBITRO]

        personas = sv.Detections.merge([jugadores, porteros, arbitros])
        colores = np.array(
            equipos_j.tolist() + equipos_p.tolist() + [2] * len(arbitros), dtype=int)

        # Proyección a la cancha y estadísticas
        balon_xy = None
        xy_cancha = np.empty((0, 2), dtype=np.float32)
        if t is not None:
            xy_cancha = t.transform_points(
                personas.get_anchors_coordinates(sv.Position.BOTTOM_CENTER))
            if len(balon):
                balon_xy = t.transform_points(
                    balon.get_anchors_coordinates(sv.Position.BOTTOM_CENTER))[0]

            de_equipo = colores < 2
            posesion.actualizar(balon_xy, xy_cancha[de_equipo], colores[de_equipo])
            calor.actualizar(xy_cancha[de_equipo], colores[de_equipo])
            if personas.tracker_id is not None and len(personas):
                distancias.actualizar(
                    idx, personas.tracker_id[de_equipo], xy_cancha[de_equipo], colores[de_equipo])

        # Dibujar sobre el video
        anotado = frame.copy()
        etiquetas = [str(i) for i in (personas.tracker_id if personas.tracker_id is not None else [])]
        anotado = ELIPSES.annotate(anotado, personas, custom_color_lookup=colores)
        if etiquetas:
            anotado = ETIQUETAS.annotate(anotado, personas, etiquetas, custom_color_lookup=colores)
        anotado = TRIANGULO_BALON.annotate(anotado, balon)
        anotado = dibujar_marcador_posesion(
            anotado, posesion.porcentajes(), colores_bgr[:2], NOMBRES_EQUIPOS)

        # Radar
        radar = cancha_base.copy()
        if t is not None:
            for c in (0, 1, 2):
                radar = draw_points_on_pitch(
                    CANCHA, xy_cancha[colores == c],
                    face_color=sv.Color.from_hex(COLORES_HEX[c]), radius=20, pitch=radar)
            if balon_xy is not None:
                radar = draw_points_on_pitch(
                    CANCHA, balon_xy[None, :], face_color=sv.Color.from_hex(COLOR_BALON),
                    radius=12, pitch=radar)

        if vista == "dividida":
            radar = cv2.resize(radar, (radar_w, radar_h))
            final = np.vstack([anotado, radar])
        else:
            chico = sv.resize_image(radar, (w // 2, h // 2))
            rh, rw = chico.shape[:2]
            rect = sv.Rect(x=w // 2 - rw // 2, y=h - rh, width=rw, height=rh)
            final = sv.draw_image(anotado, chico, opacity=0.6, rect=rect)

        escritor.write(final)

    escritor.release()
    _avisar(progreso, 0.97, "Guardando video y estadísticas")
    _a_h264(temporal, ruta_salida)

    # 4) Archivos de estadísticas
    base = str(Path(ruta_salida).with_suffix(""))
    rutas_calor = []
    for equipo in (0, 1):
        ruta = f"{base}_calor_{NOMBRES_EQUIPOS[equipo].lower()}.png"
        cv2.imwrite(ruta, calor.imagen(equipo, cancha_base))
        rutas_calor.append(ruta)

    pct = posesion.porcentajes()
    tabla = distancias.tabla()
    for fila in tabla:
        fila["equipo"] = NOMBRES_EQUIPOS[fila["equipo"]] if fila["equipo"] in (0, 1) else "?"

    resumen = {
        "segundos_analizados": round(fin / fps, 1),
        "posesion": {NOMBRES_EQUIPOS[0]: round(float(pct[0]), 1),
                     NOMBRES_EQUIPOS[1]: round(float(pct[1]), 1)},
        "distancia_por_jugador": tabla,
        "nota": "Los IDs de jugador pueden cambiar si alguien sale de cuadro; "
                "las distancias son aproximadas.",
    }
    with open(f"{base}_estadisticas.json", "w", encoding="utf-8") as f:
        json.dump(resumen, f, ensure_ascii=False, indent=2)
    with open(f"{base}_distancias.csv", "w", encoding="utf-8") as f:
        f.write("id,equipo,metros\n")
        for fila in tabla:
            f.write(f"{fila['id']},{fila['equipo']},{fila['metros']}\n")

    _avisar(progreso, 1.0, "Listo")
    return {
        "video": ruta_salida,
        "mapas_de_calor": rutas_calor,
        "json": f"{base}_estadisticas.json",
        "csv": f"{base}_distancias.csv",
        "resumen": resumen,
    }
