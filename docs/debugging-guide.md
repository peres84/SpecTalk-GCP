# SpecTalk — How to Debug & Test a Distributed System

A practical guide for juniors on how professional engineers think about, test, and debug
a system like this one. The architecture feels complex at first — this document breaks down
the mental model.

---

## The Big Picture: What "Distributed" Means

In a simple app, everything runs in one place: one process, one computer, one language.
When something breaks, you look in one place.

SpecTalk is different. A single "research arepas" voice request touches **6 separate systems**:

```
[Android App]
     ↓  WebSocket (voice audio)
[Cloud Run — FastAPI Backend]
     ↓  API call
[Gemini Live API — Google's servers]
     ↓  tool call triggers
[Cloud Tasks — job queue]
     ↓  HTTP POST
[Cloud Run — same backend, different request]
     ↓  API call
[Firebase FCM — Google's servers]
     ↓  push notification
[Android App]
```

Each arrow is a network call that can fail independently. The skill of debugging distributed
systems is **tracing a request across all these hops** and finding exactly where it broke.

---

## The Golden Rule: Follow the Request

Whenever something doesn't work, ask yourself:
> "How far did the request get before it failed?"

Work through each hop in order. Don't guess — look at the logs for each system.

---

## Part 1: How We Test Each Layer in Isolation

Before connecting everything together, you test each piece alone. This is called
**unit testing** or **integration testing at the layer boundary**.

### Layer 1: The Database

**How to test:** Connect directly to Neon with a SQL client (like DBeaver or TablePlus)
and run queries manually.

```sql
-- Did a job row get created?
SELECT * FROM jobs ORDER BY created_at DESC LIMIT 5;

-- Did the user's push token get saved?
SELECT id, email, push_token FROM users;

-- Did a resume event get created?
SELECT * FROM resume_events ORDER BY created_at DESC LIMIT 5;
```

**What to look for:** Are the rows there? Are the values correct? Is `status` updating
from `queued` → `running` → `completed`?

**Why this matters:** If the DB row is wrong, everything downstream will be wrong too.
Always verify the data layer first.

---

### Layer 2: The Backend API

**How to test:** Use `curl` or a tool like Postman to call endpoints directly, bypassing
the Android app entirely.

```bash
# Test health
curl https://gervis-backend-223350201590.us-central1.run.app/health

# Test auth
curl -X POST https://.../auth/session \
  -H "Content-Type: application/json" \
  -d '{"firebase_id_token": "<token>"}'

# Manually trigger a job completion (no Cloud Tasks needed)
curl -X POST https://.../internal/jobs/execute \
  -H "Content-Type: application/json" \
  -d '{"job_id":"<uuid>","job_type":"demo","conversation_id":"<uuid>","user_id":"<uuid>"}'
```

**Why this matters:** If the endpoint works via curl but not from the app, the bug is in
the Android code. If it fails via curl too, the bug is in the backend.

This is how we confirmed FCM notifications work — we bypassed the whole voice flow and
called `/internal/jobs/execute` directly with curl. Notification arrived. That told us
FCM was fine and the problem was upstream (Cloud Tasks not reaching the endpoint).

---

### Layer 3: The Background Job Queue (Cloud Tasks)

**How to test:** Watch the logs while triggering a job. You're looking for two log lines:

```
# Line 1: Backend enqueued the task successfully
Cloud Tasks task enqueued for job <uuid>

# Line 2: Cloud Tasks called back to the backend (this is a SEPARATE request)
[<conv_id>] Executing job <uuid> type=research
```

If you see line 1 but not line 2, Cloud Tasks received the task but couldn't deliver it
(wrong URL, auth error, your backend returned an error).

If you don't see line 1, the enqueue itself failed — check the error message.

**The key insight:** Cloud Tasks makes a brand new HTTP request to your backend. It's not
the same request that created the task. So you'll see two separate entries in the logs,
often seconds apart.

---

### Layer 4: Push Notifications (FCM)

