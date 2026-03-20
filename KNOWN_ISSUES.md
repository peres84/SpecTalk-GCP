# Known Issues

Active issues only. Resolved issues are documented in `android/CHANGELOG.md`.

---

## Android

### [A-1] FCM notification auto-opens voice session without wake word
**Priority**: High
**File**: `android/app/src/main/java/com/spectalk/app/notifications/FcmService.kt`
**Also**: `android/app/src/main/java/com/spectalk/app/settings/AppPreferences.kt`

**Symptom**: Every time the backend is redeployed, the user hears the activation chime and
Gervis speaks — without saying the wake word.

**Root cause (two-part)**:
1. **Backend**: Cloud Tasks retries jobs that were killed mid-execution during redeploy.
   The new backend instance completes the retried job → sends FCM push. *(Fixed: idempotency
   guard added to `api/internal/jobs.py` — terminal-state jobs skip re-execution.)*
2. **Android**: `FcmService.onMessageReceived()` calls `startActivity()` to auto-open the
   app and navigate to the conversation when `AppPreferences.isAutoOpenOnNotification` is
   `true`. This fires the activation chime + starts a voice session even without a wake word.

**Fix needed (Android agent)**:
Change `AppPreferences.isAutoOpenOnNotification` default from `true` to `false`.
The user should tap the notification to resume — auto-open should be opt-in.

## Backend

### [B-1] Jobs not reliably created for agent tasks
**Priority**: High
**File**: `gervis-backend/agents/orchestrator.py`, `gervis-backend/tools/coding_tools.py`

**Symptom**: Users request coding or research tasks; Gervis responds verbally but no job
row appears in the `jobs` table. No progress tracking, no completion notification, no result.

**Root cause**: Gemini model narrates tool invocations ("Initiating the code generation…")
instead of emitting an actual function call. The `confirm_and_dispatch` /
`start_background_job` tools are never called as functions.

**Fix applied**: Switched orchestrator model from `gemini-2.5-flash-native-audio-preview-12-2025`
to `gemini-2.0-flash-live-001` (stable Live API model, better function-call compliance).
`INVOKED` entry log added to all three coding tool entry points (`request_clarification`,
`generate_and_confirm_prd`, `confirm_and_dispatch`).

**Verification**: After deploy, search Cloud Run logs for `INVOKED`:
```bash
gcloud run services logs read gervis-backend \
  --region=us-central1 --project=spectalk-488516 --limit=100 | grep INVOKED
```
Expect 3 INVOKED hits per full coding flow (clarification → PRD → dispatch).

---

### [B-2] ~~Resume events not created on job completion~~ — RESOLVED
`api/internal/jobs.py` already creates a `ResumeEvent` row and sends the FCM push when a job
transitions to `completed` or `failed`. `resume_event_service.create_resume_event()` and
`notification_service.send_push_notification()` are both called in the job completion path.

---

### [B-3] ~~Pending actions created but never resolved~~ — RESOLVED
`POST /conversations/{id}/confirm` exists and resolves `pending_actions` rows correctly.
`confirm_and_dispatch` tool also resolves them through the voice path. No backend work needed.

---

## Debugging Reference

```bash
# Check jobs table
uv run python scripts/db_query.py jobs --limit 20

# Check resume events
uv run python scripts/db_query.py resume-events

# Check turns for a conversation
uv run python scripts/db_query.py turns --conversation-id <UUID> --limit 50

# Recent backend logs
gcloud run services logs read gervis-backend \
  --region=us-central1 --project=spectalk-488516 --limit=50

# Filter errors
gcloud run services logs read gervis-backend \
  --region=us-central1 --project=spectalk-488516 --limit=100 | grep -i error

# Redeploy backend
gcloud builds submit \
  --config=gervis-backend/cloudbuild.yaml \
  --project=spectalk-488516
```
