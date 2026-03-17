# Google Cloud Infrastructure Setup

Step-by-step guide to bring up all backend infrastructure for SpecTalk on Google Cloud.
Run every command from your local terminal with `gcloud` installed and authenticated.

---

## Prerequisites

```bash
# Install gcloud CLI if not already installed
# https://cloud.google.com/sdk/docs/install

# Authenticate
gcloud auth login
gcloud auth application-default login
```

---

## Step 1 — Create the Google Cloud Project

```bash
# Create the project (pick your own project ID — must be globally unique)
gcloud projects create spectalk-prod --name="SpecTalk"

# Set it as the active project for all subsequent commands
gcloud config set project spectalk-prod

# Link a billing account (required for Cloud Run, Cloud SQL, etc.)
# List your billing accounts:
gcloud billing accounts list

# Link billing (replace BILLING_ACCOUNT_ID with your ID from the list above)
gcloud billing projects link spectalk-prod \
  --billing-account=BILLING_ACCOUNT_ID
```

---

## Step 2 — Enable APIs

```bash
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  sql-component.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  firebase.googleapis.com \
  fcm.googleapis.com \
  cloudtasks.googleapis.com \
  storage.googleapis.com \
  iam.googleapis.com
```

This takes about 60 seconds.

---

## Step 3 — Create the Artifact Registry Repository

```bash
gcloud artifacts repositories create gervis \
  --repository-format=docker \
  --location=us-central1 \
  --description="SpecTalk backend Docker images"
```

---

## Step 4 — Create the Cloud SQL Instance

```bash
# PostgreSQL 16, cheapest production-ready tier
gcloud sql instances create gervis-db \
  --database-version=POSTGRES_16 \
  --tier=db-g1-small \
  --region=us-central1 \
  --edition=ENTERPRISE \
  --root-password=REPLACE_WITH_STRONG_ROOT_PASSWORD

# Create the application database
gcloud sql databases create spectalk --instance=gervis-db

# Create the application user
gcloud sql users create gervis \
  --instance=gervis-db \
  --password=REPLACE_WITH_STRONG_APP_PASSWORD
```

> **Cost note:** Cloud SQL bills 24/7. Stop the instance when not in use:
> ```bash
> gcloud sql instances patch gervis-db --activation-policy=NEVER   # stop
> gcloud sql instances patch gervis-db --activation-policy=ALWAYS  # start
> ```

