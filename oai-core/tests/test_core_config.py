"""Deployment failures must preserve encrypted accounts and explicit contracts."""
import json

import pytest

from oai_core.gateway.core import ContractRegistry, GatewayError
from oai_core.server import create_core, load_config


def test_missing_master_key_never_replaces_an_existing_accounts_key(tmp_path):
    settings = {"storageRoot": str(tmp_path / "accounts"), "masterKeyFile": str(tmp_path / "secrets/key")}
    core = create_core(settings)
    account = core.open_gateway_account("alice")
    try:
        with account.store.transaction():
            sealed = account.store.seal_json({"preserved": True}, "migration-check")
            account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?,?)", ("migration-check", sealed))
    finally:
        account.close()
    key = tmp_path / "secrets/key"
    backup = key.with_suffix(".backup")
    key.rename(backup)
    with pytest.raises(ValueError, match="original master key"):
        create_core(settings)
    assert not key.exists()
    backup.rename(key)
    restored = create_core(settings).open_gateway_account("alice")
    try:
        sealed = restored.store.database.execute("SELECT value FROM account_metadata WHERE key='migration-check'").fetchone()[0]
        assert restored.store.open_json(sealed, "migration-check") == {"preserved": True}
    finally:
        restored.close()


def test_default_contract_is_optional_but_an_explicit_invalid_contract_fails(tmp_path):
    config = tmp_path / "core.json"
    config.write_text(json.dumps({"storageRoot": "accounts", "masterKeyFile": "secrets/key"}))
    settings = load_config(config)
    assert create_core(settings).contracts.core_schema_hash == ContractRegistry().core_schema_hash
    with pytest.raises(GatewayError, match="INTERNAL_ERROR"):
        create_core({**settings, "contractRoot": str(tmp_path / "missing-contract")})
