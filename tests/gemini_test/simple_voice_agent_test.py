"""Minimal Gemini Live API voice+vision+text test using ADK.

Run:
  uvicorn demo.simple_voice_agent_test:app --reload --port 8010
"""

import asyncio
import base64
import json
import os
import sys
from pathlib import Path

DEMO_DIR = Path(__file__).resolve().parent
LOCAL_VENV_PYTHON = DEMO_DIR / ".venv" / "Scripts" / "python.exe"


def _bootstrap_runtime() -> None:
    """Relaunch inside demo/.venv if current Python misses dependencies."""
    try:
        import dotenv  # noqa: F401
        import fastapi  # noqa: F401
        import google.adk  # noqa: F401
        import google.genai  # noqa: F401
        import uvicorn  # noqa: F401
        return
    except ModuleNotFoundError:
        pass

    current = Path(sys.executable).resolve()
    is_direct_run = __name__ == "__main__"
    if is_direct_run and LOCAL_VENV_PYTHON.exists() and current != LOCAL_VENV_PYTHON.resolve():
        os.execv(
            str(LOCAL_VENV_PYTHON),
            [str(LOCAL_VENV_PYTHON), str(Path(__file__).resolve()), *sys.argv[1:]],
        )

    raise SystemExit("Missing dependencies. Run: `uv sync --project demo` and retry.")


_bootstrap_runtime()

import uvicorn
from dotenv import load_dotenv
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from google.adk.agents import Agent
from google.adk.agents.live_request_queue import LiveRequestQueue
from google.adk.agents.run_config import RunConfig, StreamingMode
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types

load_dotenv(dotenv_path=DEMO_DIR / ".env")

APP_NAME = "simple-voice-agent-test"
MODEL = os.getenv("DEMO_AGENT_MODEL", "gemini-2.5-flash-native-audio-preview-12-2025")

agent = Agent(
    name="simple_voice_agent",
    model=MODEL,
    instruction=(
        "You are a concise real-time assistant. Use audio, text, and image context. "
        "Keep replies short and practical."
    ),
)

app = FastAPI()
session_service = InMemorySessionService()
runner = Runner(app_name=APP_NAME, agent=agent, session_service=session_service)


@app.get("/")
async def index() -> FileResponse:
    return FileResponse(Path(__file__).with_name("simple_voice_agent_test.html"))


def build_run_config() -> RunConfig:
    vad_config = types.RealtimeInputConfig(
        automatic_activity_detection=types.AutomaticActivityDetection(disabled=True),
        activity_handling=types.ActivityHandling.START_OF_ACTIVITY_INTERRUPTS,
    )

    is_native_audio = "native-audio" in MODEL.lower()
    if is_native_audio:
        return RunConfig(
            streaming_mode=StreamingMode.BIDI,
            response_modalities=[types.Modality.AUDIO],
            realtime_input_config=vad_config,
            input_audio_transcription=types.AudioTranscriptionConfig(),
            output_audio_transcription=types.AudioTranscriptionConfig(),
            session_resumption=types.SessionResumptionConfig(),
        )
    return RunConfig(
        streaming_mode=StreamingMode.BIDI,
        response_modalities=[types.Modality.TEXT],
        realtime_input_config=vad_config,
        session_resumption=types.SessionResumptionConfig(),
    )


def event_to_client_payload(event) -> dict:
    payload = {
        "author": event.author or "agent",
        "turn_complete": bool(event.turn_complete),
        "interrupted": bool(event.interrupted),
        "text": "",
        "audio_b64": None,
    }

    if not event.content:
        return payload

    text_parts = []
    for part in event.content.parts:
        if part.text:
            text_parts.append(part.text)
        if part.inline_data and part.inline_data.mime_type.startswith("audio/pcm"):
            payload["audio_b64"] = base64.b64encode(part.inline_data.data).decode("ascii")

    payload["text"] = "".join(text_parts).strip()
    return payload


@app.websocket("/ws/{user_id}/{session_id}")
async def ws_endpoint(websocket: WebSocket, user_id: str, session_id: str) -> None:
    await websocket.accept()

    session = await session_service.get_session(
        app_name=APP_NAME, user_id=user_id, session_id=session_id
    )
    if not session:
        await session_service.create_session(
            app_name=APP_NAME, user_id=user_id, session_id=session_id
        )

    run_config = build_run_config()
    queue = LiveRequestQueue()

    async def upstream() -> None:
        while True:
            message = await websocket.receive()
            if "bytes" in message:
                queue.send_realtime(
                    types.Blob(mime_type="audio/pcm;rate=16000", data=message["bytes"])
                )
                continue

            if "text" not in message:
                continue

            data = json.loads(message["text"])
            msg_type = data.get("type")

            if msg_type == "text":
                queue.send_content(
                    types.Content(role="user", parts=[types.Part(text=data.get("text", ""))])
                )
            elif msg_type == "image":
                image_bytes = base64.b64decode(data.get("data", ""))
                queue.send_realtime(
                    types.Blob(
                        mime_type=data.get("mimeType", "image/jpeg"),
                        data=image_bytes,
                    )
                )
            elif msg_type == "activity_start":
                queue.send_activity_start()
            elif msg_type == "activity_end":
                queue.send_activity_end()

    async def downstream() -> None:
        async for event in runner.run_live(
            user_id=user_id,
            session_id=session_id,
            live_request_queue=queue,
            run_config=run_config,
        ):
            payload = event_to_client_payload(event)
            if (
                payload["text"]
                or payload["audio_b64"]
                or payload["turn_complete"]
                or payload["interrupted"]
            ):
                await websocket.send_text(json.dumps(payload))

    try:
        await asyncio.gather(upstream(), downstream())
    except WebSocketDisconnect:
        pass
    finally:
        queue.close()


def main() -> None:
    host = os.getenv("DEMO_HOST", "127.0.0.1")
    port = int(os.getenv("DEMO_PORT", "8010"))
    reload_enabled = os.getenv("DEMO_RELOAD", "0").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }

    uvicorn.run(
        "simple_voice_agent_test:app",
        host=host,
        port=port,
        reload=reload_enabled,
        app_dir=str(DEMO_DIR),
    )


if __name__ == "__main__":
    main()
