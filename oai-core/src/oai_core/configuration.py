"""Shared installer configuration with private credentials and preserved state."""
from __future__ import annotations

import argparse
import asyncio
import builtins
from collections.abc import Sequence
from contextlib import closing, contextmanager
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
import sqlite3
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
        print("[INFO] 已自动恢复上次未完成的配置更改，原有密钥和账号数据完好无损。")



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


def _ui_use_color() -> bool:
    return sys.stdout.isatty() and not os.environ.get("NO_COLOR")


def _ui_style(text: str, code: str) -> str:
    return f"\033[{code}m{text}\033[0m" if _ui_use_color() else text


def _ui_box(title: str, lines: tuple[str, ...] | list[str] = ()) -> None:
    border = _ui_style("╭──────────────────────────────────────────────────────────╮", "36;1")
    bottom = _ui_style("╰──────────────────────────────────────────────────────────╯", "36;1")
    bar = _ui_style("│", "36;1")
    print(f"\n{border}")
    print(f"{bar}  {_ui_style(title, '1')}")
    for line in lines:
        print(f"{bar}  {line}")
    print(bottom)


def _ui_step(message: str) -> None:
    print(f"{_ui_style('▸', '36;1')} {message}")


def _ui_ok(message: str) -> None:
    print(f"{_ui_style('✓', '32;1')} {message}")


def _ui_warn(message: str) -> None:
    print(f"{_ui_style('!', '33;1')} {message}")


def _terminal_input(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise PermissionError("A terminal is required for interactive configuration")
    return input(prompt).strip()


_DEFAULT_TERMINAL_INPUT = _terminal_input


def _select_option(
    prompt: str,
    options: Sequence[tuple[str, str]],
    *,
    default_index: int = 0,
) -> str:
    """支持 ↑/↓ 方向键切换选项并回车确认，同时兼容直接输入编号或字母。"""
    if (
        _terminal_input is not _DEFAULT_TERMINAL_INPUT
        or type(builtins.input).__name__ != "builtin_function_or_method"
        or not sys.stdin.isatty()
        or not sys.stdout.isatty()
    ):
        for _, label in options:
            print(f"  {label}")
        return _terminal_input(prompt)
    try:
        import select
        import termios

        fd = sys.stdin.fileno()
        old_attrs = termios.tcgetattr(fd)
    except Exception:
        for _, label in options:
            print(f"  {label}")
        return _terminal_input(prompt)

    selected = max(0, min(default_index, len(options) - 1))

    def _render(*, initial: bool = False, typed: str = "") -> None:
        if not initial:
            sys.stdout.write(f"\r\033[{len(options)}A")
        for idx, (_, label) in enumerate(options):
            sys.stdout.write("\r\033[2K")
            if idx == selected:
                marker = _ui_style("❯", "36;1")
                styled_label = _ui_style(label, "36;1")
                sys.stdout.write(f"  {marker} {styled_label}\n")
            else:
                sys.stdout.write(f"    {label}\n")
        sys.stdout.write(f"\r\033[2K{prompt}{typed}")
        sys.stdout.flush()

    typed_chars: list[str] = []
    new_attrs = list(old_attrs)
    new_attrs[3] &= ~(termios.ICANON | termios.ECHO)
    new_attrs[6][termios.VMIN] = 1
    new_attrs[6][termios.VTIME] = 0
    try:
        termios.tcsetattr(fd, termios.TCSANOW, new_attrs)
        _render(initial=True)
        while True:
            raw = os.read(fd, 1)
            if not raw:
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise EOFError
            ch = raw.decode("utf-8", errors="ignore")
            if ch in ("\r", "\n"):
                sys.stdout.write("\n")
                sys.stdout.flush()
                if typed_chars:
                    return "".join(typed_chars).strip()
                return options[selected][0]
            if ch == "\x03":
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise KeyboardInterrupt
            if ch == "\x04":
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise EOFError
            if ch in ("\x7f", "\b"):
                if typed_chars:
                    typed_chars.pop()
                    _render(typed="".join(typed_chars))
                continue
            if ch == "\x1b":
                seq = ""
                if select.select([fd], [], [], 0.05)[0]:
                    b1 = os.read(fd, 1).decode("utf-8", errors="ignore")
                    seq += b1
                    if b1 in ("[", "O") and select.select([fd], [], [], 0.05)[0]:
                        seq += os.read(fd, 1).decode("utf-8", errors="ignore")
                if seq in ("[A", "OA", "[D", "OD"):
                    selected = (selected - 1) % len(options)
                    typed_chars.clear()
                    _render()
                elif seq in ("[B", "OB", "[C", "OC"):
                    selected = (selected + 1) % len(options)
                    typed_chars.clear()
                    _render()
                continue
            if ch.isprintable():
                typed_chars.append(ch)
                current = "".join(typed_chars).strip().lower()
                for idx, (val, _) in enumerate(options):
                    if current == val.lower():
                        selected = idx
                        break
                _render(typed="".join(typed_chars))
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_attrs)


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
    _ui_box("🌐 网络与端口设置", (
        "0.0.0.0 表示允许局域网设备（如手机 App）连接，127.0.0.1 表示仅限本机访问。",
        "直接按回车可保持当前设置不变。",
    ))
    choice = _terminal_input(f"监听地址 [{host}]{'（输入 2fa 管理两步验证）' if allow_security else ''}: ")
    if allow_security and choice.lower() == "2fa":
        _manage_security(root, store, handle)
        return
    new_host = choice or host
    raw_port = _terminal_input(f"监听端口 [{port}]: ")
    new_port = int(raw_port) if raw_port else port
    validate_listener(new_host, new_port)
    if (new_host, new_port) == (host, port):
        _ui_ok("网络配置未更改。")
        return
    if not configure(root, new_host, new_port, operator["username"], selected, hermes,
                     store=store, handle=handle):
        _ui_warn("网络配置已保存，但可选的 AI 助手技能（skill）暂未同步完成。")


def _manage_accounts(root: Path, store, handle: str) -> None:
    require_configuration_access(root, store, handle, "accounts.read")
    _ui_box("👤 账号与权限管理", (
        "管理可登录手机 App 与后台的账号。管理员 (admin) 拥有完整管理权限。",
    ))
    choice = _select_option(
        "请选择（↑/↓ 切换，回车确认）: ",
        (
            ("1", "1. 查看账号列表"),
            ("2", "2. 创建新账号"),
            ("3", "3. 修改/重置密码"),
            ("4", "4. 删除账号"),
            ("0", "0. 返回"),
        ),
        default_index=0,
    )
    if choice in {"", "0"}:
        return
    if choice == "1":
        accounts = store.list_accounts(handle).get("accounts", [])
        if not accounts:
            print("  （暂无账号）")
        for account in accounts:
            role_label = "管理员" if account["role"] == "admin" else "普通用户"
            print(f"  • {account['username']} ({account['role']} / {role_label})")
        return
    if choice not in {"2", "3", "4"}:
        raise ValueError("无效的账号操作")
    require_configuration_access(root, store, handle, "accounts.write")
    username = _terminal_input("账号名称: ")
    if not username:
        raise ValueError("账号名称不能为空")
    if choice == "4":
        if _terminal_input(f"删除账号将立即注销其登录状态，请输入 {username} 确认: ") != username:
            print("已取消。")
            return
        store.delete_account(handle, username)
    else:
        password = _new_password()
        try:
            if choice == "2":
                store.create_account(handle, username, password)
            else:
                if _terminal_input(f"重置 {username} 的密码将使已登录设备下线，请输入账号名称确认: ") != username:
                    print("已取消。")
                    return
                store.reset_password(handle, username, password)
        finally:
            password = ""
    _ui_ok("账号操作已完成。")


def _parse_dotenv(content: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].strip()
        if "=" not in line:
            continue
        key, raw_val = line.split("=", 1)
        key, raw_val = key.strip(), raw_val.strip()
        if not key:
            continue
        if raw_val.startswith("[") and raw_val.endswith("]"):
            values[key] = raw_val
            continue
        try:
            parts = shlex.split(raw_val)
            values[key] = parts[0] if len(parts) == 1 else raw_val.strip("\"'")
        except ValueError:
            values[key] = raw_val.strip("\"'")
    return values


