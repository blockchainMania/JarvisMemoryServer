from contextlib import asynccontextmanager

from fastapi import FastAPI

from .config import settings
from .db import close_pool, ensure_schema, open_pool
from .routers import live, meetings, memory, needs, people, proposals


@asynccontextmanager
async def lifespan(app: FastAPI):
    open_pool()
    ensure_schema()
    yield
    close_pool()


app = FastAPI(
    title="Jarvis Memory API",
    version="0.1.0",
    description="Personal memory & CRM API for smart-glasses AI assistant.",
    lifespan=lifespan,
)

app.include_router(people.router)
app.include_router(meetings.router)
app.include_router(memory.router)
app.include_router(live.router)
app.include_router(needs.router)
app.include_router(proposals.router)


@app.get("/health", tags=["health"])
def health():
    return {
        "status": "ok",
        "embedding_model": settings.embedding_model,
        "embedding_dim": settings.embedding_dim,
    }
