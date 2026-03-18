from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    firebase_project_id: str = ""
    firebase_service_account_json: str = ""

    jwt_secret: str = "change-this-in-production"
    jwt_algorithm: str = "HS256"
    jwt_expire_hours: int = 720  # 30 days

    database_url: str = "postgresql+asyncpg://user:pass@localhost:5432/spectalk"

    environment: str = "development"
    allowed_origins: list[str] = ["http://localhost:3000"]

    # Phase 3: Gemini Live + ADK
    # Get from https://aistudio.google.com/app/apikey
    # Used for: Gemini Live voice session, Google Search grounding, Google Maps grounding
    gemini_api_key: str = ""
    adk_app_name: str = "spectalk"
    gemini_model: str = "gemini-2.5-flash-native-audio-preview-12-2025"

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


settings = Settings()
