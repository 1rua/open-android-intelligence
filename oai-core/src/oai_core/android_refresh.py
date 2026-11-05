"""Bounded replay of one Android refresh operation after response loss."""
from __future__ import annotations

from datetime import timedelta
import hashlib
import json
import re
from typing import Any

from .gateway.core import GatewayError, _failure, _jcs, _now, _request_body, _success, iso_millis


def refresh_operation(core: Any, request: Any) -> dict:
    body = _request_body(request)
    context = core._pre_auth_context(request, body if isinstance(body, dict) else {})
    headers = {str(key).lower(): value for key, value in (request.get("headers") or {}).items()}
    operation = headers.get("idempotency-key")
    if not isinstance(operation, str) or re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", operation) is None:
        return _failure(context, "IDEMPOTENCY_KEY_REQUIRED")
    account = None
    try:
        if not isinstance(body, dict) or not core.contracts.validate("session.refresh", body):
            raise GatewayError("SCHEMA_INVALID")
        account_id, device_id = body["accountId"], body["deviceId"]
        if not core.account_exists(account_id):
            raise GatewayError("AUTHENTICATION_FAILED")
        core.bind_negotiation(body["negotiationId"], account_id, body["installationId"])
        account = core.open_gateway_account(account_id)
        old_digest = account.sessions._digest(body["refreshCredential"])
        key = "core-android-refresh:v1:" + hashlib.sha256(_jcs([old_digest, operation]).encode()).hexdigest()
        # Negotiation is transport-local and may change on client restart. The
        # credential, account, installation and device remain the stable scope.
        scope = _jcs([account_id, body["installationId"], device_id, old_digest, operation])
        reused = None
        with account.store.transaction():
            refresh = account.store.database.execute("SELECT * FROM refresh_credentials WHERE credential_hash=?", (old_digest,)).fetchone()
            if refresh is None or refresh["installation_id"] != body["installationId"] or refresh["device_id"] != device_id:
                raise GatewayError("AUTHENTICATION_FAILED")
            row = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (key,)).fetchone()
            if row is not None:
                receipt = account.store.open_json(row[0], key)
                if receipt.get("scope") != scope or _now(receipt["expiresAt"]) <= _now():
                    raise GatewayError("AUTHENTICATION_REQUIRED")
                bundle = receipt["bundle"]
                successor = account.store.database.execute("SELECT status FROM refresh_credentials WHERE credential_hash=?", (account.sessions._digest(bundle["refreshCredential"]),)).fetchone()
                if successor is None or successor[0] != "active":
                    raise GatewayError("AUTHENTICATION_REQUIRED")
                if core.identity_store is not None:
                    epoch = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", ("core-session-epoch:" + bundle["sessionId"],)).fetchone()
                    if epoch is None:
                        raise GatewayError("SESSION_REVOKED")
                    core.identity_store.assert_epoch(account_id, int(epoch[0]))
                device = account.store.database.execute("SELECT pairing_generation FROM device_keys WHERE device_id=?", (device_id,)).fetchone()
                if device is None or device[0] != bundle["pairingGeneration"]:
                    raise GatewayError("PAIRING_GENERATION_STALE")
                return _success(context, bundle)
            try:
                bundle = account.sessions.refresh(body["refreshCredential"], body["installationId"], device_id, context["correlationId"])
            except GatewayError as error:
                if error.code != "REFRESH_REUSED":
                    raise
                reused = error
            if reused is None:
                bundle = {**bundle, "accountId": account_id}
                receipt = {"scope": scope, "expiresAt": iso_millis(_now() + timedelta(minutes=15)), "bundle": bundle}
                account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?,?)", (key, account.store.seal_json(receipt, key)))
        if reused is not None:
            raise reused
        return _success(context, bundle)
    except GatewayError as error:
        return _failure(context, error.code, error.details)
    finally:
        if account is not None:
            account.close()
