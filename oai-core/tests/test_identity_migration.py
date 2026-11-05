"""Stopped-writer migration preserves original data and refuses old writers."""
from contextlib import closing
from pathlib import Path
import sqlite3
import json

import pytest

from oai_core.gateway.account_paths import account_paths
from oai_core.gateway.core import GatewayCore, GatewayError
from oai_core.identity import IdentityStore
from oai_core.identity_migration import migrate_legacy_installation
from gateway_runtime.support import make_secret_store


@pytest.fixture
def legacy(tmp_path):
    runtime = tmp_path / "runtime"
    runtime.mkdir(mode=0o700)
    storage = runtime / "accounts"
    core = GatewayCore(storage_root=storage, secret_store=make_secret_store())
    for name in ("alice", "bob"):
        account = core.open_gateway_account(name)
        account.credentials.create_password(name + "-password")
        account.store.database.execute("INSERT INTO account_metadata VALUES ('retained-history','synthetic-marker')")
        account.close()
    return core, storage, runtime / "security/identity.sqlite"


def test_wrong_password_never_creates_migration_state(legacy):
    core, storage, identity = legacy
    with pytest.raises(GatewayError, match="AUTHENTICATION_FAILED"):
        migrate_legacy_installation(identity, storage, "alice", "wrong", confirmed=True, old_writers_stopped=lambda: True)
    assert not identity.exists()
    assert not (storage.parent / "identity-migrations").exists()


def test_old_writer_check_runs_only_after_password_and_must_succeed(legacy):
    core, storage, identity = legacy
    calls = []
    with pytest.raises(GatewayError, match="OLD_WRITERS_ACTIVE"):
        migrate_legacy_installation(identity, storage, "alice", "alice-password", confirmed=True,
                                    old_writers_stopped=lambda: calls.append(True) or False)
    assert calls == [True] and not identity.exists()


def test_migration_preserves_identity_and_data_and_fences_old_sqlite_writer(legacy):
    core, storage, identity = legacy
    journal = storage.parent / "configuration-journal.json"
    journal.write_text(json.dumps({"version": 1, "status": "committed", "entries": []}))
    journal.chmod(0o600)
    old = sqlite3.connect(account_paths(storage, "alice").database)
    try:
        migrated = migrate_legacy_installation(identity, storage, "alice", "alice-password", confirmed=True,
                                                old_writers_stopped=lambda: True)
        try:
            assert migrated.account("alice")["role"] == "admin"
            assert migrated.account("bob")["role"] == "user"
            assert migrated.authenticate("bob", "bob-password")["accountId"] == "bob"
            with pytest.raises(sqlite3.Error):
                old.execute("UPDATE account_credentials SET password_hash='attacker'")
            old.rollback()
            account = core.open_gateway_account("alice")
            try:
                assert account.store.database.execute("SELECT value FROM account_metadata WHERE key='retained-history'").fetchone()[0] == "synthetic-marker"
                assert account.store.database.execute("SELECT password_hash FROM account_credentials").fetchone()[0] == "migrated-to-core-identity"
            finally:
                account.close()
            assert list((storage.parent / "identity-migrations").glob("*/*.sqlite"))
        finally:
            migrated.close()
    finally:
        old.close()


def test_failure_before_activation_restores_old_digest_without_removing_history(legacy, monkeypatch):
    core, storage, identity = legacy
    monkeypatch.setattr("oai_core.identity_migration._guard", lambda _: (_ for _ in ()).throw(OSError("injected")))
    with pytest.raises(OSError, match="injected"):
        migrate_legacy_installation(identity, storage, "alice", "alice-password", confirmed=True, old_writers_stopped=lambda: True)
    assert not identity.exists()
    state = json.loads((storage.parent / "identity-migrations/journal.json").read_text())
    assert state["stage"] == "rolled_back"
    account = core.open_gateway_account("alice")
    try:
        assert account.credentials.verify_password("alice-password")
        assert account.store.database.execute("SELECT value FROM account_metadata WHERE key='retained-history'").fetchone()[0] == "synthetic-marker"
    finally:
        account.close()
    assert list((storage.parent / "identity-migrations").glob("*/failed-identity.sqlite"))
