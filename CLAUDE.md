# SpecTalk — Voice-Powered Project Creation for Meta Wearables

## What This Project Is

**SpecTalk** is an AI-powered project creation tool for Meta Ray-Ban glasses and AirPods.
You talk to **Gervis** — the AI assistant inside SpecTalk — to design, spec, and ship real
projects entirely hands-free. Search, Maps, and general conversation are supporting features;
the core value is turning spoken ideas into executed projects.

Built on:

- **Android app** (Kotlin, Jetpack Compose) — audio terminal, UI, push notifications
- **Python backend** (FastAPI, Google ADK) on Google Cloud — owns the Gemini Live session,
  all tools, all credentials, all conversation state
- **Firebase Authentication** — email registration and login, issues product JWTs
- **Cloud SQL (PostgreSQL)** — primary database for users, conversations, turns, jobs
- **Gemini Live API** — real-time bidirectional voice, connected from the backend only

The phone never holds a Gemini API key. All voice intelligence lives in the backend.

## Key Documents

| Document | Purpose |
|----------|---------|
| [`architecture.md`](./docs/architecture.md) | Full system design — read this before making any structural decision |
| [`TODO.md`](./TODO.md) | Active phased delivery plan — update task status after each approval |
| [`AGENTS.md`](./AGENTS.md) | To understand how to build Apps for Meta glasses |

**Always check `architecture.md` before implementing anything.** Every major design decision is
already documented there including the audio bridge protocol, WebSocket control messages, database
schema, Google Cloud service map, authentication flow, and delivery phases.

**Always update `TODO.md`** when a phase is approved and when tasks complete.

## Repository Structure

This is a monorepo containing both the Android app and the Python backend, plus reference
samples and documentation.

```
GeminiLiveAPI2026/               ← This repo root
├── android/                     ← ANDROID PROJECT (Kotlin, Jetpack Compose)
│   ├── app/                     ← Main Android app module (SpecTalk / Gervis)
│   │   └── src/main/java/com/spectalk/app/
│   │       ├── auth/            ← AuthViewModel, AuthUiState
│   │       ├── navigation/      ← NavGraph, Screen
│   │       └── ui/              ← screens/, theme/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── gradle/
│       └── libs.versions.toml   ← Version catalog
│
├── gervis-backend/              ← PYTHON BACKEND (FastAPI, Google ADK)
│   ├── main.py
│   ├── config.py
│   ├── auth/                    ← firebase.py, jwt_handler.py
│   ├── api/                     ← auth.py (POST /auth/session), future routes
│   ├── db/                      ← database.py, models.py
│   ├── middleware/               ← auth.py (JWT dependency)
│   ├── ws/                      ← voice_handler.py (Phase 3)
│   ├── agents/                  ← orchestrator.py (Phase 3)
│   ├── tools/                   ← search_tool.py, maps_tool.py (Phase 3)
│   ├── services/                ← gemini_live_client.py (Phase 3)
│   ├── Dockerfile
│   ├── requirements.txt
│   └── .env.example             ← Template only — never commit real .env
│
├── samples/
│   ├── gemini-voice-agent/      ← REFERENCE: working voice agent (UI patterns)
│   └── adk-samples/             ← Google ADK reference samples
│
├── docs/                        ← architecture.md and other docs
├── CLAUDE.md                    ← This file
├── TODO.md                      ← Phased delivery plan
└── README.md
```

**Android project** (`android/`): Kotlin, Jetpack Compose, Firebase Auth SDK, Vosk wake word.
Open `android/` as the project root in Android Studio. Deployed to Google Play / sideloaded.

**Backend** (`gervis-backend/`): Python, FastAPI, Google ADK, SQLAlchemy, Alembic. Deployed
to Google Cloud Run. Contains all API keys via Secret Manager — never in source code.

When working on the Android app, open `android/` as the project root.
When working on the backend, work in `gervis-backend/`.

## Reference Project: `samples/gemini-voice-agent` (standalone Android project)

This is the working prototype that the new app is built from. Before building any new screen or
UI component, read the equivalent implementation in this sample first.

Key files to study:

| File | What to learn from it |
|------|----------------------|
| `GeminiAgentViewModel.kt` | Session lifecycle, state management patterns |
| `GeminiLiveClient.kt` | Will be replaced by `BackendVoiceClient` — read to understand what to replicate |
| `AndroidAudioRecorder.kt` | Audio capture with AEC/NS/AGC — reuse this exactly |
| `PcmAudioPlayer.kt` | Audio playback with interrupt handling — reuse this exactly |
| `HotwordService.kt` | Wake-word detection with Vosk — reuse this exactly |
| `MainActivity.kt` | Permission flow, app entry point |
| UI composables | Animated orb, transcript display, status pills — use as design reference |

