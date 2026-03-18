# SpecTalk Backend — Shutdown & Cost-Control Guide

How to stop, pause, or permanently delete every service the backend uses.
Useful when you want to pause work, stop charges over a weekend, or tear down entirely.

---

## Services in Use

| Service | Where | Charges when idle? |
|---------|-------|--------------------|
| Cloud Run (gervis-backend) | GCP | Only when handling requests (min-instances=1 charges always) |
| Cloud Run Jobs (gervis-migrate) | GCP | Only when executing |
| Cloud Tasks queue (backend-jobs) | GCP | Only when tasks are enqueued |
| Artifact Registry (docker images) | GCP | Storage: ~$0.10/GB/month |
| Cloud Build | GCP | Only during builds |
| Secret Manager | GCP | ~$0.06/secret/month + access charges |
| Cloud Trace | GCP | $0.20 per million spans (first 2.5M/month free) |
| Firebase Authentication | Firebase | Free tier, no idle charges |
| Firebase Cloud Messaging | Firebase | Free, no idle charges |
| Neon PostgreSQL | External | Free tier auto-suspends after 5 min idle |
| Gemini API (AI Studio key) | Google AI | Only when requests are made |

**Biggest idle cost: Cloud Run with `--min-instances=1`.**
Setting it to 0 eliminates the largest portion of charges immediately.

---

## Option 1 — Quick Stop (pause charges, keep everything intact)

Run these to stop all active spending without deleting anything.
You can resume from this state in ~2 minutes.

```bash
export PROJECT_ID=spectalk-prod
export REGION=us-central1

# 1. Scale Cloud Run to zero — eliminates the biggest idle cost
gcloud run services update gervis-backend \
  --region=$REGION \
  --min-instances=0 \
  --max-instances=0

# 2. Pause the Cloud Tasks queue — stops any pending tasks from executing
gcloud tasks queues pause backend-jobs \
  --location=$REGION

# 3. Disable Cloud Build trigger — stops auto-deploys on git push
#    List triggers first to get the trigger ID
gcloud builds triggers list --project=$PROJECT_ID
#    Then disable the one for gervis-backend
gcloud builds triggers update <TRIGGER_ID> --no-build-filter --disabled
```

**To resume from a quick stop:**

```bash
# Scale Cloud Run back up
gcloud run services update gervis-backend \
  --region=$REGION \
  --min-instances=1

# Resume the Cloud Tasks queue
gcloud tasks queues resume backend-jobs \
  --location=$REGION

# Re-enable Cloud Build trigger
gcloud builds triggers update <TRIGGER_ID> --no-disabled
```

---

## Option 2 — Individual Service Shutdown

### Cloud Run — backend service

```bash
# Scale to zero (no idle charges, cold-start on next request)
gcloud run services update gervis-backend \
  --region=$REGION \
  --min-instances=0 \
  --max-instances=0

# Or delete the service entirely (irreversible — redeploy from image)
gcloud run services delete gervis-backend --region=$REGION
```

### Cloud Run Jobs — Alembic migration job

```bash
# Migration jobs only charge when executing, no idle cost.
# Delete only if you won't need to run migrations again.
gcloud run jobs delete gervis-migrate --region=$REGION
```

### Cloud Tasks — backend-jobs queue

```bash
# Pause: stops tasks from being dispatched, queue is preserved
gcloud tasks queues pause backend-jobs --location=$REGION

# Resume:
gcloud tasks queues resume backend-jobs --location=$REGION

# Purge all pending tasks (can't be undone):
gcloud tasks queues purge backend-jobs --location=$REGION

# Delete the queue entirely:
gcloud tasks queues delete backend-jobs --location=$REGION
```

### Artifact Registry — docker images

```bash
# List images
gcloud artifacts docker images list \
  $REGION-docker.pkg.dev/$PROJECT_ID/gervis

# Delete a specific image tag (saves storage costs)
gcloud artifacts docker images delete \
  $REGION-docker.pkg.dev/$PROJECT_ID/gervis/gervis-backend:latest

# Delete ALL images in the repository (keeps the repo itself)
gcloud artifacts docker images list \
  $REGION-docker.pkg.dev/$PROJECT_ID/gervis/gervis-backend \
  --format="value(IMAGE)" | \
  xargs -I{} gcloud artifacts docker images delete {} --quiet

# Delete the entire repository
gcloud artifacts repositories delete gervis \
  --location=$REGION
```

### Cloud Build — triggers

```bash
# List all triggers
gcloud builds triggers list

# Disable a trigger (stops auto-builds on git push)
gcloud builds triggers update <TRIGGER_ID> --disabled

# Delete a trigger
gcloud builds triggers delete <TRIGGER_ID>
```

### Secret Manager — secrets

```bash
# Secrets charge ~$0.06/month each. List them:
gcloud secrets list

# Disable a secret (makes it unreadable without deleting)
gcloud secrets update JWT_SECRET --no-active

# Delete a secret (permanent — you'll need to recreate it to redeploy)
gcloud secrets delete JWT_SECRET

# Delete all Phase 4 secrets at once:
for secret in JWT_SECRET DATABASE_URL GEMINI_API_KEY \
              FIREBASE_SERVICE_ACCOUNT_JSON BACKEND_BASE_URL; do
  gcloud secrets delete $secret --quiet
done
```

