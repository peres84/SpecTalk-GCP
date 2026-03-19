# Phase 4 — Jobs, Notifications, and Resume Flow
## Deployment Guide & Testing Checklist

> Status: **Deployed and running** — `gervis-backend` live on Cloud Run (spectalk-488516, us-central1)

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

> **Shell**: All commands below use **PowerShell** (Windows). If you are on macOS/Linux, replace
> `$env:VAR = "value"` with `export VAR=value` and remove backticks (use `\` for line continuation
> or just run each command on one line).

### Prerequisites

- [Google Cloud SDK](https://cloud.google.com/sdk/docs/install) installed and authenticated
- Run once to log in: `gcloud auth login`
- Docker Desktop installed (needed for local builds; Cloud Build handles it in CI)

---

### One-Time Setup (do this before the first deploy)

Work through these steps in order. Each step only needs to be done once per GCP project.

#### Step 1 — Set your project

```powershell
# Set these two variables. Everything below uses them.
$env:PROJECT_ID = "spectalk-488516"
$env:REGION = "us-central1"

# Point gcloud at your project
gcloud config set project $env:PROJECT_ID
```

> If `gcloud projects create` fails with "project already exists" that's fine — just run
> `gcloud config set project` and continue.

#### Step 2 — Enable required APIs

```powershell
gcloud services enable `
  run.googleapis.com `
  cloudbuild.googleapis.com `
  artifactregistry.googleapis.com `
  secretmanager.googleapis.com `
  cloudtasks.googleapis.com `
  firebase.googleapis.com `
  fcm.googleapis.com `
  iam.googleapis.com `
  cloudtrace.googleapis.com
```

Wait ~30 seconds after this before continuing — API activation is async.

#### Step 3 — Create Artifact Registry repository

```powershell
gcloud artifacts repositories create gervis `
  --repository-format=docker `
  --location=$env:REGION `
  --description="Gervis backend Docker images"
```

#### Step 4 — Create the Cloud Run service account

```powershell
gcloud iam service-accounts create gervis-backend `
  --display-name="Gervis Backend Service Account"

# Grant all required roles (run each separately — they're fast)
gcloud projects add-iam-policy-binding $env:PROJECT_ID `
  --member="serviceAccount:gervis-backend@$env:PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/secretmanager.secretAccessor"

gcloud projects add-iam-policy-binding $env:PROJECT_ID `
  --member="serviceAccount:gervis-backend@$env:PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/cloudtasks.enqueuer"

gcloud projects add-iam-policy-binding $env:PROJECT_ID `
  --member="serviceAccount:gervis-backend@$env:PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/cloudtrace.agent"

gcloud projects add-iam-policy-binding $env:PROJECT_ID `
  --member="serviceAccount:gervis-backend@$env:PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/firebase.sdkAdminServiceAgent"

# Verify all roles were applied
gcloud projects get-iam-policy $env:PROJECT_ID `
  --flatten="bindings[].members" `
  --format="table(bindings.role,bindings.members)" `
  --filter="bindings.members:gervis-backend"
# Expected roles: secretmanager.secretAccessor, cloudtasks.enqueuer,
#                 cloudtrace.agent, firebase.sdkAdminServiceAgent, run.invoker
```

#### Step 5 — Grant Cloud Build permissions

Cloud Build runs as the **Compute Engine default service account**
(`PROJECT_NUMBER-compute@developer.gserviceaccount.com`), not the Cloud Build SA.
You need its project number:

```powershell
# Get the project number (different from project ID)
$env:PROJECT_NUMBER = gcloud projects describe $env:PROJECT_ID --format="value(projectNumber)"
$env:CB_SA = "$env:PROJECT_NUMBER-compute@developer.gserviceaccount.com"

gcloud projects add-iam-policy-binding $env:PROJECT_ID `
  --member="serviceAccount:$env:CB_SA" `
  --role="roles/run.admin"

gcloud projects add-iam-policy-binding $env:PROJECT_ID `
  --member="serviceAccount:$env:CB_SA" `
  --role="roles/artifactregistry.writer"

gcloud iam service-accounts add-iam-policy-binding `
  "gervis-backend@$env:PROJECT_ID.iam.gserviceaccount.com" `
  --member="serviceAccount:$env:CB_SA" `
  --role="roles/iam.serviceAccountUser"
```

> **Why the Compute SA and not the Cloud Build SA?**
> Modern GCP projects use the Compute Engine default SA as the Cloud Build identity.
> If you grant roles to `@cloudbuild.gserviceaccount.com` and builds still fail with
> permission errors, grant to `PROJECT_NUMBER-compute@developer.gserviceaccount.com` instead.

#### Step 6 — Create secrets in Secret Manager

Each secret is stored once. The `DATABASE_URL` must use `asyncpg` format — Neon gives you a
`postgres://` URL with `?sslmode=require&channel_binding=require`. Replace those params with
`?ssl=require` (asyncpg doesn't understand `sslmode` or `channel_binding`):

```powershell
# Firebase service account JSON
# Download from: Firebase Console → Project Settings → Service Accounts → Generate new private key
# Save the file somewhere, then:
gcloud secrets create FIREBASE_SERVICE_ACCOUNT_JSON `
  --data-file="C:\path\to\your-firebase-adminsdk.json"

# Database URL — asyncpg format (note: ssl=require NOT sslmode=require)
# Get your Neon URL from: console.neon.tech → your project → Connection string
# Change ?sslmode=require&channel_binding=require  →  ?ssl=require
echo -n "postgresql+asyncpg://user:pass@host/db?ssl=require" | `
  gcloud secrets create DATABASE_URL --data-file=-

# JWT secret — generate a random one
$jwt = python3 -c "import secrets; print(secrets.token_hex(32))"
echo -n $jwt | gcloud secrets create JWT_SECRET --data-file=-

# Gemini API key — from https://aistudio.google.com/app/apikey
echo -n "your-gemini-api-key" | gcloud secrets create GEMINI_API_KEY --data-file=-

# BACKEND_BASE_URL — placeholder for now, updated after first deploy (Step 9)
echo -n "https://placeholder.run.app" | gcloud secrets create BACKEND_BASE_URL --data-file=-
```

> **Note on `echo -n` in PowerShell**: `echo` in PowerShell is an alias for `Write-Output`
> and adds a newline. The `-n` flag is ignored. This means your secret value may have a
> trailing newline. To avoid this, use the `[System.Text.Encoding]::UTF8.GetBytes()` method
> or just update the secret value via the GCP Console UI if you suspect a trailing newline
> is causing auth failures.
>
> **Safe alternative for secrets with special characters:**
> ```powershell
> # Write to a temp file, upload, delete
> "your-secret-value" | Out-File -FilePath "tmp_secret.txt" -Encoding utf8 -NoNewline
> gcloud secrets create MY_SECRET --data-file="tmp_secret.txt"
> Remove-Item "tmp_secret.txt"
> ```

#### Step 7 — Create the Cloud Tasks queue

```powershell
gcloud tasks queues create backend-jobs `
  --location=$env:REGION `
  --max-attempts=5 `
  --max-retry-duration=3600s `
  --min-backoff=10s `
  --max-backoff=300s
```

#### Step 8 — Create the Alembic migration Cloud Run Job

This job is a one-off container that runs `alembic upgrade head`. It must exist before
the first Cloud Build run (the build updates its image, then executes it):

```powershell
gcloud run jobs create gervis-migrate `
  --image="$env:REGION-docker.pkg.dev/$env:PROJECT_ID/gervis/gervis-backend:latest" `
  --region=$env:REGION `
  --service-account="gervis-backend@$env:PROJECT_ID.iam.gserviceaccount.com" `
  --command="uv" `
  --args="run,alembic,upgrade,head" `
  --set-secrets="DATABASE_URL=DATABASE_URL:latest"
```

> You only need to create this job once. Every subsequent build updates its image via
> `gcloud run jobs update` and then executes it.

---

### Deploying

#### First deploy (and every deploy after)

Run from the **repo root** (`GeminiLiveAPI2026/`), not from inside `gervis-backend/`:

```powershell
cd D:\Proyectos\hackathons\2026\GeminiLiveAPI2026

gcloud builds submit --config=gervis-backend/cloudbuild.yaml --substitutions="_REGION=us-central1,_REPO=gervis,_IMAGE_TAG=latest" --project=spectalk-488516
```

> **Important**: Run this as a **single line** — do not split with backticks. The
> `--substitutions` string contains `@` (in the service account email hardcoded in the YAML)
> and breaking it across lines can cause parsing issues.

The build runs 4 steps and takes ~8-12 minutes:
1. **Docker build** — builds the image (~5 min, mostly dependency install)
2. **Docker push** — pushes to Artifact Registry (~30 sec)
3. **Run migrations** — updates job image + executes `alembic upgrade head` (~2 min)
4. **Deploy** — deploys new Cloud Run revision (~1 min)

You'll see `SUCCESS` at the end when all 4 steps pass.

#### Step 9 — Update BACKEND_BASE_URL after first deploy

After the first successful deploy, get the real Cloud Run URL and update the secret:

```powershell
# Get your Cloud Run URL
gcloud run services describe gervis-backend `
  --region=$env:REGION `
  --project=$env:PROJECT_ID `
  --format="value(status.url)"

# Update the secret (replace the URL with your actual one)
echo -n "https://gervis-backend-xxxxxxxxxx-uc.a.run.app" | `
  gcloud secrets versions add BACKEND_BASE_URL --data-file=- --project=$env:PROJECT_ID
```

Then deploy again so the running container picks up the updated secret:

```powershell
gcloud builds submit --config=gervis-backend/cloudbuild.yaml --substitutions="_REGION=us-central1,_REPO=gervis,_IMAGE_TAG=latest" --project=spectalk-488516
```

#### Verify the service is running

```powershell
$url = gcloud run services describe gervis-backend --region=us-central1 --project=spectalk-488516 --format="value(status.url)"
curl "$url/health"
# Expected: {"status": "ok"}  (HTTP 200)
```

---

### Troubleshooting Common Build Failures

#### "Memory limit exceeded" — container OOM on startup

Cloud Run default is 512 MiB. The backend needs ~550 MiB. The `cloudbuild.yaml` already
sets `--memory=1Gi`. If you see OOM in logs:

```powershell
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=gervis-backend" --project=$env:PROJECT_ID --limit=20 --format="value(textPayload)"
```

Look for: `Memory limit of 512 MiB exceeded`. Fix: ensure `--memory=1Gi` is in `cloudbuild.yaml`.

#### Migration job fails — `connect() got unexpected keyword argument 'sslmode'`

asyncpg doesn't accept `sslmode` or `channel_binding` from the connection URL.
`config.py` strips these automatically. But if you update the `DATABASE_URL` secret manually,
make sure it uses `?ssl=require` not `?sslmode=require`.

#### Migration job fails — permission denied on `run.jobs.get`

The Cloud Build service account needs `roles/run.admin`. See Step 5. After granting the role,
wait ~60 seconds for IAM propagation, then retry the build.

#### Build fails — `SHORT_SHA` not available

`SHORT_SHA` is only set in git-triggered builds (Cloud Build trigger connected to GitHub).
When running `gcloud builds submit` manually, use `_IMAGE_TAG=latest` in `--substitutions`.
This is already the default in `cloudbuild.yaml`.

#### Build fails — substitutions parse error with `@` in service account

Do **not** pass `_SERVICE_ACCOUNT` as a `--substitutions` value. The `@` in the email
breaks gcloud's substitution parser. The service account is hardcoded in `cloudbuild.yaml`
as `gervis-backend@${PROJECT_ID}.iam.gserviceaccount.com` using the built-in `PROJECT_ID`.

#### Service returns 403 Forbidden

The service is deployed with `--allow-unauthenticated`. If you see 403, check:
1. The JWT middleware is rejecting a missing/invalid token (expected for protected routes)
2. The route doesn't exist (404 vs 403)
3. If it's `/health`, that should return 200 without auth — check Cloud Run service logs

#### Viewing startup/crash logs

```powershell
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=gervis-backend" --project=spectalk-488516 --limit=50 --format="value(textPayload)"
```

For migration job logs:
```powershell
gcloud logging read "resource.type=cloud_run_job AND resource.labels.job_name=gervis-migrate" --project=spectalk-488516 --limit=20 --format="value(textPayload)"
```

---

### Environment Variables on Cloud Run

The `cloudbuild.yaml` injects secrets via `--set-secrets`. The full list:

| Secret Name | Maps to env var | Required |
|-------------|----------------|----------|
| `JWT_SECRET` | `JWT_SECRET` | Yes |
| `DATABASE_URL` | `DATABASE_URL` | Yes |
| `GEMINI_API_KEY` | `GEMINI_API_KEY` | Yes |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | `FIREBASE_SERVICE_ACCOUNT_JSON` | Yes |
| `BACKEND_BASE_URL` | `BACKEND_BASE_URL` | Phase 4+ |

Cloud Run env vars set via `--set-env-vars` (no Secret Manager needed):

```
ENVIRONMENT=production
GCP_PROJECT=spectalk-488516
CLOUD_TASKS_QUEUE=backend-jobs
CLOUD_TASKS_LOCATION=us-central1
CLOUD_RUN_SERVICE_ACCOUNT=gervis-backend@spectalk-488516.iam.gserviceaccount.com
ENABLE_TRACING=cloud
```

---

### Internal Endpoint Security

`POST /internal/jobs/execute` must **never** be publicly callable.

In production Cloud Run:
1. Cloud Tasks uses an OIDC token signed by the service account — Cloud Run validates it
2. The endpoint also checks `X-CloudTasks-QueueName` header — requests without it are rejected

> In development, the header check is skipped so you can call it manually with curl.

---

## Android Frontend Changes Required

### Update backend URL

**`android/app/src/main/res/values/strings.xml`**

```xml
<!-- BEFORE (local dev) -->
<string name="backend_base_url">http://192.168.0.128:8080</string>

<!-- AFTER (production) -->
<string name="backend_base_url">https://gervis-backend-xxxxxxxxxx-uc.a.run.app</string>
```

> `BackendConfig.kt` automatically converts `https://` → `wss://` for WebSocket connections,
> so one change covers both HTTP API calls and the voice WebSocket.

### New control messages the Android app must handle

**`job_started`**
```json
{"type": "job_started", "job_id": "...", "spoken_ack": "I've started your job..."}
```
Show a job-in-progress indicator. The `spoken_ack` is already being spoken by Gemini — don't show it as a toast.

**`job_update`**
```json
{"type": "job_update", "job_id": "...", "status": "completed", "display_summary": "Your app is ready."}
```
Update the job indicator. If `status == "completed"` or `"failed"`, show `display_summary`.

**`state_update`** *(future — not yet sent)*
```json
{"type": "state_update", "state": "running_job"}
```

### FCM notification handling

When the user taps a push notification:
- Notification `data` payload contains `conversation_id`, `job_id`, `event_type`, `resume_event_id`
- Open the conversation matching `conversation_id`
- Connect the voice WebSocket — backend injects resume context automatically
- After Gemini speaks the welcome-back, call `POST /conversations/{conversation_id}/ack-resume-event`

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
