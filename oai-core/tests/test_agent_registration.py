"""Synthetic registration, bridge and trusted-origin integration tests."""
import json
import sqlite3
from types import SimpleNamespace

import pytest
from aiohttp.test_utils import TestClient, TestServer

from oai_core.agent_registration import AgentRegistration, bridge_proof, create_bridge_secret, discover_candidates, read_bridge_secret
from oai_core.auth import Principal
from oai_core.gateway.core import GatewayError, VerifiedRequestContext
from oai_core.sessions import AdapterError, build_registry
from oai_hermes_adapter.bridge import AutoAccountSpaces, create_hermes_bridge_app


class Identity:
    def __init__(self, database):
        self.database_path = database
        self.allowed = True
        self.scopes = []

    def require_management(self, handle, scope):
        self.scopes.append(scope)
        if not self.allowed or handle != "admin" or scope != "agents.manage":
            raise PermissionError("management required")
        return {"accountId": "admin"}

    def account(self, account_id):
        if account_id not in {"alice", "bob", "charlie"}:
            raise ValueError("missing account")
        return {"accountId": account_id}


class Host:
    def __init__(self):
        self.online = True
        self.sent = []

    async def status(self):
        return self.online

    def descriptor(self):
        return {"capabilities": {"list": True, "history": True, "send": True, "stream": True}}

    async def list_sessions(self, principal):
        return [{"id": principal.account_id + "-session"}]

    async def send(self, principal, session_id, text, **kwargs):
        self.sent.append((principal, session_id, text, kwargs))
        yield {"type": "done"}


def make_identity(tmp_path):
    path = tmp_path / "identity.sqlite"
    sqlite3.connect(path).close()
    path.chmod(0o600)
    return Identity(path)


@pytest.mark.asyncio
async def test_automatic_spaces_require_confirmed_core_key_not_shared_bridge_token(tmp_path):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    from oai_core.agent_registration import sign_core_request

    root = tmp_path / "accounts"
    root.mkdir(mode=0o700)
    legitimate = Ed25519PrivateKey.generate()
    attacker = Ed25519PrivateKey.generate()
    public = legitimate.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    spaces = AutoAccountSpaces(root, SimpleNamespace(storage_root=root), {"core-instance": public})
    token = "s" * 40
    app = create_hermes_bridge_app(Host(), token, [], instance_id="hermes-1", auto_spaces=spaces)
    payload = {"coreInstanceId": "core-instance", "hermesInstanceId": "hermes-1", "accountId": "alice",
               "nonce": "a" * 64}
    async with TestClient(TestServer(app)) as server:
        response = await server.post("/oai/session-adapter/v1/auto-spaces",
            headers={"Authorization": "Bearer " + token}, json={**payload, "signature": sign_core_request(attacker, payload)})
        assert response.status == 403
        response = await server.post("/oai/session-adapter/v1/auto-spaces",
            headers={"Authorization": "Bearer " + token},
            json={**payload, "nativeId": "victim", "signature": sign_core_request(legitimate, payload)})
        assert response.status == 400


