# Voice Agent Architecture

## Purpose

This document defines the target architecture for a production-grade voice agent built on:

- Android app with Meta Wearables DAT integration
- Firebase Authentication for user identity (email registration and login)
- Backend-hosted Gemini Live API session for secure, low-latency multimodal voice interaction
- Backend agent orchestration using Google ADK
- Cloud SQL (PostgreSQL) as the primary database
- Google Cloud as the deployment platform for all backend infrastructure
- Long-running background jobs with notifications and resume flows

The goal is to preserve the best possible voice UX while keeping all orchestration, memory, tools,
credentials, long-running execution, and multi-agent coordination entirely in the backend.
The Android app is a thin, secure audio terminal — it never holds a Gemini API key and never
executes business logic.

## Architecture Decision: Backend-Hosted Voice Session (Option B)

The Gemini Live WebSocket session is owned and managed by the backend, not the Android app.

The phone streams raw PCM audio to the backend over a persistent WebSocket. The backend forwards
that audio to Gemini Live, executes tool calls natively, and streams Gemini's response audio back
to the phone. The phone plays what it receives.

This eliminates the need for any Gemini API key, ephemeral token, or tool-forwarding protocol on
the client. The backend is both the orchestrator and the voice agent.

### Why This Is The Right Approach

- No credentials of any kind exist on the phone. The phone authenticates with a product JWT only.
- Tool calls are resolved in the same process as the Gemini Live session — no round-trips through
  the phone.
- Conversation state, memory, confirmations, and gated states are enforced at the source rather
  than relying on the client to forward them correctly.
- The Gemini session can be maintained across phone disconnects and reconnects without losing
  context, since the backend holds the session.
- The phone app can be updated independently of voice agent behavior. Adding tools, changing
  instructions, or evolving the orchestrator requires no app release.

### Latency Acceptance

The backend adds approximately 40–120 ms of overhead per audio leg compared to a direct
phone-to-Gemini connection. This is acceptable because:

- Gemini's own processing time (200–400 ms to first audio token) already dominates the round-trip.
- The extra overhead is less than 20% of total latency when the backend is deployed in the same
  region as the user.
- The audio hot path must be zero-copy: the backend forwards raw PCM bytes immediately with no
  buffering, transcoding, or processing.
- TCP_NODELAY must be set on both WebSocket connections to disable Nagle batching.

## Core Principles

1. The phone captures audio and plays audio. It does nothing else in the voice path.
2. The backend owns the Gemini Live session and all credentials.
3. Tools, memory, orchestration, and long-running execution live in the backend, in the same
   process as the voice agent.
4. A single conversation thread must survive multiple phone disconnects and backend Gemini
   reconnections.
5. All Gemini responses are shaped and spoken naturally by Gemini. The backend provides context;
   Gemini produces voice output.
6. Long-running work must become background jobs, not extended inline tool calls.
7. The user must always be able to leave, return, and resume naturally.

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ Android App                                                  │
│                                                              │
│  Vosk (local wake word)                                      │
│  AndroidAudioRecorder (PCM 16kHz, AEC + NS + AGC)           │
│  BackendVoiceClient (WebSocket to backend)                   │
│  PcmAudioPlayer (PCM 24kHz → speakers / BT headset)         │
│  ConversationListUI + NotificationHandler                    │
└───────────────────┬──────────────────────────────────────────┘
                    │  WS /ws/voice/{conversation_id}
                    │  Binary: PCM 16kHz up / PCM 24kHz down
                    │  JSON control messages: transcripts, state, jobs
                    │
┌───────────────────▼──────────────────────────────────────────┐
│ Backend (Python, Google ADK)                                 │
│                                                              │
│  AudioSessionManager                                         │
│  ├── Authenticates phone JWT                                 │
│  ├── Creates / resumes ConversationSession                   │
│  └── Spawns GeminiLiveVoiceAgent per active connection       │
│                                                              │
│  GeminiLiveVoiceAgent  ◄──────────────────────────────────┐  │
│  ├── Owns Gemini Live WebSocket (server-side key)         │  │
│  ├── Forwards PCM: phone → Gemini                         │  │
│  ├── Forwards PCM: Gemini → phone (zero-copy)             │  │
│  ├── Intercepts all tool calls from Gemini                │  │
│  ├── Executes tools natively (no phone round-trip)        │  │
│  ├── Injects tool results back into Gemini session        │  │
│  ├── Persists turns to conversation store                 │  │
│  └── Emits control messages to phone                      │  │
│                                                           │  │
│  Orchestrator Tools (ADK, same process):                  │  │
│  ├── search_tool                                          │  │
│  ├── maps_tool                                            │  │
│  ├── memory_tool                                          │  │
│  ├── notification_resume_tool                             │  │
│  ├── openclaw_coding_tool                                 │  │
│  ├── openclaw_assistant_tool (Phase 3)                    │  │
│  └── three_d_model_tool (Phase 3)                         │  │
│                                                           │  │
│  team_code_pr_designers (Phase 2 subagent workflow) ──────┘  │
│                                                              │
│  ConversationService  /  JobService  /  NotificationService  │
└──────────────────────────────┬───────────────────────────────┘
                               │  WebSocket
                               │  (server-side, secure key)
                    ┌──────────▼──────────┐
                    │  Gemini Live API    │
                    │  (Google Cloud)     │
                    └─────────────────────┘
```

### Client Responsibilities

The Android app is responsible for:

- user authentication against the backend (`POST /auth/session`) and storing the JWT
- calling `POST /voice/session/start` to obtain a `conversation_id` and any pending resume context
- opening and maintaining the backend voice WebSocket (`WS /ws/voice/{conversation_id}`)
- capturing microphone audio with `AndroidAudioRecorder` and streaming PCM 16kHz to the backend
  WebSocket as binary frames
- applying `AcousticEchoCanceler`, `NoiseSuppressor`, and `AutomaticGainControl` on-device before
  sending audio, so the backend receives clean audio
- receiving PCM 24kHz binary frames from the backend WebSocket and playing them via `PcmAudioPlayer`
- receiving JSON control messages from the backend WebSocket and reacting to them:
  - `interrupted`: clear the audio playback buffer immediately
  - `state_update`: update local UI state (pending confirmation UI, etc.)
  - `job_started` / `job_update`: show job status in UI
  - `input_transcript` / `output_transcript`: display conversation text
- running the local wake-word detector (Vosk) with the user-configured wake word (default:
  `"Hey Gervis"`; stored in `SharedPreferences`, key: `pref_wake_word`)
- playing a short activation sound through the active audio output (BT headset or phone speaker)
  immediately on wake word detection, before opening the backend WebSocket, so the user knows
  the agent is listening
- triggering the backend WebSocket connection after the activation sound completes
- enforcing a 10-second inactivity auto-disconnect: if no `input_transcript` or
  `output_transcript` event is received for 10 consecutive seconds and `PcmAudioPlayer` is
  empty, the app sends `{"type": "end_of_speech"}`, closes the WebSocket, and resumes the
  wake-word listener
- rendering the conversation list driven by backend-provided metadata
- receiving push notifications and reopening the correct conversation WebSocket
- calling `POST /conversations/{id}/pending-turn` when returning to a gated conversation while
  the voice session is not yet open (e.g., user taps a notification and types rather than speaks)
- calling `POST /conversations/{id}/ack-resume-event` after presenting a resume event
- optionally capturing images from Meta Glasses and sending them as control messages over the
  voice WebSocket for immediate multimodal context

The Android app is not responsible for:

- any Gemini API key, token, or credential
- any Gemini WebSocket connection
- any tool call forwarding or tool result handling
- business logic
- multi-agent orchestration
- memory retrieval or storage policy
- conversation state transitions
- turn persistence (backend persists turns directly from transcription events)

### Gemini Live Responsibilities

Gemini Live is responsible for:

- realtime speech interaction
- natural follow-up dialog
- multimodal understanding of immediate audio and image context injected by the backend
- generating spoken responses using tool results provided by the backend orchestrator
- server-side Voice Activity Detection (VAD) including barge-in and interruption detection
- emitting input and output transcripts

Gemini Live is not the system of record for:

- long-term memory
- conversation persistence
- task execution status
- notifications
- agent orchestration
- credentials or API keys

### Backend Responsibilities

The backend is the canonical control plane for:

- owning and managing all Gemini Live WebSocket connections (server-side)
- audio bridging: phone PCM → Gemini, Gemini PCM → phone
- intercepting and executing all Gemini tool calls natively
- session lifecycle
- conversation persistence and canonical turn logging (persisted directly by backend)
- memory and summaries
- tool execution
- multi-agent orchestration
- confirmation handling and gated state enforcement
- long-running jobs
- artifact storage
- notifications
- resume events
- all API credentials (Gemini, Google Search, Google Maps, OpenClaw, storage)

## Recommended Technology Direction

### Voice Transport

The backend is intentionally in the audio hot path. The phone has one persistent WebSocket
to the backend. The backend has one persistent WebSocket to Gemini Live per active voice session.

```
Phone mic → PCM 16kHz → Backend WS → Gemini Live WS
                                           ↓
