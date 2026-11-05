"""Account sessions exercise the real identity authority without Android devices."""
from __future__ import annotations

import asyncio
import base64
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import sqlite3
from types import SimpleNamespace

import pytest
from aiohttp.test_utils import make_mocked_request
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.twofactor.totp import TOTP

from oai_core.auth import Authentication
from oai_core.gateway.core import GatewayError
from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
from oai_core.identity import IdentityStore, issue_bootstrap_proof
from oai_core.management import LocalRecovery


@pytest.fixture
def auth_fixture(tmp_path: Path, monkeypatch):
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    master = create_master_key_file(tmp_path / "keys" / "master")
    keys = LocalMasterKeyStore(master)
    database = tmp_path / "runtime" / "security" / "identity.sqlite"
    storage = tmp_path / "runtime" / "accounts"
    read_fd, write_fd = os.pipe()
    try:
        os.write(write_fd, b"bootstrap\n")
        proof = issue_bootstrap_proof(database, storage, confirmation_fd=read_fd)
    finally:
        os.close(read_fd)
        os.close(write_fd)
    identity = IdentityStore(database, key_store=keys, create=True, bootstrap_proof=proof)
    account = identity.bootstrap("alice", "test-only-password", confirmed=True)
    core = SimpleNamespace(identity_store=identity, secret_store=keys)
    auth = Authentication(core, tmp_path / "runtime" / "core.sqlite")
    try:
        yield auth, identity, account, tmp_path
    finally:
        auth.close()
        identity.close()


@pytest.mark.asyncio
async def test_password_login_without_device_or_two_factor(auth_fixture):
    auth, _, account, _ = auth_fixture
    bundle = await auth.async_login({"username": "alice", "password": "test-only-password"})
    assert bundle["accountId"] == account["accountId"]
    assert bundle["token"] == bundle["accessToken"]
    assert bundle["refreshCredential"] and bundle["loginSessionId"] and bundle["clientId"]
    assert bundle["clientProof"]
    remaining = (datetime.fromisoformat(bundle["expiresAt"]) - datetime.now(timezone.utc)).total_seconds()
    assert 890 < remaining <= 900
    request = make_mocked_request("GET", "/", headers={"Authorization": "Bearer " + bundle["token"]})
    principal = await auth.authenticate(request)
    assert principal.account_id == account["accountId"]
    assert principal.device_id is None
    assert principal.client_id == bundle["clientId"]
    assert principal.login_session_id == bundle["loginSessionId"]


def refresh_input(bundle, operation="op-1"):
    return {"refreshCredential": bundle["refreshCredential"], "clientId": bundle["clientId"], "operationId": operation}


def request_for(bundle):
    return make_mocked_request("GET", "/", headers={"Authorization": "Bearer " + bundle["token"]})


@pytest.mark.asyncio
async def test_refresh_replay_survives_restart_and_logout_with_old_credential(auth_fixture):
    auth, _, _, tmp_path = auth_fixture
    original = auth.login({"username": "alice", "password": "test-only-password"})
    old = refresh_input(original)
    rotated = auth.refresh(old)
    assert rotated["refreshCredential"] != original["refreshCredential"]
    assert rotated["loginSessionId"] == original["loginSessionId"]
    assert auth.refresh(old) == rotated
    other = Authentication(auth.core, tmp_path / "runtime" / "core.sqlite")
    try:
        assert other.refresh(old) == rotated
        principal = await other.authenticate(request_for(rotated))
        other.revoke_refresh({"refreshCredential": original["refreshCredential"], "clientId": original["clientId"]})
        other.revoke_refresh({"refreshCredential": original["refreshCredential"], "clientId": original["clientId"]})
        with pytest.raises(GatewayError):
            other.assert_login_session(original["accountId"], original["loginSessionId"])
        with pytest.raises(GatewayError):
            other.assert_current(principal)
        with pytest.raises(GatewayError):
            other.refresh(old)
    finally:
        other.close()


@pytest.mark.asyncio
async def test_different_refresh_operation_revokes_family(auth_fixture):
    auth, _, _, _ = auth_fixture
    first = auth.login({"username": "alice", "password": "test-only-password"})
    second = auth.refresh(refresh_input(first))
    with pytest.raises(GatewayError) as failure:
        auth.refresh(refresh_input(first, "another-op"))
    assert failure.value.code == "REFRESH_REPLAY_DETECTED"
    with pytest.raises(GatewayError):
        await auth.authenticate(request_for(second))
    with pytest.raises(GatewayError):
        auth.refresh(refresh_input(second, "next-op"))


