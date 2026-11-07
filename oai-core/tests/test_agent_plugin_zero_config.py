"""Zero-configuration key and platform-registration regressions (ADR 0061).

The agent plugin must be usable straight after ``hermes gateway setup``:
a missing key file is provisioned automatically, an unwritable location
degrades to an in-process key instead of blocking startup, and the Hermes
platform stays visible in the setup menu before ``OAI_HERMES_*`` is filled in.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import stat
import types

import pytest

from oai_core.gateway import local_keys
from oai_core.gateway.local_keys import (
    LocalMasterKeyStore,
    MasterKeyUnavailable,
    VolatileMasterKeyStore,
    default_master_key_path,
    default_session_key_path,
    resolve_local_master_key_store,
)


def _isolated_home(tmp_path, monkeypatch):
    home = tmp_path / "home"
    home.mkdir(mode=0o700)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("XDG_STATE_HOME", raising=False)
    monkeypatch.delenv(local_keys.MASTER_KEY_FILE_ENV, raising=False)
    monkeypatch.delenv(local_keys.MASTER_KEY_FILE_ENV_ALIAS, raising=False)
    return home


def test_missing_key_is_provisioned_automatically_without_operator_steps(tmp_path, monkeypatch):
    home = _isolated_home(tmp_path, monkeypatch)
    store = resolve_local_master_key_store(environ={})
    expected = home / ".open-android-intelligence" / "gateway-master-key"
    assert isinstance(store, LocalMasterKeyStore) and store.path == expected
    info = expected.stat()
    assert stat.S_IMODE(info.st_mode) == 0o600 and info.st_size == 32
    # A second resolution reuses the key instead of rotating it.
    assert resolve_local_master_key_store(environ={}).path == expected


def test_existing_key_is_never_rotated_and_derived_references_stay_stable(tmp_path, monkeypatch):
    _isolated_home(tmp_path, monkeypatch)
    first = resolve_local_master_key_store(environ={})
    reference = first.get_or_create_aead("open-android-intelligence-gateway/alice").reference
    raw = first.path.read_bytes()
    second = resolve_local_master_key_store(environ={})
    assert second.path == first.path and second.path.read_bytes() == raw
    assert second.get_or_create_aead("open-android-intelligence-gateway/alice").reference == reference


def test_session_host_uses_its_own_key_and_ignores_the_device_core_location(tmp_path, monkeypatch):
    home = _isolated_home(tmp_path, monkeypatch)
    store = resolve_local_master_key_store(environ={}, fallback_path=default_session_key_path())
    assert store.path == home / ".open-android-intelligence" / "session-master-key"
    assert store.path != default_master_key_path()
    assert not default_master_key_path().exists()


def test_existing_legacy_key_wins_over_provisioning_a_second_one(tmp_path, monkeypatch):
    home = _isolated_home(tmp_path, monkeypatch)
    legacy = home / ".open-android-intelligence" / "gateway-master-key"
    legacy.parent.mkdir(mode=0o700)
    legacy.write_bytes(b"k" * 32)
    legacy.chmod(0o600)
    # A session host asking for its dedicated path still reuses an existing
    # default-location key only when no dedicated file exists yet...
    store = resolve_local_master_key_store(environ={}, fallback_path=home / "missing" / "dedicated")
    assert store.path == legacy


def test_unwritable_locations_degrade_to_a_volatile_key_instead_of_blocking(tmp_path, monkeypatch):
    home = _isolated_home(tmp_path, monkeypatch)
    # A regular file where each key directory must be makes every candidate fail.
    (home / ".open-android-intelligence").write_text("not a directory")
    blocked_state = tmp_path / "state-file"
    blocked_state.write_text("not a directory")
    store = resolve_local_master_key_store(environ={"XDG_STATE_HOME": str(blocked_state)})
    assert isinstance(store, VolatileMasterKeyStore) and store.ephemeral is True
    cipher = store.get_or_create_aead("open-android-intelligence-gateway/alice")
    sealed = cipher.encrypt(b"payload", b"purpose")
    assert cipher.decrypt(sealed, b"purpose") == b"payload"


def test_configured_path_is_the_only_candidate(tmp_path, monkeypatch):
    home = _isolated_home(tmp_path, monkeypatch)
    configured = tmp_path / "custom" / "key.bin"
    store = resolve_local_master_key_store(environ={local_keys.MASTER_KEY_FILE_ENV: str(configured)})
    assert store.path == configured and configured.exists()
    assert not (home / ".open-android-intelligence").exists()


def test_unsafe_existing_file_still_fails_closed(tmp_path, monkeypatch):
    home = _isolated_home(tmp_path, monkeypatch)
    key = home / ".open-android-intelligence" / "gateway-master-key"
    key.parent.mkdir(mode=0o700)
    key.write_bytes(b"k" * 32)
    key.chmod(0o644)
    store = resolve_local_master_key_store(environ={})
    with pytest.raises(MasterKeyUnavailable):
        store.validate()


def test_missing_configured_file_reports_actionable_text_without_legacy_cli(tmp_path, monkeypatch):
    _isolated_home(tmp_path, monkeypatch)
    key = tmp_path / "configured" / "key"
    key.parent.mkdir()
    key.write_bytes(b"k" * 32)
    key.chmod(0o600)
    store = LocalMasterKeyStore(key)
    key.unlink()
    with pytest.raises(MasterKeyUnavailable) as caught:
        store.validate()
    message = str(caught.value)
    assert "init-key" not in message and "hermes-account.py" not in message


class _PlatformRecorder:
    def __init__(self):
        self.entries = []
        self.hooks = {}

    def register_platform(self, **kwargs):
        self.entries.append(kwargs)

    def register_hook(self, name, callback):
        self.hooks[name] = callback


def _load_bridge(monkeypatch):
    """Bridge registration needs the pinned host SDK; skip rather than fake it."""
    sdk = os.environ.get("HERMES_SDK_ROOT")
    if not sdk:
        pytest.skip("requires the pinned Hermes SDK")
    monkeypatch.syspath_prepend(sdk)
    pytest.importorskip("gateway.config")
    return pytest.importorskip("oai_hermes_adapter.bridge",
                               reason="optional Hermes session Adapter is not installed")


@pytest.fixture
def clean_hermes_env(monkeypatch):
    for name in ("OAI_HERMES_GATEWAY_ROOT", "OAI_HERMES_ACCOUNTS", "OAI_HERMES_SESSION_TOKEN",
                 "OAI_HERMES_SESSION_PORT", "OAI_HERMES_TRUSTED_CORES_FILE",
                 local_keys.MASTER_KEY_FILE_ENV):
        monkeypatch.delenv(name, raising=False)


def test_plugin_registers_even_before_the_operator_configures_hermes(tmp_path, monkeypatch, clean_hermes_env):
    """A missing OAI_HERMES_* must not make the plugin vanish from the setup menu."""
    _isolated_home(tmp_path, monkeypatch)
    bridge = _load_bridge(monkeypatch)
    ctx = _PlatformRecorder()
    bridge.register(ctx)
    names = [entry["name"] for entry in ctx.entries]
    assert {"open-android-intelligence-gateway", "open_android", "open_android_intelligence"} <= set(names)
    entry = ctx.entries[0]
    assert entry["is_connected"](None) is False
    assert "OAI_HERMES_GATEWAY_ROOT" in entry["required_env"]
    with pytest.raises(ValueError, match="OAI_HERMES_|尚未配置"):
        entry["adapter_factory"](types.SimpleNamespace(enabled=True, extra={}))


def test_configured_platform_reports_connected_and_prepares_its_own_key(tmp_path, monkeypatch, clean_hermes_env):
    _isolated_home(tmp_path, monkeypatch)
    bridge = _load_bridge(monkeypatch)
    backend_module = pytest.importorskip("oai_hermes_adapter.backend")
    root = tmp_path / "hermes-accounts"
    root.mkdir(mode=0o700)
    secret = tmp_path / "bridge.key"
    secret.write_text("b" * 40)
    secret.chmod(0o600)
    monkeypatch.setenv("OAI_HERMES_GATEWAY_ROOT", str(root))
    monkeypatch.setenv("OAI_HERMES_ACCOUNTS", json.dumps([]))
    monkeypatch.setenv("OAI_HERMES_SESSION_SECRET_FILE", str(secret))
    # Core's provisioning writes this next to the account root; its presence is
    # what allows an empty allowlist until the first native space is created.
    trusted = tmp_path / "trusted-cores.json"
    trusted.write_text("{}")
    trusted.chmod(0o600)
    ctx = _PlatformRecorder()
    bridge.register(ctx)
    entry = ctx.entries[0]
    assert entry["is_connected"](None) is True
    # The session host prepares its own key file on first real use, without any
    # OAI master key or manual init step, and never writes into the account root.
    backend = backend_module.SessionBackend(root, [])
    try:
        key = Path.home() / ".open-android-intelligence" / "session-master-key"
        assert isinstance(backend.secret_store, LocalMasterKeyStore)
        assert backend.secret_store.path == key
        assert key.is_file() and stat.S_IMODE(key.stat().st_mode) == 0o600
        assert not (root / "session-master-key").exists()
    finally:
        backend.close()


def test_provision_preserves_secret_file_and_updates_custom_trusted_file_and_dsh_homes(tmp_path, monkeypatch):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from oai_core import configuration

    home = _isolated_home(tmp_path, monkeypatch)
    hermes_home = home / ".hermes"
    hermes_home.mkdir(mode=0o700)
    custom_secret = hermes_home / "session.secret"
    existing_token = "existing-hermes-secret-" + "x" * 20
    custom_secret.write_text(existing_token + "\n")
    custom_secret.chmod(0o600)
    custom_trusted = hermes_home / "custom-trusted-cores.json"
    custom_trusted.write_text("{}\n")
    custom_trusted.chmod(0o600)
    (hermes_home / ".env").write_text(
        f"OAI_HERMES_SESSION_SECRET_FILE={custom_secret}\n"
        f"OAI_HERMES_TRUSTED_CORES_FILE={custom_trusted}\n"
    )
    (hermes_home / ".env").chmod(0o600)

    dsh_official = home / ".dsh"
    dsh_legacy = home / ".dsh-cline"
    dsh_official.mkdir(mode=0o700)
    dsh_legacy.mkdir(mode=0o700)
    official_dsh_tok = "official-dsh-token-" + "d" * 20
    (dsh_official / ".env").write_text(f"OAI_DSH_ADAPTER_TOKEN={official_dsh_tok}\n")
    (dsh_official / ".env").chmod(0o600)
    (dsh_legacy / ".env").write_text("OAI_DSH_ADAPTER_TOKEN=stale-legacy-token-" + "z" * 20 + "\n")
    (dsh_legacy / ".env").chmod(0o600)
    monkeypatch.delenv("HERMES_HOME", raising=False)
    monkeypatch.delenv("DSH_HOME", raising=False)

    core_root = tmp_path / "core"
    core_root.mkdir(mode=0o700)
    configuration.private_write(
        core_root / "core.json",
        json.dumps({"storageRoot": "runtime/accounts", "agents": []}) + "\n",
    )
    signing_key = Ed25519PrivateKey.generate()
    manifests = configuration.provision_host_agent_files(core_root, "core-inst-1", signing_key)
    assert {m["id"] for m in manifests} == {"hermes", "dsh"}

    env_after = configuration._parse_dotenv((hermes_home / ".env").read_text())
    assert env_after["OAI_HERMES_SESSION_TOKEN"] == existing_token
    assert "OAI_HERMES_SESSION_SECRET_FILE" not in env_after
    assert "core-inst-1" in json.loads(custom_trusted.read_text())
    assert (core_root / "runtime/accounts/.agent-secrets/agent-dsh.secret").read_text().strip() == official_dsh_tok
    assert configuration._parse_dotenv((dsh_legacy / ".env").read_text())["OAI_DSH_ADAPTER_TOKEN"] == official_dsh_tok
