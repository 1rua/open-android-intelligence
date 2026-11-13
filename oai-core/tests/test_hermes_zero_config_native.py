"""Synthetic full Core HTTP -> registered Hermes bridge -> pinned native SessionStore."""
import os
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer


@pytest.mark.asyncio
async def test_real_account_login_registered_hermes_and_restart(tmp_path, monkeypatch):
    pytest.importorskip("oai_core.identity")
    sdk = os.environ.get("HERMES_SDK_ROOT")
    if not sdk:
        pytest.skip("requires the pinned Hermes SDK")
    monkeypatch.syspath_prepend(sdk)
    home = tmp_path / "hermes-home"
    home.mkdir(mode=0o700)
    monkeypatch.setenv("HERMES_HOME", str(home))
    monkeypatch.setenv("OAI_HERMES_INSTANCE_ID", "hermes-test")
    pytest.importorskip("gateway.session")
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from gateway.config import GatewayConfig, PlatformConfig
    from gateway.session import SessionStore
    from oai_core.agent_registration import AgentRegistration
    from oai_core.adapters.hermes import HermesRemoteSessionAdapter
    from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
    from oai_core.identity import IdentityStore, issue_bootstrap_proof
    from oai_core.server import AUTH_KEY, create_app, create_core
    from oai_core.sessions import AgentRegistry
    from oai_hermes_adapter.backend import SessionBackend
    from oai_hermes_adapter.bridge import AutoAccountSpaces
    from oai_hermes_adapter.runtime import SessionOnlyAdapter

    core_root = tmp_path / "core" / "accounts"
    identity_file = core_root.parent / "security" / "identity.sqlite"
    read, write = os.pipe()
    os.write(write, b"bootstrap\n")
    os.close(write)
    try:
        proof = issue_bootstrap_proof(identity_file, core_root, confirmation_fd=read)
    finally:
        os.close(read)
    identity = IdentityStore(identity_file, create=True, bootstrap_proof=proof)
    alice = identity.bootstrap("alice", "synthetic-alice-password", confirmed=True)
    management = identity.begin_management("alice", "synthetic-alice-password")
    bob = identity.create_account(management, "bob", "synthetic-bob-password")
    identity.close()
    config = {"storageRoot": str(core_root), "masterKeyFile": str(tmp_path / "core" / "master-key"),
              "contractRoot": str(Path(__file__).resolve().parents[2] / "gateway-contract")}
    hermes_root = tmp_path / "hermes-accounts"
    hermes_root.mkdir(mode=0o700)
    hermes_master = tmp_path / "hermes-key"
    create_master_key_file(hermes_master)
    backend = SessionBackend(hermes_root, [], secret_store=LocalMasterKeyStore(hermes_master))
    core = create_core(config)
    core_id = core.identity_store._policy()[2]
    signing_key = core.secret_store.instance_signing_key(core_id)
    public = signing_key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    token = "synthetic-bridge-token-" + "x" * 40
    bridge_key = core_root / "bridge.key"
    core_root.mkdir(mode=0o700)
    bridge_key.write_text(token)
    bridge_key.chmod(0o600)
    from gateway.platform_registry import PlatformEntry, platform_registry
    from oai_hermes_adapter.platform_identity import GATEWAY_PLATFORM_IDS
    for platform_id in GATEWAY_PLATFORM_IDS:
        platform_registry.register(PlatformEntry(name=platform_id, label="Synthetic test",
                                                 adapter_factory=lambda config: None, check_fn=lambda: True))
    runtime = SessionOnlyAdapter(PlatformConfig(enabled=True, extra={}), backend, token, [], 0)
    native_store = SessionStore(home / "sessions", GatewayConfig())
    runtime.set_session_store(native_store)
    assert native_store._routing_home == home
    async def synthetic_handler(event):
        return "synthetic native reply"
    runtime.set_message_handler(synthetic_handler)
    runtime.auto_spaces = AutoAccountSpaces(hermes_root, backend, {core_id: public})
    assert await runtime.connect()
    port = runtime._site._server.sockets[0].getsockname()[1]
    bridge_url = f"http://127.0.0.1:{port}"

    async def start_core(existing_core):
        registry = AgentRegistry(operations_path=core_root / "test-operations.sqlite")
        app = create_app(config, registry=registry, core=existing_core)
        auth = app[AUTH_KEY]
        def active(principal):
            try:
                auth.assert_current(principal)
                return True
            except Exception:
                return False
        reg = existing_core.agent_registration
        handle = existing_core.identity_store.begin_management("alice", "synthetic-alice-password")
        reg.initialize(handle)
        manifest = {"id": "hermes", "type": "hermes", "instanceId": "hermes-test", "sessionScope": "account",
                    "url": bridge_url, "secretFile": "bridge.key"}
        handle = existing_core.identity_store.begin_management("alice", "synthetic-alice-password")
        await reg.register(handle, manifest, core_root)
        remote = HermesRemoteSessionAdapter(bridge_url, token, reg.mappings("hermes"),
            account_registration=reg, origin_registration=reg, attachment_core=existing_core)
        assert await remote.status()
        registry.register(remote)
        client = TestClient(TestServer(app))
        await client.start_server()
        return client, reg

    async def login(client, username, password):
        response = await client.post("/open-android-intelligence/core/v1/tokens",
            json={"username": username, "password": password})
        assert response.status == 200, await response.text()
        bundle = await response.json()
        return {"Authorization": "Bearer " + bundle["token"]}

    client = None
    try:
        client, reg = await start_core(core)
        alice_headers = await login(client, "alice", "synthetic-alice-password")
        bob_headers = await login(client, "bob", "synthetic-bob-password")
        base = "/open-android-intelligence/v2/agents/hermes/sessions"
        response = await client.post(base, headers={**alice_headers, "Idempotency-Key": "alice-create"},
                                     json={"title": "synthetic session"})
        assert response.status == 200, await response.text()
        session = (await response.json())["session"]
        response = await client.post(base + "/" + session["id"] + "/messages",
            headers={**alice_headers, "Idempotency-Key": "alice-send"},
            json={"text": "synthetic prompt", "clientMessageId": "synthetic-turn"})
        assert response.status == 200, await response.text()
        assert "synthetic native reply" in await response.text()
        alice_native = reg.mappings("hermes")[alice["accountId"]]
        async def signed_get(path, extra=None):
            extra = extra or {}
            headers = reg.automatic_headers("hermes", alice["accountId"], alice_native, "GET",
                                            "/oai/session-adapter/v1" + path, None, extra)
            from aiohttp import ClientSession
            async with ClientSession() as session_client:
                async with session_client.get(bridge_url + "/oai/session-adapter/v1" + path,
                        headers={"Authorization": "Bearer " + token, "X-OAI-Account": alice_native, **extra, **headers}) as result:
                    return result.status
        assert await signed_get("/sessions/" + session["id"] + "/generation?clientMessageId=synthetic-turn") == 200
        assert await signed_get("/sessions/" + session["id"] + "/events?limit=1&waitMs=0",
                                {"X-OAI-Observer": "a" * 64}) == 200
        response = await client.get(base + "/" + session["id"] + "/history", headers=bob_headers)
        assert response.status in {403, 404}, await response.text()
        assert reg.mappings("hermes")[alice["accountId"]] != reg.mappings("hermes")[bob["accountId"]]
        binding = reg.mappings("hermes")
        await client.close()
        client = None
        core.identity_store.close()
        await runtime.disconnect()
        backend = SessionBackend(hermes_root, [], secret_store=LocalMasterKeyStore(hermes_master))
        runtime = SessionOnlyAdapter(PlatformConfig(enabled=True, extra={}), backend, token, [], port)
        runtime.set_session_store(SessionStore(home / "sessions", GatewayConfig()))
        runtime.set_message_handler(synthetic_handler)
        runtime.auto_spaces = AutoAccountSpaces(hermes_root, backend, {core_id: public})
        assert await runtime.connect()
        assert runtime._site._server.sockets[0].getsockname()[1] == port
        assert set(backend.list_gateway_account_ids()) == set(binding.values())
        core = create_core(config)
        client, restarted = await start_core(core)
        alice_headers = await login(client, "alice", "synthetic-alice-password")
        response = await client.get(base, headers=alice_headers)
        assert response.status == 200, await response.text()
        assert session["id"] in [row["id"] for row in (await response.json())["sessions"]]
        response = await client.get(base + "/" + session["id"] + "/history", headers=alice_headers)
        assert response.status == 200, await response.text()
        assert restarted.mappings("hermes") == binding
    finally:
        if client is not None:
            await client.close()
        if core.identity_store is not None:
            core.identity_store.close()
        await runtime.disconnect()
