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
│  │ "Hey Gervis" │  │ PCM 24kHz    │  │ OkHttp WS    │  │ - Inactivity timer    │  │
│  │ (configurable│  │ playback     │  │ JWT auth     │  │   (10s)               │  │
│  │  via prefs)  │  │ clear() on   │  │              │  │ - Connect timeout     │  │
│  │              │  │ interrupted  │  │ Binary+JSON  │  │   (12s)               │  │
│  │ ConnectedDev │  │              │  │ SharedFlow   │  │ - StateFlow → UI      │  │
│  │ Monitor gate │  │ hasPending   │  │ events       │  │ - Session timeout UX  │  │
│  └──────┬───────┘  │ Audio        │  └──────┬───────┘  └───────────────────────┘  │
│         │          └──────▲───────┘         │  ▲                                   │
│         │ wake word       │ PCM audio        │  │ VoiceClientEvent                 │
│         │ detected        │ (24kHz)          │  │ (Connected, AudioChunk,          │
│         ▼                 │                  │  │  InputTranscript,                │
│  ┌──────────────┐  ┌──────────────┐         │  │  OutputTranscript,               │
│  │ Activation   │  │ AndroidAudio │         │  │  Interrupted, StateUpdate,       │
│  │ Sound        │  │ Recorder     │         │  │  JobStarted, LocationRequest,    │
│  │ (SoundPool   │  │              │         │  │  SessionTimeout, Error)           │
│  │  320ms beep) │  │ PCM 16kHz    │         │  │                                   │
│  │              │  │ AEC+NS+AGC   │         ▼  │                                   │
│  └──────────────┘  └──────┬───────┘  ┌──────────────┐  ┌───────────────────────┐  │
│                           │          │TokenRepository│  │   FcmService          │  │
│                           │          │               │  │                       │  │
│  ┌──────────────┐         │          │EncryptedPrefs │  │ Receives push         │  │
│  │UserLocation  │         │          │ Product JWT   │  │ notifications         │  │
│  │ Repository   │         │          │ start_session │  │ (job completed,       │  │
│  │              │         │          │ register_push │  │  resume events)       │  │
│  │ Google Play  │         │          └──────┬────────┘  └───────────┬───────────┘  │
│  │ Services     │         │                 │                       │               │
│  └──────┬───────┘         │                 │                       │               │
│         │ location_response│                │                       │               │
└─────────┼─────────────────┼─────────────────┼───────────────────────┼───────────────┘
          │ PCM audio        │ PCM audio       │ HTTPS / WSS           │ tap
          │ (binary frames)  │ 16kHz           │ REST + WebSocket      │
          ▼                  ▼                 ▼                       ▼
═══════════════════════════════════════════════════════════════════════════════════════
                                    INTERNET
═══════════════════════════════════════════════════════════════════════════════════════
          │                  │                 │                       │
          ▼                  ▼                 ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         GOOGLE CLOUD RUN — gervis-backend                           │
