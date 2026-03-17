from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from db.database import get_db
from db.models import Conversation
from middleware.auth import require_auth

import uuid

router = APIRouter()


class VoiceSessionStartResponse(BaseModel):
    conversation_id: str
    state: str


@router.post("/session/start", response_model=VoiceSessionStartResponse)
async def start_voice_session(
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """
    Create a new conversation and return its ID.
    The Android app uses this conversation_id to open WS /ws/voice/{conversation_id}.
    Phase 3 will check for pending resume context and inject it into the Gemini session.
    """
    user_id = uuid.UUID(payload["sub"])

    conversation = Conversation(user_id=user_id, state="idle")
    db.add(conversation)
    await db.commit()
    await db.refresh(conversation)

    return VoiceSessionStartResponse(
        conversation_id=str(conversation.id),
        state=conversation.state,
    )
