# SpecTalk — Learning Resources

A junior-friendly guide to every technology used in this project.
Start with the "What is it?" section for each one before going to the links.

---

## Table of Contents

1. [Python & FastAPI](#1-python--fastapi)
2. [Google Cloud Run](#2-google-cloud-run)
3. [Cloud Build & CI/CD](#3-cloud-build--cicd)
4. [Docker & Containers](#4-docker--containers)
5. [PostgreSQL & SQLAlchemy](#5-postgresql--sqlalchemy)
6. [Alembic (Database Migrations)](#6-alembic-database-migrations)
7. [Cloud Tasks (Background Jobs)](#7-cloud-tasks-background-jobs)
8. [Firebase Authentication](#8-firebase-authentication)
9. [Firebase Cloud Messaging (FCM)](#9-firebase-cloud-messaging-fcm)
10. [Google Secret Manager](#10-google-secret-manager)
11. [Gemini Live API & Google ADK](#11-gemini-live-api--google-adk)
12. [WebSockets](#12-websockets)
13. [Kotlin & Jetpack Compose (Android)](#13-kotlin--jetpack-compose-android)
14. [OpenTelemetry & Cloud Trace](#14-opentelemetry--cloud-trace)
15. [Neon PostgreSQL (Serverless DB)](#15-neon-postgresql-serverless-db)
16. [uv (Python Package Manager)](#16-uv-python-package-manager)
17. [Artifact Registry](#17-artifact-registry)

---

## 1. Python & FastAPI

**What is it?**
Python is the language the entire backend is written in. FastAPI is the web framework —
it handles HTTP requests, routes them to the right function, validates inputs, and returns
responses. Think of it as the traffic controller for the backend.

**Why we use it:**
FastAPI is async-native (handles thousands of connections without blocking), auto-generates
API docs at `/docs`, and uses Python type hints for automatic request/response validation.

**Key concepts to learn:**
- `async def` and `await` — how Python handles concurrent work without threads
- Pydantic models — data validation (used for every request/response body)
- Dependency injection (`Depends`) — how `require_auth` gets injected into every protected route
- Router prefix grouping — how `/auth`, `/voice`, `/conversations` routes are organized

**Resources:**
- FastAPI official tutorial (best in the ecosystem): https://fastapi.tiangolo.com/tutorial/
- Async Python explained simply: https://realpython.com/async-io-python/
- Pydantic docs: https://docs.pydantic.dev/latest/

---

## 2. Google Cloud Run

**What is it?**
Cloud Run is a managed serverless platform that runs your Docker container. You give it an
image, it handles servers, load balancing, HTTPS, scaling, and restarts automatically. You
only pay when requests are being handled (with `--min-instances=0`).

**Why we use it:**
No server management. Auto-scales from 0 to thousands of instances. First-class support for
WebSockets (needed for our voice bridge) with long timeout (3600s).

**Key concepts to learn:**
- `min-instances` — how many instances stay warm (0 = scale to zero, saves money)
- `max-instances` — ceiling for scaling (protects against runaway costs)
- Revisions — every deploy creates a new revision; traffic routing is per-revision
- Service accounts — the identity Cloud Run uses to call other GCP services
- Environment variables and secrets — how config reaches your running container

**Commands used in this project:**
```bash
# Deploy
gcloud run deploy gervis-backend --image=... --region=us-central1

# Scale to zero (dev mode)
gcloud run services update gervis-backend --min-instances=0

# View logs
gcloud run services logs read gervis-backend --region=us-central1
```

**Resources:**
- Cloud Run quickstart: https://cloud.google.com/run/docs/quickstarts/build-and-deploy/deploy-python-service
- Cloud Run WebSockets: https://cloud.google.com/run/docs/triggering/websockets
- Pricing calculator: https://cloud.google.com/run/pricing

---

## 3. Cloud Build & CI/CD

**What is it?**
Cloud Build is Google's CI/CD service. When you run `gcloud builds submit`, it reads
`cloudbuild.yaml`, spins up build workers, and executes each step: build Docker image →
push to Artifact Registry → run migrations → deploy to Cloud Run.

**Why we use it:**
Reproducible deploys from any machine. The build runs in Google's infrastructure so you
don't need Docker installed locally or upload the full image from your laptop.

**Key concepts to learn:**
- `cloudbuild.yaml` steps — each step is a Docker container that runs a command
- Substitutions (`_IMAGE_TAG`, `${PROJECT_ID}`) — variables injected at build time
- Build triggers — auto-deploy on git push (we do manual submit for now)
- Service account for Cloud Build — the identity that runs gcloud commands in steps

**Resources:**
- Cloud Build overview: https://cloud.google.com/build/docs/overview
- cloudbuild.yaml reference: https://cloud.google.com/build/docs/build-config-file-schema
- CI/CD with Cloud Run: https://cloud.google.com/run/docs/continuous-deployment-with-cloud-build

---

## 4. Docker & Containers

**What is it?**
Docker packages your app and all its dependencies (Python, libraries, config) into a single
portable unit called a container. The same container runs identically on your laptop, in CI,
and in production. A `Dockerfile` describes how to build the image.

**Why we use it:**
Eliminates "works on my machine" problems. Cloud Run requires a container image. Alembic
migrations run as a separate container (Cloud Run Job) using the same image.

**Key concepts to learn:**
- `Dockerfile` — recipe for building an image (FROM, COPY, RUN, CMD)
- Image vs container — image is the blueprint, container is the running instance
- Layers — each `RUN` line creates a layer; order matters for caching
- Multi-stage builds — keep the final image small by discarding build tools
- `.dockerignore` — what NOT to copy into the image (like `.env`, `__pycache__`)

**Resources:**
- Docker getting started (interactive): https://docs.docker.com/get-started/
- Best practices for Python Dockerfiles: https://docs.docker.com/language/python/
- Dive tool (visualize image layers): https://github.com/wagoodman/dive

---

## 5. PostgreSQL & SQLAlchemy

**What is it?**
PostgreSQL is the relational database storing all app data (users, conversations, turns,
jobs). SQLAlchemy is the Python ORM (Object Relational Mapper) — it lets you write Python
classes and queries instead of raw SQL. We use the async version (`asyncpg` driver).

**Why we use it:**
PostgreSQL is the industry standard for production apps. SQLAlchemy keeps queries type-safe
and makes it easy to change the DB schema over time with Alembic.

**Key concepts to learn:**
- ORM models (`db/models.py`) — Python classes that map to DB tables
- `async with session:` — how to open/close a DB session safely
- `select()`, `where()`, `order_by()` — SQLAlchemy query building
- Foreign keys and relationships — how `Conversation` links to `Turn`, `Job`, etc.
- Connection pooling — how SQLAlchemy reuses DB connections instead of opening new ones

**Resources:**
- SQLAlchemy async tutorial: https://docs.sqlalchemy.org/en/20/orm/extensions/asyncio.html
- PostgreSQL tutorial (interactive): https://www.postgresqltutorial.com/
- asyncpg docs: https://magicstack.github.io/asyncpg/current/

---

## 6. Alembic (Database Migrations)

**What is it?**
Alembic manages changes to your database schema over time. When you add a column or a new
table, you write a migration script. Alembic applies migrations in order, so every
environment (dev, staging, prod) stays in sync.

**Why we use it:**
You can't just delete and recreate the production DB every time the schema changes — it has
real user data. Alembic lets you evolve the schema safely.

**Key concepts to learn:**
- `alembic upgrade head` — apply all pending migrations
- `alembic revision --autogenerate` — generate a migration from model changes
- `alembic_version` table — Alembic tracks which migrations have run in this table
- Up/down migrations — every migration has an `upgrade()` and `downgrade()` function

**Commands used in this project:**
```bash
cd gervis-backend
uv run alembic upgrade head          # apply migrations
uv run alembic revision --autogenerate -m "add new column"  # generate migration
uv run alembic history               # see migration history
```

**Resources:**
- Alembic tutorial: https://alembic.sqlalchemy.org/en/latest/tutorial.html
- Alembic with async SQLAlchemy: https://alembic.sqlalchemy.org/en/latest/cookbook.html#using-asyncio-with-alembic

---

## 7. Cloud Tasks (Background Jobs)

**What is it?**
Cloud Tasks is a managed queue for dispatching HTTP tasks asynchronously. You push a task
(an HTTP request) onto the queue; Cloud Tasks delivers it to your endpoint with retries,
rate limiting, and scheduling — even if your service is temporarily down.

**Why we use it:**
When a user says "research X", we don't want Gemini to wait 30 minutes for results. Instead,
we enqueue a task immediately, Gemini acknowledges the user, and the task runs in the
background. When it finishes, we send an FCM notification.

**Key concepts to learn:**
- Queue → Task → Handler: queue holds tasks, task is an HTTP POST to your service, handler
  is the endpoint that processes it (`/internal/jobs/execute`)
- Retries — Cloud Tasks retries failed tasks automatically (configurable backoff)
- Dispatch deadline — max time allowed for a task to complete (we use 3600s)
- `X-CloudTasks-QueueName` header — Cloud Tasks adds this header so your handler can verify
  the request came from Cloud Tasks, not a random caller

**Resources:**
- Cloud Tasks overview: https://cloud.google.com/tasks/docs/dual-overview
- Creating HTTP tasks: https://cloud.google.com/tasks/docs/creating-http-target-tasks
- Python client library: https://cloud.google.com/python/docs/reference/cloudtasks/latest

---

## 8. Firebase Authentication

**What is it?**
Firebase Auth is Google's managed authentication service. It handles user registration,
login, Google Sign-In, email verification, and password reset. On Android, the Firebase SDK
manages tokens. The backend uses the Firebase Admin SDK to verify those tokens.

**Why we use it:**
Building auth from scratch is risky (security vulnerabilities). Firebase handles all the
hard parts: token rotation, session management, social login, brute force protection.

**Key concepts to learn:**
- Firebase ID token — short-lived JWT issued to the Android app after login
- Product JWT — our own longer-lived JWT issued by the backend after verifying the Firebase token
- `verify_firebase_token()` — backend checks the ID token is genuine before trusting the user
- `EncryptedSharedPreferences` — how the Android app stores tokens securely on-device

**Resources:**
- Firebase Auth for Android: https://firebase.google.com/docs/auth/android/start
- Firebase Admin SDK (Python): https://firebase.google.com/docs/admin/setup
- How JWTs work: https://jwt.io/introduction

---

## 9. Firebase Cloud Messaging (FCM)

**What is it?**
FCM is Google's push notification service. When a background job completes, the backend
sends a push notification to the user's phone via FCM, even if the app is closed.

**Why we use it:**
It's the standard way to deliver push notifications to Android (and iOS). Free, reliable,
and integrates directly with Firebase.

**Key concepts to learn:**
- Push token — a unique string that identifies a specific app install on a specific device
- Notification vs data message — notification shows in the system tray; data message is
  handled silently by the app
- `FcmService` (Android) — the service that receives messages and handles them
- Notification channels (Android 8+) — system requires you to declare channels before
  showing notifications (`spectalk_jobs` is ours)

**Resources:**
- FCM overview: https://firebase.google.com/docs/cloud-messaging
- Send messages with Admin SDK: https://firebase.google.com/docs/cloud-messaging/send-message
- FCM on Android: https://firebase.google.com/docs/cloud-messaging/android/client

---

## 10. Google Secret Manager

**What is it?**
Secret Manager stores sensitive values (API keys, database passwords, JWT secrets) outside
your source code. Cloud Run reads them at startup via `--set-secrets`. You never hardcode
credentials — they live in Secret Manager and are injected as environment variables.

**Why we use it:**
If you commit a secret to git, it's compromised forever (even if you delete it). Secret
Manager also has audit logs, version history, and IAM-controlled access.

**Key concepts to learn:**
- Secret vs Secret Version — a secret has a name, each value you store is a version
- `latest` — always points to the most recent version
- IAM binding — your Cloud Run service account needs `roles/secretmanager.secretAccessor`
  to read secrets
- `--set-secrets` in gcloud — maps a secret to an env var inside the container

**Resources:**
- Secret Manager quickstart: https://cloud.google.com/secret-manager/docs/quickstart
- Using secrets in Cloud Run: https://cloud.google.com/run/docs/configuring/services/secrets

---

## 11. Gemini Live API & Google ADK

**What is it?**
Gemini Live API is Google's real-time bidirectional audio streaming API — it's what makes
voice conversations with Gervis possible. Audio goes in, audio comes back, in real time.
Google ADK (Agent Development Kit) is the framework that manages the Gemini session,
tool calling, conversation state, and agent lifecycle.

**Why we use it:**
The Live API handles VAD (voice activity detection), transcription, and interruptions
natively. ADK makes it easy to attach tools (search, maps, background jobs) that Gemini
can call mid-conversation.

**Key concepts to learn:**
- `LiveRequestQueue` — the ADK queue that feeds audio chunks into the Gemini session
- Tool calling — Gemini decides mid-conversation to call a Python function (e.g. `find_nearby_places`)
- `ToolContext` — how tools access session state (like `conversation_id`)
- `input_audio_transcription` / `output_audio_transcription` — transcripts of what was said
- Interrupted event — Gemini fires this when the user speaks over the AI (barge-in)

**Resources:**
- Google ADK docs: https://google.github.io/adk-docs/
- Gemini Live API reference: https://ai.google.dev/api/multimodal-live
- ADK bidi-demo sample: `samples/adk-samples/python/agents/bidi-demo/` (in this repo)

---

## 12. WebSockets

**What is it?**
WebSockets are a persistent two-way connection between client (Android app) and server
(Cloud Run). Unlike HTTP (request → response → close), a WebSocket stays open so both
sides can send data at any time — essential for streaming audio in real time.

**Why we use it:**
Voice requires continuous low-latency data flow. HTTP would need a new request for each
audio chunk (too slow). WebSocket keeps the pipe open for the duration of the voice session.

**Key concepts to learn:**
- Handshake — WebSocket starts as an HTTP upgrade request, then becomes a persistent connection
- Binary frames vs text frames — we send PCM audio as binary, control messages as JSON text
- `asyncio.wait` with `FIRST_COMPLETED` — how `voice_handler.py` listens on phone AND Gemini simultaneously
- Ping/pong — how WebSocket clients detect dead connections

**Resources:**
- WebSockets explained: https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API
- FastAPI WebSockets: https://fastapi.tiangolo.com/advanced/websockets/
- WebSockets in Android (OkHttp): https://square.github.io/okhttp/4.x/okhttp/okhttp3/-web-socket/

---

## 13. Kotlin & Jetpack Compose (Android)

**What is it?**
Kotlin is the modern language for Android development. Jetpack Compose is Google's
declarative UI framework — instead of XML layouts, you write UI as composable functions.
The app's state drives the UI automatically.

**Why we use it:**
Compose is the current standard for new Android apps. Kotlin coroutines make async
operations (network calls, audio) clean without callback hell.

**Key concepts to learn:**
- `@Composable` functions — building blocks of the UI
- `StateFlow` / `collectAsStateWithLifecycle` — how ViewModel state flows to the UI
- Coroutines (`suspend`, `launch`, `withContext`) — async without callbacks
- ViewModel — survives screen rotation, owns business logic and state
- `EncryptedSharedPreferences` — secure local storage for tokens

**Resources:**
- Jetpack Compose pathway (official, interactive): https://developer.android.com/courses/jetpack-compose/course
- Kotlin coroutines: https://kotlinlang.org/docs/coroutines-overview.html
- Android architecture guide: https://developer.android.com/topic/architecture

---

## 14. OpenTelemetry & Cloud Trace

**What is it?**
OpenTelemetry is an open standard for collecting observability data (traces, metrics, logs).
A "trace" is a record of work done — for example, how long `find_nearby_places` took, or
which conversation triggered a background job. Cloud Trace is Google's backend for storing
and visualizing those traces.

**Why we use it:**
When something breaks in production, logs alone don't tell you what was slow or why. Traces
show the full timeline of a request across services — from WebSocket open to Gemini response
to Cloud Task dispatch.

**Key concepts to learn:**
- Span — a unit of work with a start time, end time, and attributes
- Trace — a collection of spans that together describe a full operation
- `@trace_span("name")` — our decorator in `services/tracing.py` that wraps any async function
- ADC (Application Default Credentials) — how Cloud Run authenticates to Cloud Trace without
  an API key (uses the service account automatically)

**Resources:**
- OpenTelemetry Python: https://opentelemetry.io/docs/languages/python/getting-started/
- Cloud Trace overview: https://cloud.google.com/trace/docs/overview
- How to read a trace waterfall: https://cloud.google.com/trace/docs/finding-traces

---

## 15. Neon PostgreSQL (Serverless DB)

**What is it?**
Neon is a serverless PostgreSQL provider. "Serverless" means the database compute pauses
when idle and resumes on the next connection — similar to Cloud Run with `--min-instances=0`.
You get a standard PostgreSQL database without managing a server.

**Why we use it:**
Free tier, instant setup, no infrastructure to manage. Perfect for development. For
production with real users, we'd migrate to Cloud SQL.

**Key concepts to learn:**
- Auto-suspend — compute pauses after ~5 minutes idle; `pool_pre_ping=True` in SQLAlchemy
  handles the stale connection by testing before each query
- Connection string — `postgresql+asyncpg://user:pass@host/db?ssl=require`
- Branching — Neon lets you create database branches (like git branches) for testing

**Resources:**
- Neon getting started: https://neon.tech/docs/get-started-with-neon/signing-up
- Neon + SQLAlchemy: https://neon.tech/docs/guides/sqlalchemy
- Why serverless databases pause: https://neon.tech/docs/introduction/auto-suspend

---

## 16. uv (Python Package Manager)

**What is it?**
`uv` is a fast Python package manager written in Rust — a modern replacement for `pip` and
`venv`. It manages virtual environments, dependencies, and lockfiles. We use it exclusively
in this project (never `pip install`).

**Why we use it:**
10-100x faster than pip. Single tool for environment + package management. `uv.lock` ensures
every developer and Cloud Build uses the exact same package versions.

**Key commands:**
```bash
uv sync                    # install all dependencies from pyproject.toml
uv add <package>           # add a new dependency
uv run <command>           # run a command inside the virtual environment
uv run uvicorn main:app    # start the backend server
uv run pytest              # run tests
```

**Resources:**
- uv docs: https://docs.astral.sh/uv/
- uv vs pip comparison: https://docs.astral.sh/uv/pip/compatibility/

---

## 17. Artifact Registry

**What is it?**
Artifact Registry is Google's private Docker image registry. When Cloud Build builds the
backend image, it pushes it here. Cloud Run pulls from here on every deploy. Think of it
like Docker Hub but private and inside GCP.

**Why we use it:**
Keeps images private, co-located with the rest of the infrastructure (lower latency pulls),
and integrates with IAM for access control.

**Key concepts to learn:**
- Repository — a collection of images (ours is `gervis` in `us-central1`)
- Image tag — a label for a version (we use `latest` + `SHORT_SHA` in CI)
- `docker.pkg.dev` — the hostname format for Artifact Registry images
  (`us-central1-docker.pkg.dev/spectalk-488516/gervis/gervis-backend:latest`)

**Resources:**
- Artifact Registry quickstart: https://cloud.google.com/artifact-registry/docs/docker/quickstart
- Docker image naming: https://cloud.google.com/artifact-registry/docs/docker/names

---

## Recommended Learning Order

If you're starting from zero, this order builds on itself:

1. **Docker** — understanding containers unlocks everything else
2. **Python + FastAPI** — the backend language and framework
3. **PostgreSQL + SQLAlchemy** — how data is stored
4. **Alembic** — how the schema evolves
5. **WebSockets** — how real-time communication works
6. **Cloud Run** — where the backend lives
7. **Cloud Build** — how deploys happen automatically
8. **Firebase Auth + FCM** — identity and notifications
9. **Cloud Tasks** — background job queuing
10. **Gemini Live API + ADK** — the AI voice layer
11. **OpenTelemetry** — observability (after everything else is working)
