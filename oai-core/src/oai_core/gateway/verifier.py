"""Gateway Protocol v2 exact-byte signature verification, independent of Agent hosts."""
from __future__ import annotations

import base64
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping

from .core import (WIRE_PROTOCOL, VerifiedGatewayRequest, VerifiedRequestContext, canonicalize_target,
                   request_signature_preimage, request_signature_preimage_from_digest)

class LocalCredentialVerifier:
    """Sandbox credential verifier that must be supplied explicitly.

    Accepts any non-empty password as long as the username matches the account,
    which is only appropriate for a local sandbox driven without provisioning a
    password. It is never selected by default: composed Hermes deployments use
    :class:`AccountPasswordVerifier`, which reads the digest recorded by the
    local admin surface. It has no power to bring an account into existence.
    """

    def verify(self, account_id: str, username: str, password: str, installation: Mapping[str, Any]) -> bool:
        if not account_id or not username or not password:
            return False
        if not isinstance(installation, Mapping) or not installation.get("installationId"):
            return False
        return str(username).strip() == str(account_id).strip()


class AccountPasswordVerifier:
    """Verifies the password the local admin surface recorded for the account.

    Fails closed on every other case: unknown account, account with no recorded
    password, empty password, or a username that does not address the account.
    An account nobody gave a password to cannot be logged into at all.
    """

    def __init__(self, core: Any):
        self._core = core

    def verify(self, account_id: str, username: str, password: str, installation: Mapping[str, Any]) -> bool:
        if not isinstance(account_id, str) or not account_id:
            return False
        if not isinstance(username, str) or username.strip() != account_id:
            return False
        if not isinstance(password, str) or not password:
            return False
        if not isinstance(installation, Mapping) or not installation.get("installationId"):
            return False
        exists = getattr(self._core, "account_exists", None)
        if callable(exists) and not exists(account_id):
            return False
        try:
            account = self._core.open_gateway_account(account_id)
        except Exception:
            return False
        try:
            return bool(account.credentials.verify_password(password))
        finally:
            account.close()


_SINGLETON_REQUEST_HEADERS = frozenset({
    "authorization",
    "x-open-android-intelligence-protocol",
    "x-open-android-intelligence-account",
    "x-open-android-intelligence-device",
    "x-open-android-intelligence-session",
    "x-open-android-intelligence-request-id",
    "x-open-android-intelligence-timestamp",
    "x-open-android-intelligence-nonce",
    "x-open-android-intelligence-signature",
    "idempotency-key",
    "last-event-id",
    "content-type",
    "content-length",
    "digest",
})


_WIRE_ID = re.compile(r"^[A-Za-z0-9._~-]{1,128}$")


_TIMESTAMP = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$")


_BASE64URL = re.compile(r"^[A-Za-z0-9_-]+$")


def _header_pairs(input: Mapping[str, Any]) -> list[tuple[str, str]]:
    raw = input.get("rawHeaders", input.get("raw_headers"))
    pairs: list[tuple[str, str]] = []
    if raw:
        items = list(raw)
        if items and isinstance(items[0], (list, tuple)) and len(items[0]) == 2:
            return [(str(name), str(value)) for name, value in items]
        return [(str(items[index]), str(items[index + 1])) for index in range(0, len(items) - 1, 2)]
    headers = input.get("headers") or {}
    if isinstance(headers, Mapping):
        pairs = [(str(name), str(value)) for name, value in headers.items()]
    return pairs


def _singleton_headers(input: Mapping[str, Any]) -> dict[str, str] | None:
    """Case-folded headers, or None when a singleton arrived more than once."""
    result: dict[str, str] = {}
    for name, value in _header_pairs(input):
        key = name.lower()
        if not key or any(character.isspace() for character in key):
            return None
        # An obs-fold continuation arrives as a value starting with space or
        # tab; treating it as an independent header would let it add a second
        # meaning to a singleton.
        value = value.strip() if value[:1] in (" ", "\t") else value
        if key in _SINGLETON_REQUEST_HEADERS and key in result:
            return None
        result[key] = value
    return result


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _epoch_millis(value: str) -> int:
    """Contract timestamps are fixed-format UTC with exactly three digits.

    A value that is not in that form is a caller bug: substituting the current
    time would silently move an event on the phone's timeline.
    """
    parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


