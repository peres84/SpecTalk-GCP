# Changelog - gervis-backend

All notable changes to the SpecTalk backend are documented here.
Entries are ordered newest-first within each phase.

---

## Phase 5 - Coding Mode and OpenClaw Integration
> Status: **In Progress** — PRD workflow + real OpenClaw integration implemented

---

### [Phase 5.5] - Cloud Tasks OIDC auth + Opik conversation threading

**Modified files:** `services/job_service.py`, `tools/coding_tools.py`

#### `services/job_service.py` — OIDC token for Cloud Tasks → Cloud Run

Cloud Tasks tasks were enqueued without OIDC authentication, causing Cloud Run to reject
every callback with HTTP 403. Fixed by adding `oidc_token` to the HTTP request when
`CLOUD_RUN_SERVICE_ACCOUNT` is configured.

```python
http_request["oidc_token"] = {
    "service_account_email": settings.cloud_run_service_account,
    "audience": handler_url,
}
```

Logs a warning (not silent) if the service account is missing, so misconfiguration is
immediately visible in Cloud Run logs.

#### `tools/coding_tools.py` — Opik thread_id linking + confirm_and_dispatch entry log

- All three coding tools now call `opik.update_current_trace(thread_id=conversation_id)`
  so every tool span appears under the correct conversation thread in the Opik dashboard.
- Added `INFO` log at entry of `confirm_and_dispatch` to confirm the tool is reached in
  production (was invisible in logs, making it impossible to distinguish "tool not called"
  from "job creation failed silently").

---

### [Hotfix] - Dockerfile lockfile pinning + config secret stripping

**Modified files:** `Dockerfile`, `config.py`

---

#### `Dockerfile` — pin dependencies via `uv.lock`

**Root cause of "Cannot extract voices from a non-audio request" on Cloud Run:**

The Dockerfile was copying only `pyproject.toml` before running `uv sync`, without
copying `uv.lock`. Since `pyproject.toml` declares `google-adk>=1.0.0`, every Docker
build resolved and installed the **latest** ADK version rather than the lockfile-pinned
`1.1.1`. A newer ADK version introduced breaking changes to the RunConfig API that
caused Gemini to reject every Live session at connection time with error 1007.

Fix: copy `uv.lock` alongside `pyproject.toml` and use `uv sync --frozen --no-dev` so
the build always installs the exact versions pinned in the lockfile.

```dockerfile
# Before (broken — installs latest ADK on every build)
COPY pyproject.toml .
RUN uv sync --no-dev

# After (correct — installs lockfile-pinned versions)
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
```

**Rule going forward:** whenever adding or upgrading a dependency, run `uv add <pkg>`
(which updates `uv.lock`) and commit both `pyproject.toml` and `uv.lock` together.

---

#### `config.py` — strip trailing whitespace from all secret values

Secrets stored in GCP Secret Manager via Windows tools (PowerShell `echo`, copy-paste)
often include a trailing `\r\n`. This caused `httpx.LocalProtocolError: Illegal header
value` in the Opik health check probe and would affect any secret used as an HTTP
header value.

Added a universal `@field_validator("*", mode="before")` that strips leading/trailing
whitespace from all string settings at load time. This is a defensive measure — secrets
should be stored cleanly, but the validator prevents a stale `\r\n` from taking down
the service.

---

### [Phase 5.4] - OpenClaw context chaining + nginx deploy + path/URL parsing

**Modified files:** `tools/openclaw_coding_tool.py`

---

#### `tools/openclaw_coding_tool.py` — context chaining, deploy instructions, result parsing

**Context chaining via `previous_response_id`:**

Each completed coding job now stores its OpenClaw `response_id` in `job.artifacts`
(`type: "openclaw_response_id"`). The next coding job for the same conversation looks
up the most recent stored ID and passes it as `previous_response_id` in the request
body, giving OpenClaw full memory of prior work.

New helper `_get_last_response_id(conversation_id)` queries the DB for the most recent
completed coding job in the conversation and extracts the stored response ID.

Session key changed from `spectalk-{job_id}` → `spectalk-{conversation_id}` so all
jobs in the same conversation share one OpenClaw thread.

**nginx deploy instruction in prompt:**

