# Known Issues

Active issues only. Resolved issues are documented in `android/CHANGELOG.md`.

---

## Android

No open Android issues.

## Backend

### [B-1] Jobs not reliably created for agent tasks
**Priority**: High
**File**: `gervis-backend/agents/orchestrator.py`, `gervis-backend/tools/coding_tools.py`

**Symptom**: Users request coding or research tasks; Gervis responds verbally but no job
row appears in the `jobs` table. No progress tracking, no completion notification, no result.

**Root cause**: Gemini model narrates tool invocations ("Initiating the code generation…")
instead of emitting an actual function call. The `confirm_and_dispatch` /
`start_background_job` tools are never called as functions.

**Fix applied (partial)**: System prompt hardened with explicit "MUST call the FUNCTION"
rules + anti-markdown instructions. `INVOKED` entry log added to tool entry points.

**Verification**: After deploy, search Cloud Run logs for `INVOKED`:
```bash
gcloud run services logs read gervis-backend \
  --region=us-central1 --project=spectalk-488516 --limit=100 | grep INVOKED
```
If count is still 0 when users request tasks, the model is still narrating. Escalation:
switch orchestrator to a more instruction-following model (e.g. `gemini-2.0-flash`).

---

### [B-2] Resume events not created on job completion
**Priority**: High
**File**: `gervis-backend/` (job completion handler — exact file TBD)

**Symptom**: `resume_events` table is always empty. Jobs complete but the phone receives
no FCM push (or push arrives with no resume event to ack), so the auto-open flow is broken
and conversation history shows no completion context.

**Root cause**: No code path emits a `ResumeEvent` row when a job transitions to
`completed` or `failed`.

**Fix**: On job completion, insert a `resume_events` row and trigger the FCM push to the
conversation owner's device. The Android `FcmService` and `VoiceAgentViewModel.ackResumeEvent`
are already wired to handle this — the missing piece is the backend emission.

---

### [B-3] Pending actions created but never resolved
**Priority**: Medium
**File**: `gervis-backend/` (pending actions handler — exact file TBD)

**Symptom**: `pending_actions` rows are created but never transitioned to `approved` or
`denied`. No feedback loop back to the agent after user responds.

**Root cause**: User approval/denial flow not implemented end-to-end. The Android PRD
confirmation card calls `POST /conversations/{id}/confirm` but there is no equivalent
general-purpose approval endpoint that resolves `pending_actions` rows and notifies the
waiting agent.

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