def detect_host_agents() -> dict[str, list[Path]]:
    detected: dict[str, list[Path]] = {}
    euid = os.geteuid()
    hermes_candidates = (
        [Path(os.environ["HERMES_HOME"]).expanduser()]
        if os.environ.get("HERMES_HOME")
        else [Path.home() / ".hermes", *([Path("/home/hermes/.hermes")] if euid == 0 else [])]
    )
    for cand in hermes_candidates:
        try:
            if cand.is_symlink():
                if cand.lstat().st_uid != euid:
                    continue
                cand = cand.resolve()
            if (cand.is_dir() and not cand.is_symlink()
                    and (cand.stat().st_uid == euid or euid == 0)
                    and ((cand / "hermes-agent").is_dir()
                         or (cand / "open-android-intelligence-gateway").is_dir()
                         or (cand / ".env").is_file()
                         or (cand / "config.yaml").is_file())):
                detected["hermes"] = [cand]
                break
        except OSError:
            continue
    dsh_candidates = ([Path(os.environ["DSH_HOME"]).expanduser()]
                      if os.environ.get("DSH_HOME")
                      else [Path.home() / ".dsh", Path.home() / ".dsh-cline"])
    dsh_homes: list[Path] = []
    seen: set[Path] = set()
    for home in dsh_candidates:
        try:
            if home.is_symlink():
                if home.lstat().st_uid != euid:
                    continue
                home = home.resolve()
            if not home.is_dir() or home.is_symlink() or (home.stat().st_uid != euid and euid != 0):
                continue
            resolved = home.resolve()
            if resolved in seen:
                continue
            if ((home / "cordis.yml").is_file()
                    or (home / ".credentials.yaml").is_file()
                    or (home / ".env").is_file()
                    or (home / "profiles").is_dir()
                    or (home / "runtime").is_dir()):
                seen.add(resolved)
                dsh_homes.append(home)
        except OSError:
            continue
    if dsh_homes:
        detected["dsh"] = dsh_homes
    return detected


def _read_host_text(path: Path, owner_uid: int) -> str:
    if not path.is_file() or path.is_symlink():
        return ""
    try:
        info = path.lstat()
        if info.st_uid not in {os.geteuid(), owner_uid, 0}:
            return ""
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _write_host_text(path: Path, text: str, owner_uid: int, owner_gid: int, mode: int = 0o600) -> None:
    if path.is_symlink():
        raise ValueError(f"Configuration file must not be a symlink: {path}")
    temporary = path.with_name(path.name + "." + secrets.token_hex(8) + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), mode)
    with os.fdopen(descriptor, "wb") as target:
        target.write(text.encode("utf-8"))
        target.flush()
        os.fsync(target.fileno())
        os.fchmod(target.fileno(), mode)
        if os.geteuid() == 0 and owner_uid != 0:
            try:
                os.fchown(target.fileno(), owner_uid, owner_gid)
            except OSError:
                pass
        os.fsync(target.fileno())
    os.replace(temporary, path)
    fsync_directory(path.parent)


def _ensure_host_dir(path: Path, owner_uid: int, owner_gid: int, mode: int = 0o700) -> None:
    if path.is_symlink():
        raise ValueError(f"Private directory must not be a symlink: {path}")
    path.mkdir(parents=True, exist_ok=True, mode=mode)
    try:
        path.chmod(mode)
        if os.geteuid() == 0 and owner_uid != 0:
            os.chown(path, owner_uid, owner_gid)
    except OSError:
        pass


def _sync_hermes_venv_packages(root: Path, hermes_home: Path, owner_uid: int, owner_gid: int) -> None:
    import shutil
    repo_fallback = Path(__file__).resolve().parents[3]
    core_pkg = root / "oai-core" / "src" / "oai_core"
    if not core_pkg.is_dir():
        core_pkg = Path(__file__).resolve().parent
    adapter_pkg = root / "integrations" / "hermes-session-adapter" / "src" / "oai_hermes_adapter"
    if not adapter_pkg.is_dir():
        adapter_pkg = repo_fallback / "integrations" / "hermes-session-adapter" / "src" / "oai_hermes_adapter"
    if not core_pkg.is_dir() or not adapter_pkg.is_dir():
        return
    venv_dir = hermes_home / "hermes-agent" / "venv"
    if not venv_dir.is_dir():
        return
    site_dirs = list(venv_dir.glob("lib*/python*/site-packages"))
    for site_dir in site_dirs:
        if not site_dir.is_dir() or site_dir.is_symlink():
            continue
        try:
            editable_adapter = site_dir / "__editable__.oai_hermes_session_adapter-0.2.0.pth"
            editable_core = site_dir / "__editable__.open_android_intelligence_core-0.2.0.pth"
            if (owner_uid == os.geteuid() and editable_adapter.is_file()
                    and not (site_dir / "oai_hermes_adapter").is_dir()):
                adapter_src = str((root / "integrations" / "hermes-session-adapter" / "src").resolve()) + "\n"
                core_src = str((root / "oai-core" / "src").resolve()) + "\n"
                if editable_adapter.read_text(encoding="utf-8", errors="replace") != adapter_src:
                    editable_adapter.write_text(adapter_src, encoding="utf-8")
                if editable_core.is_file() and editable_core.read_text(encoding="utf-8", errors="replace") != core_src:
                    editable_core.write_text(core_src, encoding="utf-8")
                continue
            for src_dir, dest_name in ((core_pkg, "oai_core"), (adapter_pkg, "oai_hermes_adapter")):
                dest_dir = site_dir / dest_name
                if dest_dir.is_symlink():
                    continue
                shutil.copytree(
                    src_dir,
                    dest_dir,
                    dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
                )
                if os.geteuid() == 0 and owner_uid != 0:
                    for dirpath, dirnames, filenames in os.walk(dest_dir):
                        os.chown(dirpath, owner_uid, owner_gid)
                        for fname in filenames:
                            os.chown(os.path.join(dirpath, fname), owner_uid, owner_gid)
        except Exception:
            continue
    plugin_manifest = root / "integrations" / "hermes-session-adapter" / "plugin.yaml"
    if not plugin_manifest.is_file():
        plugin_manifest = repo_fallback / "integrations" / "hermes-session-adapter" / "plugin.yaml"
    installed_plugin = hermes_home / "plugins" / "oai-hermes-session-adapter"
    if (plugin_manifest.is_file() and not plugin_manifest.is_symlink()
            and installed_plugin.is_dir() and not installed_plugin.is_symlink()):
        try:
            manifest_text = plugin_manifest.read_text(encoding="utf-8")
            target_manifest = installed_plugin / "plugin.yaml"
            if not target_manifest.is_symlink():
                current_manifest = _read_host_text(target_manifest, owner_uid)
                if current_manifest != manifest_text:
                    _write_host_text(target_manifest, manifest_text, owner_uid, owner_gid, 0o600)
        except Exception:
            pass


def _ensure_agent_secret_file(storage_root: Path, agent_id: str, preferred_token: str | None) -> tuple[str, str]:
    from .agent_registration import create_bridge_secret
    bridge_dir = storage_root / ".agent-secrets"
    private_directory(bridge_dir)
    target = bridge_dir / f"agent-{agent_id}.secret"
    valid_preferred = (preferred_token if isinstance(preferred_token, str)
                       and 32 <= len(preferred_token) <= 4096
                       and not any(c in preferred_token for c in "\r\n\0") else None)
    if target.is_file():
        existing = private_read(target).removesuffix("\n")
        if 32 <= len(existing) <= 4096 and not any(c in existing for c in "\r\n\0"):
            if valid_preferred and existing != valid_preferred:
                private_write(target, valid_preferred + "\n")
                return os.path.relpath(target, storage_root), valid_preferred
            return os.path.relpath(target, storage_root), existing
    if valid_preferred:
        private_write(target, valid_preferred + "\n")
        return os.path.relpath(target, storage_root), valid_preferred
    if not target.exists():
        create_bridge_secret(target)
    token = private_read(target).removesuffix("\n")
    return os.path.relpath(target, storage_root), token


