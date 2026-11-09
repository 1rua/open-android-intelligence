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
from .account_sessions import AccountSessions
from .gateway.verifier import GatewayRequestVerifier
from .gateway.core import GatewayError, VerifiedRequestContext, authentication_storage, _now, _request_context


@dataclass(frozen=True)
class Principal:
    account_id: str
    context: VerifiedRequestContext | None = None
    device_id: str | None = None
    pairing_generation: int | None = None
    grant_revision: int | None = None
    token_id: str | None = None
    login_session_id: str | None = None
    client_id: str | None = None
    auth_epoch: int | None = None


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
        self.database.execute("BEGIN IMMEDIATE")
        try:
            columns = {row[1] for row in self.database.execute("PRAGMA table_info(core_tokens)")}
            for name, kind in (("auth_epoch", "INTEGER"), ("credential_digest", "TEXT")):
                if name not in columns:
                    self.database.execute(f"ALTER TABLE core_tokens ADD COLUMN {name} {kind}")
            self.database.commit()
        except BaseException:
            self.database.rollback()
            raise
        self.account_sessions = AccountSessions(core, self.database)
        self._attempts: dict[tuple[str, str], tuple[float, int]] = {}
        self._password_checks: set[asyncio.Task[Any]] = set()

    def consume_nonce(self, raw: Any, context: VerifiedRequestContext) -> bool:
        # The signature verifier already rejected duplicate singleton headers.
        headers = {str(k).lower(): str(v) for k, v in (raw.get("headers") or {}).items()}
        nonce = headers.get("x-open-android-intelligence-nonce")
        if not nonce:
            return False
        digest = hashlib.sha256(f"{context.account_id}:{context.device_id}:{context.session_id}:{nonce}".encode()).hexdigest()
        now = time.time()
        with authentication_storage("nonce-write", context.request_id):
            try:
                with self.database:
                    self.database.execute("DELETE FROM signed_nonces WHERE expires<=?", (now,))
                    self.database.execute("INSERT INTO signed_nonces VALUES (?,?)", (digest, now + 2 * self.verifier.maxClockSkewSeconds + 1))
            except sqlite3.IntegrityError:
                return False
        return True

    def close(self) -> None:
        self.database.close()

    def _prepare_login(self, body: Any, remote: str) -> dict[str, str]:
        allowed = {"username", "password", "deviceId", "clientId", "clientProof", "challengeId", "otp", "recoveryCode"}
        if (not isinstance(body, dict) or not {"username", "password"} <= set(body) or set(body) - allowed
                or any(not isinstance(v, str) or not v or len(v) > 1024 for v in body.values())
                or any(len(body[key]) > 256 or any(ord(c) < 32 for c in body[key])
                       for key in ("clientId", "clientProof") if key in body)
                or "clientProof" in body and "clientId" not in body
                or "otp" in body and "recoveryCode" in body):
            raise GatewayError("SCHEMA_INVALID")
        username = body["username"]
        if getattr(self.core, "identity_store", None) is None:
            if set(body) != {"username", "password", "deviceId"}:
                raise GatewayError("IDENTITY_DATABASE_MISSING")
        elif "clientId" not in body:
            body = {**body, "clientId": "client_" + secrets.token_urlsafe(24)}
        if ("deviceId" in body and getattr(self.core, "identity_store", None) is not None
                and self.core.identity_store._policy()[0]):
            # The identity challenge currently binds a client, not a device.
            # Refuse legacy login until it can express that binding safely.
            raise GatewayError("ACCOUNT_LOGIN_UPGRADE_REQUIRED")
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
        return body

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

    def _verify_identity_login(self, body: dict[str, str]) -> dict[str, Any]:
        identity = self.core.identity_store
        try:
            return identity.authenticate(body["username"], body["password"], otp=body.get("otp"),
                recovery_code=body.get("recoveryCode"), purpose="login", client_id=body["clientId"],
                challenge_id=body.get("challengeId"))
        except GatewayError as exc:
            if exc.code in {"MFA_REQUIRED", "MFA_ENROLLMENT_REQUIRED"}:
                raise GatewayError(exc.code, {**exc.details, "clientId": body["clientId"]}) from exc
            raise

    def _issue_identity_login(self, body: dict[str, str], result: dict[str, Any], remote: str) -> dict[str, Any]:
        if "deviceId" in body:
            identity = self.core.identity_store
            # Match the existing device-write ordering: account DB before
            # identity DB. A concurrent security change invalidates the token
            # through its epoch even if it commits just after issuance.
            identity.assert_epoch(result["accountId"], result["authEpoch"])
            credential_digest = identity.password_digest(result["accountId"])
            bundle = self._issue_login(result["accountId"], body["deviceId"], credential_digest, remote,
                                       auth_epoch=result["authEpoch"])
        else:
            bundle = self.account_sessions.issue(result, body["clientId"], body.get("clientProof"))
        self._attempts.pop((remote, body["username"]), None)
        return bundle

    def _issue_login(self, username: str, device_id: str, credential_digest: str, remote: str, *, auth_epoch: int | None = None) -> dict[str, Any]:
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
                if auth_epoch is not None:
                    self.core.identity_store.assert_epoch(username, auth_epoch)
                row = account.store.database.execute("SELECT pairing_generation, grant_revision FROM device_keys WHERE device_id=?", (device_id,)).fetchone()
                if row is None:
                    raise GatewayError("PAIRING_REQUIRED")
                token = secrets.token_urlsafe(32)
                digest = hashlib.sha256(token.encode()).hexdigest()
                now = time.time()
                expires = now + self.token_lifetime
                with self.database:
                    self.database.execute("DELETE FROM core_tokens WHERE expires <= ?", (now,))
                    self.database.execute("INSERT INTO core_tokens (digest,account_id,device_id,generation,revision,expires,auth_epoch,credential_digest) VALUES (?,?,?,?,?,?,?,?)", (digest, username, device_id, int(row[0]), int(row[1]), expires, auth_epoch, credential_digest))
                self._attempts.pop((remote, username), None)
                return {"token": token, "expiresAt": datetime.fromtimestamp(expires, timezone.utc).isoformat()}
        finally:
            account.close()

    def login(self, body: Any, remote: str = "") -> dict[str, Any]:
        body = self._prepare_login(body, remote)
        if getattr(self.core, "identity_store", None) is not None:
            return self._issue_identity_login(body, self._verify_identity_login(body), remote)
        return self._issue_login(body["username"], body["deviceId"], self._verify_login(body["username"], body["password"]), remote)

    async def async_login(self, body: Any, remote: str = "") -> dict[str, Any]:
        body = self._prepare_login(body, remote)
        if len(self._password_checks) >= self.MAX_PASSWORD_CHECKS:
            raise GatewayError("RATE_LIMITED")
        modern = getattr(self.core, "identity_store", None) is not None
        if modern:
            work = asyncio.to_thread(self._verify_identity_login, body)
        else:
            work = asyncio.to_thread(self._verify_login, body["username"], body["password"])
        check = asyncio.create_task(work)
        self._password_checks.add(check)

        def finished(task: asyncio.Task[Any]) -> None:
            self._password_checks.discard(task)
            if not task.cancelled():
                task.exception()

        check.add_done_callback(finished)
        # A disconnected caller occupies its slot until its worker really exits.
        verified = await asyncio.shield(check)
        if modern:
            return self._issue_identity_login(body, verified, remote)
        return self._issue_login(body["username"], body["deviceId"], verified, remote)

    def complete_login(self, body: Any, remote: str = "") -> dict[str, Any]:
        # Identity challenges bind the client but not a device; an unbound
        # device must never be supplied at completion.
        allowed = {"challengeId", "clientId", "clientProof", "otp", "recoveryCode"}
        if (not isinstance(body, dict) or not {"challengeId", "clientId"} <= set(body) or set(body) - allowed
                or any(not isinstance(v, str) or not v or len(v) > 1024 for v in body.values())
                or any(len(body[key]) > 256 or any(ord(c) < 32 for c in body[key])
                       for key in ("clientId", "clientProof") if key in body)
                or "otp" in body and "recoveryCode" in body):
            raise GatewayError("SCHEMA_INVALID")
        identity = getattr(self.core, "identity_store", None)
        if identity is None:
            raise GatewayError("IDENTITY_DATABASE_MISSING")
        result = identity.complete_authentication(body["challengeId"], otp=body.get("otp"),
            recovery_code=body.get("recoveryCode"), purpose="login", client_id=body["clientId"])
        return self._issue_identity_login({**body, "username": result["username"]}, result, remote)

    def refresh(self, body: Any) -> dict[str, Any]:
        return self.account_sessions.refresh(body)

    def revoke_refresh(self, body: Any) -> None:
        self.account_sessions.revoke_refresh(body)

    def assert_login_session(self, account_id: str, login_session_id: str) -> None:
        self.account_sessions.assert_family(account_id, login_session_id)

    def principal_for_login_session(self, account_id: str, login_session_id: str) -> Principal:
        family = self.account_sessions.assert_family(account_id, login_session_id)
        return Principal(account_id, login_session_id=login_session_id,
                         client_id=family["client_id"], auth_epoch=family["auth_epoch"])

    async def authenticate(self, request: web.Request) -> Principal:
        body = await request.read() if "x-open-android-intelligence-signature" in request.headers else b""
        with authentication_storage("principal-read", request.headers.get("X-Open-Android-Intelligence-Request-Id")):
            return self._authenticate(request, body)

    def _authenticate(self, request: web.Request, body: bytes) -> Principal:
        if "x-open-android-intelligence-signature" in request.headers:
            verified = self.verifier(raw_request(request, body))
            if verified is None or not self.core.is_event_session_active(verified.context):
                raise GatewayError("AUTHENTICATION_REQUIRED")
            with authentication_storage("account-open", verified.context.request_id):
                account = self.core.open_gateway_account(verified.context.account_id)
            try:
                with authentication_storage("negotiation-read", verified.context.request_id):
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
        family = self.account_sessions.access(digest)
        if family is not None:
            return Principal(family["account_id"], token_id=digest, login_session_id=family["family_id"],
                             client_id=family["client_id"], auth_epoch=family["auth_epoch"])
        row = self.database.execute("SELECT * FROM core_tokens WHERE digest=? AND expires>?", (digest, time.time())).fetchone()
        if row is None or not self.core.account_exists(row["account_id"]):
            raise GatewayError("AUTHENTICATION_REQUIRED")
        principal = Principal(row["account_id"], device_id=row["device_id"], pairing_generation=row["generation"], grant_revision=row["revision"], token_id=digest)
        self.assert_current(principal)
        # A token identifies a legal access relationship, not an Android
        # capability publication. Bind each new invocation to today's snapshot.
        account = self.core.open_gateway_account(principal.account_id)
        try:
            binding = account.store.database.execute("SELECT pairing_generation,grant_revision FROM device_keys WHERE device_id=?", (principal.device_id,)).fetchone()
            if binding is None or binding[0] != principal.pairing_generation:
                raise GatewayError("PAIRING_GENERATION_STALE")
            return Principal(principal.account_id, device_id=principal.device_id,
                pairing_generation=principal.pairing_generation, grant_revision=binding[1], token_id=digest)
        finally:
            account.close()

    def assert_current(self, principal: Principal) -> None:
        if principal.login_session_id is not None:
            if principal.token_id is None:
                row = self.account_sessions.assert_family(principal.account_id, principal.login_session_id)
            else:
                row = self.account_sessions.access(principal.token_id)
            if (row is None or row["account_id"] != principal.account_id
                    or row["family_id"] != principal.login_session_id
                    or row["client_id"] != principal.client_id or row["auth_epoch"] != principal.auth_epoch):
                raise GatewayError("AUTHENTICATION_REQUIRED")
            return
        if not self.core.account_exists(principal.account_id):
            raise GatewayError("AUTHENTICATION_REQUIRED")
        account = self.core.open_gateway_account(principal.account_id)
        try:
            if principal.token_id is not None:
                token = self.database.execute("SELECT * FROM core_tokens WHERE digest=? AND expires>?", (principal.token_id, time.time())).fetchone()
                if token is None:
                    raise GatewayError("AUTHENTICATION_REQUIRED")
                identity = getattr(self.core, "identity_store", None)
                if identity is not None:
                    # Pre-migration tokens without an epoch cannot be proven safe.
                    if token["auth_epoch"] is None:
                        raise GatewayError("AUTHENTICATION_REQUIRED")
                    identity.assert_epoch(principal.account_id, token["auth_epoch"])
                elif token["credential_digest"] is None or not secrets.compare_digest(
                        token["credential_digest"], account.credentials.password_digest() or ""):
                    raise GatewayError("AUTHENTICATION_REQUIRED")
            row = account.store.database.execute("SELECT pairing_generation,grant_revision FROM device_keys WHERE device_id=?", (principal.device_id,)).fetchone()
            if row is None or row[0] != principal.pairing_generation:
                raise GatewayError("PAIRING_GENERATION_STALE")
            if principal.token_id is None and row[1] != principal.grant_revision:
                raise GatewayError("GRANT_STALE")
        finally:
            account.close()
        if principal.context is not None and not self.core.is_event_session_active(principal.context):
            raise GatewayError("SESSION_REVOKED")
        if principal.token_id is not None:
            row = self.database.execute("SELECT account_id,device_id,generation FROM core_tokens WHERE digest=? AND expires>?", (principal.token_id, time.time())).fetchone()
            if row is None or tuple(row) != (principal.account_id, principal.device_id, principal.pairing_generation):
                raise GatewayError("AUTHENTICATION_REQUIRED")

    def revoke(self, principal: Principal) -> None:
        if principal.login_session_id is not None:
            self.assert_current(principal)
            self.account_sessions.revoke_family(principal.login_session_id)
        elif principal.token_id is not None:
            self.database.execute("DELETE FROM core_tokens WHERE digest=?", (principal.token_id,))
            self.database.commit()