OpenClaw is now instructed to:
1. Create the project in a snake_case folder
2. Deploy it via nginx over HTTP
3. Reply with exactly two lines: `PATH: <absolute path>` and `URL: <http url>`
   (URL line omitted if nginx deployment failed)

**PATH/URL parsing:**

Result builder now parses `PATH:` and `URL:` from the OpenClaw response text.
Artifacts returned:
- `openclaw_response_id` — for context chaining on follow-up jobs
- `url` — live HTTP URL (if nginx succeeded)
- `path` — absolute folder path on the VPS

Spoken summary:
- Deploy succeeded → *"Your project is ready and live at http://..."*
- Deploy failed → *"Files are at /home/pj/.openclaw/workspace/..."*

---

### [Phase 5.3] - OpenClaw OpenResponses API (SSE streaming, synchronous)

**Modified files:** `tools/openclaw_coding_tool.py`, `api/internal/jobs.py`
**New files:** `scripts/test_openclaw.py`, `docs/openclaw-user-setup.md`

---

#### `tools/openclaw_coding_tool.py` — rewritten for `POST /v1/responses` SSE

Replaced the callback-based `POST /hooks/agent` approach with the OpenClaw
**OpenResponses API** (`POST /v1/responses`), which must be explicitly enabled in
`~/.openclaw/openclaw.json` under `gateway.http.endpoints.responses.enabled = true`.

The endpoint is served on the **same port as the WebSocket gateway** (18789) via HTTP/WS
multiplexing. It streams results as Server-Sent Events (SSE).

New flow:
1. Fetch user credentials from DB via `get_decrypted_integration(user_id, "openclaw")`
2. `POST {url}/v1/responses` with `stream: true` and a 30-minute timeout
3. Consume SSE stream, collecting `response.output_text.delta` events into a full string
4. Return assembled result — no callback, no `__async_pending`, fully synchronous from
   the executor's perspective

Request headers:
```
Authorization: Bearer {token}
x-openclaw-agent-id: main
x-openclaw-session-key: spectalk-{job_id}
```

Request body:
```json
{"model": "openclaw", "input": "<PRD prompt>", "stream": true}
```

SSE events consumed:
- `response.output_text.delta` — appended to result buffer
- `error` — raises `RuntimeError` with the OpenClaw error message
- All other events (`response.created`, `response.in_progress`, etc.) — ignored

The session key `spectalk-{job_id}` is deterministic, giving each job an isolated
OpenClaw conversation.

Result spoken_summary is capped at 500 characters to keep Gervis responses concise.

---

#### `api/internal/jobs.py` — removed `__async_pending` path

The `__async_pending` sentinel check was removed. It was only needed when OpenClaw used
a callback model (fire-and-forget → callback on completion). With `POST /v1/responses`
the coding tool blocks until OpenClaw finishes and returns the result directly — no
callback, no pending state.

---

#### `scripts/test_openclaw.py` — standalone integration test

New script for verifying OpenClaw connectivity without running the full backend.
Reads `OPEN_CLAW_URL` and `OPEN_CLAW_TOKEN` from `.env`.

```bash
# From gervis-backend/:
uv run python scripts/test_openclaw.py           # streaming (default)
uv run python scripts/test_openclaw.py nostream  # non-streaming
```

Sends a simple "say hello" prompt and prints all SSE events. Confirmed: HTTP 200,
`text/event-stream` content type, `response.output_text.delta` events stream correctly.

---

#### `docs/openclaw-user-setup.md` — end-user setup guide

New complete guide covering everything a user needs to connect their own OpenClaw
instance to SpecTalk:

- **Step 1** — Enable `gateway.http.endpoints.responses` in `openclaw.json` + restart + local verify
- **Step 2** — Extract the gateway token from `openclaw.json`
- **Step 3A** — Tailscale Funnel setup (recommended): `tailscale funnel --bg 18789`
  - Explains *why* Funnel is required (GCP Cloud Run is not on the tailnet — the
    MagicDNS hostname is tailnet-only without Funnel)
- **Step 3B** — Non-Tailscale alternatives: Cloudflare Tunnel, ngrok, SSH reverse proxy, direct IP
- **Step 4** — Adding credentials in SpecTalk Settings
- **Step 5** — End-to-end test with Gervis
- Troubleshooting table for common failure modes
- Architecture note for developers

