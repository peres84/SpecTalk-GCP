"""Resume event service: create, fetch, and acknowledge resume events.

Resume events are created when a background job completes or needs input.
They drive push notifications and the welcome-back voice flow on conversation resume.
"""

import logging
import uuid

from db.database import AsyncSessionLocal
from db.models import Conversation, ResumeEvent
from sqlalchemy import select

logger = logging.getLogger(__name__)


async def create_resume_event(
    conversation_id: str,
    event_type: str,
    *,
    job_id: str | None = None,
    spoken_summary: str | None = None,
    display_summary: str | None = None,
    artifacts: dict | None = None,
) -> ResumeEvent:
    """Create a resume event and increment pending_resume_count on the conversation."""
    async with AsyncSessionLocal() as session:
        event = ResumeEvent(
            conversation_id=uuid.UUID(conversation_id),
            job_id=uuid.UUID(job_id) if job_id else None,
            event_type=event_type,
            spoken_summary=spoken_summary,
            display_summary=display_summary,
            artifacts=artifacts,
            is_acknowledged=False,
        )
        session.add(event)

        # Increment pending_resume_count on the conversation
        conv_result = await session.execute(
            select(Conversation).where(Conversation.id == uuid.UUID(conversation_id))
        )
        conv = conv_result.scalar_one_or_none()
        if conv:
            conv.pending_resume_count = (conv.pending_resume_count or 0) + 1

        await session.commit()
        await session.refresh(event)
        logger.info(
            f"[{conversation_id}] Resume event created: {event.id} type={event_type}"
        )
        return event


async def get_pending_resume_events(conversation_id: str) -> list[ResumeEvent]:
    """Return all unacknowledged resume events for a conversation, oldest first."""
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(ResumeEvent)
            .where(
                ResumeEvent.conversation_id == uuid.UUID(conversation_id),
                ResumeEvent.is_acknowledged == False,  # noqa: E712
            )
            .order_by(ResumeEvent.created_at)
        )
        return list(result.scalars().all())


async def acknowledge_resume_event(resume_event_id: str) -> bool:
    """Mark a resume event as acknowledged and decrement pending_resume_count."""
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(ResumeEvent).where(ResumeEvent.id == uuid.UUID(resume_event_id))
        )
        event = result.scalar_one_or_none()
        if not event:
            logger.warning(f"acknowledge_resume_event: event {resume_event_id} not found")
            return False

        if not event.is_acknowledged:
            event.is_acknowledged = True

            # Decrement pending_resume_count on the conversation
            conv_result = await session.execute(
                select(Conversation).where(Conversation.id == event.conversation_id)
            )
            conv = conv_result.scalar_one_or_none()
            if conv and conv.pending_resume_count > 0:
                conv.pending_resume_count -= 1

        await session.commit()
        logger.info(f"Resume event {resume_event_id} acknowledged")
        return True


async def acknowledge_all_resume_events(conversation_id: str) -> int:
    """Acknowledge all pending resume events for a conversation.

    Returns count of events acknowledged.
    """
    events = await get_pending_resume_events(conversation_id)
    count = 0
    for event in events:
        if await acknowledge_resume_event(str(event.id)):
            count += 1
    return count
