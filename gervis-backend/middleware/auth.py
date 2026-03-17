from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from auth.jwt_handler import verify_product_jwt

bearer_scheme = HTTPBearer()


def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(bearer_scheme),
) -> dict:
    """Dependency that validates the product JWT and returns the payload."""
    return verify_product_jwt(credentials.credentials)


def require_auth(payload: dict = Depends(get_current_user)) -> dict:
    """Shorthand dependency alias for protected routes."""
    return payload