---

### [Phase 5.2] - Real OpenClaw integration + per-user encrypted credentials

**New files:** `services/encryption_service.py`, `api/integrations.py`,
`api/internal/openclaw_callback.py`,
`migrations/versions/d4e1f8a2c5b9_user_integrations.py`

**Modified files:** `db/models.py`, `config.py`, `main.py`,
`tools/openclaw_coding_tool.py`, `api/internal/jobs.py`

---

#### `db/models.py` — `UserIntegration` model

New ORM model storing Fernet-encrypted third-party credentials per user.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `user_id` | UUID FK → users | |
| `service_name` | VARCHAR(64) | e.g. `"openclaw"` |
| `encrypted_url` | Text | Fernet-encrypted base URL |
| `encrypted_token` | Text | Fernet-encrypted hook token |
| `created_at` / `updated_at` | DateTime(tz) | |

Unique constraint: `(user_id, service_name)` — one credential set per service per user.
`User` model gained a `integrations` backref relationship.

---

#### `services/encryption_service.py` — Fernet symmetric encryption

Module-level singleton `_fernet` initialized from `INTEGRATION_ENCRYPTION_KEY` env var.

- **Production:** set `INTEGRATION_ENCRYPTION_KEY` in Secret Manager. Generate with:
  `uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"`
- **Development:** if the key is not set, a random per-process key is generated with a loud
  warning. Credentials will not survive a server restart in dev — intentional.

Public API:
- `encrypt(plaintext: str) -> str` — Fernet token (URL-safe base64)
- `decrypt(token: str) -> str` — raises `InvalidToken` on tampering
- `mask_url(url: str) -> str` — returns a truncated preview safe for list responses

---

#### `api/integrations.py` — `GET/POST/DELETE /integrations`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/integrations` | Upsert encrypted credentials for a service |
| `GET` | `/integrations` | List configured integrations (masked URLs only) |
| `DELETE` | `/integrations/{service}` | Remove credentials |

`POST /integrations` body:
```json
{"service": "openclaw", "url": "https://your-machine.ts.net", "token": "hook-token"}
```

Response includes `"message": "Your credentials have been encrypted and stored securely."` —
the Android app displays this to the user as the encryption confirmation.

The `url` is shown in full only once (in the save response). All subsequent `GET` responses
return a masked preview via `mask_url()`.

Internal helper `get_decrypted_integration(user_id, service)` is used by the coding tool
to retrieve plaintext credentials without going through the HTTP layer.

Supported services validated at the API layer: `{"openclaw"}`.

---

#### `tools/openclaw_coding_tool.py` — real `POST /hooks/agent` call

Replaced the 8-second mock with the real OpenClaw HTTP integration.

Flow:
1. Fetch user's OpenClaw credentials from DB via `get_decrypted_integration(user_id, "openclaw")`
2. If not configured → raise `RuntimeError` with a message directing the user to Settings
3. Build callback URL: `{BACKEND_BASE_URL}/internal/openclaw/callback/{job_id}`
4. POST to `{url}/hooks/agent` with `Authorization: Bearer {token}` (30s timeout)
5. Return `{"__async_pending": True, "display_summary": "Building..."}` immediately

The `__async_pending` sentinel tells the job executor NOT to mark the job completed —
OpenClaw will call back asynchronously when the build finishes.

Message sent to OpenClaw instructs it to POST the result back in this shape:
```json
{"spoken_summary": "...", "display_summary": "...", "artifacts": [...]}
```

---

#### `api/internal/openclaw_callback.py` — `POST /internal/openclaw/callback/{job_id}`

Receives the async result from OpenClaw when the build completes.

1. Validates `job_id` is a real UUID and corresponds to a job in `running`/`queued` state
2. Updates job to `completed` with spoken/display summary and artifacts
3. Sends `job_update: completed` control message to the phone
4. Smart delivery: `inject_job_result()` if session is live → otherwise creates resume event + FCM

Duplicate callbacks (same job already `completed`) are silently ignored (returns `{"status": "already_complete"}`).

---

#### `api/internal/jobs.py` — async-pending handling

