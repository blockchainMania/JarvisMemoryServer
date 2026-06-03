import base64
from pathlib import Path
from typing import List
from uuid import uuid4

from fastapi import APIRouter, Depends
from psycopg.types.json import Jsonb

from ..auth import require_api_key
from ..db import get_conn
from ..embeddings import embed_text
from ..schemas import (
    LifeMemoryCreate,
    MemoryCreate,
    MemoryMatch,
    MemoryOut,
    MemoryRecentRequest,
    MemorySearchRequest,
)

router = APIRouter(
    prefix="/memory",
    tags=["memory"],
    dependencies=[Depends(require_api_key)],
)

_MEMORY_FIELDS = list(MemoryOut.model_fields.keys())
_IMAGE_DIR = Path(__file__).resolve().parents[2] / "data" / "memory-images"
_IMAGE_EXTENSIONS = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
}


def _row_to_memory(row: dict) -> MemoryOut:
    return MemoryOut(**{k: row[k] for k in _MEMORY_FIELDS})


def _save_memory_image(image_base64: str, mime_type: str) -> dict:
    raw_base64 = image_base64.split(",", 1)[-1]
    image_bytes = base64.b64decode(raw_base64, validate=True)
    extension = _IMAGE_EXTENSIONS.get(mime_type, ".jpg")
    _IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"{uuid4()}{extension}"
    path = _IMAGE_DIR / filename
    path.write_bytes(image_bytes)
    return {
        "image_path": str(path),
        "image_filename": filename,
        "image_mime_type": mime_type,
        "image_size_bytes": len(image_bytes),
    }


@router.post("/save", response_model=MemoryOut, status_code=201)
def save_memory(body: MemoryCreate) -> MemoryOut:
    embedding = embed_text(body.text)
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO memories
                    (captured_at, text, embedding, related_person_ids,
                     related_meeting_id, source, metadata)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                RETURNING *
                """,
                (
                    body.captured_at,
                    body.text,
                    embedding,
                    body.related_person_ids,
                    body.related_meeting_id,
                    body.source,
                    Jsonb(body.metadata),
                ),
            )
            row = cur.fetchone()
        conn.commit()
    return _row_to_memory(row)


@router.post("/recent", response_model=List[MemoryOut])
def recent_memories(body: MemoryRecentRequest) -> List[MemoryOut]:
    limit = max(1, min(body.limit, 100))
    sql = [
        "SELECT *",
        "FROM memories",
        "WHERE 1 = 1",
    ]
    params: list = []
    if body.memory_type:
        sql.append("AND metadata->>'memory_type' = %s")
        params.append(body.memory_type)
    sql.append("ORDER BY captured_at DESC, created_at DESC LIMIT %s")
    params.append(limit)

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("\n".join(sql), params)
            rows = cur.fetchall()

    return [_row_to_memory(r) for r in rows]


@router.post("/life/save", response_model=MemoryOut, status_code=201)
def save_life_memory(body: LifeMemoryCreate) -> MemoryOut:
    text_parts = [
        f"사용자 메모: {body.user_note}",
        f"AI 장면 해석: {body.ai_interpretation}",
    ]
    if body.people_text:
        text_parts.append(f"관련 사람: {body.people_text}")
    text = "\n".join(text_parts)
    embedding = embed_text(text)

    metadata = dict(body.metadata)
    metadata.update(
        {
            "memory_type": "life_scene",
            "user_note": body.user_note,
            "ai_interpretation": body.ai_interpretation,
            "people_text": body.people_text,
        }
    )
    if body.image_base64:
        metadata.update(_save_memory_image(body.image_base64, body.image_mime_type))

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO memories
                    (captured_at, text, embedding, related_person_ids,
                     related_meeting_id, source, metadata)
                VALUES (%s, %s, %s, %s, NULL, %s, %s)
                RETURNING *
                """,
                (
                    body.captured_at,
                    text,
                    embedding,
                    body.related_person_ids,
                    body.source,
                    Jsonb(metadata),
                ),
            )
            row = cur.fetchone()
        conn.commit()
    return _row_to_memory(row)


@router.post("/search", response_model=List[MemoryMatch])
def search_memories(body: MemorySearchRequest) -> List[MemoryMatch]:
    qvec = embed_text(body.query)
    sql = [
        "SELECT *, 1 - (embedding <=> %s::vector) AS _score",
        "FROM memories",
        "WHERE embedding IS NOT NULL",
    ]
    params: list = [qvec]
    if body.time_from:
        sql.append("AND captured_at >= %s"); params.append(body.time_from)
    if body.time_to:
        sql.append("AND captured_at <= %s"); params.append(body.time_to)
    if body.person_id:
        sql.append("AND %s = ANY(related_person_ids)"); params.append(body.person_id)
    sql.append("ORDER BY embedding <=> %s::vector LIMIT %s")
    params.extend([qvec, body.top_k])

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("\n".join(sql), params)
            rows = cur.fetchall()

    return [
        MemoryMatch(memory=_row_to_memory(r), score=float(r["_score"]))
        for r in rows
    ]
