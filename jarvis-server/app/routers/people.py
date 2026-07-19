import logging
import sys
from datetime import datetime, timezone
from typing import List
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from psycopg.types.json import Jsonb

from ..auth import require_api_key
from ..db import get_conn
from ..embeddings import embed_text
from ..face_embeddings import embed_face_from_base64
from ..schemas import (
    PersonCreate,
    PersonIdentifyRequest,
    PersonMatch,
    PersonOut,
    PersonSearchRequest,
)

router = APIRouter(
    prefix="/people",
    tags=["people"],
    dependencies=[Depends(require_api_key)],
)

# No similarity cutoff is applied to identify_person matches yet -- there's nothing to tune
# against without seeing real score distributions first, so this logs every match attempt
# (top candidate scores, and face-detection failures) instead. Same pattern as
# app/routers/agent_flash.py's logger (dedicated handler, not root logger -- uvicorn's own
# root config swallows plain logging.basicConfig() calls).
logger = logging.getLogger("jarvis.people")
logger.setLevel(logging.INFO)
if not logger.handlers:
    _handler = logging.StreamHandler(sys.stderr)
    _handler.setFormatter(logging.Formatter("%(asctime)s %(name)s %(levelname)s %(message)s"))
    logger.addHandler(_handler)
    logger.propagate = False

_PERSON_FIELDS = list(PersonOut.model_fields.keys())

# Face count 0 or >1 both come back as this "no usable embedding" error text so the
# root agent can relay it to the user verbatim instead of guessing which face was meant.
_NO_FACE_ERROR = "얼굴이 잘 안 보여요. 정면으로 다시 비춰주시겠어요?"
_MULTI_FACE_ERROR = "여러 사람이 보여서 정확히 인식할 수 없어요. 한 분만 나오게 다시 비춰주시겠어요?"


def _row_to_person(row: dict) -> PersonOut:
    return PersonOut(**{k: row[k] for k in _PERSON_FIELDS})


def _face_error(face_count: int) -> HTTPException:
    return HTTPException(422, _MULTI_FACE_ERROR if face_count > 1 else _NO_FACE_ERROR)


def _search_by_face_embedding(embedding: List[float], top_k: int) -> List[PersonMatch]:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT *, 1 - (face_embedding <=> %s::vector) AS _score
                FROM people
                WHERE face_embedding IS NOT NULL
                ORDER BY face_embedding <=> %s::vector
                LIMIT %s
                """,
                (embedding, embedding, top_k),
            )
            rows = cur.fetchall()
    return [PersonMatch(person=_row_to_person(r), score=float(r["_score"])) for r in rows]


@router.post("/identify", response_model=List[PersonMatch])
def identify_person(body: PersonIdentifyRequest) -> List[PersonMatch]:
    embedding, face_count = embed_face_from_base64(body.image_base64)
    if embedding is None:
        logger.info("identify_person: no usable embedding (face_count=%d)", face_count)
        raise _face_error(face_count)
    matches = _search_by_face_embedding(embedding, body.top_k)
    logger.info(
        "identify_person: top matches %s",
        [(m.person.name, round(m.score, 4)) for m in matches],
    )
    return matches


@router.post("", response_model=PersonOut, status_code=201)
def create_person(body: PersonCreate) -> PersonOut:
    face_embedding = body.face_embedding
    if face_embedding is None and body.image_base64:
        face_embedding, face_count = embed_face_from_base64(body.image_base64)
        if face_embedding is None:
            raise _face_error(face_count)

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO people
                    (name, aliases, org, role, phone, email, address, first_met_at, last_met_at,
                     face_embedding, notes_summary, metadata)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                RETURNING *
                """,
                (
                    body.name,
                    body.aliases,
                    body.org,
                    body.role,
                    body.phone,
                    body.email,
                    body.address,
                    body.first_met_at,
                    body.last_met_at,
                    face_embedding,
                    body.notes_summary,
                    Jsonb(body.metadata),
                ),
            )
            row = cur.fetchone()
            captured_at = body.first_met_at or body.last_met_at or datetime.now(timezone.utc)
            memory_text = "\n".join(
                part
                for part in [
                    f"사람: {body.name}",
                    f"별칭: {', '.join(body.aliases)}" if body.aliases else None,
                    f"소속: {body.org}" if body.org else None,
                    f"직책: {body.role}" if body.role else None,
                    f"메모: {body.notes_summary}" if body.notes_summary else None,
                ]
                if part
            )
            cur.execute(
                """
                INSERT INTO memories
                    (captured_at, text, embedding, related_person_ids,
                     source, metadata)
                VALUES (%s, %s, %s, %s, 'derived', %s)
                """,
                (
                    captured_at,
                    memory_text,
                    embed_text(memory_text),
                    [row["id"]],
                    Jsonb(
                        {
                            "memory_type": "person_profile",
                            "origin_person_id": str(row["id"]),
                        }
                    ),
                ),
            )
        conn.commit()
    return _row_to_person(row)


@router.get("/{person_id}", response_model=PersonOut)
def get_person(person_id: UUID) -> PersonOut:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM people WHERE id = %s", (person_id,))
            row = cur.fetchone()
            if not row:
                raise HTTPException(404, "person not found")
    return _row_to_person(row)


@router.post("/search", response_model=List[PersonMatch])
def search_people(body: PersonSearchRequest) -> List[PersonMatch]:
    if body.face_embedding is None and not body.query:
        raise HTTPException(400, "either face_embedding or query is required")

    if body.face_embedding is not None:
        return _search_by_face_embedding(body.face_embedding, body.top_k)

    like = f"%{body.query}%"
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT *, 1.0::float AS _score
                FROM people
                WHERE name ILIKE %s
                   OR EXISTS (SELECT 1 FROM unnest(aliases) a WHERE a ILIKE %s)
                   OR org ILIKE %s
                LIMIT %s
                """,
                (like, like, like, body.top_k),
            )
            rows = cur.fetchall()

    return [
        PersonMatch(person=_row_to_person(r), score=float(r["_score"]))
        for r in rows
    ]
