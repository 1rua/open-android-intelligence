"""Installed identity + real HTTP Core, no Agent or preselected phone required."""
import base64
import os
from pathlib import Path
import time

import pytest
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.twofactor.totp import TOTP

from oai_core.identity import IdentityStore, issue_bootstrap_proof
from oai_core.management import LocalRecovery
from oai_core.server import AUTH_KEY, DEVICE_API, create_app, create_core
from oai_core.sessions import AgentRegistry

CONTRACT = Path(__file__).resolve().parents[2] / "gateway-contract"


@pytest.fixture
async def modern_core(tmp_path):
    storage = tmp_path / "runtime/accounts"
    path = tmp_path / "runtime/security/identity.sqlite"
    read, write = os.pipe()
    os.write(write, b"bootstrap\n")
    os.close(write)
    try:
        proof = issue_bootstrap_proof(path, storage, confirmation_fd=read)
    finally:
        os.close(read)
    store = IdentityStore(path, create=True, bootstrap_proof=proof)
    admin = store.bootstrap("admin", "synthetic-password", confirmed=True)
    config = {"storageRoot": str(storage), "masterKeyFile": str(tmp_path / "runtime/secrets/key"), "contractRoot": str(CONTRACT)}
    core = create_core(config)
    store.key_store = core.secret_store
    app = create_app(config, registry=AgentRegistry(), core=core)
    async with TestClient(TestServer(app)) as client:
        yield client, core, app, store, admin
    store.close()
    core.identity_store.close()


@pytest.mark.asyncio
async def test_no_device_login_list_refresh_and_logout_over_http(modern_core):
    client, core, app, identity, admin = modern_core
    response = await client.post(DEVICE_API + "/tokens", json={"username": "admin", "password": "synthetic-password"})
    assert response.status == 200, await response.text()
    first = await response.json()
    headers = {"Authorization": "Bearer " + first["token"]}
    response = await client.get(DEVICE_API + "/devices", headers=headers)
    assert response.status == 200 and (await response.json())["devices"] == []
    response = await client.get(DEVICE_API + "/capabilities", headers=headers)
    assert (await response.json())["error"]["code"] == "NO_DEVICES"
    body = {"refreshCredential": first["refreshCredential"], "clientId": first["clientId"], "operationId": "refresh-once"}
    response = await client.post(DEVICE_API + "/tokens/refresh", json=body)
    assert response.status == 200, await response.text()
    second = await response.json()
    assert (await (await client.post(DEVICE_API + "/tokens/refresh", json=body)).json()) == second
    response = await client.post(DEVICE_API + "/tokens/revoke", json={"refreshCredential": first["refreshCredential"], "clientId": first["clientId"]})
    assert response.status == 200 and await response.json() == {"revoked": True}
    response = await client.get(DEVICE_API + "/devices", headers={"Authorization": "Bearer " + second["token"]})
    assert response.status == 401


@pytest.mark.asyncio
async def test_server_enabled_mfa_enrollment_is_not_full_login(modern_core):
    client, core, app, identity, admin = modern_core
    handle = identity.begin_management("admin", "synthetic-password")
    bob = identity.create_account(handle, "bob", "synthetic-bob-password")
    proof = identity.authenticate("admin", "synthetic-password", purpose="enroll")["enrollmentProof"]
    setup = identity.begin_mfa_enrollment(admin["accountId"], proof)
    raw = base64.b32decode(setup["secret"] + "=" * (-len(setup["secret"]) % 8))
    code = TOTP(raw, 6, hashes.SHA1(), 30).generate(time.time()).decode()
    identity.confirm_mfa_enrollment(admin["accountId"], proof, code, enrollment_id=setup["enrollmentId"])
    handle = identity.begin_management("admin", "synthetic-password")
    LocalRecovery(identity).register(handle, confirmed=True)
    identity.set_mfa_required(handle, True)
    response = await client.post(DEVICE_API + "/tokens", json={"username": "bob", "password": "synthetic-bob-password"})
    assert response.status == 401, await response.text()
    error = (await response.json())["error"]
    assert error["code"] == "MFA_ENROLLMENT_REQUIRED"
    challenge, client_id = error["details"]["challengeId"], error["details"]["clientId"]
    response = await client.post(DEVICE_API + "/mfa/enroll", json={"challengeId": challenge})
    assert response.status == 200, await response.text()
    enrollment = await response.json()
    raw = base64.b32decode(enrollment["secret"] + "=" * (-len(enrollment["secret"]) % 8))
    code = TOTP(raw, 6, hashes.SHA1(), 30).generate(time.time()).decode()
    response = await client.post(DEVICE_API + "/mfa/confirm", json={"challengeId": challenge, "enrollmentId": enrollment["enrollmentId"], "otp": code})
    assert response.status == 200, await response.text()
    assert (await response.json())["recoveryCodes"]
    response = await client.post(DEVICE_API + "/tokens/complete", json={"challengeId": challenge, "clientId": client_id})
    assert response.status == 200, await response.text()
    bundle = await response.json()
    assert bundle["accountId"] == bob["accountId"]
    response = await client.post(DEVICE_API + "/tokens/complete", json={"challengeId": challenge, "clientId": client_id})
    assert response.status == 401
