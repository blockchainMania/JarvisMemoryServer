-- Jarvis Memory API schema
-- Vector dims: text=1024 (BGE-M3 / KURE-v1), face=512 (FaceNet-style from phone)

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ────────────────────────────────────────────────────────────
-- people
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS people (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text NOT NULL,
    aliases         text[] NOT NULL DEFAULT '{}',
    org             text,
    role            text,
    first_met_at    timestamptz,
    last_met_at     timestamptz,
    face_embedding  vector(512),
    notes_summary   text,
    metadata        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS people_face_hnsw
    ON people USING hnsw (face_embedding vector_cosine_ops);

-- ────────────────────────────────────────────────────────────
-- meetings
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS meetings (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title               text,
    person_ids          uuid[] NOT NULL DEFAULT '{}',
    started_at          timestamptz NOT NULL,
    ended_at            timestamptz,
    location            text,
    summary             text,
    summary_embedding   vector(1024),
    raw_transcript      text,
    metadata            jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS meetings_summary_hnsw
    ON meetings USING hnsw (summary_embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS meetings_started_at_desc ON meetings (started_at DESC);
CREATE INDEX IF NOT EXISTS meetings_person_ids_gin ON meetings USING gin (person_ids);

-- ────────────────────────────────────────────────────────────
-- memories  (episodic — camera/voice/manual)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS memories (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    captured_at         timestamptz NOT NULL,
    text                text NOT NULL,
    embedding           vector(1024),
    related_person_ids  uuid[] NOT NULL DEFAULT '{}',
    related_meeting_id  uuid REFERENCES meetings(id) ON DELETE SET NULL,
    source              text NOT NULL DEFAULT 'manual',
    metadata            jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS memories_embedding_hnsw
    ON memories USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS memories_captured_at_desc ON memories (captured_at DESC);
CREATE INDEX IF NOT EXISTS memories_related_person_ids_gin
    ON memories USING gin (related_person_ids);

-- ────────────────────────────────────────────────────────────
-- entities  (objects/people/companies/documents extracted from memories)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS entities (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type text NOT NULL,
    label       text NOT NULL,
    aliases     text[] NOT NULL DEFAULT '{}',
    metadata    jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding   vector(1024),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (entity_type, label)
);
CREATE INDEX IF NOT EXISTS entities_embedding_hnsw
    ON entities USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS entities_type_label_idx
    ON entities (entity_type, label);

CREATE TABLE IF NOT EXISTS memory_entities (
    memory_id   uuid NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    entity_id   uuid NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    relation    text NOT NULL DEFAULT 'mentions',
    confidence  real NOT NULL DEFAULT 1.0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (memory_id, entity_id, relation)
);
CREATE INDEX IF NOT EXISTS memory_entities_entity_idx
    ON memory_entities (entity_id);

-- ────────────────────────────────────────────────────────────
-- needs  (sales/CRM signals extracted per person/meeting)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS needs (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id   uuid REFERENCES people(id) ON DELETE CASCADE,
    meeting_id  uuid REFERENCES meetings(id) ON DELETE SET NULL,
    text        text NOT NULL,
    category    text NOT NULL DEFAULT 'interest',
    embedding   vector(1024),
    confidence  real NOT NULL DEFAULT 1.0,
    metadata    jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS needs_embedding_hnsw
    ON needs USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS needs_person_id ON needs (person_id);

-- ────────────────────────────────────────────────────────────
-- proposal_points  (cached generations; optional)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS proposal_points (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id       uuid REFERENCES people(id) ON DELETE CASCADE,
    text            text NOT NULL,
    rationale       text,
    source_need_ids uuid[] NOT NULL DEFAULT '{}',
    generated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS proposal_points_person_id ON proposal_points (person_id);
