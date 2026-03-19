# SpecTalk — Delivery Plan

**Product**: SpecTalk — voice-powered project creation for Meta wearables
**Assistant**: Gervis (the AI persona inside SpecTalk; wake word "Hey Gervis")

Phased delivery plan. Each phase ends with an explicit approval checkpoint before the next
phase begins. Update task status as work progresses. Mark a phase `✅ APPROVED` when the user
confirms it is complete and gives the go-ahead to proceed.

Reference: [`architecture.md`](./architecture.md) — source of truth for all design decisions.

---

## Phase 0 — Android App: Authentication UI
**Status: `✅ APPROVED`**

Goal: A polished **SpecTalk** Android app with registration, login, and a placeholder home
screen. No backend yet — Firebase Authentication runs client-side only. This phase proves the
UI foundation and design language before any backend work begins.

Reference for UI patterns: `samples/gemini-voice-agent/`

### Tasks

- [x] Create new Android app module (`android/` — Kotlin, Jetpack Compose, Material3)
- [x] Add Firebase Authentication dependency (`firebase-auth`, Firebase BOM 34.10.0)
- [x] Add Google Services plugin (`4.4.4`) and `google-services.json`
- [x] Design and implement **Splash / Onboarding screen**
      - Gervis mascot icon + "SpecTalk" wordmark + "Powered by Gervis" gold label
      - Auto-routes to Home (authenticated) or Login (unauthenticated)
      - Fade-in animation
- [x] Design and implement **Registration screen**
      - Email + password + confirm password fields
      - "Create Account" button + "Continue with Google" button
      - Error states (invalid email, weak password, email already in use)
      - Link to login screen
      - Email verification sent confirmation state
- [x] Design and implement **Login screen**
      - Email + password fields
      - "Sign in" button + "Continue with Google" button
      - "Forgot password?" dialog → Firebase password reset flow
      - Error states (wrong credentials, unverified email)
      - Link to registration screen
- [x] Firebase Auth integration
      - `createUserWithEmailAndPassword`
      - `sendEmailVerification` after registration
      - `signInWithEmailAndPassword`
      - `sendPasswordResetEmail`
      - Persist auth state across app restarts (Firebase SDK handles this automatically)
      - Sign out
- [x] **Google Sign-In** (via Jetpack Credential Manager + Firebase)
      - SHA-1 fingerprint registered in Firebase Console
      - Web Client ID configured in `res/values/strings.xml`
- [x] Design and implement **Home / Conversation List screen** (empty state only)
      - TopAppBar with settings icon and sign-out button
      - Empty state copy ("No conversations yet — Say Hey Gervis or tap +")
      - FAB placeholder for future voice session
- [x] Navigation graph wiring (Splash → Login/Register → Home → Settings)
- [ ] Handle deep link from email verification back into the app
- [x] Branding: Metallic red + gold color system, dark/light Material3 theme, warm surfaces
- [x] **Settings screen**
      - Wake word field: text input, default `"Hey Gervis"`
      - Persisted to `SharedPreferences` (key: `pref_wake_word`)
      - Accessible from the Home screen top bar

### Acceptance Criteria

- [x] User can register with email and password
- [x] User receives a verification email and must verify before accessing Home
- [x] User can log in and is taken to the Home screen
- [x] User can sign in with Google (one tap)
- [x] User can reset their password via email
- [x] Auth state persists across app restarts (already logged in = goes straight to Home)
- [x] UI is polished and consistent — metallic red/gold brand, dark/light mode

### Notes
- `android/docs/branding.md` — full color palette, mascot, icon, typography guide
- `android/docs/authentication.md` — Firebase setup, Google Sign-In, SHA-1 instructions

---

## Phase 1 — Android App: Voice Session UI
**Status: `🔄 IN PROGRESS`**

Goal: Full voice session UI connected to a local mock backend (or still directly to Gemini
as a temporary bridge). Wake-word detection, animated listening state, transcript display,
conversation history UI. The backend connection is stubbed or temporary — the real backend
comes in Phase 3.

Reference for all UI and audio components: `samples/gemini-voice-agent/`

