"""Android publications, not legacy account policy, authorize device operations."""
from __future__ import annotations

import json
import sqlite3
import stat
from contextlib import closing
from pathlib import Path

import pytest

from oai_core.cli import main
from oai_core.gateway.account_paths import account_paths
from oai_core.gateway.core import AccountStore, EventStore, GatewayError, TransactionOutcomeUnknown, create_gateway_core
from oai_core.gateway.admin import AdminService
from oai_core.server import DEVICE_API

from test_core_transport import PREFIX, gateway, phone_login, publish, request_signed, token


LEGACY_POLICY = "device-capability-policy:v1"
MODEL_MARKER = "device-authorization-model:v1"


def save_legacy_policy(core, account_id, value):
    account = core.open_gateway_account(account_id)
    try:
        account.store.database.execute(
            "INSERT OR REPLACE INTO account_metadata(key,value) VALUES (?,?)", (LEGACY_POLICY, value),
        )
    finally:
        account.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("policy", [False, True, "malformed"])
async def test_legacy_policy_cannot_override_android_publication_or_request_lifecycle(gateway, policy):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    binding, revision = await publish(client, core, bundle, key)
    raw = "not-json" if policy == "malformed" else json.dumps({
        "revision": 3, "enabled": {binding["capabilityId"]: policy},
    })
    save_legacy_policy(core, bundle["accountId"], raw)
    headers = await token(client, bundle)

    response = await client.get(DEVICE_API + "/capabilities", headers=headers)
    assert response.status == 200, await response.text()
    catalog = (await response.json())["capabilities"]
    actual = next(item for item in catalog if item["capabilityId"] == binding["capabilityId"])
    assert actual["status"] == actual["androidStatus"] == "READY"
    assert "gatewayEnabled" not in actual

    response = await client.post(DEVICE_API + "/device-requests", json={
        "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
    }, headers={**headers, "Idempotency-Key": "android-authority-request"})
    assert response.status == 200, await response.text()
    request_id = (await response.json())["requestId"]
    claim = await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{request_id}/result", {
        "claimId": claim["claimId"], "grantRevision": revision,
        "result": {"outcome": "succeeded", "data": {"records": ["phone-result"]}},
    })
    response = await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=headers)
    assert response.status == 200, await response.text()
    assert (await response.json())["result"]["data"] == {"records": ["phone-result"]}
    assert (await client.post(DEVICE_API + f"/device-requests/{request_id}/ack", headers=headers)).status == 200


def test_legacy_policy_cli_is_explicitly_retired_without_touching_configuration(tmp_path, capsys):
    configuration = tmp_path / "does-not-exist.json"
    assert main(["policy", "set", "--config", str(configuration), "--account", "alice",
                 "--capability", "org.example.fixture.read", "--disabled"]) == 1
    assert "DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID" in capsys.readouterr().err
    assert not configuration.exists()


@pytest.mark.asyncio
async def test_legacy_permanent_request_revocation_survives_policy_retirement(gateway):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    binding, _ = await publish(client, core, bundle, key)
    headers = await token(client, bundle)
    response = await client.post(DEVICE_API + "/device-requests", json={
        "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
    }, headers={**headers, "Idempotency-Key": "revoked-request"})
    assert response.status == 200, await response.text()
    request_id = (await response.json())["requestId"]
    account = core.open_gateway_account(bundle["accountId"])
    try:
        account.store.database.execute(
            "INSERT INTO account_metadata(key,value) VALUES (?, '1')", (f"device-request-policy-revoked:{request_id}",),
        )
    finally:
        account.close()
    response = await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=headers)
    assert response.status == 403
    assert (await response.json())["error"]["details"]["reason"] == "REQUEST_REVOKED"