@pytest.mark.asyncio
async def test_password_change_invalidates_access_refresh_and_receipt(auth_fixture):
    auth, identity, _, _ = auth_fixture
    first = auth.login({"username": "alice", "password": "test-only-password"})
    rotated = auth.refresh(refresh_input(first))
    principal = await auth.authenticate(request_for(rotated))
    manager = identity.begin_management("alice", "test-only-password")
    identity.reset_password(manager, "alice", "replacement-password")
    for action in (lambda: auth.assert_current(principal),
                   lambda: auth.assert_login_session(first["accountId"], first["loginSessionId"]),
                   lambda: auth.refresh(refresh_input(first)), lambda: auth.refresh(refresh_input(rotated, "next"))):
        with pytest.raises(GatewayError):
            action()


def test_no_identity_store_never_accepts_device_free_login(tmp_path):
    auth = Authentication(SimpleNamespace(), tmp_path / "legacy.sqlite")
    try:
        with pytest.raises(GatewayError) as failure:
            auth.login({"username": "alice", "password": "test-only-password"})
        assert failure.value.code == "IDENTITY_DATABASE_MISSING"
    finally:
        auth.close()


def test_client_scope_requires_ownership_proof_on_relogin(auth_fixture):
    auth, _, _, _ = auth_fixture
    first = auth.login({"username": "alice", "password": "test-only-password", "clientId": "my-client"})
    for proof in (None, "wrong"):
        attempt = {"username": "alice", "password": "test-only-password", "clientId": "my-client"}
        if proof is not None:
            attempt["clientProof"] = proof
        with pytest.raises(GatewayError) as failure:
            auth.login(attempt)
        assert failure.value.code == "CLIENT_PROOF_REQUIRED"
    again = auth.login({"username": "alice", "password": "test-only-password", "clientId": "my-client",
                        "clientProof": first["clientProof"]})
    assert again["clientId"] == first["clientId"]
    assert again["loginSessionId"] != first["loginSessionId"]


def test_mfa_is_required_only_when_the_server_enables_it(auth_fixture):
    auth, identity, account, _ = auth_fixture
    verified = identity.authenticate("alice", "test-only-password", purpose="enroll", client_id="setup")
    challenge = verified["enrollmentProof"]
    enrollment = identity.begin_mfa_enrollment(account["accountId"], challenge)
    encoded = enrollment["secret"]
    secret = base64.b32decode(encoded + "=" * (-len(encoded) % 8))
    otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(time.time()).decode()
    recovery_codes = identity.confirm_mfa_enrollment(account["accountId"], challenge, otp, enrollment_id=enrollment["enrollmentId"])["recoveryCodes"]
    manager = identity.begin_management("alice", "test-only-password")
    # Enrollment alone does not make 2FA mandatory.
    assert auth.login({"username": "alice", "password": "test-only-password"})["token"]
    old_session = auth.login({"username": "alice", "password": "test-only-password"})
    LocalRecovery(identity).register(manager, confirmed=True)
    identity.set_mfa_required(manager, True)
    with pytest.raises(GatewayError):
        auth.refresh(refresh_input(old_session))
    with pytest.raises(GatewayError) as failure:
        auth.login({"username": "alice", "password": "test-only-password"})
    assert failure.value.code == "MFA_REQUIRED"
    details = failure.value.details
    assert details["challengeId"] and details["clientId"] and details["expiresAt"]
    assert details["methods"] == ["totp", "recovery-code"]
    # Use an unused recovery factor; refresh does not ask for it again.
    result = auth.complete_login({"challengeId": details["challengeId"], "clientId": details["clientId"],
                                  "recoveryCode": recovery_codes[0]})
    assert result["clientProof"] and auth.refresh(refresh_input(result))["token"]
    with pytest.raises(GatewayError):
        auth.complete_login({"challengeId": details["challengeId"], "clientId": details["clientId"],
                             "recoveryCode": recovery_codes[0]})


def test_simultaneous_same_refresh_is_exactly_idempotent(auth_fixture):
    auth, _, _, tmp_path = auth_fixture
    first = auth.login({"username": "alice", "password": "test-only-password"})
    core = auth.core

    def run():
        worker = Authentication(core, tmp_path / "runtime" / "core.sqlite")
        try:
            return worker.refresh(refresh_input(first))
        finally:
            worker.close()

    with ThreadPoolExecutor(max_workers=2) as pool:
        a, b = list(pool.map(lambda _: run(), range(2)))
    assert a == b


