# Changelog - gervis-backend

All notable changes to the SpecTalk backend are documented here.
Entries are ordered newest-first within each phase.

---

## Phase 4 - Jobs, Notifications, and Resume Flow
> Status: **Deployed** — running on Cloud Run (spectalk-488516, us-central1)

### [Phase 4.3] - Cloud Run deployment fixes
**Files:** `cloudbuild.yaml`, `config.py`, `docs/phase4-deployment.md`

Several issues surfaced during the first Cloud Run deployment. All fixed.

**`cloudbuild.yaml` — migration step split:**

`gcloud run jobs execute` does not accept `--image`. The migration step was split into two:
1. `gcloud run jobs update gervis-migrate --image=...` — updates the job to the new image
2. `gcloud run jobs execute gervis-migrate --wait` — runs it

The `gervis-migrate` Cloud Run Job must be pre-created once (see deployment guide Step 8)
before Cloud Build can update and execute it.

**`cloudbuild.yaml` — `_SERVICE_ACCOUNT` substitution removed:**

Passing a service account email containing `@` via `--substitutions` breaks gcloud's
substitution parser — the `@` caused `_IMAGE_TAG=latest` to be appended to the SA email.
Fix: hardcoded `gervis-backend@${PROJECT_ID}.iam.gserviceaccount.com` directly in the YAML
deploy step using the built-in `${PROJECT_ID}` variable. `_SERVICE_ACCOUNT` removed from
the `substitutions` block entirely.

**`cloudbuild.yaml` — memory limit increased:**

Default Cloud Run memory is 512 MiB. The backend needs ~550 MiB at startup (ADK + FastAPI +
all dependencies). Added `--memory=1Gi` to the deploy step. Without this the container OOMs
on every start and Cloud Run never marks the revision healthy.

**`cloudbuild.yaml` — `--allow-unauthenticated`:**

Changed from `--no-allow-unauthenticated` to `--allow-unauthenticated`. The `--no-allow-unauthenticated`
flag requires a Google OIDC token on every request, blocking Android clients entirely (they
send product JWTs, not OIDC tokens). Authentication is handled at the application layer by
the JWT middleware. The `/internal/jobs/execute` endpoint is protected by the
`X-CloudTasks-QueueName` header check in `environment=production`.

**`config.py` — asyncpg SSL URL sanitization:**

Neon PostgreSQL connection strings use psycopg2-style query params: `?sslmode=require&channel_binding=require`.
asyncpg does not accept these parameters and crashes with `TypeError: connect() got an
unexpected keyword argument 'sslmode'` (and then `'channel_binding'` after fixing sslmode).
Added a `@field_validator("database_url")` that strips all query params and replaces them
with `?ssl=require` when any SSL mode was requested. This runs at settings load time so both
the app and the Alembic migration job get a clean URL.

**`docs/phase4-deployment.md` — rewritten with PowerShell instructions:**

The deployment guide was rewritten with step-by-step PowerShell commands matching exactly
what worked. Includes: variable setup, API enablement, SA creation, Cloud Build SA IAM
(granting to the Compute SA, not the Cloud Build SA), secret creation notes, migration job
pre-creation, troubleshooting section for every error encountered during deployment.

---

### [Phase 4.2] - Cloud Build IAM for Compute SA
**Context:** Cloud Build permissions

Modern GCP projects use the **Compute Engine default SA**
(`PROJECT_NUMBER-compute@developer.gserviceaccount.com`) as the Cloud Build identity, not
`PROJECT_NUMBER@cloudbuild.gserviceaccount.com`. Granting `roles/run.admin` and
`roles/iam.serviceAccountUser` to the wrong SA causes `PERMISSION_DENIED: Permission
'run.jobs.get' denied` on every migration step.

Fix: grant the roles to the Compute SA. The project number (not project ID) is needed:

```powershell
$env:PROJECT_NUMBER = gcloud projects describe $env:PROJECT_ID --format="value(projectNumber)"
$env:CB_SA = "$env:PROJECT_NUMBER-compute@developer.gserviceaccount.com"
gcloud projects add-iam-policy-binding $env:PROJECT_ID --member="serviceAccount:$env:CB_SA" --role="roles/run.admin"
gcloud iam service-accounts add-iam-policy-binding "gervis-backend@$env:PROJECT_ID.iam.gserviceaccount.com" --member="serviceAccount:$env:CB_SA" --role="roles/iam.serviceAccountUser"
```

