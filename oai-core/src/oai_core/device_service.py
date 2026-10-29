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


class DeviceService:
    def __init__(self, core: Any, authentication: Authentication):
        self.core = core
        self.authentication = authentication

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
            rows = account.store.database.execute("SELECT device_id,pairing_generation,grant_revision FROM device_keys ORDER BY device_id").fetchall()
            return {"devices": [{"deviceId": r[0], "pairingGeneration": r[1], "grantRevision": r[2],
                                 "online": self.core.is_device_online(principal.account_id, r[0], r[1])} for r in rows]}

    def capabilities(self, principal: Principal) -> dict[str, Any]:
        with self.account(principal) as account:
            return {"capabilities": capability_catalog(account, principal.device_id)}

    def submit(self, principal: Principal, body: Any, idempotency_key: str | None) -> dict[str, Any]:
        if not isinstance(body, dict) or set(body) != {"capabilityId", "capabilityVersion", "parameters"} or not isinstance(body["parameters"], dict):
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
        key = "core-device-idempotency:" + hashlib.sha256(f"{principal.device_id}:{principal.pairing_generation}:{idempotency_key}".encode()).hexdigest()
        with self.account(principal) as account:
            with account.store.transaction():
                saved = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (key,)).fetchone()
                if saved:
                    previous = json.loads(saved[0])
                    if previous["digest"] != digest:
                        raise GatewayError("IDEMPOTENCY_CONFLICT")
                    return self._record(account, principal, previous["requestId"])
                bindings = account.device_requests.capabilities.available(principal.device_id, principal.pairing_generation, principal.grant_revision)
                binding = next((b for b in bindings if b["capabilityId"] == body["capabilityId"] and b["capabilityVersion"] == body["capabilityVersion"]), None)
                if binding is None:
                    raise GatewayError("CAPABILITY_DENIED")
                request_id = "devreq_" + str(uuid.uuid4())
                record = account.device_requests.enqueue(
                    request_id=request_id, device_id=principal.device_id,
                    pairing_generation=principal.pairing_generation, grant_revision=principal.grant_revision,
                    risk=binding["risk"], capability={"id": binding["capabilityId"], "version": binding["capabilityVersion"]},
                    provider={"pluginId": binding["pluginId"], "authorKeyId": binding["authorKeyId"]}, parameters=body["parameters"],
                    correlation_id=request_id, requires_foreground_confirmation=binding["risk"] in {"write", "high-privilege-ephemeral"},
                    online=self.core.is_device_online(principal.account_id, principal.device_id, principal.pairing_generation))
                account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?,?)", (key, _jcs({"digest": digest, "requestId": request_id})))
                return {k: v for k, v in record.items() if k != "parameters"}

    def _record(self, account: Any, principal: Principal, request_id: str) -> dict[str, Any]:
        row = account.device_requests._row(request_id)
        account.device_requests._assert_binding(row, principal.device_id, principal.pairing_generation, principal.grant_revision)
        account.device_requests._assert_request_not_revoked(row)
        account.device_requests._expire_if_due(row, _now())
        record = account.device_requests.get(request_id)
        record.pop("parameters", None)
        receipt = account.store.database.execute("SELECT claim_id FROM claim_receipts WHERE request_id=?", (request_id,)).fetchone()
        if receipt:
            result = account.device_requests.read_result(request_id, receipt[0])
            if result is not None:
                record["result"] = result
        return record

    def get(self, principal: Principal, request_id: str) -> dict[str, Any]:
        with self.account(principal) as account:
            return self._record(account, principal, request_id)

    def cancel(self, principal: Principal, request_id: str) -> dict[str, Any]:
        with self.account(principal) as account:
            record = self._record(account, principal, request_id)
            if record["state"] in {"pending", "claimed", "cancel_requested"}:
                account.device_requests.cancel(request_id, principal.device_id, principal.pairing_generation, principal.grant_revision, request_id)
            return self._record(account, principal, request_id)

    def acknowledge(self, principal: Principal, request_id: str) -> dict[str, Any]:
        with self.account(principal) as account:
            record = self._record(account, principal, request_id)
            receipt = account.store.database.execute("SELECT claim_id FROM claim_receipts WHERE request_id=?", (request_id,)).fetchone()
            if receipt:
                account.device_requests.acknowledge_result(request_id, receipt[0])
            return {"requestId": request_id, "state": record["state"], "acknowledged": bool(receipt)}
