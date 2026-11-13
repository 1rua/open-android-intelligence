"""TOTP and high-entropy recovery-code primitives for Core identities.

State changes and replay protection belong to IdentityStore's transaction, not
this module.  A configured host key store encrypts each account's secret under
an instance- and purpose-specific derived key.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
from urllib.parse import quote, urlencode

PERIOD = 30
DIGITS = 6
RECOVERY_COUNT = 10


def new_secret() -> bytes:
    return secrets.token_bytes(20)


def encode_secret(secret: bytes) -> str:
    return base64.b32encode(secret).decode("ascii").rstrip("=")


def enrollment_uri(secret: bytes, username: str, issuer: str = "OAI Core") -> str:
    label = quote(f"{issuer}:{username}", safe="")
    query = urlencode({"secret": encode_secret(secret), "issuer": issuer,
                       "algorithm": "SHA1", "digits": DIGITS, "period": PERIOD})
    return f"otpauth://totp/{label}?{query}"


def matching_step(secret: bytes, code: str, now: float, last_step: int | None) -> int | None:
    """Accept one adjacent period but never a previously accepted time step."""
    if not isinstance(code, str) or len(code) != DIGITS or not code.isascii() or not code.isdigit():
        return None
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.twofactor.totp import TOTP
    generator = TOTP(secret, DIGITS, hashes.SHA1(), PERIOD)
    current = int(now // PERIOD)
    for step in (current, current - 1, current + 1):
        if step < 0 or (last_step is not None and step <= last_step):
            continue
        if hmac.compare_digest(generator.generate(step * PERIOD), code.encode("ascii")):
            return step
    return None


def new_recovery_codes() -> list[str]:
    return [secrets.token_urlsafe(24) for _ in range(RECOVERY_COUNT)]


def recovery_digest(instance_id: str, account_id: str, code: str) -> str:
    return hashlib.sha256(
        ("oai-core-mfa-recovery\0" + instance_id + "\0" + account_id + "\0" + code).encode("utf-8")
    ).hexdigest()


def encrypt_secret(key_store, instance_id: str, account_id: str, secret: bytes) -> bytes:
    if key_store is None:
        raise ValueError("MFA_KEY_UNAVAILABLE")
    provider = key_store.get_or_create_aead(f"oai-core:mfa:{instance_id}:{account_id}")
    associated = f"oai-core:mfa:v1:{instance_id}:{account_id}".encode()
    return bytes(provider.encrypt(secret, associated))


def decrypt_secret(key_store, instance_id: str, account_id: str, ciphertext: bytes) -> bytes:
    if key_store is None:
        raise ValueError("MFA_KEY_UNAVAILABLE")
    provider = key_store.get_or_create_aead(f"oai-core:mfa:{instance_id}:{account_id}")
    associated = f"oai-core:mfa:v1:{instance_id}:{account_id}".encode()
    secret = bytes(provider.decrypt(bytes(ciphertext), associated))
    if len(secret) != 20:
        raise ValueError("MFA_SECRET_INVALID")
    return secret