def _write_trusted_core_manifest(trusted_path: Path, instance_id: str, public_hex: str,
                                 uid: int, gid: int) -> None:
    if trusted_path.is_symlink() or trusted_path.parent.is_symlink():
        return
    _ensure_host_dir(trusted_path.parent, uid, gid, 0o700)
    existing: dict[str, str] = {}
    if trusted_path.is_file() and not trusted_path.is_symlink():
        raw_text = _read_host_text(trusted_path, uid)
        if raw_text:
            try:
                parsed = json.loads(raw_text)
                if isinstance(parsed, dict):
                    existing = {str(k): str(v) for k, v in parsed.items() if isinstance(k, str) and isinstance(v, str)}
            except Exception:
                existing = {}
    needs_write = existing.get(instance_id) != public_hex or not trusted_path.exists()
    if not needs_write:
        try:
            st = trusted_path.stat()
            needs_write = stat.S_IMODE(st.st_mode) != 0o600 or (os.geteuid() == 0 and uid != 0 and st.st_uid != uid)
        except OSError:
            needs_write = True
    if needs_write:
        existing[instance_id] = public_hex
        _write_host_text(trusted_path, json.dumps(existing, ensure_ascii=False, indent=2) + "\n", uid, gid, 0o600)


def _ensure_hermes_trusted_core(gateway_root: Path, instance_id: str, signing_key,
                                owner_uid: int | None = None, owner_gid: int | None = None, *,
                                extra_trusted_file: Path | None = None) -> Path | None:
    if signing_key is None:
        return None
    uid = os.geteuid() if owner_uid is None else owner_uid
    gid = os.getegid() if owner_gid is None else owner_gid
    from cryptography.hazmat.primitives import serialization
    public_hex = signing_key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    ).hex()
    _ensure_host_dir(gateway_root.parent, uid, gid, 0o700)
    _ensure_host_dir(gateway_root, uid, gid, 0o700)
    trusted_path = gateway_root.resolve().parent / "trusted-cores.json"
    _write_trusted_core_manifest(trusted_path, instance_id, public_hex, uid, gid)
    if extra_trusted_file is not None:
        try:
            resolved_extra = extra_trusted_file.expanduser()
            if (resolved_extra.is_absolute() and not resolved_extra.is_symlink()
                    and resolved_extra.resolve() != trusted_path.resolve()
                    and resolved_extra.parent.is_dir()
                    and resolved_extra.parent.stat().st_uid in {os.geteuid(), uid}):
                _write_trusted_core_manifest(resolved_extra, instance_id, public_hex, uid, gid)
        except OSError:
            pass
    return trusted_path


def _write_if_changed(path: Path, text: str) -> None:
    if path.is_file() and not path.is_symlink():
        existing = owned_bytes(path)
        if existing is not None and existing.decode("utf-8", errors="replace") == text:
            try:
                if stat.S_IMODE(path.stat().st_mode) == 0o600:
                    return
            except OSError:
                pass
    private_write(path, text)