@pytest.mark.asyncio
@pytest.mark.parametrize("policy", [False, "malformed"])
async def test_upgrade_fences_legacy_calls_and_snapshots_without_recreating_identity(gateway, policy):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    binding, revision = await publish(client, core, bundle, key)
    headers = await token(client, bundle)
    requests = {}
    for name in ("pending", "claimed", "succeeded"):
        response = await client.post(DEVICE_API + "/device-requests", json={
            "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
        }, headers={**headers, "Idempotency-Key": "legacy-" + name})
        assert response.status == 200, await response.text()
        requests[name] = (await response.json())["requestId"]
    receipts = {}
    for name in ("claimed", "succeeded"):
        receipts[name] = await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{requests[name]}/claim", {})
    await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{requests['succeeded']}/result", {
        "claimId": receipts["succeeded"]["claimId"], "grantRevision": revision,
        "result": {"outcome": "succeeded", "data": {"records": ["private-phone-result"]}},
    })

    # Represent an existing pre-upgrade account, including a CLI-created false
    # that never produced the old AdminService's permanent request tombstone.
    account = core.open_gateway_account(bundle["accountId"])
    try:
        original_identity = tuple(account.store.database.execute(
            "SELECT device_id,installation_id,public_key,pairing_generation FROM device_keys WHERE device_id=?", (bundle["deviceId"],),
        ).fetchone())
        original_publication = account.store.database.execute(
            "SELECT value FROM account_metadata WHERE key=?", ("device-capabilities:" + bundle["deviceId"],),
        ).fetchone()[0]
        original_requests = [tuple(row) for row in account.store.database.execute(
            "SELECT request_id,state,parameters_json,result_json FROM device_requests ORDER BY request_id",
        )]
        original_credentials = [tuple(row) for row in account.store.database.execute("SELECT * FROM account_credentials")]
        original_master_key_ref = account.master_key_ref
        event_ids = [row[0] for row in account.store.database.execute("SELECT event_id FROM events ORDER BY sequence")]
        legacy_policy = "not-json" if policy == "malformed" else json.dumps({
            "revision": 3, "enabled": {binding["capabilityId"]: False},
        })
        with account.store.transaction():
            # A historical binary had no authorization-model write guards.
            # Keep this fixture a real old database, including its old schema.
            for trigger in account.store.database.execute(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND sql LIKE '%oai_device_authorization_writer%'",
            ).fetchall():
                account.store.database.execute('DROP TRIGGER "' + trigger[0].replace('"', '""') + '"')
            account.store.database.execute("DELETE FROM account_metadata WHERE key=?", (MODEL_MARKER,))
            account.store.database.execute("INSERT OR REPLACE INTO account_metadata(key,value) VALUES (?,?)", (LEGACY_POLICY, legacy_policy))
            account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?, '1')", ("device-request-policy-revoked:" + requests["pending"],))
    finally:
        account.close()

    migrated = core.open_gateway_account(bundle["accountId"])
    try:
        backup_row = migrated.store.database.execute(
            "SELECT value FROM account_metadata WHERE key='device-authorization-backup:v1'",
        ).fetchone()
        assert backup_row is not None, "destructive migration requires a recoverable pre-migration snapshot"
        backup = json.loads(backup_row[0])
        relative_path = Path(backup["database"])
        assert not relative_path.is_absolute() and ".." not in relative_path.parts
        backup_path = migrated.paths.root / relative_path
        assert stat.S_IMODE(backup_path.stat().st_mode) == 0o600
        assert backup["masterKeyRef"] == original_master_key_ref
        with closing(sqlite3.connect(backup_path.as_uri() + "?mode=ro", uri=True)) as snapshot:
            assert snapshot.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            assert snapshot.execute(
                "SELECT request_id,state,parameters_json,result_json FROM device_requests ORDER BY request_id",
            ).fetchall() == original_requests
            assert snapshot.execute("SELECT * FROM account_credentials").fetchall() == original_credentials
            assert snapshot.execute(
                "SELECT device_id,installation_id,public_key,pairing_generation FROM device_keys WHERE device_id=?",
                (bundle["deviceId"],),
            ).fetchone() == original_identity
            assert snapshot.execute("SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,)).fetchone() is None
            assert snapshot.execute("SELECT value FROM account_metadata WHERE key=?", (LEGACY_POLICY,)).fetchone()[0] == legacy_policy
            # SQLite's standard restore reads the full snapshot without the
            # active database's future-writer guards. The original key still
            # decrypts the preserved result; no replacement identity is made.
            with closing(sqlite3.connect(backup_path.parent / "restore-probe.sqlite", isolation_level=None)) as restored:
                snapshot.backup(restored)
                restored.execute("INSERT INTO account_metadata(key,value) VALUES ('legacy-restore-probe','1')")
                result = restored.execute(
                    "SELECT result_json FROM device_requests WHERE request_id=?", (requests["succeeded"],),
                ).fetchone()[0]
                assert migrated.store.open_json(
                    result, f"device-result:{requests['succeeded']}:{receipts['succeeded']['claimId']}",
                )["data"] == {"records": ["private-phone-result"]}
        assert tuple(migrated.store.database.execute(
            "SELECT device_id,installation_id,public_key,pairing_generation FROM device_keys WHERE device_id=?", (bundle["deviceId"],),
        ).fetchone()) == original_identity
        assert migrated.store.database.execute("SELECT grant_revision FROM device_keys WHERE device_id=?", (bundle["deviceId"],)).fetchone()[0] == revision + 1
        assert migrated.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,)).fetchone()[0] == "android-v1"
        assert migrated.store.database.execute("SELECT value FROM account_metadata WHERE key='device-capability-policy:legacy-v1'").fetchone()[0] == legacy_policy
        assert migrated.store.database.execute("SELECT value FROM account_metadata WHERE key=?", ("device-capabilities:legacy-v1:" + bundle["deviceId"],)).fetchone()[0] == original_publication
        assert migrated.device_requests.capabilities.list(bundle["deviceId"], bundle["pairingGeneration"], revision + 1) == []
        states = {name: migrated.device_requests.get(request_id)["state"] for name, request_id in requests.items()}
        assert states == {"pending": "cancelled", "claimed": "outcome_unknown", "succeeded": "succeeded"}
        assert all(row[0] == row[1] == "" for row in migrated.store.database.execute("SELECT parameters_json,result_json FROM device_requests"))
        assert migrated.store.database.execute("SELECT 1 FROM account_metadata WHERE key=?", ("device-request-policy-revoked:" + requests["pending"],)).fetchone()
        assert migrated.store.database.execute("SELECT count(*) FROM claim_receipts").fetchone()[0] == 2
        with pytest.raises(GatewayError, match="CAPABILITY_DENIED") as failure:
            migrated.device_requests.read_result(requests["succeeded"], receipts["succeeded"]["claimId"])
        assert failure.value.details["reason"] == "REQUEST_REVOKED"
        after_events = migrated.events.read_after(None)
        assert [event["eventId"] for event in after_events[:len(event_ids)]] == event_ids
        assert all(event["payload"].get("parameters") == {} for event in after_events if event["eventType"] == "device.requested")
        assert "private-phone-result" not in str(migrated.audit.list())
    finally:
        migrated.close()

    # The same account's second open is read-only with respect to the model
    # boundary. A fresh real Android publication is required to call again.
    reopened = core.open_gateway_account(bundle["accountId"])
    try:
        assert reopened.store.database.execute("SELECT grant_revision FROM device_keys WHERE device_id=?", (bundle["deviceId"],)).fetchone()[0] == revision + 1
    finally:
        reopened.close()
    response = await client.get(DEVICE_API + "/capabilities", headers=headers)
    assert response.status == 403
    assert (await response.json())["error"]["code"] == "GRANT_STALE"
    headers = await token(client, bundle)
    response = await client.get(DEVICE_API + "/capabilities", headers=headers)
    actual = next(item for item in (await response.json())["capabilities"] if item["capabilityId"] == binding["capabilityId"])
    assert actual["status"] == "SYNC_REQUIRED"
    await request_signed(client, bundle, key, "POST", PREFIX + "/pairings/current/capabilities", {
        "bindings": [binding], "expectedGrantRevision": revision + 1, "localGrantRevision": 2,
    })
    headers = await token(client, bundle)
    response = await client.post(DEVICE_API + "/device-requests", json={
        "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
    }, headers={**headers, "Idempotency-Key": "after-upgrade"})
    assert response.status == 200, await response.text()


