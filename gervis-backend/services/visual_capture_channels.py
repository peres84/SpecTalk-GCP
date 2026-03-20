"""Per-conversation wait channels for automatic visual captures."""

from __future__ import annotations

import asyncio
import logging

logger = logging.getLogger(__name__)

_channels: dict[str, asyncio.Event] = {}
_capture_state: dict[str, dict] = {}


def register(conversation_id: str) -> None:
    _channels[conversation_id] = asyncio.Event()
    logger.debug(f"[{conversation_id}] visual capture channel registered")


def unregister(conversation_id: str) -> None:
    _channels.pop(conversation_id, None)
    _capture_state.pop(conversation_id, None)
    logger.debug(f"[{conversation_id}] visual capture channel unregistered")


def prepare_wait(conversation_id: str) -> bool:
    """Arm the capture channel before a request is sent to the app.

    This avoids racing with a very fast capture response that may arrive before
    wait_for_capture() starts waiting.
    """
    channel = _channels.get(conversation_id)
    if not channel:
        logger.debug(f"[{conversation_id}] no visual capture channel to prepare")
        return False

    channel.clear()
    _capture_state.pop(conversation_id, None)
    logger.debug(f"[{conversation_id}] visual capture channel prepared")
    return True


def notify_success(conversation_id: str, *, source: str | None = None) -> None:
    _capture_state[conversation_id] = {
        "ok": True,
        "source": source or "unknown",
    }
    channel = _channels.get(conversation_id)
    if channel:
        channel.set()
    logger.debug(f"[{conversation_id}] visual capture success notified")


def notify_failure(conversation_id: str, reason: str) -> None:
    _capture_state[conversation_id] = {
        "ok": False,
        "reason": reason,
    }
    channel = _channels.get(conversation_id)
    if channel:
        channel.set()
    logger.debug(f"[{conversation_id}] visual capture failure notified")


async def wait_for_capture(conversation_id: str, timeout: float = 8.0) -> dict | None:
    channel = _channels.get(conversation_id)
    if not channel:
        logger.debug(f"[{conversation_id}] no visual capture channel")
        return None

    try:
        await asyncio.wait_for(channel.wait(), timeout=timeout)
    except asyncio.TimeoutError:
        logger.debug(f"[{conversation_id}] visual capture timed out after {timeout}s")
        return None

    return _capture_state.get(conversation_id)
