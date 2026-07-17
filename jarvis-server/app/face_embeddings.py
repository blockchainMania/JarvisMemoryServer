"""Face detection + embedding via insightface -- lazy-loaded so app boot is fast
(mirrors embeddings.py's pattern for the text model).

Model: insightface 'buffalo_l' pack, which bundles a face detector (SCRFD) and a
recognition model (ArcFace, 512-dim) behind a single FaceAnalysis.get(image) call --
no separate detector needs to be wired up. Runs on CPU (ctx_id=-1); no paid API,
no network calls at inference time (model weights are downloaded once on first use).
"""
import base64
from typing import List, Optional, Tuple

import numpy as np

_app = None


def _get_app():
    global _app
    if _app is None:
        # Imported lazily so `from app.main import app` doesn't pull onnxruntime/cv2
        # upfront, same reasoning as embeddings.py's lazy sentence-transformers import.
        from insightface.app import FaceAnalysis

        _app = FaceAnalysis(name="buffalo_l")
        _app.prepare(ctx_id=-1, det_size=(640, 640))
    return _app


def embed_face_from_base64(image_base64: str) -> Tuple[Optional[List[float]], int]:
    """Detects faces in the given base64 image and returns (embedding, face_count).

    embedding is a 512-dim L2-normalized vector, only when exactly one face is found.
    embedding is None when face_count == 0 (no face) or face_count > 1 (ambiguous --
    caller should ask the user to recapture with a single person in frame rather than
    guessing which face was meant).
    """
    import cv2

    raw = base64.b64decode(image_base64)
    arr = np.frombuffer(raw, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        return None, 0

    faces = _get_app().get(img)
    if len(faces) != 1:
        return None, len(faces)

    return faces[0].normed_embedding.tolist(), 1
