"""
WebSocket voice bridge: WS /ws/voice/{conversation_id}

Protocol:
  Phone -> Backend:
    - Binary frames: raw PCM 16kHz 16-bit mono audio
    - JSON text: {"type": "end_of_speech"}
                 {"type": "text_input", "text": "..."}
                 {"type": "image", "mime_type": "image/jpeg", "data": "<base64>"}
                 {"type": "session_capabilities", "glasses_camera_ready": bool,
                  "glasses_capture_available": bool, "listening_enabled": bool}
                 {"type": "visual_capture_status", "status": "failed", "reason": "..."}
                 {"type": "location_response", "latitude": float, "longitude": float,
                  "accuracy_meters": float (optional), "location_label": str (optional)}

  Backend -> Phone:
    - Binary frames: raw PCM 24kHz 16-bit mono audio
    - JSON text: {"type": "interrupted"}
                 {"type": "input_transcript", "text": "...", "is_partial": bool}
                 {"type": "output_transcript", "text": "...", "is_partial": bool}
                 {"type": "tool_status", "activity_id": "...", "label": "...",
                  "status": "running|completed", "duration_ms": int (optional)}
                 {"type": "turn_complete"}
                 {"type": "request_location"}
                 {"type": "job_started", "job_id": "...", "spoken_ack": "..."}
                 {"type": "job_update", "job_id": "...", "status": "...", "display_summary": "..."}
                 {"type": "request_visual_capture", "source": "glasses"}
                 {"type": "state_update", "state": "..."}
                 {"type": "error", "message": "..."}

Auth: JWT passed as query param ?token=<jwt> OR Authorization header.
      Optional query param ?voice_language=<BCP-47 code> selects the spoken
      language guard for the Live session.
      Optional query param ?tailscale_host=<host or URL> selects the preferred
      host/domain used when sharing dev URLs for coding projects.
      WebSocket is closed with code 4001 if token is missing/invalid.
"""

import asyncio
import base64
import json
import logging
import os
import time

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
    set_conversation_state,
)
from services.gemini_live_client import gemini_live_client
import services.location_channels as location_channels
import services.control_channels as control_channels
import services.visual_capture_channels as visual_capture_channels
from services.tracing import record_voice_turn, opik_session_start, opik_session_end, record_voice_turn_opik
from services.location_context_service import (
    normalize_location_context,
    set_conversation_location_context,
)
from services.resume_event_service import (
    get_pending_resume_events,
    acknowledge_all_resume_events,
)
from services.session_logger import log_adk_event, log_session_start, log_session_end
from services.speech_preferences import build_speech_preferences

logger = logging.getLogger(__name__)

router = APIRouter()

# Per-conversation turn buffer: accumulates text fragments until turn_complete,
# then flushes as a single Opik span.  Keeps Opik from creating one span per
# audio chunk instead of one span per complete utterance.
# Structure: { conversation_id: { "user": ["frag1", "frag2", ...], "model": [...] } }
_turn_buffer: dict[str, dict[str, list[str]]] = {}

# User text messages injected directly from the chat composer are persisted eagerly on
# receipt. If the Live session echoes the same user text back, suppress forwarding and
# buffering once so the Android transcript and stored history do not duplicate it.
_pending_text_inputs: dict[str, list[str]] = {}
_tool_activity_timings: dict[str, dict[str, float]] = {}


