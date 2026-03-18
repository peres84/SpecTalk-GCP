"""Internal job execution endpoint for Cloud Tasks.

POST /internal/jobs/execute

This endpoint is NOT exposed to the internet. In production, Cloud Run is
configured so that /internal/* routes only accept requests bearing a valid
Cloud Tasks OIDC token (validated via the X-CloudTasks-QueueName header check).

In development, calls without the Cloud Tasks header are allowed so you can
test job completion manually with curl or a script.

Usage (local testing):
    curl -X POST http://localhost:8080/internal/jobs/execute \\
         -H "Content-Type: application/json" \\
         -d '{"job_id":"<uuid>","job_type":"demo","conversation_id":"<uuid>","user_id":"<uuid>"}'
"""

import asyncio
import logging

from fastapi import APIRouter, Header, HTTPException, status
from pydantic import BaseModel

logger = logging.getLogger(__name__)

router = APIRouter()


class JobExecuteRequest(BaseModel):
    job_id: str
    job_type: str
    conversation_id: str
    user_id: str
    payload: dict | None = None


@router.post("/jobs/execute", status_code=status.HTTP_200_OK)
async def execute_job(
    body: JobExecuteRequest,
    x_cloudtasks_queuename: str | None = Header(default=None),
    x_cloudtasks_taskname: str | None = Header(default=None),
):
    """Execute a background job dispatched by Cloud Tasks.

    Updates job status, creates a resume event, and sends an FCM push
    notification when the job completes or fails.
    """
    from config import settings

    # In production, only Cloud Tasks may call this endpoint
    if settings.environment == "production" and not x_cloudtasks_queuename:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only callable from Cloud Tasks",
        )

    from services.job_service import update_job_status
    from services.notification_service import send_push_notification, get_user_push_token
    from services.resume_event_service import create_resume_event
    from services.control_channels import send_control_message
    from services.conversation_service import set_conversation_state

    job_id = body.job_id
    conversation_id = body.conversation_id
    user_id = body.user_id

    logger.info(
        f"[{conversation_id}] Executing job {job_id} type={body.job_type}"
    )

    # Mark job as running and notify phone if connected
    await update_job_status(job_id, "running")
    await send_control_message(conversation_id, {
        "type": "job_update",
        "job_id": job_id,
        "status": "running",
    })

    try:
        result = await _execute_job_by_type(
            job_type=body.job_type,
            job_id=job_id,
            conversation_id=conversation_id,
            user_id=user_id,
            payload=body.payload or {},
        )

        spoken_summary = result.get(
            "spoken_summary",
            f"Your {body.job_type} job has completed.",
        )
        display_summary = result.get(
            "display_summary",
            f"{body.job_type.capitalize()} job completed successfully.",
        )
        artifacts = result.get("artifacts")

        await update_job_status(
            job_id,
            "completed",
            spoken_completion_summary=spoken_summary,
            display_completion_summary=display_summary,
            artifacts=artifacts,
        )

        # Create resume event — this is what the welcome-back flow reads
        resume_event = await create_resume_event(
            conversation_id=conversation_id,
            event_type="job_completed",
            job_id=job_id,
            spoken_summary=spoken_summary,
            display_summary=display_summary,
            artifacts=artifacts,
        )

        # Transition conversation to awaiting_resume
        await set_conversation_state(conversation_id, "awaiting_resume")

        # Notify the phone if still connected
        await send_control_message(conversation_id, {
            "type": "job_update",
            "job_id": job_id,
            "status": "completed",
            "display_summary": display_summary,
        })

        # FCM push notification
        push_token = await get_user_push_token(user_id)
        if push_token:
            await send_push_notification(
                push_token=push_token,
                title="SpecTalk — Job Complete",
                body=display_summary,
                conversation_id=conversation_id,
                data={
                    "job_id": job_id,
                    "resume_event_id": str(resume_event.id),
                    "event_type": "job_completed",
                },
            )

        logger.info(f"[{conversation_id}] Job {job_id} completed successfully")
        return {"status": "ok", "job_id": job_id}

    except Exception as e:
        logger.error(
            f"[{conversation_id}] Job {job_id} failed: {e}", exc_info=True
        )
        error_summary = f"The {body.job_type} job failed: {str(e)}"

        await update_job_status(job_id, "failed", error_summary=error_summary)
        await create_resume_event(
            conversation_id=conversation_id,
            event_type="job_failed",
            job_id=job_id,
            spoken_summary=(
                f"Unfortunately, your {body.job_type} job didn't complete. {error_summary}"
            ),
            display_summary=error_summary,
        )
        await set_conversation_state(conversation_id, "idle")
        await send_control_message(conversation_id, {
            "type": "job_update",
            "job_id": job_id,
            "status": "failed",
            "display_summary": error_summary,
        })

        push_token = await get_user_push_token(user_id)
        if push_token:
            await send_push_notification(
                push_token=push_token,
                title="SpecTalk — Job Failed",
                body=error_summary,
                conversation_id=conversation_id,
                data={"job_id": job_id, "event_type": "job_failed"},
            )

        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Job execution failed: {e}",
        )


async def _execute_job_by_type(
    job_type: str,
    job_id: str,
    conversation_id: str,
    user_id: str,
    payload: dict,
) -> dict:
    """Dispatch job execution by type.

    Phase 4: mock implementations only. Phase 5+ will add real dispatchers
    per job_type (OpenClaw, research pipelines, etc.).
    """
    if job_type in ("mock", "demo"):
        # Simulate a short job for testing
        await asyncio.sleep(2)
        return {
            "spoken_summary": "Your demo job has completed successfully.",
            "display_summary": "Demo job completed.",
        }

    # Unknown job type — return a generic result (Phase 5+ will handle real types)
    logger.warning(
        f"[{conversation_id}] Unknown job type '{job_type}' — returning mock result"
    )
    await asyncio.sleep(1)
    return {
        "spoken_summary": f"Your {job_type} job has been processed.",
        "display_summary": f"{job_type.capitalize()} completed.",
    }
