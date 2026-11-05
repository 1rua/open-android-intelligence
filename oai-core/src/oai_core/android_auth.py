"""Optional account-auth extension; original closed Gateway schemas stay intact."""
from __future__ import annotations

import asyncio
import base64
from datetime import datetime, timezone
import hashlib
import json
import secrets
import threading
import time
import uuid
from typing import Any

from aiohttp import web
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from .gateway.core import GatewayError, _jcs, _success, iso_millis, canonical_device_key
from .gateway.account_paths import WIRE_ID_PATTERN

PREFIX = "/open-android-intelligence/v2/sessions"
ACCOUNT_AUTH_HEADER = "X-OAI-Account-Auth"


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class AndroidAccountAuthentication:
    def __init__(self, core: Any):
        self.core = core
        self.identity = core.identity_store
        self._tickets: dict[str, dict[str, Any]] = {}
        self._lock = threading.RLock()

    def _ticket(self, challenge_id: Any) -> dict[str, Any]:
        if not isinstance(challenge_id, str):
            raise GatewayError("AUTHENTICATION_FAILED")
        record = self._tickets.get(_digest(challenge_id))
        if record is None or record["used"] or time.monotonic() >= record["deadline"]:
            raise GatewayError("AUTHENTICATION_EXPIRED")
        self.identity.assert_epoch(record["facts"]["accountId"], record["epoch"])
        if self.identity._policy()[1] != record["policy"]:
            raise GatewayError("AUTHENTICATION_EXPIRED")
        return record

    def challenge(self, body: Any) -> dict:
        required = {"negotiationId", "username", "password", "installation"}
        if not isinstance(body, dict) or not required <= set(body) or set(body) - required - {"accountId", "deviceId"}:
            raise GatewayError("SCHEMA_INVALID")
        installation = body["installation"]
        if not isinstance(installation, dict) or set(installation) != {"installationId", "displayName", "devicePublicKey"}:
            raise GatewayError("SCHEMA_INVALID")
        if (not isinstance(installation["installationId"], str) or not WIRE_ID_PATTERN.fullmatch(installation["installationId"])
                or not isinstance(installation["displayName"], str) or not installation["displayName"]
                or not canonical_device_key(installation["devicePublicKey"])):
            raise GatewayError("SCHEMA_INVALID")
        client_id = installation["installationId"]
        proof = None
        try:
            authenticated = self.identity.authenticate(body["username"], body["password"], purpose="android-login", client_id=client_id)
            account_id, epoch = authenticated["accountId"], authenticated["authEpoch"]
            methods, enrollment = [], False
        except GatewayError as error:
            if error.code not in {"MFA_REQUIRED", "MFA_ENROLLMENT_REQUIRED"}:
                raise
            account_id = self.identity.resolve_username(body["username"])
            epoch = self.identity.account(account_id)["authEpoch"]
            proof = error.details["challengeId"]
            methods, enrollment = ["totp", "recovery-code"], error.code == "MFA_ENROLLMENT_REQUIRED"
        if ("accountId" in body) != ("deviceId" in body):
            raise GatewayError("SCHEMA_INVALID")
        existing_device = body.get("deviceId")
        if existing_device is not None:
            if body["accountId"] != account_id:
                raise GatewayError("PAIRING_REVOKED", {"reason": "ACCOUNT_ID_CHANGED"})
            account = self.core.open_gateway_account(account_id)
            try:
                row = account.store.database.execute("SELECT installation_id,public_key FROM device_keys WHERE device_id=?", (existing_device,)).fetchone()
                if row is None:
                    raise GatewayError("PAIRING_REVOKED")
                if tuple(row) != (client_id, installation["devicePublicKey"]):
                    raise GatewayError("DEVICE_ACCESS_DENIED")
            finally:
                account.close()
        self.core.bind_negotiation(body["negotiationId"], account_id, client_id)
        challenge_id = secrets.token_urlsafe(32)
        expires = time.time() + 300
        facts = {"challengeId": challenge_id, "accountId": account_id, "negotiationId": body["negotiationId"],
                 "installationId": client_id, **({"deviceId": existing_device} if existing_device else {}),
                 "purpose": "android-login", "expiresAt": datetime.fromtimestamp(expires, timezone.utc).isoformat(),
                 "nonce": secrets.token_urlsafe(32), "methods": methods, "enrollmentRequired": enrollment}
        with self._lock:
            for key in list(self._tickets):
                if self._tickets[key]["deadline"] <= time.monotonic():
                    self._tickets.pop(key)
            if len(self._tickets) >= 1000:
                raise GatewayError("RATE_LIMITED")
            self._tickets[_digest(challenge_id)] = {"facts": facts, "installation": installation,
                "deadline": time.monotonic() + 300, "epoch": epoch, "policy": self.identity._policy()[1],
                "proof": proof, "used": False, "enrollmentId": None}
        return facts

    def enroll(self, body: Any) -> dict:
        if not isinstance(body, dict) or set(body) != {"challengeId"}:
            raise GatewayError("SCHEMA_INVALID")
        with self._lock:
            ticket = self._ticket(body["challengeId"])
            if not ticket["facts"]["enrollmentRequired"] or ticket["enrollmentId"] is not None:
                raise GatewayError("AUTHENTICATION_FAILED")
            result = self.identity.begin_mfa_enrollment(ticket["facts"]["accountId"], proof=ticket["proof"])
            ticket["enrollmentId"] = result["enrollmentId"]
            return {"enrollmentId": ticket["enrollmentId"], "provisioningUri": result.get("uri", result.get("provisioningUri")),
                    **({"secret": result["secret"]} if result.get("secret") else {})}

    def confirm(self, body: Any) -> dict:
        if not isinstance(body, dict) or set(body) != {"challengeId", "enrollmentId", "otp"}:
            raise GatewayError("SCHEMA_INVALID")
        with self._lock:
            ticket = self._ticket(body["challengeId"])
            if ticket["enrollmentId"] != body["enrollmentId"] or ticket["enrollmentId"] is None:
                raise GatewayError("AUTHENTICATION_FAILED")
            result = self.identity.confirm_mfa_enrollment(ticket["facts"]["accountId"], ticket["proof"], body["otp"], enrollment_id=body["enrollmentId"])
            ticket["epoch"] = self.identity.account(ticket["facts"]["accountId"])["authEpoch"]
            return {"recoveryCodes": result["recoveryCodes"]}

    def complete(self, body: Any) -> dict:
        required = {"challengeId", "negotiationId", "signature"}
        if not isinstance(body, dict) or not required <= set(body) or set(body) - required - {"otp", "recoveryCode"}:
            raise GatewayError("SCHEMA_INVALID")
        with self._lock:
            ticket = self._ticket(body["challengeId"])
            facts, installation = ticket["facts"], ticket["installation"]
            if facts["negotiationId"] != body["negotiationId"]:
                raise GatewayError("AUTHENTICATION_FAILED")
            try:
                raw = base64.b64decode(body["signature"] + "==", altchars=b"-_", validate=True)
                key = base64.urlsafe_b64decode(installation["devicePublicKey"] + "=")
                Ed25519PublicKey.from_public_bytes(key).verify(raw, b"OPEN_ANDROID_INTELLIGENCE_ACCOUNT_LOGIN_V1\n" + _jcs(facts).encode())
            except Exception as error:
                raise GatewayError("AUTHENTICATION_FAILED") from error
            account_id = facts["accountId"]
            # Factor failures must commit their attempt counter. Do not wrap
            # complete_authentication in an outer transaction that rolls it back.
            if ticket["proof"] is not None:
                authenticated = self.identity.complete_authentication(ticket["proof"], otp=body.get("otp"),
                    recovery_code=body.get("recoveryCode"), purpose="android-login", client_id=facts["installationId"])
                self.identity.assert_epoch(account_id, authenticated["authEpoch"])
            with self.identity.transaction():
                self.identity.assert_epoch(account_id, ticket["epoch"])
                if self.identity._policy()[1] != ticket["policy"]:
                    raise GatewayError("AUTHENTICATION_EXPIRED")
                account = self.core.open_gateway_account(account_id)
                try:
                    with account.store.transaction():
                        device_id = facts.get("deviceId") or "dev_" + str(uuid.uuid4())
                        if facts.get("deviceId"):
                            row = account.store.database.execute("SELECT installation_id,public_key FROM device_keys WHERE device_id=?", (device_id,)).fetchone()
                            if row is None or tuple(row) != (facts["installationId"], installation["devicePublicKey"]):
                                raise GatewayError("DEVICE_ACCESS_DENIED")
                        account.sessions.register_device_key(device_id, facts["installationId"], installation["devicePublicKey"])
                        account.store.database.execute("INSERT OR REPLACE INTO account_metadata(key,value) VALUES (?,?)",
                            ("core-device-auth-epoch:" + device_id, str(ticket["epoch"])))
                        now = iso_millis(None)
                        account.store.database.execute("INSERT OR REPLACE INTO account_metadata(key,value) VALUES (?,?)",
                            ("core-device-info:" + device_id, _jcs({"displayName": installation["displayName"], "lastSeenAt": now})))
                        session = account.sessions._issue(facts["installationId"], device_id, datetime.now(timezone.utc), auth_epoch=ticket["epoch"])
                        account.audit.append("session.account.created", {"accountId": account_id, "deviceId": device_id}, {"method": "account-auth-v1"}, body["challengeId"])
                    ticket["used"] = True
                    return {**session, "accountId": account_id}
                finally:
                    account.close()

    def routes(self) -> web.RouteTableDef:
        routes = web.RouteTableDef()
        for path, action in (("/password/challenge", self.challenge), ("/password/complete", self.complete),
                             ("/mfa/enroll", self.enroll), ("/mfa/confirm", self.confirm)):
            async def handler(request, action=action):
                # Hashing and SQLite are bounded by the listener's login admission.
                self.core._admit_password({"remoteAddress": request.remote}, "android-account", datetime.now(timezone.utc))
                try:
                    body = await request.json()
                    result = await asyncio.to_thread(action, body)
                    context = {"requestId": "req_" + uuid.uuid4().hex, "correlationId": "auth_" + uuid.uuid4().hex}
                    return web.json_response(_success(context, result), headers={"Cache-Control": "no-store"})
                finally:
                    with self.core._admission_lock:
                        self.core._password_jobs -= 1
            routes.post(PREFIX + path)(handler)
        return routes