### Tasks

- [x] `AndroidAudioRecorder` — port from `samples/gemini-voice-agent` with AEC, NS, AGC
- [x] `PcmAudioPlayer` — port from `samples/gemini-voice-agent` with interrupt-aware buffer
- [x] `HotwordService` — port from sample with these changes:
      - Read wake word from `SharedPreferences` (key: `pref_wake_word`, default `"Hey Gervis"`)
      - Rebuild Vosk grammar at service start using the configured word
      - On wake word detected: play activation sound **before** connecting to backend
      - Test with both default word and a user-configured custom word
      - Test BT headset audio routing (AirPods and Meta Glasses)
- [x] **Activation sound**
      - Add a short chime audio asset to `app/src/main/res/raw/activation_sound.wav`
      - Play via `SoundPool` or `AudioTrack` on wake word detection, routed to the active audio
        output (BT headset if connected, phone speaker otherwise)
      - Sound must finish playing before mic audio starts streaming to backend
- [x] `BackendVoiceClient` skeleton
      - WebSocket to backend: `WS /ws/voice/{conversation_id}`
      - JWT in Authorization header on upgrade
      - Binary frame send (PCM 16kHz) and receive (PCM 24kHz)
      - JSON control message parsing (`interrupted`, `input_transcript`, `output_transcript`,
        `state_update`, `job_started`, `job_update`, `error`)
      - **Note:** In this phase the WebSocket endpoint points to local emulator (`ws://10.0.2.2:8080`)
        via `BackendConfig` — replace with real Cloud Run URL in Phase 3
- [x] `VoiceAgentViewModel`
      - Uses `BackendVoiceClient` (reads URL from `BackendConfig`, JWT from `TokenRepository`)
      - Handles all control message types via `StateFlow`
      - Manages microphone start/stop, connection lifecycle
- [x] **Voice Session screen**
      - Animated listening orb (ported from sample)
      - Input transcript display (user speech)
      - Output transcript display (assistant response)
      - Connection status pill
      - Manual disconnect button
      - "Goodbye" detection → auto disconnect
- [x] **Conversation List screen** (functional)
      - Show conversation items with summary and timestamp
      - Badge for pending resume events
      - Tap to open/resume a conversation (passes conversationId to VoiceSessionScreen)
      - Pull-to-refresh, empty state, relative timestamps
      - State chip (active / resume / working / idle)
- [x] **Inactivity auto-disconnect (10 seconds)**
      - In `VoiceAgentViewModel`, maintains a `Job` inactivity timer
      - Timer starts when the voice session becomes active
      - Timer resets on every `input_transcript` or `output_transcript` control message received
      - Timer is paused while `PcmAudioPlayer` has audio in its queue (Gervis is speaking)
      - On timeout (10s of silence from both sides): sends `{"type": "end_of_speech"}`, closes
        WebSocket, resumes `HotwordService`
- [x] Bluetooth headset monitoring — restart Vosk on BT connect/disconnect
- [ ] Meta Glasses video preview (optional, port from sample if needed)
- [x] Notification channel setup for push notifications (FCM — `FcmService` + two channels created
      in `SpecTalkApplication.onCreate()`)

### Acceptance Criteria

- Wake word activates the voice session
- Audio streams correctly over the WebSocket bridge
- Transcripts display in real time
- Barge-in / interrupted event clears audio buffer and UI correctly
- BT headset (AirPods or Meta glasses audio) routes correctly
- App handles disconnect and reconnects cleanly

### ⏸ Awaiting approval to proceed to Phase 2

---

## Phase 2 — Backend: Foundation
**Status: `✅ APPROVED`**

Goal: A deployable Python backend on Google Cloud with authentication, database, and all
infrastructure in place. No Gemini yet — just the foundation.

### Tasks

#### Infrastructure Setup
- [ ] Create Google Cloud project — deferred, deploy before Phase 3 go-live
- [ ] Enable APIs: Cloud Run, Cloud SQL Admin, Secret Manager, Cloud Build, Artifact Registry,
      Firebase, Cloud Tasks, Cloud Storage — deferred
