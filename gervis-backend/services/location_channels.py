"""Module-level registry for per-connection location request channels.

Bypasses ADK session state for non-serializable objects (asyncio.Event, async callables).
Keyed by conversation_id, which is stable for the lifetime of one WebSocket connection.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable

logger = logging.getLogger(__name__)

# conversation_id -> (event, send_cb)
_channels: dict[str, tuple[asyncio.Event, Callable[[], Awaitable[None]]]] = {}

# conversation_id -> latest normalized location dict
_location_data: dict[str, dict] = {}


def register(
    conversation_id: str,
    send_cb: Callable[[], Awaitable[None]],
) -> None:
    """Register a new connection. Creates a fresh event for this session."""
    _channels[conversation_id] = (asyncio.Event(), send_cb)
    logger.debug(f"[{conversation_id}] location channel registered")


def unregister(conversation_id: str) -> None:
    """Remove the channel on disconnect (grace timer expiry or hard close)."""
    _channels.pop(conversation_id, None)
    logger.debug(f"[{conversation_id}] location channel unregistered")


def notify(conversation_id: str, location: dict) -> None:
    """Called when location_response arrives. Stores data and fires the event."""
    _location_data[conversation_id] = location
    channel = _channels.get(conversation_id)
    if channel:
        channel[0].set()
    logger.debug(f"[{conversation_id}] location channel notified")


def get_cached(conversation_id: str) -> dict | None:
    """Return the latest stored location for this conversation, or None."""
    return _location_data.get(conversation_id)


async def request_and_wait(conversation_id: str, timeout: float = 6.0) -> dict | None:
    """Send request_location to the phone and wait for the response.

    Returns the location dict on success, or None on timeout / no channel.
    """
    channel = _channels.get(conversation_id)
    if not channel:
        logger.debug(f"[{conversation_id}] no location channel — cannot request location")
        return None

    event, send_cb = channel
    event.clear()
    await send_cb()

    try:
        await asyncio.wait_for(event.wait(), timeout=timeout)
    except asyncio.TimeoutError:
        logger.debug(f"[{conversation_id}] location request timed out after {timeout}s")
        return None

    return _location_data.get(conversation_id)
