from fastapi import APIRouter, Depends, HTTPException, status, Query
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from typing import Optional

from db.database import get_db
from db.models import Conversation, Turn
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


class TurnItem(BaseModel):
    turn_id: str
    role: str
    text: Optional[str]
    event_type: Optional[str]
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