Added early-return path after `_execute_job_by_type`:
```python
if result.get("__async_pending"):
    # job stays in "running" — callback handler will complete it
    return {"status": "pending", "job_id": job_id}
```

Also updated the `coding` dispatch to pass `user_id`:
```python
return await execute_coding_job(job_id, conversation_id, user_id, payload)
```

---

#### `config.py` + `main.py` — encryption key plumbing

`config.py`: added `integration_encryption_key: str = ""` setting.

`main.py` lifespan: copies `settings.integration_encryption_key` into
`os.environ["INTEGRATION_ENCRYPTION_KEY"]` so `encryption_service.py` reads it
consistently regardless of whether it was loaded from `.env` or Secret Manager.

---

#### Migration `d4e1f8a2c5b9` — `user_integrations` table

Creates `user_integrations` with all columns, unique constraint, and index on `user_id`.
Down revision: `b7e2d4f1c8a3` (Phase 5 PendingAction fields).

**Secret Manager setup (run once):**
```powershell
# Generate key
cd gervis-backend; uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"

# Store in Secret Manager
printf 'GENERATED_KEY' | gcloud secrets create INTEGRATION_ENCRYPTION_KEY --data-file=- --project=spectalk-488516
```

---

### [Phase 5.1] - Opik (Comet ML) observability re-integrated

**Modified files:** `services/tracing.py`, `ws/voice_handler.py`, `config.py`,
`tools/location_tool.py`, `tools/maps_tool.py`, `tools/notification_resume_tool.py`,
`tools/coding_tools.py`, `cloudbuild.yaml`, `.env.example`, `pyproject.toml`

**Previous bugs fixed:**
1. `opik.configure()` called with invalid `project_name` param — that call is removed entirely.
   The client is now initialized via `opik.Opik(api_key=..., workspace=..., project_name=...)`.
2. `opik.Opik()` re-instantiated on every voice turn — now a module-level singleton in `tracing.py`.

#### `services/tracing.py` — Opik singleton + session-scoped trace helpers

New module-level state:
- `_opik_client` — `opik.Opik | None` singleton, initialized once in `_setup_opik()`.
- `_active_traces` — `dict[conversation_id → opik.Trace]` for session-scoped spans.

`_setup_opik()` is called unconditionally at the end of `setup_tracing()` — it runs even when OTel is off or not configured. Guarded by `OPIK_API_KEY`; no-op when the key is absent (graceful degradation).

New public functions:

| Function | Purpose |
|----------|---------|
| `get_opik_client()` | Returns singleton or `None` |
| `opik_session_start(conversation_id, user_id)` | Opens `opik.Trace(thread_id=conversation_id)` |
| `opik_session_end(conversation_id)` | Calls `trace.end()` and removes from `_active_traces` |
| `record_voice_turn_opik(conversation_id, role, text)` | Adds `trace.span(output={"text": text})` — stores **full text** unlike OTel which only stores `text_length` |

#### `ws/voice_handler.py` — session lifecycle hooks + per-turn Opik spans

- `opik_session_start(conversation_id, user_id)` called after `log_session_start`.
- `record_voice_turn_opik(...)` called alongside each `record_voice_turn(...)` in `_downstream_task`.
- `opik_session_end(conversation_id)` called alongside `log_session_end` in `finally`.

#### Tool files — `@opik.track` replaces `@trace_span`

All tool functions now use `@opik.track` with `ignore_arguments=["tool_context"]` to prevent ADK's `ToolContext` (non-serialisable) from causing capture errors.

| File | Function | Tool name in Opik |
|------|----------|-------------------|
| `tools/location_tool.py` | `get_user_location` | `"get_user_location"` |
| `tools/maps_tool.py` | `find_nearby_places` | `"find_nearby_places"` |
| `tools/notification_resume_tool.py` | `start_background_job` | `"start_background_job"` |
| `tools/coding_tools.py` | `request_clarification` | `"request_clarification"` |
| `tools/coding_tools.py` | `generate_and_confirm_prd` | `"generate_and_confirm_prd"` |
| `tools/coding_tools.py` | `confirm_and_dispatch` | `"confirm_and_dispatch"` |

#### `config.py` — three new Opik settings

```python
opik_api_key: str = ""          # Set via OPIK_API_KEY env var to enable
opik_workspace: str = "javier-peres"
opik_project_name: str = "gervis"
```

