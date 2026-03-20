"""Test OpenClaw POST /v1/responses endpoint.

Usage (from gervis-backend/):
    uv run python scripts/test_openclaw.py

Reads OPEN_CLAW_URL and OPEN_CLAW_TOKEN from .env
"""

import asyncio
import os
import sys
from pathlib import Path

# Load .env from gervis-backend/
env_path = Path(__file__).parent.parent / ".env"
if env_path.exists():
    for line in env_path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, val = line.partition("=")
            os.environ.setdefault(key.strip(), val.strip())

import httpx

OPENCLAW_URL = os.environ.get("OPEN_CLAW_URL", "").rstrip("/")
OPENCLAW_TOKEN = os.environ.get("OPEN_CLAW_TOKEN", "")

if not OPENCLAW_URL or not OPENCLAW_TOKEN:
    print("ERROR: OPEN_CLAW_URL or OPEN_CLAW_TOKEN not set in .env")
    sys.exit(1)

ENDPOINT = f"{OPENCLAW_URL}/v1/responses"
TEST_INPUT = "Say hello and confirm you are OpenClaw running correctly. One sentence."


async def test_no_stream() -> None:
    print(f"\n--- Non-streaming test ---")
    print(f"POST {ENDPOINT}")

    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            ENDPOINT,
            headers={
                "Authorization": f"Bearer {OPENCLAW_TOKEN}",
                "Content-Type": "application/json",
                "x-openclaw-agent-id": "main",
                "x-openclaw-session-key": "spectalk-test-001",
            },
            json={
                "model": "openclaw",
                "input": TEST_INPUT,
                "stream": False,
            },
        )

    print(f"HTTP {resp.status_code}")
    print(f"Headers: {dict(resp.headers)}")
    try:
        print(f"Body: {resp.json()}")
    except Exception:
        print(f"Raw: {resp.text[:500]}")


async def test_stream() -> None:
    print(f"\n--- Streaming test (SSE) ---")
    print(f"POST {ENDPOINT}")

    async with httpx.AsyncClient(timeout=120) as client:
        async with client.stream(
            "POST",
            ENDPOINT,
            headers={
                "Authorization": f"Bearer {OPENCLAW_TOKEN}",
                "Content-Type": "application/json",
                "x-openclaw-agent-id": "main",
                "x-openclaw-session-key": "spectalk-test-002",
            },
            json={
                "model": "openclaw",
                "input": TEST_INPUT,
                "stream": True,
            },
        ) as resp:
            print(f"HTTP {resp.status_code}")
            print(f"Content-Type: {resp.headers.get('content-type')}")
            print("--- Events ---")
            async for line in resp.aiter_lines():
                if line:
                    print(line)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "stream"
    if mode == "nostream":
        asyncio.run(test_no_stream())
    else:
        asyncio.run(test_stream())