@pytest.mark.asyncio
async def test_legacy_admin_and_direct_policy_writers_are_retired(gateway):
    _, core, _ = gateway
    service = AdminService(core, None)
    result = service.execute({"command": "capabilities.set", "accountId": "alice",
                              "capabilityId": "org.example.fixture.read", "enabled": False, "localConfirmation": True})
    assert result["ok"] is False
    assert result["error"]["code"] == "DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID"
    account = core.open_gateway_account("alice")
    try:
        from oai_core.gateway.capability_policy import DeviceCapabilityPolicy
        policy = DeviceCapabilityPolicy(account.store)
        with pytest.raises(GatewayError, match="DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID"):
            policy.set("org.example.fixture.read", False)
        with pytest.raises(GatewayError, match="DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID"):
            policy.allows("org.example.fixture.read")
        assert account.store.database.execute("SELECT 1 FROM account_metadata WHERE key=?", (LEGACY_POLICY,)).fetchone() is None
    finally:
        account.close()


@pytest.mark.asyncio
async def test_model_migration_failure_rolls_back_and_can_retry(gateway, monkeypatch):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    _, revision = await publish(client, core, bundle, key)
    account = core.open_gateway_account("alice")
    account.store.database.execute("DELETE FROM account_metadata WHERE key=?", (MODEL_MARKER,))
    account.close()
    append = EventStore.append

    def fail_migration(self, event_type, *args, **kwargs):
        if event_type == "pairing.grant.changed":
            raise RuntimeError("injected migration failure")
        return append(self, event_type, *args, **kwargs)

    with monkeypatch.context() as context:
        context.setattr(EventStore, "append", fail_migration)
        with pytest.raises(RuntimeError, match="injected migration failure"):
            core.open_gateway_account("alice")
    backup_directory = account.paths.root / "backups"
    previous_backups = {path: path.read_bytes() for path in backup_directory.glob("*.sqlite")}
    assert len(previous_backups) == 1
    # A fresh open succeeds, proving the failed constructor closed its SQLite
    # connection and the revision/marker were not partially committed.
    account = core.open_gateway_account("alice")
    try:
        assert account.store.database.execute("SELECT grant_revision FROM device_keys WHERE device_id=?", (bundle["deviceId"],)).fetchone()[0] == revision + 1
        assert account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,)).fetchone()[0] == "android-v1"
        events = [event for event in account.events.read_after(None) if event["correlationId"] == "device-authority-migration"]
        assert len(events) == 1
        assert len(list(backup_directory.glob("*.sqlite"))) == 1
        assert all(path.read_bytes() == original for path, original in previous_backups.items())
    finally:
        account.close()