def _normalize_preferred_network_host(raw: str | None) -> str | None:
    value = (raw or "").strip()
    return value or None


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
    user_id: str,
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

                elif msg_type == "text_input":
                    text = (msg.get("text") or "").strip()
                    if text:
                        try:
                            live_request_queue.send_content(
                                types.Content(
                                    role="user",
                                    parts=[types.Part(text=text)],
                                )
                            )
                        except AttributeError:
                            logger.warning(
                                f"[{conversation_id}] send_content unavailable — text_input ignored"
                            )
                            continue
                        _pending_text_inputs.setdefault(conversation_id, []).append(text)
                        asyncio.create_task(
                            persist_turn(conversation_id, "user", text, "text_input")
                        )
                        record_voice_turn(conversation_id, "user", text)
                        record_voice_turn_opik(conversation_id, "user", text)
                        logger.debug(f"[{conversation_id}] text_input injected")

                elif msg_type == "image":
                    raw = base64.b64decode(msg.get("data", ""))
                    mime = msg.get("mime_type", "image/jpeg")
                    live_request_queue.send_realtime(
                        types.Blob(mime_type=mime, data=raw)
                    )
                    visual_capture_channels.notify_success(
                        conversation_id,
                        source=msg.get("source"),
                    )
                    logger.debug(f"[{conversation_id}] image injected ({mime}, {len(raw)} bytes)")

                elif msg_type == "session_capabilities":
                    session = await gemini_live_client.get_or_create_session(
                        user_id=user_id,
                        session_id=conversation_id,
                    )
                    session.state["glasses_camera_ready"] = bool(msg.get("glasses_camera_ready", False))
                    session.state["glasses_capture_available"] = bool(
                        msg.get("glasses_capture_available", False)
                    )
                    session.state["listening_enabled"] = bool(msg.get("listening_enabled", True))
                    logger.debug(
                        f"[{conversation_id}] session_capabilities received: "
                        f"glasses_camera_ready={session.state['glasses_camera_ready']} "
                        f"glasses_capture_available={session.state['glasses_capture_available']} "
                        f"listening_enabled={session.state['listening_enabled']}"
                    )

                elif msg_type == "visual_capture_status":
                    if msg.get("status") == "failed":
                        reason = (msg.get("reason") or "Capture failed").strip()
                        visual_capture_channels.notify_failure(conversation_id, reason)
                        try:
                            live_request_queue.send_content(
                                types.Content(
                                    role="user",
                                    parts=[types.Part(
                                        text=(
                                            "System update: automatic Meta glasses capture failed. "
                                            f"Reason: {reason}. "
                                            "Tell the user briefly and ask them to reconnect their "
                                            "glasses camera or send a photo manually."
                                        )
                                    )],
                                )
                            )
                        except AttributeError:
                            logger.warning(
                                f"[{conversation_id}] send_content unavailable — visual capture failure "
                                "was not injected into the session"
                            )
                elif msg_type == "visual_capture_debug":
                    logger.info(
                        f"[{conversation_id}] visual_capture_debug: "
                        f"{msg.get('message', '')}"
                    )

                elif msg_type == "location_response":
                    normalized = normalize_location_context(msg)
                    if normalized:
                        await set_conversation_location_context(conversation_id, normalized)
                        location_channels.notify(conversation_id, normalized)
                        logger.debug(f"[{conversation_id}] location_response received")

            except (json.JSONDecodeError, KeyError) as e:
                logger.warning(f"[{conversation_id}] Malformed control message: {e}")


async def _flush_turn_buffer(conversation_id: str) -> None:
    """Flush the turn buffer: persist merged text to DB, Opik, and OTel.

    Called on turn_complete and on disconnect. Each role's fragments are
    joined into a single string and saved as ONE turn row (not per-chunk).
    """
    buf = _turn_buffer.get(conversation_id, {})
    for role, fragments in buf.items():
        if not fragments:
            continue
        full_text = " ".join(fragments).strip()
        if not full_text:
            continue
        # Map ADK role names to DB role names
        db_role = "assistant" if role == "model" else role
        # Single DB row per complete turn
        asyncio.create_task(
            persist_turn(conversation_id, db_role, full_text, "voice_transcript")
        )
        # Single OTel span per complete turn
        record_voice_turn(conversation_id, db_role, full_text)
        # Single Opik span per complete turn
        record_voice_turn_opik(conversation_id, role, full_text)
    _turn_buffer[conversation_id] = {"user": [], "model": []}


def _tool_status_label(tool_name: str) -> str:
    labels = {
        "google_search": "Searching Google",
        "get_user_location": "Checking your location",
        "find_nearby_places": "Searching maps",
        "request_clarification": "Shaping your request",
        "generate_and_confirm_prd": "Creating project plan",
        "confirm_and_dispatch": "Starting build",
        "start_background_job": "Starting background job",
        "lookup_project": "Looking up your project",
        "request_visual_capture": "Capturing glasses view",
    }
    return labels.get(tool_name, tool_name.replace("_", " ").strip().title())


