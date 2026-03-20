"""OpenClaw coding job executor — OpenResponses API (POST /v1/responses, SSE).

Flow:
  1. Fetch user's OpenClaw credentials from DB (decrypted in-process)
  2. POST the PRD to POST /v1/responses with stream=True
  3. Consume SSE stream, collecting response.output_text.delta events
  4. Return assembled result when response.completed arrives

The job is synchronous from the executor's perspective — we wait for OpenClaw
to finish streaming and return the result directly. No callback needed.
"""

import json
import logging

import httpx

logger = logging.getLogger(__name__)

# Total time budget for a coding job (OpenClaw may take several minutes)
_OPENCLAW_TIMEOUT = 1800  # 30 minutes


async def execute_coding_job(
    job_id: str,
    conversation_id: str,
    user_id: str,
    payload: dict,
) -> dict:
    """Send a PRD to OpenClaw and stream the result back.

    Args:
        job_id: The Job row UUID (string). Used as deterministic session key.
        conversation_id: The Conversation row UUID (string).
        user_id: The User row UUID (string). Used to look up OpenClaw credentials.
        payload: Dict containing "prd" key with the PRD dict from generate_prd().

    Returns:
        Dict with spoken_summary, display_summary, and optional artifacts.
    """
    from api.integrations import get_decrypted_integration

    prd = payload.get("prd", {})
    project_name = prd.get("project_name", "your project")

    # ── 1. Fetch user's OpenClaw credentials ─────────────────────────────────
    credentials = await get_decrypted_integration(user_id, "openclaw")
    if credentials is None:
        raise RuntimeError(
            "OpenClaw credentials not configured for this account. "
            "Please add your OpenClaw URL and token in Settings -> Integrations."
        )

    openclaw_url, openclaw_token = credentials
    endpoint = f"{openclaw_url.rstrip('/')}/v1/responses"
    session_key = f"spectalk-{job_id}"

    prd_json = json.dumps(prd, indent=2)
    prompt = (
        f"Build the following project according to this PRD.\n\n"
        f"PRD:\n{prd_json}\n\n"
        f"When done, provide a brief summary of what was built."
    )

    logger.info(
        f"[{conversation_id}] Sending coding job {job_id} to OpenClaw "
        f"(project='{project_name}', session_key={session_key})"
    )

    # ── 2. Stream POST /v1/responses ──────────────────────────────────────────
    full_text = ""

    async with httpx.AsyncClient(timeout=_OPENCLAW_TIMEOUT) as client:
        async with client.stream(
            "POST",
            endpoint,
            headers={
                "Authorization": f"Bearer {openclaw_token}",
                "Content-Type": "application/json",
                "x-openclaw-agent-id": "main",
                "x-openclaw-session-key": session_key,
            },
            json={
                "model": "openclaw",
                "input": prompt,
                "stream": True,
            },
        ) as resp:
            resp.raise_for_status()

            async for line in resp.aiter_lines():
                if not line.startswith("data:"):
                    continue
                raw = line[5:].strip()
                if not raw or raw == "[DONE]":
                    continue
                try:
                    event = json.loads(raw)
                except json.JSONDecodeError:
                    continue

                event_type = event.get("type", "")

                if event_type == "response.output_text.delta":
                    full_text += event.get("delta", "")

                elif event_type == "error":
                    error_msg = event.get("message", "Unknown OpenClaw error")
                    raise RuntimeError(f"OpenClaw error: {error_msg}")

    logger.info(
        f"[{conversation_id}] OpenClaw job {job_id} complete "
        f"({len(full_text)} chars)"
    )

    # ── 3. Build result ───────────────────────────────────────────────────────
    # Trim to a reasonable spoken length
    spoken = full_text.strip()
    if len(spoken) > 500:
        spoken = spoken[:497] + "..."

    return {
        "spoken_summary": spoken or f"Your {project_name} project has been built by OpenClaw.",
        "display_summary": f"OpenClaw finished building {project_name}.",
        "artifacts": [],
    }
