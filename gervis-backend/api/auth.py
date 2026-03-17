from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from auth.firebase import verify_firebase_token
from auth.jwt_handler import sign_product_jwt
from db.database import get_db
from db.models import User

router = APIRouter()


class SessionRequest(BaseModel):
    firebase_id_token: str


class SessionResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user_id: str
    email: str


@router.post("/session", response_model=SessionResponse)
async def create_session(
    body: SessionRequest,
    db: AsyncSession = Depends(get_db),
):
    """
    Exchange a Firebase ID token for a product JWT.
    Creates the user record on first login.
    """
    claims = await verify_firebase_token(body.firebase_id_token)

    firebase_uid: str = claims["uid"]
    email: str = claims.get("email", "")
    display_name: str | None = claims.get("name")

    # Fetch or create user
    result = await db.execute(select(User).where(User.firebase_uid == firebase_uid))
    user = result.scalar_one_or_none()

    if user is None:
        user = User(
            firebase_uid=firebase_uid,
            email=email,
            display_name=display_name,
        )
        db.add(user)
        await db.commit()
        await db.refresh(user)
    else:
        # Update mutable fields if they changed
        changed = False
        if user.email != email:
            user.email = email
            changed = True
        if display_name and user.display_name != display_name:
            user.display_name = display_name
            changed = True
        if changed:
            await db.commit()
            await db.refresh(user)

    token = sign_product_jwt(str(user.id), user.email)

    return SessionResponse(
        access_token=token,
        user_id=str(user.id),
        email=user.email,
    )
