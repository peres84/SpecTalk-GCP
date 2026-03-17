# Changelog — gervis-backend

All notable changes to the SpecTalk backend are documented here.
Entries are ordered newest-first within each phase.

---

## Phase 3 — Voice Agent + Gemini Live
> Status: 🔄 In Progress

### [Phase 3.7] — find_nearby_places Optional[float] ADK parse fix
**File:** `tools/maps_tool.py`

ADK's automatic function calling (`_automatic_function_calling_util.py`) cannot parse
`float | None = None` (Union/Optional) parameters. On startup, ADK inspects the function
signature to build the Gemini function declaration. The `latitude` and `longitude` params
triggered a `ValueError`, crashing the agent initialization and closing the WebSocket before
the session started.

Fix: changed both parameters from `float | None = None` to `float = 0.0`. The `0.0` value
acts as a sentinel — the tool skips `ToolConfig.retrieval_config` when either coordinate is
`0.0`. This satisfies ADK's requirement for simple type annotations while preserving the
optional GPS-precision behaviour.

---

### [Phase 3.6] — run_live deprecated API fix
**Files:** `services/gemini_live_client.py`, `ws/voice_handler.py`

ADK 1.1.1 deprecated the `session=` parameter on `runner.run_live()`. The old call
`runner.run_live(session=session, ...)` triggered a `DeprecationWarning` on every voice
connection and will break in a future ADK version.

`start_live_session()` in `GeminiLiveClient` now takes `user_id` and `session_id` directly
and passes them to `runner.run_live(user_id=..., session_id=..., ...)`. The `voice_handler.py`
call site was updated to match — it no longer receives the session object, only the IDs.

---

### [Phase 3.5] — Gemini API key test script
**New file:** `scripts/test_gemini_key.py`

Standalone script that verifies `GEMINI_API_KEY` from `.env` is valid and has access to both
the standard Gemini API and the Gemini Live streaming API. Run with:

```bash
uv run python scripts/test_gemini_key.py
```

Three sequential checks:
1. **Key present** — `GEMINI_API_KEY` is set and is not the placeholder value
2. **Text generation** — calls `gemini-2.5-flash` via `generate_content`; confirms the key
   authenticates and the API responds
3. **Live session** — opens a real `runner.run_live()` bidi session with the native-audio model,
   sends a text turn, waits up to 15s for at least one event back, then closes cleanly

Exits with a clear `✗` message and non-zero code on the first failing check so it works in CI.

---

### [Phase 3.4] — Conversation delete endpoint + null summary fix + WebSocket error logging

**`api/conversations.py`:**

`DELETE /conversations/{conversation_id}` — new endpoint.
Verifies the authenticated user owns the conversation, then deletes all child rows in FK-safe
order before removing the conversation itself:
`resume_events` → `pending_actions` → `turns` → `assets` → `jobs` → `conversation`.
Returns `204 No Content`. No request body needed.

`last_turn_summary: Optional[str] = None` — added explicit `= None` default to
`ConversationSummary.last_turn_summary` (and `working_memory` in `ConversationDetail`,
`text`/`event_type` in `TurnItem`). Pydantic v2 now reliably serializes unset Python `None`
values as JSON `null` instead of omitting them or coercing to the string `"null"`.

**`ws/voice_handler.py` — `_downstream_task` error surfacing:**

The `try/except` block was previously inside the `async for` loop. If Gemini threw an exception
on the very first iteration (e.g. invalid API key, quota exceeded, model not allowlisted), the
error escaped the handler entirely and silently killed the task — producing `connection open` /
`connection closed` in the log with no explanation.

Fix: moved the `try/except` to wrap the entire `async for`. Errors from Gemini are now:
- Logged at `ERROR` level with full `exc_info` stack trace
- Forwarded to the phone as `{"type": "error", "message": "..."}` before the socket closes

Also: after `asyncio.wait`, done tasks are now inspected for stored exceptions and logged, so
any unexpected crash in either the upstream or downstream task appears in the log.

---

### [Phase 3.3] — Turns endpoint added
**New endpoint:** `GET /conversations/{conversation_id}/turns`

Added to `api/conversations.py` to support the Android Conversation History screen. Returns the
ordered turn history for a conversation the authenticated user owns.