No code changes — IAM only.

---

### [Phase 4.1] - Replace Opik with Google Cloud Trace
**Files:** `services/tracing.py`, `ws/voice_handler.py`, `tools/location_tool.py`,
`tools/maps_tool.py`, `tools/notification_resume_tool.py`, `pyproject.toml`,
`cloudbuild.yaml`, `docs/phase4-deployment.md`

Removed the Opik (Comet) dependency entirely. All observability is now through
OpenTelemetry + Google Cloud Trace — no external API key required.

**`services/tracing.py`** — complete rewrite:
- Removed: `opik.configure()`, `opik.Opik()`, `get_opik_client()`, all Opik imports
- Added: `get_tracer()` — returns the module-level `opentelemetry.trace.Tracer` or `None`
- Added: `record_voice_turn(conversation_id, role, text)` — fire-and-forget OTel span per
  completed voice turn; stores `conversation_id`, `role`, `text_length` (not full text,
  to avoid PII in traces)
- Added: `trace_span(name)` — async function decorator that wraps a call in a named OTel
  span; no-op when tracing is off
- `ENABLE_ADK_TRACING` env var renamed to `ENABLE_TRACING`; values: `cloud` | `console` | `off`
- Auto-enables `cloud` mode when `GCP_PROJECT` or `GOOGLE_CLOUD_PROJECT` is set and
  `ENABLE_TRACING` is not explicitly set — zero-config on Cloud Run
- Cloud Trace uses Application Default Credentials (ADC) via the Cloud Run service account;
  requires `roles/cloudtrace.agent` on the service account

**Tools** — Opik decorators replaced with `@trace_span(name)`:
- `tools/location_tool.py` — removed `import opik`, removed `@opik.track`, added `@trace_span("get_user_location")`
- `tools/maps_tool.py` — same; `@trace_span("find_nearby_places")`
- `tools/notification_resume_tool.py` — same; `@trace_span("start_background_job")`
- `import os` removed from location_tool.py and maps_tool.py (was only needed for `os.getenv("OPIK_PROJECT_NAME")`)

**`ws/voice_handler.py`**:
- Removed `_opik_log_turn()` function and both call sites
- Replaced with `record_voice_turn(conversation_id, role, text)` calls on final user/assistant transcripts

**`pyproject.toml`**:
- Removed: `opik>=1.10.42`, `opentelemetry-exporter-otlp-proto-http>=1.40.0`
- Added: `opentelemetry-exporter-gcp-trace>=1.9.0`
- Kept: `opentelemetry-sdk>=1.7.0`

**`cloudbuild.yaml`**:
- `ENABLE_TRACING=cloud` added to `--set-env-vars` so Cloud Trace activates automatically on deploy

**`docs/phase4-deployment.md`**:
- Added `roles/cloudtrace.agent` to the IAM setup commands
- Updated secrets table (removed `OPIK_API_KEY`)
- Added tracing note explaining zero-config Cloud Trace auth via ADC

---

### [Phase 4.0] - Background jobs, FCM notifications, resume flow
**New files:** `services/control_channels.py`, `services/job_service.py`,
`services/notification_service.py`, `services/resume_event_service.py`,
`tools/notification_resume_tool.py`, `api/internal/__init__.py`,
`api/internal/jobs.py`, `docs/phase4-deployment.md`

**Modified files:** `config.py`, `main.py`, `services/conversation_service.py`,
`ws/voice_handler.py`, `agents/orchestrator.py`, `api/conversations.py`,
`pyproject.toml`, `cloudbuild.yaml`

---

#### `services/control_channels.py` — WebSocket control message registry

New module-level registry keyed by `conversation_id`, same pattern as
`location_channels`. Tools and services call `send_control_message()` to push
JSON to the active phone WebSocket without holding a direct WebSocket reference.

```python
control_channels.register(conversation_id, send_cb)    # called in voice_handler on connect
control_channels.unregister(conversation_id)            # called on disconnect
await control_channels.send_control_message(cid, msg)  # called from tools / internal handler
```

---

#### `services/job_service.py` — Job DB helpers + Cloud Tasks enqueue

- `create_job(conversation_id, job_type)` — creates a `Job` row with `status=queued`.
  Resolves `user_id` from the `Conversation` row (avoids storing it in ADK session state).
