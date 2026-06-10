import json
from pathlib import Path

from openai import OpenAI

from .config import settings


def _client() -> OpenAI:
    if not settings.openai_api_key:
        raise RuntimeError("GPT_API_KEY or OPENAI_API_KEY is not configured")
    return OpenAI(api_key=settings.openai_api_key)


def transcribe_audio(path: Path) -> str:
    with path.open("rb") as audio_file:
        transcript = _client().audio.transcriptions.create(
            model=settings.openai_transcription_model,
            file=audio_file,
            response_format="text",
            language="ko",
        )
    if isinstance(transcript, str):
        return transcript.strip()
    return str(getattr(transcript, "text", transcript)).strip()


def summarize_transcript(transcript: str, title: str = "") -> dict:
    schema = {
        "type": "object",
        "properties": {
            "title": {"type": "string"},
            "summary": {"type": "string"},
            "decisions": {"type": "array", "items": {"type": "string"}},
            "action_items": {"type": "array", "items": {"type": "string"}},
            "people": {"type": "array", "items": {"type": "string"}},
            "companies": {"type": "array", "items": {"type": "string"}},
            "keywords": {"type": "array", "items": {"type": "string"}},
        },
        "required": [
            "title",
            "summary",
            "decisions",
            "action_items",
            "people",
            "companies",
            "keywords",
        ],
        "additionalProperties": False,
    }
    response = _client().chat.completions.create(
        model=settings.openai_summary_model,
        messages=[
            {
                "role": "system",
                "content": (
                    "한국어 회의록 정리 도우미입니다. transcript에 없는 사실은 만들지 마세요. "
                    "핵심 요약, 결정사항, 실행할 일, 사람, 회사를 구조화하세요."
                ),
            },
            {
                "role": "user",
                "content": f"회의 제목 힌트: {title or '없음'}\n\n회의 transcript:\n{transcript}",
            },
        ],
        response_format={
            "type": "json_schema",
            "json_schema": {
                "name": "meeting_summary",
                "strict": True,
                "schema": schema,
            },
        },
        temperature=0.1,
    )
    content = response.choices[0].message.content or "{}"
    return json.loads(content)