#### `cloudbuild.yaml` — production secret + env vars

- `--set-secrets`: added `OPIK_API_KEY=OPIK_API_KEY:latest`
- `--set-env-vars`: added `OPIK_WORKSPACE=javier-peres,OPIK_PROJECT_NAME=gervis`

Before first deploy: `echo -n "key" | gcloud secrets create OPIK_API_KEY --data-file=-`

#### `.env.example` — fixed stale variable name

- `ENABLE_ADK_TRACING` → `ENABLE_TRACING` (matches current `config.py`)
- `OPIK_WORKSPACE` default updated to `javier-peres`

#### What Opik captures per session

All items grouped under `thread_id=conversation_id` for full conversation replay in Opik UI:

| Event | Captured |
|-------|----------|
| Session open | `user_id`, `conversation_id`, `thread_id` |
| User speech turn | Full transcript text |
| Model (Gervis) speech turn | Full transcript text |
| `get_user_location` | GPS result dict |
| `find_nearby_places` | query, location, result |
| `start_background_job` | `job_type`, `description`, `spoken_ack` |
| `request_clarification` | question text |
| `generate_and_confirm_prd` | `project_idea`, PRD dict |
| `confirm_and_dispatch` | `confirmed`, `change_request` |
| Session close | `trace.end()` |

---

### [Phase 5.0] - Coding mode: clarifications → PRD → confirmation → coding job
**New files:** `agents/team_code_pr_designers/__init__.py`,
`agents/team_code_pr_designers/designer_agent.py`, `tools/coding_tools.py`,
`tools/openclaw_coding_tool.py`

**Modified files:** `agents/orchestrator.py`, `api/conversations.py`,
`api/internal/jobs.py`, `db/models.py`,
`migrations/versions/b7e2d4f1c8a3_phase5_pending_action_fields.py`

---

#### `db/models.py` + migration `b7e2d4f1c8a3` — PendingAction fields added

Added three columns to `pending_actions` (the model already existed from Phase 2):

| Column | Type | Notes |
|--------|------|-------|
| `action_type` | VARCHAR(64) nullable | e.g. `"confirm_prd"` |
| `payload` | JSONB nullable | Stores the generated PRD dict |
| `status` | VARCHAR(32) NOT NULL default `"pending"` | `pending` \| `resolved` \| `cancelled` |

Existing columns (`description`, `confirmation_prompt`, `resolved_at`, `resolution`)
are kept for backward compatibility.

---

#### `agents/team_code_pr_designers/designer_agent.py` — PRD Shaper

Single async function `generate_prd(project_idea, clarifications) -> dict`.

- Uses `google.genai` Client with **Google Search grounding** (`genai_types.Tool(google_search=...)`)
  so tech stack recommendations reflect current ecosystem (Vite vs CRA, TanStack vs SWR, etc.)
- Runs the synchronous Gemini call in `asyncio.to_thread()` to stay non-blocking
- Strips markdown code blocks from the response before JSON parsing
- Fallback `_fallback_prd()` returns a sensible default if parsing fails — never crashes
- Model: `gemini-2.0-flash` (stable text model, separate from the audio preview used for voice)

Returns:
```json
{
  "project_name": "TaskFlow",
  "description": "2-3 sentences...",
  "target_platform": "web | mobile | backend | fullstack",
  "key_features": ["...", "...", "...", "...", "..."],
  "tech_stack": "React 19 + Vite + FastAPI + PostgreSQL",
  "scope_estimate": "small | medium | large"
}
```

---

#### `tools/coding_tools.py` — three new ADK tools

**`request_clarification(question, tool_context)`**
- Increments `tool_context.state["clarification_count"]` (tracks max 3 questions)
- Stores question in `tool_context.state["pending_clarification"]`
- On first call (count == 0): sends `{"type":"state_update","state":"coding_mode"}` to phone
  and sets conversation state → `coding_mode`
- Returns the question string for Gervis to speak