def _ed25519_verify(public_key: str, message: bytes, signature: str) -> bool:
    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError:
        logger.error("[open_android] cryptography is unavailable; request signatures cannot be verified")
        return False
    try:
        key = Ed25519PublicKey.from_public_bytes(_b64url_decode(public_key))
        key.verify(_b64url_decode(signature), message)
        return True
    except Exception:
        return False


def _decode_body(body: Any, headers: Mapping[str, str]) -> Any:
    """The body Core expects: parsed JSON for JSON requests, bytes otherwise."""
    if body is None:
        return None
    if isinstance(body, (bytes, bytearray, memoryview)):
        raw = bytes(body)
    else:
        return body
    if not raw:
        return None
    content_type = (headers.get("content-type") or "").split(";")[0].strip().lower()
    if content_type != "application/json":
        return raw
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None


@dataclass(frozen=True)
class VerifiedStreamingHeaders:
    """Authenticated request metadata waiting for exact streamed-body verification."""

    context: VerifiedRequestContext
    method: str
    target: str
    content_length: int
    body_sha256: str
    content_type: str | None
    idempotency_key: str | None
    last_event_id: str | None
    now: datetime | str | None

    def complete(self, streamed_body: Any) -> VerifiedGatewayRequest:
        if (
            getattr(streamed_body, "size_bytes", None) != self.content_length
            or getattr(streamed_body, "sha256", None) != self.body_sha256
        ):
            raise ValueError("ATTACHMENT_DIGEST_MISMATCH")
        return VerifiedGatewayRequest(
            context=self.context,
            method=self.method,
            target=self.target,
            body=streamed_body,
            idempotencyKey=self.idempotency_key,
            lastEventId=self.last_event_id,
            now=self.now,
        )


