"""Hermes-only bridge configuration and trusted Core pin regressions."""
from __future__ import annotations

import json
import types

import pytest

from oai_core.gateway import local_keys
from oai_hermes_adapter import bridge
from oai_hermes_adapter.bridge import AutoAccountSpaces


def _isolated_home(tmp_path, monkeypatch):
    home = tmp_path / "home"
    home.mkdir(mode=0o700)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("XDG_STATE_HOME", raising=False)
    monkeypatch.delenv(local_keys.MASTER_KEY_FILE_ENV, raising=False)
    monkeypatch.delenv(local_keys.MASTER_KEY_FILE_ENV_ALIAS, raising=False)
    return home


@pytest.fixture
def clean_hermes_env(monkeypatch):
    for name in ("OAI_HERMES_GATEWAY_ROOT", "OAI_HERMES_ACCOUNTS", "OAI_HERMES_SESSION_TOKEN",
                 "OAI_HERMES_SESSION_PORT", "OAI_HERMES_TRUSTED_CORES_FILE",
                 local_keys.MASTER_KEY_FILE_ENV):
        monkeypatch.delenv(name, raising=False)


def test_trusted_cores_reload_skips_pinned_conflict_and_loads_new_core(tmp_path):
    """A stale/conflicting historical core_id must neither overwrite its pin nor block new Core instances."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from oai_core.agent_registration import sign_core_request

    root = tmp_path / "accounts"
    root.mkdir(mode=0o700)
    trusted_file = tmp_path / "trusted-cores.json"
    old_priv = Ed25519PrivateKey.generate()
    impostor_priv = Ed25519PrivateKey.generate()
    new_priv = Ed25519PrivateKey.generate()
    old_pub = old_priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    impostor_pub = impostor_priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    new_pub = new_priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)

    spaces = AutoAccountSpaces(root, types.SimpleNamespace(storage_root=root), {"old-core": old_pub},
                               trusted_file=trusted_file)
    trusted_file.write_text(json.dumps({"old-core": impostor_pub.hex(), "new-core": new_pub.hex()}))
    trusted_file.chmod(0o600)

    payload_new = {"coreInstanceId": "new-core", "hermesInstanceId": "hermes", "challenge": "a" * 64}
    assert spaces.verified("new-core", payload_new, sign_core_request(new_priv, payload_new)) is True

    payload_old = {"coreInstanceId": "old-core", "hermesInstanceId": "hermes", "challenge": "b" * 64}
    assert spaces.verified("old-core", payload_old, sign_core_request(impostor_priv, payload_old)) is False
    assert spaces.verified("old-core", payload_old, sign_core_request(old_priv, payload_old)) is True


def test_bridge_configuration_reads_hermes_dotenv_before_process_restart(tmp_path, monkeypatch, clean_hermes_env):
    home = _isolated_home(tmp_path, monkeypatch)
    hermes_home = home / ".hermes"
    gateway_root = hermes_home / "open-android-intelligence-gateway" / "accounts"
    gateway_root.mkdir(parents=True, mode=0o700)
    trusted = gateway_root.parent / "trusted-cores.json"
    trusted.write_text("{}\n")
    trusted.chmod(0o600)
    token = "dotenv-bridge-token-" + "t" * 20
    (hermes_home / ".env").write_text(
        f"OAI_HERMES_SESSION_TOKEN={token}\n"
        f"OAI_HERMES_GATEWAY_ROOT={gateway_root}\n"
        'OAI_HERMES_ACCOUNTS=[]\n'
        "OAI_HERMES_SESSION_PORT=9892\n"
    )
    (hermes_home / ".env").chmod(0o600)
    monkeypatch.setenv("HERMES_HOME", str(hermes_home))

    assert bridge._configured_platform() is True
    cfg = bridge._bridge_configuration()
    assert cfg["token"] == token
    assert cfg["port"] == 9892
