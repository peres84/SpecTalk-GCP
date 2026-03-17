# Phase 3 Testing Guide — Voice Agent + Gemini Live

**Goal:** The backend is a full real-time voice agent. Gemini Live runs server-side via Google ADK.
The audio WebSocket bridge (`WS /ws/voice/{conversation_id}`) is live. Search and Maps work.
Turns persist to the database. No Gemini key exists anywhere on the Android app.

Work through this guide top to bottom. All checkboxes must pass before marking Phase 3 approved.

---

## Prerequisites

- Phase 2 is approved and the backend runs locally (`uv run uvicorn main:app --reload --port 8080`)
- Android app is on a physical device on the same Wi-Fi as your dev machine
- You have a Gemini API key from [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)

---

## Step 1 — Add GEMINI_API_KEY to .env

Open `gervis-backend/.env` and set:

```env
GEMINI_API_KEY=your-key-here
```

This single key covers everything: the live voice session, Google Search grounding, and Google Maps
grounding. No other API keys are needed for Phase 3.

---

## Step 2 — Start the backend and verify startup

```bash
cd gervis-backend
uv run uvicorn main:app --reload --port 8080 --host 0.0.0.0
```

Expected startup output — no errors, and this line near the end:

```
INFO:     Application startup complete.
```

Also look for the ADK init log line:

```
INFO - GeminiLiveClient initialized with model=gemini-2.5-flash-native-audio-preview-12-2025
```

If you see `RuntimeError: GeminiLiveClient not initialized` or any import error, check that
`GEMINI_API_KEY` is set in `.env` and `uv sync` has been run.

- [ ] Server starts with no errors
- [ ] ADK init line appears in the log

---

## Step 3 — Verify the WebSocket route is registered

```bash
curl http://localhost:8080/openapi.json | python -m json.tool | grep "ws/voice"
```

Expected: the path `/ws/voice/{conversation_id}` appears in the output.

Alternatively, open `http://localhost:8080/docs` in a browser — the WebSocket route will not appear
in Swagger UI (WebSocket routes are not listed there), but the server should load without errors.

- [ ] Server responds to `GET /health` → `{"status": "ok"}`

---

## Step 4 — Get a product JWT for WebSocket testing

Run the existing auth test script to get a JWT you can use for manual WebSocket tests:

```bash
cd gervis-backend
uv run python scripts/test_auth_session.py
```

Copy the product JWT printed in Step 2 of that script. You'll use it as `?token=<jwt>` in the
WebSocket URL.

Also note the `conversation_id` printed in Step 4 — use it as the path segment.

- [ ] `test_auth_session.py` passes all 4 steps
- [ ] You have a valid JWT and a `conversation_id` ready

---

## Step 5 — WebSocket connection test (browser devtools)

Open a browser console (F12 → Console) and run:

