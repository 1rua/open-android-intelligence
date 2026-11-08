"""通过真实签名 HTTP 请求验证认证存储故障与恢复。"""
from __future__ import annotations

import sqlite3
from contextlib import closing

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from oai_core.gateway.core import GatewayError
from oai_core.server import AUTH_KEY, create_core
from test_core_transport import CONTRACT, PREFIX, gateway, phone_login, signed


@pytest.mark.asyncio
async def test_account_write_lock_keeps_existing_signed_read_authenticated(gateway):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    path = PREFIX + "/agents"

    async def request():
        _, headers = signed(bundle, key, "GET", path)
        return await client.get(path, headers=headers)

    response = await request()
    assert response.status == 200, await response.text()

    account = core.open_gateway_account(bundle["accountId"])
    database_path = account.paths.database
    account.close()
    with closing(sqlite3.connect(database_path, isolation_level=None)) as writer:
        writer.execute("BEGIN IMMEDIATE")
        try:
            # WAL 下只读认证事实仍可读取，不允许为重复初始化额外申请写锁。
            with closing(sqlite3.connect(database_path.as_uri() + "?mode=ro", uri=True)) as reader:
                assert reader.execute("SELECT count(*) FROM access_sessions WHERE status='active'").fetchone()[0] > 0
            response = await request()
            assert response.status == 200, await response.text()
        finally:
            writer.rollback()
    response = await request()
    assert response.status == 200, await response.text()


@pytest.mark.asyncio
@pytest.mark.parametrize("storage", ["account-migration", "nonce-ledger"])
@pytest.mark.parametrize("path", [PREFIX + "/agents", PREFIX + "/pairings/current"])
async def test_temporary_authentication_storage_busy_is_recoverable_and_keeps_nonce(gateway, storage, path):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    account = core.open_gateway_account(bundle["accountId"])
    database_path = account.paths.database
    if storage == "account-migration":
        account.store.database.execute("DELETE FROM account_metadata WHERE key='account_schema_version'")
    account.close()
    if storage == "nonce-ledger":
        database_path = app[AUTH_KEY].database.execute("PRAGMA database_list").fetchone()[2]
        app[AUTH_KEY].database.execute("PRAGMA busy_timeout=25")
    _, headers = signed(bundle, key, "GET", path)
    with closing(sqlite3.connect(database_path, isolation_level=None)) as writer:
        writer.execute("BEGIN IMMEDIATE")
        try:
            response = await client.get(path, headers=headers)
            assert response.status == 503, await response.text()
            error = (await response.json())["error"]
            assert error["code"] == "AUTHENTICATION_STORAGE_UNAVAILABLE"
            if path.endswith("/pairings/current"):
                assert error["retryable"] is True
                assert error["details"]["stage"] == ("account-open" if storage == "account-migration" else "nonce-write")
                assert error["details"]["storageError"] == "SQLITE_BUSY"
        finally:
            writer.rollback()
    # 临时拒绝没有执行请求，也没有消耗 nonce；同一原凭据和原请求可恢复一次。
    response = await client.get(path, headers=headers)
    assert response.status == 200, await response.text()
    response = await client.get(path, headers=headers)
    assert response.status == 401, await response.text()


