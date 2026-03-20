# SpecTalk

**Turn your thoughts into shipped projects — hands-free.**

SpecTalk is an AI-powered project creation tool for Meta Ray-Ban glasses and AirPods.
You talk to **Gervis** — the AI inside SpecTalk — to design, spec, and execute real projects
entirely by voice, while living your life. No screen. No keyboard. Just say what you want to
build.

---

## The Problem

Every great project starts as a thought — usually when you're away from your desk. The idea is
vivid, the architecture is clear. Then you sit down to type it out and half of it is already
gone. The tools we use to create software require you to stop and translate thought into
keystrokes. That translation is slow and lossy.

SpecTalk closes the gap between thinking and building.

---

## How It Works

```
1.  Say "Hey Gervis" (or your custom wake word)
    → A soft chime confirms Gervis is listening

2.  Describe what you want to build in plain language
    "Build me a task management app for remote teams, real-time, mobile-first"

3.  Gervis asks one smart clarifying question at a time (max 3)
    "What's the most important action users need to do on day one?"

4.  You answer naturally, continue the conversation

5.  Gervis presents a full project plan on screen and asks for your approval
    "Here's what I have. Want me to go ahead?"

6.  You confirm. The job runs in the background.
    You put your phone away and keep walking.

7.  Push notification arrives when the project is ready.

8.  Say "Hey Gervis" to resume.
    Gervis: "Welcome back. Your task app is built and the PR is open for review."
```

No screen required at any step. The entire workflow runs through voice and your wearables.

---

## Key Features

| Feature | Description |
|---------|-------------|
| **Voice project creation** | Describe any software project by voice — Gervis shapes it into an executable brief |
| **One question at a time** | No questionnaire dumps. Gervis asks one precise question per turn, optimized for voice |
| **PRD confirmation card** | Full project spec shown on screen before any code runs |
| **Confirmation before execution** | Nothing runs without your explicit voice approval |
| **Background execution** | Jobs run while you live your life — you get a push notification when done |
| **Natural resume** | Say "Hey Gervis" after a notification — picks up with a spoken summary, no UI hunting |
| **Custom wake word** | Default: "Hey Gervis". Change it to anything in Settings |
| **Activation sound** | A short chime plays through your glasses/AirPods on wake word detection |
| **Auto-disconnect** | Session ends automatically after 10s of silence — no manual tap needed |
| **Project registry** | Say "edit my langdrill project" — Gervis remembers all your projects across sessions |
| **OpenClaw integration** | Real coding execution via OpenClaw CLI Backends (Claude Code, Codex CLI) |
| **Supporting tools** | Web search, Maps, and general conversation available within any session |
| **Secure by design** | Phone holds zero API keys. All AI, credentials, and state live in the backend |

---

## Product vs Assistant

| | Name | Role |
|-|------|------|
| **Product** | SpecTalk | The app, the brand, the platform |
| **Assistant** | Gervis | The AI you speak to inside SpecTalk |

The wake word is "Hey Gervis." The app is called SpecTalk.

---

## Architecture Overview

SpecTalk uses a two-layer architecture. The phone is a thin audio terminal. All intelligence
lives in the backend.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  ANDROID APP (Kotlin / Jetpack Compose)                                     │
│                                                                             │
│  HotwordService (Vosk)     ConnectedDeviceMonitor (wearable/BT gate)       │
│  AndroidAudioRecorder ─────────────────────────────────────────────────►   │
│  (PCM 16kHz, AEC+NS+AGC)                                           PCM     │
│                                                              WebSocket      │
│  PcmAudioPlayer ◄──────────────────────────────────────────────────────    │
│  (PCM 24kHz, clear on interrupted)                              JWT auth   │
│                                                                             │
│  VoiceAgentViewModel ─ BackendVoiceClient ─ TokenRepository                │
│  VoiceSessionScreen ─ PrdConfirmationCard ─ HomeScreen                     │
│  FcmService ─ UserLocationRepository ─ IntegrationsRepository              │
└─────────────────────────────────────────────────────────────────────────────┘
                          │  WS /ws/voice/{conversation_id}
                          │  Binary PCM 16kHz up / PCM 24kHz down
                          │  JSON control messages (transcripts, state, jobs)
                          │  REST: auth, conversations, jobs, notifications
                          │