- [ ] Create Cloud SQL PostgreSQL instance — deferred (using Neon PostgreSQL for dev ✅)
- [ ] Create Cloud Storage artifacts bucket — deferred
- [ ] Create Cloud Tasks queue (`backend-jobs`) — deferred
- [x] Configure Firebase project: enable Email/Password + Google sign-in, add Android app
- [ ] Add all secrets to Secret Manager — deferred
- [ ] Create Cloud Run service account with correct IAM roles (see `architecture.md`) — deferred
- [ ] Create Artifact Registry repository for Docker images — deferred
- [x] Create `gervis-backend/` directory in repo

#### Backend Code
- [x] `main.py` — FastAPI app, lifespan (DB pool init), router registration, CORS
- [x] `config.py` — Pydantic settings, reads from `.env`
- [x] `db/database.py` — SQLAlchemy async engine, session factory
- [x] `db/models.py` — all ORM models (User, Conversation, Turn, Job, PendingAction, ResumeEvent, Asset)
- [x] `db/migrations/` — Alembic setup, full schema migration (users + all tables)
- [x] `auth/firebase.py` — Firebase Admin SDK init, `verify_firebase_token(id_token)`
- [x] `auth/jwt_handler.py` — `sign_product_jwt`, `verify_product_jwt` (python-jose)
- [x] `api/auth.py` — `POST /auth/session`: verify Firebase token, upsert user, return JWT
- [x] `api/voice.py` — `POST /voice/session/start` stub
- [x] `api/conversations.py` — conversation CRUD endpoints
- [x] `api/jobs.py` — `GET /jobs/{job_id}`
- [x] `api/notifications.py` — `POST /notifications/device/register`
- [x] `middleware/auth.py` — `require_auth` dependency, validates Bearer JWT on protected routes
- [x] `Dockerfile` — production-ready image (uv-based, python 3.12-slim)
- [x] Cloud Build `cloudbuild.yaml` — build → push → migrate → deploy pipeline
- [x] `pyproject.toml` — dependencies managed with `uv`

#### Android: Wire Auth to Real Backend
- [x] Update `POST /auth/session` call in Android app to hit real Cloud Run URL
- [x] Store product JWT securely (EncryptedSharedPreferences)
- [x] Use product JWT on all API calls

### Acceptance Criteria

- [x] User can register in the app, Firebase creates the account, `POST /auth/session` creates a
      user row in Neon PostgreSQL, and the app receives a product JWT
- [x] All REST endpoints return correct responses (tested via `scripts/test_auth_session.py`)
- [ ] Cloud Run deployed and accessible from Android app — deferred to pre-Phase 3
- [x] Database migrations run cleanly (`alembic upgrade head` — full schema on Neon)
- [x] No credentials in source code or Docker images

### ✅ Approved — proceeding to Phase 3

---

## Phase 3 — Backend: Voice Agent + Gemini Live
**Status: `✅ APPROVED`**

Goal: The backend becomes the full voice agent. Gemini Live session runs server-side. The audio
WebSocket bridge (`WS /ws/voice/{conversation_id}`) is live. `search_tool` and `maps_tool` work.

### Tasks

#### Backend — Audio Bridge
- [x] `services/gemini_live_client.py`
      - ADK-native LiveRequestQueue + InMemoryRunner pattern
      - Model: `gemini-2.5-flash-native-audio-preview-12-2025`
      - VAD: START_SENSITIVITY_LOW, END_SENSITIVITY_LOW, silenceDurationMs: 320 (activates on ADK upgrade)
      - inputAudioTranscription + outputAudioTranscription enabled
      - Session resumption with transparent=True (activates on ADK upgrade)
      - RunConfig fields injected conditionally — forward-compatible with future ADK versions
      - Deprecated `run_live(session=)` API replaced with `run_live(user_id=, session_id=)`
- [x] `services/audio_session_manager.py`
      - Per-conversation SessionEntry tracking
      - 30s grace period on phone disconnect (cancels on reconnect)
      - Lock-safe async operations
