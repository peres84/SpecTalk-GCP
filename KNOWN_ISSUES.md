# Known Issues

## Priority 1 (Critical - Blocking Functionality)

### 1. Jobs Not Created for Agent Tasks
**Status**: Fix deployed — prompt strengthened + defensive logging added
**Impact**: Users requesting coding assistance, research, or complex tasks get no job tracking
**Root Cause**: Gemini model was narrating tool calls (e.g. "Initiating the Code") instead of actually invoking `confirm_and_dispatch` / `start_background_job` as function calls. The model produced markdown-formatted speech instead of executing tool functions.

**Symptoms**:
- Users ask agent: "Help me build a web app", "Research this API", "Write me a function"
- No corresponding job entry created in `jobs` table
- No way to track task progress or completion
- UI has nothing to display/track

**Investigation**:
```bash
# Check if jobs exist for recent conversations
uv run python scripts/db_query.py jobs --limit 20

# Check agent orchestrator logs
gcloud run services logs read gervis-backend --region=us-central1 --project=spectalk-488516 --limit=50
```

**Fix applied**:
1. `orchestrator.py` — Strengthened system prompt: explicitly says "MUST call the FUNCTION", "do NOT narrate", "the job is only created when the function is called". Added anti-markdown rules.
2. `tools/coding_tools.py` — Added `INVOKED` entry log + early return if conversation_id missing.
3. `tools/notification_resume_tool.py` — Added `INVOKED` entry log + Opik thread linking.
4. **Verification**: After deploy, search Cloud Run logs for `INVOKED` to confirm tools are being called. If still 0 jobs, the model is still narrating — may need to switch to a more instruction-following model.

---

### 2. Turns Saved in Streaming Chunks Instead of Complete Turn
**Status**: Fixed
**Impact**: Incomplete conversation history, hard to debug, bad for Opik tracking
**Root Cause**: `voice_handler.py` called `persist_turn()` on every non-partial text chunk. Gemini Live sends multiple "final" chunks per utterance (one per word/phrase), each creating a separate DB row.

**Symptoms**:
- `turns` table contains partial/intermediate transcripts
- Can't see full user utterance in a single turn record
- When Gemini interrupts, user turn gets fragmented
- Opik logs show incomplete input/output pairs
- Difficult to reconstruct user intent from turn history

**Current Behavior**:
```
Turn saved: "Hey, I want to"  (chunk 1)
Turn saved: "build a"         (chunk 2)
Turn saved: "web app"         (chunk 3 - final)
```

**Expected Behavior**:
```
Turn saved (user): "Hey, I want to build a web app"  (complete, after VAD detects silence)
Turn saved (assistant): "I can help with that! Let me create..."  (complete, after output finishes)
```

**Fix applied** (`voice_handler.py`):
1. Removed per-chunk `persist_turn()` and `record_voice_turn()` calls from the streaming loop.
2. Text fragments are buffered in `_turn_buffer[conversation_id][role]` during streaming.
3. New `_flush_turn_buffer()` function joins all fragments with `" ".join()` and persists ONE turn row + ONE OTel span + ONE Opik span per role on `turn_complete`.
4. Buffer is also flushed on disconnect (in `finally` block) so the last turn isn't lost.
5. Buffer is reset on `interrupted` events (barge-in) so the next turn starts clean.
6. No migration needed — same `turns` table, just fewer rows with complete text.

---

### 3. Opik Conversation Tracking Incomplete
**Status**: Fixed (turns); tool traces linked via thread_id
**Impact**: Agent behavior not debuggable, tool calls invisible
**Root Cause**: Opik logged fragmented turns (fixed by Issue #2 fix). Tool `@opik.track` traces were not linked to conversation thread.

**Symptoms**:
- Opik shows incomplete turns (due to issue #2)
- No visibility into tool selection or execution
- Can't see tool input/output pairs
- Can't trace failures to specific tool calls

**Fix applied**:
1. Turn merging fixed by Issue #2 fix — `_flush_turn_buffer()` now sends one Opik span per complete turn.
2. `start_background_job` now calls `opik.update_current_trace(thread_id=conversation_id)` to link to conversation thread (was missing; coding_tools already had this).
3. All tool `@opik.track` decorators already capture input/output. With thread_id linking, tool traces now appear in the correct conversation thread in the Opik dashboard.

---

## Priority 2 (High - Data Quality)

### 4. Resume Events Not Being Created
**Status**: Open
**Impact**: No way to track job completion or resume conversation flow
**Symptoms**:
- `resume_events` table is empty
- Jobs complete but no event published
- Phone doesn't know when to resume listening

**Solution**:
- Emit `ResumeEvent` when job completes (success or failure)
- Publish event to WebSocket so phone can notify user

---

## Priority 3 (Medium - Polish)

### 5. Pending Actions Not Fully Integrated
**Status**: Open
**Impact**: User approval workflow incomplete
**Symptoms**:
- `pending_actions` created but not resolved
- No feedback loop to agent on user approval/denial

---

## Debugging Checklist

Use these commands when investigating issues:

**Database State**:
```bash
# Summary of all data
uv run python scripts/db_query.py summary

# Check for jobs
uv run python scripts/db_query.py jobs --limit 20

# Check turns for a conversation
uv run python scripts/db_query.py turns --conversation-id <UUID> --limit 50

# Check resume events
uv run python scripts/db_query.py resume-events
```

**Cloud Logs**:
```bash
# Recent backend logs
gcloud run services logs read gervis-backend \
  --region=us-central1 \
  --project=spectalk-488516 \
  --limit=50

# Filter for errors
gcloud run services logs read gervis-backend \
  --region=us-central1 \
  --project=spectalk-488516 \
  --limit=100 | grep -i error
```

**Deployment**:
```bash
# After fixing issues, redeploy backend
gcloud builds submit \
  --config=gervis-backend/cloudbuild.yaml \
  --project=spectalk-488516
```

---

## Issue Resolution Process

1. Create/update this file with issue description
2. Investigate using the debugging checklist above
3. Fix the code
4. Test locally: `uv run uvicorn main:app --reload`
5. Test with database using scripts/db_query.py
6. Redeploy: `gcloud builds submit ...`
7. Verify in logs and database
8. Mark issue as "Resolved" with commit hash
