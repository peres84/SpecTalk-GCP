from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from typing import Optional

from db.database import get_db
from db.models import Conversation
from middleware.auth import require_auth

import uuid

router = APIRouter()


class ConversationSummary(BaseModel):
    conversation_id: str
    state: str
    last_turn_summary: Optional[str]
    pending_resume_count: int
    created_at: str
    updated_at: str


class ConversationDetail(ConversationSummary):
    working_memory: Optional[dict]


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