@pytest.mark.asyncio
@pytest.mark.parametrize("path", [PREFIX + "/agents", PREFIX + "/pairings/current"])
async def test_pending_message_scrub_busy_is_recoverable_without_finishing_migration(gateway, path):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    account = core.open_gateway_account(bundle["accountId"])
    database_path = account.paths.database
    # 模拟加密已提交、物理清理尚未完成的恢复状态，保留原账号与会话。
    account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES ('message_scrub_pending','1')")
    account.store.database.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    account.close()
    _, headers = signed(bundle, key, "GET", path)
    with closing(sqlite3.connect(database_path, isolation_level=None)) as reader:
        reader.execute("BEGIN")
        reader.execute("SELECT * FROM account_metadata").fetchall()
        try:
            response = await client.get(path, headers=headers)
            assert response.status == 503, await response.text()
            error = (await response.json())["error"]
            assert error["code"] == "AUTHENTICATION_STORAGE_UNAVAILABLE"
            if path.endswith("/pairings/current"):
                assert error["retryable"] is True
                assert error["details"]["stage"] == "account-open"
                assert error["details"]["storageError"] == "MESSAGE_MIGRATION_BUSY"
            with closing(sqlite3.connect(database_path.as_uri() + "?mode=ro", uri=True)) as observer:
                assert observer.execute("SELECT value FROM account_metadata WHERE key='message_scrub_pending'").fetchone()[0] == "1"
                assert observer.execute("SELECT 1 FROM account_metadata WHERE key='account_schema_version'").fetchone() is None
        finally:
            reader.rollback()
    # 读快照释放后完成清理；原签名仅可成功一次，不绕过 nonce 防重放。
    response = await client.get(path, headers=headers)
    assert response.status == 200, await response.text()
    assert (await client.get(path, headers=headers)).status == 401
    account = core.open_gateway_account(bundle["accountId"])
    try:
        assert account.store.database.execute("SELECT 1 FROM account_metadata WHERE key='message_scrub_pending'").fetchone() is None
        assert account.store.database.execute("SELECT value FROM account_metadata WHERE key='account_schema_version'").fetchone()[0] == "1"
    finally:
        account.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("sqlite_code,status,code", [
    (sqlite3.SQLITE_BUSY, 503, "AUTHENTICATION_STORAGE_UNAVAILABLE"),
    (sqlite3.SQLITE_LOCKED, 503, "AUTHENTICATION_STORAGE_UNAVAILABLE"),
    (sqlite3.SQLITE_ERROR, 500, "AUTHENTICATION_STORAGE_FAILED"),
])
async def test_session_fact_read_storage_failure_is_not_credential_revocation(gateway, monkeypatch, caplog, sqlite_code, status, code):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    connect = sqlite3.connect
    failure = sqlite3.OperationalError("private-sql-and-authentication-material")
    failure.sqlite_errorcode = sqlite_code

    def unavailable_readonly_database(database, *args, **kwargs):
        if "?mode=ro" in str(database):
            raise failure
        return connect(database, *args, **kwargs)

    path = PREFIX + "/pairings/current"
    _, headers = signed(bundle, key, "GET", path)
    with monkeypatch.context() as context:
        context.setattr(sqlite3, "connect", unavailable_readonly_database)
        response = await client.get(path, headers=headers)
    assert response.status == status, await response.text()
    error = (await response.json())["error"]
    assert error["code"] == code
    assert error["retryable"] is (status == 503)
    assert error["details"]["stage"] == "session-read"
    assert "private-sql-and-authentication-material" not in caplog.text
    for name in ("Authorization", "X-Open-Android-Intelligence-Nonce", "X-Open-Android-Intelligence-Signature"):
        assert headers[name] not in caplog.text
    # 此阶段未通过认证，原请求尚未登记 nonce，恢复后只允许成功一次。
    assert (await client.get(path, headers=headers)).status == 200
    assert (await client.get(path, headers=headers)).status == 401


def test_existing_account_schema_migration_keeps_identity_and_rejects_future_versions(tmp_path):
    settings = {"storageRoot": str(tmp_path / "data"), "masterKeyFile": str(tmp_path / "keys/master"), "contractRoot": str(CONTRACT)}
    core = create_core(settings)
    account = core.open_gateway_account("alice")
    account.credentials.create_password("test-only-password")
    master_reference = account.master_key_ref
    database_path = account.paths.database
    # 模拟升级前已有库：旧库没有本次新增的 schema 完成标记。
    account.store.database.execute("DELETE FROM account_metadata WHERE key='account_schema_version'")
    account.close()
    upgraded = create_core(settings)
    account = upgraded.open_gateway_account("alice")
    assert account.credentials.verify_password("test-only-password")
    assert account.master_key_ref == master_reference
    assert account.store.database.execute("SELECT value FROM account_metadata WHERE key='account_schema_version'").fetchone()[0] == "1"
    account.store.database.execute("UPDATE account_metadata SET value='future-version' WHERE key='account_schema_version'")
    account.close()
    with pytest.raises(GatewayError, match="ACCOUNT_STORAGE_VERSION_UNSUPPORTED"):
        upgraded.open_gateway_account("alice")
    with closing(sqlite3.connect(database_path)) as database:
        assert database.execute("SELECT value FROM account_metadata WHERE key='account_schema_version'").fetchone()[0] == "future-version"


@pytest.mark.asyncio
@pytest.mark.parametrize("refusal", ["wrong-signature", "expired-access", "revoked-session"])
async def test_storage_recovery_keeps_real_credential_refusals(gateway, refusal):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    path = PREFIX + "/agents"
    if refusal == "wrong-signature":
        key = Ed25519PrivateKey.generate()
    else:
        account = core.open_gateway_account(bundle["accountId"])
        try:
            if refusal == "expired-access":
                account.store.database.execute("UPDATE access_sessions SET expires_at='2000-01-01T00:00:00.000Z' WHERE session_id=?", (bundle["sessionId"],))
            else:
                account.sessions.revoke_session(bundle["sessionId"], "storage-regression-revocation")
        finally:
            account.close()
    _, headers = signed(bundle, key, "GET", path)
    response = await client.get(path, headers=headers)
    assert response.status == 401, await response.text()
    assert (await response.json())["error"]["code"] == "AUTHENTICATION_REQUIRED"
