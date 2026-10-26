"""Authenticated principals. Device identity is selected only at authentication."""
from __future__ import annotations

import asyncio
import hashlib
import secrets
import sqlite3
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from aiohttp import web
from open_android_intelligence_gateway.adapter import GatewayRequestVerifier
from open_android_intelligence_gateway.core import GatewayError, VerifiedRequestContext, _now, _request_context


@dataclass(frozen=True)
class Principal:
    account_id: str
    context: VerifiedRequestContext | None = None
    device_id: str | None = None
    pairing_generation: int | None = None
    grant_revision: int | None = None
    token_id: str | None = None


def raw_request(request: web.Request, body: bytes = b"") -> dict[str, Any]:
    return {"method": request.method, "url": request.raw_path, "target": request.raw_path,
            "headers": dict(request.headers), "rawHeaders": [(k.decode("ascii"), v.decode("latin-1")) for k, v in request.raw_headers],
            "body": body, "remoteAddress": request.remote}


class ReplayProtectedVerifier(GatewayRequestVerifier):
    """Apply the Core deployment nonce ledger around the pinned signature seam."""

    def __init__(self, core: Any, authentication: "Authentication"):
        super().__init__(core)
        self.authentication = authentication

    def __call__(self, raw: Any) -> Any:
        verified = super().__call__(raw)
        if verified is None or not self._core.is_event_session_active(verified.context):
            return None
        return verified if self.authentication.consume_nonce(raw, verified.context) else None

    def verify_streaming_headers(self, raw: Any) -> Any:
        ticket = super().verify_streaming_headers(raw)
        if ticket is None or not self._core.is_event_session_active(ticket.context):
            return None
        return ticket if self.authentication.consume_nonce(raw, ticket.context) else None


