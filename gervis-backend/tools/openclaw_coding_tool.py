"""OpenClaw coding job executor — real integration.

Sends the PRD to OpenClaw via POST /hooks/agent using the user's own
credentials (URL + hook token) stored encrypted in the database.

Flow:
  1. Fetch user's OpenClaw credentials from DB (decrypted in-process)
  2. POST the PRD to OpenClaw's /hooks/agent endpoint
  3. Return immediately — OpenClaw is async and will call back to
     POST /internal/openclaw/callback/{job_id} when done
  4. The job stays in "running" state until the callback arrives

Fallback: if the user has no OpenClaw credentials configured, the job
fails with a clear message instructing them to add credentials in Settings.

Real integration shape:
  POST {url}/hooks/agent
  Authorization: Bearer {token}
  Content-Type: application/json
  {
    "message": "Build the following project. When complete, POST your result to
                {BACKEND_BASE_URL}/internal/openclaw/callback/{job_id}\n\nPRD:\n{prd_json}",
    "name": "SpecTalk Coding Job",
    "timeoutSeconds": 1200
  }

Callback shape (from OpenClaw → our backend):
  POST /internal/openclaw/callback/{job_id}
  {
    "spoken_summary": "...",
    "display_summary": "...",
    "artifacts": [{"type": "github_repo", "url": "...", "label": "..."}]
  }
"""

import json
import logging

import httpx

from config import settings

logger = logging.getLogger(__name__)

# OpenClaw fires a callback — this is our maximum wait before we give up
_OPENCLAW_REQUEST_TIMEOUT = 30  # seconds (just for the initial POST, not the build)


async def execute_coding_job(
    job_id: str,
    conversation_id: str,
    user_id: str,
    payload: dict,
) -> dict:
    """Send a PRD to OpenClaw for building.

    Args:
        job_id: The Job row UUID (string). Used as part of the callback URL.
        conversation_id: The Conversation row UUID (string).
        user_id: The User row UUID (string). Used to look up OpenClaw credentials.
        payload: Dict containing "prd" key with the PRD dict from generate_prd().

    Returns:
        Dict with __async_pending=True so the executor knows NOT to mark the job
        "completed" — the OpenClaw callback will do that when the build finishes.
    """
    from api.integrations import get_decrypted_integration

    prd = payload.get("prd", {})
    project_name = prd.get("project_name", "your project")

    # ── 1. Fetch user's OpenClaw credentials ─────────────────────────────────
    credentials = await get_decrypted_integration(user_id, "openclaw")
    if credentials is None:
        raise RuntimeError(
            f"OpenClaw credentials not configured for this account. "
            "Please add your OpenClaw URL and hook token in Settings → Integrations."
        )

    openclaw_url, openclaw_token = credentials

    # ── 2. Build the callback URL and message ─────────────────────────────────
    callback_url = f"{settings.backend_base_url}/internal/openclaw/callback/{job_id}"
    prd_json = json.dumps(prd, indent=2)

    message = (
        f"Build the following project. "
        f"When complete, POST your result to {callback_url}\n\n"
        f"The result must be JSON with these fields:\n"
        f"  spoken_summary: string (1-2 natural sentences Gervis will speak to the user)\n"
        f"  display_summary: string (short line shown in the app notification)\n"
        f"  artifacts: array of objects with type, url, label fields\n\n"
        f"PRD:\n{prd_json}"
    )

    logger.info(
        f"[{conversation_id}] Sending coding job {job_id} to OpenClaw "
        f"(project='{project_name}', callback={callback_url})"
    )

    # ── 3. POST to OpenClaw (fire-and-forget — returns immediately) ───────────
    async with httpx.AsyncClient(timeout=_OPENCLAW_REQUEST_TIMEOUT) as client:
        response = await client.post(
            f"{openclaw_url.rstrip('/')}/hooks/agent",
            headers={
                "Authorization": f"Bearer {openclaw_token}",
                "Content-Type": "application/json",
            },
            json={
                "message": message,
                "name": f"SpecTalk — {project_name}",
                "timeoutSeconds": 1200,
            },
        )
        response.raise_for_status()

    logger.info(
        f"[{conversation_id}] OpenClaw accepted job {job_id} "
        f"(HTTP {response.status_code}). Awaiting callback."
    )

    # ── 4. Return async-pending sentinel ─────────────────────────────────────
    # The executor checks for __async_pending and skips the "completed" update.
    # The OpenClaw callback will mark the job completed when the build is done.
    return {
        "__async_pending": True,
        "display_summary": f"Building {project_name} via OpenClaw…",
    }