- `update_job_status(job_id, status, *, artifacts, spoken_completion_summary, ...)` — updates
  any job fields in one call.
- `get_job_by_id(job_id)` — internal lookup without ownership check.
- `enqueue_cloud_task(...)` — sends an HTTP Cloud Tasks task to `POST /internal/jobs/execute`.
  **No-op in development** if `CLOUD_TASKS_QUEUE` env var is not set — logs a warning and
  returns cleanly. In production the task carries an OIDC token for Cloud Run auth.

---

#### `services/notification_service.py` — FCM push notifications

- `send_push_notification(push_token, title, body, conversation_id, data)` — sends a Firebase
  Cloud Messaging message using `firebase_admin.messaging.send()`, run in `run_in_executor` to
  avoid blocking the async event loop. Android channel: `spectalk_jobs` (high priority).
- `get_user_push_token(user_id)` — looks up `users.push_token` from the DB.

Gracefully returns `False` (no exception) when the push token is empty or FCM fails.

---

#### `services/resume_event_service.py` — Resume event lifecycle

- `create_resume_event(conversation_id, event_type, *, job_id, spoken_summary, ...)` —
  inserts a `ResumeEvent` row and increments `conversations.pending_resume_count`.
- `get_pending_resume_events(conversation_id)` — returns all unacknowledged events,
  oldest first (used to inject resume context into Gemini on reconnect).
- `acknowledge_resume_event(resume_event_id)` — marks acknowledged, decrements count.
- `acknowledge_all_resume_events(conversation_id)` — batch acknowledge all pending events.

---

#### `tools/notification_resume_tool.py` — ADK tool `start_background_job`

Added to the Gervis agent tools list. Gemini calls this when the user requests long-running
work (coding, research, 3D models).

```
Args:
  job_type     — "coding" | "research" | "three_d_model" | "demo"
  description  — short description of what the job will do
  spoken_ack   — natural spoken acknowledgment to say to the user right now
```

On call:
1. Creates job row in DB (resolves `user_id` from conversation)
2. Sends `{"type": "job_started", "job_id": ..., "spoken_ack": ...}` to phone via `control_channels`
3. Transitions conversation state → `running_job`
4. Enqueues Cloud Task (no-op in dev)
5. Returns spoken_ack to Gemini immediately — user never waits in silence

---

#### `api/internal/jobs.py` — Cloud Tasks handler `POST /internal/jobs/execute`

Internal endpoint called by Cloud Tasks (or manually in dev):

1. Validates `X-CloudTasks-QueueName` header in `environment=production`
2. Marks job `running`, sends `job_update` to phone
3. Dispatches `_execute_job_by_type()` — Phase 4 has mock/demo implementation; Phase 5+ adds
   real dispatchers (OpenClaw, etc.)
4. On success: marks `completed`, creates `ResumeEvent`, transitions conversation →
   `awaiting_resume`, sends `job_update` to phone, sends FCM push notification
5. On failure: marks `failed`, creates `ResumeEvent` with error, sends FCM push, returns 500

---

#### `ws/voice_handler.py` — Control channel + resume context injection

**Control channel registration:**
```python
async def _send_control_message(message: dict) -> None:
    await websocket.send_text(json.dumps(message))

control_channels.register(conversation_id, _send_control_message)
# ... on disconnect:
control_channels.unregister(conversation_id)
```

**Resume context injection on reconnect:**
When the phone connects to a conversation that has pending `ResumeEvent` rows, the backend
injects a synthetic user turn into the live session via `live_request_queue.send_content()`.
Gemini reads the context and generates a natural welcome-back message immediately. Events are
acknowledged as soon as injection succeeds. Wrapped in `try/except AttributeError` for
ADK version compatibility.

**Protocol docs updated** to include `job_started`, `job_update`, `state_update`.

---

#### `api/conversations.py` — `POST /{id}/ack-resume-event`

New endpoint. Verifies conversation ownership, then calls
`acknowledge_all_resume_events()` to clear all pending events and reset
`pending_resume_count` to zero. Returns `204 No Content`.
Called by the Android app after the Gemini welcome-back message has played.

---

#### `config.py` — Phase 4 settings

```python
gcp_project: str = ""                    # Google Cloud project ID
cloud_tasks_queue: str = ""              # e.g. "backend-jobs" — empty = skip Cloud Tasks
cloud_tasks_location: str = "us-central1"
backend_base_url: str = "http://localhost:8080"  # full Cloud Run URL in production
cloud_run_service_account: str = ""      # for OIDC token on Cloud Tasks callbacks
```

