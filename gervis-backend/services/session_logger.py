"""Structured human-readable logging for Gervis voice sessions.

Hooks into the ADK event stream to surface:
  - What the user said (final transcript only)
  - Which tool Gervis decided to call, and with what arguments
  - What the tool returned
  - What Gervis said back to the user (final transcript only)
  - Barge-ins and turn completions

All log lines are prefixed with [conv:XXXXXXXX] (first 8 chars of conversation_id)
so you can filter an entire conversation in Cloud Run Logs:
  resource.type="cloud_run_revision" textPayload:"[conv:abc12345]"

Log levels:
  INFO  — user speech, tool calls/results, Gervis speech, turn complete, barge-in
  DEBUG — partial transcripts, audio bytes, unknown event types
"""

import json
import logging
import time
from typing import Any

logger = logging.getLogger(__name__)  # "services.session_logger" — same pattern as all other modules

# Per-call timing: function_call.id → start_time (module-level, in-process only)
_call_timings: dict[str, float] = {}

_SEP = "─" * 50


def _short(conv_id: str) -> str:
    """Return a compact conversation prefix: first 8 chars."""
    return conv_id[:8] if conv_id else "????????"


def _trim(text: str, max_len: int = 160) -> str:
    """Trim and sanitise a string for log output."""
    text = text.replace("\n", " ").strip()
    if len(text) > max_len:
        return text[:max_len] + "…"
    return text


def _fmt_args(args: dict) -> str:
    """Format tool arguments as a compact, readable string."""
    if not args:
        return "(no args)"
    parts = []
    for k, v in args.items():
        if isinstance(v, str):
            rendered = f'"{_trim(v, 80)}"'
        elif isinstance(v, (dict, list)):
            rendered = _trim(json.dumps(v, separators=(",", ":")), 120)
        else:
            rendered = str(v)
        parts.append(f"{k}={rendered}")
    return ",  ".join(parts)


def _fmt_result(response: Any) -> str:
    """Format a tool response for log output."""
    if response is None:
        return "(None)"
    if isinstance(response, str):
        return f'"{_trim(response, 120)}"'
    if isinstance(response, dict):
        # Show top-level keys with previews
        parts = []
        for k, v in list(response.items())[:5]:
            if isinstance(v, str):
                parts.append(f"{k}={_trim(v, 60)!r}")
            else:
                parts.append(f"{k}={type(v).__name__}")
        return "{" + ",  ".join(parts) + "}"
    return _trim(str(response), 120)


def log_session_start(conv_id: str, user_id: str) -> None:
    """Log when a WebSocket session opens."""
    p = _short(conv_id)
    logger.info(f"[conv:{p}] {'═' * 48}")
    logger.info(f"[conv:{p}] 🔌  SESSION OPEN    user={user_id[:16]}…  conv={conv_id}")
    logger.info(f"[conv:{p}] {'═' * 48}")


def log_session_end(conv_id: str) -> None:
    """Log when a WebSocket session closes."""
    p = _short(conv_id)
    logger.info(f"[conv:{p}] 🔌  SESSION CLOSED  {_SEP}")


def log_adk_event(conv_id: str, event: Any) -> None:
    """Inspect an ADK Event and emit structured log lines.

    Call this inside the _downstream_task loop for every event yielded
    by run_live(). Audio bytes are logged at DEBUG level only.
    """
    p = _short(conv_id)

    # ── Barge-in ──────────────────────────────────────────────────────────
    if event.interrupted:
        logger.info(f"[conv:{p}] ⚡  BARGE-IN    user interrupted Gervis mid-speech")
        return

    # ── Tool calls (Gervis decided to call a tool) ─────────────────────
    fn_calls = event.get_function_calls() if hasattr(event, "get_function_calls") else []
    for fc in fn_calls:
        tool_id = getattr(fc, "id", None) or fc.name
        _call_timings[tool_id] = time.monotonic()
        args_str = _fmt_args(fc.args or {})
        logger.info(f"[conv:{p}] 🔧  TOOL CALL   {fc.name}")
        logger.info(f"[conv:{p}]              ↳ {args_str}")

    # ── Tool responses (tool returned a value) ─────────────────────────
    fn_responses = event.get_function_responses() if hasattr(event, "get_function_responses") else []
    for fr in fn_responses:
        # Try to compute elapsed time using the matching call id
        elapsed_str = ""
        if fr.id and fr.id in _call_timings:
            ms = int((time.monotonic() - _call_timings.pop(fr.id)) * 1000)
            elapsed_str = f"  ({ms}ms)"
        elif fr.name in _call_timings:
            ms = int((time.monotonic() - _call_timings.pop(fr.name)) * 1000)
            elapsed_str = f"  ({ms}ms)"

        result_str = _fmt_result(fr.response)
        logger.info(f"[conv:{p}] ✅  TOOL DONE   {fr.name}{elapsed_str}")
        logger.info(f"[conv:{p}]              ↳ {result_str}")

    # ── Content parts ─────────────────────────────────────────────────
    if not event.content:
        if event.turn_complete:
            logger.info(f"[conv:{p}] ✓   TURN COMPLETE  {_SEP}")
        return

    role = event.content.role  # "user" | "model" | tool name

    for part in event.content.parts:
        # ── Thought (Gemini 2.5 thinking tokens) ────────────────────
        if getattr(part, "thought", False):
            if not event.partial:
                text = _trim(part.text or "", 300)
                logger.info(f"[conv:{p}] 💭  THINKING    {text}")
            continue

        # ── Text: user transcript ─────────────────────────────────
        if part.text and role == "user":
            if event.partial:
                logger.debug(f"[conv:{p}] 🎤  USER (partial)  {_trim(part.text, 80)}")
            else:
                logger.info(f"[conv:{p}] 🎤  USER   \"{_trim(part.text)}\"")

        # ── Text: Gervis response ─────────────────────────────────
        elif part.text and role == "model":
            if event.partial:
                logger.debug(f"[conv:{p}] 🤖  GERVIS (partial)  {_trim(part.text, 80)}")
            else:
                logger.info(f"[conv:{p}] 🤖  GERVIS  \"{_trim(part.text)}\"")

        # ── Audio bytes ───────────────────────────────────────────
        elif part.inline_data and part.inline_data.mime_type.startswith("audio/pcm"):
            logger.debug(
                f"[conv:{p}] 🔊  AUDIO    {len(part.inline_data.data)} bytes"
            )

    # ── Turn complete ──────────────────────────────────────────────
    if event.turn_complete:
        logger.info(f"[conv:{p}] ✓   TURN COMPLETE  {_SEP}")
