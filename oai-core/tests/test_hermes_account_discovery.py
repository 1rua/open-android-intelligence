"""Hermes 原生账号发现与配置回写的定向回归测试。"""

import hashlib
import json
import sqlite3
import stat

from oai_core import configuration
from oai_core.gateway.account_paths import account_paths


TEST_SESSION_TOKEN = "test-hermes-session-token-" + "x" * 24
_NO_METADATA = object()


def _write_account_database(storage_root, directory_id, metadata_id=_NO_METADATA, *, table=True):
    database = account_paths(storage_root, directory_id).database
    database.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database) as connection:
        if table:
            connection.execute("CREATE TABLE account_metadata(key TEXT PRIMARY KEY, value TEXT)")
            if metadata_id is not _NO_METADATA:
                connection.execute(
                    "INSERT INTO account_metadata(key, value) VALUES ('gateway_account_id', ?)",
                    (metadata_id,),
                )
    return database


def _write_hermes_env(home, gateway_root, accounts, *, token=None):
    home.mkdir(parents=True, exist_ok=True)
    env_file = home / ".env"
    lines = [
        f"OAI_HERMES_GATEWAY_ROOT={gateway_root}",
        f"OAI_HERMES_ACCOUNTS={json.dumps(accounts)}",
    ]
    if token is not None:
        lines.append(f"OAI_HERMES_SESSION_TOKEN={token}")
    env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    env_file.chmod(0o600)
    return env_file


def test_discovery_uses_metadata_and_preserves_only_configured_oai_accounts(tmp_path):
    """真实 ID 来自 metadata；哈希目录和自动专属账号不进入候选。"""
    gateway_root = tmp_path / "accounts"
    _write_account_database(gateway_root, "alice", "alice")
    _write_account_database(gateway_root, "bob", "bob")
    _write_account_database(gateway_root, "oai_automatic", "oai_automatic")
    _write_account_database(gateway_root, "oai_configured", "oai_configured")
    alice_hash = hashlib.sha256(b"alice").hexdigest()
    hermes_home = tmp_path / "hermes"
    _write_hermes_env(hermes_home, gateway_root, ["alice", "oai_configured", alice_hash])

    accounts, discovered_root = configuration._discover_hermes_native_accounts(hermes_home)

    assert discovered_root == gateway_root
    assert set(accounts) == {"alice", "bob", "oai_configured"}
    assert alice_hash not in accounts
    assert account_paths(gateway_root, "bob").root.name not in accounts
    assert "oai_automatic" not in accounts


def test_discovery_ignores_missing_invalid_mismatched_or_corrupt_metadata(tmp_path):
    """缺少、错误、路径不匹配及损坏的 SQLite 均不能成为账号。"""
    gateway_root = tmp_path / "accounts"
    _write_account_database(gateway_root, "missing_table", table=False)
    _write_account_database(gateway_root, "missing_value")
    _write_account_database(gateway_root, "invalid_value", "invalid/account")
    _write_account_database(gateway_root, "path_id", "different_id")
    corrupt = account_paths(gateway_root, "corrupt").database
    corrupt.parent.mkdir(parents=True, exist_ok=True)
    corrupt.write_bytes(b"not a sqlite database")
    hermes_home = tmp_path / "hermes"
    _write_hermes_env(hermes_home, gateway_root, [])

    accounts, _ = configuration._discover_hermes_native_accounts(hermes_home)

    assert accounts == []


def test_provision_writes_discovered_real_ids_to_synthetic_hermes_env(tmp_path, monkeypatch):
    """配置生成复用发现逻辑，并只把真实账号 ID 写回 0600 环境文件。"""
    core_root = tmp_path / "core"
    core_root.mkdir()
    (core_root / "core.json").write_text(
        json.dumps({"storageRoot": "runtime/accounts"}), encoding="utf-8"
    )
    hermes_home = tmp_path / "hermes"
    gateway_root = hermes_home / "open-android-intelligence-gateway" / "accounts"
    _write_account_database(gateway_root, "alice", "alice")
    _write_account_database(gateway_root, "bob", "bob")
    _write_account_database(gateway_root, "oai_automatic", "oai_automatic")
    stale_hash = hashlib.sha256(b"alice").hexdigest()
    env_file = _write_hermes_env(
        hermes_home, gateway_root, ["alice", stale_hash], token=TEST_SESSION_TOKEN
    )
    monkeypatch.setenv("HERMES_HOME", str(hermes_home))
    monkeypatch.setenv("DSH_HOME", str(tmp_path / "absent-dsh"))

    configuration.provision_host_agent_files(core_root, "synthetic-core", signing_key=None)

    env = configuration._parse_dotenv(env_file.read_text(encoding="utf-8"))
    assert json.loads(env["OAI_HERMES_ACCOUNTS"]) == ["alice", "bob"]
    assert env["OAI_HERMES_SESSION_TOKEN"] == TEST_SESSION_TOKEN
    assert stat.S_IMODE(env_file.stat().st_mode) == 0o600