---

#### `cloudbuild.yaml` — Phase 4 secrets + env vars injected on deploy

`--set-secrets` now includes:
- `FIREBASE_SERVICE_ACCOUNT_JSON` — needed for FCM in production
- `BACKEND_BASE_URL` — injected so Cloud Tasks tasks call back to the correct Cloud Run URL

`--set-env-vars` added:
- `ENVIRONMENT=production`
- `GCP_PROJECT`, `CLOUD_TASKS_QUEUE`, `CLOUD_TASKS_LOCATION`, `CLOUD_RUN_SERVICE_ACCOUNT`

---

#### `pyproject.toml` — `google-cloud-tasks>=2.16.0` added

Required for Cloud Tasks enqueue in production. Gracefully not called in development
(import skipped if `CLOUD_TASKS_QUEUE` is empty).

---

#### `docs/phase4-deployment.md` — Full deployment guide

See [`docs/phase4-deployment.md`](./docs/phase4-deployment.md) for:
- One-time GCP setup (project, APIs, service account, IAM, Artifact Registry, Cloud Tasks queue)
- Secret Manager setup for all Phase 4 secrets
- Cloud Build / Cloud Run deploy steps
- **Android frontend changes required** (backend URL, new control message types)
- Local testing guide (manual curl to `/internal/jobs/execute`)
- Phase 4 acceptance checklist

---

## Phase 3 - Voice Agent + Gemini Live
> Status: In Progress

### [Phase 3.13] - GPS coordinate precision for Maps queries ✅ Maps end-to-end confirmed
**Files:** `tools/location_tool.py`, `agents/orchestrator.py`

**Problem:** `get_user_location` was returning only `location_label` (e.g. `"Dachau, Germany"`)
which is city-level resolution. The agent was passing this string to `find_nearby_places`,
making nearby searches imprecise.

**Fix — `tools/location_tool.py`:**

`_format_location()` now returns the full location payload:

```python
{
  "available": True,
  "coordinates": "48.2631,11.4342",   # ← use this for Maps queries
  "latitude": 48.2631,
  "longitude": 11.4342,
  "accuracy_meters": 12.0,            # omitted if unknown
  "location_label": "Dachau, Germany" # display only, not for Maps
}
```

The tool docstring explicitly instructs the model: *"Always pass 'coordinates' to
find_nearby_places when available — it is far more precise than location_label."*

**Fix — `agents/orchestrator.py`:**

Agent instruction updated: when an implicit-location Maps query is needed, call
`get_user_location` first and pass the `coordinates` field (e.g. `"48.2631,11.4342"`)
to `find_nearby_places`. `location_label` is now documented as a fallback only.

**Result:** Maps grounding now uses precise GPS coordinates instead of a city name,
giving accurate "near me" results.

---

### [Phase 3.12] - Opik tracing fix + Google Maps confirmed working
**Files:** `services/tracing.py`, `ws/voice_handler.py`, `tools/location_tool.py`, `tools/maps_tool.py`

**Google Maps grounding confirmed working end-to-end.**
The full flow — `get_user_location` → `find_nearby_places` — resolves location from the
Android client and returns Maps-grounded results to the user.

**Opik tracing fixes:**

- `opik.configure()` was called with an invalid `project_name` parameter (not in its
  signature). Removed it. Project name is now passed directly to `opik.Opik(project_name=...)`.
- `opik.Opik()` was re-instantiated on every voice turn. Replaced with a module-level
  singleton in `services/tracing.py` (`_opik_client`) created once at startup and accessed
  via `get_opik_client()`.
- `@opik.track` decorators on tools now pass `project_name` and `type="tool"` explicitly so
  they appear correctly in the Opik UI regardless of global config state at import time.
- `force=True` added to `opik.configure()` so it overwrites any stale `~/.opik.config` from
  previous broken runs.

**What appears in Opik:**
- Each completed user transcript → `voice_turn:user` trace with `thread_id=conversation_id`
- Each completed assistant transcript → `voice_turn:assistant` trace
- Each `get_user_location` call → tool span with input/output
- Each `find_nearby_places` call → tool span with query, location, and result

---

### [Phase 3.11] - ADK session state fix + conversation_id in initial state
**Files:** `services/gemini_live_client.py`, `ws/voice_handler.py`, `services/conversation_service.py`

