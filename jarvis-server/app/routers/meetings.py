import json
from datetime import datetime, timezone
from pathlib import Path
from typing import List
from uuid import UUID
from uuid import uuid4

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from psycopg.types.json import Jsonb

from ..auth import require_api_key
from ..config import settings
from ..db import get_conn
from ..embeddings import embed_text
from ..meeting_ai import summarize_transcript, transcribe_audio
from ..schemas import MeetingCreate, MeetingOut, MeetingSearchRequest

router = APIRouter(
    prefix="/meetings",
    tags=["meetings"],
    dependencies=[Depends(require_api_key)],
)

_MEETING_FIELDS = list(MeetingOut.model_fields.keys())
_RECORDING_DIR = Path(__file__).resolve().parents[2] / "data" / "meeting-recordings"
_MAX_AUDIO_BYTES = 25 * 1024 * 1024


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


@router.post("/recordings", status_code=201)
async def create_meeting_from_recording(
    audio: UploadFile = File(...),
    title: str = Form(""),
    started_at: str = Form(""),
    ended_at: str = Form(""),
    person_ids: str = Form("[]"),
):
    suffix = Path(audio.filename or "meeting.wav").suffix.lower()
    if suffix not in {".wav", ".mp3", ".m4a", ".mp4", ".webm", ".mpeg", ".mpga"}:
        raise HTTPException(400, "unsupported audio format")

    audio_bytes = await audio.read(_MAX_AUDIO_BYTES + 1)
    if not audio_bytes:
        raise HTTPException(400, "empty audio file")
    if len(audio_bytes) > _MAX_AUDIO_BYTES:
        raise HTTPException(413, "audio file exceeds 25 MB")

    _RECORDING_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"{uuid4()}{suffix}"
    path = _RECORDING_DIR / filename
    path.write_bytes(audio_bytes)

    try:
        parsed_person_ids = [UUID(value) for value in json.loads(person_ids)]
    except (ValueError, TypeError, json.JSONDecodeError):
        raise HTTPException(400, "person_ids must be a JSON UUID array")

    try:
        started = datetime.fromisoformat(started_at) if started_at else datetime.now(timezone.utc)
        ended = datetime.fromisoformat(ended_at) if ended_at else datetime.now(timezone.utc)
        transcript = await run_in_threadpool(transcribe_audio, path)
        if not transcript:
            raise HTTPException(422, "no speech was transcribed")
        summary_data = await run_in_threadpool(summarize_transcript, transcript, title)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, f"meeting AI processing failed: {exc}") from exc

    meeting_title = summary_data.get("title") or title or "회의 녹음"
    summary = summary_data.get("summary") or transcript[:500]
    metadata = {
        "recording_filename": filename,
        "recording_path": str(path),
        "recording_mime_type": audio.content_type or "audio/wav",
        "recording_size_bytes": len(audio_bytes),
        "transcription_model": settings.openai_transcription_model,
        "summary_model": settings.openai_summary_model,
        "decisions": summary_data.get("decisions", []),
        "action_items": summary_data.get("action_items", []),
        "people": summary_data.get("people", []),
        "companies": summary_data.get("companies", []),
        "keywords": summary_data.get("keywords", []),
    }

    meeting_embedding = embed_text(summary)
    memory_text = "\n".join(
        [
            f"미팅: {meeting_title}",
            f"요약: {summary}",
            f"결정사항: {', '.join(metadata['decisions'])}",
            f"할일: {', '.join(metadata['action_items'])}",
            f"원문: {transcript}",
        ]
    )
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO meetings
                    (title, person_ids, started_at, ended_at, summary,
                     summary_embedding, raw_transcript, metadata)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                RETURNING *
                """,
                (
                    meeting_title,
                    parsed_person_ids,
                    started,
                    ended,
                    summary,
                    meeting_embedding,
                    transcript,
                    Jsonb(metadata),
                ),
            )
            meeting = cur.fetchone()
            cur.execute(
                """
                INSERT INTO memories
                    (captured_at, text, embedding, related_person_ids,
                     related_meeting_id, source, metadata)
                VALUES (%s, %s, %s, %s, %s, 'voice', %s)
                RETURNING *
                """,
                (
                    started,
                    memory_text,
                    embed_text(memory_text),
                    parsed_person_ids,
                    meeting["id"],
                    Jsonb(
                        {
                            "memory_type": "meeting_recording",
                            "origin_meeting_id": str(meeting["id"]),
                            **metadata,
                        }
                    ),
                ),
            )
            memory = cur.fetchone()
        conn.commit()

    return {
        "meeting": _row_to_meeting(meeting).model_dump(mode="json"),
        "memory_id": str(memory["id"]),
        "summary": summary_data,
        "transcript": transcript,
    }


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
