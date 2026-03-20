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
- **Firebase Authentication** — email + Google Sign-In, issues product JWTs
- **PostgreSQL (Neon)** — primary database for users, conversations, turns, jobs, projects
- **Gemini Live API** — real-time bidirectional voice, connected from the backend only via ADK

The phone never holds a Gemini API key. All voice intelligence lives in the backend.

## Key Documents

| Document | Purpose |
|----------|---------|
| [`architecture.md`](./docs/architecture.md) | Full system design — read this before making any structural decision |
| [`TODO.md`](./TODO.md) | Active phased delivery plan — update task status after each approval |
| [`AGENTS.md`](./AGENTS.md) | Agent architecture + Meta DAT SDK reference |

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
│   ├── app/
│   │   └── src/main/java/com/spectalk/app/
│   │       ├── MainActivity.kt
│   │       ├── SpecTalkApplication.kt
│   │       ├── auth/            ← AuthViewModel, AuthUiState, TokenRepository
│   │       ├── voice/           ← VoiceAgentViewModel, BackendVoiceClient, VoiceSessionUiState
│   │       ├── audio/           ← AndroidAudioRecorder, PcmAudioPlayer
│   │       ├── hotword/         ← HotwordService (Vosk), HotwordEventBus
│   │       ├── device/          ← ConnectedDeviceMonitor (wearables + BT)
│   │       ├── location/        ← UserLocationRepository, UserLocationContext
│   │       ├── conversations/   ← ConversationRepository, HomeViewModel
│   │       ├── integrations/    ← IntegrationsRepository (encrypted credentials)
│   │       ├── notifications/   ← FcmService, NotificationEventBus
│   │       ├── settings/        ← AppPreferences
│   │       ├── config/          ← BackendConfig (backend URL)
│   │       ├── navigation/      ← SpecTalkNavGraph, Screen
│   │       └── ui/
│   │           ├── screens/     ← VoiceSessionScreen, HomeScreen, LoginScreen,
│   │           │                   RegisterScreen, SettingsScreen, SplashScreen
│   │           ├── components/  ← PrdConfirmationCard
│   │           └── theme/       ← Color, Theme, Type
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── gradle/
│       └── libs.versions.toml   ← Version catalog
│
├── gervis-backend/              ← PYTHON BACKEND (FastAPI, Google ADK)
│   ├── main.py                  ← FastAPI app, lifespan, router registration
│   ├── config.py                ← Pydantic settings (80+ config vars)
│   ├── pyproject.toml           ← uv-managed dependencies
│   ├── Dockerfile
│   ├── auth/                    ← firebase.py, jwt_handler.py
│   ├── api/
│   │   ├── auth.py              ← POST /auth/session
│   │   ├── voice.py             ← POST /voice/session/start
│   │   ├── conversations.py     ← Conversation history + state
│   │   ├── jobs.py              ← GET /jobs/{job_id}
│   │   ├── notifications.py     ← Push token registration
│   │   ├── integrations.py      ← Encrypted credential storage
│   │   └── internal/
│   │       ├── jobs.py          ← Cloud Tasks callback (POST /internal/jobs/execute)
│   │       └── openclaw_callback.py ← OpenClaw webhook
│   ├── db/                      ← database.py, models.py (11 tables)
│   ├── middleware/               ← auth.py (require_auth JWT dependency)
│   ├── ws/                      ← voice_handler.py (WS /ws/voice/{conversation_id})
│   ├── agents/
│   │   ├── orchestrator.py      ← create_gervis_agent() — ADK agent definition
│   │   └── team_code_pr_designers/
│   │       └── designer_agent.py ← generate_prd() — PRD generation subagent
│   ├── tools/
│   │   ├── coding_tools.py      ← request_clarification, generate_and_confirm_prd, confirm_and_dispatch
│   │   ├── project_tools.py     ← lookup_project (fuzzy slug matching)
│   │   ├── notification_resume_tool.py ← start_background_job
│   │   ├── search_tool.py       ← google_search
│   │   ├── maps_tool.py         ← find_nearby_places
│   │   ├── location_tool.py     ← get_user_location
│   │   └── openclaw_coding_tool.py ← OpenClaw remote execution
│   ├── services/
│   │   ├── gemini_live_client.py    ← ADK InMemoryRunner, session lifecycle
│   │   ├── audio_session_manager.py ← Per-conversation ADK session + grace period
│   │   ├── conversation_service.py  ← persist_turn, state transitions
│   │   ├── job_service.py           ← create_job, enqueue_cloud_task
│   │   ├── project_service.py       ← upsert/find user_projects
│   │   ├── location_context_service.py ← Location persistence
│   │   ├── notification_service.py  ← FCM push
│   │   ├── encryption_service.py    ← Fernet encryption for credentials
│   │   ├── resume_event_service.py  ← Resume event management
│   │   ├── session_logger.py        ← Structured ADK event logging
│   │   ├── control_channels.py      ← Per-conversation WebSocket control routing
│   │   ├── location_channels.py     ← Per-conversation location request routing
│   │   └── tracing.py               ← OpenTelemetry + Opik observability
│   ├── migrations/              ← Alembic migration scripts
│   └── scripts/                 ← Dev/test utilities
│
├── samples/
│   ├── gemini-voice-agent/      ← REFERENCE: working voice agent (UI patterns)
│   └── adk-samples/             ← Google ADK reference samples
│
├── docs/                        ← architecture.md, architecture-diagram.md
├── CLAUDE.md                    ← This file
├── TODO.md                      ← Phased delivery plan
└── README.md
```

**Android project** (`android/`): Kotlin, Jetpack Compose, Firebase Auth SDK, Vosk wake word.
Open `android/` as the project root in Android Studio. Deployed to Google Play / sideloaded.

**Backend** (`gervis-backend/`): Python, FastAPI, Google ADK, SQLAlchemy async, Alembic. Deployed
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
| `GeminiLiveClient.kt` | Replaced by `BackendVoiceClient` — read to understand what was replicated |
| `AndroidAudioRecorder.kt` | Audio capture with AEC/NS/AGC — carried forward exactly |
| `PcmAudioPlayer.kt` | Audio playback with interrupt handling — carried forward exactly |
| `HotwordService.kt` | Wake-word detection with Vosk — carried forward exactly |
| `MainActivity.kt` | Permission flow, app entry point |
| UI composables | Animated orb, transcript display, status pills — used as design reference |

The new app replaces `GeminiLiveClient` with `BackendVoiceClient` (WebSocket to backend).
Everything else — audio capture, playback, wake word, UI patterns — is carried forward.

## Reference Project: `samples/adk-samples/agents`

Check this references when you need real examples how to build using Gemini ADK. Use
`agents/bidi-demo` or `agents/realtime-conversational-agent`.

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
**Use when:** implementing or modifying the backend Gemini Live session in `gemini_live_client.py`,
handling audio streaming, VAD configuration, transcription events, or session management.

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
over WebSocket). The backend uses ADK's `InMemoryRunner` with `LiveRequestQueue`. Fetch the ADK
docs with `/get-api-docs` before modifying the session lifecycle.

### `/gemini-api-dev` — Gemini API (standard, non-streaming)
**Use when:** using Gemini for non-realtime tasks such as PRD generation (`designer_agent.py`),
summarization, memory extraction, or any batch text/multimodal processing in backend tools.

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

### Backend

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
| PostgreSQL (Neon) | Primary database (Neon serverless, auto-suspend) |
| Firebase Authentication | Email + Google Sign-In, ID token issuance |
| Firebase Cloud Messaging | Push notifications to Android |
| Secret Manager | All API keys and credentials |
| Cloud Storage | Artifacts (code, images, 3D models) |
| Cloud Tasks | Background job queue (backend-jobs queue) |
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
configured wake word is stored in `SharedPreferences` and read by `HotwordService` on every
`onStartCommand()`. The Vosk grammar is rebuilt at runtime with the current configured word(s).
The `ConnectedDeviceMonitor` gates wake-word listening — `HotwordService` only activates when
a Meta wearable or Bluetooth audio device is connected.

### Activation Sound
When the wake word is detected, the app plays a 320ms beep through the active audio output via
`SoundPool`. This confirms to the user that Gervis is listening, **before** opening the backend
WebSocket. The sound routes to the same audio output as `PcmAudioPlayer`.

### Inactivity Auto-Disconnect (10 seconds)
When a voice session is active and no speech activity for 10 consecutive seconds:
- "No activity" means: no `input_transcript` and no `output_transcript` in the last 10s
- The timer lives in `VoiceAgentViewModel` as `INACTIVITY_TIMEOUT_MS = 10_000L`
- Resets on every transcript event, suspended while `PcmAudioPlayer.hasPendingAudio` is true
- On timeout: send `{"type": "end_of_speech"}` over WebSocket, then close connection
- After closing, resume `HotwordService` so wake-word listener reactivates

### Session Timeout (Gemini Live ~10-minute model limit)
The preview Gemini Live model has an approximately 10-minute hard session limit. When hit:
- Backend detects 1011 WebSocket close with "Failed to run inference" / "session" keywords
- Backend sends `{"type": "session_timeout", "message": "..."}` to phone
- Phone shows "session reached time limit" and offers a tap-to-reconnect UI
- This is NOT an error — it is expected behavior handled gracefully

### Audio Bridge (Backend ↔ Phone)
The backend is in the audio hot path. Zero-copy forwarding:
- Phone sends 16kHz 16-bit mono PCM as binary WebSocket frames (~2 KB chunks)
- Backend forwards to ADK `LiveRequestQueue` immediately
- Gemini returns 24kHz PCM; backend forwards as binary frames to phone immediately
- No buffering, no transcoding. See `architecture.md` > Voice Transport for TCP_NODELAY.

### Interrupted Events (Barge-In)
When Gemini emits `interrupted`, backend flushes its turn buffer, then sends
`{"type": "interrupted"}` to the phone **before** any further audio. Phone calls
`PcmAudioPlayer.clear()` immediately. This is the most important correctness requirement
for smooth barge-in UX.

### Coding Mode Flow (5 Steps)
1. User describes a project by voice
2. Gervis calls `request_clarification()` up to 3 times (phone UI transitions to `coding_mode`)
3. Gervis calls `generate_and_confirm_prd()` → PRD generated by `designer_agent.py`, shown as a
   `PrdConfirmationCard` on phone (phone state: `awaiting_confirmation`)
4. User says "yes" / "no" → Gervis calls `confirm_and_dispatch(confirmed=True/False)`
5. On confirm: Job created, enqueued to Cloud Tasks, spoken acknowledgment, phone shows job status

### Per-User Project Registry
`lookup_project(name, tool_context)` in `project_tools.py` provides fuzzy slug matching:
- Slugifies the user's spoken name and queries `user_projects` table
- Supports exact slug match and substring containment
- If multiple projects found, returns all names for disambiguation
- Enables cross-session project editing without re-specifying context

### Encrypted Integration Credentials
`user_integrations` table stores encrypted service credentials (OpenClaw URL, API key) using
Fernet symmetric encryption. Decrypted on-demand when the corresponding tool needs to authenticate.
Never stored in plaintext.

### No Credentials on Phone
The phone carries only a product JWT (from `POST /auth/session`). Firebase Auth SDK manages the
Firebase ID token on-device. No Gemini key, no Google Maps key, no anything else.

### Gemini ADK vs Raw WebSocket
The backend does NOT use a raw WebSocket to Gemini. It uses `google-genai` Python SDK via ADK's
`InMemoryRunner` + `LiveRequestQueue`. Always fetch current ADK docs with `/get-api-docs` before
implementing session changes.

### Observability
The backend uses structured logging (`session_logger.py`), OpenTelemetry tracing, and Opik
for conversational AI observability (`tracing.py`). All major events are traced with
`user_id`, `conversation_id`, `turn_id`, and `job_id` as context keys.
