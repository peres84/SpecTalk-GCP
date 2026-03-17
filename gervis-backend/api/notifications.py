from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from db.database import get_db
from db.models import User
from middleware.auth import require_auth

import uuid

router = APIRouter()


class DeviceRegisterRequest(BaseModel):
    push_token: str


class DeviceRegisterResponse(BaseModel):
    registered: bool


@router.post("/device/register", response_model=DeviceRegisterResponse)
async def register_device(
    body: DeviceRegisterRequest,
    payload: dict = Depends(require_auth),
    db: AsyncSession = Depends(get_db),
):
    """Store the FCM push token for the authenticated user's device."""
    user_id = uuid.UUID(payload["sub"])
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

    user.push_token = body.push_token
    await db.commit()

    return DeviceRegisterResponse(registered=True)
