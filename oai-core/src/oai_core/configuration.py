"""Shared installer configuration with private credentials and preserved state."""
from __future__ import annotations

import argparse
import asyncio
from contextlib import contextmanager
import copy
import hashlib
import getpass
import ipaddress
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import secrets
import shlex
import stat
import sys
import warnings


TOKEN_NAMES = {"OAI_DSH_ADAPTER_TOKEN", "OAI_HERMES_SESSION_TOKEN"}


class DshYamlParserRequired(ValueError):
    code = "DSH_YAML_PARSER_REQUIRED"


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


def private_write_bytes(path: Path, content: bytes, mode: int = 0o600, *,
                        expected_hashes: tuple[str | None, ...] | None = None) -> None:
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
    # Recheck after preparing and fsyncing the replacement, immediately before
    # the rename. Other configuration writers still need to share the lock.
    if expected_hashes is not None and path_hash(path) not in expected_hashes:
        raise ValueError("Configuration changed before replacement; preserve the recovery journal")
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

    def __init__(self, root: Path, *, request_id: str | None = None,
                 authorization=None, operator: dict | None = None, audit_store=None):
        self.root = root
        self.authorization = authorization
        if operator is not None:
            if authorization is None or audit_store is None:
                raise PermissionError("Administrator authorization and audit are required")
            authorization()
            self.audit_store, self.audit_actor = audit_store, operator["accountId"]
        self.journal = root / "runtime" / "configuration-journal.json"
        self.identifier = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + secrets.token_hex(4)
        self.directory = root / "runtime" / "configuration-backups" / self.identifier
        private_directory(self.directory)
        fsync_directory(self.directory.parent)
        self.snapshots = {}
        self.guards = {}
        self.state = {"version": 1, "id": self.identifier, "request_id": request_id,
                      "status": "preparing", "entries": []}
        if operator is not None:
            self.state["operator"] = {name: operator[name] for name in
                                      ("accountId", "authEpoch", "instanceId") if name in operator}
            self.state["scope"] = "config"
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
        if self.authorization is not None:
            self.authorization()
        if any(self.root / entry["target"] == path for entry in self.state["entries"]):
            raise ValueError("Ambiguous configuration target")
        if path not in self.snapshots:
            self.snapshots[path] = owned_bytes(path)
        before = self.snapshots[path]
        if owned_bytes(path) != before:
            raise ValueError("Configuration changed during preparation; retry after stopping the other writer")
        content = text.encode("utf-8") if isinstance(text, str) else text
        if before == content and stat.S_IMODE(path.stat().st_mode) == mode:
            return
        index = len(self.state["entries"])
        staged = self.directory / f"new-{index}"
        backup = self.directory / f"old-{index}"
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
        if self.authorization is not None:
            self.authorization()
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
        if self.authorization is not None:
            self.authorization()
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
        if hasattr(self, "audit_store"):
            self.authorization()
            self.audit_store.audit("configuration.intent", self.audit_actor, None,
                                   {"transactionId": self.identifier, "scope": "config",
                                    "targets": [entry["target"] for entry in self.state["entries"]]})
        self.state["status"] = "applying"
        self.persist()
        for entry in self.state["entries"]:
            if self.authorization is not None:
                self.authorization()
            target = self.root / entry["target"]
            staged = self.root / entry["staged"]
            if entry["kind"] == "directory":
                if path_hash(staged, "directory") != entry["after"]:
                    raise ValueError("Prepared configuration verification failed")
                if path_hash(target, "directory") != entry["before"]:
                    raise ValueError("Configuration changed before replacement; preserve the recovery journal")
                os.replace(staged, target)
                fsync_directory(staged.parent)
                fsync_directory(target.parent)
            else:
                content = owned_bytes(staged)
                if content is None or content_hash(content) != entry["after"]:
                    raise ValueError("Prepared configuration verification failed")
                private_write_bytes(target, content, entry["mode"], expected_hashes=(entry["before"],))
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
            if actual not in {entry["before"], entry["after"]}:
                raise ValueError("Configuration changed after interruption; preserve journal and inspect manually")
            if entry["before"] is None:
                if actual is not None:
                    retained = self.root / entry.get("retained", entry["staged"])
                    if retained.exists() or retained.is_symlink():
                        raise ValueError("Recovery retention path already exists; preserve journal")
                    if path_hash(target, entry["kind"]) != entry["after"]:
                        raise ValueError("Configuration changed after interruption; preserve journal and inspect manually")
                    os.replace(target, retained)
                    fsync_directory(target.parent)
                    fsync_directory(retained.parent)
            elif actual != entry["before"] or stat.S_IMODE(target.stat().st_mode) != entry["old_mode"]:
                content = owned_bytes(self.root / entry["backup"])
                if content is None or content_hash(content) != entry["before"]:
                    raise ValueError("Configuration backup verification failed; preserve the recovery journal")
                private_write_bytes(target, content, entry["old_mode"],
                                    expected_hashes=(entry["before"], entry["after"]))
        self.state["status"] = "rolled_back"
        self.persist()

    @classmethod
    def recover(cls, root: Path, *, store=None, handle: str | None = None) -> None:
        from .identity import IdentityStore
        if not isinstance(store, IdentityStore) or not isinstance(handle, str) or not handle:
            raise PermissionError("Administrator authorization is required")
        actor = store.require_management(handle, "config")
        journal = root / "runtime" / "configuration-journal.json"
        content = owned_bytes(journal, private=True)
        if content is None:
            require_configuration_access(root, store, handle, "config")
            return
        state = json.loads(content)
        if not isinstance(state, dict) or state.get("version") != 1 or state.get("status") not in {"preparing", "prepared", "applying", "committed", "rolled_back"}:
            raise ValueError("Invalid configuration recovery journal; preserve it for manual recovery")
        try:
            require_configuration_access(root, store, handle, "config")
        except (ValueError, KeyError, TypeError):
            # A damaged active config is not authority. Only the interrupted
            # transaction's verified before-image can identify the installation.
            if state["status"] in {"committed", "rolled_back"}:
                raise
            saved_operator = state.get("operator", {})
            entries = state.get("entries", [])
            candidates = [entry for entry in entries if entry.get("target") == "core.json"
                          and entry.get("kind") == "file" and entry.get("before") and entry.get("backup")]
            if saved_operator.get("instanceId") != actor["instanceId"] or len(candidates) != 1:
                raise PermissionError("Cannot verify the original installation identity") from None
            entry = candidates[0]
            identifier = state.get("id")
            if not isinstance(identifier, str) or re.fullmatch(r"[0-9]{8}T[0-9]{12}Z-[0-9a-f]{8}", identifier) is None:
                raise ValueError("Invalid configuration recovery journal")
            backup = root / entry["backup"]
            if backup.parent != root / "runtime" / "configuration-backups" / identifier or not re.fullmatch(r"old-[0-9]+", backup.name):
                raise ValueError("Invalid configuration recovery backup")
            if path_hash(backup) != entry["before"] or path_hash(root / "core.json") != entry["after"]:
                raise ValueError("Configuration backup or interrupted candidate does not match") from None
            original = json.loads(owned_bytes(backup))
            if _identity_path_from_config(root, original) != Path(store.database_path).resolve():
                raise PermissionError("Administrator authorization belongs to another installation")
        if state["status"] in {"committed", "rolled_back"}:
            return
        transaction = cls.__new__(cls)
        transaction.root, transaction.journal, transaction.state = root, journal, state
        transaction.identifier = state["id"]
        transaction.directory = root / "runtime" / "configuration-backups" / transaction.identifier
        transaction.rollback()
        operator = state.get("operator", {})
        store.audit("configuration.result", operator.get("accountId"), None,
                    {"transactionId": transaction.identifier, "status": "rolled_back"})
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
    # The executable contains no credentials and never executes legacy env files.
    text = '''#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIR}"
PYTHON="${DIR}/.venv/bin/python"
OAI="${DIR}/.venv/bin/oai"
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


def validate_agent_settings(settings: dict, core, tokens: dict[str, str], directory: Path) -> None:
    # Validate using the real adapter factories, with paths for staged secrets.
    # No Agent process, discovery or network handshake is started during save.
    from oai_core.sessions import build_registry
    registry = build_registry(settings, core)
    asyncio.run(registry.close())


def _identity_path_from_config(root: Path, config: dict) -> Path:
    if not isinstance(config, dict):
        raise ValueError("Core configuration must be an object")
    storage = config.get("storageRoot")
    identity = config.get("identityDatabase")
    if not isinstance(storage, str) or not storage:
        raise ValueError("Core configuration requires storageRoot")
    if identity is not None and (not isinstance(identity, str) or not identity):
        raise ValueError("Invalid identity database path")
    return ((root / identity) if identity is not None else
            (root / storage).parent / "security" / "identity.sqlite").resolve()


def _identity_path(root: Path) -> Path:
    """Resolve the configured identity store without opening or creating it."""
    content = owned_bytes(root / "core.json")
    config = json.loads(content) if content is not None else {"storageRoot": "runtime/accounts"}
    return _identity_path_from_config(root, config)


def require_configuration_access(root: Path, store, handle: str, scope: str = "config") -> dict:
    """Revalidate the real authority and bind it to this installation."""
    from .identity import IdentityStore
    if not isinstance(store, IdentityStore) or not isinstance(handle, str) or not handle:
        raise PermissionError("Administrator authorization is required")
    if Path(store.database_path).resolve() != _identity_path(root):
        raise PermissionError("Administrator authorization belongs to another installation")
    return store.require_management(handle, scope)


def _instance_root(instance: dict) -> Path:
    if not isinstance(instance, dict) or not instance.get("configPath"):
        raise ValueError("An explicit installed Core configuration is required")
    config_path = Path(instance["configPath"]).resolve()
    # The existing installer keeps its configuration and transaction journal at
    # this root. Refuse to silently manage a different file in the same folder.
    if config_path.name != "core.json" or not config_path.is_file():
        raise ValueError("The installed configuration must be core.json")
    return config_path.parent


def _terminal_input(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise PermissionError("A terminal is required for interactive configuration")
    return input(prompt).strip()


def _terminal_secret(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise PermissionError("A terminal is required for password entry")
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        return getpass.getpass(prompt)


def _new_password() -> str:
    password = _terminal_secret("请输入新密码: ")
    if not password or password != _terminal_secret("请再次输入新密码: "):
        raise ValueError("两次输入的密码不一致或密码为空")
    return password


def _network_settings(root: Path) -> tuple[str, int, list[str], str | None]:
    config = json.loads(owned_bytes(root / "core.json"))
    if not isinstance(config, dict) or not isinstance(config.get("listen", {}), dict):
        raise ValueError("Invalid Core configuration")
    listen = config.get("listen", {})
    host, port = listen.get("host", "0.0.0.0"), listen.get("port", 11451)
    validate_listener(host, port)
    entries = config.get("agents", [])
    if not isinstance(entries, list) or any(not isinstance(entry, dict) for entry in entries):
        raise ValueError("Invalid Agent configuration")
    selected = list(dict.fromkeys(entry.get("type") for entry in entries
                                  if isinstance(entry.get("type"), str) and entry.get("type")))
    return host, port, selected, None


def _configure_network(root: Path, store, handle: str, operator: dict, *, allow_security: bool = False) -> None:
    host, port, selected, hermes = _network_settings(root)
    choice = _terminal_input(f"监听地址 [{host}]{'（输入 2fa 管理二次验证）' if allow_security else ''}: ")
    if allow_security and choice.lower() == "2fa":
        _manage_security(root, store, handle)
        return
    new_host = choice or host
    raw_port = _terminal_input(f"监听端口 [{port}]: ")
    new_port = int(raw_port) if raw_port else port
    validate_listener(new_host, new_port)
    if (new_host, new_port) == (host, port):
        print("配置未更改。")
        return
    if not configure(root, new_host, new_port, operator["username"], selected, hermes,
                     store=store, handle=handle):
        print("配置已保存，可选 skill 同步未完成。")


def _manage_accounts(root: Path, store, handle: str) -> None:
    require_configuration_access(root, store, handle, "accounts.read")
    print("1. 查看账号  2. 创建账号  3. 修改密码  4. 删除账号  0. 返回")
    choice = _terminal_input("请选择: ")
    if choice in {"", "0"}:
        return
    if choice == "1":
        for account in store.list_accounts(handle).get("accounts", []):
            print(f"{account['username']} ({account['role']})")
        return
    if choice not in {"2", "3", "4"}:
        raise ValueError("无效的账号操作")
    require_configuration_access(root, store, handle, "accounts.write")
    username = _terminal_input("账号名称: ")
    if not username:
        raise ValueError("账号名称不能为空")
    if choice == "4":
        if _terminal_input(f"删除账号将撤销其访问权限，请输入 {username} 确认: ") != username:
            print("已取消。")
            return
        store.delete_account(handle, username)
    else:
        password = _new_password()
        try:
            if choice == "2":
                store.create_account(handle, username, password)
            else:
                if _terminal_input(f"重置 {username} 的密码将撤销现有会话，请输入账号名称确认: ") != username:
                    print("已取消。")
                    return
                store.reset_password(handle, username, password)
        finally:
            password = ""
    print("账号操作已完成。")


def _configure_agents(root: Path, store, handle: str, operator: dict) -> None:
    from .agent_registration import AgentRegistration
    from .sessions import AgentRegistry

    require_configuration_access(root, store, handle, "agents.manage")
    config = json.loads(owned_bytes(root / "core.json"))
    storage = (root / config["storageRoot"]).resolve()
    signing_key = store.key_store.instance_signing_key(operator["instanceId"]) if store.key_store is not None else None
    registration = AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False,
                                     core_signing_key=signing_key)
    registered = registration.entries()
    print("已注册 Agent: " + (", ".join(entry["id"] for entry in registered) or "无"))
    roots = [root / "runtime" / "agent-candidates", Path.home() / ".config" / "oai" / "agents"]
    candidates = registration.discover(handle, roots)
    if not candidates:
        print("未发现已授权的 Agent 候选；尚未连接。")
        return
    for index, (_, entry) in enumerate(candidates, 1):
        print(f"{index}. {entry['id']} ({entry['type']}, {entry['instanceId']}, {entry['sessionScope']})")
    choice = _terminal_input("选择候选编号（直接回车返回）: ")
    if not choice:
        return
    try:
        number = int(choice)
    except ValueError:
        raise ValueError("无效的 Agent 候选") from None
    if not 1 <= number <= len(candidates):
        raise ValueError("无效的 Agent 候选")
    _, entry = candidates[number - 1]
    if AgentRegistry.get_factory(entry["type"]) is None:
        raise ValueError("未安装该 Agent 类型的适配器插件")
    if entry["id"] in {item.get("id") for item in config.get("agents", [])}:
        raise ValueError("Agent 标识与现有配置冲突")
    if "secretFile" not in entry or Path(entry["secretFile"]).is_absolute():
        raise ValueError("Agent 候选必须使用相对于账号存储目录的 secretFile")
    if signing_key is not None and entry["type"] == "hermes":
        from cryptography.hazmat.primitives import serialization
        public = signing_key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()
        print("请在 Hermes 的受保护信任清单中确认以下 Core 实例公钥；只需首次接入时确认：")
        print(json.dumps({operator["instanceId"]: public}, ensure_ascii=False))
    if _terminal_input(f"请输入 {entry['id']} 确认验证并注册该实例: ") != entry["id"]:
        print("已取消。")
        return
    require_configuration_access(root, store, handle, "agents.manage")
    registration.initialize(handle)
    asyncio.run(registration.register(handle, entry, storage))
    print("Agent 已通过真实握手并注册；账号关联按其隔离范围处理。")


def _service_command(arguments: list[str]) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(arguments, capture_output=True, text=True, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValueError("服务管理器不可用；未更改服务。") from exc
    if result.returncode:
        raise ValueError("服务管理器操作失败；请检查用户服务状态。")
    return result


def _service_state(root: Path) -> tuple[str, str]:
    """Verify that the loaded user service belongs to this exact installation."""
    root = root.resolve()
    if sys.platform.startswith("linux"):
        result = _service_command(["systemctl", "--user", "show", "oai-core.service",
                                   "--property=LoadState,FragmentPath,WorkingDirectory,ActiveState,SubState"])
        properties = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        if properties.get("LoadState") == "not-found":
            return "not-found", "not-found"
        expected = Path.home() / ".config" / "systemd" / "user" / "oai-core.service"
        if (properties.get("LoadState") != "loaded" or properties.get("FragmentPath") != str(expected)
                or properties.get("WorkingDirectory") != str(root)):
            raise ValueError("用户服务与当前安装不匹配，未执行操作。")
        return properties.get("ActiveState", "unknown"), properties.get("SubState", "unknown")
    if sys.platform == "darwin":
        label = "com.openandroidintelligence.core"
        jobs = _service_command(["launchctl", "list"])
        if not any(line.split() and line.split()[-1] == label for line in jobs.stdout.splitlines()):
            return "not-found", "not-found"
        loaded = _service_command(["launchctl", "list", "-x", label])
        try:
            settings = plistlib.loads(loaded.stdout.encode())
        except (ValueError, plistlib.InvalidFileException) as exc:
            raise ValueError("无法核对已加载的 macOS 服务，未执行操作。") from exc
        plist = Path.home() / "Library" / "LaunchAgents" / "com.openandroidintelligence.core.plist"
        try:
            disk = plistlib.loads(owned_bytes(plist))
        except (OSError, TypeError, ValueError, plistlib.InvalidFileException) as exc:
            raise ValueError("无法核对 macOS 服务配置，未执行操作。") from exc
        if (settings.get("ProgramArguments") != [str(root / "start-core.sh")]
                or disk.get("Label") != label or disk.get("ProgramArguments") != [str(root / "start-core.sh")]
                or disk.get("WorkingDirectory") != str(root)):
            raise ValueError("用户服务与当前安装不匹配，未执行操作。")
        return "loaded", "running" if settings.get("PID") else "stopped"
    raise ValueError("当前平台不支持此服务管理入口。")


def _manage_service(root: Path, store, handle: str) -> None:
    require_configuration_access(root, store, handle, "service")
    state, detail = _service_state(root)
    print(f"服务状态: {state} ({detail})")
    print("1. 启动  2. 停止  3. 重启  0. 返回")
    choice = _terminal_input("请选择: ")
    if choice in {"", "0"}:
        return
    action = {"1": "start", "2": "stop", "3": "restart"}.get(choice)
    if action is None:
        raise ValueError("无效的服务操作")
    if _terminal_input(f"请输入 {action} 确认服务操作: ") != action:
        print("已取消。")
        return
    actor = require_configuration_access(root, store, handle, "service")
    state, _ = _service_state(root)
    if state == "not-found":
        raise ValueError("未发现当前安装的用户服务；未执行操作。")
    operation_id = secrets.token_hex(16)
    store.audit("service.intent", actor["accountId"], None, {"operationId": operation_id, "action": action})
    try:
        require_configuration_access(root, store, handle, "service")
        if sys.platform.startswith("linux"):
            _service_command(["systemctl", "--user", action, "oai-core.service"])
        else:
            plist = Path.home() / "Library" / "LaunchAgents" / "com.openandroidintelligence.core.plist"
            if action in {"stop", "restart"}:
                _service_command(["launchctl", "unload", str(plist)])
            if action in {"start", "restart"}:
                require_configuration_access(root, store, handle, "service")
                _service_command(["launchctl", "load", "-w", str(plist)])
    except BaseException:
        store.audit("service.result", actor["accountId"], None,
                    {"operationId": operation_id, "action": action, "status": "failed_or_partial"})
        raise
    store.audit("service.result", actor["accountId"], None,
                {"operationId": operation_id, "action": action, "status": "completed"})
    print(f"服务操作 {action} 已执行。")


def _manage_security(root: Path, store, handle: str) -> None:
    actor = require_configuration_access(root, store, handle, "security")
    required, _, _ = store._policy()
    print(f"服务端 2FA: {'开启' if required else '关闭'}（仅开启时需要二次验证）。")
    print("1. 绑定验证器  2. 登记管理员恢复码  3. 开启 2FA  4. 关闭 2FA  0. 返回")
    choice = _terminal_input("请选择: ")
    if choice in {"", "0"}:
        return
    if choice == "1":
        password = _terminal_secret("请再次输入管理员密码: ")
        try:
            result = store.authenticate(actor["username"], password, purpose="enroll")
        finally:
            password = ""
        if result["accountId"] != actor["accountId"]:
            raise PermissionError("账号身份发生变化")
        proof = result["enrollmentProof"]
        require_configuration_access(root, store, handle, "security")
        pending = store.begin_mfa_enrollment(actor["accountId"], proof)
        # Enrollment material is shown only in the protected local interaction.
        print(f"绑定密钥: {pending['secret']}")
        print(f"绑定地址: {pending['provisioningUri']}")
        otp = _terminal_secret("请输入验证器中的验证码: ")
        try:
            require_configuration_access(root, store, handle, "security")
            result = store.confirm_mfa_enrollment(actor["accountId"], proof, otp,
                                                  enrollment_id=pending["enrollmentId"])
        finally:
            otp = ""
        print("请安全保存以下账号恢复码；它们只显示一次：")
        for code in result["recoveryCodes"]:
            print(code)
        print("绑定完成；管理授权已失效，请重新认证。")
        return
    if choice == "2":
        if _terminal_input("请输入 register 确认登记管理员恢复码: ") != "register":
            print("已取消。")
            return
        from .management import LocalRecovery
        require_configuration_access(root, store, handle, "security")
        result = LocalRecovery(store).register(handle, confirmed=True)
        print("请离线安全保存以下管理员恢复码；它们只显示一次：")
        for code in result["recoveryCodes"]:
            print(code)
        return
    if choice not in {"3", "4"}:
        raise ValueError("无效的安全设置")
    enabled = choice == "3"
    if enabled == required:
        print("2FA 策略未更改。")
        return
    confirmation = "enable" if enabled else "disable"
    if _terminal_input(f"此操作将撤销现有会话，请输入 {confirmation} 确认: ") != confirmation:
        print("已取消。")
        return
    require_configuration_access(root, store, handle, "security")
    store.set_mfa_required(handle, enabled)
    print("2FA 策略已更改；管理授权已失效，请重新认证。")


def run_config(instance: dict, store, handle: str) -> None:
    root = _instance_root(instance)
    operator = require_configuration_access(root, store, handle, "config")
    required, _, _ = store._policy()
    print(f"服务端 2FA: {'开启' if required else '关闭'}（仅开启时需要二次验证）。")
    _configure_network(root, store, handle, operator, allow_security=True)


def run_setup(instance: dict, store, handle: str) -> None:
    """Run the five-category setup using the caller's live management session."""
    root = _instance_root(instance)
    while True:
        operator = require_configuration_access(root, store, handle, "setup")
        host, port, selected, _ = _network_settings(root)
        required, _, _ = store._policy()
        print(f"\n服务端 2FA: {'开启' if required else '关闭'}（输入 2fa 管理）")
        print(f"1. 网络设置 [{host}:{port}]")
        print("2. 账号管理")
        print(f"3. Agent 接入 [{', '.join(selected) or '无'}]")
        print("4. 服务管理")
        print("5. 退出")
        choice = _terminal_input("请选择: ")
        if choice in {"5", "0", "q", "Q"}:
            return
        if choice == "1":
            require_configuration_access(root, store, handle, "config")
            _configure_network(root, store, handle, operator)
        elif choice == "2":
            _manage_accounts(root, store, handle)
        elif choice == "3":
            require_configuration_access(root, store, handle, "config")
            _configure_agents(root, store, handle, operator)
        elif choice == "4":
            _manage_service(root, store, handle)
        elif choice.lower() == "2fa":
            _manage_security(root, store, handle)
            # Enrollment and policy changes can revoke this management session.
            return
        else:
            print("无效选择。")


def _configuration_unchanged(root: Path, host: str, port: int, selected: list[str]) -> bool:
    """Recognize a true read-only save without creating a lock or journal."""
    content = owned_bytes(root / "core.json")
    if content is None:
        return False
    config = json.loads(content)
    if not isinstance(config, dict) or not isinstance(config.get("listen"), dict):
        raise ValueError("Invalid Core configuration")
    listen = config["listen"]
    if listen.get("host") != host or listen.get("port") != port or "allowInsecureHttp" not in config:
        return False
    agents = config.get("agents")
    if not isinstance(agents, list) or any(not isinstance(entry, dict) for entry in agents):
        raise ValueError("Invalid Core agents")
    if not set(selected) <= {entry.get("type") for entry in agents}:
        return False
    if any("tokenEnv" in entry or "token" in entry for entry in agents):
        return False
    from .agent_registration import read_bridge_secret
    storage = (root / config["storageRoot"]).resolve()
    for entry in agents:
        if "secretFile" in entry:
            read_bridge_secret(entry, storage)
    start_path = root / "start-core.sh"
    if not start_path.is_file() or stat.S_IMODE(start_path.stat().st_mode) != 0o700:
        return False
    if owned_bytes(start_path) is None or b'source "${ENV_FILE}"' in owned_bytes(start_path):
        return False
    journal = owned_bytes(root / "runtime" / "configuration-journal.json", private=True)
    if journal is None:
        return False
    state = json.loads(journal)
    if not isinstance(state, dict) or state.get("version") != 1:
        raise ValueError("Invalid configuration recovery journal")
    if state.get("status") not in {"committed", "rolled_back"}:
        return False
    return True


def configure(root: Path, host: str, port: int, username: str, selected: list[str], hermes_account: str | None,
              *, store=None, handle: str | None = None, request_id: str | None = None) -> bool:
    validate_listener(host, port)
    if request_id is not None and re.fullmatch(r"[0-9a-f]{32}", request_id) is None:
        raise ValueError("Invalid configuration request ID")
    if (not all(isinstance(kind, str) and kind for kind in selected)
            or len(set(selected)) != len(selected)):
        raise ValueError("Invalid Agent selection")
    root = root.resolve()
    def authorize():
        return require_configuration_access(root, store, handle, "config")
    try:
        operator = authorize()  # The lock itself creates files: never lock before authentication.
    except (ValueError, KeyError, TypeError):
        # An authenticated administrator may restore an already journaled
        # before-image, but a corrupt active configuration grants no new write.
        with configuration_lock(root):
            ConfigurationTransaction.recover(root, store=store, handle=handle)
        operator = authorize()
    existing_config = owned_bytes(root / "core.json")
    existing_agents = json.loads(existing_config).get("agents", []) if existing_config is not None else []
    existing_types = {entry.get("type") for entry in existing_agents if isinstance(entry, dict)}
    if set(selected) - (existing_types | {"dsh", "hermes", "codex"}):
        raise ValueError("New Agent instances must be discovered and registered")
    if _configuration_unchanged(root, host, port, selected):
        print("[INFO] 配置未更改。")
        return True
    with configuration_lock(root):
        authorize()
        ConfigurationTransaction.recover(root, store=store, handle=handle)
        transaction = ConfigurationTransaction(root, request_id=request_id, authorization=authorize,
                                               operator=operator, audit_store=store)
        try:
            prepare_configuration(transaction, host, port, username, selected, hermes_account)
            transaction.commit()
        except BaseException:
            try:
                # A transaction that has already started may restore only its
                # own verified before-images even if its authorization expired.
                transaction.rollback()
                store.audit("configuration.result", operator["accountId"], None,
                            {"transactionId": transaction.identifier, "status": "rolled_back"})
            except Exception:
                raise ValueError("Configuration recovery required; verify runtime/configuration-journal.json and its backups") from None
            raise
        store.audit("configuration.result", operator["accountId"], None,
                    {"transactionId": transaction.identifier, "status": "committed"})
    print("[INFO] 配置事务已提交；账号、密码、配对和授权保持不变。")
    authorize()
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
    token_path = secret_directory / "adapter-tokens.env"
    transaction.read(token_path, private=True)
    tokens = read_tokens(token_path)
    transaction.read(root / "start-core.sh")
    legacy_tokens = legacy_start_tokens(root / "start-core.sh")
    # Existing explicit deployments are preserved; new bridge instances need a
    # verified candidate and must not be guessed from the adapter type.
    agents = list(existing_entries)
    for kind in selected:
        if kind not in existing:
            if kind != "codex":
                raise ValueError("New Agent instances must be discovered and registered")
            agents.append({"id": "codex", "name": "Codex", "type": "codex",
                           "command": ["codex", "app-server"], "codexHome": "runtime/codex"})
    staged_secrets = {}
    storage_root = (root / config["storageRoot"]).resolve()
    for entry in agents:
        sources = [name for name in ("tokenEnv", "token", "secretFile", "secretFd") if name in entry]
        if len(sources) > 1:
            raise ValueError("Ambiguous Agent credential sources")
        if not sources or sources[0] not in {"tokenEnv", "token"}:
            continue
        source = sources[0]
        if source == "tokenEnv":
            name = entry[source]
            if name not in TOKEN_NAMES:
                raise ValueError("Legacy Agent credential requires an explicit secret file")
            token = tokens.get(name) or legacy_tokens.get(name)
        else:
            token = entry[source]
        if not isinstance(token, str) or not 32 <= len(token) <= 4096 or any(c in token for c in "\r\n\0"):
            raise ValueError("Legacy Agent credential unavailable; credentials will not be silently rotated")
        identifier = entry.get("id")
        if not isinstance(identifier, str) or re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", identifier) is None or identifier in {".", ".."}:
            raise ValueError("Invalid Agent identity")
        # The registry resolves references from storageRoot, so store the
        # bridge file in a dedicated owner-only child of that directory.
        bridge_directory = storage_root / ".agent-secrets"
        private_directory(bridge_directory)
        target = bridge_directory / ("agent-" + identifier + ".secret")
        previous_secret = transaction.read(target, private=True)
        if previous_secret is not None and previous_secret != token + "\n":
            raise ValueError("Existing Agent secret conflicts with legacy credential")
        transaction.add_file(target, token + "\n")
        staged_entry = next((item for item in transaction.state["entries"]
                             if item["target"] == transaction.relative(target)), None)
        staged_secrets[identifier] = transaction.root / staged_entry["staged"] if staged_entry else target
        entry.pop(source)
        entry["secretFile"] = os.path.relpath(target, storage_root)
    config["agents"] = agents
    # Account creation and passwords belong exclusively to IdentityStore.
    # A configuration save must never create a second credential authority.
    from oai_core.server import create_core, load_config

    transaction.watch_file(root / config["masterKeyFile"])
    # load_config resolves paths relative to its input file. Validate a private
    # candidate with equivalent absolute runtime paths; active JSON stays portable.
    candidate = copy.deepcopy(config)
    for name in ("storageRoot", "masterKeyFile", "contractRoot", "identityDatabase"):
        if candidate.get(name):
            candidate[name] = str((root / candidate[name]).absolute())
    for name in ("certFile", "keyFile"):
        if candidate.get("tls", {}).get(name):
            candidate["tls"][name] = str((root / candidate["tls"][name]).absolute())
    for agent in candidate.get("agents", []):
        for name in ("codexHome", "gatewayRoot"):
            if agent.get(name):
                agent[name] = str((root / agent[name]).absolute())
        if agent.get("id") in staged_secrets:
            agent["secretFile"] = str(staged_secrets[agent["id"]])
        elif agent.get("secretFile"):
            agent["secretFile"] = str((storage_root / agent["secretFile"]).resolve())
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
    if not original_key:
        key.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        transaction.add_file(key, owned_bytes(Path(settings["masterKeyFile"])))
    transaction.add_file(config_path, json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    write_start_script(root, transaction=transaction)
    # The old credential file remains as a protected, recoverable legacy input;
    # the new startup script neither sources it nor exports its values.
    if previous is not None and any(entry["target"] == transaction.relative(config_path)
                                    for entry in transaction.state["entries"]):
        backup = root / "runtime" / "configuration-backups" / f"core-{transaction.identifier}.json"
        private_write(backup, previous)
        if path_hash(backup) != content_hash(previous.encode("utf-8")):
            raise ValueError("Configuration backup verification failed")
    return previous is not None


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
    content = read_configuration_text(path, transaction)
    if not content.strip():
        write_configuration_text(path, f"version: 1\nrefs:\n  {key}: {dotenv_scalar(value)}\n", transaction)
        return
    # PyYAML is optional for Core. Refuse an existing document if its structure
    # cannot be parsed safely rather than guessing with line-based edits.
    try:
        import yaml
    except ImportError:
        raise DshYamlParserRequired("Existing DSH credentials require PyYAML for safe updates; preserve the original file") from None

    class CredentialsLoader(yaml.SafeLoader):
        def construct_mapping(self, node, deep=False):
            self.flatten_mapping(node)
            mapping = {}
            for key_node, value_node in node.value:
                name = self.construct_object(key_node, deep=deep)
                if not isinstance(name, str) or name in mapping:
                    raise ValueError("DSH credentials require unique string keys")
                mapping[name] = self.construct_object(value_node, deep=deep)
            return mapping

    try:
        credentials = yaml.load(content, Loader=CredentialsLoader)
        if not isinstance(credentials, dict) or not isinstance(credentials.get("refs", {}), dict):
            raise ValueError("DSH credentials require a mapping with a refs mapping")
        credentials.setdefault("version", 1)
        # A YAML alias may share refs with an unrelated section. Copy only
        # this mapping so adding the OAI reference cannot mutate that section.
        credentials["refs"] = {**credentials.get("refs", {}), key: value}
        text = yaml.safe_dump(credentials, allow_unicode=True, sort_keys=False)
    except yaml.YAMLError:
        raise ValueError("DSH credentials contain unsupported or invalid YAML; preserve the original file") from None
    write_configuration_text(path, text, transaction)



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
    # A configuration save must not create Agent-owned data directories.
    values = {"OAI_HERMES_SESSION_TOKEN": dotenv_scalar(token), "OAI_HERMES_GATEWAY_ROOT": dotenv_scalar(gateway_root),
              "OAI_HERMES_ACCOUNTS": json.dumps(account_ids), "OAI_HERMES_SESSION_PORT": str(port)}
    write_configuration_text(env_file, update_environment_text(content, values), transaction)



def _recovery_before_config(root: Path) -> dict:
    """Read only a hash-verified before-image from an interrupted journal."""
    raw = owned_bytes(root / "runtime" / "configuration-journal.json", private=True)
    state = json.loads(raw) if raw is not None else None
    if not isinstance(state, dict) or state.get("version") != 1 or state.get("status") != "applying":
        raise ValueError("No interrupted configuration can be safely recovered")
    identifier = state.get("id")
    if not isinstance(identifier, str) or re.fullmatch(r"[0-9]{8}T[0-9]{12}Z-[0-9a-f]{8}", identifier) is None:
        raise ValueError("Invalid configuration recovery journal")
    entries = state.get("entries", [])
    candidates = [entry for entry in entries if isinstance(entry, dict) and entry.get("target") == "core.json"
                  and entry.get("kind") == "file" and entry.get("before") and entry.get("backup")]
    if len(candidates) != 1:
        raise ValueError("No verified configuration before-image")
    entry = candidates[0]
    backup = root / entry["backup"]
    if (backup.parent != root / "runtime" / "configuration-backups" / identifier
            or not re.fullmatch(r"old-[0-9]+", backup.name)
            or path_hash(backup) != entry["before"]
            or path_hash(root / "core.json") != entry.get("after")):
        raise ValueError("Configuration backup or interrupted candidate does not match")
    config = json.loads(owned_bytes(backup))
    _identity_path_from_config(root, config)
    return config


def _interactive_management(root: Path, scope: str):
    """Authenticate in the same process; never transmit secrets in argv/env."""
    from .identity import IdentityStore
    from .gateway.local_keys import LocalMasterKeyStore

    root = root.resolve()
    raw = owned_bytes(root / "core.json")
    if raw is None:
        raise ValueError("Core configuration is missing")
    try:
        config = json.loads(raw)
        identity_path = _identity_path_from_config(root, config)
        if not isinstance(config.get("masterKeyFile"), str) or not config["masterKeyFile"]:
            raise ValueError("Core configuration requires masterKeyFile")
    except (ValueError, TypeError, KeyError):
        config = _recovery_before_config(root)
        identity_path = _identity_path_from_config(root, config)
    key = config.get("masterKeyFile")
    if not isinstance(key, str) or not key:
        raise ValueError("Core configuration requires masterKeyFile")
    # The key store reads the original key only when the policy requires MFA.
    # This permits password-only repair with a missing business key.
    store = IdentityStore(identity_path, key_store=LocalMasterKeyStore(root / key), create=False)
    try:
        return _authenticate_management(store, scope)
    except BaseException:
        store.close()
        raise


def _authenticate_management(store, scope: str):
    username = _terminal_input("管理员账号: ")
    password = _terminal_secret("管理员密码: ")
    try:
        try:
            handle = store.begin_management(username, password, scopes={scope})
        except Exception as error:
            if getattr(error, "code", None) != "MFA_REQUIRED":
                raise
            details = getattr(error, "details", {})
            challenge = details.get("challengeId") if isinstance(details, dict) else None
            methods = details.get("methods") if isinstance(details, dict) else None
            if not isinstance(challenge, str) or not isinstance(methods, list):
                raise
            available = [method for method in ("totp", "recovery-code") if method in methods]
            if not available:
                raise
            method = available[0]
            if len(available) > 1:
                choice = _terminal_input("二次验证方式（1 验证器，2 恢复码）: ")
                if choice not in {"1", "2"}:
                    raise PermissionError("已取消二次验证")
                method = available[int(choice) - 1]
            secret = _terminal_secret("请输入验证码: " if method == "totp" else "请输入恢复码: ")
            try:
                handle = store.begin_management(username, password, scopes={scope}, challenge_id=challenge,
                                                otp=secret if method == "totp" else None,
                                                recovery_code=secret if method == "recovery-code" else None)
            finally:
                secret = ""
    finally:
        password = ""
    return store, handle


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--agents", default="")
    parser.add_argument("--hermes-account")
    parser.add_argument("--request-id")
    arguments = parser.parse_args()
    selected = arguments.agents.split(",") if arguments.agents else []
    if len(set(selected)) != len(selected) or set(selected) - {"dsh", "hermes", "codex"}:
        parser.error("Invalid Agent selection")
    try:
        store, handle = _interactive_management(arguments.root, "config")
        try:
            complete = configure(arguments.root, arguments.host, arguments.port, arguments.username, selected,
                                 arguments.hermes_account, store=store, handle=handle, request_id=arguments.request_id)
        finally:
            try:
                store.revoke_management(handle)
            finally:
                store.close()
    except Exception as exc:
        # Exception strings may originate in parsers of credential files. Do not
        # echo their contents, passwords or adapter tokens to terminal/logs.
        if isinstance(exc, DshYamlParserRequired):
            print(f"[ERROR] 配置保存失败（{exc.code}）；安全更新已有 DSH YAML 需要 PyYAML，旧配置已保留。"
                  f"请在当前 Python 环境安装后重试：{shlex.quote(sys.executable)} -m pip install PyYAML==6.0.3", file=sys.stderr)
        else:
            print(f"[ERROR] 配置保存失败（{type(exc).__name__}）；旧配置已保留或有待恢复。请检查权限、输入及 runtime/configuration-journal.json。", file=sys.stderr)
        raise SystemExit(1) from None
    if not complete:
        print("[PARTIAL] 配置事务已提交；可选 skill 安装未全部完成。")
        raise SystemExit(2)


if __name__ == "__main__":
    main()