async def _downstream_task(
    websocket: WebSocket,
    live_events,
    conversation_id: str,
) -> None:
    """Forwards ADK events to the phone WebSocket.

    CRITICAL: interrupted events are sent BEFORE any further audio.

    Text chunks are buffered per-role and flushed as a single DB row +
    Opik span on turn_complete (fixes fragmented turn storage).
    """
    # Initialise per-turn text buffer for this conversation
    _turn_buffer[conversation_id] = {"user": [], "model": []}

    try:
        async for event in live_events:
            if websocket.client_state != WebSocketState.CONNECTED:
                break

            # Structured session log — tool calls, transcripts, turn markers
            log_adk_event(conversation_id, event)

            fn_calls = event.get_function_calls() if hasattr(event, "get_function_calls") else []
            activity_timings = _tool_activity_timings.setdefault(conversation_id, {})
            for fc in fn_calls:
                activity_id = getattr(fc, "id", None) or fc.name
                activity_timings[activity_id] = time.monotonic()
                await websocket.send_text(json.dumps({
                    "type": "tool_status",
                    "activity_id": activity_id,
                    "tool_name": fc.name,
                    "label": _tool_status_label(fc.name),
                    "status": "running",
                }))

            fn_responses = event.get_function_responses() if hasattr(event, "get_function_responses") else []
            for fr in fn_responses:
                activity_id = getattr(fr, "id", None) or fr.name
                started_at = activity_timings.pop(activity_id, None)
                duration_ms = None
                if started_at is None and fr.name in activity_timings:
                    started_at = activity_timings.pop(fr.name, None)
                if started_at is not None:
                    duration_ms = int((time.monotonic() - started_at) * 1000)

                await websocket.send_text(json.dumps({
                    "type": "tool_status",
                    "activity_id": activity_id,
                    "tool_name": fr.name,
                    "label": _tool_status_label(fr.name),
                    "status": "completed",
                    "duration_ms": duration_ms,
                }))

            if event.interrupted:
                await websocket.send_text(json.dumps({"type": "interrupted"}))
                logger.debug(f"[{conversation_id}] interrupted forwarded to phone")
                # Flush and reset buffer on barge-in so the next turn starts clean
                _turn_buffer[conversation_id] = {"user": [], "model": []}
                continue

            if not event.content:
                if event.turn_complete:
                    await websocket.send_text(json.dumps({"type": "turn_complete"}))
                    await _flush_turn_buffer(conversation_id)
                continue

            for part in event.content.parts:
                if part.text:
                    if event.content.role == "user":
                        pending = _pending_text_inputs.get(conversation_id, [])
                        if not event.partial and pending and pending[0] == part.text:
                            pending.pop(0)
                            continue
                        await websocket.send_text(json.dumps({
                            "type": "input_transcript",
                            "text": part.text,
                            "is_partial": bool(event.partial),
                        }))
                        if not event.partial:
                            # Buffer — do NOT persist yet; flush on turn_complete
                            _turn_buffer.setdefault(conversation_id, {"user": [], "model": []})["user"].append(part.text)

                    elif event.content.role == "model":
                        await websocket.send_text(json.dumps({
                            "type": "output_transcript",
                            "text": part.text,
                            "is_partial": bool(event.partial),
                        }))
                        if not event.partial:
                            # Buffer — do NOT persist yet; flush on turn_complete
                            _turn_buffer.setdefault(conversation_id, {"user": [], "model": []})["model"].append(part.text)

                elif part.inline_data and part.inline_data.mime_type.startswith("audio/pcm"):
                    await websocket.send_bytes(part.inline_data.data)

            if event.turn_complete:
                await websocket.send_text(json.dumps({"type": "turn_complete"}))
                await _flush_turn_buffer(conversation_id)

    except WebSocketDisconnect:
        pass
    except Exception as e:
        error_str = str(e)
        # 1011 errors from Gemini Live after ~10 min are session timeouts —
        # the preview audio model has a maximum session duration. Send a
        # specific message type so the Android app can reconnect gracefully
        # instead of showing a generic error screen.
        is_session_timeout = "1011" in error_str and (
            "Failed to run inference" in error_str
            or "Thread was cancelled" in error_str
            or "session" in error_str.lower()
        )
        if is_session_timeout:
            logger.warning(
                f"[{conversation_id}] Gemini Live session timed out (10-min limit for preview model)"
            )
            try:
                await websocket.send_text(json.dumps({
                    "type": "session_timeout",
                    "message": "Voice session reached its time limit. Tap to start a new session.",
                }))
            except Exception:
                pass
        else:
            logger.error(f"[{conversation_id}] Gemini Live session error: {e}", exc_info=True)
            try:
                await websocket.send_text(json.dumps({"type": "error", "message": error_str}))
            except Exception:
                pass
    finally:
        # Flush any remaining buffered text so the last turn isn't lost
        await _flush_turn_buffer(conversation_id)
        _pending_text_inputs.pop(conversation_id, None)
        _tool_activity_timings.pop(conversation_id, None)


