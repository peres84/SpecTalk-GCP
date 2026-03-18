# Phase 4 — Jobs, Notifications, and Resume Flow
## Deployment Guide & Testing Checklist

> Status: Implementation complete. Awaiting Cloud Run deployment and Phase 4 approval.

---

## What Was Built

Phase 4 adds background job execution, FCM push notifications, and conversation resume flow.

| File | Purpose |
|------|---------|
| `services/control_channels.py` | Per-connection WebSocket control message registry (job_started, job_update, state_update) |
| `services/job_service.py` | Create/update/query jobs in DB; enqueue to Cloud Tasks |
| `services/notification_service.py` | FCM push via Firebase Admin SDK |
| `services/resume_event_service.py` | Create/fetch/acknowledge resume events; drive notification badge |
| `tools/notification_resume_tool.py` | ADK tool `start_background_job` — Gervis uses this to start long work |
| `api/internal/jobs.py` | `POST /internal/jobs/execute` — Cloud Tasks handler |
| `api/conversations.py` | Added `POST /{id}/ack-resume-event` |

### New WebSocket Control Messages (Backend → Phone)

```json
{"type": "job_started", "job_id": "...", "spoken_ack": "I've started building your app. I'll notify you when it's done."}
{"type": "job_update",  "job_id": "...", "status": "running|completed|failed", "display_summary": "..."}
{"type": "state_update", "state": "running_job|awaiting_resume|..."}
```

### End-to-End Flow

```
User: "Build me a landing page"
  → Gervis calls start_background_job(job_type="coding", ...)
  → Job row created (status=queued)
  → job_started control message sent to phone
  → Cloud Task enqueued to POST /internal/jobs/execute
  → Gervis speaks spoken_ack immediately, user can close the app

Cloud Tasks calls POST /internal/jobs/execute
  → Job status → running  → completed
  → ResumeEvent created, pending_resume_count incremented
  → FCM push notification sent to phone
  → job_update sent to WebSocket if phone is still connected

User taps notification, app opens
  → App reconnects WebSocket for that conversation
  → Backend finds pending ResumeEvents
  → Injects resume context into Gemini via send_content()
  → Gemini speaks a natural welcome-back message
  → pending_resume_count decremented / notification badge cleared
  → App calls POST /conversations/{id}/ack-resume-event
```

---

## Google Cloud Run Deployment

### One-Time Setup (do this before the first deploy)

#### 1. Create a Google Cloud project

```bash
# Replace with your actual project ID
export PROJECT_ID=spectalk-prod
gcloud projects create $PROJECT_ID
gcloud config set project $PROJECT_ID
```

#### 2. Enable required APIs

```bash
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudtasks.googleapis.com \
  sqladmin.googleapis.com \
  firebase.googleapis.com \
  fcm.googleapis.com \
  iam.googleapis.com
```

#### 3. Create Artifact Registry repository

```bash
gcloud artifacts repositories create gervis \
  --repository-format=docker \
  --location=us-central1 \
  --description="Gervis backend Docker images"
```

#### 4. Create the Cloud Run service account

```bash
gcloud iam service-accounts create gervis-backend \
  --display-name="Gervis Backend Service Account"

# Roles the service account needs
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:gervis-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:gervis-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudtasks.enqueuer"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:gervis-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudsql.client"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:gervis-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/firebase.sdkAdminServiceAgent"
```

#### 5. Create Secrets in Secret Manager

Create one secret per value. **Never put secrets in source code or environment variable overrides.**

```bash
# Firebase service account JSON (download from Firebase Console → Project Settings → Service Accounts)
gcloud secrets create FIREBASE_SERVICE_ACCOUNT_JSON --data-file=firebase-service-account.json

# Neon/Cloud SQL database URL (asyncpg format)
echo -n "postgresql+asyncpg://user:pass@host:5432/spectalk" | \
  gcloud secrets create DATABASE_URL --data-file=-

# JWT secret (generate a random 64-char string)
python3 -c "import secrets; print(secrets.token_hex(32))" | \
  gcloud secrets create JWT_SECRET --data-file=-

# Gemini API key (from https://aistudio.google.com/app/apikey)
echo -n "your-gemini-api-key" | \
  gcloud secrets create GEMINI_API_KEY --data-file=-

# Phase 4: new secrets
echo -n "your-opik-api-key" | \
  gcloud secrets create OPIK_API_KEY --data-file=-

# backend_base_url — full Cloud Run URL (fill in after first deploy)
echo -n "https://gervis-backend-xxxxxxxxxx-uc.a.run.app" | \
  gcloud secrets create BACKEND_BASE_URL --data-file=-
```