│                    (FastAPI / Python — us-central1 — min 1 instance)                │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                          WEBSOCKET VOICE BRIDGE                             │   │
│  │                       WS /ws/voice/{conversation_id}                        │   │
│  │                     voice_handler.py (410 lines)                            │   │
│  │                                                                             │   │
│  │   ┌─────────────┐         ┌──────────────────────────────────────────┐     │   │
│  │   │  upstream   │         │              downstream                  │     │   │
│  │   │   _task     │         │               _task                      │     │   │
│  │   │             │         │                                          │     │   │
│  │   │ phone→ADK   │         │ ADK events → phone                      │     │   │
│  │   │ PCM audio   │         │  - PCM audio bytes (binary)             │     │   │
│  │   │ images      │         │  - interrupted (JSON) ← CRITICAL        │     │   │
│  │   │ end_of_     │         │  - input_transcript (JSON)              │     │   │
│  │   │  speech     │         │  - output_transcript (JSON)             │     │   │
│  │   │ location_   │         │  - turn_complete (JSON)                 │     │   │
│  │   │  response   │         │  - state_update (JSON)                  │     │   │
│  │   └──────┬──────┘         │  - job_started (JSON)                   │     │   │
│  │          │                │  - job_update (JSON)                   │     │   │
│  │          │                │  - session_timeout (JSON)               │     │   │
│  │          │                │  - request_location (JSON)              │     │   │
│  │          │                │  - error (JSON)                         │     │   │
│  │          │                └──────────────────▲───────────────────────┘     │   │
│  │          │                                   │                             │   │
│  │          │           Turn buffering:         │ control_channels.py         │   │
│  │          │           accumulate fragments,   │ (per-conversation routing)  │   │
│  │          │           flush on turn_complete  │                             │   │
│  └──────────┼───────────────────────────────────┼─────────────────────────────┘   │
│             │                                   │                                  │
│             ▼                                   │                                  │
│  ┌─────────────────────────────────────────────┐│                                  │
│  │        AUDIO SESSION MANAGER               │ │                                  │
│  │        audio_session_manager.py            │ │                                  │
│  │                                            │ │                                  │
│  │  - Per-conversation ADK session lifecycle  │ │                                  │
│  │  - 30s grace period on phone disconnect    │ │                                  │
│  │  - live queue registration for job inject  │ │                                  │
│  │  - Session-start prompt injection:         │ │                                  │
│  │    resume events / welcome-back / greeting │ │                                  │
│  └──────────────────────┬──────────────────────┘ │                                  │
│                         │                        │                                  │
│                         ▼                        │                                  │
│  ┌─────────────────────────────────────────────┐ │                                  │
│  │          GEMINI LIVE CLIENT (ADK)           │ │                                  │
│  │          gemini_live_client.py              │ │                                  │
│  │          InMemoryRunner + LiveRequestQueue  │ │                                  │
│  │                                             │ │                                  │
│  │  ┌─────────────────────────────────────┐   │ │                                  │
│  │  │         GERVIS AGENT                │   │ │                                  │
│  │  │         orchestrator.py             │   │ │                                  │
│  │  │                                     │   │ │                                  │
│  │  │  model: gemini-2.5-flash-native-    │   │ │                                  │
│  │  │         audio-preview               │   │ │                                  │
│  │  │  ~400 line system instruction       │   │ │                                  │
│  │  │  VAD: low sensitivity, 320ms silence│   │ │                                  │
│  │  │                                     │   │ │                                  │
│  │  │  Voice-Callable Tools:              │   │ │                                  │
│  │  │  ┌────────────────────────────┐     │   │ │                                  │
│  │  │  │ google_search              │     │   │ │                                  │
│  │  │  │ get_user_location          │ ◄───┼───┼─┘ GPS from phone                  │
│  │  │  │ find_nearby_places         │     │   │                                    │
│  │  │  │ start_background_job       │ ──► │   │ creates job + enqueues             │
│  │  │  │ request_clarification      │ ──► │   │ emits state_update to phone        │
│  │  │  │ generate_and_confirm_prd   │ ──► │   │ calls designer_agent.py            │
│  │  │  │ confirm_and_dispatch       │ ──► │   │ creates Job + Cloud Task           │
│  │  │  │ lookup_project             │ ──► │   │ fuzzy slug match user_projects     │
│  │  │  └────────────────────────────┘     │   │                                    │
│  │  └─────────────────────────────────────┘   │                                    │
│  └─────────────────────────────────────────────┘                                   │
│                         │                                                           │
│                         ▼ audio + events                                            │
│              ┌──────────────────────┐                                               │
│              │   GEMINI LIVE API    │                                               │
│              │  (Google's servers)  │                                               │
│              │                      │                                               │
│              │  ~10 min session     │                                               │
│              │  limit (preview)     │                                               │
│              └──────────────────────┘                                               │
│                                                                                     │
│  ┌──────────────────────────────────────────────────────────────────────────────┐  │
│  │                       TEAM CODE PR DESIGNERS                                 │  │
│  │                   agents/team_code_pr_designers/                             │  │
│  │                                                                              │  │
│  │  designer_agent.py — ADK LlmAgent for PRD generation                        │  │
│  │  Called by generate_and_confirm_prd() tool                                  │  │
│  │  Returns structured PRD → PrdConfirmationCard on phone                      │  │
│  └──────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────────────┐ │
│  │                            REST API ROUTES                                    │ │
│  │                                                                               │ │
│  │  POST /auth/session              ← Firebase ID token → Product JWT           │ │
│  │  POST /voice/session/start       ← Create/resume conversation, return id     │ │
│  │  GET  /conversations             ← List user's conversations                 │ │
│  │  GET  /conversations/:id         ← Single conversation + state               │ │
│  │  GET  /conversations/:id/turns   ← Paginated turn history                   │ │
│  │  POST /conversations/:id/ack-resume-event ← Clear notification badge        │ │
│  │  GET  /jobs/:id                  ← Job status + artifacts                   │ │
│  │  POST /notifications/device/register ← Save FCM push token                 │ │
│  │  GET/POST /integrations          ← Encrypted credential management          │ │
│  │  POST /internal/jobs/execute     ← Called by Cloud Tasks (job runner)       │ │
│  │  POST /internal/openclaw/callback ← OpenClaw webhook for job completion     │ │
│  └───────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                     │
│  ┌──────────────────────────────────────────────────────────────────────────────┐  │
│  │                         OBSERVABILITY                                        │  │
│  │  session_logger.py — Structured ADK event logging                           │  │
│  │  tracing.py — OpenTelemetry + Opik conversational AI observability          │  │
│  │  Trace keys: user_id, conversation_id, turn_id, job_id                      │  │
│  └──────────────────────────────────────────────────────────────────────────────┘  │
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
│  - pending_actions  │                    │    user_id,              │
│  - resume_events    │                    │    payload               │
│  - assets           │                    │  }                       │
│  - user_integrations│                    │                          │
│  - user_projects    │                    │  → POST /internal/       │
│                     │                    │    jobs/execute          │
│  pool_pre_ping=True │                    │                          │
│  (Neon auto-suspend │                    │  ← POST /internal/       │
│   recovery)         │                    │    openclaw/callback     │
└─────────────────────┘                    └──────────────────────────┘


