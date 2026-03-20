from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import field_validator


_SHUTDOWN_LIVE_MODELS = {
    "gemini-2.0-flash-live-001": (
        "Gemini Live model gemini-2.0-flash-live-001 was shut down on 2025-12-09. "
        "Use gemini-2.5-flash-native-audio-preview-12-2025."
    ),
    "gemini-live-2.5-flash-preview": (
        "Gemini Live model gemini-live-2.5-flash-preview was shut down on 2025-12-09. "
        "Use gemini-2.5-flash-native-audio-preview-12-2025."
    ),
}


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    firebase_project_id: str = ""
    firebase_service_account_json: str = ""

    jwt_secret: str = "change-this-in-production"
    jwt_algorithm: str = "HS256"
    jwt_expire_hours: int = 720  # 30 days

    database_url: str = "postgresql+asyncpg://user:pass@localhost:5432/spectalk"

    @field_validator("*", mode="before")
    @classmethod
    def strip_whitespace(cls, v):
        """Strip trailing whitespace/newlines from all string secrets.

        Secrets stored via Secret Manager on Windows (or via echo) often
        include a trailing \\r\\n which causes httpx header validation errors.
        """
        if isinstance(v, str):
            return v.strip()
        return v

    @field_validator("database_url", mode="before")
    @classmethod
    def fix_asyncpg_ssl(cls, v: str) -> str:
        # Neon/psycopg2 URLs contain params asyncpg doesn't understand
        # (sslmode, channel_binding, etc). Strip all query params and
        # replace with just ssl=require if SSL was requested.
        if not isinstance(v, str) or "?" not in v:
            return v
        base, query = v.split("?", 1)
        params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
        ssl_mode = params.get("sslmode") or params.get("ssl", "")
        if ssl_mode and ssl_mode != "disable":
            return f"{base}?ssl=require"
        return base

    @field_validator("gemini_model")
    @classmethod
    def validate_gemini_model(cls, v: str) -> str:
        shutdown_message = _SHUTDOWN_LIVE_MODELS.get(v)
        if shutdown_message:
            raise ValueError(shutdown_message)
        return v

    environment: str = "development"
    allowed_origins: list[str] = ["http://localhost:3000"]

    # Phase 3: Gemini Live + ADK
    # Get from https://aistudio.google.com/app/apikey
    # Used for: Gemini Live voice session, Google Search grounding, Google Maps grounding
    # docs https://docs.cloud.google.com/vertex-ai/generative-ai/docs/models/gemini/2-5-flash-live-api
    gemini_api_key: str = ""
    adk_app_name: str = "spectalk"
    gemini_model: str = "gemini-live-2.5-flash-native-audio"
    gemini_voice_name: str = "Algieba"

    # Phase 4: Background jobs, Cloud Tasks, and FCM notifications
    # GCP project ID — required for Cloud Tasks in production
    gcp_project: str = ""
    # Cloud Tasks queue name (e.g. "backend-jobs"). Leave empty to skip Cloud Tasks.
    cloud_tasks_queue: str = ""
    cloud_tasks_location: str = "us-central1"
    # Full Cloud Run service URL — used as the target for Cloud Tasks callbacks
    backend_base_url: str = "http://localhost:8080"
    # Cloud Run service account email for OIDC token auth on Cloud Tasks callbacks
    cloud_run_service_account: str = ""

    # Phase 5: User integration credentials (OpenClaw etc.) encrypted at rest.
    # Generate with: python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
    # Store in Secret Manager as INTEGRATION_ENCRYPTION_KEY.
    integration_encryption_key: str = ""

    # Opik (Comet ML) — optional agent observability. Set OPIK_API_KEY to enable.
    opik_api_key: str = ""
    opik_workspace: str = "javier-peres"
    opik_project_name: str = "gervis"

    # Base directory on the OpenClaw VPS where all projects are created.
    # OpenClaw will create each project as a subdirectory inside this path.
    openclaw_projects_dir: str = "~/websites_projects"


settings = Settings()
