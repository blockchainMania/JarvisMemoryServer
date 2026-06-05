from contextlib import contextmanager
from typing import Iterator

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool
from pgvector.psycopg import register_vector

from .config import settings


def _configure(conn) -> None:
    register_vector(conn)


pool = ConnectionPool(
    conninfo=settings.database_url,
    min_size=1,
    max_size=10,
    open=False,
    configure=_configure,
    kwargs={"row_factory": dict_row},
)


def open_pool() -> None:
    pool.open()
    pool.wait()


def close_pool() -> None:
    pool.close()


def ensure_schema() -> None:
    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
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
                """
            )
        conn.commit()


@contextmanager
def get_conn() -> Iterator:
    with pool.connection() as conn:
        yield conn
