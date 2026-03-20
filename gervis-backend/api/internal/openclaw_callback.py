"""OpenClaw async callback endpoint.

When a real OpenClaw coding job finishes, OpenClaw POSTs back to:
  POST /internal/openclaw/callback/{job_id}

The job was left in "running" state after the initial POST to OpenClaw.
This handler completes the delivery loop:
  1. Validate job_id exists and is in "running" state
  2. Update job to "completed" with spoken/display summary and artifacts
  3. Try to inject result into the live Gemini session (if phone is still connected)
  4. Otherwise: create resume event + FCM push notification
"""

import logging
import uuid

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel

logger = logging.getLogger(__name__)

router = APIRouter()


class OpenClawCallbackRequest(BaseModel):
    spoken_summary: str
    display_summary: str
    artifacts: list[dict] = []


@router.post("/openclaw/callback/{job_id}", status_code=status.HTTP_200_OK)
async def openclaw_callback(job_id: str, body: OpenClawCallbackRequest):
    """Receive the async result from OpenClaw and complete the job delivery."""
    from services.job_service import get_job_by_id, update_job_status
    from services.audio_session_manager import audio_session_manager
    from services.control_channels import send_control_message
    from services.resume_event_service import create_resume_event
    from services.conversation_service import set_conversation_state
    from services.notification_service import send_push_notification, get_user_push_token

    # Validate the job exists
    try:
        job_uuid = uuid.UUID(job_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Invalid job_id")

    job = await get_job_by_id(job_id)
    if job is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Job not found")

    if job.status not in ("running", "queued"):
        logger.warning(
            f"[openclaw_callback] Job {job_id} is already in status={job.status} — "
            "ignoring duplicate callback"
        )
        return {"status": "already_complete"}

    conversation_id = str(job.conversation_id)
    user_id = str(job.user_id)

    logger.info(
        f"[{conversation_id}] OpenClaw callback received for job {job_id}: "
        f"{body.display_summary[:80]}"
    )

    artifacts = body.artifacts if body.artifacts else None

    await update_job_status(
        job_id,
        "completed",
        spoken_completion_summary=body.spoken_summary,
        display_completion_summary=body.display_summary,
        artifacts={"items": artifacts} if artifacts else None,
    )

    # Notify phone: job_update
    await send_control_message(conversation_id, {
        "type": "job_update",
        "job_id": job_id,
        "status": "completed",
        "display_summary": body.display_summary,
    })

    # Smart delivery: live inject vs FCM
    injected = audio_session_manager.inject_job_result(conversation_id, body.spoken_summary)

    if injected:
        logger.info(
            f"[{conversation_id}] OpenClaw result injected into live session for job {job_id}"
        )
    else:
        logger.info(
            f"[{conversation_id}] Session not live — creating resume event + FCM for job {job_id}"
        )

        resume_event = await create_resume_event(
            conversation_id=conversation_id,
            event_type="job_completed",
            job_id=job_id,
            spoken_summary=body.spoken_summary,
            display_summary=body.display_summary,
            artifacts={"items": artifacts} if artifacts else None,
        )

        await set_conversation_state(conversation_id, "awaiting_resume")

        push_token = await get_user_push_token(user_id)
        if push_token:
            await send_push_notification(
                push_token=push_token,
                title="SpecTalk — Build Complete",
                body=body.display_summary,
                conversation_id=conversation_id,
                data={
                    "job_id": job_id,
                    "resume_event_id": str(resume_event.id),
                    "event_type": "job_completed",
                },
            )

    return {"status": "ok", "job_id": job_id}