@pytest.mark.asyncio
async def test_binding_withdrawn_between_validation_and_enqueue_is_rechecked(gateway, monkeypatch):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    binding, revision = await publish(client, core, bundle, key)
    account = core.open_gateway_account("alice")
    try:
        published = account.device_requests.capabilities
        validate = published.validate
        checked = 0

        def withdraw_after_validation(*args):
            nonlocal checked
            accepted = validate(*args)
            checked += 1
            if checked == 1:
                published.register(bundle["deviceId"], bundle["pairingGeneration"], revision, [])
            return accepted

        monkeypatch.setattr(published, "validate", withdraw_after_validation)
        with pytest.raises(GatewayError, match="CAPABILITY_DENIED"):
            account.device_requests.enqueue(
                request_id="publication-race", device_id=bundle["deviceId"],
                pairing_generation=bundle["pairingGeneration"], grant_revision=revision,
                risk="read", capability={"id": binding["capabilityId"], "version": "1.0.0"},
                provider={field: binding[field] for field in ("pluginId", "authorKeyId")},
                parameters={"limit": 1}, correlation_id="publication-race",
            )
        assert checked == 2
        assert account.store.database.execute("SELECT count(*) FROM device_requests").fetchone()[0] == 0
    finally:
        account.close()


@pytest.mark.asyncio
async def test_unknown_authorization_model_cannot_be_opened_as_older_model(gateway):
    _, core, _ = gateway
    account = core.open_gateway_account("alice")
    account.store.database.execute("UPDATE account_metadata SET value='android-future' WHERE key=?", (MODEL_MARKER,))
    account.close()
    with pytest.raises(GatewayError, match="DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED"):
        core.open_gateway_account("alice")


