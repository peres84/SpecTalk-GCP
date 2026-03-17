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
**Status: `🔲 NOT STARTED`**

Goal: Full voice session UI connected to a local mock backend (or still directly to Gemini
as a temporary bridge). Wake-word detection, animated listening state, transcript display,
conversation history UI. The backend connection is stubbed or temporary — the real backend
comes in Phase 3.

Reference for all UI and audio components: `samples/gemini-voice-agent/`

### Tasks

- [ ] `AndroidAudioRecorder` — port from `samples/gemini-voice-agent` with AEC, NS, AGC
- [ ] `PcmAudioPlayer` — port from `samples/gemini-voice-agent` with interrupt-aware buffer
- [ ] `HotwordService` — port from sample with these changes:
      - Read wake word from `SharedPreferences` (key: `pref_wake_word`, default `"Hey Gervis"`)
      - Rebuild Vosk grammar at service start using the configured word
      - On wake word detected: play activation sound **before** connecting to backend
      - Test with both default word and a user-configured custom word
      - Test BT headset audio routing (AirPods and Meta Glasses)
- [ ] **Activation sound**
      - Add a short chime audio asset to `app/src/main/res/raw/activation_sound.wav`
      - Play via `SoundPool` or `AudioTrack` on wake word detection, routed to the active audio
        output (BT headset if connected, phone speaker otherwise)
      - Sound must finish playing before mic audio starts streaming to backend
- [ ] `BackendVoiceClient` skeleton
      - WebSocket to backend: `WS /ws/voice/{conversation_id}`
      - JWT in Authorization header on upgrade
      - Binary frame send (PCM 16kHz) and receive (PCM 24kHz)
      - JSON control message parsing (`interrupted`, `input_transcript`, `output_transcript`,
        `state_update`, `job_started`, `job_update`, `error`)
      - Reconnect with exponential backoff
      - **Note:** In this phase the WebSocket endpoint can point to a temporary local mock
        server or the sample's Gemini session — replace with real backend in Phase 3
- [ ] `VoiceAgentViewModel`
      - Replace `GeminiAgentViewModel` logic to use `BackendVoiceClient`
      - Handle all control message types via `StateFlow`
      - Manage microphone start/stop, connection lifecycle
- [ ] **Voice Session screen**
      - Animated listening orb (port from sample)
      - Input transcript display (user speech)
      - Output transcript display (assistant response)
      - Connection status pill
      - Manual disconnect button
      - "Goodbye" detection → auto disconnect
- [ ] **Conversation List screen** (functional)
      - Show conversation items with summary and timestamp
      - Badge for pending resume events (populated once backend exists)
      - Tap to open/resume a conversation
- [ ] **Inactivity auto-disconnect (10 seconds)**
      - In `VoiceAgentViewModel`, maintain a `Job` inactivity timer
      - Timer starts when the voice session becomes active
      - Timer resets on every `input_transcript` or `output_transcript` control message received
      - Timer is paused while `PcmAudioPlayer` has audio in its queue (Gervis is speaking)
      - On timeout (10s of silence from both sides): send `{"type": "end_of_speech"}`, close
        WebSocket, resume `HotwordService`
      - Show a brief UI fade-out animation before disconnecting
- [ ] Bluetooth headset monitoring — restart Vosk on BT connect/disconnect
- [ ] Meta Glasses video preview (optional, port from sample if needed)
- [ ] Notification channel setup for push notifications (FCM, even though no notifications yet)

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
**Status: `🔲 NOT STARTED`**

Goal: The backend becomes the full voice agent. Gemini Live session runs server-side. The audio
WebSocket bridge (`WS /ws/voice/{conversation_id}`) is live. `search_tool` and `maps_tool` work.

### Tasks

#### Backend — Audio Bridge
- [ ] `services/gemini_live_client.py`
      - Connect to Gemini Live using `google-genai` SDK (fetch docs with `/gemini-live-api-dev`)
      - OR use ADK BidiGenerateContent / LiveRequestQueue pattern (fetch ADK docs with
        `/get-api-docs` — prefer ADK-native if available and stable)
      - Handle audio input chunks, audio output chunks, transcription events, `interrupted`
      - Handle session reconnection and context re-injection
      - Configure VAD: `START_SENSITIVITY_LOW`, `END_SENSITIVITY_LOW`, `silenceDurationMs: 320`
      - Model: `gemini-2.5-flash-native-audio-preview-12-2025`
      - Enable `inputAudioTranscription` and `outputAudioTranscription`
