from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from typing import Optional

from db.database import get_db
from db.models import Job
from middleware.auth import require_auth

import uuid

router = APIRouter()


class JobResponse(BaseModel):
    job_id: str
    conversation_id: str
    job_type: str
    status: str
    artifacts: Optional[dict]
    spoken_completion_summary: Optional[str]
    display_completion_summary: Optional[str]
    error_summary: Optional[str]
    created_at: str
    updated_at: str


@router.get("/{job_id}", response_model=JobResponse)
async def get_job(
    job_id: str,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Return a job by ID. User must own it."""
    user_id = uuid.UUID(payload["sub"])
    try:
        jid = uuid.UUID(job_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    result = await db.execute(
        select(Job).where(Job.id == jid, Job.user_id == user_id)
    )
    job = result.scalar_one_or_none()
    if job is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    return JobResponse(
        job_id=str(job.id),
        conversation_id=str(job.conversation_id),
        job_type=job.job_type,
        status=job.status,
        artifacts=job.artifacts,
        spoken_completion_summary=job.spoken_completion_summary,
        display_completion_summary=job.display_completion_summary,
        error_summary=job.error_summary,
        created_at=job.created_at.isoformat(),
        updated_at=job.updated_at.isoformat(),
    )