def test_empty_account_installs_writer_guards_without_consuming_business_commit_hook(tmp_path):
    def business_commit():
        raise AssertionError("account initialization consumed the business commit hook")

    core = create_gateway_core(storage_root=tmp_path, commit_hook=business_commit)
    account = core.open_gateway_account("alice")
    try:
        assert account.store.database.execute(
            "SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,),
        ).fetchone()[0] == "android-v1"
        with closing(sqlite3.connect(account.paths.database, isolation_level=None)) as legacy:
            with pytest.raises(sqlite3.OperationalError, match="oai_device_authorization_writer"):
                legacy.execute("INSERT INTO account_metadata(key,value) VALUES ('legacy-probe','unsafe')")
    finally:
        account.close()


def test_database_rejects_old_writers_including_cached_pre_upgrade_statements(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("alice")
    path = account.paths.database
    try:
        # Seed the schema that an actual pre-model database had.
        with account.store.transaction():
            for trigger in account.store.database.execute(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND sql LIKE '%oai_device_authorization_writer%'",
            ).fetchall():
                account.store.database.execute('DROP TRIGGER "' + trigger[0].replace('"', '""') + '"')
            account.store.database.execute("DELETE FROM account_metadata WHERE key=?", (MODEL_MARKER,))
            account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?, 'legacy')", (LEGACY_POLICY,))
    finally:
        account.close()

    with closing(sqlite3.connect(path, isolation_level=None)) as already_open:
        cached_insert = "INSERT INTO account_metadata(key,value) VALUES ('cached-legacy-statement',?)"
        already_open.execute(cached_insert, ("before",))
        already_open.execute("DELETE FROM account_metadata WHERE key='cached-legacy-statement'")
        migrated = core.open_gateway_account("alice")
        try:
            with pytest.raises(sqlite3.OperationalError, match="oai_device_authorization_writer"):
                already_open.execute(cached_insert, ("after",))
            with closing(sqlite3.connect(path, isolation_level=None)) as newly_opened:
                for legacy in (already_open, newly_opened):
                    tables = legacy.execute(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*'",
                    ).fetchall()
                    for table in tables:
                        quoted = '"' + table[0].replace('"', '""') + '"'
                        column = legacy.execute(f"PRAGMA table_info({quoted})").fetchone()[1]
                        quoted_column = '"' + column.replace('"', '""') + '"'
                        # Empty writes still prepare each real trigger. A binary
                        # without the model function cannot silently initialize
                        # or write through a table the fence forgot to cover.
                        for statement in (
                            f"INSERT INTO {quoted} SELECT * FROM {quoted} WHERE 0",
                            f"UPDATE {quoted} SET {quoted_column}={quoted_column} WHERE 0",
                            f"DELETE FROM {quoted} WHERE 0",
                        ):
                            with pytest.raises(sqlite3.OperationalError, match="oai_device_authorization_writer"):
                                legacy.execute(statement)
            with migrated.store.transaction():
                migrated.store.database.execute("INSERT INTO account_metadata(key,value) VALUES ('current-writer','allowed')")
            assert migrated.store.database.execute(
                "SELECT value FROM account_metadata WHERE key='current-writer'",
            ).fetchone()[0] == "allowed"
        finally:
            migrated.close()