@router.websocket("/voice/{conversation_id}")
async def voice_websocket(
    websocket: WebSocket,
    conversation_id: str,
    token: str | None = Query(default=None),
    voice_language: str | None = Query(default=None),
    tailscale_host: str | None = Query(default=None),
) -> None:
    """Bidirectional voice bridge between the phone and ADK/Gemini Live."""
    payload = _extract_jwt(websocket, token)
    if not payload:
        await websocket.close(code=4001, reason="Unauthorized")
        return

    user_id = payload["sub"]
    logger.info(f"[{conversation_id}] WebSocket connecting for user={user_id}")
    log_session_start(conversation_id, user_id)
    opik_session_start(conversation_id, user_id)

    await websocket.accept()

    await audio_session_manager.get_or_create_entry(conversation_id, user_id)
    speech_preferences = build_speech_preferences(voice_language)
    logger.info(
        f"[{conversation_id}] Live speech preferences: "
        f"language={speech_preferences.get('voice_language')} "
        f"voice={speech_preferences.get('voice_name')}"
    )

    try:
        session = await gemini_live_client.get_or_create_session(
            user_id=user_id,
            session_id=conversation_id,
        )
        preferred_network_host = _normalize_preferred_network_host(tailscale_host)
        if preferred_network_host:
            session.state["preferred_network_host"] = preferred_network_host
        session.state.setdefault("glasses_camera_ready", False)
        session.state.setdefault("glasses_capture_available", False)
        session.state.setdefault("listening_enabled", True)

        async def _send_request_location() -> None:
            await websocket.send_text(json.dumps({"type": "request_location"}))

        async def _send_control_message(message: dict) -> None:
            await websocket.send_text(json.dumps(message))

        location_channels.register(conversation_id, _send_request_location)
        control_channels.register(conversation_id, _send_control_message)
        visual_capture_channels.register(conversation_id)

        live_request_queue = gemini_live_client.new_request_queue()
        # Register the queue so inject_job_result() can speak completed jobs immediately
        # when the phone is still connected (fast jobs that finish during the session).
        audio_session_manager.register_live_queue(conversation_id, live_request_queue)
        live_events = gemini_live_client.start_live_session(
            user_id=user_id,
            session_id=conversation_id,
            live_request_queue=live_request_queue,
            speech_preferences=speech_preferences,
        )

        # Always inject a session-start prompt so Gervis speaks first.
        #
        # Three cases:
        #  1. Pending resume events (job completed while away) → inject full resume context
        #  2. Returning user (prior turns exist, no pending events) → inject welcome-back prompt
        #  3. New conversation (no turns yet) → inject new-session greeting prompt
        #
        # Without this injection Gemini waits silently for the user to speak first.
        pending_events = await get_pending_resume_events(conversation_id)

        if pending_events:
            summaries: list[str] = []
            for ev in pending_events:
                summary = ev.display_summary or ev.spoken_summary or f"{ev.event_type} completed"
                artifacts = ev.artifacts or {}
                items = artifacts.get("items") if isinstance(artifacts, dict) else artifacts
                if isinstance(items, list):
                    for item in items:
                        if item.get("type") == "url" and item.get("url"):
                            summary = f"{summary}. URL: {item['url']}"
                            break
                summaries.append(summary)
            session_prompt = (
                "[SYSTEM - authoritative resume status] "
                "The user has returned specifically to hear the result of background work. "
                "At least one background job has ALREADY COMPLETED. It is not pending, queued, "
                "or still running. Do not greet first, and do not ask how you can help before "
                "sharing the finished result. Start immediately with the completion news. "
                f"The completed result(s): {' '.join(summaries)} "
                "Mention the exact project URL once when available, then briefly offer next steps."
            )
            await acknowledge_all_resume_events(conversation_id)
            await set_conversation_state(conversation_id, "completed")
            logger.info(
                f"[{conversation_id}] Injecting resume context for {len(pending_events)} event(s)"
            )
        else:
            # Check if this is a returning user (conversation has prior turns)
            from services.conversation_service import get_turn_count
            turn_count = await get_turn_count(conversation_id)
            if turn_count > 0:
                session_prompt = (
                    "The user has reconnected to an existing conversation. "
                    "Greet them warmly and briefly — just 'Welcome back! What would you like to do?' "
                    "or similar. Keep it short."
                )
                logger.info(f"[{conversation_id}] Injecting welcome-back prompt (turns={turn_count})")
            else:
                session_prompt = (
                    "A new voice session has started. "
                    "Greet the user briefly and ask what how you can help today."
                )
                logger.info(f"[{conversation_id}] Injecting new-session greeting prompt")

        language_instruction = speech_preferences.get("language_instruction")
        if language_instruction:
            session_prompt = (
                "[SYSTEM - follow this for the full session] "
                f"{language_instruction} "
                f"{session_prompt}"
            )

        try:
            live_request_queue.send_content(
                types.Content(
                    role="user",
                    parts=[types.Part(text=session_prompt)],
                )
            )
        except AttributeError:
            logger.warning(
                f"[{conversation_id}] send_content not available in this ADK version "
                "— session-start prompt skipped (Gervis will wait for user to speak first)"
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
        _upstream_task(websocket, live_request_queue, conversation_id, user_id)
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

        log_session_end(conversation_id)
        opik_session_end(conversation_id)
        _turn_buffer.pop(conversation_id, None)
        _pending_text_inputs.pop(conversation_id, None)
        _tool_activity_timings.pop(conversation_id, None)
        logger.info(f"[{conversation_id}] Phone disconnected, starting grace timer")
        location_channels.unregister(conversation_id)
        control_channels.unregister(conversation_id)
        visual_capture_channels.unregister(conversation_id)
        audio_session_manager.unregister_live_queue(conversation_id)
        audio_session_manager.start_grace_timer(conversation_id)
        asyncio.create_task(set_conversation_idle(conversation_id))
