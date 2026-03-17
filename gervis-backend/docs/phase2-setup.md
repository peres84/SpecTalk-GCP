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

If it says `INFO ... Running upgrade ... -> a3f8c1d2e9b4` without errors, the schema is up to date.

Verify in the Neon dashboard (SQL editor):
```sql
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

Expected tables: `assets`, `conversations`, `jobs`, `pending_actions`, `resume_events`, `turns`, `users`.

---

## Step 3 — Run the backend locally

```bash
cd gervis-backend
uv run uvicorn main:app --reload --port 8080
```

Expected startup output — no errors, and this line:
```
INFO:     Application startup complete.
```

If you see a database error, check that your Neon connection string in `.env` still uses
`ssl=require` (not `sslmode=require`) and has no `channel_binding` parameter.

---

## Step 4 — Test POST /auth/session

Open the interactive docs at **http://localhost:8080/docs** → find `POST /auth/session`.

You need a real Firebase ID token to test this. The easiest way:

### Option A — From the Android app logs

1. Build and run the Android app on an emulator
2. Sign in or register
3. In Android Studio Logcat, filter for `AuthViewModel`
4. The token exchange will fire — check that it succeeds

### Option B — From Firebase REST API (no app needed)

```bash
# Sign in with an existing Firebase account
curl -s -X POST \
  "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=YOUR_FIREBASE_WEB_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"email":"your@email.com","password":"yourpassword","returnSecureToken":true}' \
  | python -m json.tool
```

Get your **Web API Key** from Firebase Console → Project settings → General → Web API key.

Copy the `idToken` from the response, then:

```bash
curl -s -X POST http://localhost:8080/auth/session \
  -H "Content-Type: application/json" \
  -d '{"firebase_id_token":"PASTE_ID_TOKEN_HERE"}' \
  | python -m json.tool
```

Expected response:
```json
{
  "access_token": "eyJ...",
  "token_type": "bearer",
  "user_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "email": "your@email.com"
}
```

A row should now appear in the `users` table in Neon.

---

## Step 5 — Test the protected endpoints

Use the `access_token` from Step 4:

```bash
TOKEN="eyJ..."

# List conversations (should return empty array for new user)
curl -s http://localhost:8080/conversations \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool

# Start a voice session (creates a conversation row)
curl -s -X POST http://localhost:8080/voice/session/start \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool
```

Expected for voice session:
```json
{
  "conversation_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "state": "idle"
}
```

---

## Step 6 — Android: point the app at the local backend

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

## Step 7 — End-to-end test checklist

Run the Android app with the backend running locally.

- [ ] App launches → splash screen → navigates to Login (unauthenticated)
- [ ] Register with email → verification email sent
- [ ] Verify email → sign in → `POST /auth/session` fires → `Authenticated` state
- [ ] Check Neon `users` table — row created with correct `firebase_uid` and `email`
- [ ] Sign out → JWT cleared from `EncryptedSharedPreferences`
- [ ] Sign in again → JWT exchanged again, fast path on next cold start
- [ ] Google sign-in → same flow, `POST /auth/session` fires, user row created
- [ ] Check Logcat for any `AuthViewModel` or `TokenRepository` errors

---

## Current `.env` reference

Your `.env` is already correct for this phase — no changes needed:

```env
# Firebase Admin SDK
FIREBASE_PROJECT_ID=spectalk-488516
FIREBASE_SERVICE_ACCOUNT_JSON={}   ← leave as-is, ADC is used instead

# JWT
JWT_SECRET=3e865eb9024b6c0be7629880910af56805fef3e707c420afa2ca1a2dee44eb28
JWT_ALGORITHM=HS256
JWT_EXPIRE_HOURS=720

# Database (Neon)
DATABASE_URL=postgresql+asyncpg://neondb_owner:...@...neon.tech/neondb?ssl=require

# App
ENVIRONMENT=development
ALLOWED_ORIGINS=["http://localhost:3000"]
```

The backend detects that `FIREBASE_SERVICE_ACCOUNT_JSON` is not a valid file path and
automatically falls back to ADC (your `gcloud auth application-default login` session).
`FIREBASE_PROJECT_ID` tells it which project to validate tokens against.

---

## What's left before Phase 2 is approved

- [ ] `uv run alembic upgrade head` — full schema applied to Neon
- [ ] `POST /auth/session` returns a valid JWT (tested via curl or Android app)
- [ ] `POST /voice/session/start` creates a conversation row in Neon
- [ ] Android app exchanges token end-to-end (Logcat shows no errors)

Once all boxes are checked → Phase 2 approved → start Phase 3 (Gemini Live voice agent).