**Response shape:**
```json
[
  {
    "turn_id": "uuid",
    "role": "user",
    "text": "What's the weather like?",
    "event_type": "voice_transcript",
    "created_at": "2026-03-17T14:23:01.123456"
  }
]
```

**Query params:** `?limit=100&offset=0` (max 500 per page). Turns are ordered oldest-first.
Returns `404` if the conversation does not exist or belongs to a different user.

---

### [Phase 3.2] — RunConfig compatibility fix
**Problem:** `google-adk 1.1.1` uses a strict Pydantic model for `RunConfig` that rejects unknown
fields. Passing `session_resumption` and `realtime_input_config` caused a `ValidationError` on
every WebSocket connection attempt, preventing any voice session from starting.

**Fix (`services/gemini_live_client.py`):**
`_build_run_config()` now inspects `RunConfig.model_fields` at runtime before building the kwargs
dict. Fields only present in newer ADK versions (`session_resumption`, `realtime_input_config`) are
included conditionally — if the installed version declares them, they are added; otherwise they are
silently skipped. This makes the config forward-compatible: upgrading ADK automatically activates
VAD tuning and session resumption without code changes.

**Fields supported in `google-adk 1.1.1`:**
- `streaming_mode` — set to `"bidi"` (bidirectional)
- `response_modalities` — `["AUDIO"]`
- `input_audio_transcription` — `AudioTranscriptionConfig()`
- `output_audio_transcription` — `AudioTranscriptionConfig()`

**Fields activated on upgrade (not yet available in 1.1.1):**
- `session_resumption` — `SessionResumptionConfig(transparent=True)`
- `realtime_input_config` — VAD with `START_SENSITIVITY_LOW`, `END_SENSITIVITY_LOW`,
  `silence_duration_ms=320`

---

### [Phase 3.1] — Turns endpoint added
**New endpoint:** `GET /conversations/{conversation_id}/turns`

Added to `api/conversations.py` to support the Android Conversation History screen. Returns the
ordered turn history for a conversation the authenticated user owns.

**Response shape:**
```json
[
  {
    "turn_id": "uuid",
    "role": "user",
    "text": "What's the weather like?",
    "event_type": "voice_transcript",
    "created_at": "2026-03-17T14:23:01.123456"
  }
]
```

**Query params:** `?limit=100&offset=0` (max 500 per page). Turns are ordered oldest-first.
Returns `404` if the conversation does not exist or belongs to a different user.

---

### [Phase 3.0] — Voice agent implementation
**New packages added (`pyproject.toml`):**
- `google-adk>=1.0.0` — ADK Runner, Agent, LiveRequestQueue, InMemoryRunner
- `deprecated>=1.3.1` — required transitive dependency missing from ADK's metadata
- `uvicorn[standard]>=0.34.0` — upgraded from `0.32.0` (ADK requires `>=0.34.0`)

**New files:**

`agents/orchestrator.py`
Defines the Gervis ADK `Agent` via `create_gervis_agent(model)` factory. Wires the Gervis
system instruction (voice UX rules, persona, purpose) and two tools: `google_search` and
`find_nearby_places`.

`tools/search_tool.py`
Re-exports ADK's built-in `google_search` tool, which uses Gemini's native Google Search
grounding. No external Search API key required — only the Gemini API key.
Docs: https://ai.google.dev/gemini-api/docs/google-search

`tools/maps_tool.py`
Defines `find_nearby_places(query, location, latitude?, longitude?)` as an async function tool.
Uses Gemini's native Google Maps grounding (`types.Tool(google_maps=types.GoogleMaps())`).
No separate Maps API key required — only the Gemini API key. Accepts optional `lat_lng` via
`RetrievalConfig` for GPS-precise results. Runs the sync Gemini call in `run_in_executor` to
stay non-blocking.
Docs: https://ai.google.dev/gemini-api/docs/maps-grounding
Pricing: $25 per 1,000 grounded prompts. Free tier: 500 requests/day.

`services/gemini_live_client.py`
Singleton (`gemini_live_client`) wrapping an ADK `InMemoryRunner`. Initialized once in the
FastAPI lifespan via `gemini_live_client.initialize()`. Exposes:
- `get_or_create_session(user_id, session_id)` — fetches or creates an ADK session keyed by
  `conversation_id`
- `start_live_session(session, live_request_queue)` — returns the `run_live()` async generator
- `new_request_queue()` — creates a fresh `LiveRequestQueue` per connection

