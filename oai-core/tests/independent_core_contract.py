"""Standalone HTTP acceptance script requiring only Core and its direct dependencies.

Usage: python independent_core_contract.py TEMPORARY_DATA_DIRECTORY [IMPORT_ROOT]
No pytest, Hermes SDK, Gateway plugin, or test fixtures are imported here.
"""
from __future__ import annotations

import importlib.abc
from pathlib import Path
import sys


class NoHermes(importlib.abc.MetaPathFinder):
    blocked = ("open_android_intelligence_gateway", "hermes_cli", "hermes_constants", "gateway", "tools.approval")
    attempts: list[str] = []

    def find_spec(self, fullname, path=None, target=None):
        if any(fullname == prefix or fullname.startswith(prefix + ".") for prefix in self.blocked):
            self.attempts.append(fullname)
            raise AssertionError("Core attempted a forbidden host import: " + fullname)
        return None


sys.meta_path.insert(0, NoHermes())

import asyncio
import base64
from datetime import datetime, timezone
import hashlib
import json
import secrets

from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
import oai_core
from oai_core.server import create_app, create_core, DEVICE_API
from oai_core.gateway.capability_policy import DeviceCapabilityPolicy
from oai_core.gateway.core import _contract_root, _jcs
from oai_core.sessions import AgentRegistry

PREFIX = "/open-android-intelligence/v2"
contract = _contract_root()
assert (contract / "schemas").is_dir() and (contract / "vectors").is_dir(), contract
if len(sys.argv) > 2:
    assert Path(oai_core.__file__).resolve().is_relative_to(Path(sys.argv[2])), oai_core.__file__
temporary = Path(sys.argv[1])
settings = {"storageRoot": str(temporary / "data"), "masterKeyFile": str(temporary / "secrets/master")}
core = create_core(settings)
for owner in ("alice", "bob"):
    account = core.open_gateway_account(owner)
    account.credentials.create_password("test-only-password")
    account.close()


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


async def signed_request(client, phone, key, method, path, body):
    raw = json.dumps(body, separators=(",", ":")).encode()
    timestamp = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    nonce = b64(secrets.token_bytes(16))
    request_id = "req_" + secrets.token_hex(8)
    preimage = "\n".join(("OPEN-ANDROID-INTELLIGENCE-REQUEST-V2", method, path, phone["accountId"], phone["deviceId"], phone["sessionId"], request_id, timestamp, nonce, hashlib.sha256(raw).hexdigest())).encode("ascii")
    headers = {"Authorization": "Bearer " + phone["accessToken"], "Content-Type": "application/json", "Idempotency-Key": request_id,
        "X-Open-Android-Intelligence-Protocol": "2.1", "X-Open-Android-Intelligence-Account": phone["accountId"],
        "X-Open-Android-Intelligence-Device": phone["deviceId"], "X-Open-Android-Intelligence-Session": phone["sessionId"],
        "X-Open-Android-Intelligence-Request-Id": request_id, "X-Open-Android-Intelligence-Timestamp": timestamp,
        "X-Open-Android-Intelligence-Nonce": nonce, "X-Open-Android-Intelligence-Signature": b64(key.sign(preimage))}
    response = await client.request(method, path, data=raw, headers=headers)
    assert response.status == 200, await response.text()
    return (await response.json())["data"]


async def scenario():
    async with TestClient(TestServer(create_app(settings, registry=AgentRegistry(), core=core))) as client:
        negotiation = json.loads((contract / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
        negotiation["negotiationId"] = "neg_" + secrets.token_hex(8)
        negotiation["schemaHashes"]["core"] = core.contracts.core_schema_hash
        response = await client.post(PREFIX + "/negotiate", json=negotiation)
        assert response.status == 200, await response.text()
        key = Ed25519PrivateKey.generate()
        response = await client.post(PREFIX + "/sessions/password", json={"negotiationId": negotiation["negotiationId"], "username": "alice", "password": "test-only-password",
            "installation": {"installationId": negotiation["client"]["installationId"], "displayName": "Independent Core test", "devicePublicKey": b64(key.public_key().public_bytes_raw())}})
        assert response.status == 200, await response.text()
        phone = (await response.json())["data"]
        schema = {"type": "object", "additionalProperties": False, "properties": {"limit": {"type": "integer"}}, "required": ["limit"]}
        binding = {"pluginId": "org.example.fixture", "authorKeyId": "sha256:" + "a" * 64, "capabilityId": "org.example.fixture.read", "capabilityVersion": "1.0.0",
            "schemaSha256": "sha256:" + hashlib.sha256(_jcs(schema).encode()).hexdigest(), "schema": schema, "risk": "read"}
        account = core.open_gateway_account("alice")
        try:
            DeviceCapabilityPolicy(account.store).set(binding["capabilityId"], True)
        finally:
            account.close()
        publication = await signed_request(client, phone, key, "POST", PREFIX + "/pairings/current/capabilities", {"bindings": [binding], "expectedGrantRevision": 1, "localGrantRevision": 1})
        response = await client.post(DEVICE_API + "/tokens", json={"username": "alice", "password": "test-only-password", "deviceId": phone["deviceId"]})
        assert response.status == 200, await response.text()
        headers = {"Authorization": "Bearer " + (await response.json())["token"]}
        response = await client.get(DEVICE_API + "/capabilities", headers=headers)
        assert response.status == 200, await response.text()
        assert binding["capabilityId"] in await response.text()
        body = {"capabilityId": binding["capabilityId"], "capabilityVersion": binding["capabilityVersion"], "parameters": {"limit": 1}}
        response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "independent-once"})
        assert response.status == 200, await response.text()
        request_id = (await response.json())["requestId"]
        claim = await signed_request(client, phone, key, "POST", PREFIX + "/device-requests/" + request_id + "/claim", {})
        await signed_request(client, phone, key, "POST", PREFIX + "/device-requests/" + request_id + "/result", {"claimId": claim["claimId"], "grantRevision": publication["grantRevision"], "result": {"outcome": "succeeded", "data": {"records": ["independent-result"]}}})
        response = await client.get(DEVICE_API + "/device-requests/" + request_id, headers=headers)
        record = await response.json()
        assert record["state"] == "succeeded" and record["result"]["data"]["records"] == ["independent-result"], record
        response = await client.post(DEVICE_API + "/device-requests/" + request_id + "/ack", headers=headers)
        assert response.status == 200
        response = await client.get(DEVICE_API + "/device-requests/" + request_id, headers=headers)
        assert "result" not in await response.json()
        assert NoHermes.attempts == [], NoHermes.attempts
        assert not any(name == prefix or name.startswith(prefix + ".") for name in sys.modules for prefix in NoHermes.blocked)


asyncio.run(scenario())
print("independent Core HTTP contracts passed")