**Root cause of `get_user_location: no conversation_id in session state`:**

`InMemorySessionService.get_session()` returns a copy of the stored session, not a mutable
reference. Mutating `session.state["conversation_id"] = ...` on the returned object after
the fact did not affect the session stored in the service — so `tool_context.state` inside
ADK tool calls never saw the key.

**Fix:** `get_or_create_session()` now passes `state={"conversation_id": session_id}` as an
argument to `create_session()`. This is the correct ADK API for setting initial session state.
The value is stored in the session service at creation time and is visible to all subsequent
`tool_context.state` reads within `run_live()`.

The manual `session.state["conversation_id"] = conversation_id` line in `voice_handler.py`
was removed as redundant.

**DB connection warning:** `set_conversation_idle` failure downgraded from `ERROR` to
`WARNING`. The asyncpg connection is occasionally closed by the pool before the idle-state
write completes after WebSocket disconnect — this is a transient race, not a code defect.

---

### [Phase 3.10] - On-demand location: request_location / location_response
**Files:** `ws/voice_handler.py`, `tools/maps_tool.py`, `api/voice.py`

Switched from push-based location (Android sends `location_context` at session start or
proactively) to pull-based request-response initiated by the backend when the Maps tool needs
the user's location.

**Protocol change:**

Removed: `{"type": "location_context", "location_context": {...}}` (phone → backend push)

Added (phone → backend):
```json
{"type": "location_response", "latitude": 40.7128, "longitude": -74.0060,
 "accuracy_meters": 15.0, "location_label": "New York, NY"}
```

Added (backend → phone):
```json
{"type": "request_location"}
```

**`ws/voice_handler.py`:**

- Replaced `location_context` message handler with `location_response` handler. On receipt the
  payload is normalized via `normalize_location_context`, cached in `session.state["location_context"]`,
  persisted to DB, and the `_location_event` asyncio.Event is set so the Maps tool unblocks.
- On WebSocket connect, an `asyncio.Event` (`_location_event`) and a `_location_send_cb` async
  callable are injected into `session.state`. The Maps tool uses these to trigger and await
  on-demand location requests without any direct WebSocket reference.

**`tools/maps_tool.py`:**

When `find_nearby_places()` is called with an implicit location ("near me", etc.) and no cached
location context exists, the tool now:
1. Clears `_location_event`
2. Calls `_location_send_cb()` to send `{"type": "request_location"}` to the phone
3. Awaits the event with a 6-second timeout
4. Re-resolves location from the (now-updated) session state

If the phone doesn't respond in time or location permission is denied, the tool falls back to
prompting the user to share their location or name a place.

**`api/voice.py`:**

Removed `LocationContextPayload` and `VoiceSessionStartRequest`. `POST /voice/session/start`
no longer accepts a `location_context` body - location is now exchanged on-demand over the
WebSocket after the session opens.

---

### [Phase 3.9] - Session location context + maps fallback for "near me"
**Files:** `api/voice.py`, `ws/voice_handler.py`, `services/location_context_service.py`,
`tools/maps_tool.py`, `agents/orchestrator.py`

**`api/voice.py` - optional location context on session start:**

`POST /voice/session/start` now accepts an optional `location_context` payload alongside voice
session creation. The backend stores the normalized payload in `conversation.working_memory`
immediately so the session starts with the latest Android location context when available.

**`services/location_context_service.py` - shared normalization + persistence helpers:**

Added a dedicated service for sanitizing session location payloads and reading/writing them from
conversation working memory. This keeps the HTTP and WebSocket flows consistent and avoids ad-hoc
JSON handling in the transport layers.

**`ws/voice_handler.py` - live location updates over WebSocket:**

The voice socket now accepts:

```json
{
  "type": "location_context",
  "location_context": {
    "latitude": 52.52,
    "longitude": 13.405,
    "accuracy_meters": 18.0,
    "location_label": "Berlin, Germany",
    "captured_at": "2026-03-17T10:12:00Z"
  }
}
```

Incoming updates are normalized, persisted to the conversation, and copied into the ADK session
state so tools can use the freshest location during the active conversation.

**`tools/maps_tool.py` - implicit-location resolution:**