#### 6. Create the Cloud Tasks queue

```bash
gcloud tasks queues create backend-jobs \
  --location=us-central1 \
  --max-attempts=5 \
  --max-retry-duration=3600s \
  --min-backoff=10s \
  --max-backoff=300s
```

#### 7. Create the Alembic migration Cloud Run Job

```bash
gcloud run jobs create gervis-migrate \
  --image=us-central1-docker.pkg.dev/$PROJECT_ID/gervis/gervis-backend:latest \
  --region=us-central1 \
  --service-account=gervis-backend@$PROJECT_ID.iam.gserviceaccount.com \
  --command="uv" \
  --args="run,alembic,upgrade,head" \
  --set-secrets=DATABASE_URL=DATABASE_URL:latest
```

#### 8. Grant Cloud Build permissions

```bash
# Get the Cloud Build service account
CB_SA=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')@cloudbuild.gserviceaccount.com

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$CB_SA" \
  --role="roles/run.admin"

gcloud iam service-accounts add-iam-policy-binding \
  gervis-backend@$PROJECT_ID.iam.gserviceaccount.com \
  --member="serviceAccount:$CB_SA" \
  --role="roles/iam.serviceAccountUser"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$CB_SA" \
  --role="roles/artifactregistry.writer"
```

---

### Deploying

#### Option A — Trigger Cloud Build manually

```bash
cd GeminiLiveAPI2026

gcloud builds submit \
  --config=gervis-backend/cloudbuild.yaml \
  --substitutions=_REGION=us-central1,_REPO=gervis,PROJECT_ID=$PROJECT_ID,\
_SERVICE_ACCOUNT=gervis-backend@$PROJECT_ID.iam.gserviceaccount.com
```

#### Option B — Connect Cloud Build to GitHub (recommended)

1. Go to Cloud Build → Triggers → Connect Repository
2. Select your GitHub repo
3. Create a trigger on `push to main` using `gervis-backend/cloudbuild.yaml`
4. Every push to `main` auto-deploys

#### After first deploy — fill in BACKEND_BASE_URL secret

```bash
# Get your Cloud Run URL
gcloud run services describe gervis-backend \
  --region=us-central1 \
  --format='value(status.url)'

# Update the secret
echo -n "https://gervis-backend-xxxxxxxxxx-uc.a.run.app" | \
  gcloud secrets versions add BACKEND_BASE_URL --data-file=-
```

---

### Environment Variables on Cloud Run

The `cloudbuild.yaml` injects secrets via `--set-secrets`. The full list of secrets needed after Phase 4:

| Secret Name | Maps to env var | Required |
|-------------|----------------|----------|
| `JWT_SECRET` | `JWT_SECRET` | Yes |
| `DATABASE_URL` | `DATABASE_URL` | Yes |
| `GEMINI_API_KEY` | `GEMINI_API_KEY` | Yes |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | `FIREBASE_SERVICE_ACCOUNT_JSON` | Yes |
| `BACKEND_BASE_URL` | `BACKEND_BASE_URL` | Phase 4+ |
| `OPIK_API_KEY` | `OPIK_API_KEY` | Optional |

Cloud Run env vars that don't require Secret Manager (set via `--set-env-vars` in `cloudbuild.yaml`):

```
GCP_PROJECT=spectalk-prod
CLOUD_TASKS_QUEUE=backend-jobs
CLOUD_TASKS_LOCATION=us-central1
CLOUD_RUN_SERVICE_ACCOUNT=gervis-backend@spectalk-prod.iam.gserviceaccount.com
ENVIRONMENT=production
```

