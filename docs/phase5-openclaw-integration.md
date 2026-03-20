You are the backend agent for the SpecTalk project. Your working scope is exclusively gervis-backend/ — do not read or modify any files outside that directory,
  except for the reference docs listed below. You are working in parallel with an Android UI agent that will build the confirmation UI on the Android side. At the
  end of your work you must produce a precise Android Integration Spec — the Android agent cannot ask you questions, so be exact.

  ---
  Reference Docs (read-only, outside your working directory)

  Read these before writing any code:

  - docs/architecture.md — full system design and principles
  - docs/architecture-diagram.md — ASCII diagram of all components, data flow, and data model
  - gervis-backend/CHANGELOG.md — history of all backend changes so far (read to understand what exists and avoid re-implementing)
  - TODO.md — delivery phases and current status

  ---
  Project Context

  SpecTalk is a voice-powered project creation tool. The AI assistant inside is called Gervis. Key architecture facts:

  - Android app (thin audio terminal) → streams PCM audio over WebSocket → Python backend
  - Backend (FastAPI + Google ADK) owns the Gemini Live session, all tools, all state
  - Phone never holds credentials — backend is the brain
  - Background jobs flow: Gervis calls start_background_job → Cloud Tasks → POST /internal/jobs/execute → FCM or live injection
  - Phase 5 adds coding mode: voice → clarifying questions → PRD → confirmation → coding job

  3D model work has been removed from the roadmap. Phase 6 will instead add image description via Gemini Live API (user sends an image, Gervis describes what it
  sees). Do not implement anything 3D-related. If you see three_d_model references in existing code or the orchestrator system instruction, leave them as-is — do not
   clean them up.

  ---
  Files to Read Inside gervis-backend/

  Before writing any code, read these files to understand existing patterns:

  - agents/orchestrator.py — current Gervis agent (tools, system instruction, ADK Agent shape)
  - ws/voice_handler.py — WebSocket bridge; how send_control_message is called
  - services/control_channels.py — send_control_message(conversation_id, dict) — your output channel to phone
  - services/job_service.py — how jobs are created and enqueued to Cloud Tasks
  - tools/notification_resume_tool.py — existing start_background_job ADK tool (use as shape reference)
  - tools/maps_tool.py — another ADK tool example
  - db/models.py — all ORM models including Job, PendingAction, Conversation
  - api/conversations.py — existing conversation endpoints (where to add the confirm endpoint)
  - api/internal/jobs.py — Cloud Tasks executor — _execute_job_by_type is where you wire the coding job
  - services/audio_session_manager.py — inject_job_result() for live session injection
  - config.py — settings pattern (how to add new env vars)
  - main.py — where routers are registered

  Python conventions (enforce strictly):
  - Package manager: uv only — never pip install. Use uv add <pkg> for new deps, uv sync to install.
  - Async everywhere: async def, SQLAlchemy async engine
  - All secrets via environment variables — never hardcoded
  - ADK tools are plain Python functions with type hints and clear docstrings

  ---
  What Already Exists — Do Not Rebuild

  - start_background_job ADK tool — creates DB job, enqueues Cloud Tasks, sends job_started control message to phone
  - send_control_message(conversation_id, dict) — sends JSON to phone if WebSocket is open; returns True/False
  - audio_session_manager.inject_job_result() — injects spoken text into live Gemini session
  - PendingAction DB model — for gated confirmation state (verify fields in db/models.py)
  - Job DB model — background work tracking
  - Cloud Tasks → /internal/jobs/execute → _execute_job_by_type() — job dispatch pipeline already wired
  - FCM push notifications on job complete/fail
  - awaiting_confirmation state chip already handled in Android's HomeScreen.kt (state label + color already defined)

  ---
  Phase 5 Tasks — What You Must Build

  1. agents/team_code_pr_designers/designer_agent.py — PRD Shaper

  Create agents/team_code_pr_designers/__init__.py and agents/team_code_pr_designers/designer_agent.py.

  Implement one async function:
  async def generate_prd(project_idea: str, clarifications: dict) -> dict

  Given a raw voice idea and any collected clarification answers, return a structured PRD dict:
  {
      "project_name": str,
      "description": str,          # 2-3 sentences
      "target_platform": str,      # "web" | "mobile" | "backend" | "fullstack"
      "key_features": list[str],   # 3-5 items
      "tech_stack": str,           # e.g. "React + FastAPI + PostgreSQL"
      "scope_estimate": str,       # "small" | "medium" | "large"
  }

  Use a simple ADK or google-genai completion call to generate this from the inputs. This is not a multi-turn agent — one prompt-in, structured-dict-out. Use the
  existing GEMINI_API_KEY from settings; no new credentials needed.

  2. Orchestrator — Coding Mode Tools

  Update agents/orchestrator.py to add three new ADK tools and update the system instruction.

  New tools:

  request_clarification(question: str, tool_context) -> str
  - Stores question in tool_context.state["pending_clarification"]
  - Returns the question string (Gervis will speak it naturally)
  - Gervis uses this to ask ONE clarifying question at a time (max 3 total, tracked via tool_context.state["clarification_count"])

  generate_and_confirm_prd(project_idea: str, clarifications_json: str, tool_context) -> str
  - Calls generate_prd(project_idea, json.loads(clarifications_json))
  - Stores the PRD in tool_context.state["pending_prd"]
  - Sends control message to phone:
  {
    "type": "state_update",
    "state": "awaiting_confirmation",
    "prompt": "<spoken PRD summary — 2-3 natural sentences>",
    "prd_summary": {
      "project_name": "...",
      "description": "...",
      "target_platform": "...",
      "key_features": ["...", "..."],
      "tech_stack": "...",
      "scope_estimate": "small|medium|large"
    }
  }
  - Also stores a PendingAction row in the DB with action_type="confirm_prd", payload={"prd": prd_dict}
  - Returns a spoken PRD summary string for Gervis to voice naturally

  confirm_and_dispatch(confirmed: bool, change_request: str, tool_context) -> str
  - If confirmed=True: calls start_background_job with job_type="coding" and payload={"prd": tool_context.state["pending_prd"]}, updates PendingAction to resolved,
  returns spoken confirmation
  - If confirmed=False: updates PendingAction to cancelled, clears pending_prd from state, returns instructions for Gervis to ask what to change

  Get conversation_id from tool_context.state["conversation_id"] (already set on session creation).

  System instruction additions — append to GERVIS_INSTRUCTION:
  Coding mode — CRITICAL RULES:
  - When user asks to build, create, make, or code anything: call request_clarification for up to 3 questions (one at a time). After 3 questions OR if you have
  enough info, call generate_and_confirm_prd.
  - NEVER start building without confirmation. Always call generate_and_confirm_prd first, then wait for user to say yes or no.
  - When user says yes/confirm/go ahead/sounds good → call confirm_and_dispatch(confirmed=True).
  - When user says no/change/different → call confirm_and_dispatch(confirmed=False, change_request="<what they want changed>").
  - Valid job_type for coding is "coding".

  Register all three new tools in create_gervis_agent().

  3. tools/openclaw_coding_tool.py — Coding Job Executor (Mock)

  This is invoked from _execute_job_by_type() when job_type == "coding". OpenClaw's real API is TBD — implement a realistic mock:

  async def execute_coding_job(job_id: str, conversation_id: str, payload: dict) -> dict:
      # payload["prd"] contains the PRD dict
      prd = payload.get("prd", {})
      project_name = prd.get("project_name", "your project")
      await asyncio.sleep(8)  # simulate build time
      return {
          "spoken_summary": f"Your project {project_name} has been built. I've created a GitHub repository with the starter code and project structure.",
          "display_summary": f"{project_name} — code generated successfully.",
          "artifacts": [
              {"type": "github_repo", "url": f"https://github.com/spectalk-demo/{project_name.lower().replace(' ', '-')}", "label": "GitHub Repository"}
          ],
      }

  Wire into api/internal/jobs.py in _execute_job_by_type:
  elif job_type == "coding":
      from tools.openclaw_coding_tool import execute_coding_job
      return await execute_coding_job(job_id, conversation_id, payload)

  4. POST /conversations/{id}/confirm — Out-of-voice Confirmation

  Add to api/conversations.py (or a new api/confirmations.py — your call):

  POST /conversations/{id}/confirm
  Auth: Bearer JWT
  Body: {"confirmed": true}
     or {"confirmed": false, "change_request": "make it mobile instead"}
  Response: {"status": "ok"}

  Logic:
  1. Fetch most recent PendingAction where conversation_id=id and action_type="confirm_prd" and status="pending"
  2. If none → 404
  3. If confirmed=True: create job row + enqueue Cloud Tasks with job_type="coding", payload=pending_action.payload. Update PendingAction.status = "resolved". Set
  conversation state to running_job.
  4. If confirmed=False: update PendingAction.status = "cancelled". Set conversation state to idle.

  Register the router in main.py if adding a new file.

  5. Check PendingAction Model

  Read db/models.py. Verify PendingAction has: id, conversation_id, action_type, payload (JSONB), status (default "pending"), created_at. If any field is missing,
  create an Alembic migration and run it locally.

  ---
  Control Messages the Android Agent Will Implement

  Be exact — do not change these shapes after defining them.

  Coding mode started — send when Gervis detects coding intent and calls request_clarification for the first time:
  {"type": "state_update", "state": "coding_mode"}

  PRD ready, awaiting confirmation — send from generate_and_confirm_prd:
  {
    "type": "state_update",
    "state": "awaiting_confirmation",
    "prompt": "Here's what I'll build: a task management web app using React and FastAPI. It'll have user auth, task creation, and real-time updates. Should I go
  ahead?",
    "prd_summary": {
      "project_name": "TaskFlow",
      "description": "A real-time task management web app.",
      "target_platform": "web",
      "key_features": ["User authentication", "Task CRUD", "Real-time updates"],
      "tech_stack": "React + FastAPI + PostgreSQL",
      "scope_estimate": "medium"
    }
  }

  Confirmation accepted — job dispatched — send after confirm_and_dispatch(confirmed=True), immediately followed by job_started:
  {"type": "state_update", "state": "running_job"}

  Confirmation denied — back to idle:
  {"type": "state_update", "state": "idle"}

  Already handled by Android (do not change format):
  - {"type": "job_started", "job_id": "...", "description": "..."} ✅
  - {"type": "job_update", "job_id": "...", "status": "completed|failed", "display_summary": "..."} ✅

  ---
  Verification Step

  After all code is written, run:
  cd gervis-backend
  uv run python -c "from agents.orchestrator import create_gervis_agent; print('imports OK')"
  uv run python -c "from tools.openclaw_coding_tool import execute_coding_job; print('openclaw OK')"
  uv run python -c "from agents.team_code_pr_designers.designer_agent import generate_prd; print('prd OK')"

  Fix any import errors before finishing.

  ---
  Changelog

  Add a Phase 5 section at the top of gervis-backend/CHANGELOG.md documenting every file changed, what was added, and why.

  ---
  Android Integration Spec (return this when done)

  Output a clearly labeled block called ## ANDROID INTEGRATION SPEC containing:

  1. Every control message the Android app must handle — exact JSON shapes
  2. Full request/response for POST /conversations/{id}/confirm
  3. State machine: backend state → what Android UI should show
  4. Step-by-step awaiting_confirmation UX flow: what to display, what Yes/No buttons do, how to handle out-of-voice confirmation
  5. Any changes to existing message formats


