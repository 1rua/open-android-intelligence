"""Real Core account-login extension, signatures, stable registration and MFA."""
import base64
import json
import os
import time
import hashlib

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.twofactor.totp import TOTP
from oai_core.management import LocalRecovery
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from oai_core.android_auth import AndroidAccountAuthentication
from oai_core.gateway.core import GatewayError, _jcs
from oai_core.gateway.local_keys import LocalMasterKeyStore
from oai_core.identity import IdentityStore, issue_bootstrap_proof
from oai_core.server import create_core

CONTRACT = Path(__file__).resolve().parents[2] / "gateway-contract"


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


@pytest.fixture
def provisioned(tmp_path):
    storage = tmp_path / "runtime/accounts"
    identity_path = tmp_path / "runtime/security/identity.sqlite"
    read, write = os.pipe()
    os.write(write, b"bootstrap\n")
    os.close(write)
    try:
        proof = issue_bootstrap_proof(identity_path, storage, confirmation_fd=read)
    finally:
        os.close(read)
    identity = IdentityStore(identity_path, create=True, bootstrap_proof=proof)
    admin = identity.bootstrap("admin", "synthetic-only-password", confirmed=True)
    settings = {"storageRoot": str(storage), "masterKeyFile": str(tmp_path / "runtime/secrets/key"),
                "contractRoot": str(CONTRACT)}
    core = create_core(settings)
    identity.key_store = core.secret_store
    try:
        yield core, identity, admin
    finally:
        identity.close()
        core.identity_store.close()


def begin(core, service, key, existing=None):
    negotiation = json.loads((CONTRACT / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    negotiation["negotiationId"] = "neg_" + os.urandom(8).hex()
    negotiation["schemaHashes"]["core"] = core.contracts.core_schema_hash
    response = core._handle_pre_auth({"body": negotiation, "method": "POST", "target": "/open-android-intelligence/v2/negotiate"})
    assert "error" not in response
    body = {"negotiationId": negotiation["negotiationId"], "username": "admin", "password": "synthetic-only-password",
            "installation": {"installationId": negotiation["client"]["installationId"], "displayName": "test phone",
                             "devicePublicKey": b64(key.public_key().public_bytes_raw())}, **(existing or {})}
    return service.challenge(body)


def finish(service, facts, key, **extra):
    return service.complete({"challengeId": facts["challengeId"], "negotiationId": facts["negotiationId"],
        "signature": b64(key.sign(b"OPEN_ANDROID_INTELLIGENCE_ACCOUNT_LOGIN_V1\n" + _jcs(facts).encode())), **extra})


def test_account_login_registers_and_reuses_only_proven_device(provisioned):
    core, identity, admin = provisioned
    service = AndroidAccountAuthentication(core)
    key = Ed25519PrivateKey.generate()
    facts = begin(core, service, key)
    assert facts["methods"] == [] and facts["enrollmentRequired"] is False
    first = finish(service, facts, key)
    again = begin(core, service, key, {"accountId": first["accountId"], "deviceId": first["deviceId"]})
    second = finish(service, again, key)
    assert first["deviceId"] == second["deviceId"]
    assert first["pairingGeneration"] == second["pairingGeneration"]
    with pytest.raises(GatewayError):
        finish(service, again, key)


def test_wrong_private_key_cannot_complete_or_reuse_device(provisioned):
    core, _, _ = provisioned
    service = AndroidAccountAuthentication(core)
    key = Ed25519PrivateKey.generate()
    facts = begin(core, service, key)
    with pytest.raises(GatewayError, match="AUTHENTICATION_FAILED"):
        finish(service, facts, Ed25519PrivateKey.generate())
    first = finish(service, facts, key)
    with pytest.raises(GatewayError, match="DEVICE_ACCESS_DENIED"):
        begin(core, service, Ed25519PrivateKey.generate(), {"accountId": first["accountId"], "deviceId": first["deviceId"]})


def test_password_change_fences_pending_android_login(provisioned):
    core, identity, _ = provisioned
    service = AndroidAccountAuthentication(core)
    key = Ed25519PrivateKey.generate()
    facts = begin(core, service, key)
    management = identity.begin_management("admin", "synthetic-only-password")
    identity.reset_password(management, "admin", "different-synthetic-password")
    with pytest.raises(GatewayError, match="AUTHENTICATION_EXPIRED"):
        finish(service, facts, key)


def test_server_mfa_required_and_enrollment_round_trip(provisioned):
    core, identity, admin = provisioned
    proof = identity.authenticate("admin", "synthetic-only-password", purpose="enroll")["enrollmentProof"]
    setup = identity.begin_mfa_enrollment(admin["accountId"], proof)
    secret = base64.b32decode(setup["secret"] + "=" * (-len(setup["secret"]) % 8))
    otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(time.time()).decode()
    codes = identity.confirm_mfa_enrollment(admin["accountId"], proof, otp, enrollment_id=setup["enrollmentId"])["recoveryCodes"]
    handle = identity.begin_management("admin", "synthetic-only-password")
    LocalRecovery(identity).register(handle, confirmed=True)
    identity.set_mfa_required(handle, True)
    service = AndroidAccountAuthentication(core)
    key = Ed25519PrivateKey.generate()
    facts = begin(core, service, key)
    assert facts["methods"] == ["totp", "recovery-code"]
    wrong = "000000"
    for _ in range(5):
        with pytest.raises(GatewayError, match="AUTHENTICATION_FAILED"):
            finish(service, facts, key, otp=wrong)
    proof = service._tickets[hashlib.sha256(facts["challengeId"].encode()).hexdigest()]["proof"]
    failed = identity.database.execute("SELECT failed FROM challenges WHERE digest=?", (hashlib.sha256(proof.encode()).hexdigest(),)).fetchone()[0]
    assert failed == 5
    with pytest.raises(GatewayError, match="AUTHENTICATION_FAILED"):
        finish(service, facts, key, recoveryCode=codes[0])
    fresh = begin(core, service, key)
    assert finish(service, fresh, key, recoveryCode=codes[0])["accountId"] == admin["accountId"]


def test_android_refresh_operation_replays_after_response_loss(provisioned):
    core, _, _ = provisioned
    service = AndroidAccountAuthentication(core)
    key = Ed25519PrivateKey.generate()
    facts = begin(core, service, key)
    first = finish(service, facts, key)
    request = {"method": "POST", "target": "/open-android-intelligence/v2/sessions/refresh",
        "headers": {"Idempotency-Key": "refresh-operation"}, "body": {"negotiationId": facts["negotiationId"],
        "accountId": first["accountId"], "deviceId": first["deviceId"], "installationId": facts["installationId"],
        "refreshCredential": first["refreshCredential"]}}
    rotated = core._handle_session_refresh(request)
    assert "error" not in rotated, rotated
    again = core._handle_session_refresh(request)
    assert again["data"] == rotated["data"]
    assert rotated["data"]["refreshCredential"] != first["refreshCredential"]