The new app replaces `GeminiLiveClient` with `BackendVoiceClient` (WebSocket to backend).
Everything else — audio capture, playback, wake word, UI patterns — is carried forward.

## Reference Project: `samples/adk-samples/agents`

Check this references when you need real examples how to build using gemini ADK you can use agents/bidi-demo or agents/realtime-conversational-agent

## Available Skills

Use these skills before writing code against any of the listed technologies. They provide
current API documentation via `chub`, preventing errors from outdated training knowledge.

### `/get-api-docs` — Universal API documentation fetcher
**Use when:** writing code against any third-party SDK or API (Google ADK, Firebase Admin SDK,
Cloud SQL, Cloud Tasks, SQLAlchemy, FastAPI, etc.)

```bash
chub search "google adk"          # find the right doc ID
chub get <id> --lang py           # fetch Python docs
```

Use this skill to fetch accurate Google ADK documentation before implementing the orchestrator,
tools, or any ADK agent patterns. Do not rely on training knowledge for ADK — fetch the docs.

### `/gemini-live-api-dev` — Gemini Live API (real-time bidirectional streaming)
**Use when:** implementing the backend Gemini Live session in `gemini_live_client.py`, handling
audio streaming, VAD configuration, transcription events, or session management.

Key things this skill covers that are critical for this project:

- The correct SDK is `google-genai` (Python) — **not** `google-generativeai` (deprecated)
- Recommended model: `gemini-2.5-flash-native-audio-preview-12-2025`
- Input audio: PCM 16kHz 16-bit mono — `audio/pcm;rate=16000`
- Output audio: PCM 24kHz 16-bit mono
- Use `send_realtime_input` for audio chunks, **not** `send_client_content`
- Send `audioStreamEnd` when mic pauses
- Clear audio queue on `interrupted` events

**Important — Google ADK BidiGenerateContent:**
Google ADK has native support for Gemini Live via `BidiGenerateContent` (bidirectional streaming
over WebSocket). When implementing the backend voice agent with ADK, prefer the ADK-native Live
session management over a raw WebSocket wrapper. Fetch the ADK docs with `/get-api-docs` to find
the correct ADK Live agent pattern (`LiveRequestQueue`, streaming session handling). This avoids
re-implementing session lifecycle management that ADK already handles.

### `/gemini-api-dev` — Gemini API (standard, non-streaming)
**Use when:** using Gemini for non-realtime tasks such as summarization, memory extraction,
PRD generation, or any batch text/multimodal processing in backend tools.

### DAT SDK skills (Android)
These skills cover the Meta Wearables DAT SDK used in the Android app:

| Skill | Use when |
|-------|---------|
| `/getting-started` | Setting up DAT SDK in a new Android module |
| `/camera-streaming` | Working with `StreamSession` and `VideoFrame` |
| `/session-lifecycle` | Handling stream pause, resume, stop |
| `/permissions-registration` | Camera permission flows, Meta account registration |
| `/mockdevice-testing` | Testing without physical Meta glasses |
| `/debugging` | Diagnosing DAT SDK issues |
| `/sample-app-guide` | Navigating the sample apps |

## Coding Conventions

### Android (Kotlin)

All Android code must follow `.claude/rules/dat-conventions.md`. Key rules:

- `suspend` functions for all async operations — no callbacks
- `StateFlow` / `Flow` for observable state
- `DatResult<T, E>` for error handling — never use `getOrThrow()`
- `sealed interface` for state hierarchies
- Naming: `*Manager` (long-lived), `*Session` (short-lived flow), `*Result`, `*Error`

### Python Backend

- **Package manager: `uv` only** — never use `pip install`. Use `uv sync` to install deps,
  `uv run <cmd>` to run scripts, `uv add <pkg>` to add packages. Dependencies live in
  `pyproject.toml`, not `requirements.txt`.
- Async everywhere: `async def`, `asyncio`, `asyncpg`, SQLAlchemy async engine
- FastAPI for HTTP and WebSocket endpoints
- Pydantic models for all request/response schemas
- SQLAlchemy ORM models in `db/models.py`, Alembic for migrations
- All secrets via environment variables injected from Secret Manager — never hardcoded
- ADK patterns from official docs (fetch with `/get-api-docs`) — do not guess ADK API shapes

### General

