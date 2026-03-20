"""OpenClaw coding job executor using POST /v1/responses with SSE streaming.

Flow:
  1. Fetch the user's OpenClaw credentials from DB
  2. Look up prior OpenClaw context for this project, when available
  3. POST the PRD to /v1/responses with stream=True
  4. Consume SSE stream and collect response.output_text.delta events
  5. Capture the response_id from response.completed and persist it on the job row
  6. Return assembled result when streaming ends

Context chaining: each completed job stores its OpenClaw response_id in job.artifacts.
The next coding job for the same project can pass that value as previous_response_id
so OpenClaw keeps the existing coding context.
"""

import json
import logging
import re
from urllib.parse import urlparse, urlunparse

import httpx
import opik

from config import settings

logger = logging.getLogger(__name__)

_OPENCLAW_TIMEOUT = 1800  # 30 minutes


def _normalize_network_host(raw: str | None) -> str | None:
    value = (raw or "").strip()
    if not value:
        return None
    if "://" not in value:
        value = f"http://{value}"
    parsed = urlparse(value)
    if not parsed.hostname:
        return None
    return urlunparse((parsed.scheme or "http", parsed.netloc, "", "", "", ""))


def _rewrite_project_url(project_url: str | None, preferred_network_host: str | None) -> str | None:
    if not project_url:
        return project_url

    normalized_host = _normalize_network_host(preferred_network_host)
    if not normalized_host:
        return project_url

    raw_project_url = project_url.strip()
    current = urlparse(raw_project_url if "://" in raw_project_url else f"http://{raw_project_url}")
    preferred = urlparse(normalized_host)
    if not preferred.hostname:
        return project_url

    port = current.port or preferred.port
    if port is None:
        match = re.search(r":(\d{2,5})(?:/|$)", raw_project_url) or re.search(
            r"\bport\s+(\d{2,5})\b",
            raw_project_url,
            re.IGNORECASE,
        )
        if match:
            port = int(match.group(1))

    netloc = preferred.hostname
    if port:
        netloc = f"{netloc}:{port}"

    return urlunparse((
        preferred.scheme or current.scheme or "http",
        netloc,
        "/",
        "",
        "",
        "",
    ))


def _connection_help_message(openclaw_url: str) -> str:
    host = urlparse(openclaw_url).hostname or openclaw_url
    return (
        "Could not reach your OpenClaw server from the backend. "
        f"The saved OpenClaw URL '{host}' must be directly reachable from Cloud Run. "
        "Do not use localhost, 127.0.0.1, or a private Tailscale-only 100.x address here. "
        "Use a public HTTPS URL for OpenClaw, such as Tailscale Funnel, Cloudflare Tunnel, "
        "ngrok, or another public endpoint. "
        "Keep your Tailscale 100.x host in Project Links only for the returned app URL."
    )


async def _get_last_response_id(conversation_id: str, user_id: str, project_name: str) -> str | None:
    """Return the most recent OpenClaw response_id for this project."""
    from services.project_service import find_user_project

    if user_id and project_name:
        project = await find_user_project(user_id, project_name)
        if project and project.last_openclaw_response_id:
            return project.last_openclaw_response_id

    from sqlalchemy import desc, select
    from db.database import AsyncSessionLocal
    from db.models import Job
    import uuid

    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(Job)
            .where(Job.conversation_id == uuid.UUID(conversation_id))
            .where(Job.job_type == "coding")
            .where(Job.status == "completed")
            .order_by(desc(Job.created_at))
            .limit(1)
        )
        job = result.scalar_one_or_none()
        if job and job.artifacts:
            for artifact in job.artifacts if isinstance(job.artifacts, list) else []:
                if artifact.get("type") == "openclaw_response_id":
                    return artifact.get("url")
    return None