def test_current_writer_guard_survives_unknown_commit_connection_recovery(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("alice")
    try:
        account.store.fail_next_commit = True
        with pytest.raises(TransactionOutcomeUnknown):
            with account.store.transaction():
                account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES ('unknown-commit','rolled-back')")
        with account.store.transaction():
            account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES ('recovered-writer','allowed')")
        assert account.store.database.execute(
            "SELECT value FROM account_metadata WHERE key='unknown-commit'",
        ).fetchone() is None
        assert account.store.database.execute(
            "SELECT value FROM account_metadata WHERE key='recovered-writer'",
        ).fetchone()[0] == "allowed"
        with closing(sqlite3.connect(account.paths.database, isolation_level=None)) as legacy:
            with pytest.raises(sqlite3.OperationalError, match="oai_device_authorization_writer"):
                legacy.execute("DELETE FROM account_metadata WHERE key='recovered-writer'")
    finally:
        account.close()


def test_unknown_model_is_rejected_before_any_initialization_write(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("alice")
    path = account.paths.database
    try:
        account.store.database.execute("UPDATE account_metadata SET value='android-future' WHERE key=?", (MODEL_MARKER,))
        account.store.database.execute("DELETE FROM account_metadata WHERE key='deployment_id'")
    finally:
        account.close()
    with pytest.raises(GatewayError, match="DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED"):
        core.open_gateway_account("alice")
    with closing(sqlite3.connect(path)) as untouched:
        assert untouched.execute("SELECT value FROM account_metadata WHERE key='deployment_id'").fetchone() is None


@pytest.mark.asyncio
async def test_backup_failure_leaves_legacy_requests_and_identity_untouched_and_can_retry(gateway):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    binding, revision = await publish(client, core, bundle, key)
    headers = await token(client, bundle)
    response = await client.post(DEVICE_API + "/device-requests", json={
        "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 7},
    }, headers={**headers, "Idempotency-Key": "backup-failure-request"})
    assert response.status == 200, await response.text()
    request_id = (await response.json())["requestId"]
    account = core.open_gateway_account("alice")
    try:
        path = account.paths.database
        with account.store.transaction():
            for trigger in account.store.database.execute(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND sql LIKE '%oai_device_authorization_writer%'",
            ).fetchall():
                account.store.database.execute('DROP TRIGGER "' + trigger[0].replace('"', '""') + '"')
            account.store.database.execute("DELETE FROM account_metadata WHERE key=?", (MODEL_MARKER,))
            account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?, 'legacy-false')", (LEGACY_POLICY,))
        original = tuple(account.store.database.execute("SELECT * FROM device_requests WHERE request_id=?", (request_id,)).fetchone())
        identity = tuple(account.store.database.execute("SELECT * FROM device_keys WHERE device_id=?", (bundle["deviceId"],)).fetchone())
        master_ref = account.master_key_ref
        blocker = account.paths.root / "backups"
        blocker.write_text("snapshot storage is unavailable", encoding="utf-8")
    finally:
        account.close()
    with pytest.raises(GatewayError, match="DEVICE_AUTHORIZATION_BACKUP_FAILED"):
        core.open_gateway_account("alice")
    with closing(sqlite3.connect(path, isolation_level=None)) as legacy:
        assert legacy.execute("SELECT * FROM device_requests WHERE request_id=?", (request_id,)).fetchone() == original
        assert legacy.execute("SELECT * FROM device_keys WHERE device_id=?", (bundle["deviceId"],)).fetchone() == identity
        assert legacy.execute("SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,)).fetchone() is None
        assert legacy.execute("SELECT value FROM account_metadata WHERE key='master_key_ref'").fetchone()[0] == master_ref
        assert legacy.execute("SELECT value FROM account_metadata WHERE key=?", (LEGACY_POLICY,)).fetchone()[0] == "legacy-false"
        assert legacy.execute("SELECT 1 FROM account_metadata WHERE key='device-authorization-backup:v1'").fetchone() is None
        assert legacy.execute("SELECT 1 FROM account_metadata WHERE key=?", ("device-request-authority-migrated:" + request_id,)).fetchone() is None
        legacy.execute("INSERT INTO account_metadata(key,value) VALUES ('failed-migration-probe','not-upgraded')")
    blocker.rename(blocker.with_name("backup-unavailable-evidence"))
    migrated = core.open_gateway_account("alice")
    try:
        assert migrated.device_requests.get(request_id)["state"] == "cancelled"
        assert migrated.store.database.execute("SELECT grant_revision FROM device_keys WHERE device_id=?", (bundle["deviceId"],)).fetchone()[0] == revision + 1
    finally:
        migrated.close()


def test_empty_model_marker_and_writer_guards_roll_back_together(tmp_path, monkeypatch):
    original_connect = sqlite3.connect

    class FailingGuardConnection(sqlite3.Connection):
        def execute(self, sql, *args, **kwargs):
            if "oai_device_authorization_writer()" in sql and "BEFORE UPDATE" in sql:
                raise sqlite3.OperationalError("injected schema write failure")
            return super().execute(sql, *args, **kwargs)

    def failing_connection(*args, **kwargs):
        return original_connect(*args, **kwargs, factory=FailingGuardConnection)

    core = create_gateway_core(storage_root=tmp_path)
    with monkeypatch.context() as context:
        context.setattr(sqlite3, "connect", failing_connection)
        with pytest.raises(sqlite3.OperationalError, match="injected schema write failure"):
            core.open_gateway_account("alice")
    path = account_paths(tmp_path, "alice").database
    with closing(original_connect(path, isolation_level=None)) as legacy:
        assert legacy.execute("SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,)).fetchone() is None
        assert legacy.execute("SELECT name FROM sqlite_master WHERE type='trigger' AND sql LIKE '%oai_device_authorization_writer%'").fetchall() == []
        legacy.execute("INSERT INTO account_metadata(key,value) VALUES ('initialization-probe','legacy-writable')")
    account = core.open_gateway_account("alice")
    try:
        assert account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,)).fetchone()[0] == "android-v1"
    finally:
        account.close()


def test_base_account_store_does_not_opt_native_session_database_into_device_model(tmp_path):
    paths = account_paths(tmp_path, "native-session-account")
    store = AccountStore(paths, account_id="native-session-account")
    try:
        assert store.database.execute("SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,)).fetchone() is None
        with closing(sqlite3.connect(paths.database, isolation_level=None)) as legacy:
            legacy.execute("INSERT INTO account_metadata(key,value) VALUES ('native-session-probe','original-model')")
    finally:
        store.close()


def test_legacy_backup_excludes_competing_writer_until_persistent_fences_commit(tmp_path, monkeypatch):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("alice")
    path = account.paths.database
    try:
        with account.store.transaction():
            for trigger in account.store.database.execute(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND sql LIKE '%oai_device_authorization_writer%'",
            ).fetchall():
                account.store.database.execute('DROP TRIGGER "' + trigger[0].replace('"', '""') + '"')
            account.store.database.execute("DELETE FROM account_metadata WHERE key=?", (MODEL_MARKER,))
            account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?, 'legacy')", (LEGACY_POLICY,))
    finally:
        account.close()
    original_connect = sqlite3.connect
    attempted = []

    class SnapshotSourceConnection(sqlite3.Connection):
        def backup(self, target, *args, **kwargs):
            with closing(original_connect(path, isolation_level=None, timeout=0.01)) as legacy:
                with pytest.raises(sqlite3.OperationalError, match="database is locked"):
                    legacy.execute("INSERT INTO account_metadata(key,value) VALUES ('racing-legacy-writer','unsafe')")
            attempted.append(True)
            return super().backup(target, *args, **kwargs)

    def observed_connection(database, *args, **kwargs):
        if str(database).endswith("?mode=ro"):
            kwargs["factory"] = SnapshotSourceConnection
        return original_connect(database, *args, **kwargs)

    with monkeypatch.context() as context:
        context.setattr(sqlite3, "connect", observed_connection)
        migrated = core.open_gateway_account("alice")
    try:
        assert attempted == [True]
        assert migrated.store.database.execute("SELECT value FROM account_metadata WHERE key='racing-legacy-writer'").fetchone() is None
        with closing(original_connect(path, isolation_level=None)) as legacy:
            with pytest.raises(sqlite3.OperationalError, match="oai_device_authorization_writer"):
                legacy.execute("INSERT INTO account_metadata(key,value) VALUES ('racing-legacy-writer','unsafe')")
    finally:
        migrated.close()


def test_retained_snapshot_refuses_changed_database_without_growing_backups(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("alice")
    path = account.paths.database
    backup_directory = account.paths.root / "backups"
    try:
        for trigger in account.store.database.execute(
            "SELECT name FROM sqlite_master WHERE type='trigger' AND sql LIKE '%oai_device_authorization_writer%'",
        ).fetchall():
            account.store.database.execute('DROP TRIGGER "' + trigger[0].replace('"', '""') + '"')
        account.store.database.execute("DELETE FROM account_metadata WHERE key=?", (MODEL_MARKER,))
        account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?, 'legacy')", (LEGACY_POLICY,))
    finally:
        account.close()

    def failed_commit():
        raise sqlite3.OperationalError("injected migration commit failure")

    core.commit_hook = failed_commit
    with pytest.raises(TransactionOutcomeUnknown):
        core.open_gateway_account("alice")
    core.commit_hook = None
    snapshots = list(backup_directory.glob("*.sqlite"))
    assert len(snapshots) == 1
    original = snapshots[0].read_bytes()
    # The old model remains writable after the rolled-back migration. A real
    # later old-writer change makes the retained snapshot unsafe to reuse.
    with closing(sqlite3.connect(path, isolation_level=None)) as legacy:
        legacy.execute("INSERT INTO account_metadata(key,value) VALUES ('legacy-after-failure','new-data')")
    for _ in range(3):
        with pytest.raises(GatewayError, match="DEVICE_AUTHORIZATION_BACKUP_CONFLICT") as conflict:
            core.open_gateway_account("alice")
        assert conflict.value.details["reason"] == "RETAINED_SNAPSHOT_REQUIRES_MAINTENANCE"
        assert list(backup_directory.glob("*.sqlite")) == snapshots
        assert snapshots[0].read_bytes() == original
    with closing(sqlite3.connect(path)) as legacy:
        assert legacy.execute("SELECT value FROM account_metadata WHERE key=?", (MODEL_MARKER,)).fetchone() is None
        assert legacy.execute("SELECT value FROM account_metadata WHERE key='legacy-after-failure'").fetchone()[0] == "new-data"


def test_incomplete_retained_snapshot_requires_maintenance_and_is_never_replaced(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("alice")
    backup_directory = account.paths.root / "backups"
    try:
        account.store.database.execute("DELETE FROM account_metadata WHERE key=?", (MODEL_MARKER,))
        account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?, 'legacy')", (LEGACY_POLICY,))
        backup_directory.mkdir(mode=0o700)
        snapshot = backup_directory / "device-authority-pre-android-v1.sqlite"
        snapshot.write_bytes(b"incomplete SQLite snapshot")
        snapshot.chmod(0o600)
    finally:
        account.close()
    for _ in range(3):
        with pytest.raises(GatewayError, match="DEVICE_AUTHORIZATION_BACKUP_CONFLICT"):
            core.open_gateway_account("alice")
        assert list(backup_directory.glob("*.sqlite")) == [snapshot]
        assert snapshot.read_bytes() == b"incomplete SQLite snapshot"
