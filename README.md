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

3.  Gervis asks one smart clarifying question at a time
    "What's the most important action users need to do on day one?"

4.  You answer naturally, continue the conversation

5.  Gervis summarizes the full project plan and asks for your approval
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
| **Confirmation before execution** | Nothing runs without your explicit voice approval |
| **Background execution** | Jobs run while you live your life — you get a push notification when done |
| **Natural resume** | Say "Hey Gervis" after a notification — picks up with a spoken summary, no UI hunting |
| **Custom wake word** | Default: "Hey Gervis". Change it to anything in Settings |
| **Activation sound** | A short chime plays through your glasses/AirPods on wake word detection |
| **Auto-disconnect** | Session ends automatically after 10s of silence — no manual tap needed |
| **Supporting tools** | Web search, Maps, and general conversation available within any session |
| **Secure by design** | Phone holds zero API keys. All AI, credentials, and state live in the backend |

---

## Product vs Assistant

| | Name | Role |
|-|------|------|
| **Product** | SpecTalk | The app, the brand, the platform |
| **Assistant** | Gervis | The AI you speak to inside SpecTalk |

The wake word is "Hey Gervis." The app is called SpecTalk. Both names serve a purpose: SpecTalk
communicates what the product does; Gervis gives the assistant a persona and makes the
interaction feel personal.

---

## Architecture Overview

SpecTalk uses a two-layer architecture. The phone is a thin audio terminal. All intelligence
lives in the backend.

```
┌─────────────────────────┐              ┌──────────────────────────────┐
│  ANDROID APP            │              │  BACKEND (Google Cloud)      │
│                         │  PCM audio   │                              │
│  Mic capture (16kHz) ───┼─────────────►│  Audio bridge (zero-copy)    │
│  PcmAudioPlayer     ◄───┼─────────────┤  Gemini Live session         │
│  Vosk wake word         │  PCM audio   │  ADK Orchestrator            │
│  SpecTalk UI            │              │  Project creation pipeline   │
│  Firebase Auth JWT      │  JWT auth    │  Tools: Search, Maps, Jobs   │
│                         │              │  Cloud SQL database          │
│  No API keys            │              │  All credentials             │
└─────────────────────────┘              └──────────────────────────────┘
        Android                                   Google Cloud Run
   Kotlin / Compose                      Python / FastAPI / Google ADK
   Meta DAT SDK                          Cloud SQL / FCM / Secret Manager
```

**The phone never holds a Gemini API key.** The backend owns the Gemini Live WebSocket session,
executes all tool calls natively, and persists all conversation state. The phone is a microphone,
a speaker, and a UI — nothing more.

For the full architecture specification, see [`architecture.md`](./architecture.md).

---

## Tech Stack

### Android App
| Technology | Purpose |
|-----------|---------|
| Kotlin + Jetpack Compose | UI and app logic |
| Meta Wearables DAT SDK | Glasses integration (video frames) |
| Firebase Auth SDK | Email registration and login |
| Vosk | On-device wake word detection |
| OkHttp WebSocket | Backend voice connection |
| AndroidAudioRecorder | Mic capture with AEC, NS, AGC |
| PcmAudioPlayer | Real-time audio playback |

### Backend
| Technology | Purpose |
|-----------|---------|
| Python + FastAPI | HTTP and WebSocket server |
| Google ADK | Orchestrator agent and tool framework |
| Gemini Live API | Real-time bidirectional voice AI |
| SQLAlchemy + asyncpg | Async database access |
| Alembic | Database migrations |
| Firebase Admin SDK | Token verification + FCM push |

### Google Cloud Infrastructure
| Service | Purpose |
|---------|---------|
| Cloud Run | Backend hosting (min 1 instance, 3600s WS timeout) |
| Cloud SQL (PostgreSQL) | Users, conversations, turns, jobs, resume events |
| Firebase Authentication | Email registration and login |
| Firebase Cloud Messaging | Push notifications |
| Secret Manager | All API keys and credentials |
| Cloud Storage | Project artifacts (code, assets) |
| Cloud Tasks | Background job queue |
| Artifact Registry + Cloud Build | CI/CD pipeline |

---

## Project Structure

