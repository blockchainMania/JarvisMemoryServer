from datetime import datetime
from typing import List, Literal, Optional
from uuid import UUID

from pydantic import BaseModel, Field


# ─── People ───────────────────────────────────────────────────
class PersonCreate(BaseModel):
    name: str
    aliases: List[str] = Field(default_factory=list)
    org: Optional[str] = None
    role: Optional[str] = None
    first_met_at: Optional[datetime] = None
    last_met_at: Optional[datetime] = None
    face_embedding: Optional[List[float]] = None
    notes_summary: Optional[str] = None
    metadata: dict = Field(default_factory=dict)


class PersonOut(BaseModel):
    id: UUID
    name: str
    aliases: List[str]
    org: Optional[str]
    role: Optional[str]
    first_met_at: Optional[datetime]
    last_met_at: Optional[datetime]
    notes_summary: Optional[str]
    metadata: dict
    created_at: datetime
    updated_at: datetime


class PersonSearchRequest(BaseModel):
    face_embedding: Optional[List[float]] = None
    query: Optional[str] = None
    top_k: int = 5


class PersonMatch(BaseModel):
    person: PersonOut
    score: float


# ─── Meetings ─────────────────────────────────────────────────
class MeetingCreate(BaseModel):
    title: Optional[str] = None
    person_ids: List[UUID] = Field(default_factory=list)
    started_at: datetime
    ended_at: Optional[datetime] = None
    location: Optional[str] = None
    summary: Optional[str] = None
    raw_transcript: Optional[str] = None
    metadata: dict = Field(default_factory=dict)


class MeetingOut(BaseModel):
    id: UUID
    title: Optional[str]
    person_ids: List[UUID]
    started_at: datetime
    ended_at: Optional[datetime]
    location: Optional[str]
    summary: Optional[str]
    raw_transcript: Optional[str]
    metadata: dict
    created_at: datetime


class MeetingSearchRequest(BaseModel):
    query: str
    top_k: int = 5
    time_from: Optional[datetime] = None
    time_to: Optional[datetime] = None
    person_id: Optional[UUID] = None


# ─── Memories ─────────────────────────────────────────────────
class MemoryCreate(BaseModel):
    captured_at: datetime
    text: str
    related_person_ids: List[UUID] = Field(default_factory=list)
    related_meeting_id: Optional[UUID] = None
    source: Literal["camera", "voice", "manual", "derived"] = "manual"
    metadata: dict = Field(default_factory=dict)


class LifeMemoryCreate(BaseModel):
    captured_at: datetime
    user_note: str
    ai_interpretation: str
    people_text: Optional[str] = None
    labels: List[str] = Field(default_factory=list)
    entities: List[dict] = Field(default_factory=list)
    related_person_ids: List[UUID] = Field(default_factory=list)
    image_base64: Optional[str] = None
    image_mime_type: str = "image/jpeg"
    source: Literal["camera", "voice", "manual", "derived"] = "camera"
    metadata: dict = Field(default_factory=dict)


class MemoryOut(BaseModel):
    id: UUID
    captured_at: datetime
    text: str
    related_person_ids: List[UUID]
    related_meeting_id: Optional[UUID]
    source: str
    metadata: dict
    created_at: datetime


class MemorySearchRequest(BaseModel):
    query: str
    top_k: int = 5
    time_from: Optional[datetime] = None
    time_to: Optional[datetime] = None
    person_id: Optional[UUID] = None


class MemoryRecentRequest(BaseModel):
    limit: int = 20
    memory_type: Optional[str] = "life_scene"


class MemoryMatch(BaseModel):
    memory: MemoryOut
    score: float


# ─── Needs ────────────────────────────────────────────────────
class NeedCreate(BaseModel):
    person_id: UUID
    meeting_id: Optional[UUID] = None
    text: str
    category: Literal["pain", "interest", "constraint", "budget", "timeline"] = "interest"
    confidence: float = 1.0
    metadata: dict = Field(default_factory=dict)


class NeedOut(BaseModel):
    id: UUID
    person_id: UUID
    meeting_id: Optional[UUID]
    text: str
    category: str
    confidence: float
    metadata: dict
    created_at: datetime


# ─── Proposal context (for client-side Gemini synthesis) ──────
class ProposalContext(BaseModel):
    person: PersonOut
    needs: List[NeedOut]
    recent_meetings: List[MeetingOut]
