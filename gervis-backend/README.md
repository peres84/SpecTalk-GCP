# Gervis Backend

FastAPI backend for SpecTalk. Owns the Gemini Live session, all tools, all credentials, and
all conversation state. The Android app connects here — it never holds any API keys.

## Prerequisites

- Python 3.12+
- [uv](https://docs.astral.sh/uv/getting-started/installation/) — `curl -LsSf https://astral.sh/uv/install.sh | sh`
- PostgreSQL instance (local or Cloud SQL)
- Firebase project with Email/Password auth enabled
- `gcloud` CLI (for Cloud SQL / Application Default Credentials)

## Getting Started

### 1. Install dependencies

```bash
uv sync
```

### 2. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and fill in:

| Variable | Where to get it |
|----------|----------------|
| `FIREBASE_PROJECT_ID` | Firebase Console → Project Settings |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Console → Project Settings → Service accounts → Generate new private key — save the JSON file and set the path here. Or leave blank and use `gcloud auth application-default login` instead. |
| `JWT_SECRET` | Any random 256-bit string — run `openssl rand -hex 32` |
| `DATABASE_URL` | `postgresql+asyncpg://user:pass@localhost:5432/spectalk` for local Postgres |

### 3. Set up the database

```bash
# Create the database (local Postgres example)
createdb spectalk

# Run migrations (creates all tables)
uv run alembic upgrade head
```

### 4. Run the server

```bash
uv run uvicorn main:app --reload --port 8080
```

API is now available at `http://localhost:8080`. Interactive docs at `http://localhost:8080/docs`.

---

## Auth Flow

```
Android                        Backend                     Firebase
  │                              │                            │
  ├─ createUserWithEmailAndPassword ──────────────────────────►│
  │◄──────────────────── Firebase ID token ───────────────────┤
  │                              │                            │
  ├─ POST /auth/session ─────────►│                            │
  │   { firebase_id_token }      ├─ verify_id_token ──────────►│
  │                              │◄──── decoded claims ────────┤
  │                              ├─ upsert User in DB          │
  │◄──── { access_token } ───────┤                            │
  │                              │                            │
  ├─ All future API calls        │                            │
  │   Authorization: Bearer <access_token>                    │
```

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/health` | None | Health check |
| `POST` | `/auth/session` | None | Exchange Firebase ID token → product JWT |

More endpoints are added in later phases (see `../TODO.md`).

## Project Structure

```
gervis-backend/
├── main.py              # FastAPI app, lifespan, router registration
├── config.py            # Pydantic settings (reads .env)
├── pyproject.toml       # Dependencies (managed with uv)
├── Dockerfile
├── auth/
│   ├── firebase.py      # verify_firebase_token
│   └── jwt_handler.py   # sign_product_jwt / verify_product_jwt
├── api/
│   └── auth.py          # POST /auth/session
├── db/
│   ├── database.py      # Async SQLAlchemy engine, get_db dependency
│   └── models.py        # ORM models (User, ...)
└── middleware/
    └── auth.py          # require_auth FastAPI dependency
```

## Running Tests

```bash
uv run pytest
```

## Docker

```bash
docker build -t gervis-backend .
docker run -p 8080:8080 --env-file .env gervis-backend
```

## Deploying to Cloud Run

See `../docs/architecture.md` for the full Cloud Run deployment guide and IAM setup.

```bash
gcloud run deploy gervis-backend \
  --source . \
  --region us-central1 \
  --allow-unauthenticated \
  --set-secrets="JWT_SECRET=JWT_SECRET:latest,DATABASE_URL=DATABASE_URL:latest"
```