```
meta-wearables-dat-android/        ← Android app repository (this repo)
│
├── app/                           ← SpecTalk Android app
├── samples/gemini-voice-agent/    ← Reference implementation (UI patterns)
├── mwdat-core/                    ← Meta DAT SDK core
├── mwdat-camera/                  ← Meta DAT SDK camera
├── mwdat-mockdevice/              ← Meta DAT SDK mock device for testing
│
├── CLAUDE.md                      ← AI assistant instructions for this project
├── README-SPECTALK.md             ← This file
├── architecture.md                ← Full system design specification
├── TODO.md                        ← Phased delivery plan
├── DEVPOST.md                     ← Hackathon submission write-up
├── VIDEO_PRESENTATION.md          ← 2-minute video script and scene breakdown
├── VIDEO_VISUALS.md               ← Image and video generation prompts
└── .claude/
    ├── rules/dat-conventions.md   ← Android/Kotlin coding conventions
    └── skills/                    ← Available AI assistant skills

gervis-backend/                    ← Python backend repository (Phase 2)
│
├── main.py
├── ws/voice_handler.py            ← Audio WebSocket bridge
├── agents/orchestrator.py         ← ADK voice agent (IS the Gervis persona)
├── agents/team_code_pr_designers/ ← Multi-agent project spec workflow
├── tools/                         ← search, maps, memory, job, openclaw tools
├── services/                      ← audio session, conversation, job, notification
├── db/                            ← SQLAlchemy models + Alembic migrations
└── auth/                          ← Firebase token verification + JWT
```

---

## Building the Android App

### Vosk Wake-Word Model

The Vosk speech model is **not stored in this repository** (it's ~20 MB of binary files).
Gradle downloads and extracts it automatically into `android/app/src/main/assets/model/`
the first time you build — no manual step needed.

**If the automatic download fails** (network issue, proxy, CI environment), download it manually:

```bash
# From the repo root
curl -L https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip -o vosk-model.zip
unzip vosk-model.zip
mkdir -p android/app/src/main/assets/model
cp -r vosk-model-small-en-us-0.15/* android/app/src/main/assets/model/
rm -rf vosk-model.zip vosk-model-small-en-us-0.15
```

Then re-run the build normally.

---

## Delivery Phases

See [`TODO.md`](./TODO.md) for the full task-level breakdown with approval checkpoints.

| Phase | Goal | Key Deliverable |
|-------|------|----------------|
| **0** | Android auth UI | Registration, login, settings (wake word), empty home |
| **1** | Android voice UI | Wake word, activation sound, voice session, inactivity timeout |
| **2** | Backend foundation | Cloud Run, Cloud SQL, Firebase Auth, all REST endpoints |
| **3** | Backend voice agent | Gemini Live server-side, ADK orchestrator, audio bridge, search + maps |
| **4** | Jobs + notifications | Background jobs, FCM, resume flow, welcome-back voice |
| **5** | Project creation | Coding mode, `team_code_pr_designers`, confirmation, OpenClaw |
| **6** | Advanced features | 3D model, long-term memory, artifact browser, multimodal |

Each phase ends with an explicit approval gate before the next phase begins.

---

## Security Model

| What | Where | Notes |
|------|-------|-------|
| Gemini API key | Secret Manager (backend only) | Never on device |
| Google Maps key | Secret Manager (backend only) | Never on device |
| Firebase credentials | Secret Manager (backend only) | Admin SDK server-side only |
| User auth | Firebase Auth SDK on device | ID token exchanged for product JWT |
| Product JWT | Android Keystore (device) | Short-lived, used for all API calls |
| Conversation data | Cloud SQL (backend) | Encrypted at rest by Cloud SQL default |
| Project artifacts | Cloud Storage (backend) | Accessed via backend only |

The phone never holds a long-lived credential for any AI service, database, or cloud provider.

---

## Voice Session Lifecycle

```
Silence
  │
  ▼ Wake word detected ("Hey Gervis")
Activation chime plays through glasses/AirPods
  │
  ▼ WebSocket opens to backend
Gervis: "Hi, what would you like to build today?"
  │
  ▼ User talks → Gervis responds → conversation flows
  │
  ▼ [User says "goodbye" OR 10 seconds of silence from both sides]
Auto-disconnect → WebSocket closes → Wake word listener resumes
  │
  ▼ [If a background job completed]
Push notification → User taps → "Hey Gervis" → Resume with spoken summary
```

---

## References

- [`architecture.md`](./docs/architecture.md) — Complete system design, all technical decisions
- [`TODO.md`](./TODO.md) — Active delivery plan with approval gates
- [`CLAUDE.md`](./CLAUDE.md) — Instructions for the AI coding assistant working on this project
- [Meta Wearables DAT SDK](https://wearables.developer.meta.com/docs/develop/)
- [Gemini Live API](https://ai.google.dev/gemini-api/docs/live)
- [Google ADK](https://google.github.io/adk-docs/)
- [OpenClaw CLI Backends](https://docs.openclaw.ai/gateway/cli-backends#cli-backends)
