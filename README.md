# JarvisMemoryServer

자비스 OS의 기억 저장/검색 백엔드 레포입니다. 현재 핵심 서버는 `jarvis-server/` 폴더에 있습니다.

```text
JarvisAndroidClient
  -> Gemini Live function calling
  -> jarvis-server FastAPI
  -> Postgres + pgvector + JSONB
```

서버 실행과 API 설명은 [jarvis-server/README.md](jarvis-server/README.md)를 참고하세요.

아래 내용은 이 레포에 남아 있는 기존 GlassLink/FaceDetectionAPP 코드 설명입니다.

# GlassLink Face Detection App

Android app for testing Meta Ray-Ban glasses camera streaming, face detection, person memory, and live viewer sharing.

## Features

- Meta Wearables DAT camera registration and permission flow
- CameraX + ML Kit face detection
- Local person database with Room
- Glasses live streaming screen with viewer link sharing
- Foreground streaming service for background persistence
- LiveKit-based browser viewer served by a FastAPI backend
- Multi-tenant stream server with API-key authentication
- Viewer count polling and browser-side viewer status
- Android local recording and browser recording controls
- Optional AI summary/coaching service integration

## Project Structure

```text
app/                         Android app source
app/src/main/java/...         Kotlin + Jetpack Compose screens and services
streaming-server/             FastAPI streaming/token server
streaming-server/static/      Browser viewer
docs/                         Product and POC notes
```

## Requirements

- Android Studio
- JDK 11+
- Android SDK 36
- Meta AI app and compatible Meta wearable device
- GitHub package access for `facebook/meta-wearables-dat-android`
- Python 3.10+ for the streaming server

## Local Configuration

Do not commit secrets. Put local credentials in `local.properties`:

```properties
github_username=YOUR_GITHUB_USERNAME
github_token=YOUR_GITHUB_TOKEN_WITH_PACKAGE_READ_ACCESS
openai_api_key=YOUR_OPENAI_API_KEY
```

The app also supports these environment variables:

```bash
export GITHUB_ACTOR=YOUR_GITHUB_USERNAME
export GITHUB_TOKEN=YOUR_GITHUB_TOKEN_WITH_PACKAGE_READ_ACCESS
export OPENAI_API_KEY=YOUR_OPENAI_API_KEY
```

## Android Build

Open the project in Android Studio:

```text
/Users/jujaehyeong/Desktop/develop/JarvisMemoryServer
```

Or build from the terminal:

```bash
./gradlew assembleDebug
```

## Streaming Server

Install dependencies:

```bash
cd streaming-server
python3 -m pip install -r requirements.txt
```

Run with LiveKit settings:

```bash
LIVEKIT_URL=wss://your-project.livekit.cloud \
LIVEKIT_API_KEY=your_api_key \
LIVEKIT_API_SECRET=your_api_secret \
ADMIN_KEY=change-this-admin-key \
uvicorn main:app --host 0.0.0.0 --port 8000
```

Create the first tenant:

```bash
curl -s -X POST http://localhost:8000/admin/tenants \
  -H "X-Admin-Key: change-this-admin-key" \
  -H "Content-Type: application/json" \
  -d '{"name":"GlassLink Dev"}'
```

Copy the returned `api_key` into `TENANT_API_KEY` in `StreamingScreen.kt` before testing the live streaming flow.

## Notes

- The current app uses `mwdat-core` and `mwdat-camera`.
- Ray-Ban Meta Display UI requires newer DAT display APIs and a separate glasses UI, not Android screen mirroring.
- `local.properties`, log files, Claude/Cursor metadata, and local server databases are ignored by Git.