class Authentication:
    """Signed phone requests and revocable, account/device-bound CLI tokens."""

    MAX_PASSWORD_CHECKS = 4

    def __init__(self, core: Any, database: Path, token_lifetime: int = 86400):
        self.core = core
        self.verifier = ReplayProtectedVerifier(core, self)
        self.token_lifetime = token_lifetime
        database.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.database = sqlite3.connect(database)
        database.chmod(0o600)
        self.database.row_factory = sqlite3.Row
        self.database.execute("CREATE TABLE IF NOT EXISTS core_tokens (digest TEXT PRIMARY KEY, account_id TEXT NOT NULL, device_id TEXT NOT NULL, generation INTEGER NOT NULL, revision INTEGER NOT NULL, expires REAL NOT NULL)")
        self.database.execute("CREATE TABLE IF NOT EXISTS signed_nonces (digest TEXT PRIMARY KEY, expires REAL NOT NULL)")
        self.database.commit()
        self._attempts: dict[tuple[str, str], tuple[float, int]] = {}
        self._password_checks: set[asyncio.Task[str]] = set()

    def consume_nonce(self, raw: Any, context: VerifiedRequestContext) -> bool:
        # The signature verifier already rejected duplicate singleton headers.
        headers = {str(k).lower(): str(v) for k, v in (raw.get("headers") or {}).items()}
        nonce = headers.get("x-open-android-intelligence-nonce")
        if not nonce:
            return False
        digest = hashlib.sha256(f"{context.account_id}:{context.device_id}:{context.session_id}:{nonce}".encode()).hexdigest()
        now = time.time()
        try:
            with self.database:
                self.database.execute("DELETE FROM signed_nonces WHERE expires<=?", (now,))
                self.database.execute("INSERT INTO signed_nonces VALUES (?,?)", (digest, now + 2 * self.verifier.maxClockSkewSeconds + 1))
            return True
        except sqlite3.IntegrityError:
            return False

    def close(self) -> None:
        self.database.close()

    def _prepare_login(self, body: Any, remote: str) -> tuple[str, str, str]:
        if not isinstance(body, dict) or set(body) != {"username", "password", "deviceId"} or any(not isinstance(v, str) or not v for v in body.values()):
            raise GatewayError("SCHEMA_INVALID")
        username, password, device_id = body["username"], body["password"], body["deviceId"]
        key = (remote, username)
        now = time.time()
        started, attempts = self._attempts.get(key, (now, 0))
        if now - started > 60:
            started, attempts = now, 0
        if attempts >= 10:
            raise GatewayError("RATE_LIMITED")
        self._attempts[key] = (started, attempts + 1)
        # Bound memory even when an unauthenticated peer keeps changing usernames.
        if len(self._attempts) > 4096:
            self._attempts = {k: v for k, v in self._attempts.items() if now - v[0] < 60}
            if len(self._attempts) > 4096:
                raise GatewayError("RATE_LIMITED")
        if not self.core.account_exists(username):
            raise GatewayError("AUTHENTICATION_FAILED")
        return username, password, device_id

    def _verify_login(self, username: str, password: str) -> str:
        # Each worker owns and closes its account connection. The token database
        # and admission/rate-limit state belong to the listener's thread.
        if not self.core.account_exists(username):
            raise GatewayError("AUTHENTICATION_FAILED")
        account = self.core.open_gateway_account(username)
        try:
            digest = account.credentials.password_digest()
            if digest is None or not account.credentials.verify_password(password):
                raise GatewayError("AUTHENTICATION_FAILED")
            return digest
        finally:
            account.close()

    def _issue_login(self, username: str, device_id: str, credential_digest: str, remote: str) -> dict[str, Any]:
        if not self.core.account_exists(username):
            raise GatewayError("AUTHENTICATION_FAILED")
        account = self.core.open_gateway_account(username)
        try:
            # Recheck credentials and pairing after the worker completes. Hold
            # the account write lock so a password reset or pairing revocation
            # cannot race the token's binding to the current account/device.
            with account.store.transaction():
                if account.credentials.password_digest() != credential_digest:
                    raise GatewayError("AUTHENTICATION_FAILED")
                row = account.store.database.execute("SELECT pairing_generation, grant_revision FROM device_keys WHERE device_id=?", (device_id,)).fetchone()
                if row is None:
                    raise GatewayError("PAIRING_REQUIRED")
                token = secrets.token_urlsafe(32)
                digest = hashlib.sha256(token.encode()).hexdigest()
                now = time.time()
                expires = now + self.token_lifetime
                with self.database:
                    self.database.execute("DELETE FROM core_tokens WHERE expires <= ?", (now,))
                    self.database.execute("INSERT INTO core_tokens VALUES (?,?,?,?,?,?)", (digest, username, device_id, int(row[0]), int(row[1]), expires))
                self._attempts.pop((remote, username), None)
                return {"token": token, "expiresAt": datetime.fromtimestamp(expires, timezone.utc).isoformat()}
        finally:
            account.close()

    def login(self, body: Any, remote: str = "") -> dict[str, Any]:
        username, password, device_id = self._prepare_login(body, remote)
        return self._issue_login(username, device_id, self._verify_login(username, password), remote)

    async def async_login(self, body: Any, remote: str = "") -> dict[str, Any]:
        username, password, device_id = self._prepare_login(body, remote)
        # Reject excess work instead of accumulating an unbounded worker queue.
        if len(self._password_checks) >= self.MAX_PASSWORD_CHECKS:
            raise GatewayError("RATE_LIMITED")
        check = asyncio.create_task(asyncio.to_thread(self._verify_login, username, password))
        self._password_checks.add(check)

        def finished(task: asyncio.Task[str]) -> None:
            self._password_checks.discard(task)
            if not task.cancelled():
                task.exception()

        check.add_done_callback(finished)
        # A disconnected/cancelled caller still occupies its slot until its
        # password worker exits; cancellation cannot bypass the admission cap.
        credential_digest = await asyncio.shield(check)
        return self._issue_login(username, device_id, credential_digest, remote)

    async def authenticate(self, request: web.Request) -> Principal:
        if "x-open-android-intelligence-signature" in request.headers:
            verified = self.verifier(raw_request(request, await request.read()))
            if verified is None or not self.core.is_event_session_active(verified.context):
                raise GatewayError("AUTHENTICATION_REQUIRED")
            account = self.core.open_gateway_account(verified.context.account_id)
            try:
                self.core._assert_negotiation_bound(account, _request_context(verified), _now())
            finally:
                account.close()
            ctx = verified.context
            return Principal(ctx.account_id, ctx, ctx.device_id, ctx.pairing_generation, ctx.grant_revision)
        authorization = request.headers.getall("Authorization", [])
        if len(authorization) != 1 or not authorization[0].startswith("Bearer "):
            raise GatewayError("AUTHENTICATION_REQUIRED")
        token = authorization[0][7:]
        digest = hashlib.sha256(token.encode()).hexdigest()
        row = self.database.execute("SELECT * FROM core_tokens WHERE digest=? AND expires>?", (digest, time.time())).fetchone()
        if row is None or not self.core.account_exists(row["account_id"]):
            raise GatewayError("AUTHENTICATION_REQUIRED")
        principal = Principal(row["account_id"], device_id=row["device_id"], pairing_generation=row["generation"], grant_revision=row["revision"], token_id=digest)
        self.assert_current(principal)
        return principal

    def assert_current(self, principal: Principal) -> None:
        if not self.core.account_exists(principal.account_id):
            raise GatewayError("AUTHENTICATION_REQUIRED")
        account = self.core.open_gateway_account(principal.account_id)
        try:
            row = account.store.database.execute("SELECT pairing_generation,grant_revision FROM device_keys WHERE device_id=?", (principal.device_id,)).fetchone()
            if row is None or tuple(row) != (principal.pairing_generation, principal.grant_revision):
                raise GatewayError("GRANT_STALE")
        finally:
            account.close()
        if principal.context is not None and not self.core.is_event_session_active(principal.context):
            raise GatewayError("SESSION_REVOKED")
        if principal.token_id is not None:
            row = self.database.execute("SELECT 1 FROM core_tokens WHERE digest=? AND expires>?", (principal.token_id, time.time())).fetchone()
            if row is None:
                raise GatewayError("AUTHENTICATION_REQUIRED")

    def revoke(self, principal: Principal) -> None:
        if principal.token_id is not None:
            self.database.execute("DELETE FROM core_tokens WHERE digest=?", (principal.token_id,))
            self.database.commit()