@pytest.mark.asyncio
async def test_registered_core_automatically_creates_and_reuses_private_native_spaces(tmp_path):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
    from oai_hermes_adapter.backend import SessionBackend

    identity = make_identity(tmp_path)
    root = tmp_path / "accounts"
    root.mkdir(mode=0o700)
    key_file = tmp_path / "master.key"
    create_master_key_file(key_file)
    backend = SessionBackend(root, [], secret_store=LocalMasterKeyStore(key_file))
    key = Ed25519PrivateKey.generate()
    public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    spaces = AutoAccountSpaces(root, backend, {"core-instance": public})
    registration = AgentRegistration("core-instance", identity, lambda p: True, lambda facts: True,
                                     core_signing_key=key, create=True)
    secret = "s" * 40
    (tmp_path / "bridge.key").write_text(secret)
    (tmp_path / "bridge.key").chmod(0o600)
    async with TestClient(TestServer(create_hermes_bridge_app(Host(), secret, [], instance_id="hermes-1",
                                                             auto_spaces=spaces))) as server:
        manifest = {"id": "hermes", "type": "hermes", "instanceId": "hermes-1", "sessionScope": "account",
                    "url": str(server.make_url("/")).rstrip("/"), "secretFile": "bridge.key"}
        await registration.register("admin", manifest, tmp_path)
        registry = build_registry({"storageRoot": str(tmp_path)}, SimpleNamespace(agent_registration=registration))
        try:
            remote = registry.get("hermes")
            alice = await remote.list_sessions(Principal("alice"))
            bob = await remote.list_sessions(Principal("bob"))
            assert alice != bob
            registration.reserve_legacy_mappings("hermes", {"charlie": "legacy-native"})
            assert await remote.list_sessions(Principal("alice")) == alice
            with pytest.raises(ValueError, match="conflicts"):
                registration.confirm_mapping("admin", "hermes", "bob", "legacy-native")
            native_id = registration.mappings("hermes")["alice"]
            assert native_id.startswith("oai_")
            proof = registration.automatic_headers("hermes", "alice", native_id, "GET",
                "/oai/session-adapter/v1/sessions", None, {})
            headers = {"Authorization": "Bearer " + secret, "X-OAI-Account": native_id, **proof}
            first = await server.get("/oai/session-adapter/v1/sessions", headers=headers)
            assert first.status == 200
            replay = await server.get("/oai/session-adapter/v1/sessions", headers=headers)
            assert replay.status == 403
            fresh = registration.automatic_headers("hermes", "alice", native_id, "GET",
                "/oai/session-adapter/v1/sessions", None, {})
            altered = await server.get("/oai/session-adapter/v1/sessions/other/history",
                headers={"Authorization": "Bearer " + secret, "X-OAI-Account": native_id, **fresh})
            assert altered.status == 403
        finally:
            await registry.close()
            backend.close()


def test_auto_space_recovers_after_interrupted_creation(tmp_path, monkeypatch):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
    from oai_core.gateway import core as gateway_core
    from oai_hermes_adapter.backend import SessionBackend

    root = tmp_path / "accounts"
    root.mkdir(mode=0o700)
    master = tmp_path / "key"
    create_master_key_file(master)
    backend = SessionBackend(root, [], secret_store=LocalMasterKeyStore(master))
    key = Ed25519PrivateKey.generate().public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    spaces = AutoAccountSpaces(root, backend, {"core": key})
    original = gateway_core.AccountStore
    def interrupted(*args, **kwargs):
        raise OSError("synthetic interrupted creation")
    monkeypatch.setattr(gateway_core, "AccountStore", interrupted)
    with pytest.raises(OSError):
        spaces.prepare("core", "alice")
    monkeypatch.setattr(gateway_core, "AccountStore", original)
    spaces = AutoAccountSpaces(root, backend, {"core": key})
    native_id = spaces.prepare("core", "alice")
    assert native_id.startswith("oai_")
    assert spaces.prepare("core", "alice") == native_id
    assert spaces.prepare("core", "bob") != native_id
    backend.close()


def context():
    return VerifiedRequestContext(account_id="alice", device_id="phone", session_id="signed-session",
                                  request_id="request", correlation_id="request",
                                  pairing_generation=2, grant_revision=3)


