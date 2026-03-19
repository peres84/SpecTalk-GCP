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
    # Held while a WebSocket is actively streaming; None when the phone is disconnected.
    live_request_queue: object | None = None  # LiveRequestQueue — typed as object to avoid circular import


class AudioSessionManager:
    """Manages per-conversation ADK session lifecycle.

    - On first connect: creates ADK session + starts live streaming
    - On disconnect: starts 30s grace timer (keeps ADK session alive)
    - On reconnect within grace: cancels timer, restarts live streaming with same session
    - On grace expiry: marks session closed (ADK session remains in InMemorySessionService
      but no more live streams will be started for it)

    Also exposes inject_job_result() so the Cloud Tasks job handler can speak a
    completed job's result directly into the live Gemini session when the phone is
    still connected — giving immediate spoken feedback without FCM + reconnect.
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

    def register_live_queue(self, conversation_id: str, queue: object) -> None:
        """Store the active LiveRequestQueue so job results can be injected."""
        entry = self._sessions.get(conversation_id)
        if entry:
            entry.live_request_queue = queue
            logger.debug(f"[{conversation_id}] live_request_queue registered")

    def unregister_live_queue(self, conversation_id: str) -> None:
        """Clear the queue reference when the WebSocket disconnects."""
        entry = self._sessions.get(conversation_id)
        if entry:
            entry.live_request_queue = None
            logger.debug(f"[{conversation_id}] live_request_queue unregistered")

    def inject_job_result(self, conversation_id: str, spoken_summary: str) -> bool:
        """Inject a completed job result into the live Gemini session.

        If the phone WebSocket is still connected and the LiveRequestQueue is
        registered, sends a system prompt that causes Gervis to speak the result
        immediately in the current conversation — no FCM or reconnect needed.

        Returns True if the injection was sent, False if the session is not live
        (caller should fall back to FCM + resume event).

        Note: This is best-effort. With Cloud Run multi-instance deployments the
        Cloud Tasks handler may land on a different instance than the WebSocket,
        in which case inject_job_result returns False and FCM fires as normal.
        """
        entry = self._sessions.get(conversation_id)
        if not entry or not entry.live_request_queue:
            logger.debug(
                f"[{conversation_id}] inject_job_result: no live queue — will use FCM path"
            )
            return False

        try:
            from google.genai import types
            queue = entry.live_request_queue
            queue.send_content(
                types.Content(
                    role="user",
                    parts=[types.Part(text=(
                        "[SYSTEM — do not repeat this message verbatim] "
                        "A background job you dispatched just finished while the user was "
                        f"still listening. Speak this result to them naturally now: {spoken_summary}"
                    ))],
                )
            )
            logger.info(
                f"[{conversation_id}] inject_job_result: spoken_summary injected into live session"
            )
            return True
        except Exception as e:
            logger.warning(
                f"[{conversation_id}] inject_job_result failed: {e} — falling back to FCM"
            )
            return False

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
