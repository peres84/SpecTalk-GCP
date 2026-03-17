# Phase 2 Setup — Backend Foundation

**Status:** In progress
**Project ID:** `spectalk-488516`
**Database:** Neon PostgreSQL (already configured)
**Firebase:** Already set up — email/password + Google, SHA-1 registered

This guide covers exactly what remains to bring Phase 2 to a working state.
Skip anything already marked ✅.

---

## What's already done

- ✅ Firebase project created (`spectalk-488516`)
- ✅ Email/Password and Google sign-in enabled in Firebase Console
- ✅ Android SHA-1 fingerprint registered in Firebase Console
- ✅ `google-services.json` in the Android app
- ✅ Neon PostgreSQL instance running — connection string in `.env`
- ✅ `JWT_SECRET` generated and in `.env`
- ✅ `FIREBASE_PROJECT_ID` set in `.env`
- ✅ Authenticated via `gcloud` CLI — ADC handles Firebase token verification locally
- ✅ Backend code: `POST /auth/session`, all ORM models, Alembic migrations

No service account JSON file needed. The backend uses your gcloud CLI credentials
(`gcloud auth application-default login`) locally, and the Cloud Run service account
automatically in production. `FIREBASE_PROJECT_ID` in `.env` tells the Firebase Admin SDK
which project to validate tokens against.

---

## Step 1 — Run the new migration against Neon

A second migration was added since the initial setup (`a3f8c1d2e9b4_add_full_schema`).
It adds `push_token` to users and creates the `conversations`, `turns`, `jobs`,
`pending_actions`, `resume_events`, and `assets` tables.

```bash
cd gervis-backend
uv run alembic upgrade head
```

Expected output:
```
INFO  [alembic.runtime.migration] Running upgrade 55eaaead7014 -> a3f8c1d2e9b4, add full schema
```

Verify in the Neon dashboard (SQL editor):
```sql
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

Expected tables: `assets`, `conversations`, `jobs`, `pending_actions`, `resume_events`, `turns`, `users`.

---

## Step 2 — Run the backend locally

```bash
cd gervis-backend
uv run uvicorn main:app --reload --port 8080 --host 0.0.0.0
```

Expected startup output — no errors, and this line:
```
INFO:     Application startup complete.
```

---

## Step 3 — Add test credentials to .env

Add these four keys to your `gervis-backend/.env`:

```env
# Testing scripts
FIREBASE_WEB_API_KEY=your-firebase-web-api-key
TEST_USER_EMAIL=test@example.com
TEST_USER_PASSWORD=your-test-password
BACKEND_URL=http://localhost:8080
```

**Where to get `FIREBASE_WEB_API_KEY`:**
Firebase Console → select `spectalk-488516` → gear icon → **Project settings**
→ **General** tab → scroll down to **Your apps** → **Web API key** — copy it.

`TEST_USER_EMAIL` and `TEST_USER_PASSWORD` must be an existing, verified Firebase user
(same account you registered with in the Android app, or any account you created).

---

## Step 4 — Run the test scripts

**Just the Firebase token** (if you need the raw token for something else):
```bash
cd gervis-backend
uv run python scripts/get_firebase_token.py
```

**Full end-to-end test** — Firebase sign-in → product JWT → protected endpoints:
```bash
cd gervis-backend
uv run python scripts/test_auth_session.py
```

Expected output:
```
── Step 1 — Firebase sign-in ──────────────────────────────────────────
  Email   : test@example.com
  Backend : http://localhost:8080

  ✓ Got Firebase ID token (1000+ chars)
  Expires in : 3600s

── Step 2 — POST /auth/session ────────────────────────────────────────

  ✓ Product JWT received (200+ chars)
  user_id : xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  email   : test@example.com

── Step 3 — GET /conversations (protected) ────────────────────────────

  ✓ /conversations returned 0 item(s)

── Step 4 — POST /voice/session/start ─────────────────────────────────

  ✓ Voice session created
  conversation_id : xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  state           : idle

────────────────────────────────────────────────────────────────────────

  All checks passed. Phase 2 auth flow is working end-to-end.
```

A row should appear in the Neon `users` table and a row in `conversations`.

---

## Step 5 — Android: point the app at the local backend

The Android app is already wired to call `POST /auth/session`.
For an emulator, `http://10.0.2.2:8080` reaches your host machine's port 8080.

`android/app/src/main/res/values/strings.xml` already has:
```xml
<string name="backend_base_url">http://10.0.2.2:8080</string>
```

No change needed for emulator testing. For a physical device on the same Wi-Fi:
```xml
<string name="backend_base_url">http://192.168.x.x:8080</string>
```
Replace `192.168.x.x` with your machine's local IP (`ipconfig` on Windows).

---

## Step 6 — End-to-end test checklist

- [ ] `uv run alembic upgrade head` — all 7 tables created in Neon
- [ ] `uv run python scripts/test_auth_session.py` — all 4 steps pass
- [ ] Neon `users` table has a row with correct `firebase_uid` and `email`
- [ ] Neon `conversations` table has a row from the voice session start call
- [ ] Android app signs in → navigates to Home (no errors in Logcat)
- [ ] Sign out → sign in again → fast path (no backend call on cold start with stored JWT)

Once all boxes are checked → Phase 2 approved → start Phase 3 (Gemini Live voice agent).

---

## Current `.env` reference

```env
# Firebase Admin SDK
FIREBASE_PROJECT_ID=spectalk-488516
FIREBASE_SERVICE_ACCOUNT_JSON={}

# JWT
JWT_SECRET=3e865eb9024b6c0be7629880910af56805fef3e707c420afa2ca1a2dee44eb28
JWT_ALGORITHM=HS256
JWT_EXPIRE_HOURS=720

# Database (Neon)
DATABASE_URL=postgresql+asyncpg://neondb_owner:...@...neon.tech/neondb?ssl=require

# App
ENVIRONMENT=development
ALLOWED_ORIGINS=["http://localhost:3000"]

# Testing scripts
FIREBASE_WEB_API_KEY=your-firebase-web-api-key
TEST_USER_EMAIL=test@example.com
TEST_USER_PASSWORD=your-test-password
BACKEND_URL=http://localhost:8080
```
