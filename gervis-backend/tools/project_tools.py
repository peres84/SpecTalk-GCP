"""Project lookup tool for Gervis.

Allows Gervis to look up a user's existing projects by name across sessions.
Used when the user says "edit my project X" or "update my X app".
"""

import logging
from typing import Any

import opik
from google.adk.tools import ToolContext

logger = logging.getLogger(__name__)


@opik.track(name="lookup_project", project_name="gervis",
            capture_input=True, capture_output=True,
            ignore_arguments=["tool_context"])
async def lookup_project(
    project_name: str,
    tool_context: ToolContext,
) -> dict[str, Any]:
    """Look up an existing project by name from the user's project history.

    Use this when the user says "edit my project X", "update my X app",
    "continue working on X", or refers to a project by name that might
    have been built in a previous session.

    Args:
        project_name: The project name as spoken by the user (e.g. "langdrill",
                      "LangDrill", "lang drill app"). Fuzzy matching is applied.

    Returns:
        Dict with:
          - found (bool): whether a matching project was found
          - project_name (str): canonical stored name
          - path (str|None): absolute path on the VPS
          - url (str|None): live HTTP URL if deployed
          - has_openclaw_context (bool): whether OpenClaw has prior session context
          - all_projects (list): names of all user projects (only when found=False)
    """
    from services.project_service import find_user_project, list_user_projects

    state = tool_context.state if tool_context else {}
    conversation_id: str = state.get("conversation_id", "")
    user_id: str = state.get("user_id", "")

    if conversation_id:
        try:
            opik.update_current_trace(thread_id=conversation_id)
        except Exception:
            pass

    if not user_id:
        # Fall back: resolve user_id from conversation
        try:
            from db.database import AsyncSessionLocal
            from db.models import Conversation
            from sqlalchemy import select
            import uuid
            async with AsyncSessionLocal() as db:
                result = await db.execute(
                    select(Conversation.user_id).where(
                        Conversation.id == uuid.UUID(conversation_id)
                    )
                )
                row = result.one_or_none()
                if row:
                    user_id = str(row[0])
        except Exception as e:
            logger.warning(f"lookup_project: could not resolve user_id: {e}")

    if not user_id:
        return {"found": False, "error": "Could not identify user", "all_projects": []}

    project = await find_user_project(user_id, project_name)

    if project:
        state["selected_project"] = {
            "project_name": project.project_name,
            "path": project.path,
            "url": project.url,
            "last_openclaw_response_id": project.last_openclaw_response_id,
        }
        logger.info(f"lookup_project: found '{project.project_name}' for user {user_id}")
        return {
            "found": True,
            "project_name": project.project_name,
            "path": project.path,
            "url": project.url,
            "has_openclaw_context": project.last_openclaw_response_id is not None,
        }
    else:
        state.pop("selected_project", None)
        all_projects = await list_user_projects(user_id)
        names = [p.project_name for p in all_projects]
        logger.info(f"lookup_project: '{project_name}' not found for user {user_id}. Existing: {names}")
        return {
            "found": False,
            "all_projects": names,
        }