`find_nearby_places()` now accepts `ToolContext` and resolves phrases like `"near me"`,
`"my location"`, `"around me"`, and `"here"` from session state. If Android shared a
human-readable `location_label`, that is preferred. Otherwise it falls back to a lat/lng string.
If no location context is available, the tool returns a user-facing prompt asking for location
permission or an explicit place.

**`agents/orchestrator.py` - agent instruction update:**

The system prompt now explicitly tells the backend agent to use session location context for Maps
queries when the user implies current location instead of naming a city or neighborhood.

---

### [Phase 3.8] - ActivityEnd crash fix + maps tool schema cleanup
**Files:** `ws/voice_handler.py`, `tools/maps_tool.py`

**`voice_handler.py` - `send_realtime(types.ActivityEnd())` removed:**

In ADK 1.1.1, `LiveRequestQueue.send_realtime()` only accepts `types.Blob`. Passing
`types.ActivityEnd()` caused ADK to build a `LiveRequest` with a null blob
(`{data: None, mime_type: None}`). Gemini's `_parse_client_message` rejected it:
`ValueError: Unsupported input type "<class 'dict'>"`, crashing the downstream task
and closing the WebSocket immediately after connection.

Fix: removed the `send_realtime(types.ActivityEnd())` call entirely. The `end_of_speech`
control message from the phone is now acknowledged with a debug log only. VAD
(`realtime_input_config` in ADK >= 1.2) will handle silence detection automatically
once the ADK version is upgraded.

**`tools/maps_tool.py` - lat/lon params removed:**