**How to test:** Use the Firebase Console → Cloud Messaging → "Send test message", or
use the Admin SDK directly via curl/script.

```python
# scripts/test_fcm.py
import firebase_admin
from firebase_admin import messaging

firebase_admin.initialize_app()
message = messaging.Message(
    notification=messaging.Notification(title="Test", body="Hello"),
    token="<push_token_from_db>",
)
response = messaging.send(message)
print("Sent:", response)
```

**Why this matters:** FCM can fail silently. Testing it in isolation tells you if the
token is valid, if the SA has the right permissions, and if the device can receive notifications.

---

### Layer 5: The Voice Session (Gemini + WebSocket)

**How to test:** Use a WebSocket client tool like `websocat` or browser devtools.

```bash
# Install websocat (Windows)
winget install websocat

# Connect to the voice WebSocket
websocat "wss://gervis-backend-.../ws/voice/<conv_id>?token=<jwt>"
```

Or watch the Android logcat while using the app — the `BackendVoiceClient` logs every
control message it receives.

---

## Part 2: Reading Logs Like a Detective

Logs are your primary debugging tool in production. Here's how to read them.

### Cloud Run Logs

```powershell
# Live tail (shows new logs as they arrive)
gcloud run services logs tail gervis-backend --region=us-central1 --project=spectalk-488516

# Last 50 lines
gcloud run services logs read gervis-backend --region=us-central1 --project=spectalk-488516 --limit=50
```

**What each log line tells you:**

```
2026-03-19 02:14:14  Failed to enqueue Cloud Task for job 9bb79f2f: 400 Task.dispatchDeadline must be between [15s, 30m].
│                    │                                              │
│                    │                                              └── The actual error from Google's API
│                    └── Our log message with the job ID
└── Timestamp
```

**Pattern to look for:**
- `ERROR` or `Failed` → something broke, read the message carefully
- `200 OK`, `204 No Content` → HTTP success
- `101` on WebSocket upgrade → WebSocket connected successfully
- `400`, `403`, `500` → HTTP errors — read what follows

### Reading a Stack Trace

When you see a big block of indented text like this:

```
Traceback (most recent call last):
  File "/app/ws/voice_handler.py", line 140, in _downstream_task   ← where it happened
    async for event in live_events:
  File "...google/adk/runners.py", line 325, in run_live
    ...
google.genai.errors.APIError: 1011 None. Internal error occurred.   ← the actual error
```

**Always read the last line first.** That's the actual error. Then read the second-to-last
file in the stack — that's usually where your code touched the failing library.

In this example: `APIError: 1011` means Gemini's servers had an internal error. Not our bug.

---

## Part 3: The Debugging Workflow We Used in This Project

Here's exactly how we debugged the Cloud Tasks → FCM notification chain, step by step.
This is a real example from building SpecTalk.

### Problem: "No notification when job runs"

**Step 1: Reproduce it and get the job ID**
```
Asked Gervis: "research arepas from Venezuela"
Log showed: [conv_id] Background job started: 9bb79f2f type=research
```
We have a job ID. Now we can track it.

**Step 2: Check if the task was enqueued**
```
Log showed: Failed to enqueue Cloud Task for job 9bb79f2f: 400 Invalid URL
```
Task never left. The BACKEND_BASE_URL secret had `\r\r` (Windows carriage returns) appended.
Fix: update the secret with `printf` instead of `echo`.

**Step 3: Verify the fix and redeploy. Reproduce again.**
```
Log showed: Failed to enqueue Cloud Task for job ...: 400 Task.dispatchDeadline must be between [15s, 30m]
```
New error — different problem. We set `dispatch_deadline=3600s` but Cloud Tasks max is 1800s.
Fix: change to 1800s.

**Step 4: Verify the fix and redeploy. Reproduce again.**
(Pending — this is where we are now)
Expected: task enqueues → Cloud Tasks calls `/internal/jobs/execute` → FCM notification sent.

### What this teaches you

- **One error at a time.** Fix the first error, redeploy, look again. Don't try to fix
  everything you imagine might be wrong.