`services/audio_session_manager.py`
Manages per-conversation `SessionEntry` objects. On phone disconnect, starts a 30-second grace
timer; if the phone reconnects within the window the timer is cancelled and the same ADK session
is reused. On grace expiry the entry is removed and the next connection starts fresh. Lock-safe
for concurrent reconnect races.

`services/conversation_service.py`
Three async DB helpers used by the WebSocket downstream task:
- `persist_turn(conversation_id, role, text, event_type)` — writes a `Turn` row on every final
  transcription event (non-partial)
- `set_conversation_active(conversation_id)` — sets `state = "active"` on WebSocket connect
- `set_conversation_idle(conversation_id)` — sets `state = "idle"` on WebSocket disconnect

`ws/voice_handler.py`
FastAPI `APIRouter` exposing `WS /ws/voice/{conversation_id}`.

Authentication: JWT accepted as `?token=<jwt>` query param or `Authorization: Bearer` header.
Invalid/missing token closes the socket immediately with code `4001`.

Upstream task (`phone → ADK`):
- Binary frames forwarded as `audio/pcm;rate=16000` blobs via `send_realtime()`
- `{"type": "end_of_speech"}` → sends `types.ActivityEnd()` (falls back gracefully if not
  available in the installed SDK version)
- `{"type": "image", ...}` → decodes base64 and injects as a blob into the ADK session

Downstream task (`ADK → phone`):
- **`interrupted` events are forwarded to the phone FIRST, before any further audio.** This is
  the critical correctness requirement for barge-in UX — the phone clears its audio buffer
  immediately on receipt.
- Audio `inline_data` parts → forwarded as raw binary frames (zero-copy)
- `input_transcript` / `output_transcript` → forwarded as JSON; final turns persisted to DB
- `turn_complete` → forwarded as `{"type": "turn_complete"}`

On disconnect: `live_request_queue.close()` is called, grace timer starts, conversation set idle.

**Modified files:**

`config.py` — added:
- `gemini_api_key: str` — single key for Gemini Live, Search grounding, Maps grounding
- `adk_app_name: str` — default `"spectalk"`
- `gemini_model: str` — default `"gemini-2.5-flash-native-audio-preview-12-2025"`
- Removed `google_maps_api_key` (not needed — Maps grounding uses only the Gemini key)

`main.py` — added:
- `os.environ.setdefault("GOOGLE_API_KEY", settings.gemini_api_key)` in lifespan (ADK picks
  this up automatically)
- `gemini_live_client.initialize()` call in lifespan
- `ws_voice_router` mounted at prefix `/ws`

---

## Phase 2 — Backend Foundation
> Status: ✅ Approved

**Delivered:**
- FastAPI app with lifespan DB pool init, CORS middleware, router registration
- SQLAlchemy async engine + `AsyncSessionLocal` session factory
- Full ORM models: `User`, `Conversation`, `Turn`, `Job`, `PendingAction`, `ResumeEvent`, `Asset`
- Alembic migrations: initial schema + full schema (all 7 tables on Neon PostgreSQL)
- Firebase Admin SDK init with ADC (`gcloud auth application-default login`)
- `POST /auth/session` — verifies Firebase ID token, upserts user row, returns product JWT
- `GET /conversations` — list user's conversations
- `GET /conversations/{id}` — single conversation detail
- `POST /voice/session/start` — creates conversation row, returns `conversation_id`
- `GET /jobs/{job_id}` — job status stub
- `POST /notifications/device/register` — stores FCM push token on user row
- JWT middleware (`require_auth` dependency) — validates Bearer JWT on all protected routes
- Production-ready `Dockerfile` (uv-based, python 3.12-slim)
- `cloudbuild.yaml` — Cloud Build CI/CD pipeline (build → push → migrate → deploy)

**Database:** Neon PostgreSQL (development). Cloud SQL PostgreSQL (production — deferred).

---

## Phase 0 / Phase 1 — Android Only
> Backend: no changes in these phases.

Phase 0 delivered Firebase Authentication (email/password + Google Sign-In) on Android.
Phase 1 delivered the voice session UI, `BackendVoiceClient`, `HotwordService`, audio components,
and the Conversation List screen on Android. Backend remained at Phase 2 state throughout.