═══════════════════════════════════════════════════════════════════════════════════════
                              AUTHENTICATION FLOW
═══════════════════════════════════════════════════════════════════════════════════════

  Android App                  Backend                    Firebase
      │                           │                           │
      │── email+pw or Google ────►│                           │
      │   (Firebase Auth SDK)     │── verify ID token ───────►│
      │                           │◄── user confirmed ────────│
      │                           │── upsert user row (DB)    │
      │◄── Product JWT ───────────│                           │
      │    (EncryptedSharedPrefs) │                           │
      │                           │                           │
      │── Bearer JWT on all ─────►│                           │
      │   API + WebSocket calls   │── require_auth validates  │
      │                           │   JWT signature locally   │


═══════════════════════════════════════════════════════════════════════════════════════
                           VOICE SESSION FLOW (HAPPY PATH)
═══════════════════════════════════════════════════════════════════════════════════════

  Phone                Backend (Cloud Run)             ADK / Gemini Live
    │                         │                               │
    │ "Hey Gervis"            │                               │
    │ (wake word detected)    │                               │
    │ [activation beep plays] │                               │
    │── POST /voice/session/start ──────────────────────────► │
    │◄── { conversation_id } ──────────────────────────────── │
    │── WS /ws/voice/{id} JWT ─────────────────────────────► │
    │── proactive location ──►│── inject location context     │
    │                         │── check resume events ───────►│
    │                         │── run InMemoryRunner ─────────►│
    │                         │                               │── Gemini session opens
    │                         │◄── "Hi, what do you want"─────│   (Gervis speaks first)
    │◄── PCM 24kHz ───────────│                               │
    │                         │                               │
    │── PCM 16kHz ───────────►│── LiveRequestQueue ──────────►│
    │                         │                               │── VAD detects speech
    │                         │◄── audio response ────────────│
    │◄── PCM 24kHz ───────────│                               │
    │◄── output_transcript ───│                               │
    │                         │                               │
    │── PCM 16kHz ───────────►│── (tool call triggered)       │
    │                         │── google_search()             │
    │                         │── find_nearby_places()        │
    │                         │── request_clarification()     │
    │◄── state_update JSON ───│   (→ phone shows coding_mode) │
    │                         │── generate_and_confirm_prd()  │
    │◄── state_update JSON ───│   (→ phone shows PRD card)    │
    │── "yes" (spoken) ──────►│── confirm_and_dispatch() ────►│── Job created
    │◄── job_started JSON ────│                               │
    │                         │── Cloud Tasks enqueued        │
    │                         │                               │
    │ [10s silence]           │                               │
    │── end_of_speech JSON ──►│── audioStreamEnd to Gemini    │
    │   (WebSocket closes)    │── graceful disconnect         │