- [x] `ws/voice_handler.py`
      - `WS /ws/voice/{conversation_id}` — JWT auth via query param or Authorization header
      - Binary PCM forwarding: phone → ADK (zero-copy via send_realtime)
      - Binary PCM forwarding: ADK audio events → phone (zero-copy)
      - `interrupted` forwarded to phone BEFORE any audio (critical for barge-in UX)
      - Handles `image` control messages from phone
      - `turn_complete`, `input_transcript`, `output_transcript` JSON events to phone
      - Gemini errors surfaced as `{"type":"error","message":"..."}` before socket closes
      - Task exceptions after `asyncio.wait` logged at ERROR level with full stack trace
- [x] `services/conversation_service.py`
      - `persist_turn()` — persists user/assistant turns from transcription events
      - `set_conversation_active()` / `set_conversation_idle()` — state transitions

#### Backend — Orchestrator Agent
- [x] `agents/orchestrator.py`
      - ADK Agent (Gervis) with full personality + voice UX system instruction
      - Tools: google_search (ADK built-in native grounding), find_nearby_places (Maps grounding)
- [x] `tools/search_tool.py` — ADK built-in google_search (native Search grounding, no separate key)
- [x] `tools/maps_tool.py` — native Maps grounding via `types.GoogleMaps()`, returns spoken_summary

#### Backend — Conversations API (added during Phase 3)
- [x] `GET /conversations/{id}/turns` — paginated turn history (`?limit=100&offset=0`)
- [x] `DELETE /conversations/{id}` — FK-safe cascade delete (resume_events → pending_actions → turns → assets → jobs → conversation)
- [x] `last_turn_summary` null serialization fix — explicit `Optional[str] = None` defaults in Pydantic models

#### Backend — Bug Fixes (ADK 1.1.1 compatibility)
- [x] `RunConfig` Pydantic validation — unknown fields (`session_resumption`, `realtime_input_config`) injected conditionally via `model_fields` inspection
- [x] `types.ActivityEnd()` crash — removed `send_realtime(ActivityEnd())` call; ADK 1.1.1 only accepts `types.Blob` in `send_realtime`; VAD handles silence detection
- [x] `find_nearby_places` schema warnings — removed `latitude`/`longitude` params; ADK cannot represent default values in Gemini function schemas
- [x] `Optional[float]` parse error — ADK automatic function calling cannot parse Union types

#### Setup
- [x] `GEMINI_API_KEY` added to `gervis-backend/.env` — validated via `scripts/test_gemini_key.py`
- [x] `GOOGLE_MAPS_API_KEY` not required — Maps grounding uses only `GEMINI_API_KEY`

#### Tooling / Docs
- [x] `scripts/test_gemini_key.py` — 3-check validation: key present → text generation → live bidi session
- [x] `docs/phase3-testing.md` — step-by-step testing checklist for Phase 3 approval
- [x] `CHANGELOG.md` — full change history for all Phase 3 work

#### Android — Connect to Real Backend Voice WebSocket
- [x] `BackendVoiceClient` JWT auth — token passed via Authorization header on WebSocket upgrade
- [x] `BackendConfig.wsBaseUrl` pointing to local backend for testing
- [x] End-to-end voice session working: phone mic → backend → ADK/Gemini → backend → phone speaker
- [ ] Set `BackendConfig.wsBaseUrl` to Cloud Run URL before production deploy
- [ ] Remove any temporary direct Gemini connection from Phase 1 (if present)

### Acceptance Criteria

- [x] Wake word on phone → Gemini session starts on backend → voice conversation works end-to-end
- [X] `search_tool` executes on backend — Gemini speaks a grounded search result naturally
- [x] `maps_tool` executes on backend — Gemini speaks a grounded maps result naturally
- [x] Barge-in / interrupted works: phone audio buffer clears immediately
- [x] Turns persist to PostgreSQL in real time (visible in DB after a conversation)
- [x] No Gemini API key exists anywhere on the Android app

### ⏸ Awaiting approval to proceed to Phase 4

---

## Phase 4 — Jobs, Notifications, and Resume Flow
**Status: `🔄 IN PROGRESS — Cloud Tasks working, resume flow working, FCM push token debug pending`**