# Phase 5 — OpenClaw Integration Guide

Supplementary document for the backend agent. Read this alongside the main Phase 5 prompt.

---

## Priority: Test the PRD Workflow First

**Do not wire OpenClaw until the PRD creation flow is working end-to-end.**

The most important part of Phase 5 is the conversation quality — Gervis asking the right clarifying questions, the sub-agents doing real research to inform the PRD, and the user confirming something they actually want built. OpenClaw is just the execution step at the end.

Test this full flow first with a mock coding job (`asyncio.sleep(8)` returning a fake GitHub URL):

```
User: "build me a task management app"
  → Gervis: asks ONE clarifying question (e.g. "Who is this for — personal use or a team?")
  → User answers
  → Gervis: asks another (e.g. "Should it have mobile support or is web-only fine?")
  → User answers
  → Gervis: asks one more if needed, then calls generate_and_confirm_prd
  → Sub-agents research the right tech stack, patterns, and feasibility using google_search
  → PRD generated with informed recommendations (not generic boilerplate)
  → Gervis reads PRD summary naturally: "Here's what I'll build..."
  → Android shows awaiting_confirmation UI
  → User says "yes" → mock coding job dispatches → FCM notification → resume
```

Only move to the real OpenClaw integration once this flow feels right end-to-end.

