from fastapi import APIRouter, Depends, HTTPException, status, Query, Response
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, delete, update
from typing import Optional

from db.database import get_db
from db.models import Conversation, Turn, Job, PendingAction, ResumeEvent, Asset, UserProject
from middleware.auth import require_auth

import uuid

router = APIRouter()


class ConversationSummary(BaseModel):
    conversation_id: str
    state: str
    last_turn_summary: Optional[str] = None  # explicit None default — serializes as JSON null
    pending_resume_count: int
    created_at: str
    updated_at: str


class ConversationDetail(ConversationSummary):
    working_memory: Optional[dict] = None


@router.get("", response_model=list[ConversationSummary])
async def list_conversations(
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Return all conversations for the authenticated user, newest first."""
    user_id = uuid.UUID(payload["sub"])
    result = await db.execute(
        select(Conversation)
        .where(Conversation.user_id == user_id)
        .order_by(Conversation.updated_at.desc())
    )
    conversations = result.scalars().all()
    return [
        ConversationSummary(
            conversation_id=str(c.id),
            state=c.state,
            last_turn_summary=c.last_turn_summary,
            pending_resume_count=c.pending_resume_count,
            created_at=c.created_at.isoformat(),
            updated_at=c.updated_at.isoformat(),
        )
        for c in conversations
    ]


@router.get("/{conversation_id}", response_model=ConversationDetail)
async def get_conversation(
    conversation_id: str,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Return a single conversation. User must own it."""
    user_id = uuid.UUID(payload["sub"])
    try:
        conv_id = uuid.UUID(conversation_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    result = await db.execute(
        select(Conversation).where(
            Conversation.id == conv_id,
            Conversation.user_id == user_id,
        )
    )
    conversation = result.scalar_one_or_none()
    if conversation is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    return ConversationDetail(
        conversation_id=str(conversation.id),
        state=conversation.state,
        last_turn_summary=conversation.last_turn_summary,
        pending_resume_count=conversation.pending_resume_count,
        working_memory=conversation.working_memory,
        created_at=conversation.created_at.isoformat(),
        updated_at=conversation.updated_at.isoformat(),
    )


@router.delete("/{conversation_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_conversation(
    conversation_id: str,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Delete a conversation and all its child records. User must own it."""
    user_id = uuid.UUID(payload["sub"])
    try:
        conv_id = uuid.UUID(conversation_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    # Verify ownership before touching anything
    result = await db.execute(
        select(Conversation.id).where(
            Conversation.id == conv_id,
            Conversation.user_id == user_id,
        )
    )
    if result.scalar_one_or_none() is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    # Delete child rows in FK-safe order.
    # resume_events has FKs to both conversation_id AND job_id, so we must
    # delete all resume_events referencing jobs in this conversation BEFORE
    # deleting the jobs themselves (handles cross-conversation edge cases).
    job_ids_result = await db.execute(
        select(Job.id).where(Job.conversation_id == conv_id)
    )
    job_ids = [r[0] for r in job_ids_result.fetchall()]

    await db.execute(delete(ResumeEvent).where(ResumeEvent.conversation_id == conv_id))
    if job_ids:
        await db.execute(delete(ResumeEvent).where(ResumeEvent.job_id.in_(job_ids)))
        await db.execute(
            update(UserProject)
            .where(UserProject.last_job_id.in_(job_ids))
            .values(last_job_id=None)
        )
    await db.execute(delete(PendingAction).where(PendingAction.conversation_id == conv_id))
    await db.execute(delete(Turn).where(Turn.conversation_id == conv_id))
    await db.execute(delete(Asset).where(Asset.conversation_id == conv_id))
    await db.execute(delete(Job).where(Job.conversation_id == conv_id))
    await db.execute(delete(Conversation).where(Conversation.id == conv_id))
    await db.commit()

    return Response(status_code=status.HTTP_204_NO_CONTENT)


class TurnItem(BaseModel):
    turn_id: str
    role: str
    text: Optional[str] = None
    event_type: Optional[str] = None
    created_at: str


@router.get("/{conversation_id}/turns", response_model=list[TurnItem])
async def list_turns(
    conversation_id: str,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
    limit: int = Query(default=100, le=500),
    offset: int = Query(default=0, ge=0),
):
    """Return the turn history for a conversation. User must own the conversation."""
    user_id = uuid.UUID(payload["sub"])
    try:
        conv_id = uuid.UUID(conversation_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    # Verify ownership
    result = await db.execute(
        select(Conversation.id).where(
            Conversation.id == conv_id,
            Conversation.user_id == user_id,
        )
    )
    if result.scalar_one_or_none() is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    result = await db.execute(
        select(Turn)
        .where(Turn.conversation_id == conv_id)
        .order_by(Turn.created_at.asc())
        .offset(offset)
        .limit(limit)
    )
    turns = result.scalars().all()
    return [
        TurnItem(
            turn_id=str(t.id),
            role=t.role,
            text=t.text,
            event_type=t.event_type,
            created_at=t.created_at.isoformat(),
        )
        for t in turns
    ]


class ConfirmRequest(BaseModel):
    confirmed: bool
    change_request: Optional[str] = None
    network_host: Optional[str] = None


@router.post("/{conversation_id}/confirm")
async def confirm_prd(
    conversation_id: str,
    body: ConfirmRequest,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Accept or reject a pending PRD confirmation for a coding job.

    Called by the Android app when the user taps Yes or No on the
    awaiting_confirmation screen (out-of-voice path).

    - confirmed=true  → create coding job, enqueue Cloud Tasks, set conversation to running_job
    - confirmed=false → cancel the pending action, set conversation to idle
    """
    from sqlalchemy import update as sql_update
    from services.job_service import create_job, enqueue_cloud_task, get_active_job_for_conversation
    from services.conversation_service import set_conversation_state
    from services.control_channels import send_control_message

    user_id = uuid.UUID(payload["sub"])
    try:
        conv_id = uuid.UUID(conversation_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    # Verify ownership
    result = await db.execute(
        select(Conversation.id).where(
            Conversation.id == conv_id,
            Conversation.user_id == user_id,
        )
    )
    if result.scalar_one_or_none() is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    # Fetch the most recent pending PRD action
    result = await db.execute(
        select(PendingAction)
        .where(
            PendingAction.conversation_id == conv_id,
            PendingAction.action_type == "confirm_prd",
            PendingAction.status == "pending",
        )
        .order_by(PendingAction.created_at.desc())
        .limit(1)
    )
    pending_action = result.scalar_one_or_none()
    if pending_action is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No pending PRD confirmation found for this conversation",
        )

    if body.confirmed:
        # Extract PRD from the pending action payload
        prd = (pending_action.payload or {}).get("prd", {})
        existing_project = (pending_action.payload or {}).get("existing_project")
        project_name = prd.get("project_name", "your project")
        active_job = await get_active_job_for_conversation(conversation_id, "coding")
        if active_job:
            return {"status": "ok", "job_id": str(active_job.id), "deduped": True}

        resolve_result = await db.execute(
            sql_update(PendingAction)
            .where(
                PendingAction.id == pending_action.id,
                PendingAction.status == "pending",
            )
            .values(status="resolved")
        )
        if resolve_result.rowcount == 0:
            active_job = await get_active_job_for_conversation(conversation_id, "coding")
            if active_job:
                await db.commit()
                return {"status": "ok", "job_id": str(active_job.id), "deduped": True}
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="PRD confirmation has already been handled",
            )
        await db.commit()

        # Create job row
        job = await create_job(conversation_id=conversation_id, job_type="coding")
        job_id = str(job.id)
        job_user_id = str(job.user_id)

        # Enqueue to Cloud Tasks
        await enqueue_cloud_task(
            job_id=job_id,
            job_type="coding",
            conversation_id=conversation_id,
            user_id=job_user_id,
            payload={
                "prd": prd,
                "existing_project": existing_project,
                "preferred_network_host": body.network_host,
            },
        )

        # Set conversation state and notify phone if WebSocket is open
        await set_conversation_state(conversation_id, "running_job")
        await send_control_message(conversation_id, {
            "type": "state_update",
            "state": "running_job",
        })
        await send_control_message(conversation_id, {
            "type": "job_started",
            "job_id": job_id,
            "description": f"Building {project_name}",
        })

        return {"status": "ok", "job_id": job_id}

    else:
        # User declined — cancel the pending action and reset state
        pending_action.status = "cancelled"
        await db.commit()

        await set_conversation_state(conversation_id, "idle")
        await send_control_message(conversation_id, {
            "type": "state_update",
            "state": "idle",
        })

        return {"status": "ok"}


@router.post("/{conversation_id}/ack-resume-event", status_code=status.HTTP_204_NO_CONTENT)
async def ack_resume_event(
    conversation_id: str,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Acknowledge all pending resume events for a conversation.

    Called by the Android app after the welcome-back message has been played
    and the user has seen the job result. Clears the notification badge.
    """
    user_id = uuid.UUID(payload["sub"])
    try:
        conv_id = uuid.UUID(conversation_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    # Verify ownership
    result = await db.execute(
        select(Conversation.id).where(
            Conversation.id == conv_id,
            Conversation.user_id == user_id,
        )
    )
    if result.scalar_one_or_none() is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")

    # Acknowledge all pending resume events (and zero out pending_resume_count)
    from services.resume_event_service import acknowledge_all_resume_events
    await acknowledge_all_resume_events(conversation_id)

    return Response(status_code=status.HTTP_204_NO_CONTENT)