Phone speaker ← PCM 24kHz ← Backend WS ← Gemini Live WS
```

Audio forwarding in the backend must be zero-copy:

- receive binary frame from phone WebSocket
- wrap in Gemini Live realtimeInput message
- send immediately to Gemini WebSocket
- no buffering, no transcoding, no inspection

Receive path from Gemini:

- receive binary audio chunk from Gemini WebSocket
- send immediately to phone WebSocket as binary frame
- no buffering except a small interrupt-aware queue to support clearing on barge-in

To minimize latency:

- deploy backend in the same region as the majority of users
- set `TCP_NODELAY` on all WebSocket connections
- use small audio chunk sizes (2 KB = 64 ms at 16kHz) to minimize per-chunk latency
- the backend audio bridge must run on a dedicated asyncio task, not shared with tool execution

### VAD and Echo Cancellation

The phone applies `AcousticEchoCanceler` to the microphone signal before sending audio to the
backend. This removes Gemini's response audio from the captured signal before it reaches the
backend, preventing echo artifacts from confusing Gemini's server-side VAD.

Gemini's server-side VAD operates identically regardless of whether the audio traveled directly
from the phone or via the backend, because the backend forwards raw PCM bytes without modification.

Gemini Live must be configured with low VAD sensitivity to avoid false triggers from residual echo:

```json
"realtimeInputConfig": {
  "automaticActivityDetection": {
    "disabled": false,
    "startOfSpeechSensitivity": "START_SENSITIVITY_LOW",
    "endOfSpeechSensitivity": "END_SENSITIVITY_LOW",
    "prefixPaddingMs": 60,
    "silenceDurationMs": 320
  }
}
```

#### Barge-In and Interrupted Event

When Gemini detects barge-in and emits an `interrupted` event, the backend must:

1. Stop forwarding pending Gemini audio chunks to the phone.
2. Clear the internal audio forward queue.
3. Send `{"type": "interrupted"}` to the phone WebSocket immediately, before any other message.
4. Continue forwarding phone mic audio to Gemini normally.

The phone must clear its `PcmAudioPlayer` buffer the moment it receives the `interrupted` control
message. Failure to do this causes stale Gemini audio to overlap with the user's new speech.

### Backend Agent Runtime

Recommended backend runtime:

- Google ADK as the orchestration framework
- Python backend preferred for first implementation due to stronger practical maturity around
  agent workflows and Gemini Live streaming examples
- FastAPI for REST endpoints and WebSocket handling
- The ADK orchestrator agent runs in the same Python process as the audio bridge

The backend may later evolve into a polyglot platform, but the first implementation should
optimize for delivery speed and architectural clarity.

### User Authentication

Firebase Authentication handles all user identity. It provides email registration, login, email
verification, and password reset out of the box with no custom auth server required.

#### Registration and Login Flow

```
1. User opens the Android app for the first time.
2. App presents email + password registration form.
3. App calls Firebase Auth SDK: createUserWithEmailAndPassword().
4. Firebase creates the user and returns a Firebase ID token (JWT, ~1 hour TTL).
5. App sends the Firebase ID token to POST /auth/session.
6. Backend validates the token using Firebase Admin SDK.
7. Backend creates or fetches the user record in the database (keyed by Firebase UID).
8. Backend returns a product JWT (signed with backend secret, custom TTL).
9. App stores the product JWT and uses it for all subsequent API calls and WebSocket upgrades.