- **The error message is usually right.** `400 Task.dispatchDeadline must be between [15s, 30m]`
  tells you exactly what's wrong. Read error messages carefully before searching.
- **Isolate the layer.** We confirmed FCM works via curl before debugging Cloud Tasks.
  That meant we could focus on the enqueue step, not doubt FCM.

---

## Part 4: Common Errors and What They Mean

| Error | What it means | How to fix |
|-------|--------------|-----------|
| `400 Invalid URL` | A URL has illegal characters (often `\r` or `\n` from Windows copy-paste) | Use `printf` instead of `echo` when setting secrets |
| `400 dispatchDeadline must be between [15s, 30m]` | Cloud Tasks deadline too long | Max is 1800s (30 min) |
| `403 PERMISSION_DENIED` | Service account lacks an IAM role | Check `gcloud projects get-iam-policy` and add the missing role |
| `1011 Internal error` | Gemini's servers had a transient error | Usually self-resolves; retry the request |
| `TypeError: connect() got unexpected keyword argument 'sslmode'` | asyncpg doesn't understand psycopg2-style SSL params | Strip `sslmode=` from the DB URL, use `?ssl=require` |
| `connection is closed` | DB connection was stale (Neon auto-suspend) | `pool_pre_ping=True` in SQLAlchemy engine |
| `OOMKilled` / exit code 137 | Container ran out of memory | Increase `--memory` in Cloud Run (we went 512MiB → 1Gi) |
| `push_token is null` | FCM token was never sent to the backend | Register token proactively on every login, not just `onNewToken()` |

---

## Part 5: How to Test the Full End-to-End Flow

Once all layers work in isolation, this is the checklist for a full integration test:

```
1. Open the app → verify it reaches the backend (check /health)
2. Log in → verify auth/session returns a JWT and push token is registered
3. Say "Hey Gervis, start a demo job"
4. Verify in logs: job created → Cloud Task enqueued
5. Wait 5-10 seconds
6. Verify in logs: Cloud Task called /internal/jobs/execute → job completed → FCM sent
7. Check phone: push notification received
8. Tap notification → app opens the right conversation
9. Say "Hey Gervis" again → Gervis gives welcome-back message
10. Verify in DB: resume_event acknowledged, pending_resume_count = 0
```

If any step fails, you now know exactly which layer to look at.

---

## Part 6: Tools Every Backend Developer Uses

| Tool | Purpose | How we use it |
|------|---------|--------------|
| `curl` | Make HTTP requests from the terminal | Test API endpoints without needing the app |
| `gcloud run services logs` | Read Cloud Run logs | See what happened in production |
| DBeaver / TablePlus | SQL GUI client | Query the database directly |
| Postman | HTTP client with a UI | Test APIs with saved requests |
| `cat -A` | Show hidden characters in strings | Found the `\r\r` in BACKEND_BASE_URL |
| Firebase Console | Firebase web UI | Send test FCM notifications, view users |
| GCP Console → Cloud Tasks | View queued/failed tasks | See if tasks are being delivered |
| GCP Console → Cloud Run → Logs | Same as gcloud CLI but in browser | Easier to filter and search |

---

## Part 7: The Mindset

**You will spend more time debugging than writing code.** This is normal. Senior engineers
are not faster at writing code — they are faster at debugging because they have a systematic
approach:

1. **Don't guess.** Look at the actual error.
2. **Reproduce it.** If you can't reproduce it, you can't confirm it's fixed.
3. **Change one thing at a time.** If you change three things and it works, you don't know which one fixed it.
4. **Confirm each fix with evidence.** A log line, a DB row, a notification — not just "it seems to work".
5. **Understand why it broke, not just how to fix it.** The `\r\r` bug happened because PowerShell's `echo` adds carriage returns. Knowing why means you won't make the same mistake again.

The architecture of SpecTalk is complex — but it's made of simple pieces. Master each piece
in isolation, then connect them one at a time.
