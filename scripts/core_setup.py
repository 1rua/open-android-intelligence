"""Shared installer configuration with private credentials and preserved state."""
from __future__ import annotations

import argparse
import asyncio
from contextlib import contextmanager
import copy
import hashlib
import ipaddress
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import stat
import sys
from urllib.parse import urlsplit


TOKEN_NAMES = {"OAI_DSH_ADAPTER_TOKEN", "OAI_HERMES_SESSION_TOKEN"}


def private_directory(path: Path) -> None:
    if path.is_symlink():
        raise ValueError(f"Private directory must not be a symlink: {path}")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.stat().st_uid != os.geteuid():
        raise ValueError(f"Private directory must belong to the current user: {path}")
    path.chmod(0o700)


def private_read(path: Path) -> str:
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor) as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_mode & 0o077:
            raise ValueError(f"Credential file requires current ownership and mode 0600: {path}")
        return source.read()


def private_write(path: Path, text: str, *, executable: bool = False) -> None:
    private_write_bytes(path, text.encode("utf-8"), 0o700 if executable else 0o600)


def fsync_directory(path: Path) -> None:
    """Persist a rename on POSIX; Windows does not expose directory fsync."""
    if os.name == "nt":
        return
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def private_write_bytes(path: Path, content: bytes, mode: int = 0o600) -> None:
    if path.is_symlink():
        raise ValueError(f"Configuration file must not be a symlink: {path}")
    temporary = path.with_name(path.name + "." + secrets.token_hex(8) + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as target:
        target.write(content)
        target.flush()
        os.fsync(target.fileno())
        os.fchmod(target.fileno(), mode)
        os.fsync(target.fileno())
    os.replace(temporary, path)
    fsync_directory(path.parent)


def owned_bytes(path: Path, *, private: bool = False) -> bytes | None:
    if path.is_symlink():
        raise ValueError(f"Configuration file must not be a symlink: {path}")
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return None
    with os.fdopen(descriptor, "rb") as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid():
            raise ValueError(f"Configuration file must be owned by the current user: {path}")
        if private and metadata.st_mode & 0o077:
            raise ValueError(f"Credential file requires mode 0600: {path}")
        return source.read()


def content_hash(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def path_hash(path: Path, kind: str = "file") -> str | None:
    if path.is_symlink():
        raise ValueError("Configuration recovery refuses symlinks")
    if not path.exists():
        return None
    if kind == "file":
        return content_hash(owned_bytes(path))
    if not path.is_dir():
        raise ValueError("Configuration recovery expected an account directory")
    manifest = []
    for parent, directories, files in os.walk(path, followlinks=False):
        for name in sorted([*directories, *files]):
            item = Path(parent) / name
            if item.is_symlink():
                raise ValueError("Configuration recovery refuses symlinks")
            relative = item.relative_to(path).as_posix()
            manifest.append((relative, "directory" if item.is_dir() else content_hash(owned_bytes(item))))
    return content_hash(json.dumps(sorted(manifest)).encode())


@contextmanager
def configuration_lock(root: Path):
    private_directory(root / "runtime")
    descriptor = os.open(root / "runtime" / "configuration.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_mode & 0o077:
            raise ValueError("Configuration lock requires current ownership and mode 0600")
        if os.name == "nt":
            import msvcrt
            if metadata.st_size == 0:
                os.write(descriptor, b"0")
            os.lseek(descriptor, 0, os.SEEK_SET)
            msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)


class ConfigurationTransaction:
    """Verified before-images and a durable undo journal for a single save.

    Backups and interrupted candidates are retained. Recovery refuses to replace
    data changed by another writer or to use a backup whose hash no longer matches.
    """

    def __init__(self, root: Path):
        self.root = root
        self.journal = root / "runtime" / "configuration-journal.json"
        self.identifier = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + secrets.token_hex(4)
        self.directory = root / "runtime" / "configuration-backups" / self.identifier
        private_directory(self.directory)
        fsync_directory(self.directory.parent)
        self.snapshots = {}
        self.guards = {}
        self.state = {"version": 1, "id": self.identifier, "status": "preparing", "entries": []}
        self.persist()

    def relative(self, path: Path) -> str:
        return os.path.relpath(path, self.root)

    def persist(self) -> None:
        text = json.dumps(self.state, ensure_ascii=False, indent=2) + "\n"
        private_write(self.directory / "journal.json", text)
        private_write(self.journal, text)

    def read(self, path: Path, *, private: bool = False) -> str | None:
        if path not in self.snapshots:
            self.snapshots[path] = owned_bytes(path, private=private)
        content = self.snapshots[path]
        return content.decode("utf-8") if content is not None else None

    def watch_file(self, path: Path) -> None:
        self.guards[path] = owned_bytes(path, private=True)

    def add_file(self, path: Path, text: str | bytes, mode: int = 0o600) -> None:
        if any(self.root / entry["target"] == path for entry in self.state["entries"]):
            raise ValueError("Ambiguous configuration target")
        if path not in self.snapshots:
            self.snapshots[path] = owned_bytes(path)
        before = self.snapshots[path]
        if owned_bytes(path) != before:
            raise ValueError("Configuration changed during preparation; retry after stopping the other writer")
        index = len(self.state["entries"])
        staged = self.directory / f"new-{index}"
        backup = self.directory / f"old-{index}"
        content = text.encode("utf-8") if isinstance(text, str) else text
        private_write_bytes(staged, content)
        if before is not None:
            private_write_bytes(backup, before)
        self.state["entries"].append({
            "kind": "file", "target": self.relative(path), "staged": self.relative(staged),
            "backup": self.relative(backup) if before is not None else None,
            "before": content_hash(before) if before is not None else None,
            "after": content_hash(content), "mode": mode,
            "old_mode": stat.S_IMODE(path.stat().st_mode) if before is not None else None,
            "retained": self.relative(path.with_name(path.name + "." + self.identifier + ".rolled-back")),
        })

    def add_account(self, staged: Path, target: Path) -> None:
        if target.exists() or target.is_symlink():
            raise ValueError("Unregistered account directory already exists; preserve it and inspect manually")
        for parent, _, files in os.walk(staged):
            for name in files:
                descriptor = os.open(Path(parent) / name, os.O_RDONLY | os.O_NOFOLLOW)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
            fsync_directory(Path(parent))
        self.state["entries"].append({"kind": "directory", "target": self.relative(target),
                                      "staged": self.relative(staged), "backup": None, "before": None,
                                      "after": path_hash(staged, "directory")})

    def verify_backups(self) -> None:
        for entry in self.state["entries"]:
            if entry["before"] is not None:
                if path_hash(self.root / entry["backup"]) != entry["before"]:
                    raise ValueError("Configuration backup verification failed; preserve the recovery journal")

    def commit(self) -> None:
        for path, content in self.guards.items():
            if owned_bytes(path, private=True) != content:
                raise ValueError("Configuration changed during preparation; preserve it and retry")
        self.verify_backups()
        for entry in self.state["entries"]:
            if path_hash(self.root / entry["target"], entry["kind"]) != entry["before"]:
                raise ValueError("Configuration changed during preparation; preserve it and retry")
            if path_hash(self.root / entry["staged"], entry["kind"]) != entry["after"]:
                raise ValueError("Prepared configuration verification failed")
        self.state["status"] = "prepared"
        self.persist()
        self.state["status"] = "applying"
        self.persist()
        for entry in self.state["entries"]:
            target = self.root / entry["target"]
            staged = self.root / entry["staged"]
            if entry["kind"] == "directory":
                os.replace(staged, target)
                fsync_directory(staged.parent)
                fsync_directory(target.parent)
            else:
                private_write_bytes(target, owned_bytes(staged), entry["mode"])
        self.state["status"] = "committed"
        self.persist()

    def rollback(self) -> None:
        if self.state["status"] in {"preparing", "prepared"}:
            self.state["status"] = "rolled_back"
            self.persist()
            return
        self.verify_backups()
        # Check every target before undoing any of them. Never overwrite a third
        # state left by an external writer after an interrupted save.
        for entry in self.state["entries"]:
            actual = path_hash(self.root / entry["target"], entry["kind"])
            if actual not in {entry["before"], entry["after"]}:
                raise ValueError("Configuration changed after interruption; preserve journal and inspect manually")
        for entry in reversed(self.state["entries"]):
            target = self.root / entry["target"]
            actual = path_hash(target, entry["kind"])
            if entry["before"] is None:
                if actual is not None:
                    retained = self.root / entry.get("retained", entry["staged"])
                    if retained.exists() or retained.is_symlink():
                        raise ValueError("Recovery retention path already exists; preserve journal")
                    os.replace(target, retained)
                    fsync_directory(target.parent)
                    fsync_directory(retained.parent)
            elif actual != entry["before"] or stat.S_IMODE(target.stat().st_mode) != entry["old_mode"]:
                private_write_bytes(target, owned_bytes(self.root / entry["backup"]), entry["old_mode"])
        self.state["status"] = "rolled_back"
        self.persist()

    @classmethod
    def recover(cls, root: Path) -> None:
        journal = root / "runtime" / "configuration-journal.json"
        content = owned_bytes(journal, private=True)
        if content is None:
            return
        state = json.loads(content)
        if state.get("version") != 1 or state.get("status") not in {"preparing", "prepared", "applying", "committed", "rolled_back"}:
            raise ValueError("Invalid configuration recovery journal; preserve it for manual recovery")
        if state["status"] in {"committed", "rolled_back"}:
            return
        transaction = cls.__new__(cls)
        transaction.root, transaction.journal, transaction.state = root, journal, state
        transaction.identifier = state["id"]
        transaction.directory = root / "runtime" / "configuration-backups" / transaction.identifier
        transaction.rollback()
        print("[INFO] 已从恢复 journal 还原上次未完成的配置；原主密钥与账号保持不变。")



def read_tokens(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    tokens = {}
    for line in private_read(path).splitlines():
        pieces = shlex.split(line)
        if len(pieces) != 1 or "=" not in pieces[0]:
            raise ValueError("Invalid adapter credential file")
        name, value = pieces[0].split("=", 1)
        if name not in TOKEN_NAMES or name in tokens:
            raise ValueError("Invalid adapter credential name")
        if len(value) < 32 or any(character in value for character in "\r\n\0"):
            raise ValueError("Invalid adapter credential value")
        tokens[name] = value
    return tokens


def legacy_start_tokens(path: Path) -> dict[str, str]:
    """Migrate only the old installer's literal exports, never evaluate shell."""
    if not path.exists():
        return {}
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor) as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid():
            raise ValueError("Existing startup script must belong to the current user")
        contents = source.read()
    tokens = {}
    for line in contents.splitlines():
        match = re.fullmatch(r'export (OAI_(?:DSH_ADAPTER|HERMES_SESSION)_TOKEN)="\$\{\1:-([A-Za-z0-9_-]{32,})\}"', line.strip())
        if match:
            if match[1] in tokens:
                raise ValueError("Ambiguous credentials in the existing startup script")
            tokens[match[1]] = match[2]
    return tokens


def write_start_script(root: Path, *, transaction: ConfigurationTransaction | None = None) -> None:
    # The executable contains no credential value. The private parent directory
    # and ownership/mode check protect the sourced environment file.
    text = '''#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIR}"
PYTHON="${DIR}/.venv/bin/python"
OAI="${DIR}/.venv/bin/oai"
ENV_FILE="${DIR}/runtime/secrets/adapter-tokens.env"
JOURNAL_FILE="${DIR}/runtime/configuration-journal.json"
"${PYTHON}" - "${JOURNAL_FILE}" <<'JOURNAL'
import json
import os
from pathlib import Path
import stat
import sys
path = Path(sys.argv[1])
if path.is_symlink():
    raise SystemExit("Configuration journal must not be a symlink")
if path.exists():
    metadata = path.stat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_mode & 0o077:
        raise SystemExit("Configuration journal requires owner-only access")
    try:
        state = json.loads(path.read_text())
        ready = state.get("version") == 1 and state.get("status") in {"committed", "rolled_back"}
    except Exception:
        ready = False
    if not ready:
        raise SystemExit("Configuration journal requires recovery; rerun scripts/setup.sh before starting Core")
JOURNAL
if [ -f "${ENV_FILE}" ]; then
    "${PYTHON}" - "${ENV_FILE}" <<'CHECK'
import os
from pathlib import Path
import stat
import sys
from urllib.parse import urlsplit
import sys
path = Path(sys.argv[1])
metadata = path.lstat()
parent = path.parent.lstat()
if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid()
        or metadata.st_mode & 0o077 or not stat.S_ISDIR(parent.st_mode)
        or parent.st_uid != os.geteuid() or parent.st_mode & 0o077):
    raise SystemExit("Adapter credential file requires owner-only access")
CHECK
    set -a
    # shellcheck disable=SC1090
    source "${ENV_FILE}"
    set +a
fi
exec "${OAI}" serve --config "${DIR}/core.json"
'''
    if transaction is None:
        private_write(root / "start-core.sh", text, executable=True)
    else:
        transaction.add_file(root / "start-core.sh", text, 0o700)


def validate_listener(host: str, port: int) -> None:
    if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
        raise ValueError("Invalid listener port")
    if not isinstance(host, str) or not host or host != host.strip():
        raise ValueError("Invalid listener address")
    try:
        ipaddress.ip_address(host)
        return
    except ValueError:
        pass
    # A hostname is syntactically validated without DNS/network access. Numeric
    # dotted addresses must parse as IP, so typos do not become DNS lookups.
    try:
        ascii_host = host.rstrip(".").encode("idna").decode("ascii")
    except UnicodeError:
        raise ValueError("Invalid listener address") from None
    if (len(ascii_host) > 253 or re.fullmatch(r"[0-9.]+", ascii_host)
            or any(not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label)
                   for label in ascii_host.split("."))):
        raise ValueError("Invalid listener address")


@contextmanager
def adapter_environment(tokens: dict[str, str]):
    previous = {name: os.environ.get(name) for name in tokens}
    try:
        os.environ.update(tokens)
        yield
    finally:
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def validate_agent_settings(settings: dict, core, tokens: dict[str, str], directory: Path) -> None:
    # This is the same builder used by server.create_app. Construction validates
    # IDs/types/URLs/credentials/mappings; discover/status must not run here.
    from oai_core.sessions import build_registry
    with adapter_environment(tokens):
        registry = build_registry({**settings, "storageRoot": str(directory / "registry-validation")}, core)
        asyncio.run(registry.close())


def configure(root: Path, host: str, port: int, username: str, selected: list[str], hermes_account: str | None) -> bool:
    validate_listener(host, port)
    if len(set(selected)) != len(selected) or set(selected) - {"dsh", "hermes", "codex"}:
        raise ValueError("Invalid Agent selection")
    root = root.resolve()
    with configuration_lock(root):
        ConfigurationTransaction.recover(root)
        transaction = ConfigurationTransaction(root)
        try:
            existed = prepare_configuration(transaction, host, port, username, selected, hermes_account)
            transaction.commit()
        except Exception:
            try:
                transaction.rollback()
            except Exception:
                raise ValueError("Configuration recovery required; verify runtime/configuration-journal.json and its backups") from None
            raise
    if existed:
        print(f"[INFO] 账号 {username} 已存在，保留原密码、配对和授权。")
    else:
        print(f"[INFO] 账号 {username} 已创建；配置事务已提交。")
    return deploy_skills_to_agents(root)


def prepare_configuration(transaction: ConfigurationTransaction, host: str, port: int, username: str,
                          selected: list[str], hermes_account: str | None) -> bool:
    root = transaction.root
    config_path = root / "core.json"
    if config_path.is_symlink():
        raise ValueError("Core configuration must not be a symlink")
    previous = transaction.read(config_path)
    config = json.loads(previous) if previous is not None else {
        "storageRoot": "runtime/accounts", "masterKeyFile": "runtime/secrets/core-master-key",
    }
    if not isinstance(config, dict):
        raise ValueError("Core configuration must be an object")
    if previous is not None:
        storage = root / config["storageRoot"]
        key = root / config["masterKeyFile"]
        if any(storage.glob("*/gateway.sqlite")) and not key.exists():
            raise ValueError("Existing account data requires its original master key; restore the key before configuring")
    config["listen"] = {**config.get("listen", {}), "host": host, "port": port}
    config.setdefault("allowInsecureHttp", True)
    existing_entries = config.get("agents", [])
    if not isinstance(existing_entries, list) or any(not isinstance(entry, dict) for entry in existing_entries):
        raise ValueError("Core agents must be an array of objects")
    existing = {entry["type"]: entry for entry in existing_entries if "type" in entry}
    secret_directory = root / "runtime" / "secrets"
    private_directory(secret_directory)
    token_path = secret_directory / "adapter-tokens.env"
    transaction.read(token_path, private=True)
    tokens = read_tokens(token_path)
    transaction.read(root / "start-core.sh")
    legacy_tokens = legacy_start_tokens(root / "start-core.sh")
    # Re-running a wizard never drops previously configured host instances.
    # Removing an integration remains an explicit edit of the agents array.
    agents = list(existing_entries)
    selected_or_existing = list(dict.fromkeys([*selected, *(kind for kind in existing if kind in {"codex", "dsh", "hermes"})]))
    for kind in selected_or_existing:
        if kind == "codex":
            if kind not in existing:
                agents.append({"id": "codex", "name": "Codex", "type": "codex",
                               "command": ["codex", "app-server"], "codexHome": "runtime/codex"})
            continue
        name, port_number, token_name = (
            ("DSH", 8791, "OAI_DSH_ADAPTER_TOKEN") if kind == "dsh"
            else ("Hermes", 8792, "OAI_HERMES_SESSION_TOKEN")
        )
        entry = existing.get(kind, {"id": kind, "name": name, "type": kind,
                                    "url": f"http://127.0.0.1:{port_number}", "tokenEnv": token_name})
        if entry.get("tokenEnv") == token_name:
            token = os.environ.get(token_name) or tokens.get(token_name) or legacy_tokens.get(token_name)
            if token is None and kind in existing:
                raise ValueError(f"Provide the existing {token_name} in the environment; credentials will not be silently rotated")
            token = token or secrets.token_urlsafe(32)
            if len(token) < 32 or any(character in token for character in "\r\n\0"):
                raise ValueError(f"Invalid {token_name}")
            tokens[token_name] = token
        if kind == "hermes" and "accounts" not in entry:
            if not hermes_account:
                raise ValueError("Hermes requires an explicit native account mapping")
            entry["accounts"] = {username: hermes_account}
        if kind not in existing:
            agents.append(entry)
    config["agents"] = agents
    from oai_core.gateway.account_paths import account_paths

    account_database = account_paths(root / config["storageRoot"], username).database
    if not account_database.is_file() and not os.environ.get("OAI_INIT_ADMIN_PASSWORD"):
        raise ValueError("New account requires a password")
    from oai_core.server import create_core, load_config

    transaction.watch_file(root / config["masterKeyFile"])
    # load_config resolves paths relative to its input file. Validate a private
    # candidate with equivalent absolute runtime paths; active JSON stays portable.
    candidate = copy.deepcopy(config)
    for name in ("storageRoot", "masterKeyFile", "contractRoot"):
        if candidate.get(name):
            candidate[name] = str((root / candidate[name]).absolute())
    for name in ("certFile", "keyFile"):
        if candidate.get("tls", {}).get(name):
            candidate["tls"][name] = str((root / candidate["tls"][name]).absolute())
    for agent in candidate.get("agents", []):
        for name in ("codexHome", "gatewayRoot"):
            if agent.get(name):
                agent[name] = str((root / agent[name]).absolute())
    validation = transaction.directory / "validate-config.json"
    private_write(validation, json.dumps(candidate))
    settings = load_config(validation)
    key = Path(settings["masterKeyFile"])
    storage = Path(settings["storageRoot"])
    if key.is_symlink() or key.resolve().is_relative_to(storage.resolve()):
        raise ValueError("masterKeyFile must be an original private key outside storageRoot")
    if not key.exists() and any(storage.glob("*/gateway.sqlite")):
        raise ValueError("Existing account data requires its original master key")
    original_key = key.exists()
    if not original_key:
        settings["masterKeyFile"] = str(transaction.directory / "new-master-key")
    transaction.state["preparation"] = {"master_key_candidate": transaction.relative(Path(settings["masterKeyFile"])),
                                          "account_storage": transaction.relative(storage / (".configuration-stage-" + transaction.identifier))}
    transaction.persist()
    core = create_core(settings)
    validate_agent_settings(settings, core, tokens, transaction.directory)
    existed = core.account_exists(username)
    staged_account = None
    if not existed:
        destination = account_paths(storage, username).root
        if destination.exists() or destination.is_symlink():
            raise ValueError("Unregistered account directory already exists; preserve it and inspect manually")
        # Stage a genuinely new account on the destination filesystem. Existing
        # account databases, passwords and histories are never opened for writing.
        private_directory(storage)
        staging_storage = storage / (".configuration-stage-" + transaction.identifier)
        staged_core = create_core({**settings, "storageRoot": str(staging_storage)})
        account = staged_core.open_gateway_account(username)
        try:
            account.credentials.create_password(os.environ["OAI_INIT_ADMIN_PASSWORD"])
        finally:
            account.close()
        staged_account = account_paths(staging_storage, username).root
    if not original_key:
        key.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        transaction.add_file(key, owned_bytes(Path(settings["masterKeyFile"])))
    transaction.add_file(token_path, "".join(f"{name}={shlex.quote(value)}\n" for name, value in sorted(tokens.items())))
    transaction.add_file(config_path, json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    write_start_script(root, transaction=transaction)

    if "hermes" in selected_or_existing and "OAI_HERMES_SESSION_TOKEN" in tokens:
        mappings = [value for entry in agents if entry.get("type") == "hermes"
                    for value in entry.get("accounts", {}).values()]
        hermes_entries = [entry for entry in agents if entry.get("type") == "hermes"]
        ports = {urlsplit(entry.get("url", "http://127.0.0.1:8792")).port or 8792 for entry in hermes_entries}
        roots = {str((root / entry["gatewayRoot"]).absolute()) for entry in hermes_entries if entry.get("gatewayRoot")}
        if len(ports) != 1 or len(roots) > 1:
            raise ValueError("Ambiguous Hermes host environment; preserve the existing configuration")
        sync_hermes_adapter_env(tokens["OAI_HERMES_SESSION_TOKEN"], username, transaction=transaction,
                                accounts=list(dict.fromkeys(mappings)), port=next(iter(ports)),
                                gateway_root=next(iter(roots), None))
    if "dsh" in selected_or_existing and "OAI_DSH_ADAPTER_TOKEN" in tokens:
        sync_dsh_adapter_env(tokens["OAI_DSH_ADAPTER_TOKEN"], transaction=transaction)
    if staged_account is not None:
        transaction.add_account(staged_account, account_paths(storage, username).root)
    if previous is not None:
        backup = root / "runtime" / "configuration-backups" / f"core-{transaction.identifier}.json"
        private_write(backup, previous)
        if path_hash(backup) != content_hash(previous.encode("utf-8")):
            raise ValueError("Configuration backup verification failed")
    return existed


def deploy_skills_to_agents(root: Path) -> bool:
    # Optional post-commit installation must not remove existing host files.
    import shutil
    try:
        from oai_core.skills_installer import get_agent_skill_destinations, get_skill_source_dir
        source = get_skill_source_dir()
        destinations = get_agent_skill_destinations()
    except Exception:
        source = root / "integrations" / "skills" / "oai-client"
        destinations = [Path.home() / ".agents" / "skills"]
    if not (source / "SKILL.md").is_file():
        print("[WARN] 配置已提交，未找到可选 oai-client skill；请稍后手动安装。")
        return False
    complete = True
    standard = Path.home() / ".agents" / "skills"
    for destination in destinations:
        # Do not create fake host homes just to deploy an optional skill.
        if destination != standard and not destination.parent.is_dir():
            continue
        target = destination / "oai-client"
        try:
            if target.is_symlink() or (target.exists() and not target.is_dir()):
                raise ValueError("Existing skill target requires manual review")
            destination.mkdir(parents=True, exist_ok=True)
            shutil.copytree(source, target, dirs_exist_ok=True)
            print(f"[INFO] 已同步 oai-client skill: {target}")
        except Exception as exc:
            complete = False
            print(f"[WARN] 可选 skill 安装未完成（{type(exc).__name__}）: {target}")
    return complete


def read_configuration_text(path: Path, transaction: ConfigurationTransaction | None = None) -> str:
    if transaction is not None:
        return transaction.read(path) or ""
    content = owned_bytes(path)
    return content.decode("utf-8") if content is not None else ""


def write_configuration_text(path: Path, text: str, transaction: ConfigurationTransaction | None = None) -> None:
    if transaction is None:
        private_write(path, text)
    else:
        transaction.add_file(path, text)


def update_environment_text(content: str, values: dict[str, str]) -> str:
    output, seen = [], set()
    for line in content.splitlines():
        matched = next((key for key in values if line.startswith(key + "=") or line.startswith("export " + key + "=")), None)
        if matched is None:
            output.append(line)
        elif matched not in seen:
            output.append(f"{matched}={values[matched]}")
            seen.add(matched)
    output.extend(f"{key}={value}" for key, value in values.items() if key not in seen)
    return "\n".join(output) + "\n"


def dotenv_scalar(value: str) -> str:
    return value if re.fullmatch(r"[A-Za-z0-9_./:@+-]+", value) else json.dumps(value, ensure_ascii=False)


def sync_dsh_adapter_env(token: str, *, transaction: ConfigurationTransaction | None = None) -> None:
    candidates = [Path.home() / ".dsh-cline", Path.home() / ".dsh"]
    if os.environ.get("DSH_HOME"):
        candidates.insert(0, Path(os.environ["DSH_HOME"]))
    seen = set()
    for home in candidates:
        resolved = home.resolve()
        if not home.is_dir() or resolved in seen:
            continue
        seen.add(resolved)
        env_file = home / ".env"
        content = read_configuration_text(env_file, transaction)
        write_configuration_text(env_file, update_environment_text(content, {"OAI_DSH_ADAPTER_TOKEN": dotenv_scalar(token)}), transaction)
        update_dsh_credentials_yaml(home / ".credentials.yaml", "OAI_DSH_ADAPTER_TOKEN", token, transaction=transaction)


def update_dsh_credentials_yaml(path: Path, key: str, value: str, *, transaction: ConfigurationTransaction | None = None) -> None:
    # The credential format only needs one scalar ref. Preserve all unrelated
    # lines, and never replace a file that could not be read.
    content = read_configuration_text(path, transaction)
    lines = content.splitlines()
    output, in_refs, updated = [], False, False
    has_version = any(line.startswith("version:") for line in lines)
    has_refs = any(line == "refs:" for line in lines)
    scalar = dotenv_scalar(value)
    if not has_version:
        output.append("version: 1")
    for line in lines:
        if line == "refs:":
            in_refs = True
            output.append(line)
            continue
        if in_refs and line and not line[0].isspace():
            if not updated:
                output.append(f"  {key}: {scalar}")
                updated = True
            in_refs = False
        if in_refs and line.strip().startswith(key + ":"):
            if not updated:
                output.append(f"  {key}: {scalar}")
                updated = True
            continue
        output.append(line)
    if not has_refs:
        output.append("refs:")
    if not updated:
        output.append(f"  {key}: {scalar}")
    write_configuration_text(path, "\n".join(output) + "\n", transaction)


def sync_hermes_adapter_env(token: str, account_id: str, *, transaction: ConfigurationTransaction | None = None,
                            accounts: list[str] | None = None, port: int = 8792, gateway_root: str | None = None) -> None:
    home = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))
    if not home.is_dir():
        return
    env_file = home / ".env"
    content = read_configuration_text(env_file, transaction)
    if gateway_root is None:
        for line in content.splitlines():
            assignment = line.removeprefix("export ")
            if assignment.startswith("OAI_HERMES_GATEWAY_ROOT="):
                raw = assignment.split("=", 1)[1]
                parsed = shlex.split(raw)
                if len(parsed) != 1:
                    raise ValueError("Invalid existing Hermes gateway root; preserve it and inspect manually")
                gateway_root = parsed[0]
    gateway_root = gateway_root or str(home / "open-android-intelligence-gateway" / "accounts")
    account_ids = [account_id] if accounts is None else accounts
    if not account_ids:
        raise ValueError("Hermes requires an explicit native account mapping")
    # Directory preparation is also fallible, so complete it before committing.
    Path(gateway_root).mkdir(parents=True, exist_ok=True)
    values = {"OAI_HERMES_SESSION_TOKEN": dotenv_scalar(token), "OAI_HERMES_GATEWAY_ROOT": dotenv_scalar(gateway_root),
              "OAI_HERMES_ACCOUNTS": json.dumps(account_ids), "OAI_HERMES_SESSION_PORT": str(port)}
    write_configuration_text(env_file, update_environment_text(content, values), transaction)



def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--agents", default="")
    parser.add_argument("--hermes-account")
    arguments = parser.parse_args()
    selected = arguments.agents.split(",") if arguments.agents else []
    if len(set(selected)) != len(selected) or set(selected) - {"dsh", "hermes", "codex"}:
        parser.error("Invalid Agent selection")
    try:
        complete = configure(arguments.root, arguments.host, arguments.port, arguments.username, selected, arguments.hermes_account)
    except Exception as exc:
        # Exception strings may originate in parsers of credential files. Do not
        # echo their contents, passwords or adapter tokens to terminal/logs.
        print(f"[ERROR] 配置保存失败（{type(exc).__name__}）；旧配置已保留或有待恢复。请检查权限、输入及 runtime/configuration-journal.json。", file=sys.stderr)
        raise SystemExit(1) from None
    if not complete:
        print("[PARTIAL] 配置事务已提交；可选 skill 安装未全部完成。")
        raise SystemExit(2)


if __name__ == "__main__":
    main()