```javascript
const jwt = "PASTE_YOUR_JWT_HERE";
const convId = "PASTE_YOUR_CONVERSATION_ID_HERE";
const ws = new WebSocket(`ws://localhost:8080/ws/voice/${convId}?token=${jwt}`);
ws.onopen = () => console.log("✅ Connected");
ws.onmessage = (e) => console.log("← Message:", e.data);
ws.onclose = (e) => console.log("❌ Closed:", e.code, e.reason);
ws.onerror = (e) => console.error("Error:", e);
```

Expected: `✅ Connected` appears immediately.

Then send a text message to trigger a Gervis greeting:

```javascript
ws.send(JSON.stringify({ type: "text_input", text: "Hello Gervis!" }));
```

> Note: The voice handler processes binary audio and a few JSON control types. A `text_input` type
> won't trigger ADK directly, but the connection and ADK session startup should succeed — you may
> see `turn_complete` or no response. The key test here is that the connection opens and the backend
> starts an ADK session without crashing.

Check the backend log for:
```
INFO - [<conversation_id>] WebSocket connecting for user=...
```

- [ ] WebSocket connection opens (`code 1000` or stays open, not `4001 Unauthorized`)
- [ ] Backend log shows the connection line for the right conversation_id
- [ ] Closing the browser tab logs a grace timer start: `Grace timer started (30s)`

---

## Step 6 — Reject invalid JWT

Repeat the browser test with a bad token:

```javascript
const ws = new WebSocket(`ws://localhost:8080/ws/voice/some-id?token=not-a-real-jwt`);
ws.onclose = (e) => console.log("Closed:", e.code, e.reason);
```

Expected: connection closes immediately with code `4001`.

- [ ] Invalid JWT → closes with `4001 Unauthorized`
- [ ] No JWT at all → closes with `4001 Unauthorized`

---

## Step 7 — End-to-end voice test on Android

This is the main acceptance test.

### 7a — Point the Android app at your local backend

In `android/app/src/main/res/values/strings.xml`, confirm the backend URL is your machine's
local Wi-Fi IP (from `ipconfig`, Wireless LAN adapter Wi-Fi → IPv4 Address):

```xml
<string name="backend_base_url">http://192.168.x.x:8080</string>
```

Build and install:
```bash
cd android
./gradlew installDebug
```

### 7b — Start a voice session

1. Open the app and sign in
2. Say **"Hey Gervis"** (or tap the FAB if the wake word isn't triggering)
3. Wait for the activation chime
4. Say: **"Hello, what can you help me with?"**

Expected on phone:
- The animated orb enters listening state
- Your speech appears as `input_transcript` text on screen
- Gervis responds with audio through the phone speaker (or AirPods if connected)
- Gervis's response appears as `output_transcript` text on screen

Expected in backend log:
```
INFO - [<conversation_id>] WebSocket connecting for user=...
DEBUG - [<conversation_id>] interrupted forwarded to phone    ← only if you spoke over Gervis
```

- [ ] Wake word → activation chime → voice session opens
- [ ] Your speech transcription appears in real time on screen
- [ ] Gervis responds with audio (you can hear it)
- [ ] Gervis's response transcription appears on screen
- [ ] No errors in Android Logcat (`adb logcat | grep -i "spectalk\|gervis\|error"`)

---

## Step 8 — Google Search grounding

During an active voice session, ask a question that requires current web information:

> "Hey Gervis, who won the most recent FIFA World Cup?"

or

> "What are the latest developments in quantum computing?"

Expected:
- Gervis searches the web and gives a natural spoken answer
- The answer should be factually grounded (not a hallucination)
- Response should be conversational, not a list of URLs

Check the backend log — you should NOT see a tool call error:
```
ERROR - ...    ← nothing like this for google_search
```

- [ ] Gervis answers a current-events question correctly using web grounding
- [ ] Response is spoken naturally, no raw URLs read out loud

---

## Step 9 — Google Maps grounding

During an active voice session, ask about a location:

> "Hey Gervis, what are the best coffee shops near Times Square in New York?"

or

> "Find me Italian restaurants near downtown Seattle."

Expected:
- Gervis calls `find_nearby_places` internally
- A second Gemini call runs with Maps grounding
- Gervis speaks a concise answer: top options by name, no markdown

Check the backend log for the function call being dispatched:
```
DEBUG - ...find_nearby_places...
```

- [ ] Gervis answers a location query with real place names
- [ ] Response is spoken naturally (no addresses read character by character)

---

## Step 10 — Barge-in / interrupted

This is the single most important correctness test.

1. Start a voice session and ask Gervis to say something long:
   > "Tell me the history of the Roman Empire in detail."
2. While Gervis is speaking, interrupt by saying something:
   > "Actually, stop — what's 2 plus 2?"

Expected on phone:
- Audio playback stops immediately (no delay)
- The orb returns to listening state
- Your new question is heard and Gervis answers it

Expected in backend log:
```
DEBUG - [<conversation_id>] interrupted forwarded to phone
```

The `interrupted` JSON message must arrive at the phone **before** any further audio — this is
hardcoded in the downstream task (`_downstream_task` sends `interrupted` first and `continue`s,
skipping all pending audio).

- [ ] Interrupting Gervis stops playback immediately (< 200ms subjective delay)
- [ ] The new question is processed correctly
- [ ] Backend log shows `interrupted forwarded to phone`

---

## Step 11 — Turn persistence

After a voice conversation with at least 3 back-and-forth exchanges, check the database:

```bash
cd gervis-backend
uv run python -c "
import asyncio
from db.database import AsyncSessionLocal
from db.models import Turn
from sqlalchemy import select

async def check():
    async with AsyncSessionLocal() as s:
        rows = (await s.execute(select(Turn).order_by(Turn.created_at.desc()).limit(10))).scalars().all()
        for r in rows:
            print(r.role.ljust(10), r.text[:80] if r.text else '(empty)')