═══════════════════════════════════════════════════════════════════════════════════════
                         BACKGROUND JOB FLOW (NOTIFICATIONS)
═══════════════════════════════════════════════════════════════════════════════════════

  Phone     Backend (Cloud Run)    Cloud Tasks    OpenClaw    Firebase FCM
    │               │                   │              │            │
    │ "build X"     │                   │              │            │
    │──────────────►│                   │              │            │
    │               │── Gemini calls    │              │            │
    │               │   confirm_and_    │              │            │
    │               │   dispatch()      │              │            │
    │               │── create job row  │              │            │
    │◄── job_started│   status=queued   │              │            │
    │    JSON       │── enqueue task ──►│              │            │
    │               │                  │              │            │
    │ (user closes  │                  │── POST /internal           │
    │  the app)     │                  │   /jobs/execute│            │
    │               │◄─────────────────│              │            │
    │               │── update running │── submit to─►│            │
    │               │                  │   OpenClaw   │            │
    │               │                  │              │── coding   │
    │               │                  │              │   executes │
    │               │◄─── callback ────────────────────│            │
    │               │── update completed              │            │
    │               │── create resume_event           │            │
    │               │── send FCM ────────────────────────────────►│
    │◄── notification ──────────────────────────────────────────── │
    │               │                  │              │            │
    │ (user taps)   │                  │              │            │
    │── POST /voice/session/start ─────│              │            │
    │── WS reconnects                  │              │            │
    │               │── inject resume context into Gemini          │
    │◄── Gervis speaks welcome-back message with result            │
    │               │                  │              │            │


═══════════════════════════════════════════════════════════════════════════════════════
                         CODING MODE FLOW (5 STEPS)
═══════════════════════════════════════════════════════════════════════════════════════

  Phone          VoiceAgentViewModel          Backend Tools
    │                    │                         │
    │ "build me a task   │                         │
    │  tracker app"      │                         │
    │──────────────────► │── PCM to Gemini ───────►│
    │                    │                         │── request_clarification("Who is it for?")
    │◄── state_update ───│   (state: coding_mode)  │
    │    coding_mode     │                         │── request_clarification("Real-time needed?")
    │◄── output_         │                         │   (max 3 clarifications)
    │    transcript      │                         │
    │ "yes, for remote   │                         │── generate_and_confirm_prd(
    │  teams"            │                         │     idea, clarifications)
    │                    │                         │   → designer_agent.py generates PRD
    │◄── state_update ───│   (awaiting_            │
    │    + PRD JSON      │    confirmation)         │
    │ [PrdConfirmation   │                         │
    │  Card shown]       │                         │
    │ "yes, go ahead"    │                         │
    │──────────────────► │── confirm_and_dispatch(confirmed=True)
    │◄── job_started ────│                         │── Job row created
    │    JSON            │                         │── Cloud Tasks enqueued
    │                    │                         │── OpenClaw notified


═══════════════════════════════════════════════════════════════════════════════════════
                              GCP SERVICES MAP