┌─────────────────────────▼───────────────────────────────────────────────────┐
│  GERVIS BACKEND (Python / FastAPI / Google ADK) — Google Cloud Run          │
│                                                                             │
│  voice_handler.py ─── upstream_task ──► LiveRequestQueue                   │
│                   └── downstream_task ◄─ ADK events                        │
│                                                                             │
│  create_gervis_agent()  ←  orchestrator.py (~400 line system instruction)  │
│  ├── google_search          find_nearby_places    get_user_location         │
│  ├── request_clarification  generate_and_confirm_prd  confirm_and_dispatch │
│  ├── start_background_job   lookup_project                                 │
│  └── (calls designer_agent.py for PRD generation)                          │
│                                                                             │
│  gemini_live_client.py ── InMemoryRunner + LiveRequestQueue                │
│  audio_session_manager.py ── 30s grace period on disconnect                │
│  job_service.py ── Cloud Tasks enqueue                                     │
│  notification_service.py ── FCM push                                       │
│  encryption_service.py ── Fernet-encrypted credentials                     │
│  project_service.py ── fuzzy slug project lookup                           │
│  tracing.py ── OpenTelemetry + Opik observability                          │
└──────────────────────────────────────────────────────────────────────────── ┘
         │                                    │
         ▼                                    ▼
┌──────────────────┐                 ┌──────────────────┐
│  Neon PostgreSQL │                 │  Google Cloud    │
│  (9 tables)      │                 │  Tasks           │
│  users           │                 │  backend-jobs    │
│  conversations   │                 │  queue           │
│  turns / jobs    │                 └──────────────────┘
│  user_projects   │                          │
│  user_integrations                          ▼
│  resume_events   │                 ┌──────────────────┐
└──────────────────┘                 │  OpenClaw        │
                                     │  (Remote coding  │
                                     │   execution)     │
                                     └──────────────────┘