### Cloud Trace

Trace charges only occur when spans are ingested. To stop tracing without a redeploy:

```bash
# Update the Cloud Run service env var to disable tracing
gcloud run services update gervis-backend \
  --region=$REGION \
  --update-env-vars=ENABLE_TRACING=off
```

To re-enable:

```bash
gcloud run services update gervis-backend \
  --region=$REGION \
  --update-env-vars=ENABLE_TRACING=cloud
```

### Cloud SQL (if/when migrated from Neon)

```bash
# Stop the instance (no compute charges, but storage still billed)
gcloud sql instances patch gervis-db --activation-policy=NEVER

# Restart:
gcloud sql instances patch gervis-db --activation-policy=ALWAYS

# Delete the instance (permanent — ensure you have a backup first)
gcloud sql instances delete gervis-db
```

---

## Option 3 — External Services

These are outside GCP and have their own consoles.

### Neon PostgreSQL (development database)

Neon free tier auto-suspends after 5 minutes of inactivity — no action needed.

- **Pause manually**: Neon Console → your project → Branches → `main` → Suspend
- **Delete the project**: Neon Console → Settings → Delete project (permanent)
- **Connection string**: stored in `DATABASE_URL` secret — update it if you switch databases

### Gemini API key (Google AI Studio)

The API key charges only when requests are made. To fully stop:

1. Go to [aistudio.google.com](https://aistudio.google.com) → API Keys
2. Click the key → **Delete** (you'll need to create a new one and update the `GEMINI_API_KEY` secret to redeploy)

Or just revoke the key from your Google Cloud project:

```bash
# List API keys
gcloud services api-keys list

# Delete a key by UID
gcloud services api-keys delete <KEY_UID>
```

### Firebase Authentication + FCM

Firebase free tier (Spark plan) has no idle charges.

- **Disable sign-in providers**: Firebase Console → Authentication → Sign-in method → disable Email/Password and Google
- **FCM**: No charges for idle. To stop sending: just don't call `messaging.send()` (done by setting `CLOUD_TASKS_QUEUE=` empty so no jobs run)
- **Delete the Firebase project**: Firebase Console → Project Settings → scroll to bottom → Delete project (this also deletes all Firebase Auth users)

---

## Option 4 — Full Teardown (delete everything in GCP)

**Warning: this is irreversible. All data, secrets, and deployed services will be gone.**

### Nuclear: disable billing

Disabling billing stops all chargeable services immediately without deleting anything.
You have 30 days to re-enable before resources are deleted.

```bash
# Unlink the billing account from the project
gcloud billing projects unlink $PROJECT_ID
```

### Full project deletion

Deletes the GCP project and everything in it after a 30-day grace period.

```bash
gcloud projects delete $PROJECT_ID
```

### Targeted teardown (preserves the project)

```bash
export PROJECT_ID=spectalk-prod
export REGION=us-central1

# 1. Delete Cloud Run service and migration job
gcloud run services delete gervis-backend --region=$REGION --quiet
gcloud run jobs delete gervis-migrate --region=$REGION --quiet

# 2. Delete Cloud Tasks queue
gcloud tasks queues delete backend-jobs --location=$REGION --quiet

# 3. Delete Artifact Registry repo (and all images in it)
gcloud artifacts repositories delete gervis --location=$REGION --quiet

# 4. Delete all secrets
for secret in JWT_SECRET DATABASE_URL GEMINI_API_KEY \
              FIREBASE_SERVICE_ACCOUNT_JSON BACKEND_BASE_URL; do
  gcloud secrets delete $secret --quiet 2>/dev/null || true
done

# 5. Delete the service account
gcloud iam service-accounts delete \
  gervis-backend@$PROJECT_ID.iam.gserviceaccount.com --quiet

# 6. Delete Cloud Build triggers
gcloud builds triggers list --format="value(id)" | \
  xargs -I{} gcloud builds triggers delete {} --quiet

# 7. Disable the APIs (optional — prevents accidental usage)
gcloud services disable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  cloudtasks.googleapis.com \
  secretmanager.googleapis.com \
  artifactregistry.googleapis.com \
  cloudtrace.googleapis.com
```

---

## Cost Reference (approximate, us-central1)

| Service | Free tier | Pay-as-you-go |
|---------|-----------|---------------|
| Cloud Run | 2M req/month, 360k vCPU-sec | $0.00002400/vCPU-sec + $0.00000250/GB-sec |
| Cloud Run (min-instances=1) | — | ~$7–15/month for 0.5 vCPU 512MB always-on |
| Cloud Tasks | 1M tasks/month free | $0.40/million tasks after |
| Artifact Registry | 0.5 GB free | $0.10/GB/month |
| Secret Manager | 6 active secrets free | $0.06/secret/month + $0.03/10k accesses |
| Cloud Trace | 2.5M spans/month free | $0.20/million spans |
| Cloud Build | 120 min/day free | $0.003/build-minute |
| Neon PostgreSQL | Free tier (0.5 GB, auto-suspend) | From $19/month for always-on |
| Gemini API | AI Studio: free with rate limits | Per-token pricing on paid tier |
| Firebase Auth | Free (Spark plan) | Blaze plan: usage-based |
| FCM | Always free | — |
