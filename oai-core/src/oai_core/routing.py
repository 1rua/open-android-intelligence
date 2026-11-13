"""Account-owned device selection; a selected request never changes its target."""
from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any

from .gateway.core import GatewayError

DEFAULT_DEVICE_KEY = "core-default-device"


@dataclass(frozen=True)
class TargetBinding:
    device_id: str
    pairing_generation: int
    grant_revision: int
    reason: str

    def descriptor(self) -> dict[str, Any]:
        return {"deviceId": self.device_id, "pairingGeneration": self.pairing_generation,
                "grantRevision": self.grant_revision, "reason": self.reason}


class DeviceRouter:
    def __init__(self, core: Any):
        self.core = core

    def devices(self, account: Any) -> list[dict[str, Any]]:
        rows = account.store.database.execute(
            "SELECT device_id,pairing_generation,grant_revision FROM device_keys ORDER BY device_id"
        ).fetchall()
        devices = []
        for row in rows:
            saved = account.store.database.execute(
                "SELECT value FROM account_metadata WHERE key=?", ("core-device-info:" + row[0],)
            ).fetchone()
            metadata = json.loads(saved[0]) if saved else {}
            devices.append({"deviceId": row[0], "pairingGeneration": row[1], "grantRevision": row[2],
                            "displayName": metadata.get("displayName", row[0]),
                            "online": self.core.is_device_online(account.account_id, row[0], row[1]),
                            **{key: metadata[key] for key in ("model", "platformApi", "appVersion", "registeredAt", "lastSeenAt") if key in metadata}})
        return devices

    @staticmethod
    def binding(account: Any, device_id: str, reason: str) -> TargetBinding:
        if not isinstance(device_id, str) or not device_id:
            raise GatewayError("SCHEMA_INVALID")
        row = account.store.database.execute(
            "SELECT pairing_generation,grant_revision FROM device_keys WHERE device_id=?", (device_id,)
        ).fetchone()
        if row is None:
            raise GatewayError("DEVICE_ACCESS_DENIED")
        return TargetBinding(device_id, int(row[0]), int(row[1]), reason)

    @staticmethod
    def default_device(account: Any) -> str | None:
        row = account.store.database.execute(
            "SELECT value FROM account_metadata WHERE key=?", (DEFAULT_DEVICE_KEY,)
        ).fetchone()
        if row is None:
            return None
        return row[0] if account.store.database.execute(
            "SELECT 1 FROM device_keys WHERE device_id=?", (row[0],)
        ).fetchone() else None

    def set_default(self, account: Any, device_id: str | None) -> dict[str, Any]:
        if device_id is None:
            account.store.database.execute("DELETE FROM account_metadata WHERE key=?", (DEFAULT_DEVICE_KEY,))
        else:
            self.binding(account, device_id, "default")
            account.store.database.execute("INSERT OR REPLACE INTO account_metadata(key,value) VALUES (?,?)",
                                           (DEFAULT_DEVICE_KEY, device_id))
        return {"defaultDeviceId": device_id}

    def resolve(self, account: Any, principal: Any, *, device_id: str | None = None,
                origin_context: str | None = None) -> TargetBinding:
        # A legacy token may select only the device to which it was originally
        # issued. Account-level tokens have no such field; do not infer one.
        legacy = principal.context is None and principal.device_id and not getattr(principal, "login_session_id", None)
        if legacy:
            if device_id is not None and device_id != principal.device_id:
                raise GatewayError("DEVICE_ACCESS_DENIED")
            target = self.binding(account, principal.device_id, "legacy-credential")
            if target.pairing_generation != principal.pairing_generation:
                raise GatewayError("PAIRING_GENERATION_STALE")
            return target
        if device_id is not None:
            return self.binding(account, device_id, "explicit")
        if origin_context is not None:
            registration = getattr(self.core, "agent_registration", None)
            if registration is None or not isinstance(origin_context, str):
                raise GatewayError("ORIGIN_CONTEXT_INVALID")
            facts = registration.verify_origin(origin_context, principal.account_id)
            target = self.binding(account, facts["deviceId"], "session-origin")
            if target.pairing_generation != facts["pairingGeneration"]:
                raise GatewayError("PAIRING_GENERATION_STALE")
            return target
        if principal.context is not None:
            target = self.binding(account, principal.context.device_id, "session-origin")
            if target.pairing_generation != principal.context.pairing_generation:
                raise GatewayError("PAIRING_GENERATION_STALE")
            return target
        default = self.default_device(account)
        if default is not None:
            return self.binding(account, default, "default")
        devices = self.devices(account)
        online = [device for device in devices if device["online"]]
        if len(online) == 1:
            return self.binding(account, online[0]["deviceId"], "unique-online")
        if not online:
            raise GatewayError("DEVICE_OFFLINE" if devices else "NO_DEVICES", {"devices": devices})
        raise GatewayError("DEVICE_SELECTION_REQUIRED", {"devices": online})
