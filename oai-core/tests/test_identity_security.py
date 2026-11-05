"""Isolated identity, optional MFA and local management regressions."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import secrets
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.twofactor.totp import TOTP
import pytest

from oai_core.gateway.account_paths import sha256_hex
from oai_core.gateway.core import GatewayError
from oai_core.gateway.credentials import hash_password
from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
from oai_core.identity import IdentityStore, issue_bootstrap_proof
from oai_core.instance import discover_instance, register_instance
from oai_core.management import LocalRecovery, read_secret_fd, require_management_fd


@pytest.fixture
def tmp_path():
    # Preserve synthetic fixtures for review; pytest's built-in tmp_path removes
    # older runs, which is inappropriate for this workspace's safety policy.
    parent = Path(__file__).parents[2] / ".worktrees" / "identity-test-data"
    parent.mkdir(parents=True, exist_ok=True)
    target = parent / secrets.token_hex(16)
    target.mkdir(mode=0o700)
    return target


def fails(code, fn, *args, **kwargs):
    with pytest.raises(GatewayError) as caught:
        fn(*args, **kwargs)
    assert caught.value.code == code
    return caught.value


def channel(value):
    read, write = os.pipe()
    os.write(write, (value + "\n").encode())
    os.close(write)
    return read


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    database = runtime / "security" / "identity.sqlite"
    root = runtime / "accounts"
    key = runtime / "key"
    create_master_key_file(key)
    fd = channel("bootstrap")
    try:
        proof = issue_bootstrap_proof(database, root, confirmation_fd=fd)
    finally:
        os.close(fd)
    store = IdentityStore(database, key_store=LocalMasterKeyStore(key), create=True, bootstrap_proof=proof)
    account = store.bootstrap("admin", "admin-password", confirmed=True)
    yield store, account, tmp_path
    store.close()


def challenge(store, username, password, *, purpose="login", client_id=None):
    with pytest.raises(GatewayError) as raised:
        store.authenticate(username, password, purpose=purpose, client_id=client_id)
    assert raised.value.code in {"MFA_REQUIRED", "MFA_ENROLLMENT_REQUIRED"}
    return raised.value.details["challengeId"]


def enroll(store, account, username, password, now):
    required = store._policy()[0]
    if required:
        proof = challenge(store, username, password, purpose="login", client_id="test")
        purpose, client = "login", "test"
    else:
        proof = store.authenticate(username, password, purpose="enroll")["enrollmentProof"]
        purpose, client = "enroll", None
    pending = store.begin_mfa_enrollment(account["accountId"], proof)
    secret = base64.b32decode(pending["secret"] + "=" * (-len(pending["secret"]) % 8))
    otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(now).decode()
    confirmed = store.confirm_mfa_enrollment(account["accountId"], proof, otp, enrollment_id=pending["enrollmentId"])
    signed_in = store.complete_authentication(proof, purpose=purpose, client_id=client)
    assert signed_in["mfaVerified"] is True
    return secret, confirmed["recoveryCodes"]


def prepare_enabled(env, monkeypatch, now=2_000_000_010):
    store, admin, _ = env
    monkeypatch.setattr("oai_core.identity.time.time", lambda: now)
    secret, codes = enroll(store, admin, "admin", "admin-password", now)
    handle = store.begin_management("admin", "admin-password")
    local = LocalRecovery(store).register(handle, confirmed=True)["recoveryCodes"]
    store.set_mfa_required(handle, True)
    return secret, codes, local


def test_password_derivation_does_not_hold_identity_lock(env, monkeypatch):
    store, admin, _ = env
    started, release = threading.Event(), threading.Event()
    original = __import__("oai_core.identity", fromlist=["verify_password"]).verify_password

    def paused(password, digest):
        started.set()
        assert release.wait(5)
        return original(password, digest)

    monkeypatch.setattr("oai_core.identity.verify_password", paused)
    with ThreadPoolExecutor(max_workers=2) as workers:
        pending = workers.submit(store.authenticate, "admin", "admin-password")
        assert started.wait(5)
        try:
            current = workers.submit(store.account, admin["accountId"])
            assert current.result(timeout=1)["accountId"] == admin["accountId"]
        finally:
            release.set()
        assert pending.result(timeout=5)["accountId"] == admin["accountId"]


def test_bootstrap_is_one_time_and_fd_required(env):
    store, admin, home = env
    assert admin["role"] == "admin"
    assert store.authenticate("admin", "admin-password")["mfaVerified"] is False
    other = IdentityStore(home / "other" / "identity.sqlite", create=True)
    fails("BOOTSTRAP_PROOF_REQUIRED", other.bootstrap, "bob", "pass", confirmed=True)
    regular = home / "regular"
    regular.write_text("bootstrap\n")
    with regular.open() as stream:
        fails("BOOTSTRAP_CONFIRMATION_CHANNEL_INVALID", issue_bootstrap_proof,
              home / "db", home / "accounts", confirmation_fd=stream.fileno())
    assert not (home / "other").exists()


def test_default_policy_auth_management_scope_epoch_and_last_admin(env):
    store, admin, _ = env
    fails("AUTHENTICATION_FAILED", store.authenticate, "admin", "wrong")
    read = store.begin_management("admin", "admin-password", scopes=["accounts.read"])
    assert store.require_management(read, "accounts.read")["accountId"] == admin["accountId"]
    fails("MANAGEMENT_REQUIRED", store.create_account, read, "alice", "alice-password")
    handle = store.begin_management("admin", "admin-password")
    alice = store.create_account(handle, "alice", "alice-password")
    assert alice["role"] == "user" and alice["accountId"] != "alice"
    assert store.resolve_username("alice") == alice["accountId"]
    assert store.password_digest(alice["accountId"]).startswith("scrypt$")
    fails("ADMIN_REQUIRED", store.begin_management, "alice", "alice-password")
    fails("LAST_ADMIN_REQUIRED", store.delete_account, handle, "admin")
    store.set_role(handle, "alice", "admin")
    old_epoch = store.account(alice["accountId"])["authEpoch"]
    store.reset_password(handle, "alice", "new-password")
    fails("AUTHENTICATION_EXPIRED", store.assert_epoch, alice["accountId"], old_epoch)
    fails("AUTHENTICATION_FAILED", store.authenticate, "alice", "alice-password")
    assert store.authenticate("alice", "new-password")["role"] == "admin"
    store.delete_account(handle, "alice")
    replacement = store.create_account(handle, "alice", "another-password")
    assert replacement["accountId"] != alice["accountId"]
    store.revoke_management(read)
    fails("MANAGEMENT_REQUIRED", store.require_management, read, "accounts.read")


def test_management_expires_and_rejects_wall_clock_rollback(env, monkeypatch):
    store, _, _ = env
    monkeypatch.setattr("oai_core.identity.time.time", lambda: 1_000_000)
    monkeypatch.setattr("oai_core.identity.time.monotonic", lambda: 1000)
    handle = store.begin_management("admin", "admin-password")
    monkeypatch.setattr("oai_core.identity.time.monotonic", lambda: 1900)
    fails("MANAGEMENT_REQUIRED", store.require_management, handle, "config")
    monkeypatch.setattr("oai_core.identity.time.monotonic", lambda: 1001)
    monkeypatch.setattr("oai_core.identity.time.time", lambda: 999_999)
    fails("MANAGEMENT_REQUIRED", store.require_management, handle, "config")


def test_mfa_is_opt_in_then_enforces_bound_challenge_and_replay(env, monkeypatch):
    store, _, _ = env
    now = 2_000_000_010
    secret, codes, _ = prepare_enabled(env, monkeypatch, now)
    pending = challenge(store, "admin", "admin-password", client_id="phone")
    fails("AUTHENTICATION_FAILED", store.complete_authentication, pending, recovery_code=codes[0], client_id="other")
    result = store.complete_authentication(pending, recovery_code=codes[0], client_id="phone")
    assert result["mfaVerified"] is True
    fails("AUTHENTICATION_FAILED", store.complete_authentication, pending, recovery_code=codes[0], client_id="phone")
    pending = challenge(store, "admin", "admin-password", client_id="phone")
    fails("AUTHENTICATION_FAILED", store.complete_authentication, pending, recovery_code=codes[0], client_id="phone")
    monkeypatch.setattr("oai_core.identity.time.time", lambda: now + 30)
    otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(now + 30).decode()
    assert store.complete_authentication(pending, otp=otp, client_id="phone")["mfaVerified"]
    pending = challenge(store, "admin", "admin-password", client_id="phone")
    fails("AUTHENTICATION_FAILED", store.complete_authentication, pending, otp=otp, client_id="phone")


def test_enrollment_after_server_enables_requires_no_duplicate_otp(env, monkeypatch):
    store, _, _ = env
    now = 2_000_000_010
    _, admin_codes, _ = prepare_enabled(env, monkeypatch, now)
    handle = store.begin_management("admin", "admin-password", recovery_code=admin_codes[0])
    user = store.create_account(handle, "user", "pw")
    enroll(store, user, "user", "pw", now)
    assert store.account(user["accountId"])["mfaEnrolled"]


def test_wrong_codes_expire_and_revoke_on_password_change(env, monkeypatch):
    store, _, _ = env
    now = 2_000_000_010
    _, codes, _ = prepare_enabled(env, monkeypatch, now)
    pending = challenge(store, "admin", "admin-password")
    for _ in range(5):
        fails("AUTHENTICATION_FAILED", store.complete_authentication, pending, otp="000000")
    fails("AUTHENTICATION_FAILED", store.complete_authentication, pending, recovery_code=codes[0])
    expired = challenge(store, "admin", "admin-password")
    monkeypatch.setattr("oai_core.identity.time.time", lambda: now + 300)
    fails("AUTHENTICATION_EXPIRED", store.complete_authentication, expired, recovery_code=codes[0])
    monkeypatch.setattr("oai_core.identity.time.time", lambda: now)
    handle = store.begin_management("admin", "admin-password", recovery_code=codes[0])
    pending = challenge(store, "admin", "admin-password")
    store.reset_password(handle, "admin", "new-pass")
    fails("AUTHENTICATION_EXPIRED", store.complete_authentication, pending, recovery_code=codes[1])
    fails("MANAGEMENT_REQUIRED", store.require_management, handle, "security")


def test_recovery_registration_independent_single_use_and_rollback(env, monkeypatch):
    store, _, _ = env
    _, _, local = prepare_enabled(env, monkeypatch)
    recover = LocalRecovery(store)
    fails("AUTHENTICATION_FAILED", recover.reset_password, "admin", "wrong", "new-pass", confirmed=True)
    store.database.execute("CREATE TRIGGER fail_reset BEFORE UPDATE OF password_hash ON accounts BEGIN SELECT RAISE(ABORT,'fail'); END")
    with pytest.raises(sqlite3.IntegrityError):
        recover.reset_password("admin", local[0], "new-pass", confirmed=True)
    store.database.execute("DROP TRIGGER fail_reset")
    result = recover.reset_password("admin", local[0], "new-pass", confirmed=True)
    assert result["role"] == "admin" and store._policy()[0]
    fails("AUTHENTICATION_FAILED", recover.reset_password, "admin", local[0], "another", confirmed=True)
    assert challenge(store, "admin", "new-pass")
    connection = sqlite3.connect(recover.path)
    try:
        audit = connection.execute("SELECT action FROM audit_events ORDER BY id").fetchall()
    finally:
        connection.close()
    assert audit == [("recovery.registered",), ("recovery.password_reset",)]


def test_offline_readonly_missing_key_and_policy_fail_closed(env):
    store, _, home = env
    database = store.database_path
    before = database.read_bytes()
    result = IdentityStore.verify_offline_password(database, "admin", "admin-password")
    assert result["mfaVerified"] is False and database.read_bytes() == before
    fails("AUTHENTICATION_FAILED", IdentityStore.verify_offline_password, database, "admin", "bad")
    store.database.execute("UPDATE metadata SET value='broken' WHERE key='mfa_required'")
    fails("OFFLINE_MFA_OR_POLICY_UNAVAILABLE", IdentityStore.verify_offline_password, database, "admin", "admin-password")
    fails("IDENTITY_POLICY_INVALID", store.authenticate, "admin", "admin-password")
    fails("IDENTITY_DATABASE_MISSING", IdentityStore, home / "missing" / "identity.sqlite")
    assert not (home / "missing").exists()


def test_legacy_digest_migration_keeps_id_and_non_admin(tmp_path):
    root = tmp_path / "runtime" / "accounts"
    root.mkdir(parents=True)
    digests = {}
    for name in ("oldadmin", "olduser"):
        directory = root / sha256_hex(name)
        directory.mkdir()
        digest = digests[name] = hash_password(name + "-pw")
        db = sqlite3.connect(directory / "gateway.sqlite")
        db.executescript("CREATE TABLE account_metadata(key TEXT PRIMARY KEY,value TEXT); CREATE TABLE account_credentials(credential_id TEXT PRIMARY KEY,password_hash TEXT);")
        db.execute("INSERT INTO account_metadata VALUES ('gateway_account_id',?)", (name,))
        db.execute("INSERT INTO account_credentials VALUES ('password',?)", (digest,))
        db.commit()
        db.close()
    database = root.parent / "security" / "identity.sqlite"
    store = IdentityStore(database, create=True)
    fails("AUTHENTICATION_FAILED", store.migrate_legacy, root, "oldadmin", "bad", confirmed=True)
    assert not database.exists()
    migrated = store.migrate_legacy(root, "oldadmin", "oldadmin-pw", confirmed=True)
    assert migrated["accountId"] == "oldadmin" and migrated["role"] == "admin"
    assert store.authenticate("olduser", "olduser-pw")["role"] == "user"
    assert store.password_digest("oldadmin") == digests["oldadmin"]
    store.close()


def test_recovery_registry_rollback_cannot_reuse_consumed_admin_code(env):
    store, _, home = env
    handle = store.begin_management("admin", "admin-password")
    recovery = LocalRecovery(store)
    code = recovery.register(handle, confirmed=True)["recoveryCodes"][0]
    snapshot = home / "recovery-before-use.sqlite"
    with sqlite3.connect(recovery.path) as source, sqlite3.connect(snapshot) as target:
        source.backup(target)
    recovery.reset_password("admin", code, "replacement", confirmed=True)
    with sqlite3.connect(snapshot) as source, sqlite3.connect(recovery.path) as target:
        source.backup(target)
    fails("AUTHENTICATION_FAILED", recovery.reset_password, "admin", code, "attacker-password", confirmed=True)
    assert store.authenticate("admin", "replacement")["role"] == "admin"


def test_recovery_cli_accepts_only_independent_local_proof(env, monkeypatch, capsys):
    from oai_core.cli import main
    store, _, home = env
    handle = store.begin_management("admin", "admin-password")
    code = LocalRecovery(store).register(handle, confirmed=True)["recoveryCodes"][0]
    config = home / "core.json"
    config.write_text(json.dumps({"storageRoot": "runtime/accounts", "masterKeyFile": "runtime/key"}))
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    values = iter([code, "new-password", "new-password"])
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: next(values))
    monkeypatch.setattr("builtins.input", lambda _: "admin")
    assert main(["recover", "admin", "--config", str(config)]) == 0
    assert "recovered" in capsys.readouterr().out
    assert store.authenticate("admin", "new-password")["role"] == "admin"


def test_transaction_rolls_back_account_and_audit(env):
    store, _, _ = env
    handle = store.begin_management("admin", "admin-password")
    store.database.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON audit_events WHEN NEW.action='account.created' BEGIN SELECT RAISE(ABORT,'fail'); END")
    with pytest.raises(sqlite3.IntegrityError):
        store.create_account(handle, "not-created", "pw")
    fails("AUTHENTICATION_FAILED", store.resolve_username, "not-created")


def test_audit_scrubs_nested_mfa_secrets(env):
    store, _, _ = env
    store.audit("check", metadata={"safe": [{"otp": "secret1", "recoveryCode": "secret2", "nested": {"uri": "secret3"}}]})
    data = store.database.execute("SELECT metadata FROM audit_events WHERE action='check'").fetchone()[0]
    assert all(secret not in data for secret in ("secret1", "secret2", "secret3"))


def test_secret_fd_checks_persisted_management_handle(env):
    store, _, home = env
    handle = store.begin_management("admin", "admin-password", scopes=["config"])
    fd = channel(handle)
    try:
        assert require_management_fd(store, fd, "config")["role"] == "admin"
    finally:
        os.close(fd)
    file = home / "plain"
    file.write_text(handle)
    with file.open() as stream:
        fails("SECRET_CHANNEL_INVALID", read_secret_fd, stream.fileno())


def test_discover_explicit_registration_and_ambiguity(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    first = tmp_path / "first" / "core.json"
    first.parent.mkdir()
    first.write_text(json.dumps({"storageRoot": "runtime/accounts", "listen": {"host": "0.0.0.0", "port": 11451}}))
    info = register_instance(first)
    assert info["url"] == "http://127.0.0.1:11451"
    assert Path(info["identityDatabase"]) == first.parent / "runtime/security/identity.sqlite"
    assert discover_instance() == info
    registry = tmp_path / ".config/oai/instances.json"
    assert not Path(json.loads(registry.read_text())["instances"][0]).is_absolute()
    second = tmp_path / "second" / "core.json"
    second.parent.mkdir()
    second.write_text(json.dumps({"storageRoot": "accounts"}))
    register_instance(second)
    error = fails("INSTANCE_REQUIRED", discover_instance)
    assert len(error.details["configPaths"]) == 2
    assert discover_instance(first) == info


def test_disabling_policy_restores_password_only_without_erasing_binding(env, monkeypatch):
    store, admin, _ = env
    _, codes, _ = prepare_enabled(env, monkeypatch)
    handle = store.begin_management("admin", "admin-password", recovery_code=codes[0])
    store.set_mfa_required(handle, False)
    assert store.authenticate("admin", "admin-password")["mfaVerified"] is False
    assert store.account(admin["accountId"])["mfaEnrolled"] is True
    handle = store.begin_management("admin", "admin-password", scopes=["agents.manage"])
    assert store.require_management(handle, "agents.manage")["accountId"] == admin["accountId"]


def test_explicit_enrollment_id_version_and_policy_changes_invalidate_challenges(env, monkeypatch):
    store, admin, _ = env
    now = 2_000_000_010
    monkeypatch.setattr("oai_core.identity.time.time", lambda: now)
    proof = store.authenticate("admin", "admin-password", purpose="enroll")["enrollmentProof"]
    pending = store.begin_mfa_enrollment(admin["accountId"], proof)
    secret = base64.b32decode(pending["secret"] + "=" * (-len(pending["secret"]) % 8))
    otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(now).decode()
    fails("AUTHENTICATION_FAILED", store.confirm_mfa_enrollment, admin["accountId"], proof, otp, enrollment_id="wrong")
    confirmed = store.confirm_mfa_enrollment(admin["accountId"], proof, otp, enrollment_id=pending["enrollmentId"])
    assert store.complete_authentication(proof, purpose="enroll")["mfaVerified"]
    handle = store.begin_management("admin", "admin-password")
    LocalRecovery(store).register(handle, confirmed=True)
    store.set_mfa_required(handle, True)
    stale = challenge(store, "admin", "admin-password")
    handle = store.begin_management("admin", "admin-password", recovery_code=confirmed["recoveryCodes"][0])
    store.set_mfa_required(handle, False)
    fails("AUTHENTICATION_EXPIRED", store.complete_authentication, stale, recovery_code=confirmed["recoveryCodes"][1])


def test_enabling_requires_both_recovery_preparations(env, monkeypatch):
    store, admin, _ = env
    now = 2_000_000_010
    monkeypatch.setattr("oai_core.identity.time.time", lambda: now)
    handle = store.begin_management("admin", "admin-password")
    fails("MFA_RECOVERY_PREPARATION_REQUIRED", store.set_mfa_required, handle, True)
    enroll(store, admin, "admin", "admin-password", now)
    handle = store.begin_management("admin", "admin-password")
    fails("MFA_RECOVERY_PREPARATION_REQUIRED", store.set_mfa_required, handle, True)
    LocalRecovery(store).register(handle, confirmed=True)
    store.set_mfa_required(handle, True)
    assert store._policy()[0]


def test_last_admin_concurrent_removal_is_transactional(env):
    store, _, _ = env
    first = store.begin_management("admin", "admin-password")
    store.create_account(first, "other", "other-password")
    store.set_role(first, "other", "admin")
    second = store.begin_management("other", "other-password")
    path = store.database_path

    def remove(handle, target):
        candidate = IdentityStore(path)
        try:
            try:
                candidate.delete_account(handle, target)
                return "deleted"
            except GatewayError as exc:
                return exc.code
        finally:
            candidate.close()

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda pair: remove(*pair), [(first, "other"), (second, "admin")]))
    assert results.count("deleted") == 1
    assert len([a for a in store.database.execute("SELECT account_id FROM accounts WHERE role='admin' AND status='active'")]) == 1


def test_concurrent_recovery_code_is_single_use_across_store_instances(env, monkeypatch):
    store, _, _ = env
    _, codes, _ = prepare_enabled(env, monkeypatch)
    one = challenge(store, "admin", "admin-password", client_id="one")
    two = challenge(store, "admin", "admin-password", client_id="two")
    path = store.database_path

    def complete(pair):
        challenge_id, client_id = pair
        candidate = IdentityStore(path)
        try:
            try:
                candidate.complete_authentication(challenge_id, recovery_code=codes[0], client_id=client_id)
                return "ok"
            except GatewayError as exc:
                return exc.code
        finally:
            candidate.close()

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(complete, [(one, "one"), (two, "two")]))
    assert sorted(results) == ["AUTHENTICATION_FAILED", "ok"]


def test_last_admin_rejects_malformed_other_digest(env):
    store, _, _ = env
    handle = store.begin_management("admin", "admin-password")
    other = store.create_account(handle, "other", "pw")
    store.set_role(handle, "other", "admin")
    store.database.execute("UPDATE accounts SET password_hash='scrypt$broken' WHERE account_id=?", (other["accountId"],))
    fails("LAST_ADMIN_REQUIRED", store.delete_account, handle, "admin")


def test_local_recovery_refuses_damaged_registry_without_resetting_it(env):
    store, _, _ = env
    handle = store.begin_management("admin", "admin-password")
    registry = store.database_path.parent / "local-recovery.sqlite"
    registry.write_text("damaged")
    registry.chmod(0o600)
    fails("LOCAL_RECOVERY_UNAVAILABLE", LocalRecovery(store).register, handle, confirmed=True)
    assert registry.read_text() == "damaged"


def test_missing_recovery_registry_is_not_silently_recreated(env):
    store, _, home = env
    handle = store.begin_management("admin", "admin-password")
    recovery = LocalRecovery(store)
    recovery.register(handle, confirmed=True)
    recovery.path.rename(home / "preserved-recovery.sqlite")
    fails("LOCAL_RECOVERY_UNAVAILABLE", recovery.register, handle, confirmed=True)
    assert not recovery.path.exists()


def test_unfinished_legacy_journal_blocks_migration_before_writes(tmp_path):
    runtime = tmp_path / "runtime"
    storage = runtime / "accounts"
    storage.mkdir(parents=True)
    (runtime / "identity-journal.json").write_text("pending")
    database = runtime / "security/identity.sqlite"
    store = IdentityStore(database, create=True)
    fails("LEGACY_STATE_INCOMPLETE", store.migrate_legacy, storage, "admin", "pw", confirmed=True)
    assert not database.exists()


def test_independent_admin_recovery_works_without_business_key(env, monkeypatch):
    store, _, _ = env
    _, _, codes = prepare_enabled(env, monkeypatch)
    store.key_store = None
    result = LocalRecovery(store).reset_password("admin", codes[0], "replacement", confirmed=True)
    assert result["mfaEnrolled"] and store._policy()[0]
    challenge_id = challenge(store, "admin", "replacement")
    fails("MFA_KEY_UNAVAILABLE", store.complete_authentication, challenge_id, otp="123456")


def test_saved_login_url_discovery_uses_private_nonsecret_reference(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    client = tmp_path / ".config/oai/client.json"
    client.parent.mkdir(parents=True)
    client.write_text(json.dumps({"url": "https://example.test", "token": "not-to-be-returned"}))
    client.chmod(0o600)
    assert discover_instance() == {"url": "https://example.test"}
    client.write_text(json.dumps({"url": "https://example.test", "loggedOut": True}))
    fails("INSTANCE_NOT_FOUND", discover_instance)


def test_offline_mfa_requires_a_live_consumable_proof(env, monkeypatch):
    store, _, _ = env
    prepare_enabled(env, monkeypatch)
    fails("OFFLINE_MFA_OR_POLICY_UNAVAILABLE", IdentityStore.verify_offline_password,
          store.database_path, "admin", "admin-password")


def test_challenge_account_enforces_client_purpose_expiry_and_consumption(env, monkeypatch):
    store, admin, _ = env
    now = 2_000_000_010
    _, codes, _ = prepare_enabled(env, monkeypatch, now)
    proof = challenge(store, "admin", "admin-password", purpose="android-login", client_id="phone")
    assert store.challenge_account(proof, purpose="android-login", client_id="phone") == admin["accountId"]
    fails("AUTHENTICATION_FAILED", store.challenge_account, proof, purpose="login", client_id="phone")
    fails("AUTHENTICATION_FAILED", store.challenge_account, proof, purpose="android-login", client_id="other")
    store.complete_authentication(proof, recovery_code=codes[0], purpose="android-login", client_id="phone")
    fails("AUTHENTICATION_FAILED", store.challenge_account, proof, purpose="android-login", client_id="phone")
    expired = challenge(store, "admin", "admin-password", client_id="phone")
    monkeypatch.setattr("oai_core.identity.time.time", lambda: now + 300)
    fails("AUTHENTICATION_EXPIRED", store.challenge_account, expired, client_id="phone")