---

### Internal Endpoint Security

`POST /internal/jobs/execute` must **never** be publicly callable.

In production Cloud Run:
1. The service is deployed with `--no-allow-unauthenticated` (already in `cloudbuild.yaml`)
2. Cloud Tasks uses an OIDC token signed by the service account — Cloud Run validates it automatically
3. The endpoint also checks `X-CloudTasks-QueueName` header — requests without it are rejected in `environment=production`

> In development, the header check is skipped so you can call it manually with curl.

---

## Android Frontend Changes Required

### What to change before production deploy

#### `android/app/src/main/res/values/strings.xml`

Change the `backend_base_url` value from the local IP to your Cloud Run URL:

```xml
<!-- BEFORE (local dev) -->
<string name="backend_base_url">http://192.168.0.128:8080</string>

<!-- AFTER (production) -->
<string name="backend_base_url">https://gervis-backend-xxxxxxxxxx-uc.a.run.app</string>
```

> `BackendConfig.kt` automatically converts `https://` → `wss://` for WebSocket connections, so one change covers both HTTP API calls and the voice WebSocket.

#### New control messages the Android app must handle

The app's `BackendVoiceClient` parses JSON control messages. It needs to handle three new types from Phase 4:

**`job_started`**
```json
{"type": "job_started", "job_id": "...", "spoken_ack": "I've started your job..."}
```
Show a job-in-progress indicator in the voice session UI. The `spoken_ack` is already being spoken by Gemini — don't display it as a toast.

**`job_update`**
```json
{"type": "job_update", "job_id": "...", "status": "completed", "display_summary": "Your app is ready."}
```
Update the job indicator. If `status == "completed"` or `"failed"`, show the `display_summary` in the UI.

**`state_update`** *(future — not yet sent by backend)*
```json
{"type": "state_update", "state": "running_job"}
```
Update the conversation state chip in the UI.

#### FCM notification handling

When the user taps a push notification from a completed job:
- The notification `data` payload contains `conversation_id`, `job_id`, `event_type`, `resume_event_id`
- The app should open the conversation matching `conversation_id`
- Connect the voice WebSocket — the backend will automatically inject the resume context
- After Gemini speaks the welcome-back message, call `POST /conversations/{conversation_id}/ack-resume-event`

FCM notification channels to create in Android (already created in Phase 1 `SpecTalkApplication`):
- `spectalk_jobs` — high priority, used for job complete/failed notifications

---

## Local Testing (No Cloud Tasks)

Since `CLOUD_TASKS_QUEUE` is empty in development, jobs are queued in the DB but not auto-executed. Simulate completion manually:

```bash
# 1. Start a job via the voice agent (say "start a demo job")
#    The job ID will appear in the backend logs

# 2. Manually trigger completion
curl -X POST http://localhost:8080/internal/jobs/execute \
  -H "Content-Type: application/json" \
  -d '{
    "job_id": "<job-id-from-logs>",
    "job_type": "demo",
    "conversation_id": "<conversation-id>",
    "user_id": "<user-id>"
  }'

# 3. Backend will:
#    - Mark job completed
#    - Create a resume event
#    - Send FCM push (if push_token is set)
#    - Send job_update to WebSocket if phone is connected
```

---

## Phase 4 Acceptance Checklist

- [ ] Say "start a demo job" to Gervis → `job_started` WebSocket message arrives at phone
- [ ] Job row visible in DB with `status=queued`
- [ ] `POST /internal/jobs/execute` called manually → job status → `completed`
- [ ] `resume_events` row created, `pending_resume_count` incremented on conversation
- [ ] FCM notification received on phone (requires real push token)
- [ ] Phone reconnects to conversation WebSocket → Gemini speaks welcome-back message
- [ ] `GET /conversations` shows `pending_resume_count > 0` badge
- [ ] `POST /conversations/{id}/ack-resume-event` → badge resets to 0
- [ ] In production: Cloud Tasks auto-calls `/internal/jobs/execute` after job enqueued
- [ ] `/internal/jobs/execute` returns 403 without Cloud Tasks header in `environment=production`
