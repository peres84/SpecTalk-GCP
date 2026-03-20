"""OpenClaw coding job executor — OpenResponses API (POST /v1/responses, SSE).

Flow:
  1. Fetch user's OpenClaw credentials from DB (decrypted in-process)
  2. Look up the last OpenClaw response_id for this conversation (for context chaining)
  3. POST the PRD to POST /v1/responses with stream=True
     - If a previous_response_id exists, pass it so OpenClaw remembers prior context
  4. Consume SSE stream, collecting response.output_text.delta events
  5. Capture the response_id from response.completed and persist it on the job row
  6. Return assembled result when streaming ends

Context chaining: each completed job stores its OpenClaw response_id in job.artifacts.
The next coding job for the same conversation looks up the most recent response_id and
passes it as previous_response_id, giving OpenClaw full context of prior work.
"""

import json
import logging

import httpx

logger = logging.getLogger(__name__)

# Total time budget for a coding job (OpenClaw may take several minutes)
_OPENCLAW_TIMEOUT = 1800  # 30 minutes


async def _get_last_response_id(conversation_id: str) -> str | None:
    """Return the most recent OpenClaw response_id for this conversation, if any."""
    from sqlalchemy import select, desc
    from db.database import AsyncSessionLocal
    from db.models import Job

    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(Job)
            .where(Job.conversation_id == conversation_id)
            .where(Job.job_type == "coding")
            .where(Job.status == "completed")
            .order_by(desc(Job.created_at))
            .limit(1)
        )
        job = result.scalar_one_or_none()
        if job and job.artifacts:
            for artifact in job.artifacts:
                if artifact.get("type") == "openclaw_response_id":
                    return artifact.get("url")
    return None


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
    session_key = f"spectalk-{conversation_id}"  # per-conversation, not per-job

    # ── 2. Look up prior context ──────────────────────────────────────────────
    previous_response_id = await _get_last_response_id(conversation_id)
    if previous_response_id:
        logger.info(
            f"[{conversation_id}] Chaining from previous OpenClaw response: {previous_response_id}"
        )

    prd_json = json.dumps(prd, indent=2)
    prompt = (
        f"Build the following project according to this PRD.\n\n"
        f"PRD:\n{prd_json}\n\n"
        f"IMPORTANT — when the build is complete, do the following:\n"
        f"1. Create the project inside a folder named after the project (use snake_case).\n"
        f"2. Deploy it using nginx so it is accessible over HTTP.\n"
        f"3. Reply with ONLY these two lines (no extra explanation):\n"
        f"   PATH: <exact absolute path to the project folder>\n"
        f"   URL: <http URL where the project is accessible>\n"
        f"   If nginx deployment failed, omit the URL line and only include PATH."
    )

    # ── 3. Build request body ─────────────────────────────────────────────────
    request_body: dict = {
        "model": "openclaw",
        "input": prompt,
        "stream": True,
    }
    if previous_response_id:
        request_body["previous_response_id"] = previous_response_id

    logger.info(
        f"[{conversation_id}] Sending coding job {job_id} to OpenClaw "
        f"(project='{project_name}', session_key={session_key}, "
        f"chained={previous_response_id is not None})"
    )

    # ── 4. Stream POST /v1/responses ──────────────────────────────────────────
    full_text = ""
    openclaw_response_id = None

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
            json=request_body,
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

                elif event_type == "response.completed":
                    openclaw_response_id = event.get("response", {}).get("id")

                elif event_type == "error":
                    error_msg = event.get("message", "Unknown OpenClaw error")
                    raise RuntimeError(f"OpenClaw error: {error_msg}")

    logger.info(
        f"[{conversation_id}] OpenClaw job {job_id} complete "
        f"({len(full_text)} chars, response_id={openclaw_response_id})"
    )

    # ── 5. Parse PATH and URL from response ───────────────────────────────────
    project_path = None
    project_url = None

    for line in full_text.splitlines():
        line = line.strip()
        if line.startswith("PATH:"):
            project_path = line[5:].strip()
        elif line.startswith("URL:"):
            project_url = line[4:].strip()

    logger.info(
        f"[{conversation_id}] OpenClaw job {job_id} — "
        f"path={project_path} url={project_url}"
    )

    # ── 6. Build result ───────────────────────────────────────────────────────
    artifacts = []
    if openclaw_response_id:
        # Store response_id so the next job can chain from it
        artifacts.append({"type": "openclaw_response_id", "url": openclaw_response_id, "label": "OpenClaw response ID"})
    if project_url:
        artifacts.append({"type": "url", "url": project_url, "label": f"{project_name} (live)"})
    if project_path:
        artifacts.append({"type": "path", "url": project_path, "label": f"{project_name} (path)"})

    if project_url:
        spoken = f"Your {project_name} project is ready and live at {project_url}"
        display = f"{project_name} deployed — {project_url}"
    elif project_path:
        spoken = f"Your {project_name} project has been built. The files are at {project_path}"
        display = f"{project_name} built — {project_path}"
    else:
        spoken = f"Your {project_name} project has been built by OpenClaw."
        display = f"OpenClaw finished building {project_name}."

    return {
        "spoken_summary": spoken,
        "display_summary": display,
        "artifacts": artifacts,
    }