def provision_host_agent_files(root: Path, instance_id: str, signing_key: Any = None) -> list[dict]:
    detected = detect_host_agents()
    if not detected:
        return []
    raw_config = owned_bytes(root / "core.json")
    if raw_config is None:
        return []
    config = json.loads(raw_config)
    storage = (root / config["storageRoot"]).resolve()
    private_directory(storage)
    legacy_tokens: dict[str, str] = {}
    token_path = root / "runtime" / "secrets" / "adapter-tokens.env"
    if token_path.is_file():
        try:
            legacy_tokens = read_tokens(token_path)
        except Exception:
            legacy_tokens = {}
    candidates_dir = root / "runtime" / "agent-candidates"
    private_directory(candidates_dir)
    provisioned: list[dict] = []
    if "hermes" in detected:
        hermes_home = detected["hermes"][0]
        try:
            st = hermes_home.stat()
            owner_uid, owner_gid = st.st_uid, st.st_gid
        except OSError:
            owner_uid, owner_gid = os.geteuid(), os.getegid()
        _sync_hermes_venv_packages(root, hermes_home, owner_uid, owner_gid)
        env_file = hermes_home / ".env"
        env_text = _read_host_text(env_file, owner_uid)
        env_vars = _parse_dotenv(env_text)
        preferred = env_vars.get("OAI_HERMES_SESSION_TOKEN")
        if not preferred and env_vars.get("OAI_HERMES_SESSION_SECRET_FILE"):
            secret_candidate = Path(env_vars["OAI_HERMES_SESSION_SECRET_FILE"]).expanduser()
            if not secret_candidate.is_absolute():
                secret_candidate = hermes_home / secret_candidate
            preferred = _read_host_text(secret_candidate, owner_uid).removesuffix("\n") or None
        preferred = preferred or legacy_tokens.get("OAI_HERMES_SESSION_TOKEN")
        secret_rel, token = _ensure_agent_secret_file(storage, "hermes", preferred)
        gateway_root = Path(env_vars.get("OAI_HERMES_GATEWAY_ROOT")
                            or (hermes_home / "open-android-intelligence-gateway" / "accounts")).expanduser()
        try:
            port = int(env_vars.get("OAI_HERMES_SESSION_PORT", "8792"))
        except ValueError:
            port = 8792
        hermes_instance_id = env_vars.get("OAI_HERMES_INSTANCE_ID") or "hermes"
        accounts, _ = _discover_hermes_native_accounts(hermes_home)
        existing_trusted = (Path(env_vars["OAI_HERMES_TRUSTED_CORES_FILE"]).expanduser()
                            if env_vars.get("OAI_HERMES_TRUSTED_CORES_FILE") else None)
        trusted_path = _ensure_hermes_trusted_core(
            gateway_root, instance_id, signing_key, owner_uid, owner_gid,
            extra_trusted_file=existing_trusted,
        )
        updates = {
            "OAI_HERMES_SESSION_TOKEN": dotenv_scalar(token),
            "OAI_HERMES_GATEWAY_ROOT": dotenv_scalar(str(gateway_root)),
            "OAI_HERMES_ACCOUNTS": json.dumps(accounts, ensure_ascii=False),
            "OAI_HERMES_SESSION_PORT": str(port),
            "OAI_HERMES_INSTANCE_ID": dotenv_scalar(hermes_instance_id),
        }
        if trusted_path is not None:
            updates["OAI_HERMES_TRUSTED_CORES_FILE"] = dotenv_scalar(str(trusted_path))
        new_env_text = update_environment_text(
            env_text, updates, unset={"OAI_HERMES_SESSION_SECRET_FILE", "OAI_HERMES_SESSION_SECRET_FD"}
        )
        if new_env_text != env_text or not env_file.is_file():
            _write_host_text(env_file, new_env_text, owner_uid, owner_gid, 0o600)
        manifest = {
            "id": "hermes",
            "name": "Hermes",
            "type": "hermes",
            "instanceId": hermes_instance_id,
            "sessionScope": "account",
            "url": f"http://127.0.0.1:{port}",
            "secretFile": secret_rel,
        }
        _write_if_changed(candidates_dir / "hermes.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        provisioned.append(manifest)
    if "dsh" in detected:
        dsh_homes = detected["dsh"]
        preferred = None
        home_tokens: list[str | None] = []
        missing_credentials = False
        for home in dsh_homes:
            env_file = home / ".env"
            current_tok = None
            if env_file.is_file():
                try:
                    owner_uid = home.stat().st_uid
                    current_tok = _parse_dotenv(_read_host_text(env_file, owner_uid)).get("OAI_DSH_ADAPTER_TOKEN")
                except Exception:
                    current_tok = None
                if preferred is None and current_tok:
                    preferred = current_tok
            home_tokens.append(current_tok)
            if not (home / ".credentials.yaml").is_file():
                missing_credentials = True
        preferred = preferred or legacy_tokens.get("OAI_DSH_ADAPTER_TOKEN")
        secret_rel, token = _ensure_agent_secret_file(storage, "dsh", preferred)
        if missing_credentials or any(tok != token for tok in home_tokens):
            try:
                sync_dsh_adapter_env(token, homes=detected.get("dsh"))
            except Exception:
                pass
        manifest = {
            "id": "dsh",
            "name": "DSH",
            "type": "dsh",
            "instanceId": "dsh",
            "sessionScope": "deployment",
            "url": "http://127.0.0.1:8791",
            "secretFile": secret_rel,
        }
        _write_if_changed(candidates_dir / "dsh.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        provisioned.append(manifest)
    return provisioned


def provision_host_candidates(root: Path, store, handle: str, operator: dict) -> list[dict]:
    require_configuration_access(root, store, handle, "agents.manage")
    signing_key = store.key_store.instance_signing_key(operator["instanceId"]) if store.key_store is not None else None
    return provision_host_agent_files(root, operator["instanceId"], signing_key)


def _discover_hermes_native_accounts(hermes_home: Path) -> tuple[list[str], Path]:
    owner_uid = hermes_home.stat().st_uid if hermes_home.exists() else os.geteuid()
    env_file = hermes_home / ".env"
    env_vars = _parse_dotenv(_read_host_text(env_file, owner_uid)) if env_file.is_file() else {}
    gateway_root = Path(env_vars.get("OAI_HERMES_GATEWAY_ROOT")
                        or (hermes_home / "open-android-intelligence-gateway" / "accounts")).expanduser()
    try:
        accounts = json.loads(env_vars.get("OAI_HERMES_ACCOUNTS", "[]"))
        if not isinstance(accounts, list):
            accounts = []
    except Exception:
        accounts = []
    from .gateway.account_paths import WIRE_ID_PATTERN, account_paths
    valid = []
    for acct in accounts:
        if (isinstance(acct, str) and WIRE_ID_PATTERN.fullmatch(acct) and acct not in valid
                and account_paths(gateway_root, acct).database.is_file()):
            valid.append(acct)
    if gateway_root.is_dir():
        for db_file in sorted(gateway_root.glob("*/gateway.sqlite")):
            if not db_file.is_file() or db_file.is_symlink() or db_file.parent.is_symlink():
                continue
            try:
                # 目录名是账号 ID 的哈希；只从现有数据库读取真实身份。
                with closing(sqlite3.connect(db_file.absolute().as_uri() + "?mode=ro", uri=True, timeout=0.1)) as db:
                    row = db.execute("SELECT value FROM account_metadata WHERE key='gateway_account_id'").fetchone()
            except (OSError, sqlite3.Error):
                continue
            acct = row[0] if row else None
            if (not isinstance(acct, str) or WIRE_ID_PATTERN.fullmatch(acct) is None
                    or acct.startswith("oai_") or acct in valid):
                continue
            if account_paths(gateway_root, acct).database == db_file.resolve():
                valid.append(acct)
    return valid, gateway_root


def bind_host_hermes_owner(registration, entry: dict, identity_store: Any) -> None:
    if entry.get("type") != "hermes" or identity_store is None:
        return
    detected = detect_host_agents()
    if "hermes" not in detected:
        return
    accounts, _ = _discover_hermes_native_accounts(detected["hermes"][0])
    if not accounts:
        return
    try:
        with identity_store.transaction() as db:
            admins = db.execute(
                "SELECT account_id, username FROM accounts WHERE role='admin' AND status='active' ORDER BY rowid"
            ).fetchall()
    except Exception:
        return
    existing = registration.mappings(entry["id"])
    for account_id, username in admins:
        if not isinstance(username, str) or account_id in existing:
            continue
        target_native = username if username in accounts else (accounts[0] if len(accounts) == 1 and not existing else None)
        if target_native and target_native not in existing.values():
            try:
                registration.bind_initial_owner_mapping(entry["id"], account_id, target_native)
                existing = registration.mappings(entry["id"])
            except Exception:
                pass


def _confirm_operator_hermes_mapping(registration, handle: str, entry: dict, operator: dict) -> None:
    if entry.get("type") != "hermes":
        return
    username = operator.get("username")
    account_id = operator.get("accountId")
    if not isinstance(username, str) or not username or not isinstance(account_id, str) or not account_id:
        return
    detected = detect_host_agents()
    if "hermes" not in detected:
        return
    accounts, _ = _discover_hermes_native_accounts(detected["hermes"][0])
    if not accounts:
        return
    existing = registration.mappings(entry["id"])
    if account_id in existing:
        return
    target_native = username if username in accounts else (accounts[0] if len(accounts) == 1 and not existing else None)
    if not target_native or target_native in existing.values():
        return
    try:
        registration.confirm_mapping(handle, entry["id"], account_id, target_native)
    except Exception:
        pass


def restart_hermes_service_if_active() -> bool:
    if not sys.platform.startswith("linux"):
        return False
    svc_env = os.environ.copy()
    if "XDG_RUNTIME_DIR" not in svc_env:
        runtime_dir = Path(f"/run/user/{os.geteuid()}")
        if runtime_dir.is_dir():
            svc_env["XDG_RUNTIME_DIR"] = str(runtime_dir)
    prefixes: list[list[str]] = [["systemctl", "--user"]]
    if os.geteuid() == 0:
        prefixes.append(["systemctl"])
        detected_hermes = detect_host_agents().get("hermes", [])
        if detected_hermes:
            try:
                owner_uid = detected_hermes[0].stat().st_uid
                if owner_uid != 0:
                    import pwd
                    owner_user = pwd.getpwuid(owner_uid).pw_name
                    runuser = shutil.which("runuser")
                    if runuser:
                        prefixes.append([runuser, "-u", owner_user, "--", "env",
                                         f"XDG_RUNTIME_DIR=/run/user/{owner_uid}",
                                         f"DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/{owner_uid}/bus",
                                         "systemctl", "--user"])
                    systemctl = shutil.which("systemctl")
                    if systemctl:
                        prefixes.append(["systemctl", f"--machine={owner_user}@.host", "--user"])
            except Exception:
                pass
    for cmd_prefix in prefixes:
        try:
            state = subprocess.run([*cmd_prefix, "is-active", "hermes-gateway.service"],
                                   capture_output=True, text=True, timeout=5, check=False, env=svc_env)
            if state.stdout.strip() in {"active", "activating", "reloading"}:
                subprocess.run([*cmd_prefix, "restart", "hermes-gateway.service"],
                               capture_output=True, text=True, timeout=15, check=True, env=svc_env)
                return True
        except Exception:
            continue
    detected = detect_host_agents()
    if "hermes" in detected:
        import signal
        pid_file = detected["hermes"][0] / "gateway.pid"
        try:
            owner_uid = detected["hermes"][0].stat().st_uid
            raw = _read_host_text(pid_file, owner_uid)
            if raw:
                info = json.loads(raw)
                pid = info.get("pid")
                if isinstance(pid, int) and pid > 1 and info.get("kind") == "hermes-gateway":
                    os.kill(pid, signal.SIGUSR1)
                    return True
        except Exception:
            pass
    return False


def restart_dsh_service_if_active(profile_home: Path | None = None, profile_name: str | None = None) -> bool:
    """若本机检测到运行中的 DSH 服务/进程，自动安全重启使其加载新插件。"""
    if not sys.platform.startswith("linux"):
        return False
    import pwd
    import signal
    import shutil
    import time

    svc_env = os.environ.copy()
    if "XDG_RUNTIME_DIR" not in svc_env:
        runtime_dir = Path(f"/run/user/{os.geteuid()}")
        if runtime_dir.is_dir():
            svc_env["XDG_RUNTIME_DIR"] = str(runtime_dir)
    prefixes: list[list[str]] = [["systemctl", "--user"]]
    detected_dsh = detect_host_agents().get("dsh", [])
    owner_user = None
    owner_uid = None
    owner_gid = None
    if detected_dsh:
        try:
            stat_info = detected_dsh[0].stat()
            owner_uid = stat_info.st_uid
            owner_gid = stat_info.st_gid
            if owner_uid != 0:
                owner_user = pwd.getpwuid(owner_uid).pw_name
        except Exception:
            pass

    if os.geteuid() == 0:
        prefixes.append(["systemctl"])
        if owner_user and owner_uid is not None:
            runuser = shutil.which("runuser")
            if runuser:
                prefixes.append([runuser, "-u", owner_user, "--", "env",
                                 f"XDG_RUNTIME_DIR=/run/user/{owner_uid}",
                                 f"DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/{owner_uid}/bus",
                                 "systemctl", "--user"])
            systemctl = shutil.which("systemctl")
            if systemctl:
                prefixes.append(["systemctl", f"--machine={owner_user}@.host", "--user"])

    units = ["dsh.service", "dsh-web.service", "dsh@web.service", "deepseek-harness.service"]
    for cmd_prefix in prefixes:
        for unit in units:
            try:
                state = subprocess.run([*cmd_prefix, "is-active", unit],
                                       capture_output=True, text=True, timeout=5, check=False, env=svc_env)
                if state.stdout.strip() in {"active", "activating", "reloading"}:
                    subprocess.run([*cmd_prefix, "restart", unit],
                                   capture_output=True, text=True, timeout=15, check=True, env=svc_env)
                    return True
            except Exception:
                continue

    candidate_pid_files: list[Path] = []
    if profile_home is not None:
        candidate_pid_files.extend([
            profile_home.parent / ".dsh-app-remote" / "web.pid",
            profile_home / "web.pid",
            profile_home / "dsh.pid",
        ])
    for home in detected_dsh:
        candidate_pid_files.extend([
            home.parent / ".dsh-app-remote" / "web.pid",
            home / "web.pid",
            home / "dsh.pid",
        ])
    candidate_pid_files.append(Path.home() / ".dsh-app-remote" / "web.pid")

    seen_pids = set()
    for pid_file in candidate_pid_files:
        if not pid_file.is_file():
            continue
        try:
            p_uid = pid_file.stat().st_uid
            raw = _read_host_text(pid_file, p_uid)
            if not raw or not raw.strip().isdigit():
                continue
            pid = int(raw.strip())
            if pid <= 1 or pid in seen_pids:
                continue
            seen_pids.add(pid)

            try:
                os.kill(pid, 0)
            except OSError:
                continue

            is_dsh = False
            proc_cmdline = Path(f"/proc/{pid}/cmdline")
            if proc_cmdline.is_file():
                try:
                    cmd_bytes = proc_cmdline.read_bytes()
                    cmd_parts = [p.decode("utf-8", "replace").lower() for p in cmd_bytes.split(b"\0") if p]
                    if any("dsh" in p or "dsh-android-gateway" in p or "oai-dsh" in p for p in cmd_parts):
                        is_dsh = True
                except Exception:
                    pass
            if not is_dsh and (pid_file.parent / "web.cmd").is_file():
                is_dsh = True

            if not is_dsh:
                continue

            try:
                os.kill(pid, signal.SIGTERM)
            except OSError:
                continue

            for _ in range(15):
                time.sleep(0.2)
                try:
                    os.kill(pid, 0)
                except OSError:
                    break
            else:
                try:
                    os.kill(pid, signal.SIGKILL)
                except OSError:
                    pass

            home_parent = pid_file.parent.parent
            dsh_bin = shutil.which("dsh")
            if not dsh_bin and (home_parent / ".local/bin/dsh").is_file():
                dsh_bin = str(home_parent / ".local/bin/dsh")
            if not dsh_bin:
                dsh_bin = "dsh"

            launch_cmd = [dsh_bin, "web", "--no-open"]
            if profile_name and profile_name != "web":
                launch_cmd = [dsh_bin, "--profile", profile_name, "--no-open"]

            log_file = pid_file.parent / "web.log"
            log_fd = None
            try:
                log_fd = os.open(log_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            except Exception:
                pass
            target_out = log_fd if log_fd is not None else subprocess.DEVNULL

            try:
                if os.geteuid() == 0 and p_uid != 0:
                    p_user = pwd.getpwuid(p_uid).pw_name
                    p_gid = pwd.getpwuid(p_uid).pw_gid
                    runuser = shutil.which("runuser")
                    if runuser:
                        cmd_str = " ".join(shlex.quote(arg) for arg in launch_cmd)
                        proc = subprocess.Popen(
                            [runuser, "-u", p_user, "--", "bash", "-c",
                             f"nohup {cmd_str} > {shlex.quote(str(log_file))} 2>&1 & echo $!"],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                        )
                        out, _ = proc.communicate(timeout=5)
                        new_pid_str = out.strip().splitlines()[-1] if out.strip() else ""
                        if new_pid_str.isdigit():
                            _write_host_text(pid_file, f"{new_pid_str}\n", p_uid, p_gid, 0o600)
                            return True
                    else:
                        child = subprocess.Popen(
                            launch_cmd,
                            stdin=subprocess.DEVNULL, stdout=target_out, stderr=target_out,
                            user=p_uid, group=p_gid, start_new_session=True,
                        )
                        if child and child.pid:
                            _write_host_text(pid_file, f"{child.pid}\n", p_uid, p_gid, 0o600)
                            return True
                else:
                    child = subprocess.Popen(
                        launch_cmd,
                        stdin=subprocess.DEVNULL, stdout=target_out, stderr=target_out,
                        start_new_session=True,
                    )
                    if child and child.pid:
                        p_gid = os.getegid()
                        _write_host_text(pid_file, f"{child.pid}\n", p_uid, p_gid, 0o600)
                        return True
            finally:
                if log_fd is not None:
                    try:
                        os.close(log_fd)
                    except Exception:
                        pass
        except Exception:
            continue

    try:
        proc_root = Path("/proc")
        for proc in proc_root.iterdir():
            if not proc.name.isdigit():
                continue
            proc_pid = int(proc.name)
            if proc_pid <= 1 or proc_pid == os.getpid():
                continue
            try:
                cmd_bytes = (proc / "cmdline").read_bytes()
                cmd_parts = [p.decode("utf-8", "replace").lower() for p in cmd_bytes.split(b"\0") if p]
                if any("dsh" in p for p in cmd_parts) and any("web" in p for p in cmd_parts):
                    proc_uid = proc.stat().st_uid
                    proc_gid = proc.stat().st_gid
                    os.kill(proc_pid, signal.SIGTERM)
                    for _ in range(15):
                        time.sleep(0.2)
                        try:
                            os.kill(proc_pid, 0)
                        except OSError:
                            break
                    else:
                        try:
                            os.kill(proc_pid, signal.SIGKILL)
                        except OSError:
                            pass

                    proc_user = pwd.getpwuid(proc_uid).pw_name if proc_uid != 0 else None
                    home_dir = Path(f"/home/{proc_user}") if proc_user else Path.home()
                    dsh_bin = shutil.which("dsh")
                    if not dsh_bin and (home_dir / ".local/bin/dsh").is_file():
                        dsh_bin = str(home_dir / ".local/bin/dsh")
                    if not dsh_bin:
                        dsh_bin = "dsh"
                    launch_cmd = [dsh_bin, "web", "--no-open"]
                    if profile_name and profile_name != "web":
                        launch_cmd = [dsh_bin, "--profile", profile_name, "--no-open"]

                    if os.geteuid() == 0 and proc_uid != 0 and proc_user:
                        runuser = shutil.which("runuser")
                        if runuser:
                            subprocess.Popen(
                                [runuser, "-u", proc_user, "--", *launch_cmd],
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                start_new_session=True,
                            )
                            return True
                        else:
                            subprocess.Popen(
                                launch_cmd,
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                user=proc_uid, group=proc_gid, start_new_session=True,
                            )
                            return True
                    else:
                        subprocess.Popen(
                            launch_cmd,
                            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            start_new_session=True,
                        )
                        return True
            except Exception:
                continue
    except Exception:
        pass

    return False


def _register_with_host_retry(registration, handle: str, entry: dict, storage: Path, *,
                              already_restarted: bool = False) -> None:
    import time
    try:
        asyncio.run(registration.register(handle, entry, storage))
        return
    except ValueError:
        agent_type = entry.get("type")
        if agent_type not in {"hermes", "dsh"}:
            raise
        if not already_restarted:
            restarted = False
            if agent_type == "hermes":
                restarted = restart_hermes_service_if_active()
            elif agent_type == "dsh":
                restarted = restart_dsh_service_if_active()
            if not restarted:
                raise
    last_error: Exception | None = None
    for _ in range(24):
        time.sleep(0.5)
        try:
            asyncio.run(registration.register(handle, entry, storage))
            return
        except ValueError as err:
            last_error = err
    raise last_error or ValueError("Agent instance handshake failed")


def _is_local_dsh_bridge(entry: dict) -> bool:
    """Only offer local installation for the candidate this installer provisions."""
    from urllib.parse import urlsplit

    if (entry.get("type") != "dsh" or entry.get("instanceId") != "dsh"
            or entry.get("sessionScope") != "deployment"
            or entry.get("prefix", "/oai/session-adapter/v1") != "/oai/session-adapter/v1"):
        return False
    try:
        url = urlsplit(entry.get("url", ""))
        return (url.scheme == "http" and url.hostname in {"127.0.0.1", "::1", "localhost"}
                and url.port == 8791 and not url.username and not url.password
                and url.path in {"", "/"} and not url.query and not url.fragment)
    except (ValueError, TypeError):
        return False


def _is_local_hermes_bridge(entry: dict, *, home: Path | None = None) -> bool:
    """Only repair the detected host's configured loopback bridge."""
    from urllib.parse import urlsplit

    if home is None:
        homes = detect_host_agents().get("hermes", [])
        if len(homes) != 1:
            return False
        home = homes[0]
    try:
        values = _parse_dotenv(_read_host_text(home / ".env", home.stat().st_uid))
        port = int(values.get("OAI_HERMES_SESSION_PORT", "8792"))
        url = urlsplit(entry.get("url", ""))
        return (entry.get("type") == "hermes" and entry.get("sessionScope") == "account"
                and entry.get("instanceId") == values.get("OAI_HERMES_INSTANCE_ID", "hermes")
                and entry.get("prefix", "/oai/session-adapter/v1") == "/oai/session-adapter/v1"
                and url.scheme == "http" and url.hostname in {"127.0.0.1", "::1", "localhost"}
                and url.port == port and not url.username and not url.password
                and url.path in {"", "/"} and not url.query and not url.fragment)
    except (OSError, ValueError, TypeError):
        return False


def _has_legacy_hermes_plugin(entry: dict) -> bool:
    if entry.get("type") != "hermes":
        return False
    homes = detect_host_agents().get("hermes", [])
    if len(homes) != 1 or not _is_local_hermes_bridge(entry, home=homes[0]):
        return False
    home = homes[0]
    legacy_dir = home / "plugins" / "open-android-intelligence-gateway"
    current_dir = home / "plugins" / "oai-hermes-session-adapter"
    return legacy_dir.is_dir() and not legacy_dir.is_symlink() and not current_dir.is_dir()


def _upgrade_hermes_bridge(root: Path, store, handle: str, entry: dict) -> bool:
    from .hermes_installation import HermesAdapterInstallError, install_hermes_adapter

    homes = detect_host_agents().get("hermes", [])
    if len(homes) != 1:
        print("未找到唯一的 Hermes 安装目录，暂时无法自动安装插件；尚未注册。")
        return False
    home = homes[0]
    if not _is_local_hermes_bridge(entry, home=home):
        print("该连接地址不是本机发现的 Hermes 服务，请先在对应机器上安装连接插件后再试；尚未注册。")
        return False
    hermes_choice = _select_option(
        "是否安装或更新本机 Hermes 连接插件（会话适配器）？(↑/↓ 选择或输入 y/n) [n]: ",
        (
            ("y", "1. 是，立即安装/更新 (Yes)"),
            ("n", "2. 否，暂不安装 (No)"),
        ),
        default_index=1,
    )
    if hermes_choice.lower() not in {"y", "yes", "1"}:
        print("已取消安装；尚未注册。")
        return False
    try:
        require_configuration_access(root, store, handle, "agents.manage")
        if detect_host_agents().get("hermes", []) != [home] or not _is_local_hermes_bridge(entry, home=home):
            print("Hermes 目录或连接配置已发生变化，请重新选择候选；尚未注册。")
            return False
        install_hermes_adapter(root, store, handle, home)
    except HermesAdapterInstallError as error:
        print(f"[错误] {error}（{error.code}）；尚未注册。")
        return False
    _ui_ok("Hermes 连接插件已安装并启用，正在重新测试连接...")
    return True


def _upgrade_dsh_bridge(root: Path, store, handle: str, entry: dict, *, allow_install: bool = False) -> bool:
    """An operator selects exactly one existing profile; registration stays separate."""
    from .dsh_installation import DshAdapterInstallError, discover_dsh_profiles, install_dsh_adapter

    if not _is_local_dsh_bridge(entry):
        print("该连接地址不是默认本机 DSH 服务，请先在对应机器上升级连接插件后再试；尚未注册。")
        return False
    try:
        homes = detect_host_agents().get("dsh", [])
        effective_allow_install = allow_install
        profiles = (discover_dsh_profiles(homes, include_uninstalled=True)
                    if allow_install else discover_dsh_profiles(homes))
        if not profiles and not allow_install:
            fallback = discover_dsh_profiles(homes, include_uninstalled=True)
            if fallback:
                profiles = fallback
                effective_allow_install = True
        if not profiles:
            print("未找到可用的 DSH 运行配置 (profile)，请先在 DSH 中创建配置后再试；尚未注册。"
                  if effective_allow_install else "未找到已启用连接插件的 DSH 运行配置 (profile)，请先在 DSH 中安装插件后再试；尚未注册。")
            return False
        print("请选择要安装连接插件的 DSH 运行配置 (profile)，原有配置会自动备份："
              if effective_allow_install else "检测到 DSH 连接插件版本较旧。请选择要升级的运行配置 (profile)，原有配置会自动备份：")
        profile_options = [
            (str(index), f"{index}. {os.path.relpath(profile.home, Path.home())} / {profile.name}")
            for index, profile in enumerate(profiles, 1)
        ]
        profile_options.append(("", "0. 取消并返回"))
        choice = _select_option(
            "选择并确认安装的 profile 编号（↑/↓ 选择，或选 0 返回）: "
            if effective_allow_install else "选择并确认升级的 profile 编号（↑/↓ 选择，或选 0 返回）: ",
            profile_options,
            default_index=0,
        )
        if choice in {"", "0"}:
            print("已取消安装；尚未注册。" if effective_allow_install else "已取消升级；尚未注册。")
            return False
        if not choice.isdecimal() or not 1 <= int(choice) <= len(profiles):
            print("无效的 DSH profile 编号；尚未注册。")
            return False
        profile = profiles[int(choice) - 1]
        require_configuration_access(root, store, handle, "agents.manage")
        if effective_allow_install:
            install_dsh_adapter(root, store, handle, profile, include_uninstalled=True)
        else:
            install_dsh_adapter(root, store, handle, profile)
    except DshAdapterInstallError as error:
        print(f"[错误] {error}（{error.code}）；尚未注册。")
        return False
    _ui_ok(f"DSH 连接插件已更新：{os.path.relpath(profile.home, Path.home())} / {profile.name}。")
    print("正在重新测试连接；如果 DSH 仍在运行旧版本，请重启或重新加载该 DSH 配置后再试。")
    return True


def auto_connect_host_agents(root: Path, store, handle: str, operator: dict, *,
                             hermes_restarted: bool = False,
                             dsh_restarted: bool = False) -> list[str]:
    from .agent_registration import AgentHandshakeError, AgentRegistration
    from .sessions import AgentRegistry

    if not detect_host_agents():
        return []
    require_configuration_access(root, store, handle, "agents.manage")
    candidates = provision_host_candidates(root, store, handle, operator)
    if not candidates:
        return []
    config = json.loads(owned_bytes(root / "core.json"))
    storage = (root / config["storageRoot"]).resolve()
    signing_key = store.key_store.instance_signing_key(operator["instanceId"]) if store.key_store is not None else None
    registration = AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False,
                                     core_signing_key=signing_key)
    registration.initialize(handle)
    explicit_ids = {item.get("id") for item in config.get("agents", []) if isinstance(item, dict)}
    connected: list[str] = []
    for entry in candidates:
        if entry["id"] in explicit_ids or AgentRegistry.get_factory(entry["type"]) is None:
            continue
        try:
            already_restarted = (
                (bool(hermes_restarted) and entry.get("type") == "hermes")
                or (bool(dsh_restarted) and entry.get("type") == "dsh")
            )
            _register_with_host_retry(
                registration, handle, entry, storage,
                already_restarted=already_restarted,
            )
            _confirm_operator_hermes_mapping(registration, handle, entry, operator)
            connected.append(entry["id"])
            _ui_ok(f"[INFO] 已自动发现并连接本机 AI 助手: {entry['id']} ({entry['type']})")
        except Exception as error:
            if isinstance(error, AgentHandshakeError):
                print(f"[INFO] 已检测到本机 {entry['id']} 助手，但暂未完成连接：{error}（{error.code}）。稍后可在 setup 的「Agent 接入」中一键处理。")
            else:
                print(f"[INFO] 已生成 {entry['id']} 助手待连接配置；待该 Agent 服务运行后可在 setup 中完成接入。")
    return connected


def _configure_agents(root: Path, store, handle: str, operator: dict) -> None:
    from .agent_registration import AgentHandshakeError, AgentRegistration
    from .sessions import AgentRegistry

    require_configuration_access(root, store, handle, "agents.manage")
    provision_host_candidates(root, store, handle, operator)
    config = json.loads(owned_bytes(root / "core.json"))
    storage = (root / config["storageRoot"]).resolve()
    signing_key = store.key_store.instance_signing_key(operator["instanceId"]) if store.key_store is not None else None
    registration = AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False,
                                     core_signing_key=signing_key)
    registered = registration.entries()
    _ui_box("🤖 本地 AI 助手连接", (
        "自动发现本机已安装的 AI 助手（如 Hermes、DSH），验证连通后即可在手机端使用。",
    ))
    print("已连接/已注册的 AI 助手: " + (", ".join(entry["id"] for entry in registered) or "无"))
    roots = [root / "runtime" / "agent-candidates", Path.home() / ".config" / "oai" / "agents"]
    candidates = registration.discover(handle, roots)
    if not candidates:
        print("暂未发现可连接的本地 AI 助手；尚未连接。")
        return
    scope_labels = {"account": "按账号独立会话", "deployment": "全局共享会话"}
    candidate_options = [
        (
            str(index),
            f"{index}. {entry['id']} ({entry['type']}, {entry['instanceId']}, {entry['sessionScope']} · {scope_labels.get(entry['sessionScope'], entry['sessionScope'])})",
        )
        for index, (_, entry) in enumerate(candidates, 1)
    ]
    candidate_options.append(("", "0. 返回上一级"))
    choice = _select_option(
        "选择候选编号（↑/↓ 选择，或选 0 返回）: ",
        candidate_options,
        default_index=0,
    )
    if choice in {"", "0"}:
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
        _ui_ok("已自动配置连接安全凭据与双向信任关系。")
    confirm_agent = _select_option(
        f"确认连接并注册 {entry['id']} 吗？(直接回车确认，或输入 y / {entry['id']}) [y]: ",
        (
            ("y", "1. 是，立即连接 (Yes)"),
            ("n", "2. 否，取消返回 (No)"),
        ),
        default_index=0,
    ).strip()
    if confirm_agent.lower() not in {"", "y", "yes", "1", entry["id"].lower()}:
        print("已取消；尚未注册。")
        return
    require_configuration_access(root, store, handle, "agents.manage")
    registration.initialize(handle)
    try:
        _register_with_host_retry(registration, handle, entry, storage)
    except AgentHandshakeError as error:
        print(f"[错误] {error}（{error.code}）。")
        legacy_hermes_upgrade = (
            error.code == "AGENT_BRIDGE_AUTHENTICATION_FAILED"
            and _has_legacy_hermes_plugin(entry)
        )
        if error.code not in {"AGENT_BRIDGE_UPGRADE_REQUIRED", "AGENT_BRIDGE_UNAVAILABLE"} and not legacy_hermes_upgrade:
            return
        restarted_after_upgrade = False
        if entry.get("type") == "hermes":
            installed = _upgrade_hermes_bridge(root, store, handle, entry)
            if installed:
                restarted_after_upgrade = restart_hermes_service_if_active()
        elif entry.get("type") == "dsh":
            installed = _upgrade_dsh_bridge(root, store, handle, entry,
                                            allow_install=error.code == "AGENT_BRIDGE_UNAVAILABLE")
            if installed:
                restarted_after_upgrade = restart_dsh_service_if_active()
        else:
            return
        if not installed:
            return
        try:
            _register_with_host_retry(
                registration, handle, entry, storage, already_restarted=restarted_after_upgrade
            )
        except AgentHandshakeError as retry_error:
            print(f"[错误] {retry_error}（{retry_error.code}）；尚未注册，请重启或重新加载对应 AI 助手服务后重试。")
            return
    _confirm_operator_hermes_mapping(registration, handle, entry, operator)
    _ui_ok("Agent 已通过真实握手并注册成功！现在可以在手机端选择并使用该助手了。")


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
    _ui_box("⚙️ 后台服务管理", (
        "查看或控制当前用户下的 OAI Core 后台运行状态。",
    ))
    print(f"服务状态: {state} ({detail})")
    choice = _select_option(
        "请选择（↑/↓ 切换，回车确认）: ",
        (
            ("1", "1. 启动"),
            ("2", "2. 停止"),
            ("3", "3. 重启"),
            ("0", "0. 返回"),
        ),
        default_index=0,
    )
    if choice in {"", "0"}:
        return
    action = {"1": "start", "2": "stop", "3": "restart"}.get(choice)
    if action is None:
        raise ValueError("无效的服务操作")
    action_names = {"start": "启动", "stop": "停止", "restart": "重启"}
    confirm_action = _select_option(
        f"确认服务操作「{action_names[action]} ({action})」吗？(直接回车确认，或输入 y / {action}) [y]: ",
        (
            ("y", f"1. 是，执行{action_names[action]} (Yes)"),
            ("n", "2. 否，取消返回 (No)"),
        ),
        default_index=0,
    ).strip()
    if confirm_action.lower() not in {"", "y", "yes", "1", action.lower()}:
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
    _ui_ok(f"服务操作 {action} 已执行。")


def _manage_security(root: Path, store, handle: str) -> None:
    actor = require_configuration_access(root, store, handle, "security")
    required, _, _ = store._policy()
    _ui_box("🔐 两步验证 (2FA) 与管理员恢复码", (
        "开启两步验证后，登录和管理操作需要额外输入手机验证器 App 的动态验证码。",
    ))
    print(f"服务端 2FA: {'开启' if required else '关闭'}（仅开启时需要两步验证）。")
    choice = _select_option(
        "请选择（↑/↓ 切换，回车确认）: ",
        (
            ("1", "1. 绑定手机验证器 (TOTP)"),
            ("2", "2. 重新生成管理员恢复码"),
            ("3", "3. 开启 2FA"),
            ("4", "4. 关闭 2FA"),
            ("0", "0. 返回"),
        ),
        default_index=0,
    )
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
        _ui_ok("绑定完成；当前登录状态已刷新，请重新登录。")
        return
    if choice == "2":
        confirm_reg = _select_option(
            "确认重新生成管理员应急恢复码吗？（旧恢复码将失效，直接回车或输入 y / register 确认）[y]: ",
            (
                ("y", "1. 是，重新生成 (Yes)"),
                ("n", "2. 否，取消返回 (No)"),
            ),
            default_index=0,
        ).strip()
        if confirm_reg.lower() not in {"", "y", "yes", "1", "register"}:
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
    action_label = "开启" if enabled else "关闭"
    confirm_2fa = _select_option(
        f"{action_label} 2FA 将使已登录设备重新验证，确认继续吗？(直接回车确认，或输入 y / {confirmation}) [y]: ",
        (
            ("y", f"1. 是，确认{action_label} (Yes)"),
            ("n", "2. 否，取消返回 (No)"),
        ),
        default_index=0,
    ).strip()
    if confirm_2fa.lower() not in {"", "y", "yes", "1", confirmation}:
        print("已取消。")
        return
    require_configuration_access(root, store, handle, "security")
    store.set_mfa_required(handle, enabled)
    _ui_ok("2FA 策略已更新；当前登录状态已刷新，请重新登录。")


def run_config(instance: dict, store, handle: str) -> None:
    root = _instance_root(instance)
    operator = require_configuration_access(root, store, handle, "config")
    required, _, _ = store._policy()
    print(f"服务端 2FA: {'开启' if required else '关闭'}（仅开启时需要两步验证）。")
    _configure_network(root, store, handle, operator, allow_security=True)


def run_setup(instance: dict, store, handle: str) -> None:
    """Run the five-category setup using the caller's live management session."""
    from .agent_registration import AgentRegistration

    root = _instance_root(instance)
    while True:
        operator = require_configuration_access(root, store, handle, "setup")
        host, port, selected, _ = _network_settings(root)
        registered_ids = [
            entry["id"]
            for entry in AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries()
            if isinstance(entry, dict) and isinstance(entry.get("id"), str)
        ]
        connected_agents = list(dict.fromkeys([*selected, *registered_ids]))
        required, _, _ = store._policy()
        print("\n╭─── OAI Core 设置与管理面板 ──────────────────────────────╮")
        print(f"│  两步验证 (2FA): {'已开启' if required else '未开启'}（支持 ↑/↓ 方向键切换选项，回车确认）")
        print("╰──────────────────────────────────────────────────────────╯")
        choice = _select_option(
            "请选择（↑/↓ 切换，回车确认）: ",
            (
                ("1", f"1. 网络设置 [{host}:{port}]"),
                ("2", "2. 账号管理"),
                ("3", f"3. Agent 接入 [{', '.join(connected_agents) or '无'}]"),
                ("4", "4. 服务管理"),
                ("2fa", f"2fa. 两步验证 (2FA) [{'已开启' if required else '未开启'}]"),
                ("5", "5. 退出"),
            ),
            default_index=0,
        )
        if choice in {"5", "0", "q", "Q"}:
            return
        try:
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
                _ui_warn("无效选择，请输入菜单编号（1-5 或 2fa）。")
        except ValueError as exc:
            _ui_warn(f"[提示] {exc}")


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
    _ui_ok("[INFO] 配置已安全保存生效；原有账号、密码和设备配对保持不变。")
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
    local_skill = root / "integrations" / "skills" / "oai-client"
    try:
        from oai_core.skills_installer import get_agent_skill_destinations, get_skill_source_dir
        source = local_skill if (local_skill / "SKILL.md").is_file() else get_skill_source_dir()
        destinations = get_agent_skill_destinations()
    except Exception:
        source = local_skill
        destinations = [Path.home() / ".agents" / "skills"]
    if not (source / "SKILL.md").is_file():
        _ui_warn("[WARN] 配置已保存，但未找到可选的 oai-client 技能文件，可稍后手动安装。")
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
            _ui_ok(f"[INFO] 已同步 oai-client 技能到: {target}")
        except Exception as exc:
            complete = False
            _ui_warn(f"[WARN] 可选技能安装未完成（{type(exc).__name__}）: {target}")
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


def update_environment_text(content: str, values: dict[str, str], *, unset: set[str] | None = None) -> str:
    output, seen = [], set()
    removed = unset or set()
    for line in content.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("export "):
            stripped = stripped[7:].lstrip()
        key_part = stripped.split("=", 1)[0].rstrip() if "=" in stripped else None
        if key_part in removed:
            continue
        matched = key_part if key_part in values else None
        if matched is None:
            output.append(line)
        elif matched not in seen:
            output.append(f"{matched}={values[matched]}")
            seen.add(matched)
    output.extend(f"{key}={value}" for key, value in values.items() if key not in seen)
    return "\n".join(output) + "\n"


def dotenv_scalar(value: str) -> str:
    return value if re.fullmatch(r"[A-Za-z0-9_./:@+-]+", value) else json.dumps(value, ensure_ascii=False)


def sync_dsh_adapter_env(token: str, *, transaction: ConfigurationTransaction | None = None,
                         homes: list[Path] | None = None) -> None:
    candidates = list(homes) if homes is not None else []
    if not candidates:
        try:
            candidates.extend(detect_host_agents().get("dsh", []))
        except Exception:
            pass
    if not candidates:
        candidates = [Path.home() / ".dsh", Path.home() / ".dsh-cline"]
        if os.environ.get("DSH_HOME"):
            candidates.insert(0, Path(os.environ["DSH_HOME"]))
    seen = set()
    for home in candidates:
        try:
            resolved = home.resolve()
        except OSError:
            continue
        if not home.is_dir() or resolved in seen:
            continue
        seen.add(resolved)
        owner_uid = home.stat().st_uid if os.geteuid() == 0 else os.geteuid()
        owner_gid = home.stat().st_gid if os.geteuid() == 0 else os.getegid()
        env_file = home / ".env"
        content = read_configuration_text(env_file, transaction)
        new_env = update_environment_text(content, {"OAI_DSH_ADAPTER_TOKEN": dotenv_scalar(token)})
        if transaction is not None:
            write_configuration_text(env_file, new_env, transaction)
        else:
            _write_host_text(env_file, new_env, owner_uid, owner_gid, 0o600)
        update_dsh_credentials_yaml(home / ".credentials.yaml", "OAI_DSH_ADAPTER_TOKEN", token,
                                    transaction=transaction, owner_uid=owner_uid, owner_gid=owner_gid)


def update_dsh_credentials_yaml(path: Path, key: str, value: str, *,
                                transaction: ConfigurationTransaction | None = None,
                                owner_uid: int | None = None, owner_gid: int | None = None) -> None:
    content = read_configuration_text(path, transaction)
    if not content.strip():
        new_content = f"version: 1\nrefs:\n  {key}: {dotenv_scalar(value)}\n"
        if transaction is not None:
            write_configuration_text(path, new_content, transaction)
        else:
            _write_host_text(path, new_content, owner_uid or os.geteuid(), owner_gid or os.getegid(), 0o600)
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
    if transaction is not None:
        write_configuration_text(path, text, transaction)
    else:
        _write_host_text(path, text, owner_uid or os.geteuid(), owner_gid or os.getegid(), 0o600)



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
                choice = _select_option(
                    "二次验证方式（↑/↓ 选择或输入 1 验证器，2 恢复码）: ",
                    (
                        ("1", "1. 手机验证器动态码 (TOTP)"),
                        ("2", "2. 应急恢复码 (Recovery Code)"),
                    ),
                    default_index=0,
                )
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
            print(f"[ERROR] 配置保存失败（{exc.code}）；更新已有的 DSH 配置文件需要安装 PyYAML，您原有的配置完好未动。"
                  f"请在当前 Python 环境运行以下命令后重试：{shlex.quote(sys.executable)} -m pip install PyYAML==6.0.3", file=sys.stderr)
        else:
            print(f"[ERROR] 配置保存失败（{type(exc).__name__}）；原有配置已保留且未受损。请检查文件权限、输入内容或查看恢复记录 runtime/configuration-journal.json。", file=sys.stderr)
        raise SystemExit(1) from None
    if not complete:
        print("[PARTIAL] 核心配置已保存生效，但可选的 AI 助手技能（skill）未全部同步完成。")
        raise SystemExit(2)


if __name__ == "__main__":
    main()