class GatewayRequestVerifier:
    """Turns a raw HTTP request into the typed verified-request seam.

    This is the host-side half of contract §6: the phone signs the canonical
    target and the digest of the exact body bytes with the device key it
    registered at login, so a proxy that rewrote either cannot produce a
    signature that still verifies. Every failure returns None rather than
    raising — an authentication seam must fail closed without telling the
    caller which of the checks it failed.
    """

    def __init__(self, core: Any, max_clock_skew_seconds: int = 120):
        self._core = core
        self._max_clock_skew_seconds = int(max_clock_skew_seconds)

    @property
    def maxClockSkewSeconds(self) -> int:
        return self._max_clock_skew_seconds

    def __call__(self, input: Mapping[str, Any]) -> VerifiedGatewayRequest | None:
        try:
            return self._verify(input)
        except Exception:
            return None

    def verify_streaming_headers(self, input: Mapping[str, Any]) -> "VerifiedStreamingHeaders | None":
        """Authenticate a raw attachment body from its signed Digest before reading it."""
        try:
            if input.get("method") != "PUT":
                return None
            target = input.get("target")
            if not isinstance(target, str) or re.fullmatch(
                r"/open-android-intelligence/v2/attachments/[^/]+/content", target,
            ) is None:
                return None
            headers = _singleton_headers(input)
            if headers is None or "content-encoding" in headers:
                return None
            raw_length = headers.get("content-length")
            if not isinstance(raw_length, str) or re.fullmatch(r"(?:0|[1-9][0-9]*)", raw_length) is None:
                return None
            content_length = int(raw_length)
            digest_header = headers.get("digest")
            if not isinstance(digest_header, str) or not digest_header.startswith("sha-256="):
                return None
            encoded_digest = digest_header[len("sha-256="):]
            digest = base64.b64decode(encoded_digest, validate=True)
            if len(digest) != 32 or base64.b64encode(digest).decode("ascii") != encoded_digest:
                return None
            body_sha256 = digest.hex()
            verified = self._verify(input, body_sha256=body_sha256, decode_body=False)
            if verified is None:
                return None
            return VerifiedStreamingHeaders(
                context=verified.context,
                method=verified.method,
                target=verified.target,
                content_length=content_length,
                body_sha256=body_sha256,
                content_type=headers.get("content-type"),
                idempotency_key=verified.idempotency_key,
                last_event_id=verified.last_event_id,
                now=verified.now,
            )
        except Exception:
            return None

    # Host-adapter naming alias.
    verify = __call__

    def _verify(
        self, input: Mapping[str, Any], *, body_sha256: str | None = None,
        decode_body: bool = True,
    ) -> VerifiedGatewayRequest | None:
        method = input.get("method")
        target = input.get("target")
        # PATCH carries the conversation title update (contract §7); leaving it out
        # of the signed method set made every rename fail closed with 401 while the
        # HTTP boundary above already accepted PATCH.
        if method not in {"GET", "POST", "PUT", "DELETE", "PATCH"} or not isinstance(target, str):
            return None
        headers = _singleton_headers(input)
        if headers is None:
            return None
        if headers.get("x-open-android-intelligence-protocol") != WIRE_PROTOCOL:
            return None
        access_token = self._bearer_token(headers)
        account_id = headers.get("x-open-android-intelligence-account")
        device_id = headers.get("x-open-android-intelligence-device")
        session_id = headers.get("x-open-android-intelligence-session")
        request_id = headers.get("x-open-android-intelligence-request-id")
        timestamp = headers.get("x-open-android-intelligence-timestamp")
        nonce = headers.get("x-open-android-intelligence-nonce")
        signature = headers.get("x-open-android-intelligence-signature")
        for value in (access_token, account_id, device_id, session_id, request_id, timestamp, nonce, signature):
            if not isinstance(value, str) or not value:
                return None
        for value in (account_id, device_id, session_id, request_id):
            if _WIRE_ID.fullmatch(value) is None:
                return None
        if _TIMESTAMP.fullmatch(timestamp) is None:
            return None
        if _BASE64URL.fullmatch(nonce) is None or len(_b64url_decode(nonce)) != 16:
            return None
        if _BASE64URL.fullmatch(signature) is None or len(_b64url_decode(signature)) != 64:
            return None

        signed_at = datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
        if abs((datetime.now(timezone.utc) - signed_at).total_seconds()) > self._max_clock_skew_seconds:
            return None

        session = self._resolve_session(account_id, device_id, session_id, access_token, timestamp)
        if session is None:
            return None

        canonical = canonicalize_target(target)
        if canonical != target:
            return None
        signed_fields = {
            "method": method, "target": canonical,
            "accountId": account_id, "deviceId": device_id, "sessionId": session_id,
            "requestId": request_id, "timestamp": timestamp, "nonce": nonce,
        }
        if body_sha256 is None:
            preimage = request_signature_preimage({**signed_fields, "bodyHex": _raw_body(input).hex()})
        else:
            preimage = request_signature_preimage_from_digest(signed_fields, body_sha256)
        if not _ed25519_verify(session["devicePublicKey"], preimage, signature):
            return None

        return VerifiedGatewayRequest(
            context=VerifiedRequestContext(
                accountId=account_id, deviceId=device_id, sessionId=session_id,
                requestId=request_id, correlationId=request_id,
                pairingGeneration=session["pairingGeneration"],
                grantRevision=session["grantRevision"],
                installationId=session["installationId"],
            ),
            method=method,
            target=canonical,
            body=_decode_body(input.get("body"), headers) if decode_body else None,
            idempotencyKey=headers.get("idempotency-key"),
            lastEventId=headers.get("last-event-id"),
            now=timestamp,
        )

    def _bearer_token(self, headers: Mapping[str, str]) -> str | None:
        authorization = headers.get("authorization")
        if not isinstance(authorization, str):
            return None
        scheme, separator, token = authorization.partition(" ")
        if not separator or scheme.strip().lower() != "bearer":
            return None
        return token.strip() or None

    def _resolve_session(
        self, account_id: str, device_id: str, session_id: str, access_token: str, timestamp: str,
    ) -> Mapping[str, Any] | None:
        exists = getattr(self._core, "account_exists", None)
        if callable(exists) and not exists(account_id):
            return None
        account = self._core.open_gateway_account(account_id)
        try:
            resolver = getattr(account.sessions, "resolve_session", None)
            if not callable(resolver):
                return None
            return resolver(access_token, session_id, device_id, timestamp)
        except Exception:
            return None
        finally:
            account.close()


def _raw_body(input: Mapping[str, Any]) -> bytes:
    body = input.get("body")
    if body is None:
        return b""
    if isinstance(body, (bytes, bytearray, memoryview)):
        return bytes(body)
    if isinstance(body, str):
        return body.encode("utf-8")
    return json.dumps(body, separators=(",", ":"), sort_keys=True).encode("utf-8")


def create_gateway_request_verifier(core: Any, max_clock_skew_seconds: int = 120) -> GatewayRequestVerifier:
    return GatewayRequestVerifier(core, max_clock_skew_seconds)
