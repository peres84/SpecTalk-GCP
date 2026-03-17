# Database Setup — PostgreSQL for Gervis Backend

The backend requires a PostgreSQL 15+ database. There is no local database requirement —
choose one of the options below based on your environment.

---

## Option 1: Neon (Recommended for development)

**Free tier, serverless, zero setup.**

1. Go to [neon.tech](https://neon.tech) and create a free account.
2. Create a new project (pick the region closest to your Cloud Run region, e.g. `eu-central-1` for Frankfurt).
3. Copy the connection string from the dashboard. It looks like:

   ```
   postgresql://user:password@ep-xxxx.eu-central-1.aws.neon.tech/neondb?sslmode=require
   ```

4. Set it in your `.env`:

   ```env
   DATABASE_URL=postgresql+asyncpg://user:password@ep-xxxx.eu-central-1.aws.neon.tech/neondb?ssl=require
   ```

   > **Important gotchas:**
   > - Replace `postgresql://` with `postgresql+asyncpg://` for SQLAlchemy async engine.
   > - Replace `sslmode=require` with `ssl=require` — asyncpg does not support `sslmode`.
   > - Remove `channel_binding=require` — not supported by asyncpg.
   >
   > Neon gives you: `...?sslmode=require&channel_binding=require`
   > You need:       `...?ssl=require`

5. Run migrations:

   ```bash
   cd gervis-backend
   uv run alembic upgrade head
   ```

**Cost**: Free (0.5 GB storage, 1 compute unit).

---

## Option 2: Supabase (Free tier)

1. Go to [supabase.com](https://supabase.com) and create a free project.
2. Go to **Settings → Database → Connection string → URI**.
3. Copy the URI and replace `postgresql://` with `postgresql+asyncpg://`.
4. Set `DATABASE_URL` in `.env` and run migrations as above.

**Cost**: Free (500 MB database, 2 projects).

---

## Option 3: Cloud SQL on Google Cloud (Production)

This is the production target per `architecture.md`. Use this when deploying to Cloud Run.

### Create the instance

```bash
# Create PostgreSQL 16 instance in Frankfurt
gcloud sql instances create gervis-db \
  --database-version=POSTGRES_16 \
  --tier=db-f1-micro \
  --region=europe-west3 \
  --root-password=YOUR_STRONG_PASSWORD

# Create the application database
gcloud sql databases create spectalk --instance=gervis-db

# Create the application user
gcloud sql users create gervis \
  --instance=gervis-db \
  --password=YOUR_APP_PASSWORD
```

### Get the connection name

```bash
gcloud sql instances describe gervis-db --format="value(connectionName)"
# Output: your-project:europe-west3:gervis-db
```

### Configure the backend

When running on Cloud Run, the backend connects via the **Cloud SQL Python Connector**
(already in `pyproject.toml` as `cloud-sql-python-connector`). Set these env vars in
Cloud Run (injected from Secret Manager):

```env
CLOUD_SQL_CONNECTION_NAME=your-project:europe-west3:gervis-db
DB_USER=gervis
DB_PASSWORD=YOUR_APP_PASSWORD
DB_NAME=spectalk
```

The connector is already wired in `db/database.py` — it handles the Unix socket connection
automatically when `CLOUD_SQL_CONNECTION_NAME` is set.

### Run migrations on Cloud Run

```bash
# One-off migration job via Cloud Run Jobs or locally with proxy
gcloud sql connect gervis-db --user=gervis --database=spectalk
# Then run: uv run alembic upgrade head
```

**Cost**: ~$25/month for `db-g1-small` with `--edition=ENTERPRISE` (cheapest available tier as of 2026 — `db-f1-micro` was retired).

> **Note:** Cloud SQL bills 24/7 even when idle. Stop it when not in use:
> ```bash
> gcloud sql instances patch gervis-db --activation-policy=NEVER   # stop
> gcloud sql instances patch gervis-db --activation-policy=ALWAYS  # start
> ```

---

## Environment Variables Reference

| Variable | Description | Example |
|----------|-------------|---------|
| `DATABASE_URL` | Full async connection URL (Neon/Supabase) | `postgresql+asyncpg://...` |
| `CLOUD_SQL_CONNECTION_NAME` | Cloud SQL instance connection name | `project:region:instance` |
| `DB_USER` | Database user | `gervis` |
| `DB_PASSWORD` | Database password | (from Secret Manager) |
| `DB_NAME` | Database name | `spectalk` |

When `CLOUD_SQL_CONNECTION_NAME` is set, `db/database.py` uses the Cloud SQL connector.
Otherwise it uses `DATABASE_URL` directly (for Neon/Supabase/local dev).

---

## Generating Secrets

To generate a strong secret (e.g. for `JWT_SECRET`):

```bash
python -c "import secrets; print(secrets.token_hex(32))"
```

---

## Quick Start (Dev)

```bash
# 1. Sign up at neon.tech, copy your connection string
# 2. Create .env from template
cp .env.example .env
# 3. Set DATABASE_URL in .env
# 4. Install deps
uv sync
# 5. Run migrations
uv run alembic upgrade head
# 6. Start server
uv run uvicorn main:app --reload --port 8080
```
