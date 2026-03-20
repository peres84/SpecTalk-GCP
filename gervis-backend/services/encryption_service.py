"""Symmetric encryption for user integration credentials.

Uses Fernet (AES-128-CBC + HMAC-SHA256) from the `cryptography` package,
which ships as a transitive dependency of python-jose[cryptography].

Key management:
  Production — set INTEGRATION_ENCRYPTION_KEY in Secret Manager.
               Generate with: python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
  Development — a per-process random key is generated on first use and a
               loud warning is logged. Credentials saved in dev will not
               survive a process restart.
"""

import base64
import logging
import os

logger = logging.getLogger(__name__)

_fernet = None  # module-level singleton


def _get_fernet():
    global _fernet
    if _fernet is not None:
        return _fernet

    from cryptography.fernet import Fernet

    raw_key = os.environ.get("INTEGRATION_ENCRYPTION_KEY", "")
    if raw_key:
        try:
            key = raw_key.encode() if isinstance(raw_key, str) else raw_key
            _fernet = Fernet(key)
            logger.info("encryption_service: Fernet key loaded from INTEGRATION_ENCRYPTION_KEY")
        except Exception as e:
            raise RuntimeError(
                f"INTEGRATION_ENCRYPTION_KEY is set but invalid: {e}. "
                "Generate a valid key with: python -c \"from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())\""
            ) from e
    else:
        dev_key = Fernet.generate_key()
        _fernet = Fernet(dev_key)
        logger.warning(
            "encryption_service: INTEGRATION_ENCRYPTION_KEY is not set. "
            "Using a per-process random key — stored credentials WILL NOT survive "
            "a server restart. Set INTEGRATION_ENCRYPTION_KEY in production."
        )

    return _fernet


def encrypt(plaintext: str) -> str:
    """Encrypt a string. Returns a URL-safe base64 Fernet token (str)."""
    f = _get_fernet()
    return f.encrypt(plaintext.encode()).decode()


def decrypt(token: str) -> str:
    """Decrypt a Fernet token. Raises cryptography.fernet.InvalidToken on failure."""
    f = _get_fernet()
    return f.decrypt(token.encode()).decode()


def mask_url(url: str) -> str:
    """Return a masked version of a URL safe to show in list responses.

    Example: "https://my-machine.tail-abc.ts.net" -> "https://my-machine.tail-....ts.net"
    Falls back to showing first 20 chars + "..." for non-standard URLs.
    """
    if not url:
        return ""
    try:
        from urllib.parse import urlparse
        parsed = urlparse(url)
        host = parsed.netloc or parsed.path
        if len(host) > 24:
            host_masked = host[:12] + "..." + host[-8:]
        else:
            host_masked = host
        return f"{parsed.scheme}://{host_masked}" if parsed.scheme else host_masked
    except Exception:
        return url[:20] + "..." if len(url) > 20 else url