**`generate_and_confirm_prd(project_idea, clarifications_json, tool_context)`**
- Parses `clarifications_json` (JSON string) into a dict
- Calls `generate_prd()` (Gemini + Search grounding)
- Stores PRD in `tool_context.state["pending_prd"]`
- Sends `state_update: awaiting_confirmation` control message with full `prd_summary`
- Persists a `PendingAction` row (`action_type="confirm_prd"`, `status="pending"`)
- Sets conversation state → `awaiting_confirmation`
- Returns a 2-3 sentence spoken PRD summary for Gervis to voice

**`confirm_and_dispatch(confirmed, change_request, tool_context)`**
- If `confirmed=True`:
  - Updates `PendingAction.status = "resolved"`
  - Creates `Job` row (`job_type="coding"`)
  - Enqueues Cloud Tasks task with `payload={"prd": prd_dict}`
  - Sends `state_update: running_job` + `job_started` control messages
  - Sets conversation state → `running_job`
- If `confirmed=False`:
  - Updates `PendingAction.status = "cancelled"`
  - Clears `pending_prd` from session state, resets `clarification_count`
  - Sends `state_update: idle` control message
  - Returns instruction to ask what the user wants changed

---

#### `tools/openclaw_coding_tool.py` — coding job executor (initial mock, replaced in Phase 5.2)

`execute_coding_job(job_id, conversation_id, payload) -> dict`

Initial implementation: realistic mock — `asyncio.sleep(8)` then returns a GitHub repo URL
derived from `prd["project_name"]`. Replaced in Phase 5.2 with the real OpenClaw integration.

---

#### `api/internal/jobs.py` — coding job wired

Added to `_execute_job_by_type()`:
```python
elif job_type == "coding":
    from tools.openclaw_coding_tool import execute_coding_job
    return await execute_coding_job(job_id, conversation_id, payload)
```

---

#### `api/conversations.py` — `POST /{id}/confirm`

New out-of-voice confirmation endpoint for users who return to the app to confirm a PRD.

**Request:** `POST /conversations/{id}/confirm`
- Auth: Bearer JWT
- Body: `{"confirmed": true}` or `{"confirmed": false, "change_request": "make it mobile"}`

**Logic:**
1. Verify user owns the conversation
2. Fetch most recent `PendingAction` where `action_type="confirm_prd"` and `status="pending"`
3. If `confirmed=true`: create Job row + enqueue Cloud Tasks + resolve PendingAction
   + set conversation to `running_job` + send `state_update` and `job_started` control messages
4. If `confirmed=false`: cancel PendingAction + set conversation to `idle`
   + send `state_update: idle` control message

**Response:** `{"status": "ok"}` (+ `"job_id"` when confirmed)

---

#### `agents/orchestrator.py` — Coding mode system instruction + new tools registered

- Appended **Coding mode — CRITICAL RULES** section to `GERVIS_INSTRUCTION`:
  - Triggers on: "build", "create", "make", "code" anything
  - Step 1: `request_clarification` up to 3 times (one question per call)
  - Step 2: `generate_and_confirm_prd` — always call before dispatching
  - Step 3: Speak PRD summary, wait for user
  - Step 4: `confirm_and_dispatch` on yes/no
  - Coding requests must NOT use `start_background_job` directly — use coding mode flow
- Registered `request_clarification`, `generate_and_confirm_prd`, `confirm_and_dispatch`
  in `create_gervis_agent()` tools list (total: 7 tools)

---

## Phase 4 - Jobs, Notifications, and Resume Flow
> Status: **Deployed** — running on Cloud Run (spectalk-488516, us-central1)

### [Phase 4.5] - Smart job delivery: live injection vs FCM fallback
**Files:** `services/audio_session_manager.py`, `ws/voice_handler.py`, `api/internal/jobs.py`

#### Problem
When a background job completes while the phone WebSocket is still open (fast research queries,
< 2s), the backend sent a `job_update` control message and an FCM push notification — but
Gervis never spoke the result. The user had to tap the notification, leave the current
conversation, and reconnect to hear the answer. This was especially jarring for quick jobs
where the voice session was still active.

#### Root cause
The job completion path only knew two routes: control-channel message (UI update) and
FCM + resume event (for reconnect). It never injected the result back into the live Gemini
session, so Gervis had nothing to say.

#### Fix: two-path delivery

**`services/audio_session_manager.py` — `register_live_queue` / `inject_job_result`:**

