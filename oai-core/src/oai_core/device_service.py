"""One device entrypoint; all transitions belong to the upstream Core store."""
from __future__ import annotations

import hashlib
import json
import re
import uuid
from contextlib import contextmanager
from typing import Any, Iterator

from .gateway.capability_policy import capability_catalog
from .gateway.core import GatewayError, _jcs, _now

from .auth import Authentication, Principal
from .declarations import caller_declaration
from .routing import DeviceRouter, TargetBinding


class DeviceService:
    def __init__(self, core: Any, authentication: Authentication):
        self.core = core
        self.authentication = authentication
        self.router = DeviceRouter(core)

    @contextmanager
    def account(self, principal: Principal) -> Iterator[Any]:
        self.authentication.assert_current(principal)
        account = self.core.open_gateway_account(principal.account_id)
        try:
            account.store.require_aead()
            yield account
        finally:
            account.close()

    def devices(self, principal: Principal) -> dict[str, Any]:
        with self.account(principal) as account:
            return {"devices": self.router.devices(account), "defaultDeviceId": self.router.default_device(account)}

    def set_default(self, principal: Principal, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict) or set(body) != {"deviceId"}:
            raise GatewayError("SCHEMA_INVALID")
        with self.account(principal) as account, account.store.transaction():
            return self.router.set_default(account, body["deviceId"])

    def capabilities(self, principal: Principal, device_id: str | None = None) -> dict[str, Any]:
        with self.account(principal) as account:
            target = self.router.resolve(account, principal, device_id=device_id)
            return {"selectedDevice": target.descriptor(), "capabilities": capability_catalog(account, target.device_id)}

    def _request_target(self, account: Any, principal: Principal, request_id: str) -> TargetBinding:
        row = account.device_requests._row(request_id)
        target = self.router.binding(account, row["device_id"], "accepted-request")
        if principal.device_id is not None and principal.context is None and principal.login_session_id is None:
            if principal.device_id != target.device_id or principal.pairing_generation != target.pairing_generation:
                raise GatewayError("PAIRING_GENERATION_STALE")
        if principal.context is not None and principal.context.device_id != target.device_id:
            raise GatewayError("DEVICE_ACCESS_DENIED")
        account.device_requests._assert_binding(row, target.device_id, target.pairing_generation, target.grant_revision)
        owner = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?",
            ("core-device-owner:" + request_id,)).fetchone()
        if owner is not None:
            scope = json.loads(owner[0])
            if scope.get("clientId") and scope["clientId"] != principal.client_id:
                raise GatewayError("DEVICE_REQUEST_ACCESS_DENIED")
        return target

    @staticmethod
    def _publication_revision(account: Any, principal: Principal) -> int:
        if principal.token_id is None:
            return principal.grant_revision
        row = account.store.database.execute("SELECT pairing_generation,grant_revision FROM device_keys WHERE device_id=?", (principal.device_id,)).fetchone()
        if row is None or row[0] != principal.pairing_generation:
            raise GatewayError("PAIRING_GENERATION_STALE")
        return int(row[1])

    def submit(self, principal: Principal, body: Any, idempotency_key: str | None) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise GatewayError("SCHEMA_INVALID")
        caller = caller_declaration(body.get("caller"))
        required = {"capabilityId", "capabilityVersion", "parameters", "caller"}
        if not required <= set(body) or set(body) - required - {"deviceId", "originContext"} or not isinstance(body["parameters"], dict):
            raise GatewayError("SCHEMA_INVALID")
        if not isinstance(body["capabilityId"], str) or not isinstance(body["capabilityVersion"], str):
            raise GatewayError("SCHEMA_INVALID")
        if not isinstance(idempotency_key, str) or re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", idempotency_key) is None:
            raise GatewayError("IDEMPOTENCY_KEY_REQUIRED")
        if principal.context is not None and idempotency_key != principal.context.request_id:
            raise GatewayError("IDEMPOTENCY_KEY_REQUIRED")
        digest = hashlib.sha256(_jcs(body).encode()).hexdigest()
        # This ledger is metadata only. Native DeviceRequestStore owns queue,
        # claims, expiry, cancellation, results and transient payload retention.
        if principal.context is not None:
            scope = "phone:" + principal.context.device_id
        elif principal.client_id:
            scope = "client:" + principal.client_id
        else:
            scope = "legacy:" + str(principal.device_id) + ":" + str(principal.pairing_generation)
        key = "core-device-admission:v1:" + hashlib.sha256(_jcs([principal.account_id, scope, idempotency_key]).encode()).hexdigest()
        with self.account(principal) as account:
            with account.store.transaction():
                saved = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (key,)).fetchone()
                if saved:
                    previous = json.loads(saved[0])
                    if previous["digest"] != digest:
                        raise GatewayError("IDEMPOTENCY_CONFLICT")
                    return self._record(account, principal, previous["requestId"])
                target = self.router.resolve(account, principal, device_id=body.get("deviceId"), origin_context=body.get("originContext"))
                revision = target.grant_revision
                bindings = account.device_requests.capabilities.available(target.device_id, target.pairing_generation, revision)
                binding = next((b for b in bindings if b["capabilityId"] == body["capabilityId"] and b["capabilityVersion"] == body["capabilityVersion"]), None)
                if binding is None:
                    raise account.device_requests.capabilities.unavailable_error(
                        target.device_id, target.pairing_generation, revision, body["capabilityId"], body["capabilityVersion"])
                online = self.core.is_device_online(principal.account_id, target.device_id, target.pairing_generation)
                if binding["risk"] == "high-privilege-ephemeral" and not online:
                    raise GatewayError("DEVICE_OFFLINE", {"reason": "LIVE_CONNECTION_REQUIRED", "source": "core-connection",
                        "capabilityId": body["capabilityId"], "capabilityVersion": body["capabilityVersion"]})
                request_id = "devreq_" + str(uuid.uuid4())
                record = account.device_requests.enqueue(
                    request_id=request_id, device_id=target.device_id,
                    pairing_generation=target.pairing_generation, grant_revision=revision,
                    risk=binding["risk"], capability={"id": binding["capabilityId"], "version": binding["capabilityVersion"]},
                    provider={"pluginId": binding["pluginId"], "authorKeyId": binding["authorKeyId"]}, parameters=body["parameters"],
                    caller=caller,
                    correlation_id=request_id, requires_foreground_confirmation=binding["risk"] in {"write", "high-privilege-ephemeral"},
                    online=online)
                account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?,?)", (key, _jcs({"digest": digest, "requestId": request_id})))
                account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?,?)",
                    ("core-device-owner:" + request_id, _jcs({"clientId": principal.client_id, "scope": scope})))
                return self._record(account, principal, request_id)

    def _record(self, account: Any, principal: Principal, request_id: str) -> dict[str, Any]:
        row = account.device_requests._row(request_id)
        target = self._request_target(account, principal, request_id)
        account.device_requests._assert_request_not_revoked(row)
        account.device_requests._expire_if_due(row, _now())
        record = account.device_requests.get(request_id)
        record.pop("parameters", None)
        receipt = account.store.database.execute("SELECT claim_id FROM claim_receipts WHERE request_id=?", (request_id,)).fetchone()
        if receipt:
            result = account.device_requests.read_result(request_id, receipt[0])
            if result is not None:
                record["result"] = result
        if record["state"] == "pending" and not self.core.is_device_online(principal.account_id, target.device_id, target.pairing_generation):
            record["details"] = {"reason": "DEVICE_OFFLINE", "queued": True, "source": "core-connection"}
        elif record["state"] == "claimed" and record.get("localAuthorizationState") == "awaiting_confirmation":
            record["details"] = {"reason": "awaiting_confirmation", "source": "android-progress"}
        result = record.get("result", {})
        if result.get("outcome") in {"denied", "failed"} and isinstance(result.get("data"), dict):
            data = result["data"]
            reason = data.get("reason", data.get("code"))
            if reason == "LOCAL_USER_REJECTED":
                record["details"] = {"reason": "user_rejected", "androidReason": reason, "source": "android-result"}
            elif isinstance(reason, str) and reason in {"LOCAL_CONFIRMATION_TIMEOUT", "DEVICE_REQUEST_TIMEOUT"}:
                record["details"] = {"reason": "timeout", "androidReason": reason, "source": "android-result",
                                     "phase": "confirmation" if reason == "LOCAL_CONFIRMATION_TIMEOUT" else "execution"}
            elif isinstance(reason, str) and re.fullmatch(r"[A-Z0-9_]{1,128}", reason):
                record["details"] = {"reason": reason, "source": "android-result"}
        elif record["state"] in {"expired", "outcome_unknown"} and _now(record["expiresAt"]) <= _now():
            record["details"] = {"reason": "timeout", "source": "core-deadline",
                                 "outcomeUnknown": record["state"] == "outcome_unknown"}
        return record

    def get(self, principal: Principal, request_id: str) -> dict[str, Any]:
        with self.account(principal) as account:
            with account.store.transaction():
                return self._record(account, principal, request_id)

    def cancel(self, principal: Principal, request_id: str) -> dict[str, Any]:
        with self.account(principal) as account:
            with account.store.transaction():
                record = self._record(account, principal, request_id)
                if record["state"] in {"pending", "claimed", "cancel_requested"}:
                    target = self._request_target(account, principal, request_id)
                    account.device_requests.cancel(request_id, target.device_id, target.pairing_generation, target.grant_revision, request_id)
                return self._record(account, principal, request_id)

    def acknowledge(self, principal: Principal, request_id: str, result_digest: str | None = None) -> dict[str, Any]:
        with self.account(principal) as account:
            with account.store.transaction():
                self._record(account, principal, request_id)
                receipt = account.store.database.execute("SELECT claim_id FROM claim_receipts WHERE request_id=?", (request_id,)).fetchone()
                if receipt is None:
                    raise GatewayError("RESULT_NOT_READY")
                return account.device_requests.acknowledge_result(request_id, receipt[0], result_digest)