@pytest.mark.asyncio
async def test_real_handshake_registration_mapping_and_registry(tmp_path):
    identity = make_identity(tmp_path)
    live = {"principal": True, "origin": True}
    reg = AgentRegistration(instance_id="core-instance", identity_store=identity,
                            validate_principal=lambda p: live["principal"],
                            validate_origin=lambda facts: live["origin"], create=True)
    host = Host()
    secret = "synthetic-bridge-secret-" + "x" * 32
    (tmp_path / "bridge.key").write_text(secret + "\n")
    (tmp_path / "bridge.key").chmod(0o600)
    app = create_hermes_bridge_app(host, secret, ["native-alice", "native-bob"], instance_id="hermes-1")
    async with TestClient(TestServer(app)) as server:
        manifest = {"id": "hermes", "type": "hermes", "instanceId": "hermes-1", "sessionScope": "account",
                    "url": str(server.make_url("/")).rstrip("/"), "secretFile": "bridge.key"}
        await reg.register("admin", manifest, tmp_path)
        assert reg.entries() == [manifest]
        assert reg.mappings("hermes") == {}
        core = SimpleNamespace(agent_registration=reg)
        registry = build_registry({"storageRoot": str(tmp_path)}, core)
        try:
            remote = registry.get("hermes")
            assert (await registry.discover())[0]["online"] is True
            with pytest.raises(AdapterError, match="AGENT_ACCOUNT_NOT_CONNECTED"):
                await remote.list_sessions(Principal("alice"))
        finally:
            await registry.close()
        reg.import_mappings("admin", "hermes", {"alice": "native-alice", "bob": "native-bob"})
        registry = build_registry({"storageRoot": str(tmp_path)}, core)
        try:
            remote = registry.get("hermes")
            assert await remote.list_sessions(Principal("alice")) == [{"id": "native-alice-session"}]
            await remote.status()
            principal = Principal("alice", context=context(), device_id="phone", pairing_generation=2, grant_revision=3)
            assert [x async for x in remote.send(principal, "session-1", "synthetic", client_message_id="turn-1")] == [{"type": "done"}]
            received, session_id, _, args = host.sent[-1]
            assert received.account_id == "native-alice"
            assert session_id == "session-1" and args["client_message_id"] == "turn-1"
            facts = reg.verify_origin(received.origin_context, "alice")
            assert (facts["agentId"], facts["sessionId"], facts["turnId"], facts["deviceId"]) == ("hermes", "session-1", "turn-1", "phone")
            assert facts["pairingGeneration"] == 2 and facts["grantRevision"] == 3
            assert facts["deviceSessionId"] == "signed-session"
            with pytest.raises(GatewayError, match="ORIGIN_CONTEXT_INVALID"):
                reg.verify_origin(received.origin_context, "bob")
            live["origin"] = False
            with pytest.raises(GatewayError, match="ORIGIN_CONTEXT_INVALID"):
                reg.verify_origin(received.origin_context, "alice")
        finally:
            await registry.close()
        assert set(identity.scopes) == {"agents.manage"}


@pytest.mark.asyncio
async def test_handshake_rejects_replacement_offline_and_expired_management(tmp_path):
    identity = make_identity(tmp_path)
    reg = AgentRegistration(instance_id="core", identity_store=identity, validate_principal=lambda p: True,
                            validate_origin=lambda p: True, create=True)
    host = Host()
    secret = "s" * 40
    key = tmp_path / "key"
    key.write_text(secret)
    key.chmod(0o600)
    async with TestClient(TestServer(create_hermes_bridge_app(host, secret, ["native"], instance_id="actual"))) as server:
        manifest = {"id": "hermes", "type": "hermes", "instanceId": "wrong", "sessionScope": "account",
                    "url": str(server.make_url("/")).rstrip("/"), "secretFile": "key"}
        with pytest.raises(ValueError, match="handshake"):
            await reg.register("admin", manifest, tmp_path)
        manifest["instanceId"] = "actual"
        host.online = False
        with pytest.raises(ValueError, match="handshake"):
            await reg.register("admin", manifest, tmp_path)
        host.online = True
        identity.allowed = False
        with pytest.raises(PermissionError):
            await reg.register("admin", manifest, tmp_path)
        assert reg.entries() == []


@pytest.mark.asyncio
async def test_management_is_rechecked_after_network_handshake(tmp_path):
    from aiohttp import web
    identity = make_identity(tmp_path)
    reg = AgentRegistration("core", identity, lambda p: True, lambda facts: True, create=True)
    secret = "s" * 40
    key = tmp_path / "key"
    key.write_text(secret)
    key.chmod(0o600)

    async def handshake(request):
        challenge = (await request.json())["challenge"]
        identity.allowed = False
        return web.json_response({"instanceId": "instance", "sessionScope": "account", "online": True,
            "capabilities": {}, "proof": bridge_proof(secret, challenge, "instance", "account", True, {})})

    app = web.Application()
    app.router.add_post("/oai/session-adapter/v1/handshake", handshake)
    async with TestClient(TestServer(app)) as server:
        manifest = {"id": "hermes", "type": "hermes", "instanceId": "instance", "sessionScope": "account",
                    "url": str(server.make_url("/")).rstrip("/"), "secretFile": "key"}
        with pytest.raises(PermissionError):
            await reg.register("admin", manifest, tmp_path)
        assert reg.entries() == []


