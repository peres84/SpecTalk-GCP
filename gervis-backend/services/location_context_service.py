"""Helpers for storing and reading per-conversation location context."""

from __future__ import annotations

import logging
import uuid
from typing import Any

from sqlalchemy import select

from db.database import AsyncSessionLocal
from db.models import Conversation

logger = logging.getLogger(__name__)

LOCATION_CONTEXT_KEY = "location_context"


def normalize_location_context(payload: dict[str, Any] | None) -> dict[str, Any] | None:
    """Validate and normalize a location payload for storage and session state."""
    if not payload:
        return None

    try:
        latitude = float(payload["latitude"])
        longitude = float(payload["longitude"])
    except (KeyError, TypeError, ValueError):
        return None

    normalized: dict[str, Any] = {
        "latitude": latitude,
        "longitude": longitude,
    }

    accuracy = payload.get("accuracy_meters")
    if accuracy is not None:
        try:
            normalized["accuracy_meters"] = float(accuracy)
        except (TypeError, ValueError):
            pass

    label = payload.get("location_label")
    if isinstance(label, str) and label.strip():
        normalized["location_label"] = label.strip()

    captured_at = payload.get("captured_at")
    if isinstance(captured_at, str) and captured_at.strip():
        normalized["captured_at"] = captured_at.strip()

    return normalized


async def set_conversation_location_context(
    conversation_id: str,
    location_context: dict[str, Any],
) -> None:
    """Persist the latest location context on the conversation working memory."""
    try:
        async with AsyncSessionLocal() as session:
            result = await session.execute(
                select(Conversation).where(Conversation.id == uuid.UUID(conversation_id))
            )
            conversation = result.scalar_one_or_none()
            if conversation is None:
                return

            working_memory = dict(conversation.working_memory or {})
            working_memory[LOCATION_CONTEXT_KEY] = location_context
            conversation.working_memory = working_memory
            await session.commit()
    except Exception as e:
        logger.error(f"Failed to store location context [{conversation_id}]: {e}")


async def get_conversation_location_context(conversation_id: str) -> dict[str, Any] | None:
    """Load the latest stored location context for a conversation."""
    try:
        async with AsyncSessionLocal() as session:
            result = await session.execute(
                select(Conversation.working_memory).where(
                    Conversation.id == uuid.UUID(conversation_id)
                )
            )
            working_memory = result.scalar_one_or_none() or {}
            location_context = working_memory.get(LOCATION_CONTEXT_KEY)
            if isinstance(location_context, dict):
                return location_context
    except Exception as e:
        logger.error(f"Failed to load location context [{conversation_id}]: {e}")
    return None
