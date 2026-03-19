# SpecTalk — Complete System Architecture Diagram

```
╔══════════════════════════════════════════════════════════════════════════════════════╗
║                              SPECTALK SYSTEM ARCHITECTURE                           ║
╚══════════════════════════════════════════════════════════════════════════════════════╝


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  ANDROID APP                                        │
│                              (Kotlin / Jetpack Compose)                             │
│                                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐  │
│  │HotwordService│  │ PcmAudio     │  │BackendVoice  │  │   VoiceAgentViewModel │  │
│  │              │  │ Player       │  │ Client       │  │                       │  │
│  │ Vosk grammar │  │              │  │              │  │ - Session lifecycle   │  │
│  │ "Hey Gervis" │  │ PCM 24kHz    │  │ WebSocket    │  │ - Inactivity timer    │  │
│  │              │  │ playback     │  │ JWT auth     │  │ - StateFlow → UI      │  │
│  └──────┬───────┘  └──────▲───────┘  └──────┬───────┘  └───────────────────────┘  │
│         │                 │                  │  ▲                                   │
│         │ wake word       │ PCM audio        │  │ control messages                 │
│         │ detected        │ (24kHz)          │  │ (JSON)                           │
│         ▼                 │                  ▼  │                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐  │
│  │ Activation   │  │ AndroidAudio │  │TokenRepository│  │   FcmService          │  │
│  │ Sound        │  │ Recorder     │  │               │  │                       │  │
│  │ (chime.wav)  │  │              │  │ EncryptedPrefs│  │ Receives push         │  │
│  │              │  │ PCM 16kHz    │  │ Product JWT   │  │ notifications         │  │
│  └──────────────┘  └──────┬───────┘  └──────┬────────┘  └───────────┬───────────┘  │
│                           │                  │                       │               │
└───────────────────────────┼──────────────────┼───────────────────────┼───────────────┘
                            │ PCM audio        │ HTTPS / WSS           │ tap
                            │ (binary frames)  │                       │
                            ▼                  ▼                       ▼
═══════════════════════════════════════════════════════════════════════════════════════
                                    INTERNET
═══════════════════════════════════════════════════════════════════════════════════════
                            │                  │                       │
                            ▼                  ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         GOOGLE CLOUD RUN — gervis-backend                           │
│                    (FastAPI / Python — us-central1 — min 0 instances)               │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                          WEBSOCKET VOICE BRIDGE                             │   │
│  │                       WS /ws/voice/{conversation_id}                        │   │
│  │                                                                             │   │
│  │   ┌─────────────┐         ┌──────────────────────────────────────────┐     │   │
│  │   │  upstream   │         │              downstream                  │     │   │
│  │   │   _task     │         │               _task                      │     │   │
│  │   │             │         │                                          │     │   │
│  │   │ phone→ADK   │         │ ADK events → phone                      │     │   │
│  │   │ PCM audio   │         │  - PCM audio bytes (binary)             │     │   │
│  │   │ images      │         │  - interrupted (JSON)                   │     │   │
│  │   │ end_of_     │         │  - input_transcript (JSON)              │     │   │
│  │   │  speech     │         │  - output_transcript (JSON)             │     │   │
│  │   │ location    │         │  - turn_complete (JSON)                 │     │   │
│  │   │  _response  │         │  - job_started (JSON)                   │     │   │
│  │   └──────┬──────┘         │  - job_update (JSON)                   │     │   │
│  │          │                │  - state_update (JSON)                  │     │   │
│  │          ▼                └──────────────────▲───────────────────────┘     │   │
│  │   LiveRequestQueue                           │                             │   │
│  └──────────────────────┬────────────────────────┼─────────────────────────────┘   │
│                         │                        │                                  │
│                         ▼                        │                                  │
│  ┌─────────────────────────────────────────────┐ │                                  │
│  │          GEMINI LIVE CLIENT (ADK)           │ │                                  │
│  │          InMemoryRunner + Agent             │ │                                  │
│  │                                             │ │                                  │
│  │  ┌─────────────────────────────────────┐   │ │                                  │
│  │  │         GERVIS AGENT                │   │ │                                  │
│  │  │         (ADK Agent)                 │   │ │                                  │
│  │  │                                     │   │ │                                  │
│  │  │  model: gemini-2.5-flash-native-    │   │ │                                  │
│  │  │         audio-preview               │   │ │                                  │
│  │  │                                     │   │ │                                  │
│  │  │  Tools:                             │   │ │                                  │
│  │  │  ┌────────────────┐                 │   │ │                                  │
│  │  │  │ google_search  │ ←── grounded    │   │ │                                  │
│  │  │  └────────────────┘     web search  │   │ │                                  │
│  │  │  ┌────────────────┐                 │   │ │                                  │
│  │  │  │get_user_       │ ←── GPS from    │   │ │                                  │
│  │  │  │ location       │     phone       │   │ │                                  │
│  │  │  └────────────────┘                 │   │ │                                  │
│  │  │  ┌────────────────┐                 │   │ │                                  │
│  │  │  │find_nearby_    │ ←── Google Maps │   │ │                                  │
│  │  │  │ places         │     grounding   │   │ │                                  │
│  │  │  └────────────────┘                 │   │ │                                  │
│  │  │  ┌────────────────┐                 │   │ │                                  │
│  │  │  │start_background│ ──► creates job │   │ │                                  │
│  │  │  │  _job          │     + enqueues  │   │ │                                  │
│  │  │  └────────────────┘                 │   │ │                                  │
│  │  └─────────────────────────────────────┘   │ │                                  │
│  └─────────────────────────────────────────────┘ │                                  │
│                         │                        │                                  │
│                         ▼ audio + events         │ events                           │
│              ┌──────────────────────┐            │                                  │
│              │   GEMINI LIVE API    │────────────┘                                  │
│              │  (Google's servers)  │                                               │
│              └──────────────────────┘                                               │
│                                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────────────┐ │
│  │                            REST API ROUTES                                    │ │
│  │                                                                               │ │
│  │  POST /auth/session          ← Firebase ID token → Product JWT               │ │
│  │  POST /voice/session/start   ← Creates conversation row, returns conv_id     │ │
│  │  GET  /conversations         ← List user's conversations                     │ │
│  │  GET  /conversations/:id     ← Single conversation + working memory          │ │
│  │  DEL  /conversations/:id     ← FK-safe cascade delete                        │ │
│  │  GET  /conversations/:id/turns ← Paginated turn history                      │ │
│  │  POST /conversations/:id/ack-resume-event ← Clear notification badge         │ │
│  │  GET  /jobs/:id              ← Job status + artifacts                        │ │
│  │  POST /notifications/device/register ← Save FCM push token                  │ │
│  │  POST /internal/jobs/execute ← Called by Cloud Tasks (job runner)            │ │
│  └───────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                     │
└──────────────────┬──────────────────────────────────────────────────────────────────┘
                   │
       ┌───────────┴────────────────────────────────────┐
       │                                                │
       ▼                                                ▼
┌─────────────────────┐                    ┌──────────────────────────┐
│   NEON POSTGRESQL   │                    │  GOOGLE CLOUD TASKS      │
│   (Serverless DB)   │                    │  Queue: backend-jobs      │
│                     │                    │                          │
│  Tables:            │                    │  Task payload:           │
│  - users            │                    │  {                       │
│  - conversations    │                    │    job_id,               │
│  - turns            │                    │    job_type,             │
│  - jobs             │                    │    conversation_id,      │
│  - resume_events    │                    │    user_id,              │
│  - pending_actions  │                    │    payload               │
│  - assets           │                    │  }                       │
│                     │                    │                          │
│  pool_pre_ping=True │                    │  → POST /internal/       │
│  (Neon auto-suspend │                    │    jobs/execute          │
│   recovery)         │                    │  max deadline: 1800s     │
└─────────────────────┘                    └──────────────────────────┘


═══════════════════════════════════════════════════════════════════════════════════════
                              AUTHENTICATION FLOW
═══════════════════════════════════════════════════════════════════════════════════════

  Android App                  Backend                    Firebase
      │                           │                           │
      │── email + password ──────►│                           │
      │                           │── verify ID token ───────►│
      │                           │◄── user confirmed ────────│
      │                           │── upsert user row (DB)    │
      │◄── Product JWT ───────────│                           │
      │    (stored encrypted)     │                           │
      │                           │                           │
      │── Bearer JWT on every ───►│                           │
      │   API call                │── require_auth validates  │
      │                           │   JWT signature locally   │


═══════════════════════════════════════════════════════════════════════════════════════
                           VOICE SESSION FLOW (HAPPY PATH)
═══════════════════════════════════════════════════════════════════════════════════════

  Phone          Backend (Cloud Run)        ADK / Gemini Live
    │                    │                         │
    │ "Hey Gervis"       │                         │
    │ (wake word)        │                         │
    │── POST /voice/session/start ──────────────►  │
    │◄── { conversation_id } ──────────────────    │
    │── WS /ws/voice/{id} ──────────────────────►  │
    │                    │── run_live() ──────────►│
    │                    │                         │── Gemini session opens
    │── PCM audio ──────►│── send_realtime() ─────►│
    │                    │                         │── VAD detects speech
    │                    │◄── audio response ──────│
    │◄── PCM audio ──────│                         │
    │◄── output_transcript ──────────────────────  │
    │                    │                         │
    │── PCM audio ──────►│── (tool call triggered) │
    │                    │── google_search() ──    │
    │                    │── find_nearby_places()  │
    │                    │── start_background_job()│
    │                    │                         │


═══════════════════════════════════════════════════════════════════════════════════════
                         BACKGROUND JOB FLOW (NOTIFICATIONS)
═══════════════════════════════════════════════════════════════════════════════════════

  Phone     Backend (Cloud Run)    Cloud Tasks      Firebase FCM
    │               │                   │                │
    │ "research X"  │                   │                │
    │──────────────►│                   │                │
    │               │── Gemini calls    │                │
    │               │   start_background│                │
    │               │   _job()          │                │
    │               │── create job row (DB)              │
    │◄── job_started│   status=queued   │                │
    │    (spoken ack│                   │                │
    │     + JSON)   │── enqueue task ──►│                │
    │               │                  │                 │
    │ (user closes  │                  │── POST /internal│
    │  the app)     │                  │   /jobs/execute │
    │               │◄─────────────────│                 │
    │               │── update job     │                 │
    │               │   status=running │                 │
    │               │── execute job    │                 │
    │               │   (mock/real)    │                 │
    │               │── update job     │                 │
    │               │   status=completed                 │
    │               │── create resume_event              │
    │               │── send FCM ──────────────────────►│
    │               │                  │                 │── push to device
    │◄── notification ──────────────────────────────────│
    │    "Job done" │                  │                 │
    │               │                  │                 │
    │ (user taps    │                  │                 │
    │  notification)│                  │                 │
    │──────────────►│                  │                 │
    │ WS reconnects │── inject resume context            │
    │               │   into Gemini                      │
    │◄── Gervis speaks welcome-back message              │
    │               │                  │                 │


═══════════════════════════════════════════════════════════════════════════════════════
                              GCP SERVICES MAP
═══════════════════════════════════════════════════════════════════════════════════════

  ┌─────────────────────────────────────────────────────────────────────────┐
  │                        spectalk-488516 (GCP Project)                   │
  │                                                                         │
  │  ┌──────────────────┐    ┌──────────────────┐   ┌───────────────────┐  │
  │  │   Cloud Run      │    │  Artifact Registry│   │  Cloud Build      │  │
  │  │                  │    │                  │   │                   │  │
  │  │  gervis-backend  │◄───│  gervis/         │◄──│  cloudbuild.yaml  │  │
  │  │  us-central1     │    │  gervis-backend  │   │  build→push→      │  │
  │  │  min 0 instances │    │  :latest         │   │  migrate→deploy   │  │
  │  │  1Gi memory      │    └──────────────────┘   └───────────────────┘  │
  │  │  3600s timeout   │                                                   │
  │  │                  │    ┌──────────────────┐   ┌───────────────────┐  │
  │  │  Service Account:│    │  Secret Manager  │   │  Cloud Tasks      │  │
  │  │  gervis-backend@ │    │                  │   │                   │  │
  │  │  spectalk-...    │───►│  JWT_SECRET      │   │  backend-jobs     │  │
  │  └──────────────────┘    │  DATABASE_URL    │   │  us-central1      │  │
  │           │              │  GEMINI_API_KEY  │   │  max 1800s        │  │
  │           │              │  FIREBASE_SA_JSON│   └───────────────────┘  │
  │           │              │  BACKEND_BASE_URL│                          │
  │           ▼              └──────────────────┘                          │
  │  ┌──────────────────┐                          ┌───────────────────┐   │
  │  │  Cloud Run Job   │                          │  Cloud Trace      │   │
  │  │                  │                          │                   │   │
  │  │  gervis-migrate  │                          │  OpenTelemetry    │   │
  │  │  Alembic         │                          │  spans + traces   │   │
  │  │  upgrade head    │                          │  ENABLE_TRACING=  │   │
  │  └──────────────────┘                          │  cloud            │   │
  │                                                └───────────────────┘   │
  └─────────────────────────────────────────────────────────────────────────┘

  External services (not in GCP project):
  ┌──────────────────────┐    ┌──────────────────┐    ┌──────────────────┐
  │  Firebase Auth       │    │  Firebase FCM    │    │  Neon PostgreSQL │
  │  (Google-managed)    │    │  (Google-managed)│    │  (External SaaS) │
  │                      │    │                  │    │                  │
  │  Email + Google      │    │  Push to Android │    │  Free tier       │
  │  sign-in             │    │  channel:        │    │  Auto-suspend    │
  │  ID token → JWT      │    │  spectalk_jobs   │    │  ssl=require     │
  └──────────────────────┘    └──────────────────┘    └──────────────────┘


═══════════════════════════════════════════════════════════════════════════════════════
                              DATA MODEL (KEY TABLES)
═══════════════════════════════════════════════════════════════════════════════════════

  users                    conversations              jobs
  ─────────────────        ─────────────────────      ─────────────────────────
  id (uuid) PK             id (uuid) PK               id (uuid) PK
  firebase_uid             user_id → users            conversation_id → convs
  email                    state                      user_id → users
  push_token  ◄────────    (idle/active/              job_type
  created_at               running_job/               status
                           awaiting_resume)           (queued/running/
                           last_turn_summary           completed/failed)
                           pending_resume_count        spoken_completion_summary
                           working_memory              display_completion_summary
                                                      artifacts (jsonb)

  turns                    resume_events
  ─────────────────        ─────────────────────────────
  id (uuid) PK             id (uuid) PK
  conversation_id          conversation_id → convs
  role (user/assistant)    job_id → jobs
  text                     event_type
  event_type               spoken_summary
  created_at               display_summary
                           acknowledged
                           artifacts (jsonb)
```