---

## Sub-Agent Research (Critical)

The `team_code_pr_designers` sub-agent must have access to `google_search` (the ADK built-in
grounding tool). Before generating the PRD, it should autonomously research:

- Current best practices for the requested platform and type of app
- Recommended tech stacks for the scope and team size described
- Any relevant libraries, patterns, or pitfalls for the project type

This is what separates a good PRD from a generic one. A user asking for "a React app" should
get a PRD that reflects current React ecosystem knowledge (Vite vs CRA, Tanstack Query vs SWR,
etc.) — not a template filled with 2021-era assumptions.

The `generate_prd` function should pass search-grounded context into the PRD generation prompt.

---

## OpenClaw Integration — When Ready

### Architecture

```
Cloud Run (gervis-backend)          VPS (OpenClaw)
         │                                │
         │  POST /hooks/agent             │
         │  Authorization: Bearer TOKEN   │
         │  message: PRD + callback URL   │
         │ ─────────────────────────────► │
         │                                │  OpenClaw runs coding agent
         │                                │  (isolated session, google_search, etc.)
         │                                │
         │  POST /internal/openclaw/      │
         │    callback/{job_id}           │
         │ ◄───────────────────────────── │
         │  { result, artifacts }         │
         │                                │
   update job → FCM or live inject
```

