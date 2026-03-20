"""OpenClaw coding job executor — Phase 5.

Invoked from api/internal/jobs.py when job_type == "coding".
The real OpenClaw API integration is documented in docs/phase5-openclaw-integration.md.

Current state: realistic mock that simulates build time (8s) and returns a
plausible GitHub repo URL. Replace the body of execute_coding_job with the
real POST /hooks/agent call once Tailscale Funnel is set up.

Real integration shape (when ready):
  POST {OPENCLAW_BASE_URL}/hooks/agent
  Authorization: Bearer {OPENCLAW_HOOK_TOKEN}
  { "message": "Build ...\n\nPRD:\n{prd_json}", "name": "...", "timeoutSeconds": 1200 }

  OpenClaw calls back to:
  POST {BACKEND_BASE_URL}/internal/openclaw/callback/{job_id}
  { "spoken_summary": "...", "display_summary": "...", "artifacts": [...] }
"""

import asyncio
import logging

logger = logging.getLogger(__name__)


async def execute_coding_job(job_id: str, conversation_id: str, payload: dict) -> dict:
    """Execute a coding job for the given PRD.

    Args:
        job_id: The Job row UUID (string).
        conversation_id: The Conversation row UUID (string).
        payload: Dict containing "prd" key with the PRD dict from generate_prd().

    Returns:
        Dict with spoken_summary, display_summary, and artifacts.
    """
    prd = payload.get("prd", {})
    project_name = prd.get("project_name", "your project")
    tech_stack = prd.get("tech_stack", "modern stack")
    platform = prd.get("target_platform", "web")

    logger.info(
        f"[{conversation_id}] OpenClaw coding job {job_id}: building '{project_name}' "
        f"({platform}, {tech_stack})"
    )

    # Simulate build time — replace with real OpenClaw POST when ready
    await asyncio.sleep(8)

    repo_slug = project_name.lower().replace(" ", "-").replace("_", "-")
    # Strip characters that are not URL-safe
    safe_slug = "".join(c for c in repo_slug if c.isalnum() or c == "-")

    github_url = f"https://github.com/spectalk-demo/{safe_slug}"

    return {
        "spoken_summary": (
            f"Your project {project_name} has been built. "
            f"I've created a GitHub repository with the starter code, "
            f"project structure, and documentation. "
            f"It's ready for you to clone and start developing."
        ),
        "display_summary": f"{project_name} — code generated successfully.",
        "artifacts": [
            {
                "type": "github_repo",
                "url": github_url,
                "label": "GitHub Repository",
            }
        ],
    }