def test_concurrent_construction_migrates_old_token_table_once(auth_fixture, tmp_path):
    auth, _, _, _ = auth_fixture
    path = tmp_path / "old-concurrent.sqlite"
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE core_tokens (digest TEXT PRIMARY KEY, account_id TEXT NOT NULL, device_id TEXT NOT NULL, generation INTEGER NOT NULL, revision INTEGER NOT NULL, expires REAL NOT NULL)")
    db.close()
    ready = threading.Barrier(2)

    def create(_):
        ready.wait()
        worker = Authentication(auth.core, path)
        try:
            return {row[1] for row in worker.database.execute("PRAGMA table_info(core_tokens)")}
        finally:
            worker.close()

    with ThreadPoolExecutor(max_workers=2) as pool:
        columns = list(pool.map(create, range(2)))
    assert all({"auth_epoch", "credential_digest"} <= item for item in columns)


def test_device_bound_login_requires_upgrade_when_mfa_is_enabled(auth_fixture):
    auth, identity, account, _ = auth_fixture
    proof = identity.authenticate("alice", "test-only-password", purpose="enroll", client_id="setup")["enrollmentProof"]
    enrollment = identity.begin_mfa_enrollment(account["accountId"], proof)
    secret = base64.b32decode(enrollment["secret"] + "=" * (-len(enrollment["secret"]) % 8))
    otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(time.time()).decode()
    codes = identity.confirm_mfa_enrollment(account["accountId"], proof, otp, enrollment_id=enrollment["enrollmentId"])["recoveryCodes"]
    manager = identity.begin_management("alice", "test-only-password")
    LocalRecovery(identity).register(manager, confirmed=True)
    identity.set_mfa_required(manager, True)
    with pytest.raises(GatewayError) as required:
        auth.login({"username": "alice", "password": "test-only-password", "deviceId": "device-a", "clientId": "client"})
    assert required.value.code == "ACCOUNT_LOGIN_UPGRADE_REQUIRED"
    # Reject a legacy device login even if it attempts to skip the challenge by
    # attaching a recovery factor to the initial password request.
    with pytest.raises(GatewayError) as rejected:
        auth.login({"username": "alice", "password": "test-only-password", "deviceId": "device-a",
                    "clientId": "client", "recoveryCode": codes[0]})
    assert rejected.value.code == "ACCOUNT_LOGIN_UPGRADE_REQUIRED"


def test_completion_rejects_an_unbound_device_id(auth_fixture):
    auth, _, _, _ = auth_fixture
    with pytest.raises(GatewayError) as rejected:
        auth.complete_login({"challengeId": "challenge", "clientId": "client", "deviceId": "device-b"})
    assert rejected.value.code == "SCHEMA_INVALID"


def test_enrollment_required_challenge_completes_after_enrollment(auth_fixture):
    auth, identity, _, _ = auth_fixture
    manager = identity.begin_management("alice", "test-only-password")
    bob = identity.create_account(manager, "bob", "bob-test-password")
    proof = identity.authenticate("alice", "test-only-password", purpose="enroll", client_id="setup")["enrollmentProof"]
    enrollment = identity.begin_mfa_enrollment(identity.resolve_username("alice"), proof)
    secret = base64.b32decode(enrollment["secret"] + "=" * (-len(enrollment["secret"]) % 8))
    otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(time.time()).decode()
    identity.confirm_mfa_enrollment(identity.resolve_username("alice"), proof, otp, enrollment_id=enrollment["enrollmentId"])
    manager = identity.begin_management("alice", "test-only-password")
    LocalRecovery(identity).register(manager, confirmed=True)
    identity.set_mfa_required(manager, True)
    with pytest.raises(GatewayError) as failure:
        auth.login({"username": "bob", "password": "bob-test-password"})
    assert failure.value.code == "MFA_ENROLLMENT_REQUIRED"
    details = failure.value.details
    assert details["challengeId"] and details["clientId"] and details["expiresAt"] and details["methods"]
    enrollment = identity.begin_mfa_enrollment(bob["accountId"], details["challengeId"])
    secret = base64.b32decode(enrollment["secret"] + "=" * (-len(enrollment["secret"]) % 8))
    otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(time.time()).decode()
    identity.confirm_mfa_enrollment(bob["accountId"], details["challengeId"], otp, enrollment_id=enrollment["enrollmentId"])
    bundle = auth.complete_login({"challengeId": details["challengeId"], "clientId": details["clientId"]})
    assert bundle["accountId"] == bob["accountId"] and bundle["clientProof"]