asyncio.run(check())
"
```

Expected: alternating `user` and `assistant` rows with the actual spoken text.

- [ ] User turns (from `input_transcript` final events) saved to `turns` table with `role=user`
- [ ] Assistant turns (from `output_transcript` final events) saved with `role=assistant`
- [ ] `event_type=voice_transcript` on all rows

---

## Step 12 — Inactivity auto-disconnect (10 seconds)

1. Start a voice session
2. Say something to confirm it's connected
3. Stop talking — don't say anything for 10+ seconds

Expected on phone:
- After 10 seconds of mutual silence (no input or output transcript), the session closes
- The wake word listener reactivates (HotwordService resumes)
- The conversation state in the backend returns to `idle`

Check the backend log:
```
INFO - [<conversation_id>] Phone disconnected, starting grace timer
```

> Note: The 10-second inactivity timer lives in `VoiceAgentViewModel` on Android (Phase 1).
> This test confirms the Android side sends `end_of_speech` and closes the WebSocket on timeout,
> and the backend handles the disconnect correctly.

- [ ] Session auto-closes after 10 seconds of silence
- [ ] Wake word is immediately active again after disconnect
- [ ] Backend log shows the disconnect and grace timer start

---

## Step 13 — Grace period (30-second reconnect window)

1. Start a voice session and say something
2. Manually disconnect (tap the disconnect button or kill/reopen the app)
3. Reconnect within 30 seconds (tap FAB or say "Hey Gervis" again)

Expected:
- The ADK session resumes — Gervis should remember the context from before the disconnect
- The new turn continues the conversation naturally

Check the backend log:
```
INFO - [<conversation_id>] Phone reconnected within grace period
```

Then let the grace period expire without reconnecting:
- Wait 30+ seconds after disconnecting
- Reconnect — this should start a **fresh** ADK session (Gervis introduces itself again)

- [ ] Reconnecting within 30s resumes the same conversation context
- [ ] Backend log shows `Phone reconnected within grace period`
- [ ] Reconnecting after 30s starts a fresh session with no memory of the old one

---

## Step 14 — Security: no Gemini key on Android

Search the entire Android source tree for any Gemini key patterns:

```bash
grep -r "GEMINI\|gemini.*key\|AIza" android/app/src/ --include="*.kt" --include="*.java" --include="*.xml"
```

Expected: **zero results**. The Android app must contain no Gemini API key, no Google AI Studio
key, and no raw `AIza...` API key strings.

Also confirm in `BackendVoiceClient.kt` that the WebSocket URL is built from `BackendConfig` and
the Authorization header uses the product JWT from `TokenRepository` — not any AI credential.

- [ ] Zero Gemini/AI key matches in Android source
- [ ] `BackendVoiceClient` uses JWT from `TokenRepository`, URL from `BackendConfig`

---

## Step 15 — Conversation list reflects voice sessions

After several voice sessions, open the Conversation List screen in the app:

- [ ] Each session appears as a conversation item with a timestamp
- [ ] Tapping a conversation opens it and resumes correctly
- [ ] The `state` chip shows `idle` for ended sessions

---

## Acceptance Criteria Summary

| # | Test | Pass condition |
|---|------|----------------|
| 2 | Server starts | ADK init line in log, no errors |
| 5 | WebSocket connects | Opens with valid JWT, grace timer on close |
| 6 | JWT rejection | `4001` on bad/missing token |
| 7 | End-to-end voice | Hear Gervis, see transcripts, no Logcat errors |
| 8 | Search grounding | Current-events answer, no URLs read aloud |
| 9 | Maps grounding | Real place names, conversational response |
| 10 | Barge-in | Playback stops < 200ms, `interrupted` in log |
| 11 | Turn persistence | User + assistant rows in `turns` table |
| 12 | Inactivity disconnect | Session closes after 10s silence |
| 13 | Grace period | Resume within 30s keeps context; after 30s = fresh |
| 14 | No key on Android | Zero grep matches for Gemini keys |
| 15 | Conversation list | Sessions visible, state = idle |

All 11 items checked → **Phase 3 approved → proceed to Phase 4.**

---

## Troubleshooting

**`RuntimeError: GeminiLiveClient not initialized`**
→ `GEMINI_API_KEY` is empty in `.env`. Set it and restart the server.

**WebSocket closes immediately with `1011`**
→ ADK session failed to start. Check the backend log for the exception — usually means invalid
API key, quota exceeded, or the model name is wrong.

**No audio from Gervis (transcripts appear but silence)**
→ The phone receives PCM binary frames but `PcmAudioPlayer` is not playing them. Check Logcat for
`PcmAudioPlayer` errors. Confirm the audio session is not muted or routed to the wrong output.

**Maps returns "I had trouble finding..."**
→ Maps grounding costs $0.025/request. Confirm your API key has the Gemini API enabled in Google
AI Studio and is not a restricted key. Check the backend log for the exception detail.

**`4001 Unauthorized` even with a valid JWT**
→ The JWT may have expired (30-day lifetime). Re-run `test_auth_session.py` to get a fresh one.
On Android, check that `TokenRepository` is refreshing the JWT when `POST /auth/session` is called.

**Grace timer not cancelling on reconnect**
→ The reconnect must use the **same `conversation_id`**. If the Android app creates a new
conversation via `POST /voice/session/start` on every session, the IDs won't match. The app should
reuse the existing `conversation_id` when resuming an open conversation.
