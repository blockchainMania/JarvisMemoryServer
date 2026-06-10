import os
from dataclasses import dataclass
from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Settings:
    database_url: str = os.getenv(
        "DATABASE_URL",
        "postgresql://jarvis:jarvis_dev_password@localhost:5433/jarvis",
    )
    api_key: str = os.getenv("API_KEY", "dev-secret-change-me")
    host: str = os.getenv("HOST", "0.0.0.0")
    port: int = int(os.getenv("PORT", "8000"))

    embedding_model: str = os.getenv("EMBEDDING_MODEL", "nlpai-lab/KURE-v1")
    embedding_dim: int = int(os.getenv("EMBEDDING_DIM", "1024"))
    face_embedding_dim: int = int(os.getenv("FACE_EMBEDDING_DIM", "512"))
    embedding_device: str = os.getenv("EMBEDDING_DEVICE", "cpu")

    openai_api_key: str = os.getenv("OPENAI_API_KEY") or os.getenv("GPT_API_KEY", "")
    openai_transcription_model: str = os.getenv(
        "OPENAI_TRANSCRIPTION_MODEL",
        "gpt-4o-mini-transcribe",
    )
    openai_summary_model: str = os.getenv(
        "OPENAI_SUMMARY_MODEL",
        "gpt-4.1-mini",
    )
    openai_reasoning_effort: str = os.getenv("OPENAI_REASONING_EFFORT", "low")


settings = Settings()
