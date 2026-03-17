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


settings = Settings()
