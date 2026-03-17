"""
get_firebase_token.py
---------------------
Exchange TEST_USER_EMAIL + TEST_USER_PASSWORD for a Firebase ID token
using the Firebase Auth REST API.

Usage:
    uv run python scripts/get_firebase_token.py

Output:
    Prints the Firebase ID token to stdout so it can be copy-pasted
    or piped into test_auth_session.py.

Required .env keys:
    FIREBASE_WEB_API_KEY   — Firebase project Web API Key
                             (Firebase Console → Project settings → General → Web API key)
    TEST_USER_EMAIL        — email of an existing, verified Firebase user
    TEST_USER_PASSWORD     — password for that user
"""

import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

# Load .env from the gervis-backend directory
from dotenv import load_dotenv
import os

load_dotenv(Path(__file__).parent.parent / ".env")

API_KEY    = os.getenv("FIREBASE_WEB_API_KEY", "")
EMAIL      = os.getenv("TEST_USER_EMAIL", "")
PASSWORD   = os.getenv("TEST_USER_PASSWORD", "")

FIREBASE_SIGNIN_URL = (
    f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={API_KEY}"
)


def main():
    missing = [k for k, v in [
        ("FIREBASE_WEB_API_KEY", API_KEY),
        ("TEST_USER_EMAIL",      EMAIL),
        ("TEST_USER_PASSWORD",   PASSWORD),
    ] if not v]

    if missing:
        print(f"[ERROR] Missing .env keys: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)

    payload = json.dumps({
        "email": EMAIL,
        "password": PASSWORD,
        "returnSecureToken": True,
    }).encode()

    req = urllib.request.Request(
        FIREBASE_SIGNIN_URL,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = json.loads(e.read())
        message = body.get("error", {}).get("message", str(e))
        print(f"[ERROR] Firebase sign-in failed: {message}", file=sys.stderr)
        sys.exit(1)

    token = data.get("idToken", "")
    if not token:
        print("[ERROR] Response had no idToken field.", file=sys.stderr)
        sys.exit(1)

    print("\n── Firebase ID token ──────────────────────────────────────────────")
    print(token)
    print("───────────────────────────────────────────────────────────────────")
    print(f"\nSigned in as : {data.get('email')}")
    print(f"Expires in   : {data.get('expiresIn')}s")
    print("\nCopy the token above and use it with test_auth_session.py\n")


if __name__ == "__main__":
    main()
