"""
test_auth_session.py
--------------------
Full end-to-end auth test:
  1. Sign in to Firebase and get an ID token (using TEST_USER_EMAIL / TEST_USER_PASSWORD)
  2. Exchange that token for a product JWT via POST /auth/session on the local backend
  3. Print the product JWT and verify a protected endpoint works

Usage:
    uv run python scripts/test_auth_session.py

Required .env keys:
    FIREBASE_WEB_API_KEY   — Firebase project Web API Key
    TEST_USER_EMAIL        — email of an existing, verified Firebase user
    TEST_USER_PASSWORD     — password for that user

Optional .env keys:
    BACKEND_URL            — defaults to http://localhost:8080
"""

import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

from dotenv import load_dotenv
import os

load_dotenv(Path(__file__).parent.parent / ".env")

API_KEY      = os.getenv("FIREBASE_WEB_API_KEY", "")
EMAIL        = os.getenv("TEST_USER_EMAIL", "")
PASSWORD     = os.getenv("TEST_USER_PASSWORD", "")
BACKEND_URL  = os.getenv("BACKEND_URL", "http://localhost:8080").rstrip("/")

FIREBASE_SIGNIN_URL = (
    f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={API_KEY}"
)


# ── helpers ────────────────────────────────────────────────────────────────────

def post_json(url: str, body: dict, headers: dict = None) -> dict:
    payload = json.dumps(body).encode()
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=payload, headers=req_headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body_text = e.read().decode()
        try:
            error_data = json.loads(body_text)
        except Exception:
            error_data = {"raw": body_text}
        raise RuntimeError(f"HTTP {e.code} from {url}: {json.dumps(error_data, indent=2)}")


def get_json(url: str, bearer_token: str) -> dict:
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {bearer_token}"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"HTTP {e.code} from {url}: {e.read().decode()}")


def separator(title=""):
    width = 68
    if title:
        pad = (width - len(title) - 2) // 2
        print(f"\n{'─' * pad} {title} {'─' * (width - pad - len(title) - 2)}")
    else:
        print("─" * width)


# ── main ───────────────────────────────────────────────────────────────────────

def main():
    missing = [k for k, v in [
        ("FIREBASE_WEB_API_KEY", API_KEY),
        ("TEST_USER_EMAIL",      EMAIL),
        ("TEST_USER_PASSWORD",   PASSWORD),
    ] if not v]

    if missing:
        print(f"[ERROR] Missing .env keys: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)

    # ── Step 1: Firebase sign-in ───────────────────────────────────────────────
    separator("Step 1 — Firebase sign-in")
    print(f"  Email   : {EMAIL}")
    print(f"  Backend : {BACKEND_URL}")

    try:
        firebase_resp = post_json(FIREBASE_SIGNIN_URL, {
            "email": EMAIL,
            "password": PASSWORD,
            "returnSecureToken": True,
        })
    except RuntimeError as e:
        print(f"\n[FAIL] Firebase sign-in failed.\n{e}", file=sys.stderr)
        sys.exit(1)

    id_token = firebase_resp.get("idToken", "")
    print(f"\n  ✓ Got Firebase ID token ({len(id_token)} chars)")
    print(f"  Expires in : {firebase_resp.get('expiresIn')}s")

    # ── Step 2: Exchange for product JWT ──────────────────────────────────────
    separator("Step 2 — POST /auth/session")

    try:
        session_resp = post_json(
            f"{BACKEND_URL}/auth/session",
            {"firebase_id_token": id_token},
        )
    except RuntimeError as e:
        print(f"\n[FAIL] Backend auth exchange failed.\n{e}", file=sys.stderr)
        sys.exit(1)

    product_jwt = session_resp.get("access_token", "")
    user_id     = session_resp.get("user_id", "")
    user_email  = session_resp.get("email", "")

    print(f"\n  ✓ Product JWT received ({len(product_jwt)} chars)")
    print(f"  user_id : {user_id}")
    print(f"  email   : {user_email}")

    separator("Product JWT")
    print(product_jwt)

    # ── Step 3: Verify a protected endpoint ───────────────────────────────────
    separator("Step 3 — GET /conversations (protected)")

    try:
        convs = get_json(f"{BACKEND_URL}/conversations", product_jwt)
        print(f"\n  ✓ /conversations returned {len(convs)} item(s)")
    except RuntimeError as e:
        print(f"\n[FAIL] Protected endpoint check failed.\n{e}", file=sys.stderr)
        sys.exit(1)

    # ── Step 4: Create a voice session ────────────────────────────────────────
    separator("Step 4 — POST /voice/session/start")

    try:
        voice_resp = post_json(
            f"{BACKEND_URL}/voice/session/start",
            {},
            headers={"Authorization": f"Bearer {product_jwt}"},
        )
        print(f"\n  ✓ Voice session created")
        print(f"  conversation_id : {voice_resp.get('conversation_id')}")
        print(f"  state           : {voice_resp.get('state')}")
    except RuntimeError as e:
        print(f"\n[FAIL] Voice session start failed.\n{e}", file=sys.stderr)
        sys.exit(1)

    separator()
    print("\n  All checks passed. Phase 2 auth flow is working end-to-end.\n")


if __name__ == "__main__":
    main()
