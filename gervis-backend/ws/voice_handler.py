"""
WebSocket voice bridge: WS /ws/voice/{conversation_id}

Protocol:
  Phone -> Backend:
    - Binary frames: raw PCM 16kHz 16-bit mono audio
    - JSON text: {"type": "end_of_speech"}
                 {"type": "image", "mime_type": "image/jpeg", "data": "<base64>"}
                 {"type": "location_response", "latitude": float, "longitude": float,
                  "accuracy_meters": float (optional), "location_label": str (optional)}

  Backend -> Phone:
    - Binary frames: raw PCM 24kHz 16-bit mono audio
    - JSON text: {"type": "interrupted"}
                 {"type": "input_transcript", "text": "...", "is_partial": bool}
                 {"type": "output_transcript", "text": "...", "is_partial": bool}
                 {"type": "turn_complete"}
                 {"type": "request_location"}
                 {"type": "job_started", "job_id": "...", "spoken_ack": "..."}
                 {"type": "job_update", "job_id": "...", "status": "...", "display_summary": "..."}
                 {"type": "state_update", "state": "..."}
                 {"type": "error", "message": "..."}

Auth: JWT passed as query param ?token=<jwt> OR Authorization header.
      WebSocket is closed with code 4001 if token is missing/invalid.
"""

import asyncio
import base64
import json
import logging
import os

from fastapi import APIRouter, HTTPException, Query, WebSocket, WebSocketDisconnect
from google.adk.agents.live_request_queue import LiveRequestQueue
from google.genai import types
from starlette.websockets import WebSocketState

from auth.jwt_handler import verify_product_jwt
from services.audio_session_manager import audio_session_manager
from services.conversation_service import (
    persist_turn,
    set_conversation_active,
    set_conversation_idle,
)
from services.gemini_live_client import gemini_live_client
import services.location_channels as location_channels
import services.control_channels as control_channels
from services.tracing import record_voice_turn
from services.location_context_service import (
    normalize_location_context,
    set_conversation_location_context,
)
from services.resume_event_service import (
    get_pending_resume_events,
    acknowledge_all_resume_events,
)

logger = logging.getLogger(__name__)

router = APIRouter()


def _extract_jwt(websocket: WebSocket, token: str | None) -> dict | None:
    """Extract and verify JWT from query param or Authorization header."""
    if token:
        try:
            return verify_product_jwt(token)
        except HTTPException:
            return None

    auth_header = websocket.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        raw = auth_header.removeprefix("Bearer ").strip()
        try:
            return verify_product_jwt(raw)
        except HTTPException:
            return None

    return None


async def _upstream_task(
    websocket: WebSocket,
    live_request_queue: LiveRequestQueue,
    conversation_id: str,
) -> None:
    """Forwards phone messages to the ADK live request queue."""
    while True:
        message = await websocket.receive()

        if message.get("type") == "websocket.disconnect":
            break

        if "bytes" in message and message["bytes"] is not None:
            audio_data = message["bytes"]
            audio_blob = types.Blob(
                mime_type="audio/pcm;rate=16000",
                data=audio_data,
            )
            live_request_queue.send_realtime(audio_blob)

        elif "text" in message and message["text"] is not None:
            try:
                msg = json.loads(message["text"])
                msg_type = msg.get("type")

                if msg_type == "end_of_speech":
                    logger.debug(f"[{conversation_id}] end_of_speech received (VAD handles)")

                elif msg_type == "image":
                    raw = base64.b64decode(msg.get("data", ""))
                    mime = msg.get("mime_type", "image/jpeg")
                    live_request_queue.send_realtime(
                        types.Blob(mime_type=mime, data=raw)
                    )
                    logger.debug(f"[{conversation_id}] image injected ({mime}, {len(raw)} bytes)")

                elif msg_type == "location_response":
                    normalized = normalize_location_context(msg)
                    if normalized:
                        await set_conversation_location_context(conversation_id, normalized)
                        location_channels.notify(conversation_id, normalized)
                        logger.debug(f"[{conversation_id}] location_response received")

            except (json.JSONDecodeError, KeyError) as e:
                logger.warning(f"[{conversation_id}] Malformed control message: {e}")


