"""Portable installer regressions: no host SDK, personal paths or live accounts."""
from contextlib import closing
import json
import os
from pathlib import Path
import runpy
import shlex
import stat
import subprocess
import sys

import pytest


REPOSITORY = Path(__file__).resolve().parents[1]
HELPER = runpy.run_path(str(REPOSITORY / "scripts" / "core_setup.py"))


def run_bash(source, *, environment=None):
    result = subprocess.run(["bash", "-c", source], cwd=REPOSITORY,
                            env=environment, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr + result.stdout
    return result


@pytest.mark.parametrize("name", ["install.sh", "setup.sh"])
def test_script_syntax(name):
    result = subprocess.run(["bash", "-n", str(REPOSITORY / "scripts" / name)], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


def test_install_script_resolves_its_own_repository():
    result = run_bash(f'source {shlex.quote(str(REPOSITORY / "scripts" / "install.sh"))}\nresolve_repo_root\nprintf "%s\\n" "$TARGET_DIR" "$IS_LOCAL_REPO"')
    assert result.stdout.splitlines()[-2:] == [str(REPOSITORY), "1"]


@pytest.fixture
def install_directory(tmp_path):
    binary = tmp_path / ".venv" / "bin"
    binary.mkdir(parents=True)
    (binary / "python").write_text("#!/usr/bin/env bash\nexec " + shlex.quote(sys.executable) + ' "$@"\n')
    (binary / "python").chmod(0o700)
    return tmp_path


@pytest.mark.parametrize("name", ["install.sh", "setup.sh"])
def test_wizards_create_account_and_keep_tokens_out_of_start_script(name, install_directory):
    root = install_directory
    environment = dict(os.environ, PYTHONPATH=f"{REPOSITORY / 'oai-core' / 'src'}:/home/djbd/.oai-core/.venv/lib/python3.14/site-packages")
    environment.pop("OAI_HERMES_SESSION_TOKEN", None)
    environment.pop("OAI_DSH_ADAPTER_TOKEN", None)
    source = f'''
source {shlex.quote(str(REPOSITORY / "scripts" / name))}
TARGET_DIR={shlex.quote(str(root))}
CONFIG_FILE="$TARGET_DIR/core.json"
VENV_PYTHON="$TARGET_DIR/.venv/bin/python"
prompt_read() {{ printf -v "$2" '%s' "${{3:-}}"; }}
prompt_password() {{ printf -v "$2" '%s' 'synthetic-installer-password'; }}
INSTALL_HERMES_PLUGIN=1
INSTALL_DSH_PLUGIN=1
INSTALL_CODEX_CONFIG=1
INSTALL_HERMES=1
INSTALL_DSH=1
INSTALL_CODEX=1
CFG_HOST=127.0.0.1
CFG_PORT=8045
CFG_USERNAME=alice
CFG_PASSWORD=synthetic-installer-password
'''
    source += "guide_initial_configuration\n" if name == "install.sh" else "save_configuration_and_account\n"
    run_bash(source, environment=environment)
    config = json.loads((root / "core.json").read_text())
    assert config["listen"] == {"host": "127.0.0.1", "port": 8045}
    assert config.get("allowInsecureHttp") is True
    assert {entry["type"] for entry in config["agents"]} == {"dsh", "hermes", "codex"}
    assert next(entry for entry in config["agents"] if entry["type"] == "hermes")["accounts"] == {"alice": "alice"}
    tokens = HELPER["read_tokens"](root / "runtime" / "secrets" / "adapter-tokens.env")
    assert set(tokens) == {"OAI_HERMES_SESSION_TOKEN", "OAI_DSH_ADAPTER_TOKEN"}
    start = (root / "start-core.sh").read_text()
    assert all(len(value) >= 32 and value not in start for value in tokens.values())
    assert "synthetic-installer-password" not in start
    assert stat.S_IMODE((root / "core.json").stat().st_mode) == 0o600
    assert stat.S_IMODE((root / "runtime" / "secrets" / "adapter-tokens.env").stat().st_mode) == 0o600
    assert stat.S_IMODE((root / "start-core.sh").stat().st_mode) == 0o700


def test_reconfigure_preserves_account_key_custom_paths_and_bridge_tokens(tmp_path, monkeypatch):
    from oai_core.server import create_core, load_config
    monkeypatch.delenv("OAI_HERMES_SESSION_TOKEN", raising=False)
    monkeypatch.delenv("OAI_DSH_ADAPTER_TOKEN", raising=False)
    monkeypatch.setenv("OAI_INIT_ADMIN_PASSWORD", "synthetic-original-password")
    configure = HELPER["configure"]
    (tmp_path / "core.json").write_text(json.dumps({
        "storageRoot": "existing/accounts", "masterKeyFile": "existing/secrets/old-master-key",
        "agents": [{"id": "separate-codex", "type": "codex", "command": ["codex", "app-server"], "codexHome": "existing/codex"}],
    }))
    configure(tmp_path, "127.0.0.1", 8045, "alice", ["hermes"], "legacy-alice")
    initial = json.loads((tmp_path / "core.json").read_text())
    key_path = tmp_path / initial["masterKeyFile"]
    initial_key = key_path.read_bytes()
    token_path = tmp_path / "runtime" / "secrets" / "adapter-tokens.env"
    initial_tokens = token_path.read_text()
    # Preserve an administrator-managed path and a configured Core/Hermes mapping.
    initial["unrelatedAdminSetting"] = {"enabled": True}
    initial["tlsSpkiSha256"] = "sha256:" + "1" * 64
    (tmp_path / "core.json").write_text(json.dumps(initial))
    monkeypatch.setenv("OAI_INIT_ADMIN_PASSWORD", "synthetic-new-password-must-be-ignored")
    configure(tmp_path, "127.0.0.1", 9045, "alice", ["hermes"], "another-account-must-be-ignored")
    updated = json.loads((tmp_path / "core.json").read_text())
    assert updated["storageRoot"] == initial["storageRoot"]
    assert updated["masterKeyFile"] == initial["masterKeyFile"]
    assert updated["tlsSpkiSha256"] == initial["tlsSpkiSha256"]
    assert updated["unrelatedAdminSetting"] == {"enabled": True}
    assert next(entry for entry in updated["agents"] if entry["type"] == "hermes")["accounts"] == {"alice": "legacy-alice"}
    assert updated["agents"][0] == initial["agents"][0]
    assert key_path.read_bytes() == initial_key
    assert token_path.read_text() == initial_tokens
    backups = list((tmp_path / "runtime" / "configuration-backups").glob("*.json"))
    assert any(json.loads(path.read_text()) == initial for path in backups)
    core = create_core(load_config(tmp_path / "core.json"))
    with closing(core.open_gateway_account("alice")) as account:
        assert account.credentials.verify_password("synthetic-original-password")
        assert not account.credentials.verify_password("synthetic-new-password-must-be-ignored")


def test_start_script_loads_private_environment_and_refuses_readable_secrets(install_directory):
    root = install_directory
    private_directory = root / "runtime" / "secrets"
    HELPER["private_directory"](private_directory)
    token = "synthetic-bridge-token-contains-a-quote'and-dollar$-1234567890"
    HELPER["private_write"](private_directory / "adapter-tokens.env", "OAI_HERMES_SESSION_TOKEN=" + shlex.quote(token) + "\n")
    HELPER["write_start_script"](root)
    (root / ".venv" / "bin" / "oai").write_text("#!/usr/bin/env python3\nimport json, os, sys\nprint(json.dumps({'token':os.environ['OAI_HERMES_SESSION_TOKEN'],'args':sys.argv[1:]}))\n")
    (root / ".venv" / "bin" / "oai").chmod(0o700)
    output = subprocess.run([str(root / "start-core.sh")], capture_output=True, text=True, check=True)
    actual = json.loads(output.stdout)
    assert actual == {"token": token, "args": ["serve", "--config", str(root / "core.json")]}
    (private_directory / "adapter-tokens.env").chmod(0o644)
    rejected = subprocess.run([str(root / "start-core.sh")], capture_output=True, text=True)
    assert rejected.returncode != 0
    assert token not in rejected.stdout + rejected.stderr


def test_rejects_symlink_and_insecure_token_file(tmp_path):
    external = tmp_path / "external"
    external.write_text("OAI_HERMES_SESSION_TOKEN=synthetic-token-12345678901234567890\n")
    external.chmod(0o644)
    with pytest.raises(ValueError, match="0600"):
        HELPER["read_tokens"](external)
    linked = tmp_path / "linked"
    linked.symlink_to(external)
    with pytest.raises(OSError):
        HELPER["read_tokens"](linked)
    with pytest.raises(ValueError, match="symlink"):
        HELPER["private_write"](linked, "ignored")


def test_existing_accounts_with_missing_key_are_not_rekeyed(tmp_path):
    database = tmp_path / "existing" / "accounts" / "synthetic-account" / "gateway.sqlite"
    database.parent.mkdir(parents=True)
    database.write_bytes(b"synthetic database sentinel")
    config_path = tmp_path / "core.json"
    original = json.dumps({"storageRoot": "existing/accounts", "masterKeyFile": "existing/secrets/missing-key", "agents": []})
    config_path.write_text(original)
    with pytest.raises(ValueError, match="original master key"):
        HELPER["configure"](tmp_path, "127.0.0.1", 8045, "alice", [], None)
    assert config_path.read_text() == original
    assert database.read_bytes() == b"synthetic database sentinel"
    assert not (tmp_path / "existing" / "secrets" / "missing-key").exists()


def test_core_only_configuration_creates_no_hermes_reference(tmp_path, monkeypatch):
    monkeypatch.setenv("OAI_INIT_ADMIN_PASSWORD", "synthetic-core-only-password")
    monkeypatch.setenv("OAI_HERMES_SESSION_TOKEN", "synthetic-unused-environment-token-1234567890")
    HELPER["configure"](tmp_path, "127.0.0.1", 8045, "alice", [], None)
    config = json.loads((tmp_path / "core.json").read_text())
    assert config["agents"] == []
    assert HELPER["read_tokens"](tmp_path / "runtime" / "secrets" / "adapter-tokens.env") == {}


def test_legacy_start_script_tokens_migrate_without_rotation_or_execution(tmp_path, monkeypatch):
    monkeypatch.delenv("OAI_HERMES_SESSION_TOKEN", raising=False)
    monkeypatch.setenv("OAI_INIT_ADMIN_PASSWORD", "synthetic-old-installer-password")
    configure = HELPER["configure"]
    configure(tmp_path, "127.0.0.1", 8045, "alice", ["hermes"], "alice")
    token_path = tmp_path / "runtime" / "secrets" / "adapter-tokens.env"
    original_token = HELPER["read_tokens"](token_path)["OAI_HERMES_SESSION_TOKEN"]
    token_path.rename(token_path.with_suffix(".legacy"))
    sentinel = tmp_path / "must-not-be-executed"
    (tmp_path / "start-core.sh").write_text(
        'export OAI_HERMES_SESSION_TOKEN="${OAI_HERMES_SESSION_TOKEN:-' + original_token + '}"\n'
        + "touch " + shlex.quote(str(sentinel)) + "\n"
    )
    # Existing configured agents and credentials survive even when none is added.
    configure(tmp_path, "127.0.0.1", 9045, "alice", [], None)
    assert HELPER["read_tokens"](token_path)["OAI_HERMES_SESSION_TOKEN"] == original_token
    assert original_token not in (tmp_path / "start-core.sh").read_text()
    assert not sentinel.exists()
    assert json.loads((tmp_path / "core.json").read_text())["agents"][0]["type"] == "hermes"


def test_existing_unrecoverable_bridge_token_is_not_silently_rotated(tmp_path, monkeypatch):
    monkeypatch.delenv("OAI_HERMES_SESSION_TOKEN", raising=False)
    config_path = tmp_path / "core.json"
    original = json.dumps({"storageRoot": "runtime/accounts", "masterKeyFile": "runtime/secrets/master",
                           "agents": [{"id": "hermes", "type": "hermes", "tokenEnv": "OAI_HERMES_SESSION_TOKEN", "accounts": {"alice": "alice"}}]})
    config_path.write_text(original)
    with pytest.raises(ValueError, match="will not be silently rotated"):
        HELPER["configure"](tmp_path, "127.0.0.1", 8045, "alice", [], None)
    assert config_path.read_text() == original
    assert not (tmp_path / "runtime" / "secrets" / "adapter-tokens.env").exists()


def test_detected_hermes_does_not_install_or_disable_host_plugin():
    source = f'''
source {shlex.quote(str(REPOSITORY / "scripts" / "install.sh"))}
DETECTED_HERMES=1
DETECTED_DSH=0
DETECTED_CODEX=0
prompt_read() {{ printf -v "$2" '%s' n; }}
hermes() {{ printf 'host mutation attempted\\n' >&2; return 99; }}
configure_agent_plugins
printf 'selected=%s\\n' "$INSTALL_HERMES_PLUGIN"
'''
    result = run_bash(source)
    assert "selected=0" in result.stdout
    assert "host mutation attempted" not in result.stderr
