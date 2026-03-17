import os
import firebase_admin
from firebase_admin import auth as firebase_auth, credentials
from fastapi import HTTPException, status

from config import settings

_firebase_app: firebase_admin.App | None = None


def get_firebase_app() -> firebase_admin.App:
    global _firebase_app
    if _firebase_app is not None:
        return _firebase_app

    if settings.firebase_service_account_json and os.path.exists(
        settings.firebase_service_account_json
    ):
        cred = credentials.Certificate(settings.firebase_service_account_json)
        _firebase_app = firebase_admin.initialize_app(cred)
    else:
        # Use Application Default Credentials (gcloud auth application-default login locally,
        # or the Cloud Run service account in production). Pass the project ID explicitly so
        # verify_id_token knows which Firebase project to validate tokens against.
        _firebase_app = firebase_admin.initialize_app(
            options={"projectId": settings.firebase_project_id}
        )

    return _firebase_app


async def verify_firebase_token(id_token: str) -> dict:
    """Verify a Firebase ID token and return the decoded claims."""
    try:
        get_firebase_app()
        decoded = firebase_auth.verify_id_token(id_token)
        return decoded
    except firebase_auth.ExpiredIdTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Firebase token expired",
        )
    except firebase_auth.InvalidIdTokenError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Invalid Firebase token: {e}",
        )
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Token verification failed: {e}",
        )