@pytest.mark.asyncio
async def test_mapping_import_is_atomic_and_aliases_share_ownership(tmp_path):
    identity = make_identity(tmp_path)
    reg = AgentRegistration(instance_id="core", identity_store=identity, validate_principal=lambda p: True,
                            validate_origin=lambda p: True, create=True)
    host, secret = Host(), "s" * 40
    key = tmp_path / "key"
    key.write_text(secret)
    key.chmod(0o600)
    async with TestClient(TestServer(create_hermes_bridge_app(host, secret, ["native"], instance_id="one"))) as server:
        manifest = {"id": "a", "type": "hermes", "instanceId": "one", "sessionScope": "account",
                    "url": str(server.make_url("/")).rstrip("/"), "secretFile": "key"}
        await reg.register("admin", manifest, tmp_path)
        await reg.register("admin", {**manifest, "id": "alias"}, tmp_path)
        reg.confirm_mapping("admin", "a", "alice", "native")
        with pytest.raises(ValueError, match="conflicts"):
            reg.import_mappings("admin", "alias", {"bob": "fresh", "charlie": "native"})
        assert reg.mappings("alias") == {"alice": "native"}
        with pytest.raises(ValueError, match="identity or bridge key"):
            reg.store._register_instance("admin", {**manifest, "type": "remote", "sessionScope": "deployment"}, secret,
                "a" * 64, {"instanceId": "one", "sessionScope": "deployment", "online": True, "capabilities": {},
                           "proof": bridge_proof(secret, "a" * 64, "one", "deployment", True, {})})


def test_secret_sources_and_limited_candidate_discovery(tmp_path):
    key = tmp_path / "key"
    create_bridge_secret(key)
    assert len(read_bridge_secret({"secretFile": "key"}, tmp_path)) >= 32
    with pytest.raises(FileExistsError):
        create_bridge_secret(key)
    with key.open("rb") as f:
        assert len(read_bridge_secret({"secretFd": f.fileno()}, tmp_path)) >= 32
    key.chmod(0o644)
    with pytest.raises(ValueError):
        read_bridge_secret({"secretFile": "key"}, tmp_path)
    manifests = tmp_path / "manifests"
    manifests.mkdir(mode=0o700)
    entry = {"id": "a", "type": "hermes", "instanceId": "one", "sessionScope": "account",
             "url": "http://127.0.0.1:8792", "secretFile": "key"}
    (manifests / "candidate.json").write_text(json.dumps(entry))
    (manifests / "nested").mkdir()
    (manifests / "nested" / "ignored.json").write_text(json.dumps(entry))
    (manifests / "linked.json").symlink_to(manifests / "candidate.json")
    assert discover_candidates([manifests]) == [(manifests / "candidate.json", entry)]
    manifests.chmod(0o777)
    assert discover_candidates([manifests]) == []


def test_origin_requires_verified_context_and_revocation_checks(tmp_path):
    identity = make_identity(tmp_path)
    reg = AgentRegistration(instance_id="core", identity_store=identity, validate_principal=lambda p: True,
                            validate_origin=lambda facts: True, create=True)
    with pytest.raises(GatewayError, match="ORIGIN_CONTEXT_INVALID"):
        reg.issue_origin(Principal("alice", device_id="phone"), agent_id="a", session_id="s", turn_id="t")
    principal = Principal("alice", context=context())
    token = reg.issue_origin(principal, agent_id="a", session_id="s", turn_id="t")
    assert reg.verify_origin(token, "alice")["turnId"] == "t"
    with pytest.raises(GatewayError, match="ORIGIN_CONTEXT_INVALID"):
        reg.verify_origin(token[:-1] + ("A" if token[-1] != "A" else "B"), "alice")
    other = AgentRegistration(instance_id="core", identity_store=identity, validate_principal=lambda p: True,
                              validate_origin=lambda facts: True)
    with pytest.raises(GatewayError, match="ORIGIN_CONTEXT_INVALID"):
        other.verify_origin(token, "alice")
