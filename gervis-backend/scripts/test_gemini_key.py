"""
test_gemini_key.py
------------------
Verifies the GEMINI_API_KEY in .env is valid and can reach the Gemini Live API.

Runs three checks:
  1. Key present  — GEMINI_API_KEY is set and non-empty
  2. Text model   — basic generate_content call (gemini-2.5-flash) returns a response
  3. Live model   — opens a real Gemini Live bidi session, sends a text turn, receives
                    at least one event, then closes cleanly

Usage:
    uv run python scripts/test_gemini_key.py

Required .env key:
    GEMINI_API_KEY   — your key from https://aistudio.google.com/app/apikey
"""

import asyncio
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).parent.parent / ".env")

# ── helpers ──────────────────────────────────────────────────────────────────

WIDTH = 68

def header(title: str):
    print(f"\n-- {title} {'-' * (WIDTH - len(title) - 4)}")

def ok(msg: str):
    print(f"  [OK] {msg}")

def fail(msg: str):
    print(f"  [FAIL] {msg}")
    sys.exit(1)

def info(msg: str):
    print(f"    {msg}")

# ── check 1: key present ──────────────────────────────────────────────────────

header("Check 1 - GEMINI_API_KEY present")

api_key = os.getenv("GEMINI_API_KEY", "").strip()
if not api_key:
    fail("GEMINI_API_KEY is not set in .env")
if api_key.lower().startswith("your-"):
    fail("GEMINI_API_KEY still has the placeholder value — set a real key in .env")

info(f"Key  : {api_key[:8]}...{api_key[-4:]}  ({len(api_key)} chars)")
ok("Key is present")

# ── check 2: text model ───────────────────────────────────────────────────────

header("Check 2 - Text generation (gemini-2.5-flash)")

try:
    from google import genai
    from google.genai import types as gtypes
except ImportError:
    fail("google-genai is not installed — run: uv sync")

try:
    client = genai.Client(api_key=api_key)
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents="Reply with exactly three words: the sky is",
    )
    text = (response.text or "").strip()
    info(f"Response : {text!r}")
    if not text:
        fail("Empty response from Gemini — key may be invalid or quota exceeded")
    ok(f"Text generation works")
except Exception as e:
    fail(f"generate_content failed: {e}")

# ── check 3: live model (bidi session) ───────────────────────────────────────

header("Check 3 - Gemini Live session (gemini-2.5-flash-native-audio-preview-12-2025)")

LIVE_MODEL = os.getenv(
    "GEMINI_MODEL",
    "gemini-2.5-flash-native-audio-preview-12-2025",
)
info(f"Model : {LIVE_MODEL}")


async def _test_live():
    try:
        from google.adk.agents import Agent
        from google.adk.agents.live_request_queue import LiveRequestQueue
        from google.adk.agents.run_config import RunConfig
        from google.adk.runners import InMemoryRunner
    except ImportError:
        fail("google-adk is not installed — run: uv sync")

    os.environ["GOOGLE_API_KEY"] = api_key

    agent = Agent(
        name="key_tester",
        model=LIVE_MODEL,
        instruction="You are a test assistant. Reply very briefly.",
    )
    runner = InMemoryRunner(app_name="key-test", agent=agent)
    session = await runner.session_service.create_session(
        app_name="key-test", user_id="test"
    )

    live_request_queue = LiveRequestQueue()
    run_config = RunConfig(
        streaming_mode="bidi",
        response_modalities=["AUDIO"],
        input_audio_transcription=gtypes.AudioTranscriptionConfig(),
        output_audio_transcription=gtypes.AudioTranscriptionConfig(),
    )

    live_events = runner.run_live(
        user_id="test",
        session_id=session.id,
        live_request_queue=live_request_queue,
        run_config=run_config,
    )

    # Send a short text turn and wait for at least one event back
    from google.genai.types import Content, Part
    live_request_queue.send_content(
        Content(role="user", parts=[Part.from_text(text="Say hello.")])
    )

    events_received = 0
    try:
        async with asyncio.timeout(15):
            async for event in live_events:
                events_received += 1
                if event.content:
                    for part in event.content.parts:
                        if part.text:
                            info(f"Event text   : {part.text!r}")
                        if part.inline_data:
                            info(f"Event audio  : {len(part.inline_data.data)} bytes PCM")
                if event.turn_complete or events_received >= 5:
                    live_request_queue.close()
                    break
    except TimeoutError:
        live_request_queue.close()
        fail(
            "No events received from Gemini Live within 15s — "
            "key may lack access to the Live API or the model name is wrong"
        )
    finally:
        live_request_queue.close()

    if events_received == 0:
        fail("Gemini Live session opened but returned zero events")

    ok(f"Live session works - received {events_received} event(s)")


asyncio.run(_test_live())

# ── summary ───────────────────────────────────────────────────────────────────

print(f"\n{'-' * WIDTH}")
print()
print("  All checks passed. Your GEMINI_API_KEY is valid and can reach")
print("  both the standard Gemini API and the Gemini Live streaming API.")
print()