@opik.track(
    name="execute_coding_job",
    project_name="gervis",
    capture_input=True,
    capture_output=True,
)
async def execute_coding_job(
    job_id: str,
    conversation_id: str,
    user_id: str,
    payload: dict,
) -> dict:
    """Send a PRD to OpenClaw and stream the result back."""
    try:
        opik.update_current_trace(thread_id=conversation_id)
    except Exception:
        pass

    from api.integrations import get_decrypted_integration

    prd = payload.get("prd", {})
    existing_project = payload.get("existing_project") or {}
    existing_project_name = existing_project.get("project_name")
    existing_project_path = existing_project.get("path")
    existing_project_url = existing_project.get("url")
    existing_response_id = existing_project.get("last_openclaw_response_id")
    preferred_network_host = payload.get("preferred_network_host")
    project_name = existing_project_name or prd.get("project_name", "your project")

    credentials = await get_decrypted_integration(user_id, "openclaw")
    if credentials is None:
        raise RuntimeError(
            "OpenClaw credentials not configured for this account. "
            "Please add your OpenClaw URL and token in Settings -> Integrations."
        )

    openclaw_url, openclaw_token = credentials
    endpoint = f"{openclaw_url.rstrip('/')}/v1/responses"
    session_key = f"spectalk-{conversation_id}"

    previous_response_id = existing_response_id or await _get_last_response_id(
        conversation_id,
        user_id,
        project_name,
    )
    if previous_response_id:
        logger.info(
            f"[{conversation_id}] Chaining from previous OpenClaw response: {previous_response_id}"
        )

    prd_json = json.dumps(prd, indent=2)
    if existing_project_path:
        prompt = (
            f"Update the existing project according to this PRD.\n\n"
            f"Existing project name: {project_name}\n"
            f"Existing project path: {existing_project_path}\n"
            f"Existing project URL: {existing_project_url or 'unknown'}\n\n"
            f"PRD:\n{prd_json}\n\n"
            f"IMPORTANT: this is an edit to an existing project.\n"
            f"1. Reuse the exact existing project folder at '{existing_project_path}'.\n"
            f"2. Do NOT create a new project directory or a new slugged folder.\n"
            f"3. Apply the requested changes inside the existing workspace.\n"
            f"4. Inside that project folder, run 'npm install' first.\n"
            f"5. Then run the app with 'npm run dev -- --host 0.0.0.0 --port 5173'.\n"
            f"6. If port 5173 is already in use, choose another available port and use that instead.\n"
            f"7. For website or landing-page visuals, use Gemini Nano Banana or ChatGPT image "
            f"generation/editing tools when needed to create polished custom visuals instead of "
            f"shipping with bland placeholders.\n"
            f"8. The user wants the shared URL to use this host/domain when possible: "
            f"{preferred_network_host or 'use the machine host you actually start on'}.\n"
            f"9. Reply with ONLY these two lines (no extra explanation):\n"
            f"   PATH: <exact absolute path to the project folder>\n"
            f"   URL: <exact URL where the running app is reachable>\n"
            f"   If the app is still using the same URL, return the current URL.\n"
            f"   If deployment failed, omit the URL line and only include PATH."
        )
    else:
        prompt = (
            f"Build the following project according to this PRD.\n\n"
            f"PRD:\n{prd_json}\n\n"
            f"IMPORTANT: when the build is complete, do the following:\n"
            f"1. Create the project inside '{settings.openclaw_projects_dir}/<project_slug>/' "
            f"(use snake_case for the folder name, e.g. lang_drill for 'LangDrill').\n"
            f"   Create the parent directory if it does not exist.\n"
            f"2. Inside the project folder, run 'npm install' first.\n"
            f"3. Then run the app with 'npm run dev -- --host 0.0.0.0 --port 5173'.\n"
            f"4. If port 5173 is already in use, choose another available port and use that instead.\n"
            f"5. For website or landing-page visuals, use Gemini Nano Banana or ChatGPT image "
            f"generation/editing tools when needed to create polished custom visuals instead of "
            f"shipping with bland placeholders.\n"
            f"6. The user wants the shared URL to use this host/domain when possible: "
            f"{preferred_network_host or 'use the machine host you actually start on'}.\n"
            f"7. Reply with ONLY these two lines (no extra explanation):\n"
            f"   PATH: <exact absolute path to the project folder>\n"
            f"   URL: <exact URL where the running app is reachable>\n"
            f"   If nginx deployment failed, omit the URL line and only include PATH."
        )

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
        f"chained={previous_response_id is not None}, "
        f"existing_path={existing_project_path!r})"
    )

    full_text = ""
    openclaw_response_id = None

    try:
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
    except httpx.ConnectError as exc:
        logger.error(
            f"[{conversation_id}] Could not connect to OpenClaw at {openclaw_url}: {exc}"
        )
        raise RuntimeError(_connection_help_message(openclaw_url)) from exc
    except httpx.ConnectTimeout as exc:
        logger.error(
            f"[{conversation_id}] Timed out connecting to OpenClaw at {openclaw_url}: {exc}"
        )
        raise RuntimeError(_connection_help_message(openclaw_url)) from exc
    except httpx.HTTPStatusError as exc:
        body_preview = exc.response.text[:300] if exc.response is not None else ""
        logger.error(
            f"[{conversation_id}] OpenClaw HTTP error {exc.response.status_code} "
            f"from {openclaw_url}: {body_preview}"
        )
        raise RuntimeError(
            f"OpenClaw returned HTTP {exc.response.status_code}. "
            "Please verify the OpenClaw base URL and hook token in Settings -> Integrations."
        ) from exc

    logger.info(
        f"[{conversation_id}] OpenClaw job {job_id} complete "
        f"({len(full_text)} chars, response_id={openclaw_response_id})"
    )

    project_path = existing_project_path
    project_url = existing_project_url
    for line in full_text.splitlines():
        line = line.strip()
        if line.startswith("PATH:"):
            project_path = line[5:].strip()
        elif line.startswith("URL:"):
            project_url = line[4:].strip()

    project_url = _rewrite_project_url(project_url, preferred_network_host)

    logger.info(
        f"[{conversation_id}] OpenClaw job {job_id} result "
        f"path={project_path} url={project_url}"
    )

    artifacts = []
    if openclaw_response_id:
        artifacts.append(
            {
                "type": "openclaw_response_id",
                "url": openclaw_response_id,
                "label": "OpenClaw response ID",
            }
        )
    if project_url:
        artifacts.append({"type": "url", "url": project_url, "label": f"{project_name} (live)"})
    if project_path:
        artifacts.append({"type": "path", "url": project_path, "label": f"{project_name} (path)"})

    if project_url:
        spoken = f"Your {project_name} project is ready and live at {project_url}"
        display = f"{project_name} deployed - {project_url}"
    elif project_path:
        spoken = f"Your {project_name} project has been built. The files are at {project_path}"
        display = f"{project_name} built - {project_path}"
    else:
        spoken = f"Your {project_name} project has been built by OpenClaw."
        display = f"OpenClaw finished building {project_name}."

    try:
        from services.project_service import upsert_user_project

        await upsert_user_project(
            user_id=user_id,
            project_name=project_name,
            job_id=job_id,
            path=project_path,
            url=project_url,
            last_openclaw_response_id=openclaw_response_id,
        )
    except Exception as e:
        logger.warning(f"[{conversation_id}] Failed to upsert UserProject: {e}")

    return {
        "spoken_summary": spoken,
        "display_summary": display,
        "artifacts": artifacts,
    }
