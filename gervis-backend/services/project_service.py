"""Per-user project registry service.

Stores project metadata (path, url, OpenClaw response_id) so they persist
across sessions. Enables "edit my project X" from any new conversation.
"""

import re
import logging
import uuid

from db.database import AsyncSessionLocal
from db.models import UserProject
from sqlalchemy import select

logger = logging.getLogger(__name__)


def _slugify(name: str) -> str:
    """Normalize a project name to a slug for voice-query matching.

    "LangDrill" → "langdrill"
    "lang drill" → "langdrill"
    "Lang_Drill" → "langdrill"
    "My Cool App!" → "mycoolapp"
    """
    return re.sub(r'[^a-z0-9]', '', name.lower())


async def upsert_user_project(
    user_id: str,
    project_name: str,
    job_id: str,
    *,
    path: str | None = None,
    url: str | None = None,
    last_openclaw_response_id: str | None = None,
) -> UserProject:
    """Create or update a UserProject entry after a successful coding job."""
    slug = _slugify(project_name)
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(UserProject).where(
                UserProject.user_id == uuid.UUID(user_id),
                UserProject.slug == slug,
            )
        )
        project = result.scalar_one_or_none()

        if project:
            # Update existing — only overwrite fields that have new values
            project.project_name = project_name  # keep latest casing
            if path:
                project.path = path
            if url:
                project.url = url
            if last_openclaw_response_id:
                project.last_openclaw_response_id = last_openclaw_response_id
            project.last_job_id = uuid.UUID(job_id)
        else:
            project = UserProject(
                user_id=uuid.UUID(user_id),
                project_name=project_name,
                slug=slug,
                path=path,
                url=url,
                last_openclaw_response_id=last_openclaw_response_id,
                last_job_id=uuid.UUID(job_id),
            )
            session.add(project)

        await session.commit()
        await session.refresh(project)
        logger.info(f"UserProject upserted: user={user_id} slug={slug} path={path} url={url}")
        return project


async def find_user_project(user_id: str, query: str) -> UserProject | None:
    """Find a user's project by name (fuzzy slug match).

    Tries exact slug match first, then checks if the query slug is contained
    in any stored slug or vice-versa.
    """
    query_slug = _slugify(query)
    async with AsyncSessionLocal() as session:
        # Exact slug match
        result = await session.execute(
            select(UserProject).where(
                UserProject.user_id == uuid.UUID(user_id),
                UserProject.slug == query_slug,
            )
        )
        project = result.scalar_one_or_none()
        if project:
            return project

        # Fuzzy: load all user projects and find best substring match
        result = await session.execute(
            select(UserProject).where(UserProject.user_id == uuid.UUID(user_id))
        )
        all_projects = result.scalars().all()
        for p in all_projects:
            if query_slug in p.slug or p.slug in query_slug:
                return p

        return None


async def list_user_projects(user_id: str) -> list[UserProject]:
    """Return all projects for a user, newest first."""
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(UserProject)
            .where(UserProject.user_id == uuid.UUID(user_id))
            .order_by(UserProject.updated_at.desc())
        )
        return list(result.scalars().all())