Goal: Background jobs run via Cloud Tasks. Push notifications via FCM. User can leave, get
notified when work completes, and resume naturally.

### Backend Tasks

- [x] `services/control_channels.py` — per-connection WebSocket control message registry
- [x] `services/job_service.py` — create, update, query jobs in DB; enqueue Cloud Tasks
- [x] `services/notification_service.py` — FCM push via Firebase Admin SDK Messaging API
- [x] `services/resume_event_service.py` — create, fetch, acknowledge resume events
- [x] `tools/notification_resume_tool.py` — `start_background_job` ADK tool for orchestrator
- [x] `api/internal/jobs.py` — `POST /internal/jobs/execute` (Cloud Tasks handler)
- [x] Wire `job_started` / `job_update` control messages to phone WebSocket via `control_channels`
- [x] Resume context injection into Gemini on WebSocket reconnect (welcome-back message)
- [x] `POST /conversations/{id}/ack-resume-event` endpoint
- [x] Replace Opik with Google Cloud Trace (`services/tracing.py` rewrite)
- [x] `docs/phase4-deployment.md` — full GCP setup + Android changes guide (PowerShell)
- [x] `docs/shutdown-guide.md` — how to stop all GCP services
- [x] `config.py` — asyncpg SSL URL validator (strips sslmode/channel_binding, injects `?ssl=require`)
- [x] `db/database.py` — `pool_pre_ping=True` for Neon auto-suspend stale connection recovery
- [x] `agents/orchestrator.py` — stronger background job prompt (Gemini now calls `start_background_job` for all research requests)
- [x] `services/job_service.py` — removed OIDC token from Cloud Task (no longer needed; endpoint is public, protected by X-CloudTasks-QueueName header)
- [x] `api/conversations.py` — FK-safe DELETE (also deletes resume_events by job_id before deleting jobs)
- [x] `services/job_service.py` — dispatch deadline fixed (3600s → 1800s, Cloud Tasks max is 30 min)
- [x] `BACKEND_BASE_URL` secret — fixed Windows `\r\r` carriage return corruption (was causing 400 Invalid URL on task enqueue)
- [x] `main.py` — removed duplicate `GEMINI_API_KEY` env var (was causing genai SDK to init two connections → double audio response)
- [x] `api/internal/jobs.py` — added push_token null logging to diagnose silent FCM skip

### Cloud Run Deployment

- [x] GCP project `spectalk-488516` created, all APIs enabled
- [x] Service account `gervis-backend@spectalk-488516.iam.gserviceaccount.com` created with all IAM roles
- [x] All secrets added to Secret Manager (`JWT_SECRET`, `DATABASE_URL`, `GEMINI_API_KEY`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `BACKEND_BASE_URL`)
- [x] Cloud Tasks queue `backend-jobs` created (us-central1)
- [x] `gervis-migrate` Cloud Run Job created (Alembic migrations)
- [x] Backend deployed to Cloud Run (`gervis-backend` service, us-central1, min 0 instances, 1Gi)
- [x] `BACKEND_BASE_URL` secret filled in with Cloud Run URL
- [x] Android app `backend_base_url` updated to Cloud Run HTTPS URL
- [ ] Deploy latest fixes (FCM push_token logging + GEMINI_API_KEY removal) — pending `gcloud builds submit`

### Android Tasks (send to frontend agent)

- [x] `strings.xml` — `backend_base_url` updated to Cloud Run HTTPS URL
- [x] `TokenRepository` — proactive FCM push token registration on every login (fixes token never reaching backend on existing installs)
- [x] `VoiceAgentViewModel` — gate mic chunks on `hasPendingAudio` to fix echo (Gervis hearing its own voice)
- [ ] `BackendVoiceClient` — handle `job_started` control message → show job indicator
- [ ] `BackendVoiceClient` — handle `job_update` control message → update/dismiss indicator
- [ ] FCM notification handler — tap opens matching conversation
- [ ] On conversation open after notification → connect WebSocket → Gemini speaks welcome-back
- [ ] Call `POST /conversations/{id}/ack-resume-event` after welcome-back message plays
- [ ] Conversation list — show badge from `pending_resume_count`

