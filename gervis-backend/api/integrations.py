"""User integration credentials API.

Allows authenticated users to save, view, and delete their third-party
integration credentials (e.g. OpenClaw URL + hook token).

All credentials are Fernet-encrypted before hitting the database.
The plaintext values are never persisted — only the encrypted blobs.

Endpoints:
  POST   /integrations              — save (upsert) credentials for a service
  GET    /integrations              — list configured integrations (masked URLs)
  DELETE /integrations/{service}    — remove credentials for a service
"""

import uuid
import logging

from fastapi import APIRouter, Depends, HTTPException, status, Response
from pydantic import BaseModel, field_validator
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, delete

from db.database import get_db
from db.models import UserIntegration
from middleware.auth import require_auth
from services.encryption_service import encrypt, decrypt, mask_url

logger = logging.getLogger(__name__)

router = APIRouter()

SUPPORTED_SERVICES = {"openclaw"}


# ── Request / response models ─────────────────────────────────────────────────

class SaveIntegrationRequest(BaseModel):
    service: str
    url: str
    token: str

    @field_validator("service")
    @classmethod
    def validate_service(cls, v: str) -> str:
        v = v.lower().strip()
        if v not in SUPPORTED_SERVICES:
            raise ValueError(f"Unsupported service '{v}'. Supported: {sorted(SUPPORTED_SERVICES)}")
        return v

    @field_validator("url")
    @classmethod
    def validate_url(cls, v: str) -> str:
        v = v.strip()
        if not v.startswith(("http://", "https://")):
            raise ValueError("url must start with http:// or https://")
        return v

    @field_validator("token")
    @classmethod
    def validate_token(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("token must not be empty")
        return v


class IntegrationSavedResponse(BaseModel):
    status: str
    service: str
    url_preview: str
    message: str


class IntegrationItem(BaseModel):
    service: str
    url_preview: str
    connected: bool


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.post("", response_model=IntegrationSavedResponse)
async def save_integration(
    body: SaveIntegrationRequest,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Save (upsert) encrypted integration credentials for the authenticated user.

    Credentials are encrypted with Fernet before storage. The response
    includes a one-time url_preview (shown to the user as confirmation).
    Subsequent GET requests will show a masked preview only.
    """
    user_id = uuid.UUID(payload["sub"])

    encrypted_url = encrypt(body.url)
    encrypted_token = encrypt(body.token)

    # Upsert — update if exists, insert if not
    result = await db.execute(
        select(UserIntegration).where(
            UserIntegration.user_id == user_id,
            UserIntegration.service_name == body.service,
        )
    )
    existing = result.scalar_one_or_none()

    if existing:
        existing.encrypted_url = encrypted_url
        existing.encrypted_token = encrypted_token
        logger.info(f"[integrations] Updated {body.service} credentials for user {str(user_id)[:8]}…")
    else:
        db.add(UserIntegration(
            user_id=user_id,
            service_name=body.service,
            encrypted_url=encrypted_url,
            encrypted_token=encrypted_token,
        ))
        logger.info(f"[integrations] Saved new {body.service} credentials for user {str(user_id)[:8]}…")

    await db.commit()

    return IntegrationSavedResponse(
        status="saved",
        service=body.service,
        url_preview=body.url,  # full URL shown once on save
        message="Your credentials have been encrypted and stored securely.",
    )


@router.get("", response_model=list[IntegrationItem])
async def list_integrations(
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """List all configured integrations for the authenticated user.

    URLs are masked — only a truncated preview is returned (never the plaintext).
    """
    user_id = uuid.UUID(payload["sub"])

    result = await db.execute(
        select(UserIntegration).where(UserIntegration.user_id == user_id)
    )
    integrations = result.scalars().all()

    items = []
    for integration in integrations:
        try:
            plain_url = decrypt(integration.encrypted_url)
            url_preview = mask_url(plain_url)
            connected = True
        except Exception:
            url_preview = "(decryption error)"
            connected = False

        items.append(IntegrationItem(
            service=integration.service_name,
            url_preview=url_preview,
            connected=connected,
        ))

    return items


@router.delete("/{service}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_integration(
    service: str,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Remove integration credentials for a service."""
    user_id = uuid.UUID(payload["sub"])
    service = service.lower().strip()

    result = await db.execute(
        select(UserIntegration.id).where(
            UserIntegration.user_id == user_id,
            UserIntegration.service_name == service,
        )
    )
    if result.scalar_one_or_none() is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Integration not found")

    await db.execute(
        delete(UserIntegration).where(
            UserIntegration.user_id == user_id,
            UserIntegration.service_name == service,
        )
    )
    await db.commit()
    logger.info(f"[integrations] Deleted {service} credentials for user {str(user_id)[:8]}…")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


# ── Internal helper (used by openclaw_coding_tool) ────────────────────────────

async def get_decrypted_integration(user_id: str, service: str) -> tuple[str, str] | None:
    """Return (url, token) for a user's integration, or None if not configured.

    This is called from tools, not from the API layer, so it opens its own session.
    """
    from db.database import AsyncSessionLocal

    try:
        async with AsyncSessionLocal() as db_session:
            result = await db_session.execute(
                select(UserIntegration).where(
                    UserIntegration.user_id == uuid.UUID(user_id),
                    UserIntegration.service_name == service,
                )
            )
            integration = result.scalar_one_or_none()
            if integration is None:
                return None
            url = decrypt(integration.encrypted_url)
            token = decrypt(integration.encrypted_token)
            return url, token
    except Exception as e:
        logger.error(f"[integrations] Failed to load {service} credentials for user {user_id[:8]}…: {e}")
        return None
