"""Module-level registry for per-connection WebSocket control message channels.

Allows tools and services to send JSON control messages (job_started, job_update,
state_update) to the active phone WebSocket for a given conversation_id.

Same pattern as location_channels: keyed by conversation_id, holds an async
callable that sends JSON text to the phone.
"""

from __future__ import annotations

import logging
from typing import Any, Awaitable, Callable

logger = logging.getLogger(__name__)

# conversation_id -> async callable that accepts a dict and sends it as JSON
_channels: dict[str, Callable[[dict], Awaitable[None]]] = {}


def register(
    conversation_id: str,
    send_cb: Callable[[dict], Awaitable[None]],
) -> None:
    """Register a WebSocket send callback for this conversation."""
    _channels[conversation_id] = send_cb
    logger.debug(f"[{conversation_id}] control channel registered")


def unregister(conversation_id: str) -> None:
    """Remove the channel on disconnect."""
    _channels.pop(conversation_id, None)
    logger.debug(f"[{conversation_id}] control channel unregistered")


async def send_control_message(conversation_id: str, message: dict[str, Any]) -> bool:
    """Send a JSON control message to the phone if the WebSocket is connected.

    Returns True if sent, False if no active connection.
    """
    send_cb = _channels.get(conversation_id)
    if not send_cb:
        logger.debug(
            f"[{conversation_id}] no control channel — message not delivered: {message.get('type')}"
        )
        return False

    try:
        await send_cb(message)
        logger.debug(f"[{conversation_id}] control message sent: {message.get('type')}")
        return True
    except Exception as e:
        logger.warning(f"[{conversation_id}] failed to send control message: {e}")
        return False