On subsequent logins:
1. App calls Firebase Auth SDK: signInWithEmailAndPassword().
2. Firebase returns a fresh ID token.
3. App calls POST /auth/session to exchange it for a product JWT.
```

#### Why Firebase Auth + Product JWT

Using Firebase Auth alone (validating the Firebase token on every request) would work, but
issuing a product JWT gives the backend full control over:

- token shape and custom claims (user tier, feature flags, subscription status)
- token expiry independent of Firebase's 1-hour default
- ability to revoke sessions without depending on Firebase token revocation latency
- decoupling from Firebase if the auth provider ever changes

The Firebase token is only used once — to prove identity at `POST /auth/session`. Every other
call uses the product JWT.

#### Token Refresh

When the product JWT expires, the app calls Firebase Auth SDK `getIdToken(forceRefresh: true)`
to get a fresh Firebase ID token, then calls `POST /auth/session` again to get a new product JWT.
No separate refresh-token endpoint is needed.

#### User Record in Database

On first `POST /auth/session` for a new Firebase UID, the backend creates a row in the `users`
table. This establishes the stable `user_id` used throughout the system.

### Database

The backend uses **Neon PostgreSQL** (serverless) as the primary relational database.
Neon auto-suspends on inactivity; `pool_pre_ping=True` in the SQLAlchemy engine handles
transparent reconnection after auto-suspend wakeup.

#### Why Neon PostgreSQL

- serverless PostgreSQL with auto-suspend — zero cost on idle, instant wakeup
- relational model fits the data (users → conversations → turns, jobs, events)
- strong consistency and ACID transactions for state transitions and job updates
- native JSONB columns for flexible payloads (tool outcomes, artifacts, structured data)
- `ssl=require` for secure connections from Cloud Run
- familiar SQL tooling for schema migrations (Alembic)

#### Schema

```sql
-- Stable user identity, one row per Firebase UID
CREATE TABLE users (
    user_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid    TEXT UNIQUE NOT NULL,
    email           TEXT NOT NULL,
    display_name    TEXT,
    push_token      TEXT,          -- FCM device token for push notifications
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per conversation thread, survives reconnects and app closes
CREATE TABLE conversations (
    conversation_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(user_id),
    state                   TEXT NOT NULL DEFAULT 'idle',
                            -- idle | general_chat | coding_mode |
                            -- awaiting_user_input | awaiting_confirmation |
                            -- running_job | awaiting_resume | completed | error
    last_turn_summary       TEXT,
    pending_resume_count    INT NOT NULL DEFAULT 0,
    working_memory          JSONB,  -- active mode, pending question, job refs, etc.
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every persisted user/assistant turn and tool event
CREATE TABLE turns (
    turn_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id),
    role            TEXT NOT NULL,  -- user | assistant | tool_call | tool_result | event
    text            TEXT,
    event_type      TEXT,           -- job_started | job_completed | confirmation_requested | etc.
    structured_data JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_turns_conversation ON turns(conversation_id, created_at);

-- Background jobs dispatched by the orchestrator
CREATE TABLE jobs (
    job_id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id             UUID NOT NULL REFERENCES conversations(conversation_id),
    user_id                     UUID NOT NULL REFERENCES users(user_id),
    job_type                    TEXT NOT NULL,  -- coding | three_d_model | research | etc.
    status                      TEXT NOT NULL DEFAULT 'queued',
                                -- queued | running | awaiting_input |
                                -- completed | failed | cancelled
    artifacts                   JSONB,
    spoken_completion_summary   TEXT,
    display_completion_summary  TEXT,
    error_summary               TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Actions awaiting explicit user confirmation before execution
CREATE TABLE pending_actions (
    pending_action_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID NOT NULL REFERENCES conversations(conversation_id),
    description         TEXT NOT NULL,
    confirmation_prompt TEXT NOT NULL,
    resolved_at         TIMESTAMPTZ,
    resolution          TEXT,       -- confirmed | cancelled
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Notification-backed resume items created when jobs complete or need input
CREATE TABLE resume_events (
    resume_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id),
    job_id          UUID REFERENCES jobs(job_id),
    event_type      TEXT NOT NULL,  -- job_completed | job_failed | awaiting_input
    spoken_summary  TEXT,
    display_summary TEXT,
    artifacts       JSONB,
    is_acknowledged BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_resume_events_conversation ON resume_events(conversation_id, is_acknowledged);

-- Multimodal assets uploaded by the user during a conversation
CREATE TABLE assets (
    asset_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(user_id),
    conversation_id UUID REFERENCES conversations(conversation_id),
    mime_type       TEXT NOT NULL,
    storage_url     TEXT NOT NULL,   -- Cloud Storage signed or public URL
    caption         TEXT,
    source          TEXT,            -- phone_camera | glasses | uploaded
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Encrypted third-party service credentials (OpenClaw, etc.)
CREATE TABLE user_integrations (
    integration_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(user_id),
    service_name    TEXT NOT NULL,   -- openclaw | etc.
    encrypted_url   TEXT,            -- Fernet-encrypted service URL
    encrypted_token TEXT,            -- Fernet-encrypted API token
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_user_integrations_service ON user_integrations(user_id, service_name);

-- Per-user project registry for cross-session project lookup
CREATE TABLE user_projects (
    project_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(user_id),
    project_name    TEXT NOT NULL,
    slug            TEXT NOT NULL,   -- URL-safe slug for fuzzy matching
    path            TEXT,            -- Local path or remote URL
    url             TEXT,
    openclaw_context JSONB,          -- Context for OpenClaw execution
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_user_projects_slug ON user_projects(user_id, slug);
```

#### Database Access Pattern

- backend uses SQLAlchemy (async) with asyncpg driver
- Cloud Run connects to Cloud SQL via Cloud SQL Auth Proxy sidecar or built-in connector
- connection pool size set to match Cloud Run instance concurrency
- Alembic manages schema migrations; migrations run as a Cloud Run Job on each deploy

### Recommended Backend Project Structure

```
backend/
├── main.py                           # FastAPI app, lifespan, router registration
├── ws/
│   └── voice_handler.py              # WS /ws/voice/{conversation_id}
│                                     # Audio bridge + control message dispatch
├── agents/
│   ├── orchestrator.py               # ADK root agent — IS the voice agent
│   │                                 # Owns system instruction, tool list
│   └── team_code_pr_designers/       # Phase 2 multi-agent workflow
│       ├── __init__.py
│       ├── frontend_pr_designer.py
│       ├── backend_pr_designer.py
│       ├── security_pr_designer.py
│       └── architecture_pr_designer.py
├── tools/
│   ├── search_tool.py
│   ├── maps_tool.py
│   ├── memory_tool.py
│   ├── notification_resume_tool.py
│   ├── openclaw_coding_tool.py
│   └── three_d_model_tool.py
├── services/
│   ├── audio_session_manager.py      # Lifecycle of per-user voice WS sessions
│   ├── gemini_live_client.py         # Backend-side Gemini Live WebSocket wrapper
│   ├── conversation_service.py       # Turn persistence, state transitions
│   ├── job_service.py                # Background job queue and status
│   ├── notification_service.py       # FCM push notification delivery
│   └── resume_event_service.py       # Resume event creation and acknowledgment
├── db/
│   ├── database.py                   # SQLAlchemy async engine, session factory
│   ├── models.py                     # ORM models (User, Conversation, Turn, Job, etc.)
│   └── migrations/                   # Alembic migration scripts
│       ├── env.py
│       └── versions/
├── auth/
│   ├── firebase.py                   # Firebase Admin SDK init, ID token verification
│   └── jwt.py                        # Product JWT sign / verify
├── models/                           # Pydantic request/response schemas
│   ├── auth.py
│   ├── conversation.py
│   ├── job.py
│   ├── resume_event.py
│   └── identity.py
└── api/
    ├── auth.py                       # POST /auth/session
    ├── voice.py                      # POST /voice/session/start
    ├── conversations.py
    ├── jobs.py
    └── notifications.py
```

## Google Cloud Deployment

All backend infrastructure runs on Google Cloud.

### Service Map

```
┌─────────────────────────────────────────────────────────────────────┐
│ Google Cloud Project                                                │
│                                                                     │
│  Firebase Authentication ──────────────────────────────────────┐   │
│  (email registration, login, ID token issuance)                │   │
│                                                                 │   │
│  Cloud Run (FastAPI backend)  ◄── Android app (JWT + WS)       │   │
│  ├── min-instances: 1  (persistent WebSocket support)          │   │
│  ├── connects to Cloud SQL via Cloud SQL Auth Proxy            │   │
│  ├── reads secrets from Secret Manager                         │   │
│  ├── writes artifacts to Cloud Storage                         │   │
│  └── calls Gemini Live API (Vertex AI or AI Studio)            │   │
│                                                                 │   │
│  Cloud SQL (PostgreSQL)                                        │   │
│  ├── primary instance (us-central1 or nearest region)         │   │
│  └── automated backups, point-in-time recovery                │   │
│                                                                 │   │
│  Cloud Storage                                                 │   │
│  └── artifacts bucket (generated code, images, 3D models)     │   │
│                                                                 │   │
│  Cloud Tasks                                                   │   │
│  └── background job queue (coding jobs, 3D model jobs)        │   │
│                                                                 │   │
│  Firebase Cloud Messaging (FCM)                                │   │
│  └── push notifications to Android devices  ◄─────────────────┘   │
│                                                                     │
│  Secret Manager                                                     │
│  ├── GEMINI_API_KEY                                                 │
│  ├── JWT_SECRET                                                     │
│  ├── DATABASE_URL                                                   │
│  ├── GOOGLE_MAPS_API_KEY                                            │
│  ├── GOOGLE_SEARCH_API_KEY                                          │
│  └── OPENCLAW_API_KEY                                               │
│                                                                     │
│  Artifact Registry                                                  │
│  └── Docker images for Cloud Run deployments                        │
│                                                                     │
│  Cloud Build                                                        │
│  └── CI/CD: build → push to Artifact Registry → deploy to Cloud Run│
│                                                                     │
│  Cloud Logging + Cloud Trace                                        │
│  └── observability for all services                                 │
└─────────────────────────────────────────────────────────────────────┘
```

### Cloud Run Configuration

Cloud Run is the compute layer for the FastAPI backend.

Key configuration requirements:

- **Minimum instances: 1** — Cloud Run scales to zero by default. WebSocket connections require at
  least one warm instance to be always available. Set `--min-instances=1`.
- **Concurrency** — each instance handles multiple concurrent WebSocket sessions. Set concurrency
  to match expected simultaneous users per instance (start with 80).
- **Request timeout: 3600s** — the default 300s timeout will close active voice WebSocket sessions.
  Cloud Run supports up to 3600s; set this to the maximum.
- **CPU always allocated** — audio bridge coroutines run continuously. Set
  `--cpu-throttling=false` (CPU always allocated, not only during requests).
- **Cloud SQL connection** — use the built-in Cloud SQL connector (`--add-cloudsql-instances`)
  rather than a sidecar proxy in v1. This simplifies the container.
- **Region** — deploy to the region closest to the majority of users to minimize audio latency.
  `us-central1` is a good default. Gemini Live API endpoint should be in the same region.

### Gemini Live API on Google Cloud

Two options for calling Gemini Live from the backend:

**Option 1: Google AI Studio API key (simpler, recommended for v1)**
- Uses `generativelanguage.googleapis.com` endpoint
- Authenticated with an API key stored in Secret Manager
- No Google Cloud IAM setup required
- Quota managed through Google AI Studio

**Option 2: Vertex AI Gemini API (recommended for production)**
- Uses `{region}-aiplatform.googleapis.com` endpoint
- Authenticated with the Cloud Run service account (no API key required)
- Quota managed through Google Cloud project
- Better integration with Google Cloud IAM and audit logging
- Supports VPC Service Controls for stricter security

For v1, start with Option 1 (AI Studio key) to simplify setup. Migrate to Option 2 (Vertex AI)
before public launch.

### Cloud SQL Setup

- instance type: `db-g1-small` for development, `db-custom-2-7680` for production
- region: same region as Cloud Run to minimize query latency
- private IP with VPC for production (Cloud Run in VPC via Serverless VPC Access connector);
  Cloud SQL Auth Proxy connector for v1 development
- enable automated backups with 7-day retention
- enable point-in-time recovery
- database name: `jarvis_backend`
- connection string stored in Secret Manager as `DATABASE_URL`

### Cloud Storage Setup

- bucket name: `{project-id}-artifacts`
- region: same as Cloud Run
- lifecycle policy: delete artifacts older than 90 days (configurable)
- Cloud Run service account needs `storage.objectCreator` and `storage.objectViewer` roles

### Cloud Tasks Setup (Background Jobs)

Cloud Tasks manages the background job queue for coding and 3D model jobs.

- queue name: `backend-jobs`
- handler endpoint: `POST /internal/jobs/execute` (internal Cloud Run endpoint, not exposed
  to the internet)
- Cloud Tasks retries failed jobs with exponential backoff
- max retries: 5
- task deadline: 3600s (for long-running coding jobs)

### Firebase Configuration

Two Firebase services are used:

**Firebase Authentication**
- enable Email/Password sign-in method in Firebase console
- enable Email verification (required before user can call `POST /auth/session` successfully)
- backend uses Firebase Admin SDK to verify ID tokens:
  ```python
  import firebase_admin
  from firebase_admin import auth as firebase_auth

  firebase_admin.initialize_app()  # uses GOOGLE_APPLICATION_CREDENTIALS automatically on Cloud Run

  def verify_firebase_token(id_token: str) -> str:
      decoded = firebase_auth.verify_id_token(id_token)
      return decoded["uid"]  # Firebase UID → used to look up user_id in database
  ```

**Firebase Cloud Messaging (FCM)**
- used to send push notifications to Android devices
- backend sends notifications using the Firebase Admin SDK Messaging API
- device push token stored in `users.push_token`, updated on each `POST /notifications/device/register`

### Secret Manager

All credentials are stored in Secret Manager and accessed by Cloud Run at startup via the
Secret Manager client library or environment variable injection (`--set-secrets` flag).

Never hardcode credentials in source code or container images.

Secrets to provision before first deploy:

| Secret name           | Description                                      |
|-----------------------|--------------------------------------------------|
| `GEMINI_API_KEY`      | Google AI Studio API key for Gemini Live         |
| `JWT_SECRET`          | HMAC secret for signing product JWTs             |
| `DATABASE_URL`        | PostgreSQL connection string (Cloud SQL)         |
| `GOOGLE_MAPS_API_KEY` | Google Maps Platform API key                     |
| `GOOGLE_SEARCH_API_KEY` | Google Custom Search API key                   |
| `OPENCLAW_API_KEY`    | OpenClaw platform API key                        |

### Deployment Pipeline

Recommended CI/CD flow using Cloud Build:

```
git push → Cloud Build trigger
  → build Docker image
  → push to Artifact Registry
  → run Alembic migrations (Cloud Run Job)
  → deploy to Cloud Run (--no-traffic)
  → smoke test
  → shift 100% traffic to new revision
```

The Alembic migration step must complete successfully before traffic shifts to the new revision.

### IAM Roles for Cloud Run Service Account

The Cloud Run service account needs:

| Role | Purpose |
|------|---------|
| `roles/cloudsql.client` | Connect to Cloud SQL |
| `roles/secretmanager.secretAccessor` | Read secrets |
| `roles/storage.objectAdmin` | Read and write artifacts bucket |
| `roles/cloudtasks.enqueuer` | Enqueue background jobs |
| `roles/firebase.sdkAdminServiceAgent` | Verify Firebase tokens + send FCM |

## Backend Voice WebSocket Protocol

### Connection

```
WS /ws/voice/{conversation_id}
Headers:
  Authorization: Bearer <jwt>
```

The backend authenticates the JWT, resolves the `conversation_id`, and opens or resumes the
associated `GeminiLiveVoiceAgent`. If the conversation has pending resume events, the backend
injects them into the Gemini session context before accepting mic audio.

### Phone → Backend Messages

Binary frames (no framing header required):

```
[raw PCM bytes]   16kHz, 16-bit, mono
                  Chunk size: ~2 KB (64 ms of audio)
                  Sent continuously while mic is active
```

JSON control messages:

```json
{"type": "end_of_speech"}
```
User explicitly ended the session (said "goodbye" or tapped stop). Backend sends `audioStreamEnd`
to Gemini and begins graceful disconnect.

```json
{"type": "interrupt"}
```
Optional: user tapped a manual barge-in button. Backend clears audio queue and signals Gemini.
In normal use the server-side VAD handles barge-in automatically.

```json
{"type": "image", "mime_type": "image/jpeg", "data": "<base64>"}
```
Optional multimodal frame from Meta Glasses or phone camera. Backend injects the image into the
active Gemini session as a realtimeInput inline part. If the image belongs to a longer workflow
(e.g., 3D model collection), backend also persists it to asset storage and returns an `asset_id`
via a control message.

### Backend → Phone Messages

Binary frames:

```
[raw PCM bytes]   24kHz, 16-bit, mono
                  Forwarded immediately from Gemini response audio chunks
                  No re-encoding or buffering beyond interrupt-aware queue
```

JSON control messages:

```json
{"type": "input_transcript", "text": "Hey, what is the weather in Madrid?"}
```
User speech as transcribed by Gemini. Used to display conversation text in the app UI.

```json
{"type": "output_transcript", "text": "It is currently 18 degrees and partly cloudy in Madrid."}
```
Assistant response text as transcribed by Gemini.

```json
{"type": "interrupted"}
```
Gemini detected barge-in. Phone must clear its audio playback buffer immediately.

```json
{
  "type": "state_update",
  "state": "awaiting_confirmation",
  "pending_action": {
    "pending_action_id": "pa_abc123",
    "description": "Create a new Next.js project and push to GitHub",
    "confirmation_prompt": "Should I go ahead and start the coding job for your Next.js app?"
  }
}
```
Backend transitioned conversation state. Phone updates its UI (e.g., shows confirmation prompt,
disables free-form input).

```json
{
  "type": "job_started",
  "job_id": "job_xyz789",
  "spoken_ack": "I have started the coding job. I will notify you when it is done."
}
```
A background job was created. `spoken_ack` is already being spoken by Gemini; phone shows
job status indicator in UI.

```json
{
  "type": "job_update",
  "job_id": "job_xyz789",
  "status": "completed",
  "display_summary": "Your Next.js app has been generated and the PR is ready for review."
}
```
A job changed state. Phone updates the job indicator.

```json
{"type": "asset_saved", "asset_id": "asset_001", "caption": "Front view captured"}
```
An image sent by the phone was persisted to asset storage. Phone can use this `asset_id` in
subsequent interactions.

```json
{"type": "error", "spoken_summary": "Something went wrong. Would you like me to try again?"}
```
Backend error that was already surfaced to the user through Gemini's spoken response.

## Backend Agent Topology

### Orchestrator Agent — The Voice Agent

The orchestrator is not a routing layer that sits in front of the voice agent. It IS the voice
agent. The `GeminiLiveVoiceAgent` in the backend runs the ADK orchestrator and attaches it to the
Gemini Live session.

The orchestrator:

- holds the system instruction that defines the assistant's personality and behavior rules
- receives tool call requests from Gemini and resolves them by executing the correct tool
- loads conversation state and relevant memory before responding to tool calls
- decides whether to answer directly, ask a clarifying question, request confirmation, or start
  a background job
- persists turns, state patches, and tool events directly to the conversation store
- emits control messages to the phone WebSocket after significant state changes

The orchestrator is the only component that should call tools. The phone never calls tools.

### Tools First, Agents Only When Needed

The default design is:

- orchestrator at the top
- simple capabilities implemented as tools called directly by the orchestrator
- specialized subagent workflows used only when the task is multi-step, collaborative, and
  stateful in ways that exceed a single tool call

This intentionally avoids unnecessary agent proliferation. If a capability is a direct action or
retrieval step, implement it as a tool.

### Recommended Tool Inventory

The orchestrator directly owns or invokes:

- `search_tool`
- `maps_tool`
- `three_d_model_tool`
- `memory_tool`
- `notification_resume_tool`
- `openclaw_coding_tool`
- `openclaw_assistant_tool` (Phase 3, optional)

### Tool Semantics

#### `search_tool`

Use for:

- web search
- current information lookup
- source-backed answers

This should be a tool, not a dedicated agent.

#### `maps_tool`

Use for:

- places search
- directions
- ETA
- local recommendations

This should be a tool, not a dedicated agent.

#### `three_d_model_tool`

Use for:

- validating the required image set for a 3D model job
- submitting 3D model generation jobs
- returning job status and artifact references

This should be a tool, not a dedicated agent.

#### `memory_tool`

Use for:

- saving durable user memory
- retrieving relevant prior memory before responding
- summarizing and indexing completed conversation history

This should begin as a tool-backed service. A separate memory agent is not required in v1.

#### `notification_resume_tool`

Use for:

- creating resume events when a job completes or needs user input
- fetching pending resume events to inject into a resumed Gemini session
- acknowledging resume events

This should begin as a tool-backed service. A separate notification agent is not required in v1.

#### `openclaw_coding_tool`

Use for:

- sending an approved PRD or task package to OpenClaw
- starting a coding execution job
- polling or receiving coding job status
- collecting artifacts and logs from OpenClaw

This is a tool invoked by the orchestrator. It is not itself the conversational agent.

#### `openclaw_assistant_tool`

Use for:

- lightweight, conversational interaction with OpenClaw outside of full coding execution
- cases where the user wants OpenClaw involved but a full PRD workflow is unnecessary

Scope rule:

- optional capability, not required for v1 launch
- advisory and assistant-style use only
- no repository mutation, deployment, or execution side effects through this tool
- any action that changes code or starts a job must route through `team_code_pr_designers` and
  `openclaw_coding_tool`

### Specialized Multi-Agent Workflow

Only one specialized backend multi-agent workflow is required initially:

- `team_code_pr_designers`

This is justified as a subagent workflow because the task is collaborative, multi-step, and
requires multiple specialized perspectives before producing a handoff artifact.

### `team_code_pr_designers` Internal Structure

Contains four focused subagents:

- `frontend_pr_designer`
- `backend_pr_designer`
- `security_pr_designer`
- `architecture_pr_designer`

Responsibilities:

- shape vague user requirements into a strong PRD or technical brief
- surface missing requirements
- challenge risky assumptions
- define frontend, backend, architecture, and security considerations
- generate the minimum number of strong follow-up questions
- prepare the execution handoff for `openclaw_coding_tool`

The user does not interact directly with these subagents. The orchestrator collapses their work
into a single clean next step and speaks it naturally through Gemini.

## Tool and Agent Inventory In Backend

### 1. Google Search

Purpose:

- latest information
- source-backed answers
- web research
- current facts

Rules:

- always executed in backend
- returns both structured sources and a voice-friendly spoken summary
- supports citations for UI views

### 2. Google Maps

Purpose:

- places search
- directions
- travel estimates
- nearby recommendations
- place details

Rules:

- always executed in backend
- location-aware policy lives in backend
- voice responses must be concise while UI may show richer detail

### 3. OpenClaw Agent

Purpose:

- take an approved PRD or technical plan and execute code work
- create implementation artifacts
- run code generation pipelines
- execute CI-style steps after approval

Rules:

- never auto-executes without explicit user confirmation
- runs as a background job for non-trivial tasks
- writes artifacts, logs, status, and deployment links to backend storage

Execution model:

- OpenClaw is the primary coding execution engine for v1
- backend submits approved coding work to OpenClaw
- OpenClaw directly invokes local coding CLIs through its CLI Backends feature
- Claude Code CLI and Codex CLI are expected to run through OpenClaw, not through a custom runner
  in the first implementation

Reference:

- OpenClaw CLI Backends documentation: https://docs.openclaw.ai/gateway/cli-backends#cli-backends

### 4. `team_code_pr_designers`

Purpose:

- shape vague requirements into a strong project brief
- challenge assumptions
- ask missing questions
- prepare a strong PRD or project requirements document
- produce an execution-ready handoff for OpenClaw

Expected behavior:

- gathers requirements over multiple turns
- asks one good question at a time in voice mode
- stores working state in conversation state
- only marks the plan as approved after explicit user confirmation

### 5. `3D_model`

Purpose:

- generate a 3D model from four images
- validate required image count and quality
- manage long-running media processing

Rules:

- requires four images or a backend-managed collection workflow
- always runs as a backend capability
- usually executes as a background job
- returns artifact references instead of large inline payloads

## OpenClaw Coding Execution Strategy

### V1 Strategy

For the first implementation, coding execution uses OpenClaw directly rather than a custom
subprocess runner service.

OpenClaw is responsible for:

- receiving approved implementation tasks from the backend
- selecting the configured CLI backend
- invoking Claude Code CLI or Codex CLI directly
- preserving CLI session continuity where supported
- returning coding results, logs, and artifacts to the backend workflow

### Why OpenClaw-First

Benefits:

- lower implementation complexity for v1
- native support for Claude Code CLI and Codex CLI through OpenClaw CLI Backends
- less custom process orchestration in the backend
- easier path to project-specific Claude configuration when the job workspace is prepared correctly

### Backend Role Around OpenClaw

Even when OpenClaw runs the CLI directly, the backend still owns:

- approvals
- conversation state
- memory
- job lifecycle
- notifications
- resume events
- artifact indexing for app UI

### Project Configuration Expectations

When OpenClaw executes coding work in a prepared project workspace, Claude Code can use
project-local configuration such as:

- `CLAUDE.md`
- `.claude/settings.json`
- `.claude/settings.local.json`
- `.mcp.json`
- `.claude/commands/`

The backend should prepare the correct repository or worktree context before dispatching work to
OpenClaw whenever project-specific behavior is needed.

### Future Evolution

Custom runner infrastructure remains a possible future enhancement but is not required for the
initial architecture.

Future enhancement:

- `Coming soon`: dedicated custom coding runner abstraction for cases where OpenClaw CLI Backends
  are insufficient due to advanced sandboxing, custom streaming, or provider-specific execution
  control requirements.

## Session, State, Memory, and Identity

### Identity Model

The following identifiers must be separated clearly.

- `user_id`: stable product identity
- `conversation_id`: stable conversation thread identity
- `turn_id`: one persisted conversation turn or event record
- `voice_ws_session_id`: one phone-to-backend WebSocket session (short-lived)
- `live_session_id`: one backend-to-Gemini Live WebSocket session (owned by backend)
- `tool_call_id`: one Gemini-requested tool call intercepted and executed by backend
- `job_id`: one long-running backend task
- `resume_event_id`: one notification-backed resume item
- `pending_action_id`: one action awaiting user confirmation

Note: `voice_ws_session_id` and `live_session_id` are separate. The phone may disconnect and
reconnect (new `voice_ws_session_id`) while the backend keeps the Gemini Live session open
(same `live_session_id`), preserving context. Alternatively the backend may reconnect to Gemini
while the phone remains connected.

### Conversation Rules

- `conversation_id` is created when a conversation starts
- all tool calls during that conversation reuse the same `conversation_id`
- a phone WebSocket disconnect must not end the conversation or create a new one
- the backend may keep the Gemini Live session open briefly after a phone disconnect to allow
  fast reconnection without losing context
- one conversation may have multiple `voice_ws_session_id` and `live_session_id` values over time
- the conversation only closes when the user ends it or policy archives it

### Session Layers

There are three distinct session layers.

#### Phone Voice WebSocket Session

Owned by the phone and the backend audio bridge:

- short-lived (`voice_ws_session_id`)
- transport-oriented
- may drop and reconnect without losing conversation context
- authenticated by JWT on WebSocket upgrade

#### Backend Gemini Live Session

Owned by the backend:

- medium-lived (`live_session_id`)
- backend connects to Gemini Live on first phone connection or conversation start
- backend may hold this session open across brief phone disconnects
- if Gemini Live session drops, backend reconnects and re-injects conversation context before
  resuming audio forwarding
- the phone is unaware of backend-to-Gemini reconnections

#### Application Conversation Session

Owned by backend ADK:

- persistent (`conversation_id`)
- includes all events, state, and memory
- survives app close and reopen
- survives backend Gemini reconnections
- binds all tool calls, jobs, resume events, and memory updates

### Canonical Turn Log

The backend is the source of truth for conversation history. Since the backend owns the Gemini
Live session, it can persist turns directly from transcription events — no turn sync from the
phone is required.

Rules:

- every finalized user turn is persisted by the backend from Gemini `inputTranscription` events
- every finalized assistant turn is persisted by the backend from Gemini `outputTranscription`
  events
- tool calls and tool responses are recorded as conversation events at execution time
- job lifecycle changes and resume events are also recorded as conversation events
- a direct Gemini response is not exempt from persistence even if no tool was called

### Backend-Gated Pending States

When conversation state is one of the following:

- `awaiting_user_input`
- `awaiting_confirmation`

the backend must not forward raw mic audio to Gemini and let it resolve the interaction freely.

Instead:

- the backend intercepts the next user transcript from the Gemini `inputTranscription` event
- the backend resolves the pending question or confirmation against canonical state before
  allowing Gemini to continue
- the backend either executes the confirmed action, cancels it, or asks a follow-up question
- only after resolution does the backend resume normal audio forwarding

This enforcement happens inside the backend audio session, not on the phone. Because the backend
controls the Gemini Live session directly, it can inject context, block forwarding, or reframe
prompts without depending on the phone to cooperate.

When the user returns to a gated conversation while the voice session is not yet open (e.g., the
app was closed before the user answered a confirmation), the phone calls
`POST /conversations/{id}/pending-turn` with the user's text response, and the backend resolves
the pending state via REST before the voice session is reopened.

### Working Memory

Conversation state must store:

- active mode
- pending question
- pending confirmation
- current task summary
- current selected backend capability or workflow
- recent tool outcomes
- active attachments
- job references

This state is scoped to `conversation_id`.

### Long-Term Memory

Long-term memory is scoped to `user_id` and may include:

- preferences
- recurring habits
- important saved facts
- project-specific long-term context
- summaries of completed conversations

The backend summarizes completed or meaningful sessions into durable memory using `memory_tool`.

## Attachments And Multimodal Inputs

### Direct-To-Gemini Media (via Backend)

Images can be sent from the phone over the voice WebSocket as JSON control messages
(`{"type": "image", ...}`). The backend injects them into the active Gemini Live session as
`realtimeInput` inline parts. Gemini receives them immediately for multimodal reasoning:

- "what do you see?"
- "describe this screen"
- "is this object damaged?"

### Backend-Managed Attachments

The same images are also persisted to backend asset storage when they are part of longer
workflows:

- 3D model generation
- code/design generation from sketches
- task resumption across sessions
- collaboration with backend agent teams

The backend returns an `asset_id` to the phone via an `asset_saved` control message.

### Attachment Policy

All attachments referenced in tool calls or job submissions use metadata references, not raw
binaries:

- `asset_id`
- `type`
- `mime_type`
- `storage_url`
- `caption`
- `source`

## Response Contract Between Backend And Gemini

The backend shapes the context it injects into Gemini when tool results are returned. Gemini then
produces the spoken response naturally.

### Tool Result Shape

Every tool result injected back into Gemini should include:

- `spoken_summary`: a concise, voice-ready version of the result for Gemini to speak naturally
- `display_text`: a richer version for the app UI, sent separately via a `state_update` or
  `job_update` control message
- `structured_data`: raw data for logic (e.g., job IDs, artifact URLs)
- `state_patch`: any changes to conversation state the backend is applying

### Voice Formatting Policy

The backend is responsible for:

- cleaning raw provider output
- removing field labels and transport noise
- creating a concise `spoken_summary` for Gemini to use as the semantic basis
- creating richer `display_text` for the app UI if needed
- returning structured data for logic and persistence

Gemini Live is responsible for:

- speaking the result naturally
- preserving conversational continuity
- using the backend `spoken_summary` as the canonical semantic basis for its spoken response

### Assistant Personality

The assistant's name is **Gervis**. All system instructions, spoken responses, UI copy, and
notification text must use this name. The system instruction should establish Gervis as a
concise, helpful voice assistant optimized for hands-free use with Meta glasses and AirPods.

### System Instruction Policy

The system instruction for the Gemini Live agent must enforce:

- prefer `spoken_summary` from tool results; never read raw JSON, field names, or identifiers
- never say generic wrappers like "Response:" or "The result is:"
- keep spoken output concise unless the user asks for detail
- ask one question at a time in voice mode
- always summarize understanding before requesting confirmation for an action

## Confirmation Model

### Actions That Require Confirmation

The backend must require explicit user confirmation before:

- code generation that writes files
- deployment actions
- code execution
- PR creation or submission
- external side effects
- irreversible mutations
- monetary or quota-consuming operations

### Confirmation Flow (Active Voice Session)

1. User requests an action.
2. Backend transitions conversation state to `awaiting_confirmation`.
3. Backend returns a `confirm_action` tool result to Gemini with the `spoken_summary` as the
   confirmation question.
4. Gemini speaks the confirmation question to the user.
5. Backend sends `{"type": "state_update", "state": "awaiting_confirmation", "pending_action": {...}}`
   to the phone so the UI reflects the gated state.
6. User speaks "yes" or "no".
7. Backend intercepts the `inputTranscription` event, resolves the confirmation against
   `pending_action_id`, and either executes or cancels.
8. Backend transitions state and injects the outcome back to Gemini.
9. Backend sends a `state_update` control message clearing the pending action.

### Confirmation Flow (Returned From Notification)

If the user closed the app before answering a confirmation and returns via push notification:

1. Phone calls `POST /voice/session/start` and receives the `conversation_id` and pending state.
2. Phone displays the pending confirmation prompt from backend metadata.
3. If the user types a response, phone calls `POST /conversations/{id}/pending-turn`.
4. If the user opens the voice session, the backend injects the pending context into Gemini
   before accepting mic audio, then resolves confirmation through the normal voice flow.

The source of truth for pending confirmations is always backend state.

## Coding Mode

### Goal

Coding mode is a structured, stateful mode for handling requests such as:

- create a website
- build an app
- generate a backend service
- design and implement a feature

### Coding Mode Lifecycle

1. User expresses a coding request.
2. Orchestrator activates `coding_mode` in conversation state.
3. `team_code_pr_designers` shapes requirements.
4. Backend asks clarifying questions one at a time through Gemini's voice.
5. Backend produces a strong project brief or PRD.
6. Backend requests confirmation through Gemini.
7. Upon confirmation, `openclaw_coding_tool` starts the job by submitting the approved task to
   OpenClaw.
8. The job runs in the background.
9. The user receives a push notification when completed.
10. On resume, the backend injects the resume context into Gemini and the voice agent welcomes
    the user back with the result summary.

### Coding Mode UX Rules

- ask one strong question at a time
- do not dump a large questionnaire in voice mode
- always summarize current understanding before asking for approval
- do not execute code or deployments without explicit confirmation
- support job continuation after app close

## Background Jobs

### What Becomes A Job

Any request that is slow, expensive, multi-step, or long-running must become a background job.

Examples:

- generate a website
- create a 3D model
- perform extended web research
- deploy an app
- run OpenClaw execution pipelines, including Claude Code CLI or Codex CLI execution through
  OpenClaw CLI Backends

### Job States

- `queued`
- `running`
- `awaiting_input`
- `completed`
- `failed`
- `cancelled`

### Job Contract

Each job stores:

- `job_id`
- `conversation_id`
- `user_id`
- `job_type`
- `status`
- `created_at`
- `updated_at`
- `artifacts`
- `spoken_completion_summary`
- `display_completion_summary`
- `error_summary`

### Inline Tool Rule

A background job must start quickly and return immediately to the voice path with:

- a short spoken acknowledgment via `spoken_summary` in the tool result
- a `job_id`
- an optional expected next step

The backend sends a `job_started` control message to the phone simultaneously.

The user must never wait in a long audio silence for a job to complete.

## Notifications And Resume Flow

### Notification Requirements

When a job completes or needs user input:

- backend creates a `resume_event`
- backend calls `notification_resume_tool` to send a push notification
- the notification carries enough metadata to open the correct conversation

### Resume Event Model

A `resume_event` includes:

- `resume_event_id`
- `conversation_id`
- `job_id`
- `event_type`
- `spoken_summary`
- `display_summary`
- `artifacts`
- `created_at`
- `is_acknowledged`

### App Behavior On Notification

The app should:

- show the conversation with a notification badge in the conversation list
- open the matching conversation when clicked
- call `POST /voice/session/start` to get the conversation state and pending resume context
- open the backend voice WebSocket
- backend injects resume context into Gemini before accepting mic audio
- backend acknowledges the resume event internally
- phone calls `POST /conversations/{id}/ack-resume-event`
- Gemini plays a natural welcome-back message driven by the `resume_event` data

### Resume Voice UX

The first spoken message on resume must sound like:

- welcome back
- brief status
- primary artifact or result
- optional next action

The welcome-back message is generated by Gemini using the `spoken_summary` from the
`resume_event` injected by the backend. It must not be hardcoded in the app.

## Conversation List And History In App

### App Requirements

When the user closes the voice WebSocket or the app:

- all conversations remain visible in the UI
- each conversation shows the latest message summary
- conversations with pending job completions show a badge
- conversations can be resumed by tapping

The conversation list is driven entirely by backend-derived metadata:

- latest persisted turn summary
- current conversation state
- pending resume event count
- last updated timestamp

### Resume Semantics

When a conversation is reopened:

- app uses the existing `conversation_id`
- app calls `POST /voice/session/start` with the existing `conversation_id`
- app opens the backend voice WebSocket
- app does not create a new conversation thread unless the user starts a new one

## API Surface Between App And Backend

The phone interacts with the backend through one persistent WebSocket and a small set of REST
endpoints.

### Full Endpoint List

```
Authentication:
  POST /auth/session          Exchange Firebase ID token → product JWT
                              Creates user record in DB on first call

Voice (WebSocket — primary audio and control channel):
  WS   /ws/voice/{conversation_id}

Session:
  POST /voice/session/start

Conversations:
  GET  /conversations
  GET  /conversations/{conversation_id}
  GET  /conversations/{conversation_id}/turns
  GET  /conversations/{conversation_id}/resume-events
  POST /conversations/{conversation_id}/pending-turn
  POST /conversations/{conversation_id}/ack-resume-event
  POST /conversations/{conversation_id}/close

Jobs:
  GET  /jobs/{job_id}

Notifications:
  POST /notifications/device/register

Internal (not exposed to internet, called by Cloud Tasks only):
  POST /internal/jobs/execute
```

Note: `POST /conversations/{conversation_id}/turns` and
`POST /conversations/{conversation_id}/tool-execution` are removed. Turns are persisted directly
by the backend from Gemini transcription events. Tool execution happens inside the backend agent,
not via a phone-initiated HTTP call.

### `POST /auth/session`

Request body:

```json
{"firebase_id_token": "<token from Firebase Auth SDK>"}
```

Responsibilities:

- verify the Firebase ID token using the Firebase Admin SDK
- extract the Firebase UID from the verified token
- look up the user record in the `users` table by `firebase_uid`
- if no record exists (first login after registration), create a new row in `users` with a
  new `user_id` (UUID), storing `firebase_uid` and `email` from the token claims
- sign and return a product JWT containing `user_id`, `email`, and expiry
- the phone stores this JWT and uses it for all API calls and WebSocket upgrades

Response body:

```json
{
  "access_token": "<product JWT>",
  "user_id": "<uuid>",
  "email": "user@example.com"
}
```

### `WS /ws/voice/{conversation_id}`

Responsibilities:

- accept phone WebSocket connection authenticated by JWT
- route to the correct `GeminiLiveVoiceAgent` for this conversation
- if no active Gemini Live session exists, open one and inject conversation context
- if pending resume events exist, inject them into Gemini before accepting mic audio
- bridge binary PCM frames bidirectionally
- dispatch JSON control messages to and from the phone

### `POST /voice/session/start`

Responsibilities:

- authenticate the user
- create or resume a conversation
- return `conversation_id`
- return current conversation state
- return pending resume event summary if present (to show UI before voice starts)

Note: this endpoint no longer returns Gemini Live ephemeral credentials. The phone never connects
to Gemini directly.

### `GET /conversations`

Responsibilities:

- return the user conversation list
- include last message summary and last updated timestamp
- include badge counts and pending resume-event counts
- support conversation list rendering without reconstructing state on device

### `GET /conversations/{conversation_id}`

Responsibilities:

- return conversation metadata
- return current backend state
- return whether the conversation is gated by a pending question or confirmation
- return summary information needed before opening the voice WebSocket

### `GET /conversations/{conversation_id}/turns`

Responsibilities:

- return persisted transcript and event history for the conversation
- support chat history rendering in the app
- support backend-driven resume and review experiences

### `POST /conversations/{conversation_id}/pending-turn`

Responsibilities:

- receive the user's next text or transcript when the voice session is not open and backend
  state is `awaiting_user_input` or `awaiting_confirmation`
- resolve the pending question or confirmation against canonical backend state
- update conversation state
- return the next normalized backend response for display

This endpoint is the fallback for non-voice resolution of gated states. During an active voice
session, gated states are resolved through the audio WebSocket.

### `GET /conversations/{conversation_id}/resume-events`

Responsibilities:

- return pending resume events for the conversation
- used to populate notification badges and resume context before opening the voice WebSocket

### `POST /conversations/{conversation_id}/ack-resume-event`

Responsibilities:

- mark a resume event as consumed or acknowledged
- let backend update badge counts and conversation metadata
- identify which resume event is being presented in the resumed conversation

### `POST /conversations/{conversation_id}/close`

Responsibilities:

- mark the conversation as closed or archived
- trigger summarization or long-term memory persistence if policy requires it
- preserve the conversation for future read-only history and explicit resume if supported

### `GET /jobs/{job_id}`

Responsibilities:

- return current job status, progress, and artifacts
- used for polling in cases where push notification was not delivered

### `POST /notifications/device/register`

Responsibilities:

- register the device push token (FCM or APNs)
- associate the token with the authenticated user

## State Machine

Recommended conversation state machine:

- `idle`
- `general_chat`
- `coding_mode`
- `awaiting_user_input`
- `awaiting_confirmation`
- `running_job`
- `awaiting_resume`
- `completed`
- `error`

Only the backend may authoritatively transition the conversation state. The phone reflects state;
it never drives it.

## Observability

The backend must log and trace:

- conversation lifecycle events
- voice WebSocket connections and disconnections
- backend Gemini Live session opens, closes, and reconnections
- tool call interceptions and execution outcomes
- subagent routing decisions
- confirmation requests and resolutions
- job lifecycle transitions
- notification delivery attempts and outcomes
- resume event creation and acknowledgment

Recommended trace keys on all log entries:

- `user_id`
- `conversation_id`
- `turn_id`
- `voice_ws_session_id`
- `live_session_id`
- `tool_call_id`
- `job_id`
- `resume_event_id`

## Security And Trust Boundaries

### User Identity

Users register and authenticate with Firebase Authentication (email and password). The phone
never manages passwords or sessions directly — Firebase Auth SDK handles all credential storage
and token refresh on the device.

On successful Firebase login the phone receives a Firebase ID token. This token is short-lived
(~1 hour) and is only used once: to call `POST /auth/session`. The backend validates it with the
Firebase Admin SDK and returns a product JWT, which the phone uses for everything else.

### Client Credentials

The phone carries only:

- the Firebase user session (managed by Firebase Auth SDK, not accessible as raw credentials)
- the product JWT issued by `POST /auth/session`

The phone must never hold:

- Gemini API keys of any kind
- Google Search or Maps credentials
- OpenClaw credentials
- any model provider key
- any storage credential
- any database credential

### Backend Credentials

All the following remain exclusively in Google Cloud Secret Manager, accessed only by the
Cloud Run service account at runtime:

- `GEMINI_API_KEY` — Gemini Live API key (or replaced by Vertex AI service account in production)
- `JWT_SECRET` — HMAC secret for signing and verifying product JWTs
- `DATABASE_URL` — Cloud SQL connection string
- `GOOGLE_MAPS_API_KEY`
- `GOOGLE_SEARCH_API_KEY`
- `OPENCLAW_API_KEY`

No credential appears in source code, environment files committed to version control, or
container images.

### WebSocket Authentication

The voice WebSocket connection must be authenticated using the product JWT in the
`Authorization: Bearer <jwt>` header on the HTTP upgrade request. Unauthenticated connections
are rejected before any audio is forwarded or any Gemini session is opened.

### Firebase Token Validation

The backend validates Firebase ID tokens on `POST /auth/session` using the Firebase Admin SDK.
The Admin SDK is initialized automatically on Cloud Run using the service account bound to the
Cloud Run revision — no explicit credential file is required.

### User Account Integrations

If future features require user-owned Google resources such as Calendar or Gmail:

- OAuth lives between the user and the backend
- the phone only carries the product JWT
- backend stores and refreshes the provider credentials in the database (encrypted at rest by
  Cloud SQL's default encryption)

## Failure Handling

### Inactivity Auto-Disconnect

When a voice session is active, the app enforces a 10-second inactivity timeout.

Definition of inactivity: no `input_transcript` event and no `output_transcript` event received
in the last 10 seconds, AND `PcmAudioPlayer` has no audio queued (Gervis is not speaking).

Behavior on timeout:

- app sends `{"type": "end_of_speech"}` over the WebSocket
- app closes the WebSocket connection
- app resumes `HotwordService` (wake-word listener reactivates)
- app shows a brief fade-out animation on the voice session screen

Timer management:

- the 10s countdown is a coroutine `Job` in `VoiceAgentViewModel`
- it is cancelled and restarted on every `input_transcript` or `output_transcript` event
- it is suspended while `PcmAudioPlayer` reports active playback
- it is cancelled entirely if the user manually disconnects first

### Phone Voice WebSocket Disconnect

On phone disconnect:

- backend stops forwarding PCM to the phone
- backend optionally keeps the Gemini Live session open for a configurable grace period
  (e.g., 30 seconds) to allow fast reconnection without losing context
- if the phone reconnects within the grace period, audio forwarding resumes using the same
  `live_session_id`
- if the grace period expires, backend sends `audioStreamEnd` to Gemini, closes the Gemini
  session gracefully, and persists any remaining transcripts
- the `conversation_id` is always preserved

### Backend-to-Gemini Session Drop

On Gemini Live disconnect from the backend side:

- backend attempts reconnection to Gemini Live
- backend re-injects the last N turns of conversation context into the new session setup message
- if the phone is connected, backend sends a brief `{"type": "interrupted"}` to clear the phone's
  audio buffer while reconnecting
- once Gemini is reconnected, audio forwarding resumes transparently
- the phone is unaware of the reconnection

### Tool Execution Failure

On tool failure:

- backend catches the error and returns a graceful `spoken_summary` to Gemini describing the
  failure briefly
- Gemini speaks the apology naturally and offers to retry
- conversation state remains intact
- the failure is logged with `tool_call_id` for observability

### Backend Service Failure

On backend process crash or restart:

- in-flight voice WebSocket sessions are lost
- the phone receives a WebSocket close frame and displays a reconnection UI
- on reconnect, the phone calls `POST /voice/session/start` to restore context
- all conversation state and turns are recovered from the persistent store
- the new voice session picks up where the conversation left off

### Notification Delivery Failure

If push notification fails:

- the `resume_event` still exists in the backend
- the conversation list badge appears the next time the app syncs conversation metadata
- `GET /jobs/{job_id}` is available for polling as a fallback

## Android App Changes From Previous Architecture

The following summarizes what changes in the Android app when moving to Option B.

### Removed

- `GeminiLiveClient` — replaced by `BackendVoiceClient`
- All Gemini WebSocket protocol handling (setup messages, realtimeInput JSON encoding, tool call
  parsing, tool result forwarding)
- Ephemeral token request logic
- `POST /voice/session/refresh-token` call
- Any Gemini API key or token storage
- Tool call forwarding to backend over HTTP
- `POST /conversations/{id}/turns` periodic sync

### Added

- `BackendVoiceClient` — a simple WebSocket client that:
  - upgrades with JWT in the Authorization header
  - sends raw PCM binary frames from `AndroidAudioRecorder`
  - receives raw PCM binary frames and enqueues them in `PcmAudioPlayer`
  - sends and receives JSON control messages
  - handles reconnection with exponential backoff

### Unchanged

- `HotwordService` and Vosk wake-word detection
- `AndroidAudioRecorder` including `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl`
- `PcmAudioPlayer` including interrupt-aware buffer clearing
- Meta DAT SDK integration for glasses video frames
- `POST /auth/session` and JWT storage
- `POST /voice/session/start`
- All `GET /conversations` and `GET /conversations/{id}` calls
- `POST /conversations/{id}/pending-turn`
- Push notification handling
- Conversation list and history UI

## Delivery Phases — Implementation Status

### Phase 0 — Android Auth UI ✅ COMPLETE

- Email registration + login screens (Jetpack Compose, Material 3)
- Google Sign-In via Credentials Manager
- Firebase Auth SDK integration (createUser, signIn, emailVerification, passwordReset)
- Splash / onboarding screen with auto-routing
- `SpecTalkNavGraph` navigation with sealed `Screen` interface

### Phase 1 — Android Voice UI ✅ COMPLETE

- `HotwordService` (Vosk wake-word detection, configurable via SharedPreferences)
- `ConnectedDeviceMonitor` gate — listens only when wearable/BT device connected
- Activation sound (320ms SoundPool beep) on wake-word detection
- `AndroidAudioRecorder` — 16kHz PCM with AEC, NoiseSuppressor, AGC
- `PcmAudioPlayer` — 24kHz PCM playback with `clear()` on interrupted events
- `BackendVoiceClient` — OkHttp WebSocket with JWT auth + binary+JSON messages
- `VoiceAgentViewModel` — full session lifecycle, inactivity timer (10s), connect timeout (12s)
- Session timeout recovery UI (`session_timeout` message handling)
- Proactive location send on session connect
- `VoiceSessionScreen` — animated orb, transcript display, status pills
- `PrdConfirmationCard` — PRD display with Yes/No voice confirmation
- `HomeScreen` — conversation history from backend
- `SettingsScreen` — wake word configuration
- FCM push notification receiver (`FcmService`)

### Phase 2 — Backend Foundation ✅ COMPLETE

- PostgreSQL (Neon) with full schema — 9 tables + Alembic migrations
- Firebase Authentication + Product JWT flow (`POST /auth/session`)
- Cloud Run deployment of FastAPI backend
- Secret Manager for all credentials
- All REST endpoints implemented (auth, voice, conversations, jobs, notifications)
- `require_auth` middleware (JWT verification on every request)
- `POST /voice/session/start` — creates or resumes conversation

### Phase 3 — Backend Voice Agent ✅ COMPLETE

- `voice_handler.py` — WebSocket audio bridge (upstream + downstream tasks)
- `gemini_live_client.py` — ADK `InMemoryRunner` + `LiveRequestQueue` (no raw WebSocket)
- `orchestrator.py` — `create_gervis_agent()` with 400+ line system instruction
- VAD config: low sensitivity, 320ms silence duration
- Canonical turn persistence from Gemini transcription events directly to PostgreSQL
- `search_tool`, `maps_tool`, `location_tool` — google_search, find_nearby_places, get_user_location
- `control_channels.py` + `location_channels.py` — per-conversation control routing
- Interrupted event handling: flush buffer → send interrupted → phone clears audio
- Session timeout detection + graceful client notification
- Turn buffering: accumulate fragments per-role, flush on turn_complete
- Session-start prompt injection (resume events / welcome-back / new greeting)
- `AudioSessionManager` with 30s grace period on phone disconnect
- Structured logging (`session_logger.py`) + OpenTelemetry + Opik tracing (`tracing.py`)

### Phase 4 — Jobs + Notifications ✅ COMPLETE

- `start_background_job` tool → `job_service.py` → Cloud Tasks enqueue
- `job_started` / `job_update` control messages to phone
- FCM push notifications (`notification_service.py`)
- `resume_event_service.py` — ResumeEvent creation and acknowledgment
- `POST /internal/jobs/execute` — Cloud Tasks callback handler
- `POST /internal/openclaw/callback` — OpenClaw job completion webhook
- `inject_job_result()` — speaks result directly if phone is connected during completion
- `GET /jobs/{job_id}` — polling fallback if push notification not delivered

### Phase 5 — Project Creation + Coding Mode ✅ COMPLETE

- `request_clarification` tool — tracks count (max 3), emits `state_update` on first call
- `generate_and_confirm_prd` tool — calls `designer_agent.py`, sends PRD to phone as `PendingAction`
- `confirm_and_dispatch` tool — creates Job, dispatches to Cloud Tasks, spoken ack
- `team_code_pr_designers/designer_agent.py` — ADK LlmAgent for PRD generation
- `openclaw_coding_tool.py` — OpenClaw integration for remote coding execution
- `lookup_project` tool — per-user project registry with fuzzy slug matching
- `user_projects` table + `project_service.py` — cross-session project persistence
- `user_integrations` table + `encryption_service.py` — Fernet-encrypted credentials
- `IntegrationsRepository.kt` — Android integration credential management
- `GET/POST /integrations` API endpoints

### Phase 6 — Advanced Features (Planned)

- Meta Wearables DAT SDK integration (glasses camera streaming, video frames)
- `three_d_model_tool` workflow — 4-image 3D model generation
- Cross-session long-term memory via `memory_tool`
- Artifact browser in app
- Resume summaries and post-job follow-up voice actions
- Richer multimodal attachment handling via `image` control messages

## Non-Goals

The following are intentionally out of scope for the first implementation:

- any Gemini API key or credential on the Android app
- direct phone-to-Gemini Live WebSocket connection
- tool call forwarding from phone to backend over HTTP
- WebRTC for audio transport (WebSocket is sufficient for v1; WebRTC is a future optimization
  for ultra-low-latency requirements)
- audio transcoding in the backend hot path (always forward raw PCM)
- storing only client-side memory
- hardcoded welcome-back text in the Android app

## Final Recommendation

Build the system as a two-layer architecture running entirely on Google Cloud:

- a thin audio terminal on Android: captures mic audio, plays response audio, displays
  conversation UI, handles notifications — authenticated only with Firebase Auth and a product JWT
- a backend-owned voice agent on Cloud Run: holds the Gemini Live session, executes all tools
  natively, persists all turns to Cloud SQL, manages conversation state, jobs, memory, and
  resume flows — all credentials live here in Secret Manager

The Android app never holds a Gemini credential of any kind. User identity is established through
Firebase Authentication; the `user_id` in the database is the stable anchor for all conversation
history, memory, and jobs. The backend audio bridge forwards raw PCM bytes with zero buffering.
The orchestrator ADK agent is the voice agent — there is no delegation protocol between phone
and backend for tool calls.

For coding execution in v1, use OpenClaw directly with its documented CLI Backends support for
Claude Code CLI and Codex CLI. Treat any custom runner implementation as a later optimization,
not a launch requirement.

The added latency of routing audio through the backend (~40–120 ms) is acceptable given that it
is small relative to Gemini's processing time, and is fully offset by: eliminating the tool
round-trip penalty, gaining native confirmation enforcement, and making the system secure by
design with no credential management required on the client.