### Acceptance Criteria

- [x] Say "research X" → Gemini calls `start_background_job` → spoken ack → job row in DB
- [x] Cloud Tasks automatically calls `/internal/jobs/execute` → job completes ✅
- [x] FCM notification arrives on device (confirmed via manual curl)
- [x] Gemini speaks welcome-back message on reconnect using resume event data ✅
- [ ] FCM notification arrives automatically after Cloud Tasks job (push_token null — debug pending)
- [ ] Tapping notification opens the right conversation (Android not yet wired)
- [ ] Conversation list shows badge, clears after `ack-resume-event`
- [ ] Cross-session conversation history (agent starts fresh each reconnect — Phase 6)

### Known limitations
- Agent has no memory across sessions (InMemoryRunner resets on Cloud Run restart/scale-to-zero)
- FCM auto-notification not yet confirmed end-to-end (push_token lookup returning null in Cloud Task context)

### ⏸ Awaiting FCM end-to-end + Android notification wiring before approval to proceed to Phase 5

---

## Phase 5 — Coding Mode and OpenClaw Integration
**Status: `🔲 NOT STARTED`**

Goal: User can describe a coding project by voice. The backend shapes the requirements, asks
clarifying questions, requests confirmation, and dispatches to OpenClaw as a background job.

### Tasks

- [ ] `agents/team_code_pr_designers/` — four subagents (frontend, backend, security,
      architecture) that collaboratively shape a PRD
- [ ] `tools/openclaw_coding_tool.py` — submit approved PRD to OpenClaw, poll status, collect
      artifacts
- [ ] Orchestrator: detect coding intent, transition to `coding_mode` state
- [ ] Orchestrator: activate `team_code_pr_designers`, ask one clarifying question at a time
- [ ] Orchestrator: produce PRD summary, request confirmation via `confirm_action` state
- [ ] Confirmation flow: intercept "yes/no" transcript, resolve `pending_action`, dispatch job
- [ ] Android: show `awaiting_confirmation` UI state (confirmation prompt, yes/no visual)
- [ ] Android: `POST /conversations/{id}/pending-turn` for returning users in gated state
- [ ] Test full coding mode lifecycle: voice request → questions → PRD → confirm → job → notify →
      resume

### Acceptance Criteria

- User says "build me a landing page for my startup" by voice
- Gervis asks clarifying questions one at a time
- Gervis presents a PRD summary and asks for confirmation
- User confirms → job dispatches to OpenClaw → notification on complete → resume with result

### ⏸ Awaiting approval to proceed to Phase 6

---

## Phase 6 — Advanced Features
**Status: `🔲 NOT STARTED`**

Goal: 3D model workflow, long-term memory, artifact browser in app, richer multimodal handling.

### Tasks

- [ ] `tools/three_d_model_tool.py` — validate 4-image set, submit job, return artifact refs
- [ ] `tools/memory_tool.py` — save/retrieve durable user memory, session summarization
- [ ] `tools/openclaw_assistant_tool.py` (optional) — lightweight OpenClaw assistance
- [ ] Android: `image` control message sending from Meta Glasses frames and phone camera
- [ ] Android: `asset_saved` control message handling → show asset confirmation in UI
- [ ] Android: artifact browser screen — list job artifacts, open/share them
- [ ] Backend: session summarization into long-term memory on conversation close
- [ ] Backend: inject relevant long-term memory into Gemini context at session start
- [ ] Migrate Gemini from AI Studio API key to Vertex AI (service account auth)

### Acceptance Criteria

- User sends 4 images, 3D model job runs, notification on complete, artifact viewable in app
- Gervis remembers user preferences across separate conversations
- Artifacts from coding jobs are browsable in the app

### ⏸ Phase 6 is the final planned phase

---

## Completion Checklist

- [x] Phase 0 approved
- [ ] Phase 1 approved
- [x] Phase 2 approved
- [x] Phase 3 approved
- [ ] Phase 4 approved
- [ ] Phase 5 approved
- [ ] Phase 6 approved