- Read `architecture.md` before implementing any new feature
- Follow the delivery phases in `TODO.md` — do not build Phase 3 features during Phase 1
- Each phase requires explicit user approval before starting the next
- Do not add features beyond the current phase scope

## Build and Run

### Android App

Open `android/` as the project root in Android Studio, then:

```bash
cd android

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test

# Install on connected device
./gradlew installDebug
```

### Backend (Phase 2+)

```bash
cd gervis-backend

# Install dependencies (always use uv — never pip install)
uv sync

# Run locally
uv run uvicorn main:app --reload --port 8080

# Run database migrations
uv run alembic upgrade head

# Run tests
uv run pytest
```

## Google Cloud Services in Use

All backend infrastructure runs on Google Cloud. See `architecture.md` > Google Cloud Deployment
for the full service map, IAM roles, and configuration requirements.

| Service | Purpose |
|---------|---------|
| Cloud Run | FastAPI backend (min 1 instance, 3600s timeout) |
| Cloud SQL (PostgreSQL) | Primary database |
| Firebase Authentication | Email registration and login |
| Firebase Cloud Messaging | Push notifications to Android |
| Secret Manager | All API keys and credentials |
| Cloud Storage | Artifacts (code, images, 3D models) |
| Cloud Tasks | Background job queue |
| Artifact Registry | Docker images |
| Cloud Build | CI/CD pipeline |

## Important Technical Notes

### Product Name vs Assistant Name
The **product** is called **SpecTalk**. The **AI assistant** inside SpecTalk is called **Gervis**.
These are distinct:
- App name, store listing, splash screen, notifications: **SpecTalk**
- Wake word, system instruction, spoken responses, assistant persona: **Gervis**
- Example: "Welcome to SpecTalk. I'm Gervis. What would you like to build today?"
- Never call the product "Gervis" and never call the assistant "SpecTalk"

### Wake Word
The default wake word is **"Hey Gervis"**. The user can change it in the app settings. The
configured wake word is stored in `SharedPreferences` and read by `HotwordService` on every start.
When implementing `HotwordService`, always read the wake word from settings — never hardcode it.
The Vosk grammar is rebuilt at runtime with the current configured word(s).

### Activation Sound
When the wake word is detected, the app must play a short confirmation sound (beep or chime)
through the active audio output (AirPods or Meta Glasses audio if connected, phone speaker
otherwise) **before** opening the backend WebSocket. This tells the user the agent is now
listening. The sound asset lives in `app/src/main/res/raw/`. Play it using `AudioTrack` or
`SoundPool` on the same audio session as `PcmAudioPlayer` so it routes to the same output device.

### Inactivity Auto-Disconnect (10 seconds)
When a voice session is active and no speech activity has occurred for 10 consecutive seconds,
the app must automatically end the session:
- "No activity" means: no `input_transcript` event and no `output_transcript` event received
  in the last 10 seconds — i.e., neither the user nor Gemini has spoken
- The 10s timer resets on every `input_transcript` or `output_transcript` event
- On timeout: send `{"type": "end_of_speech"}` over the WebSocket, then close the connection
- After closing, resume the `HotwordService` so the wake word listener reactivates normally
- This timer lives in `VoiceAgentViewModel` as a `Job` that is cancelled and restarted on each
  transcript event
- Do not trigger auto-disconnect while audio is actively playing (`PcmAudioPlayer` is non-empty)

### Audio Bridge (Backend ↔ Phone)
The backend is in the audio hot path. The audio bridge in `voice_handler.py` must be zero-copy:
forward raw PCM binary frames immediately, no buffering, no transcoding. See `architecture.md` >
Voice Transport for the full latency and TCP_NODELAY requirements.

### Interrupted Events
When Gemini emits an `interrupted` event, the backend must forward `{"type": "interrupted"}` to
the phone **before** any further audio. The phone must clear `PcmAudioPlayer` immediately. This
is the single most important correctness requirement for smooth barge-in UX.

### No Credentials on Phone
The phone carries only a product JWT (from `POST /auth/session`). Firebase Auth SDK manages the
Firebase ID token on-device. No Gemini key, no Google Maps key, no anything else. If you find
yourself putting a credential in the Android app, stop and re-read `architecture.md`.

### Gemini ADK vs Raw WebSocket
The backend does NOT use a raw WebSocket to Gemini. It uses the `google-genai` Python SDK
(or ADK's built-in Live session support if available). Always fetch the current ADK docs with
`/get-api-docs` before implementing. ADK's `BidiGenerateContent` / `LiveRequestQueue` patterns
handle reconnection, context injection, and session lifecycle — use them.
