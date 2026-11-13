"""Android capability diagnostics and read-only inspection of retired account policy."""
from __future__ import annotations

import json
import re
from typing import Any

# Onboarding hints only. A hint can never authorize or dispatch a request.
CAPABILITY_HINTS = {
    "org.openandroidintelligence.notifications.query": "通知",
    "org.openandroidintelligence.sms.query": "短信",
    "org.openandroidintelligence.call-log.query": "通话记录",
}
POLICY_KEY = "device-capability-policy:v1"


class DeviceCapabilityPolicy:
    def __init__(self, store: Any):
        self.store = store

    def snapshot(self) -> dict[str, Any]:
        row = self.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (POLICY_KEY,)).fetchone()
        if row is None:
            return {"revision": 0, "enabled": {}}
        from .core import GatewayError
        try:
            value = json.loads(row[0])
        except (ValueError, TypeError):
            raise GatewayError("CAPABILITY_DENIED", {"reason": "CAPABILITY_POLICY_INVALID"}) from None
        # Malformed local configuration must not silently turn any switch on.
        if (not isinstance(value, dict) or set(value) != {"revision", "enabled"}
                or type(value["revision"]) is not int or value["revision"] < 0
                or not isinstance(value["enabled"], dict)
                or any(not self.valid_id(k) or type(v) is not bool for k, v in value["enabled"].items())):
            raise GatewayError("CAPABILITY_DENIED", {"reason": "CAPABILITY_POLICY_INVALID"})
        return value

    @staticmethod
    def valid_id(value: Any) -> bool:
        return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9.-]{1,256}", value) is not None

    def allows(self, capability_id: str) -> bool:
        """Legacy policy has no authority over an Android device."""
        from .core import GatewayError
        raise GatewayError("DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID")

    def set(self, capability_id: str, enabled: bool) -> dict[str, Any]:
        """Retain an explicit retirement error for old local callers; never write."""
        from .core import GatewayError
        raise GatewayError("DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID")


def capability_catalog(account: Any, device_id: str | None = None) -> list[dict[str, Any]]:
    """Current, authenticated inventory; never confuse an old/absent snapshot with absence."""
    devices = account.store.database.execute(
        "SELECT device_id,pairing_generation,grant_revision FROM device_keys" + (" WHERE device_id=?" if device_id else "") + " ORDER BY device_id",
        (device_id,) if device_id else (),
    ).fetchall()
    output = []
    for device in devices:
        target, generation, revision = device
        saved = account.device_requests.capabilities.publication(target)
        current = saved is not None and saved["generation"] == generation and saved["revision"] == revision
        bindings = saved["bindings"] if current else []
        inventory = saved.get("inventory") if current else None
        declared = {(b["pluginId"], b["capabilityId"], b["capabilityVersion"]): b for b in bindings}
        states = {(s["pluginId"], s["capabilityId"], s["capabilityVersion"]): s for s in inventory or []}
        keys = set(declared) | set(states)
        if saved is not None and not current:
            # Retain identities only as SYNC_REQUIRED diagnostics. Stale
            # schemas, providers and READY assertions never become callable.
            keys |= {(item["pluginId"], item["capabilityId"], item["capabilityVersion"])
                     for item in saved["bindings"] + saved.get("inventory", [])}
        keys |= {("", key, "") for key in set(CAPABILITY_HINTS) if not any(k[1] == key for k in keys)}
        for key in sorted(keys):
            binding, state = declared.get(key), states.get(key)
            android_state = ("SYNC_REQUIRED" if not current else state["status"] if state else
                             "READY" if binding else "PLUGIN_NOT_INSTALLED" if inventory is not None else "ANDROID_STATE_UNKNOWN")
            # Inventory describes diagnostics, never a grant. READY requires a matching binding.
            if android_state == "READY" and binding is None:
                android_state = "SYNC_REQUIRED"
            output.append({"deviceId": target, "pairingGeneration": generation, "grantRevision": revision,
                           "pluginId": key[0] or None, "capabilityId": key[1], "capabilityVersion": key[2] or None,
                           "androidStatus": android_state, "status": android_state,
                           **({"reason": state["reason"]} if state and state.get("reason") else {}),
                           **({"provider": {k: binding[k] for k in ("pluginId", "authorKeyId")},
                               "risk": binding["risk"], "schema": binding["schema"]} if binding else {})})
    if not devices:
        output = [{"capabilityId": key, "capabilityVersion": None, "androidStatus": "PAIRING_REQUIRED", "status": "PAIRING_REQUIRED"}
                  for key in sorted(set(CAPABILITY_HINTS))]
    return output
