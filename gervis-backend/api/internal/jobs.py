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

Delivery strategy (job completes):
  1. Try to inject spoken_summary directly into the live Gemini session via
     audio_session_manager.inject_job_result(). Gervis speaks immediately.
  2. If session is not live (phone disconnected / different Cloud Run instance):
     create a resume_event + set conversation to awaiting_resume + send FCM push.
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

    Updates job status, then either:
    - Injects the result into the live Gemini session (phone still connected), or
    - Creates a resume event + FCM notification (phone disconnected).
    """
    from config import settings

    # In production, only Cloud Tasks may call this endpoint
    if settings.environment == "production" and not x_cloudtasks_queuename:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only callable from Cloud Tasks",
        )

    from services.job_service import update_job_status, get_job_by_id
    from services.notification_service import send_push_notification, get_user_push_token
    from services.resume_event_service import create_resume_event
    from services.control_channels import send_control_message
    from services.conversation_service import set_conversation_state
    from services.audio_session_manager import audio_session_manager

    job_id = body.job_id
    conversation_id = body.conversation_id
    user_id = body.user_id

    logger.info(
        f"[{conversation_id}] Executing job {job_id} type={body.job_type}"
    )

    # Idempotency guard: Cloud Tasks retries on backend restart (redeploy kills
    # in-flight jobs). If the job already reached a terminal state from a previous
    # attempt, return 200 immediately so Cloud Tasks stops retrying and does not
    # fire a duplicate FCM notification.
    existing_job = await get_job_by_id(job_id)
    if existing_job and existing_job.status in ("completed", "failed"):
        logger.info(
            f"[{conversation_id}] Job {job_id} already in terminal state "
            f"'{existing_job.status}' — skipping duplicate execution"
        )
        return {"status": "already_complete", "job_id": job_id}

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

        # Always send the job_update control message (delivered if phone is connected)
        await send_control_message(conversation_id, {
            "type": "job_update",
            "job_id": job_id,
            "status": "completed",
            "display_summary": display_summary,
        })

        # --- Smart delivery: live injection vs FCM ---
        # Try to speak the result directly into the live Gemini session.
        # This is best-effort: succeeds when the phone WebSocket is still open on
        # this Cloud Run instance; falls through to FCM when the session is gone.
        injected = audio_session_manager.inject_job_result(conversation_id, spoken_summary)

        if injected:
            # Gervis will speak the result immediately — no FCM or resume event needed.
            logger.info(
                f"[{conversation_id}] Job {job_id} result injected into live session "
                f"— skipping FCM and resume event"
            )
        else:
            # Phone is not connected (or different instance) — use FCM + resume flow.
            logger.info(
                f"[{conversation_id}] Job {job_id} session not live — creating resume event + FCM"
            )

            resume_event = await create_resume_event(
                conversation_id=conversation_id,
                event_type="job_completed",
                job_id=job_id,
                spoken_summary=spoken_summary,
                display_summary=display_summary,
                artifacts=artifacts,
            )

            await set_conversation_state(conversation_id, "awaiting_resume")

            push_token = await get_user_push_token(user_id)
            logger.info(
                f"[{conversation_id}] FCM push_token for user {user_id}: "
                f"{'found' if push_token else 'NULL — skipping notification'}"
            )
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

        # For failures: always create a resume event and notify via FCM.
        # Even if the session is live, failures warrant a persistent notification
        # so the user can review them later.
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

        # Also try to inject the failure message into the live session
        audio_session_manager.inject_job_result(
            conversation_id,
            f"Unfortunately, your {body.job_type} job didn't complete. {error_summary}",
        )

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
    if job_type == "demo":
        # Very short smoke-test job — only use "demo" for curl testing
        await asyncio.sleep(3)
        return {
            "spoken_summary": "Your demo job has completed successfully.",
            "display_summary": "Demo job completed.",
        }

    if job_type == "mock":
        await asyncio.sleep(3)
        return {
            "spoken_summary": "Your mock job has completed.",
            "display_summary": "Mock job completed.",
        }

    if job_type == "research":
        # Simulate a realistic research pipeline — long enough that the
        # spoken_ack ("I'm on it!") finishes playing before inject_job_result
        # fires. 1-second mocks caused double-audio overlap.
        await asyncio.sleep(20)
        description = payload.get("description", "your research topic")
        return {
            "spoken_summary": (
                f"I finished researching {description}. "
                "I found several relevant sources and compiled a summary. "
                "Ready to walk you through the key findings whenever you're back."
            ),
            "display_summary": f"Research complete: {description}",
        }

    if job_type == "coding":
        from tools.openclaw_coding_tool import execute_coding_job
        return await execute_coding_job(job_id, conversation_id, user_id, payload)

    # Unknown job type — return a generic result
    logger.warning(
        f"[{conversation_id}] Unknown job type '{job_type}' — returning mock result"
    )
    await asyncio.sleep(15)
    return {
        "spoken_summary": f"Your {job_type} job has been completed.",
        "display_summary": f"{job_type.capitalize()} completed.",
    }