- [ ] `services/audio_session_manager.py`
      - Manage per-conversation `GeminiLiveVoiceAgent` instances
      - Handle grace period on phone disconnect (keep Gemini session open 30s)
      - Spawn / resume agent on new phone connection
- [ ] `ws/voice_handler.py`
      - `WS /ws/voice/{conversation_id}` — authenticated by JWT on HTTP upgrade
      - Binary frame forwarding: phone PCM → Gemini (zero-copy, TCP_NODELAY)
      - Binary frame forwarding: Gemini PCM → phone (zero-copy, interrupt-aware queue)
      - JSON control message dispatch to phone
      - Handle `end_of_speech` and `interrupt` control messages from phone
      - Handle `image` control messages — inject into Gemini session
- [ ] `services/conversation_service.py`
      - Persist user turns from Gemini `inputTranscription` events
      - Persist assistant turns from Gemini `outputTranscription` events
      - State machine transitions
      - `working_memory` JSONB updates

#### Backend — Orchestrator Agent
- [ ] `agents/orchestrator.py`
      - ADK root agent with Gervis system instruction and personality
      - Tool list: `search_tool`, `maps_tool`
      - Intercept Gemini tool calls, execute via ADK, inject results back
      - Handle backend-gated states (`awaiting_confirmation`, `awaiting_user_input`)
- [ ] `tools/search_tool.py` — Google Search API, returns `spoken_summary` + structured sources
- [ ] `tools/maps_tool.py` — Google Maps API, returns concise `spoken_summary` + rich display data

#### Android — Connect to Real Backend Voice WebSocket
- [ ] Update `BackendVoiceClient` to point to real Cloud Run WebSocket URL
- [ ] Pass JWT in Authorization header on WebSocket upgrade
- [ ] Remove any temporary direct Gemini connection from Phase 1
- [ ] Test full end-to-end: phone mic → backend → Gemini → backend → phone speaker

### Acceptance Criteria

- Wake word on phone → Gemini session starts on backend → voice conversation works end-to-end
- `search_tool` and `maps_tool` execute on backend, Gemini speaks the result naturally
- Barge-in / interrupted works: phone audio buffer clears immediately
- Turns persist to Cloud SQL in real time
- No Gemini API key exists anywhere on the Android app

### ⏸ Awaiting approval to proceed to Phase 4

---

## Phase 4 — Jobs, Notifications, and Resume Flow
**Status: `🔲 NOT STARTED`**

Goal: Background jobs run via Cloud Tasks. Push notifications via FCM. User can leave, get
notified when work completes, and resume naturally.

### Tasks

- [ ] `services/job_service.py` — create, update, query jobs in DB; enqueue Cloud Tasks
- [ ] `services/notification_service.py` — FCM push via Firebase Admin SDK Messaging API
- [ ] `services/resume_event_service.py` — create, fetch, acknowledge resume events
- [ ] `tools/notification_resume_tool.py` — ADK tool for orchestrator
- [ ] `api/internal/jobs.py` — `POST /internal/jobs/execute` (Cloud Tasks handler, not internet-
      facing)
- [ ] Wire `job_started` / `job_update` control messages to phone WebSocket
- [ ] Wire `state_update` control message for `running_job` / `awaiting_resume` states
- [ ] Android: handle `job_started`, `job_update` control messages → show job status in UI
- [ ] Android: handle FCM push notification → show notification, open conversation on tap
- [ ] Android: on conversation open after notification → backend injects resume context into
      Gemini → Gemini plays welcome-back message
- [ ] Android: `POST /conversations/{id}/ack-resume-event` after resume event presented
- [ ] Conversation list badge count from `pending_resume_count`

### Acceptance Criteria

- A long job (mock or real) runs in background, user closes app
- FCM notification arrives when job completes
- Tapping notification opens the right conversation
- Gemini speaks a natural welcome-back message using resume event data
- Conversation list shows badge

### ⏸ Awaiting approval to proceed to Phase 5

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
- [ ] Phase 3 approved
- [ ] Phase 4 approved
- [ ] Phase 5 approved
- [ ] Phase 6 approved