`float = 0.0` defaults triggered ADK schema warnings ("Default value is not supported in
function declaration schema for Google AI") on every session start - one warning per
optional param. Removed `latitude` and `longitude` from the function signature entirely.
The `location: str` param (e.g. "downtown Seattle") provides sufficient context for Maps
grounding. GPS-coordinate precision can be re-added in a future phase by declaring a manual
function schema instead of relying on automatic parsing.

---

### [Phase 3.7] - find_nearby_places Optional[float] ADK parse fix
**File:** `tools/maps_tool.py`

ADK's automatic function calling (`_automatic_function_calling_util.py`) cannot parse
`float | None = None` (Union/Optional) parameters. On startup, ADK inspects the function
signature to build the Gemini function declaration. The `latitude` and `longitude` params
triggered a `ValueError`, crashing the agent initialization and closing the WebSocket before
the session started.

Fix: changed both parameters from `float | None = None` to `float = 0.0`. (Superseded by
Phase 3.8 which removes the params entirely to also eliminate schema default warnings.)

---

### [Phase 3.6] - run_live deprecated API fix
**Files:** `services/gemini_live_client.py`, `ws/voice_handler.py`

ADK 1.1.1 deprecated the `session=` parameter on `runner.run_live()`. The old call
`runner.run_live(session=session, ...)` triggered a `DeprecationWarning` on every voice
connection and will break in a future ADK version.

`start_live_session()` in `GeminiLiveClient` now takes `user_id` and `session_id` directly
and passes them to `runner.run_live(user_id=..., session_id=..., ...)`. The `voice_handler.py`
call site was updated to match - it no longer receives the session object, only the IDs.

---

### [Phase 3.5] - Gemini API key test script
**New file:** `scripts/test_gemini_key.py`

Standalone script that verifies `GEMINI_API_KEY` from `.env` is valid and has access to both
the standard Gemini API and the Gemini Live streaming API. Run with:

```bash
uv run python scripts/test_gemini_key.py
```

Three sequential checks:
1. **Key present** - `GEMINI_API_KEY` is set and is not the placeholder value
2. **Text generation** - calls `gemini-2.5-flash` via `generate_content`; confirms the key
   authenticates and the API responds
3. **Live session** - opens a real `runner.run_live()` bidi session with the native-audio model,
   sends a text turn, waits up to 15s for at least one event back, then closes cleanly

Exits with a clear `x` message and non-zero code on the first failing check so it works in CI.

---

### [Phase 3.4] - Conversation delete endpoint + null summary fix + WebSocket error logging

**`api/conversations.py`:**

`DELETE /conversations/{conversation_id}` - new endpoint.
Verifies the authenticated user owns the conversation, then deletes all child rows in FK-safe
order before removing the conversation itself:
`resume_events` -> `pending_actions` -> `turns` -> `assets` -> `jobs` -> `conversation`.
Returns `204 No Content`. No request body needed.

`last_turn_summary: Optional[str] = None` - added explicit `= None` default to
`ConversationSummary.last_turn_summary` (and `working_memory` in `ConversationDetail`,
`text`/`event_type` in `TurnItem`). Pydantic v2 now reliably serializes unset Python `None`
values as JSON `null` instead of omitting them or coercing to the string `"null"`.

**`ws/voice_handler.py` - `_downstream_task` error surfacing:**

The `try/except` block was previously inside the `async for` loop. If Gemini threw an exception
on the very first iteration (e.g. invalid API key, quota exceeded, model not allowlisted), the
error escaped the handler entirely and silently killed the task - producing `connection open` /
`connection closed` in the log with no explanation.

Fix: moved the `try/except` to wrap the entire `async for`. Errors from Gemini are now:
- Logged at `ERROR` level with full `exc_info` stack trace
- Forwarded to the phone as `{"type": "error", "message": "..."}` before the socket closes

Also: after `asyncio.wait`, done tasks are now inspected for stored exceptions and logged, so
any unexpected crash in either the upstream or downstream task appears in the log.

---

### [Phase 3.3] - Turns endpoint added
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

### [Phase 3.2] - RunConfig compatibility fix
**Problem:** `google-adk 1.1.1` uses a strict Pydantic model for `RunConfig` that rejects unknown
fields. Passing `session_resumption` and `realtime_input_config` caused a `ValidationError` on
every WebSocket connection attempt, preventing any voice session from starting.

**Fix (`services/gemini_live_client.py`):**
`_build_run_config()` now inspects `RunConfig.model_fields` at runtime before building the kwargs
dict. Fields only present in newer ADK versions (`session_resumption`, `realtime_input_config`) are
included conditionally - if the installed version declares them, they are added; otherwise they are
silently skipped. This makes the config forward-compatible: upgrading ADK automatically activates
VAD tuning and session resumption without code changes.

**Fields supported in `google-adk 1.1.1`:**
- `streaming_mode` - set to `"bidi"` (bidirectional)
- `response_modalities` - `["AUDIO"]`
- `input_audio_transcription` - `AudioTranscriptionConfig()`
- `output_audio_transcription` - `AudioTranscriptionConfig()`

**Fields activated on upgrade (not yet available in 1.1.1):**
- `session_resumption` - `SessionResumptionConfig(transparent=True)`
- `realtime_input_config` - VAD with `START_SENSITIVITY_LOW`, `END_SENSITIVITY_LOW`,
  `silence_duration_ms=320`

---

### [Phase 3.1] - Turns endpoint added
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

### [Phase 3.0] - Voice agent implementation
**New packages added (`pyproject.toml`):**
- `google-adk>=1.0.0` - ADK Runner, Agent, LiveRequestQueue, InMemoryRunner
- `deprecated>=1.3.1` - required transitive dependency missing from ADK's metadata
- `uvicorn[standard]>=0.34.0` - upgraded from `0.32.0` (ADK requires `>=0.34.0`)

**New files:**

`agents/orchestrator.py`
Defines the Gervis ADK `Agent` via `create_gervis_agent(model)` factory. Wires the Gervis
system instruction (voice UX rules, persona, purpose) and two tools: `google_search` and
`find_nearby_places`.

`tools/search_tool.py`
Re-exports ADK's built-in `google_search` tool, which uses Gemini's native Google Search
grounding. No external Search API key required - only the Gemini API key.
Docs: https://ai.google.dev/gemini-api/docs/google-search

`tools/maps_tool.py`
Defines `find_nearby_places(query, location, latitude?, longitude?)` as an async function tool.
Uses Gemini's native Google Maps grounding (`types.Tool(google_maps=types.GoogleMaps())`).
No separate Maps API key required - only the Gemini API key. Accepts optional `lat_lng` via
`RetrievalConfig` for GPS-precise results. Runs the sync Gemini call in `run_in_executor` to
stay non-blocking.
Docs: https://ai.google.dev/gemini-api/docs/maps-grounding
Pricing: $25 per 1,000 grounded prompts. Free tier: 500 requests/day.

`services/gemini_live_client.py`
Singleton (`gemini_live_client`) wrapping an ADK `InMemoryRunner`. Initialized once in the
FastAPI lifespan via `gemini_live_client.initialize()`. Exposes:
- `get_or_create_session(user_id, session_id)` - fetches or creates an ADK session keyed by
  `conversation_id`
- `start_live_session(session, live_request_queue)` - returns the `run_live()` async generator
- `new_request_queue()` - creates a fresh `LiveRequestQueue` per connection

`services/audio_session_manager.py`
Manages per-conversation `SessionEntry` objects. On phone disconnect, starts a 30-second grace
timer; if the phone reconnects within the window the timer is cancelled and the same ADK session
is reused. On grace expiry the entry is removed and the next connection starts fresh. Lock-safe
for concurrent reconnect races.

`services/conversation_service.py`
Three async DB helpers used by the WebSocket downstream task:
- `persist_turn(conversation_id, role, text, event_type)` - writes a `Turn` row on every final
  transcription event (non-partial)
- `set_conversation_active(conversation_id)` - sets `state = "active"` on WebSocket connect
- `set_conversation_idle(conversation_id)` - sets `state = "idle"` on WebSocket disconnect

`ws/voice_handler.py`
FastAPI `APIRouter` exposing `WS /ws/voice/{conversation_id}`.

Authentication: JWT accepted as `?token=<jwt>` query param or `Authorization: Bearer` header.
Invalid/missing token closes the socket immediately with code `4001`.

Upstream task (`phone -> ADK`):
- Binary frames forwarded as `audio/pcm;rate=16000` blobs via `send_realtime()`
- `{"type": "end_of_speech"}` -> sends `types.ActivityEnd()` (falls back gracefully if not
  available in the installed SDK version)
- `{"type": "image", ...}` -> decodes base64 and injects as a blob into the ADK session

Downstream task (`ADK -> phone`):
- **`interrupted` events are forwarded to the phone FIRST, before any further audio.** This is
  the critical correctness requirement for barge-in UX - the phone clears its audio buffer
  immediately on receipt.
- Audio `inline_data` parts -> forwarded as raw binary frames (zero-copy)
- `input_transcript` / `output_transcript` -> forwarded as JSON; final turns persisted to DB
- `turn_complete` -> forwarded as `{"type": "turn_complete"}`

On disconnect: `live_request_queue.close()` is called, grace timer starts, conversation set idle.

**Modified files:**

`config.py` - added:
- `gemini_api_key: str` - single key for Gemini Live, Search grounding, Maps grounding
- `adk_app_name: str` - default `"spectalk"`
- `gemini_model: str` - default `"gemini-2.5-flash-native-audio-preview-12-2025"`
- Removed `google_maps_api_key` (not needed - Maps grounding uses only the Gemini key)

`main.py` - added:
- `os.environ.setdefault("GOOGLE_API_KEY", settings.gemini_api_key)` in lifespan (ADK picks
  this up automatically)
- `gemini_live_client.initialize()` call in lifespan
- `ws_voice_router` mounted at prefix `/ws`

---

## Phase 2 - Backend Foundation
> Status: Approved

**Delivered:**
- FastAPI app with lifespan DB pool init, CORS middleware, router registration
- SQLAlchemy async engine + `AsyncSessionLocal` session factory
- Full ORM models: `User`, `Conversation`, `Turn`, `Job`, `PendingAction`, `ResumeEvent`, `Asset`
- Alembic migrations: initial schema + full schema (all 7 tables on Neon PostgreSQL)
- Firebase Admin SDK init with ADC (`gcloud auth application-default login`)
- `POST /auth/session` - verifies Firebase ID token, upserts user row, returns product JWT
- `GET /conversations` - list user's conversations
- `GET /conversations/{id}` - single conversation detail
- `POST /voice/session/start` - creates conversation row, returns `conversation_id`
- `GET /jobs/{job_id}` - job status stub
- `POST /notifications/device/register` - stores FCM push token on user row
- JWT middleware (`require_auth` dependency) - validates Bearer JWT on all protected routes
- Production-ready `Dockerfile` (uv-based, python 3.12-slim)
- `cloudbuild.yaml` - Cloud Build CI/CD pipeline (build -> push -> migrate -> deploy)

**Database:** Neon PostgreSQL (development). Cloud SQL PostgreSQL (production - deferred).

---

## Phase 0 / Phase 1 - Android Only
> Backend: no changes in these phases.

Phase 0 delivered Firebase Authentication (email/password + Google Sign-In) on Android.
Phase 1 delivered the voice session UI, `BackendVoiceClient`, `HotwordService`, audio components,
and the Conversation List screen on Android. Backend remained at Phase 2 state throughout.
