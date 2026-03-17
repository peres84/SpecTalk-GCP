"""
WebSocket voice bridge: WS /ws/voice/{conversation_id}

Protocol:
  Phone -> Backend:
    - Binary frames: raw PCM 16kHz 16-bit mono audio
    - JSON text: {"type": "end_of_speech"}
                 {"type": "image", "mime_type": "image/jpeg", "data": "<base64>"}

  Backend -> Phone:
    - Binary frames: raw PCM 24kHz 16-bit mono audio
    - JSON text: {"type": "interrupted"}
                 {"type": "input_transcript", "text": "...", "is_partial": bool}
                 {"type": "output_transcript", "text": "...", "is_partial": bool}
                 {"type": "turn_complete"}
                 {"type": "error", "message": "..."}

Auth: JWT passed as query param ?token=<jwt> OR Authorization header.
      WebSocket is closed with code 4001 if token is missing/invalid.
"""

import asyncio
import base64
import json
import logging
from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query
from starlette.websockets import WebSocketState
from google.adk.agents.live_request_queue import LiveRequestQueue
from google.genai import types

from auth.jwt_handler import verify_product_jwt
from fastapi import HTTPException
from services.gemini_live_client import gemini_live_client
from services.audio_session_manager import audio_session_manager
from services.conversation_service import persist_turn, set_conversation_active, set_conversation_idle

logger = logging.getLogger(__name__)

router = APIRouter()


def _extract_jwt(websocket: WebSocket, token: str | None) -> dict | None:
    """Extract and verify JWT from query param or Authorization header."""
    # 1. Query param
    if token:
        try:
            return verify_product_jwt(token)
        except HTTPException:
            return None

    # 2. Authorization header
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

        # Binary frame = raw PCM audio from phone mic (16kHz 16-bit mono)
        if "bytes" in message and message["bytes"] is not None:
            audio_data = message["bytes"]
            audio_blob = types.Blob(
                mime_type="audio/pcm;rate=16000",
                data=audio_data,
            )
            live_request_queue.send_realtime(audio_blob)

        # Text frame = JSON control message from phone
        elif "text" in message and message["text"] is not None:
            try:
                msg = json.loads(message["text"])
                msg_type = msg.get("type")

                if msg_type == "end_of_speech":
                    # Phone signals end of speech.
                    # types.ActivityEnd() is available in google-genai >= 0.8 / ADK >= 1.0.
                    # If not available in the installed version, this will raise AttributeError
                    # and we fall through to the except block, logging a warning instead.
                    try:
                        live_request_queue.send_realtime(types.ActivityEnd())
                    except AttributeError:
                        logger.warning(
                            f"[{conversation_id}] types.ActivityEnd not available — "
                            "end_of_speech signal skipped (VAD will handle silence detection)"
                        )
                    logger.debug(f"[{conversation_id}] end_of_speech received")

                elif msg_type == "image":
                    # Inject image into the ADK session
                    raw = base64.b64decode(msg.get("data", ""))
                    mime = msg.get("mime_type", "image/jpeg")
                    live_request_queue.send_realtime(
                        types.Blob(mime_type=mime, data=raw)
                    )
                    logger.debug(f"[{conversation_id}] image injected ({mime}, {len(raw)} bytes)")

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
    async for event in live_events:
        if websocket.client_state != WebSocketState.CONNECTED:
            break

        try:
            # CRITICAL: Handle interrupted FIRST — clear phone audio buffer immediately
            if event.interrupted:
                await websocket.send_text(json.dumps({"type": "interrupted"}))
                logger.debug(f"[{conversation_id}] interrupted forwarded to phone")
                continue

            if not event.content:
                if event.turn_complete:
                    await websocket.send_text(json.dumps({"type": "turn_complete"}))
                continue

            # Transcription events from user (input) and model (output)
            for part in event.content.parts:
                if part.text:
                    if event.content.role == "user":
                        await websocket.send_text(json.dumps({
                            "type": "input_transcript",
                            "text": part.text,
                            "is_partial": bool(event.partial),
                        }))
                        # Persist final user turns
                        if not event.partial:
                            asyncio.create_task(
                                persist_turn(conversation_id, "user", part.text, "voice_transcript")
                            )

                    elif event.content.role == "model":
                        await websocket.send_text(json.dumps({
                            "type": "output_transcript",
                            "text": part.text,
                            "is_partial": bool(event.partial),
                        }))
                        # Persist final assistant turns
                        if not event.partial:
                            asyncio.create_task(
                                persist_turn(conversation_id, "assistant", part.text, "voice_transcript")
                            )

                # Binary audio from Gemini — forward raw PCM bytes to phone (zero-copy)
                elif part.inline_data and part.inline_data.mime_type.startswith("audio/pcm"):
                    await websocket.send_bytes(part.inline_data.data)

            if event.turn_complete:
                await websocket.send_text(json.dumps({"type": "turn_complete"}))

        except WebSocketDisconnect:
            break
        except Exception as e:
            logger.error(f"[{conversation_id}] downstream_task error: {e}")
            break


@router.websocket("/voice/{conversation_id}")
async def voice_websocket(
    websocket: WebSocket,
    conversation_id: str,
    token: str | None = Query(default=None),
) -> None:
    """
    Bidirectional voice bridge between the phone and ADK/Gemini Live.

    Authenticated by JWT (query param ?token= or Authorization header).
    """
    # 1. Authenticate on upgrade
    payload = _extract_jwt(websocket, token)
    if not payload:
        await websocket.close(code=4001, reason="Unauthorized")
        return

    user_id = payload["sub"]
    logger.info(f"[{conversation_id}] WebSocket connecting for user={user_id}")

    await websocket.accept()

    # 2. Get or create session entry (handles reconnect grace logic)
    entry = await audio_session_manager.get_or_create_entry(conversation_id, user_id)

    # 3. Start ADK Live session for this connection
    try:
        adk_session = await gemini_live_client.get_or_create_session(
            user_id=user_id,
            session_id=conversation_id,
        )
        live_request_queue = gemini_live_client.new_request_queue()
        live_events = gemini_live_client.start_live_session(adk_session, live_request_queue)
    except Exception as e:
        logger.error(f"[{conversation_id}] Failed to start ADK session: {e}")
        await websocket.send_text(json.dumps({
            "type": "error",
            "message": "Failed to start voice session",
        }))
        await websocket.close(code=1011)
        return

    # 4. Mark conversation active in DB
    asyncio.create_task(set_conversation_active(conversation_id))

    # 5. Run upstream and downstream concurrently
    upstream = asyncio.create_task(
        _upstream_task(websocket, live_request_queue, conversation_id)
    )
    downstream = asyncio.create_task(
        _downstream_task(websocket, live_events, conversation_id)
    )

    try:
        await asyncio.wait(
            [upstream, downstream],
            return_when=asyncio.FIRST_COMPLETED,
        )
    except Exception as e:
        logger.error(f"[{conversation_id}] Session error: {e}")
    finally:
        # Clean up tasks
        upstream.cancel()
        downstream.cancel()
        live_request_queue.close()

        logger.info(f"[{conversation_id}] Phone disconnected, starting grace timer")
        audio_session_manager.start_grace_timer(conversation_id)
        asyncio.create_task(set_conversation_idle(conversation_id))
