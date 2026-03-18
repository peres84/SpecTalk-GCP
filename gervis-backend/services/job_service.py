"""Job service: create, update, and query background jobs.

Also enqueues tasks to Cloud Tasks when a queue is configured.
Gracefully skips Cloud Tasks in development (queue not configured).
"""

import json
import logging
import uuid

from db.database import AsyncSessionLocal
from db.models import Conversation, Job
from sqlalchemy import select

logger = logging.getLogger(__name__)


async def create_job(
    conversation_id: str,
    job_type: str,
) -> Job:
    """Create a new job in the database with status=queued.

    user_id is resolved from the conversation row to avoid passing it
    through ADK session state.
    """
    async with AsyncSessionLocal() as session:
        # Resolve user_id from the conversation
        conv_result = await session.execute(
            select(Conversation.user_id).where(Conversation.id == uuid.UUID(conversation_id))
        )
        row = conv_result.one_or_none()
        if not row:
            raise ValueError(f"Conversation {conversation_id} not found")
        user_id = row[0]

        job = Job(
            conversation_id=uuid.UUID(conversation_id),
            user_id=user_id,
            job_type=job_type,
            status="queued",
        )
        session.add(job)
        await session.commit()
        await session.refresh(job)
        logger.info(f"[{conversation_id}] Job created: {job.id} type={job_type}")
        return job


async def update_job_status(
    job_id: str,
    status: str,
    *,
    artifacts: dict | None = None,
    spoken_completion_summary: str | None = None,
    display_completion_summary: str | None = None,
    error_summary: str | None = None,
) -> Job | None:
    """Update job status and optional result fields."""
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(Job).where(Job.id == uuid.UUID(job_id))
        )
        job = result.scalar_one_or_none()
        if not job:
            logger.warning(f"update_job_status: job {job_id} not found")
            return None

        job.status = status
        if artifacts is not None:
            job.artifacts = artifacts
        if spoken_completion_summary is not None:
            job.spoken_completion_summary = spoken_completion_summary
        if display_completion_summary is not None:
            job.display_completion_summary = display_completion_summary
        if error_summary is not None:
            job.error_summary = error_summary

        await session.commit()
        await session.refresh(job)
        logger.info(f"Job {job_id} status -> {status}")
        return job


async def get_job_by_id(job_id: str) -> Job | None:
    """Fetch a job by ID (no ownership check — internal use only)."""
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(Job).where(Job.id == uuid.UUID(job_id))
        )
        return result.scalar_one_or_none()


async def enqueue_cloud_task(
    job_id: str,
    job_type: str,
    conversation_id: str,
    user_id: str,
    payload: dict | None = None,
) -> None:
    """Enqueue a Cloud Tasks HTTP task for this job.

    No-op if CLOUD_TASKS_QUEUE is not configured (development mode).
    Requires google-cloud-tasks and OIDC service account in production.
    """
    from config import settings

    if not settings.cloud_tasks_queue:
        logger.info(
            f"Cloud Tasks not configured — job {job_id} will not be auto-executed. "
            "Set CLOUD_TASKS_QUEUE to enable."
        )
        return

    try:
        from google.cloud import tasks_v2  # type: ignore[import]
        from google.protobuf import duration_pb2  # type: ignore[import]

        client = tasks_v2.CloudTasksClient()
        parent = client.queue_path(
            settings.gcp_project,
            settings.cloud_tasks_location,
            settings.cloud_tasks_queue,
        )

        task_body = json.dumps({
            "job_id": job_id,
            "job_type": job_type,
            "conversation_id": conversation_id,
            "user_id": user_id,
            "payload": payload or {},
        }).encode()

        handler_url = f"{settings.backend_base_url}/internal/jobs/execute"

        task: dict = {
            "http_request": {
                "http_method": tasks_v2.HttpMethod.POST,
                "url": handler_url,
                "headers": {"Content-Type": "application/json"},
                "body": task_body,
            },
            "dispatch_deadline": duration_pb2.Duration(seconds=3600),
        }

        # Add OIDC auth in production if service account is configured
        if settings.cloud_run_service_account:
            task["http_request"]["oidc_token"] = {
                "service_account_email": settings.cloud_run_service_account,
            }

        client.create_task(request={"parent": parent, "task": task})
        logger.info(f"Cloud Tasks task enqueued for job {job_id}")

    except ImportError:
        logger.error(
            "google-cloud-tasks not installed. Run: uv add google-cloud-tasks"
        )
    except Exception as e:
        logger.error(f"Failed to enqueue Cloud Task for job {job_id}: {e}")
