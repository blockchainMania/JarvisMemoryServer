from typing import List
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from psycopg.types.json import Jsonb

from ..auth import require_api_key
from ..db import get_conn
from ..embeddings import embed_text
from ..schemas import MeetingCreate, MeetingOut, MeetingSearchRequest

router = APIRouter(
    prefix="/meetings",
    tags=["meetings"],
    dependencies=[Depends(require_api_key)],
)

_MEETING_FIELDS = list(MeetingOut.model_fields.keys())


def _row_to_meeting(row: dict) -> MeetingOut:
    return MeetingOut(**{k: row[k] for k in _MEETING_FIELDS})


@router.post("", response_model=MeetingOut, status_code=201)
def create_meeting(body: MeetingCreate) -> MeetingOut:
    summary_embedding = embed_text(body.summary) if body.summary else None
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO meetings
                    (title, person_ids, started_at, ended_at, location,
                     summary, summary_embedding, raw_transcript, metadata)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                RETURNING *
                """,
                (
                    body.title,
                    body.person_ids,
                    body.started_at,
                    body.ended_at,
                    body.location,
                    body.summary,
                    summary_embedding,
                    body.raw_transcript,
                    Jsonb(body.metadata),
                ),
            )
            row = cur.fetchone()

            memory_text = "\n".join(
                part
                for part in [
                    f"미팅: {body.title}" if body.title else "미팅 기록",
                    f"요약: {body.summary}" if body.summary else None,
                    f"장소: {body.location}" if body.location else None,
                    f"원문: {body.raw_transcript}" if body.raw_transcript else None,
                ]
                if part
            )
            cur.execute(
                """
                INSERT INTO memories
                    (captured_at, text, embedding, related_person_ids,
                     related_meeting_id, source, metadata)
                VALUES (%s, %s, %s, %s, %s, 'derived', %s)
                """,
                (
                    body.started_at,
                    memory_text,
                    embed_text(memory_text),
                    body.person_ids,
                    row["id"],
                    Jsonb(
                        {
                            "memory_type": "meeting",
                            "origin_meeting_id": str(row["id"]),
                        }
                    ),
                ),
            )

            if body.person_ids:
                # Touch first_met_at / last_met_at on each person.
                cur.execute(
                    """
                    UPDATE people SET
                        last_met_at  = GREATEST(COALESCE(last_met_at,  %s), %s),
                        first_met_at = LEAST(   COALESCE(first_met_at, %s), %s),
                        updated_at   = now()
                    WHERE id = ANY(%s::uuid[])
                    """,
                    (
                        body.started_at, body.started_at,
                        body.started_at, body.started_at,
                        body.person_ids,
                    ),
                )
        conn.commit()
    return _row_to_meeting(row)


@router.get("/{meeting_id}", response_model=MeetingOut)
def get_meeting(meeting_id: UUID) -> MeetingOut:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM meetings WHERE id = %s", (meeting_id,))
            row = cur.fetchone()
            if not row:
                raise HTTPException(404, "meeting not found")
    return _row_to_meeting(row)


@router.post("/search", response_model=List[MeetingOut])
def search_meetings(body: MeetingSearchRequest) -> List[MeetingOut]:
    qvec = embed_text(body.query)
    sql = [
        "SELECT *, 1 - (summary_embedding <=> %s::vector) AS _score",
        "FROM meetings",
        "WHERE summary_embedding IS NOT NULL",
    ]
    params: list = [qvec]
    if body.time_from:
        sql.append("AND started_at >= %s"); params.append(body.time_from)
    if body.time_to:
        sql.append("AND started_at <= %s"); params.append(body.time_to)
    if body.person_id:
        sql.append("AND %s = ANY(person_ids)"); params.append(body.person_id)
    sql.append("ORDER BY summary_embedding <=> %s::vector LIMIT %s")
    params.extend([qvec, body.top_k])

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("\n".join(sql), params)
            rows = cur.fetchall()
    return [_row_to_meeting(r) for r in rows]