```

**The phone never holds a Gemini API key.** The backend owns the Gemini Live WebSocket session,
executes all tool calls natively, and persists all conversation state. The phone is a microphone,
a speaker, and a UI — nothing more.

For the full architecture specification, see [`docs/architecture.md`](./docs/architecture.md).

---

## Tech Stack

### Android App
| Technology | Purpose |
|-----------|---------|
| Kotlin + Jetpack Compose | UI and app logic (Material 3) |
| Meta Wearables DAT SDK 0.5.0 | Glasses integration (camera, device monitor) |
| Firebase Auth SDK | Email + Google Sign-In |
| Vosk 0.3.47 | On-device wake word detection (configurable) |
| OkHttp WebSocket | Backend voice connection with JWT auth |
| AndroidAudioRecorder | Mic capture (16kHz PCM, AEC, NS, AGC) |
| PcmAudioPlayer | Real-time 24kHz PCM playback with barge-in clear |
| Google Play Services | Location services |
| Firebase Cloud Messaging | Push notification receiver |
| Encrypted SharedPreferences | Secure JWT storage |

### Backend
| Technology | Purpose |
|-----------|---------|
| Python + FastAPI | HTTP and WebSocket server |
| Google ADK (`InMemoryRunner`) | Orchestrator agent + LiveRequestQueue |
| Gemini Live API | Real-time bidirectional voice AI (~10min sessions) |
| SQLAlchemy async + asyncpg | Async PostgreSQL access |
| Alembic | Database migrations |
| Firebase Admin SDK | Token verification + FCM push |
| Fernet (cryptography) | Encrypted integration credential storage |
| OpenTelemetry + Opik | Distributed tracing + conversational AI observability |

### Google Cloud Infrastructure
| Service | Purpose |
|---------|---------|
| Cloud Run | Backend hosting (min 1 instance, 3600s WS timeout) |
| Neon PostgreSQL | Users, conversations, turns, jobs, projects, integrations |
| Firebase Authentication | Email + Google sign-in |
| Firebase Cloud Messaging | Push notifications |
| Secret Manager | All API keys and credentials |
| Cloud Storage | Project artifacts (code, assets) |
| Cloud Tasks | Background job queue (backend-jobs) |
| Artifact Registry + Cloud Build | CI/CD pipeline |

---

## Project Structure

```
GeminiLiveAPI2026/
├── android/                       ← SpecTalk Android app (Kotlin/Compose)
│   └── app/src/main/java/com/spectalk/app/
│       ├── auth/                  ← Firebase auth + JWT exchange
│       ├── voice/                 ← VoiceAgentViewModel, BackendVoiceClient
│       ├── audio/                 ← AndroidAudioRecorder, PcmAudioPlayer
│       ├── hotword/               ← HotwordService (Vosk), HotwordEventBus
│       ├── device/                ← ConnectedDeviceMonitor
│       ├── location/              ← UserLocationRepository
│       ├── conversations/         ← ConversationRepository, HomeViewModel
│       ├── integrations/          ← IntegrationsRepository
│       ├── notifications/         ← FcmService
│       └── ui/screens/            ← VoiceSessionScreen, HomeScreen, LoginScreen, etc.
│
├── gervis-backend/                ← Python backend (FastAPI/ADK)
│   ├── ws/voice_handler.py        ← WebSocket audio bridge (zero-copy)
│   ├── agents/orchestrator.py     ← create_gervis_agent() — the Gervis persona
│   ├── agents/team_code_pr_designers/ ← designer_agent.py for PRD generation
│   ├── tools/                     ← 8 voice-callable tools
│   ├── services/                  ← Audio session, jobs, notifications, encryption
│   ├── db/models.py               ← 9-table SQLAlchemy schema
│   └── api/                       ← REST endpoints + internal callbacks
│
├── samples/gemini-voice-agent/    ← Reference implementation (UI patterns)
├── samples/adk-samples/           ← Google ADK reference samples
├── docs/                          ← architecture.md, architecture-diagram.md
├── CLAUDE.md                      ← AI assistant instructions
├── AGENTS.md                      ← Agent architecture + Meta DAT SDK reference
└── TODO.md                        ← Phased delivery plan
```

---

## Building the Android App

### Vosk Wake-Word Model

The Vosk speech model is **not stored in this repository** (~20 MB binary files).
Gradle downloads and extracts it automatically into `android/app/src/main/assets/model/`
the first time you build — no manual step needed.

**If the automatic download fails** (network issue, proxy, CI environment):

```bash
curl -L https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip -o vosk-model.zip
unzip vosk-model.zip
mkdir -p android/app/src/main/assets/model
cp -r vosk-model-small-en-us-0.15/* android/app/src/main/assets/model/
rm -rf vosk-model.zip vosk-model-small-en-us-0.15
```

---

## Delivery Phases

See [`TODO.md`](./TODO.md) for the full task-level breakdown with approval checkpoints.

| Phase | Goal | Status |
|-------|------|--------|
| **0** | Android auth UI | ✅ Complete |
| **1** | Android voice UI | ✅ Complete |
| **2** | Backend foundation | ✅ Complete |
| **3** | Backend voice agent | ✅ Complete |
| **4** | Jobs + notifications | ✅ Complete |
| **5** | Project creation (coding mode, PRD, OpenClaw) | ✅ Complete |
| **6** | Meta glasses camera, 3D models, long-term memory | Planned |

---

## Security Model

| What | Where | Notes |
|------|-------|-------|
| Gemini API key | Secret Manager (backend only) | Never on device |
| Google Maps key | Secret Manager (backend only) | Never on device |
| Firebase credentials | Secret Manager (backend only) | Admin SDK server-side only |
| OpenClaw API key | Secret Manager (backend only) | Never on device |
| Integration credentials | Neon DB, Fernet-encrypted | Per-user, decrypted on-demand |
| User auth | Firebase Auth SDK on device | ID token exchanged for product JWT |
| Product JWT | EncryptedSharedPreferences (device) | 30-day TTL, used for all API calls |
| Conversation data | Neon PostgreSQL (backend) | SSL-required connection |
| Project artifacts | Cloud Storage (backend) | Accessed via backend only |

The phone never holds a long-lived credential for any AI service, database, or cloud provider.

---

## Voice Session Lifecycle

```
Silence
  │
  ▼  Wake word detected ("Hey Gervis")
     [ConnectedDeviceMonitor must see wearable/BT device]
Activation chime plays through glasses/AirPods
  │
  ▼  WebSocket opens to backend
     [Gervis proactively injected with resume events or new session context]
Gervis: "Hi, what would you like to build today?"
  │
  ▼  User talks → Gervis responds → tools execute in backend
  │
  ▼  [User says "build me X"]
Gervis asks clarifying questions (max 3), one at a time
  │
  ▼  Gervis generates PRD → PrdConfirmationCard shown on phone
User confirms or rejects by voice
  │
  ▼  [Confirmed] Job created → Cloud Tasks → OpenClaw executes
     Auto-disconnect → WebSocket closes → Wake word listener resumes
  │
  ▼  [Job completes in background]
Push notification → User taps → "Hey Gervis"
Gervis: "Welcome back. Your app is built. The PR is ready for review."
```

---

## References

- [`docs/architecture.md`](./docs/architecture.md) — Complete system design, all technical decisions
- [`docs/architecture-diagram.md`](./docs/architecture-diagram.md) — ASCII system diagrams
- [`TODO.md`](./TODO.md) — Active delivery plan with approval gates
- [`CLAUDE.md`](./CLAUDE.md) — Instructions for the AI coding assistant
- [`AGENTS.md`](./AGENTS.md) — Agent architecture + Meta DAT SDK reference
- [Meta Wearables DAT SDK](https://wearables.developer.meta.com/docs/develop/)
- [Gemini Live API](https://ai.google.dev/gemini-api/docs/live)
- [Google ADK](https://google.github.io/adk-docs/)
- [OpenClaw CLI Backends](https://docs.openclaw.ai/gateway/cli-backends#cli-backends)