Added two new methods to `AudioSessionManager`:
- `register_live_queue(conversation_id, queue)` — stores the active `LiveRequestQueue`
  while a WebSocket is streaming. Called from `voice_handler.py` immediately after the
  queue is created.
- `unregister_live_queue(conversation_id)` — clears the reference when the phone
  disconnects (called in the finally block of the WebSocket handler).
- `inject_job_result(conversation_id, spoken_summary)` — if a queue is registered,
  calls `queue.send_content()` with a system prompt that causes Gervis to speak the
  result naturally in the current conversation. Returns `True` on success, `False` when
  the session is not live (different instance or phone disconnected).

**`ws/voice_handler.py` — register / unregister the queue:**

Two one-liners added:
```python
audio_session_manager.register_live_queue(conversation_id, live_request_queue)  # after queue created
audio_session_manager.unregister_live_queue(conversation_id)  # in finally block
```

**`api/internal/jobs.py` — smart delivery routing:**

After a job completes successfully:
1. Call `audio_session_manager.inject_job_result(conversation_id, spoken_summary)`
2. If `True` → Gervis speaks immediately. Skip FCM and resume event entirely.
3. If `False` → create resume event, set conversation to `awaiting_resume`, send FCM.

For failures: always create a resume event and FCM (so the user has a persistent record),
but also try live injection so Gervis can speak the failure reason immediately if connected.

#### Multi-instance caveat
`audio_session_manager` is in-process memory. With Cloud Run multi-instance scaling, a
Cloud Tasks request may land on a different instance than the active WebSocket — in that
case `inject_job_result` returns `False` and FCM fires normally. The fast-job optimization
works reliably on the same instance (the common case when `min-instances=0` and there is
one active connection).

#### UX result
- **Fast jobs (< session timeout, same instance):** Gervis speaks immediately mid-conversation.
  No notification, no tap required.
- **Slow/disconnected jobs:** FCM push → tap → reconnect → welcome-back as before.

---

### [Phase 4.4] - FCM end-to-end confirmed + Android notification tap wiring
**Files:** Android — `FcmService.kt`, `MainActivity.kt`, `NotificationEventBus.kt`,
`SpecTalkNavGraph.kt`, `ConversationRepository.kt`, `VoiceAgentViewModel.kt`,
`AndroidManifest.xml`; Backend — `TODO.md`

FCM push notifications confirmed working end-to-end. Android side fully wired.

**Android changes:**
- `FcmService.onMessageReceived`: parses `conversation_id` from data payload, shows a
  high-priority notification (`CHANNEL_ID_RESUME`) with `PendingIntent` to `MainActivity`.
- `NotificationEventBus`: new singleton `SharedFlow` that bridges notification tap events
  to the Compose NavGraph without needing Activity references.
- `MainActivity`: added `android:launchMode="singleTop"` (required for `onNewIntent`),
  emits `conversation_id` from `onCreate` (cold start) and `onNewIntent` (warm start) to
  the bus.
- `SpecTalkNavGraph`: `LaunchedEffect` collects from `NotificationEventBus` and navigates
  to `VoiceSessionScreen` for the target conversation.
- `ConversationRepository.ackResumeEvent`: new method — `POST /conversations/{id}/ack-resume-event`.
- `VoiceAgentViewModel`: calls `ackResumeEvent` after the first `OutputTranscript` per session
  (idempotent — safe when no pending events exist). This clears the conversation badge after
  Gervis delivers the welcome-back message.

---

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

### [Phase 4.4] - Missing firebase.sdkAdminServiceAgent IAM role
**Context:** FCM push notifications silently failing in production

The `roles/firebase.sdkAdminServiceAgent` role was missing from the `gervis-backend`
service account. This role is required for `firebase_admin.messaging.send()` to work —
without it FCM calls fail silently (the notification service catches exceptions and returns
`False`).

Fix — run once, no redeploy needed (IAM takes effect in ~60 seconds):

```powershell
gcloud projects add-iam-policy-binding spectalk-488516 `
  --member="serviceAccount:gervis-backend@spectalk-488516.iam.gserviceaccount.com" `
  --role="roles/firebase.sdkAdminServiceAgent"
```

Also added to `docs/phase4-deployment.md` Step 4 so future deployments include it from
the start.

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
