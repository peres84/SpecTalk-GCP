"""Background job tool for Gervis.

Allows the orchestrator to start a background job and immediately return
a spoken acknowledgment to the user, without blocking the voice session.

The job is enqueued to Cloud Tasks and processed asynchronously.
A push notification is sent when the job completes.
"""

import asyncio
import logging
from typing import Any

import opik
from google.adk.tools import ToolContext

logger = logging.getLogger(__name__)


@opik.track(name="start_background_job", project_name="gervis",
            capture_input=True, capture_output=True,
            ignore_arguments=["tool_context"])
async def start_background_job(
    job_type: str,
    description: str,
    spoken_ack: str,
    tool_context: ToolContext,
) -> dict[str, Any]:
    """Start a background job and return immediately with a spoken acknowledgment.

    Use this when a task will take more than a few seconds — for example,
    generating code, creating a project, or running extended research.
    The user can leave and will be notified when the job completes.

    Args:
        job_type: Type of job, e.g. "coding", "research", "three_d_model", "demo"
        description: Short description of what the job will do
        spoken_ack: Natural spoken acknowledgment to say to the user right now,
                    e.g. "I've started building your app. I'll let you know when it's ready."

    Returns:
        Dict with job_id, spoken_ack for the agent to speak, and status.
    """
    from services.job_service import create_job, enqueue_cloud_task
    from services.control_channels import send_control_message
    from services.conversation_service import set_conversation_state

    state = tool_context.state if tool_context else {}
    conversation_id: str | None = state.get("conversation_id")

    if not conversation_id:
        logger.warning("start_background_job: no conversation_id in session state")
        return {
            "error": "Session context unavailable",
            "spoken_ack": "I wasn't able to start the job. Please try again.",
        }

    try:
        job = await create_job(
            conversation_id=conversation_id,
            job_type=job_type,
        )
        job_id = str(job.id)
        user_id = str(job.user_id)

        # Send job_started control message to the phone (best-effort, non-blocking)
        asyncio.create_task(
            send_control_message(conversation_id, {
                "type": "job_started",
                "job_id": job_id,
                "spoken_ack": spoken_ack,
            })
        )

        # Update conversation state to running_job (non-blocking)
        asyncio.create_task(set_conversation_state(conversation_id, "running_job"))

        # Enqueue to Cloud Tasks — no-op in dev if CLOUD_TASKS_QUEUE not set
        asyncio.create_task(
            enqueue_cloud_task(
                job_id=job_id,
                job_type=job_type,
                conversation_id=conversation_id,
                user_id=user_id,
                payload={"description": description},
            )
        )

        logger.info(
            f"[{conversation_id}] Background job started: {job_id} type={job_type}"
        )
        return {
            "job_id": job_id,
            "spoken_ack": spoken_ack,
            "status": "queued",
        }

    except Exception as e:
        logger.error(f"[{conversation_id}] Failed to start background job: {e}")
        return {
            "error": str(e),
            "spoken_ack": "I had trouble starting that job. Please try again.",
        }