**Why this shape:**
- `/hooks/agent` returns 200 immediately (async acceptance) — Cloud Run doesn't block
- Cloud Tasks job stays alive (up to 1800s deadline) waiting for the callback
- Cloud Run is already public HTTPS — OpenClaw can POST the callback directly
- No Tailscale client needed on Cloud Run side

### Exposing OpenClaw via Tailscale Funnel

OpenClaw runs on a private VPS. Cloud Run cannot reach it unless it has a public URL.
Use Tailscale Funnel to expose OpenClaw's port without opening a public firewall port:

```bash
# On the VPS — expose OpenClaw's default port
tailscale funnel 18789

# Check the public URL assigned
tailscale funnel status
# → https://your-machine.tail-xxxx.ts.net
```

This gives a stable `*.ts.net` HTTPS URL. That is your `OPENCLAW_BASE_URL`.
Cloud Run calls it over regular HTTPS. No Tailscale registration needed on Cloud Run.

### Finding OpenClaw Credentials

**Base URL** — from `tailscale funnel status` output.

**Hook token** — on the VPS:
```bash
cat ~/.openclaw/openclaw.json | grep -i token
# or
openclaw config show
# or
openclaw webhooks token    # generates/shows the hook token
```

Look for `hooks.token` or `webhooks.token` in the config. This is different from the
Gateway token (`OPENCLAW_GATEWAY_TOKEN`).

### Storing Secrets in Secret Manager

```bash
# Use printf — never echo (avoids \r corruption on Windows/PowerShell)
printf 'https://your-machine.tail-xxxx.ts.net' | \
  gcloud secrets create OPENCLAW_BASE_URL --data-file=- --project=spectalk-488516

printf 'your-hook-token-here' | \
  gcloud secrets create OPENCLAW_HOOK_TOKEN --data-file=- --project=spectalk-488516
```

Then add both to `gervis-backend/config.py` and `cloudbuild.yaml` (Secret Manager mount).

### What to Build

Two components around OpenClaw:

**1. `tools/openclaw_coding_tool.py` — fires the job**

```python
POST {OPENCLAW_BASE_URL}/hooks/agent
Authorization: Bearer {OPENCLAW_HOOK_TOKEN}
Content-Type: application/json

{
  "message": "Build the following project. When complete, POST your result to {BACKEND_BASE_URL}/internal/openclaw/callback/{job_id}\n\nPRD:\n{prd_json}",
  "name": "SpecTalk Coding Job",
  "timeoutSeconds": 1200
}
```

Returns immediately — the Cloud Tasks job stays alive waiting for the callback.

**2. `POST /internal/openclaw/callback/{job_id}` — receives the result**

New endpoint in `api/internal/`. When OpenClaw POSTs here:
- Validate the request (check a shared secret header or job_id exists in DB)
- Call `update_job_status(job_id, "completed", spoken_summary=..., artifacts=...)`
- Call `audio_session_manager.inject_job_result()` if session is still live
- Otherwise: `create_resume_event()` + `send_push_notification()`

### Callback Request Shape (from OpenClaw)

Instruct OpenClaw in the message prompt to POST back in this format:

```json
{
  "job_id": "<from the callback URL>",
  "spoken_summary": "Your TaskFlow app is ready. I've set up a React frontend with user auth, task management, and real-time updates using WebSockets.",
  "display_summary": "TaskFlow — React + FastAPI app generated successfully.",
  "artifacts": [
    {
      "type": "github_repo",
      "url": "https://github.com/...",
      "label": "GitHub Repository"
    }
  ]
}
```

---

## Implementation Order

1. ✅ Build PRD workflow (clarifying questions → research → PRD → confirm → mock job)
2. ✅ Test end-to-end with mock coding job (8s sleep → fake GitHub URL)
3. ⬜ Set up Tailscale Funnel on VPS, get `OPENCLAW_BASE_URL`
4. ⬜ Find hook token, add both secrets to Secret Manager + `config.py`
5. ⬜ Replace mock in `openclaw_coding_tool.py` with real `POST /hooks/agent` call
6. ⬜ Add `POST /internal/openclaw/callback/{job_id}` endpoint
7. ⬜ Test real OpenClaw coding job end-to-end