async def _downstream_task(
    websocket: WebSocket,
    live_events,
    conversation_id: str,
) -> None:
    """Forwards ADK events to the phone WebSocket.

    CRITICAL: interrupted events are sent BEFORE any further audio.
    """
    try:
        async for event in live_events:
            if websocket.client_state != WebSocketState.CONNECTED:
                break

            if event.interrupted:
                await websocket.send_text(json.dumps({"type": "interrupted"}))
                logger.debug(f"[{conversation_id}] interrupted forwarded to phone")
                continue

            if not event.content:
                if event.turn_complete:
                    await websocket.send_text(json.dumps({"type": "turn_complete"}))
                continue

            for part in event.content.parts:
                if part.text:
                    if event.content.role == "user":
                        await websocket.send_text(json.dumps({
                            "type": "input_transcript",
                            "text": part.text,
                            "is_partial": bool(event.partial),
                        }))
                        if not event.partial:
                            asyncio.create_task(
                                persist_turn(conversation_id, "user", part.text, "voice_transcript")
                            )
                            record_voice_turn(conversation_id, "user", part.text)

                    elif event.content.role == "model":
                        await websocket.send_text(json.dumps({
                            "type": "output_transcript",
                            "text": part.text,
                            "is_partial": bool(event.partial),
                        }))
                        if not event.partial:
                            asyncio.create_task(
                                persist_turn(conversation_id, "assistant", part.text, "voice_transcript")
                            )
                            record_voice_turn(conversation_id, "assistant", part.text)

                elif part.inline_data and part.inline_data.mime_type.startswith("audio/pcm"):
                    await websocket.send_bytes(part.inline_data.data)

            if event.turn_complete:
                await websocket.send_text(json.dumps({"type": "turn_complete"}))

    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.error(f"[{conversation_id}] Gemini Live session error: {e}", exc_info=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": str(e)}))
        except Exception:
            pass


@router.websocket("/voice/{conversation_id}")
async def voice_websocket(
    websocket: WebSocket,
    conversation_id: str,
    token: str | None = Query(default=None),
) -> None:
    """Bidirectional voice bridge between the phone and ADK/Gemini Live."""
    payload = _extract_jwt(websocket, token)
    if not payload:
        await websocket.close(code=4001, reason="Unauthorized")
        return

    user_id = payload["sub"]
    logger.info(f"[{conversation_id}] WebSocket connecting for user={user_id}")

    await websocket.accept()

    await audio_session_manager.get_or_create_entry(conversation_id, user_id)

    try:
        session = await gemini_live_client.get_or_create_session(
            user_id=user_id,
            session_id=conversation_id,
        )

        async def _send_request_location() -> None:
            await websocket.send_text(json.dumps({"type": "request_location"}))

        async def _send_control_message(message: dict) -> None:
            await websocket.send_text(json.dumps(message))

        location_channels.register(conversation_id, _send_request_location)
        control_channels.register(conversation_id, _send_control_message)

        live_request_queue = gemini_live_client.new_request_queue()
        live_events = gemini_live_client.start_live_session(
            user_id=user_id,
            session_id=conversation_id,
            live_request_queue=live_request_queue,
        )

        # Inject pending resume context into Gemini before the first audio frame.
        # This triggers a spoken welcome-back message when the user reconnects.
        pending_events = await get_pending_resume_events(conversation_id)
        if pending_events:
            summaries = ". ".join(
                ev.spoken_summary or ev.display_summary or f"{ev.event_type} completed"
                for ev in pending_events
            )
            resume_context = (
                f"The user has returned after being away. "
                f"The following completed while they were gone: {summaries}. "
                "Please greet the user warmly, briefly tell them the news, "
                "and offer to help with next steps."
            )
            try:
                live_request_queue.send_content(
                    types.Content(
                        role="user",
                        parts=[types.Part(text=resume_context)],
                    )
                )
                logger.info(
                    f"[{conversation_id}] Resume context injected for {len(pending_events)} event(s)"
                )
                asyncio.create_task(acknowledge_all_resume_events(conversation_id))
            except AttributeError:
                logger.warning(
                    f"[{conversation_id}] send_content not available in this ADK version "
                    "— resume context injection skipped"
                )

    except Exception as e:
        logger.error(f"[{conversation_id}] Failed to start ADK session: {e}")
        await websocket.send_text(json.dumps({
            "type": "error",
            "message": "Failed to start voice session",
        }))
        await websocket.close(code=1011)
        return

    asyncio.create_task(set_conversation_active(conversation_id))

    upstream = asyncio.create_task(
        _upstream_task(websocket, live_request_queue, conversation_id)
    )
    downstream = asyncio.create_task(
        _downstream_task(websocket, live_events, conversation_id)
    )

    try:
        done, _ = await asyncio.wait(
            [upstream, downstream],
            return_when=asyncio.FIRST_COMPLETED,
        )
        for task in done:
            if task.exception():
                logger.error(
                    f"[{conversation_id}] Task failed: {task.exception()}",
                    exc_info=task.exception(),
                )
    except Exception as e:
        logger.error(f"[{conversation_id}] Session error: {e}", exc_info=True)
    finally:
        upstream.cancel()
        downstream.cancel()
        live_request_queue.close()

        logger.info(f"[{conversation_id}] Phone disconnected, starting grace timer")
        location_channels.unregister(conversation_id)
        control_channels.unregister(conversation_id)
        audio_session_manager.start_grace_timer(conversation_id)
        asyncio.create_task(set_conversation_idle(conversation_id))