Get the connection name (you'll need it for the DATABASE_URL and Secret Manager):

```bash
gcloud sql instances describe gervis-db --format="value(connectionName)"
# Output: spectalk-prod:us-central1:gervis-db
```

The `DATABASE_URL` for Cloud SQL via the connector:
```
postgresql+asyncpg://gervis:REPLACE_WITH_STRONG_APP_PASSWORD@/spectalk?host=/cloudsql/spectalk-prod:us-central1:gervis-db
```

---

## Step 5 — Create the Service Account

```bash
# Create the service account
gcloud iam service-accounts create gervis-backend \
  --display-name="Gervis Backend (Cloud Run)"

# Grant required roles
PROJECT=spectalk-prod
SA=gervis-backend@${PROJECT}.iam.gserviceaccount.com

# Secret Manager — read secrets at runtime
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:${SA}" \
  --role="roles/secretmanager.secretAccessor"

# Cloud SQL — connect via the connector
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:${SA}" \
  --role="roles/cloudsql.client"

# Cloud Tasks — enqueue background jobs (Phase 4)
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:${SA}" \
  --role="roles/cloudtasks.enqueuer"

# Cloud Storage — read/write artifacts (Phase 4+)
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:${SA}" \
  --role="roles/storage.objectAdmin"

# Firebase Admin — verify tokens + send FCM push notifications
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:${SA}" \
  --role="roles/firebase.sdkAdminServiceAgent"
```

---

## Step 6 — Set Up Firebase

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → select your existing GCP project `spectalk-prod`
3. Under **Authentication → Sign-in method**, enable:
   - **Email/Password**
   - **Google**
4. Under **Project settings → General**, find your **Project ID** — it should be `spectalk-prod`
5. Under **Project settings → Service accounts** → click **Generate new private key**
   - Save the downloaded JSON file somewhere safe (e.g. `~/secrets/spectalk-firebase.json`)
   - You'll upload its contents to Secret Manager in Step 7

---

## Step 7 — Add Secrets to Secret Manager

Generate the JWT secret:
```bash
python -c "import secrets; print(secrets.token_hex(32))"
# Copy the output — use it as JWT_SECRET below
```

Create each secret:

```bash
PROJECT=spectalk-prod

# JWT secret
echo -n "PASTE_JWT_SECRET_HERE" | \
  gcloud secrets create JWT_SECRET --data-file=- --project=$PROJECT

# Database URL (Cloud SQL connector format)
echo -n "postgresql+asyncpg://gervis:REPLACE_WITH_STRONG_APP_PASSWORD@/spectalk?host=/cloudsql/spectalk-prod:us-central1:gervis-db" | \
  gcloud secrets create DATABASE_URL --data-file=- --project=$PROJECT

# Firebase service account JSON (paste the entire JSON file contents)
gcloud secrets create FIREBASE_SERVICE_ACCOUNT_JSON \
  --data-file=~/secrets/spectalk-firebase.json \
  --project=$PROJECT

# Gemini API key (get from https://aistudio.google.com/app/apikey)
echo -n "PASTE_GEMINI_API_KEY_HERE" | \
  gcloud secrets create GEMINI_API_KEY --data-file=- --project=$PROJECT

# Google Maps API key (get from Google Cloud Console → APIs & Services → Credentials)
echo -n "PASTE_GOOGLE_MAPS_API_KEY_HERE" | \
  gcloud secrets create GOOGLE_MAPS_API_KEY --data-file=- --project=$PROJECT

# Google Search API key (get from https://programmablesearchengine.google.com)
echo -n "PASTE_GOOGLE_SEARCH_API_KEY_HERE" | \
  gcloud secrets create GOOGLE_SEARCH_API_KEY --data-file=- --project=$PROJECT
```

Grant the service account access to read all secrets:
```bash
SA=gervis-backend@spectalk-prod.iam.gserviceaccount.com

for SECRET in JWT_SECRET DATABASE_URL FIREBASE_SERVICE_ACCOUNT_JSON GEMINI_API_KEY GOOGLE_MAPS_API_KEY GOOGLE_SEARCH_API_KEY; do
  gcloud secrets add-iam-policy-binding $SECRET \
    --member="serviceAccount:${SA}" \
    --role="roles/secretmanager.secretAccessor" \
    --project=spectalk-prod
done
```

---

## Step 8 — Create the Cloud Tasks Queue (Phase 4)

Not needed until Phase 4. Create it now to avoid a deploy-time error if referenced:

```bash
gcloud tasks queues create backend-jobs \
  --location=us-central1
```

---

## Step 9 — Create Cloud Storage Bucket (Phase 4+)

```bash
gcloud storage buckets create gs://spectalk-artifacts \
  --location=us-central1 \
  --uniform-bucket-level-access
```

---

## Step 10 — Grant Cloud Build the Deploy Permission

Cloud Build needs permission to deploy to Cloud Run and act as the service account:

```bash
PROJECT=spectalk-prod
PROJECT_NUMBER=$(gcloud projects describe $PROJECT --format="value(projectNumber)")
CB_SA=${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com

gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:${CB_SA}" \
  --role="roles/run.admin"

gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:${CB_SA}" \
  --role="roles/iam.serviceAccountUser"

gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:${CB_SA}" \
  --role="roles/secretmanager.secretAccessor"
```

---

## Step 11 — Connect Cloud Build to GitHub

1. Go to [Cloud Build → Triggers](https://console.cloud.google.com/cloud-build/triggers) in your project
2. Click **Connect repository** → select **GitHub**
3. Authorize and select the `GeminiLiveAPI2026` repository
4. Click **Create trigger**:
   - **Name**: `deploy-gervis-backend`
   - **Event**: Push to branch — `^main$`
   - **Source**: your repo
   - **Configuration**: Cloud Build configuration file
   - **Location**: `gervis-backend/cloudbuild.yaml`
5. Click **Save**

---

## Step 12 — Create the Alembic Migration Cloud Run Job

Before the first deploy, create the migration job that `cloudbuild.yaml` will execute:

```bash
gcloud run jobs create gervis-migrate \
  --image=us-central1-docker.pkg.dev/spectalk-prod/gervis/gervis-backend:latest \
  --region=us-central1 \
  --service-account=gervis-backend@spectalk-prod.iam.gserviceaccount.com \
  --set-secrets=DATABASE_URL=DATABASE_URL:latest,FIREBASE_SERVICE_ACCOUNT_JSON=FIREBASE_SERVICE_ACCOUNT_JSON:latest,JWT_SECRET=JWT_SECRET:latest \
  --command=uv \
  --args=run,alembic,upgrade,head \
  --add-cloudsql-instances=spectalk-prod:us-central1:gervis-db
```

> **Note:** The first time you run this the image won't exist yet. Do the first Docker build and push manually (Step 13), then create the job.

---

## Step 13 — First Manual Deploy

Trigger the first build manually from the `gervis-backend/` directory:

```bash
cd gervis-backend

# Build and push the image
gcloud builds submit \
  --tag=us-central1-docker.pkg.dev/spectalk-prod/gervis/gervis-backend:latest \
  .
```

Then create the migration job (now the image exists):
```bash
# (run the gcloud run jobs create command from Step 12 now)
```

Then run the migrations:
```bash
gcloud run jobs execute gervis-migrate --region=us-central1 --wait
```

Then deploy the service:
```bash
gcloud run deploy gervis-backend \
  --image=us-central1-docker.pkg.dev/spectalk-prod/gervis/gervis-backend:latest \
  --region=us-central1 \
  --platform=managed \
  --service-account=gervis-backend@spectalk-prod.iam.gserviceaccount.com \
  --min-instances=1 \
  --timeout=3600 \
  --add-cloudsql-instances=spectalk-prod:us-central1:gervis-db \
  --set-secrets=JWT_SECRET=JWT_SECRET:latest,DATABASE_URL=DATABASE_URL:latest,FIREBASE_SERVICE_ACCOUNT_JSON=FIREBASE_SERVICE_ACCOUNT_JSON:latest,GEMINI_API_KEY=GEMINI_API_KEY:latest \
  --no-allow-unauthenticated
```

Get the service URL:
```bash
gcloud run services describe gervis-backend \
  --region=us-central1 \
  --format="value(status.url)"
# Example: https://gervis-backend-xxxxxxxxxx-uc.a.run.app
```

---

## Step 14 — Verify the Deployment

```bash
SERVICE_URL=$(gcloud run services describe gervis-backend \
  --region=us-central1 --format="value(status.url)")

curl ${SERVICE_URL}/health
# Expected: {"status":"ok"}
```

If it returns `{"status":"ok"}` — the backend is live.

---

## Step 15 — Make the Service Accessible from Android

Cloud Run is deployed with `--no-allow-unauthenticated`, meaning it requires a Google identity
token on every request. For the Android app to call it directly using the product JWT, you have
two options:

**Option A (Recommended for production):** Set up a Cloud Endpoints or API Gateway in front of
Cloud Run that accepts the product JWT — or switch `--no-allow-unauthenticated` to
`--allow-unauthenticated` and rely entirely on the product JWT validation inside the app.

**Option B (Simplest for now):** Allow unauthenticated at the Cloud Run level and let the
app-level JWT middleware handle authentication:

```bash
gcloud run services add-iam-policy-binding gervis-backend \
  --region=us-central1 \
  --member="allUsers" \
  --role="roles/run.invoker"
```

> SpecTalk's `middleware/auth.py` validates the product JWT on every protected route, so
> unauthenticated access to `/auth/session` and `/health` is intentional.

---

## What to Put in the Android App

Once you have the service URL, add it to the Android app:

1. Open `android/app/src/main/res/values/strings.xml`
2. Add:
   ```xml
   <string name="backend_url">https://gervis-backend-xxxxxxxxxx-uc.a.run.app</string>
   ```

The Android auth wiring (JWT storage + API calls) is handled in the next code task.

---

## Summary of Resources Created

| Resource | Name |
|----------|------|
| GCP Project | `spectalk-prod` |
| Artifact Registry repo | `us-central1/gervis` |
| Cloud SQL instance | `gervis-db` (PostgreSQL 16) |
| Cloud SQL database | `spectalk` |
| Cloud SQL user | `gervis` |
| Service account | `gervis-backend@spectalk-prod.iam.gserviceaccount.com` |
| Secret Manager secrets | `JWT_SECRET`, `DATABASE_URL`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `GEMINI_API_KEY`, `GOOGLE_MAPS_API_KEY`, `GOOGLE_SEARCH_API_KEY` |
| Cloud Tasks queue | `backend-jobs` |
| Cloud Storage bucket | `gs://spectalk-artifacts` |
| Cloud Run service | `gervis-backend` |
| Cloud Run migration job | `gervis-migrate` |
| Cloud Build trigger | `deploy-gervis-backend` |
