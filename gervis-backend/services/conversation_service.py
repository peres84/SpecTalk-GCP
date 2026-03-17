"""Persist conversation turns to the database from ADK transcription events."""

import uuid
import logging

from db.database import AsyncSessionLocal
from db.models import Turn, Conversation
from sqlalchemy import select

logger = logging.getLogger(__name__)


async def persist_turn(
    conversation_id: str,
    role: str,
    text: str,
    event_type: str | None = None,
) -> None:
    """Persist a conversation turn to the database. Fire-and-forget safe."""
    try:
        async with AsyncSessionLocal() as session:
            turn = Turn(
                conversation_id=uuid.UUID(conversation_id),
                role=role,
                text=text,
                event_type=event_type,
            )
            session.add(turn)
            await session.commit()
    except Exception as e:
        logger.error(f"Failed to persist turn [{conversation_id}] role={role}: {e}")


async def set_conversation_active(conversation_id: str) -> None:
    """Mark a conversation as active when a voice session starts."""
    try:
        async with AsyncSessionLocal() as session:
            result = await session.execute(
                select(Conversation).where(
                    Conversation.id == uuid.UUID(conversation_id)
                )
            )
            conv = result.scalar_one_or_none()
            if conv:
                conv.state = "active"
                await session.commit()
    except Exception as e:
        logger.error(f"Failed to set conversation active [{conversation_id}]: {e}")


async def set_conversation_idle(conversation_id: str) -> None:
    """Mark a conversation as idle when the voice session ends."""
    try:
        async with AsyncSessionLocal() as session:
            result = await session.execute(
                select(Conversation).where(
                    Conversation.id == uuid.UUID(conversation_id)
                )
            )
            conv = result.scalar_one_or_none()
            if conv:
                conv.state = "idle"
                await session.commit()
    except Exception as e:
        logger.warning(f"Failed to set conversation idle [{conversation_id}]: {e}")
