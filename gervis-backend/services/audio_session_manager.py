"""Per-conversation ADK session lifecycle manager with 30s grace period."""

import asyncio
import logging
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)

GRACE_PERIOD_SECONDS = 30


@dataclass
class SessionEntry:
    """Lightweight state tracker per conversation. ADK session is in InMemorySessionService."""
    user_id: str
    grace_task: asyncio.Task | None = None
    closed: bool = False


class AudioSessionManager:
    """Manages per-conversation ADK session lifecycle.

    - On first connect: creates ADK session + starts live streaming
    - On disconnect: starts 30s grace timer (keeps ADK session alive)
    - On reconnect within grace: cancels timer, restarts live streaming with same session
    - On grace expiry: marks session closed (ADK session remains in InMemorySessionService
      but no more live streams will be started for it)
    """

    def __init__(self):
        self._sessions: dict[str, SessionEntry] = {}
        self._lock = asyncio.Lock()

    async def get_or_create_entry(self, conversation_id: str, user_id: str) -> SessionEntry:
        """Return existing session entry (cancelling any grace timer) or create a new one."""
        async with self._lock:
            if conversation_id in self._sessions:
                entry = self._sessions[conversation_id]
                if entry.closed:
                    # Session expired — create fresh entry
                    entry = SessionEntry(user_id=user_id)
                    self._sessions[conversation_id] = entry
                else:
                    # Cancel any pending grace timer (reconnect within grace period)
                    if entry.grace_task and not entry.grace_task.done():
                        entry.grace_task.cancel()
                        entry.grace_task = None
                        logger.info(f"[{conversation_id}] Phone reconnected within grace period")
            else:
                entry = SessionEntry(user_id=user_id)
                self._sessions[conversation_id] = entry

            return entry

    def start_grace_timer(self, conversation_id: str) -> None:
        """Start the 30s grace timer after phone disconnects."""
        entry = self._sessions.get(conversation_id)
        if not entry:
            return

        async def _expire():
            try:
                await asyncio.sleep(GRACE_PERIOD_SECONDS)
                async with self._lock:
                    if conversation_id in self._sessions:
                        self._sessions[conversation_id].closed = True
                        del self._sessions[conversation_id]
                        logger.info(f"[{conversation_id}] Grace period expired, session removed")
            except asyncio.CancelledError:
                pass  # Phone reconnected — timer was cancelled

        entry.grace_task = asyncio.create_task(_expire())
        logger.info(f"[{conversation_id}] Grace timer started ({GRACE_PERIOD_SECONDS}s)")


# Singleton
audio_session_manager = AudioSessionManager()