def test_exact_old_refresh_retry_never_returns_an_already_used_successor(auth_fixture):
    auth, _, _, _ = auth_fixture
    first = auth.login({"username": "alice", "password": "test-only-password"})
    second = auth.refresh(refresh_input(first, "one"))
    third = auth.refresh(refresh_input(second, "two"))
    with pytest.raises(GatewayError):
        auth.refresh(refresh_input(first, "one"))
    assert auth.refresh(refresh_input(third, "three"))["token"]


def test_missing_receipt_key_does_not_consume_refresh_credential(auth_fixture):
    auth, _, _, _ = auth_fixture
    bundle = auth.login({"username": "alice", "password": "test-only-password"})
    keys = auth.core.secret_store
    auth.core.secret_store = None
    try:
        with pytest.raises(GatewayError) as failure:
            auth.refresh(refresh_input(bundle))
        assert failure.value.code == "REFRESH_KEY_UNAVAILABLE"
    finally:
        auth.core.secret_store = keys
    assert auth.refresh(refresh_input(bundle))["refreshCredential"] != bundle["refreshCredential"]


def test_refresh_rejects_a_different_client_without_revoking(auth_fixture):
    auth, _, _, _ = auth_fixture
    bundle = auth.login({"username": "alice", "password": "test-only-password"})
    request = refresh_input(bundle)
    request["clientId"] = "other-client"
    with pytest.raises(GatewayError):
        auth.refresh(request)
    with pytest.raises(GatewayError):
        auth.revoke_refresh({"refreshCredential": bundle["refreshCredential"], "clientId": "other-client"})
    assert auth.refresh(refresh_input(bundle))["token"]


def test_expired_access_is_distinguishable_from_revoked_family(auth_fixture, monkeypatch):
    auth, _, _, _ = auth_fixture
    bundle = auth.login({"username": "alice", "password": "test-only-password"})
    now = time.time()
    monkeypatch.setattr("oai_core.account_sessions.time.time", lambda: now + 901)
    with pytest.raises(GatewayError) as failure:
        asyncio.run(auth.authenticate(request_for(bundle)))
    assert failure.value.code == "SESSION_EXPIRED"
    auth.assert_login_session(bundle["accountId"], bundle["loginSessionId"])
    principal = auth.principal_for_login_session(bundle["accountId"], bundle["loginSessionId"])
    auth.assert_current(principal)
    assert auth.refresh(refresh_input(bundle))["token"]


def test_refresh_receipts_are_encrypted_and_expire_without_revoking_family(auth_fixture, monkeypatch):
    auth, _, _, tmp_path = auth_fixture
    first = auth.login({"username": "alice", "password": "test-only-password"})
    second = auth.refresh(refresh_input(first))
    now = time.time()
    database_bytes = (tmp_path / "runtime" / "core.sqlite").read_bytes()
    assert first["refreshCredential"].encode() not in database_bytes
    assert second["refreshCredential"].encode() not in database_bytes
    assert second["token"].encode() not in database_bytes
    monkeypatch.setattr("oai_core.account_sessions.time.time", lambda: now + 901)
    with pytest.raises(GatewayError):
        auth.refresh(refresh_input(first))
    assert auth.refresh(refresh_input(second, "next"))["token"]


def test_clock_rollback_does_not_extend_refresh_receipt(auth_fixture, monkeypatch):
    auth, _, _, _ = auth_fixture
    bundle = auth.login({"username": "alice", "password": "test-only-password"})
    auth.refresh(refresh_input(bundle))
    now = time.time()
    monkeypatch.setattr("oai_core.account_sessions.time.time", lambda: now - 60)
    with pytest.raises(GatewayError):
        auth.refresh(refresh_input(bundle))


def test_existing_legacy_schema_is_migrated_without_granting_old_tokens(auth_fixture, tmp_path):
    auth, _, _, _ = auth_fixture
    old = tmp_path / "legacy.sqlite"
    db = sqlite3.connect(old)
    db.execute("CREATE TABLE core_tokens (digest TEXT PRIMARY KEY, account_id TEXT NOT NULL, device_id TEXT NOT NULL, generation INTEGER NOT NULL, revision INTEGER NOT NULL, expires REAL NOT NULL)")
    db.execute("INSERT INTO core_tokens VALUES (?,?,?,?,?,?)", ("legacy", "alice", "device", 1, 1, time.time() + 900))
    db.commit()
    db.close()
    migrated = Authentication(auth.core, old)
    try:
        row = migrated.database.execute("SELECT * FROM core_tokens WHERE digest='legacy'").fetchone()
        assert row["auth_epoch"] is None and row["credential_digest"] is None
        assert (row["account_id"], row["device_id"], row["generation"]) == ("alice", "device", 1)
    finally:
        migrated.close()