═══════════════════════════════════════════════════════════════════════════════════════

  ┌─────────────────────────────────────────────────────────────────────────────┐
  │                        spectalk-488516 (GCP Project)                       │
  │                                                                             │
  │  ┌──────────────────┐    ┌──────────────────┐   ┌───────────────────┐      │
  │  │   Cloud Run      │    │  Artifact Registry│   │  Cloud Build      │      │
  │  │                  │    │                  │   │                   │      │
  │  │  gervis-backend  │◄───│  gervis/         │◄──│  cloudbuild.yaml  │      │
  │  │  us-central1     │    │  gervis-backend  │   │  build→push→      │      │
  │  │  min 1 instance  │    │  :latest         │   │  migrate→deploy   │      │
  │  │  1Gi memory      │    └──────────────────┘   └───────────────────┘      │
  │  │  3600s timeout   │                                                       │
  │  │                  │    ┌──────────────────┐   ┌───────────────────┐      │
  │  │  Service Account:│    │  Secret Manager  │   │  Cloud Tasks      │      │
  │  │  gervis-backend@ │    │                  │   │                   │      │
  │  │  spectalk-...    │───►│  JWT_SECRET      │   │  backend-jobs     │      │
  │  └──────────────────┘    │  DATABASE_URL    │   │  us-central1      │      │
  │           │              │  GEMINI_API_KEY  │   │  max 1800s        │      │
  │           │              │  FIREBASE_SA_JSON│   └───────────────────┘      │
  │           │              │  OPENCLAW_KEY    │                              │
  │           │              │  INTEGRATION_    │   ┌───────────────────┐      │
  │           │              │  ENCRYPTION_KEY  │   │  Cloud Trace      │      │
  │           │              └──────────────────┘   │  OpenTelemetry    │      │
  │           │                                     │  + Opik           │      │
  │           ▼                                     └───────────────────┘      │
  │  ┌──────────────────┐                                                       │
  │  │  Cloud Run Job   │                                                       │
  │  │  gervis-migrate  │                                                       │
  │  │  Alembic         │                                                       │
  │  │  upgrade head    │                                                       │
  │  └──────────────────┘                                                       │
  └─────────────────────────────────────────────────────────────────────────────┘

  External services (not in GCP project):
  ┌──────────────────────┐    ┌──────────────────┐    ┌──────────────────┐    ┌──────────┐
  │  Firebase Auth       │    │  Firebase FCM    │    │  Neon PostgreSQL │    │ OpenClaw │
  │  (Google-managed)    │    │  (Google-managed)│    │  (External SaaS) │    │          │
  │                      │    │                  │    │                  │    │ Remote   │
  │  Email + Google      │    │  Push to Android │    │  Free tier       │    │ coding   │
  │  sign-in             │    │  channel:        │    │  Auto-suspend    │    │ executor │
  │  ID token → JWT      │    │  spectalk_jobs   │    │  ssl=require     │    │ via CLI  │
  └──────────────────────┘    └──────────────────┘    └──────────────────┘    └──────────┘


═══════════════════════════════════════════════════════════════════════════════════════
                              DATA MODEL (ALL TABLES)
═══════════════════════════════════════════════════════════════════════════════════════

  users                    conversations              jobs
  ─────────────────        ─────────────────────      ─────────────────────────
  user_id (uuid) PK        conversation_id (uuid) PK  job_id (uuid) PK
  firebase_uid             user_id → users            conversation_id → convs
  email                    state                      user_id → users
  display_name             (idle/active/              job_type
  push_token  ◄────────    coding_mode/               status
  created_at               awaiting_confirmation/     (queued/running/
  updated_at               running_job/               completed/failed)
                           awaiting_resume)            spoken_completion_summary
                           last_turn_summary           display_completion_summary
                           pending_resume_count        artifacts (jsonb)
                           working_memory (jsonb)

  turns                    resume_events              user_projects
  ─────────────────        ─────────────────────      ─────────────────────
  turn_id (uuid) PK        resume_event_id (uuid) PK  project_id (uuid) PK
  conversation_id          conversation_id → convs    user_id → users
  role (user/assistant)    job_id → jobs              project_name
  text                     event_type                 slug (unique per user)
  event_type               spoken_summary             path / url
  created_at               display_summary            openclaw_context (jsonb)
                           is_acknowledged            created_at

  pending_actions          user_integrations          assets
  ─────────────────        ─────────────────────      ─────────────────────
  pending_action_id PK     integration_id (uuid) PK   asset_id (uuid) PK
  conversation_id          user_id → users            user_id → users
  description              service_name               conversation_id
  confirmation_prompt      encrypted_url              mime_type
  resolved_at              encrypted_token            storage_url
  resolution               created_at                 source / caption
  created_at               updated_at                 created_at
```
