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
