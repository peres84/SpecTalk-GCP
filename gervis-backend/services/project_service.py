"""Per-user project registry service.

Stores project metadata (path, url, OpenClaw response_id) so they persist
across sessions. Enables "edit my project X" from any new conversation.
"""

import re
import logging
import uuid
from difflib import SequenceMatcher

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


def _tokenize(name: str) -> list[str]:
    """Split a project name into normalized alphanumeric words."""
    return [token for token in re.split(r"[^a-z0-9]+", name.lower()) if token]


def _match_score(query: str, project: UserProject) -> float:
    """Return a conservative fuzzy-match score for a stored project.

    Prefers exact slug/token matches and only falls back to approximate slug
    similarity. This avoids the old behavior where the first substring hit could
    match an unrelated project.
    """
    query_slug = _slugify(query)
    project_slug = project.slug or _slugify(project.project_name)
    query_tokens = set(_tokenize(query))
    project_tokens = set(_tokenize(project.project_name))

    if not query_slug:
        return 0.0
    if query_slug == project_slug:
        return 1.0

    score = 0.0

    if query_tokens and query_tokens == project_tokens:
        score = max(score, 0.98)
    elif len(query_tokens) > 1 and query_tokens.issubset(project_tokens):
        score = max(score, 0.93)
    elif len(project_tokens) > 1 and project_tokens.issubset(query_tokens):
        score = max(score, 0.88)

    # Allow prefix matching for natural spoken shortcuts like "hackathon nav"
    if len(query_slug) >= 6 and project_slug.startswith(query_slug):
        score = max(score, 0.86)

    # Approximate slug similarity, but only as a fallback.
    ratio = SequenceMatcher(None, query_slug, project_slug).ratio()
    if ratio >= 0.78:
        score = max(score, ratio)

    return score


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

    Tries exact slug match first, then scores all of the user's stored project
    names conservatively. Returns None when there is no strong enough match.
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

        # Fuzzy: score all user projects and choose the strongest confident match.
        result = await session.execute(
            select(UserProject)
            .where(UserProject.user_id == uuid.UUID(user_id))
            .order_by(UserProject.updated_at.desc())
        )
        all_projects = result.scalars().all()

        scored = _rank_projects(query, all_projects)

        if not scored:
            return None

        scored.sort(key=lambda item: item[0], reverse=True)
        best_score, best_project = scored[0]
        second_score = scored[1][0] if len(scored) > 1 else 0.0

        logger.info(
            "find_user_project: query=%r best=%r score=%.3f second=%.3f",
            query,
            best_project.project_name,
            best_score,
            second_score,
        )

        # Require a reasonably strong match and avoid ambiguous near-ties.
        if best_score < 0.78:
            return None
        if second_score and (best_score - second_score) < 0.05 and best_score < 0.95:
            logger.info(
                "find_user_project: query=%r ambiguous between %r and %r",
                query,
                best_project.project_name,
                scored[1][1].project_name,
            )
            return None

        return best_project


async def find_user_project_candidates(
    user_id: str,
    query: str,
    *,
    limit: int = 3,
    min_score: float = 0.65,
) -> list[UserProject]:
    """Return the strongest candidate projects for an ambiguous user query."""
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(UserProject)
            .where(UserProject.user_id == uuid.UUID(user_id))
            .order_by(UserProject.updated_at.desc())
        )
        all_projects = result.scalars().all()

    ranked = _rank_projects(query, all_projects)
    return [project for score, project in ranked if score >= min_score][:limit]


async def list_user_projects(user_id: str) -> list[UserProject]:
    """Return all projects for a user, newest first."""
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(UserProject)
            .where(UserProject.user_id == uuid.UUID(user_id))
            .order_by(UserProject.updated_at.desc())
        )
        return list(result.scalars().all())


def _rank_projects(query: str, projects: list[UserProject]) -> list[tuple[float, UserProject]]:
    """Score and sort stored projects for a spoken project query."""
    scored: list[tuple[float, UserProject]] = []
    for project in projects:
        score = _match_score(query, project)
        if score > 0:
            scored.append((score, project))
    scored.sort(key=lambda item: item[0], reverse=True)
    return scored
