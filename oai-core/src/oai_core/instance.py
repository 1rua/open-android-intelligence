"""Discover explicit, registered or standard local Core installations.

The installation registry holds only relative references and is never searched
outside the current user's supported locations.  It does not contain keys.
"""
from __future__ import annotations

import fcntl
import ipaddress
import json
import os
from pathlib import Path
import secrets
import stat
from urllib.parse import urlsplit

from .gateway.core import GatewayError


def _registry_path() -> Path:
    return Path.home() / ".config" / "oai" / "instances.json"


def _ensure_private_directory(path: Path) -> None:
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid():
        raise GatewayError("INSTANCE_REGISTRY_INVALID")
    if info.st_mode & 0o077:
        try:
            path.chmod(0o700)
            info = path.lstat()
        except OSError as exc:
            raise GatewayError("INSTANCE_REGISTRY_INVALID") from exc
        if info.st_mode & 0o077:
            raise GatewayError("INSTANCE_REGISTRY_INVALID")


def _private(path: Path, error_code: str = "INSTANCE_REGISTRY_INVALID") -> None:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid():
        raise GatewayError(error_code)
    if info.st_mode & 0o077:
        try:
            path.chmod(0o600)
            info = path.lstat()
        except OSError as exc:
            raise GatewayError(error_code) from exc
        if info.st_mode & 0o077:
            raise GatewayError(error_code)


def _load_registry() -> list[Path]:
    registry = _registry_path()
    if not registry.exists() and not registry.is_symlink():
        return []
    try:
        if registry.parent.exists():
            _ensure_private_directory(registry.parent)
        _private(registry)
        value = json.loads(registry.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or value.get("version") != 1 or not isinstance(value.get("instances"), list):
            raise ValueError()
        result = []
        for item in value["instances"]:
            if not isinstance(item, str) or not item or Path(item).is_absolute():
                raise ValueError()
            result.append((registry.parent / item).resolve())
        return list(dict.fromkeys(result))
    except (OSError, ValueError, TypeError) as exc:
        raise GatewayError("INSTANCE_REGISTRY_INVALID") from exc


def _url(config: dict) -> str:
    explicit = config.get("url")
    if explicit is not None:
        if not isinstance(explicit, str):
            raise GatewayError("INSTANCE_CONFIG_INVALID")
        parsed = urlsplit(explicit)
        try:
            invalid = (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username is not None
                       or parsed.password is not None or parsed.path not in {"", "/"} or parsed.query or parsed.fragment
                       or (parsed.port is not None and not 1 <= parsed.port <= 65535))
        except ValueError as exc:
            raise GatewayError("INSTANCE_CONFIG_INVALID") from exc
        if invalid:
            raise GatewayError("INSTANCE_CONFIG_INVALID")
        return explicit.rstrip("/")
    listener = config.get("listen", {})
    if not isinstance(listener, dict):
        raise GatewayError("INSTANCE_CONFIG_INVALID")
    host, port = listener.get("host", "0.0.0.0"), listener.get("port", 11451)
    if not isinstance(host, str) or not host or type(port) is not int or not 1 <= port <= 65535:
        raise GatewayError("INSTANCE_CONFIG_INVALID")
    if host in {"0.0.0.0", "::"}:
        host = "127.0.0.1"
    elif ":" in host:
        try:
            ipaddress.IPv6Address(host)
        except ValueError as exc:
            raise GatewayError("INSTANCE_CONFIG_INVALID") from exc
        host = f"[{host}]"
    elif any(c in host for c in "/@?# "):
        raise GatewayError("INSTANCE_CONFIG_INVALID")
    tls = config.get("tls", {})
    if not isinstance(tls, dict):
        raise GatewayError("INSTANCE_CONFIG_INVALID")
    return f"{'https' if tls.get('certFile') and tls.get('keyFile') else 'http'}://{host}:{port}"


def _from_config(path: Path) -> dict:
    path = path.expanduser().absolute()
    if path.is_symlink():
        raise GatewayError("INSTANCE_CONFIG_INVALID")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or not isinstance(value.get("storageRoot"), str) or not value["storageRoot"]:
            raise ValueError()
        storage = (path.parent / value["storageRoot"]).resolve()
        identity = value.get("identityDatabase")
        if identity is not None and (not isinstance(identity, str) or not identity):
            raise ValueError()
        identity_path = (path.parent / identity).resolve() if identity else storage.parent / "security" / "identity.sqlite"
        instance = {"configPath": str(path.resolve()), "url": _url(value), "storageRoot": str(storage),
                    "identityDatabase": str(identity_path)}
        master = value.get("masterKeyFile")
        if master is not None:
            if not isinstance(master, str) or not master:
                raise ValueError()
            instance["masterKeyFile"] = str((path.parent / master).resolve())
        return instance
    except (OSError, ValueError, TypeError) as exc:
        raise GatewayError("INSTANCE_CONFIG_INVALID") from exc


def discover_instance(config: Path | None = None) -> dict:
    if config is not None:
        return _from_config(Path(config))
    registered = _load_registry()
    if len(registered) > 1:
        raise GatewayError("INSTANCE_REQUIRED", {"configPaths": [str(path) for path in registered]})
    if registered:
        return _from_config(registered[0])
    client = Path.home() / ".config" / "oai" / "client.json"
    if client.exists() or client.is_symlink():
        try:
            _private(client, error_code="INSTANCE_CONFIG_INVALID")
            saved = json.loads(client.read_text(encoding="utf-8"))
            if not isinstance(saved, dict):
                raise ValueError()
            if saved.get("loggedOut") is not True and isinstance(saved.get("url"), str):
                return {"url": _url({"url": saved["url"]})}
        except (OSError, ValueError, TypeError) as exc:
            raise GatewayError("INSTANCE_CONFIG_INVALID") from exc
    standard = Path.home() / ".oai-core" / "core.json"
    if standard.exists() or standard.is_symlink():
        return _from_config(standard)
    raise GatewayError("INSTANCE_NOT_FOUND")


def register_instance(config: Path) -> dict:
    """Record a verified explicit local configuration, preserving other entries."""
    instance = _from_config(Path(config))
    path = Path(instance["configPath"])
    registry = _registry_path()
    _ensure_private_directory(registry.parent)
    lock = os.open(registry.parent / "instances.lock", os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        info = os.fstat(lock)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid():
            raise GatewayError("INSTANCE_REGISTRY_INVALID")
        if info.st_mode & 0o077:
            try:
                os.fchmod(lock, 0o600)
                info = os.fstat(lock)
            except OSError as exc:
                raise GatewayError("INSTANCE_REGISTRY_INVALID") from exc
            if info.st_mode & 0o077:
                raise GatewayError("INSTANCE_REGISTRY_INVALID")
        fcntl.flock(lock, fcntl.LOCK_EX)
        entries = _load_registry()
        if path not in entries:
            entries.append(path)
        data = {"version": 1, "instances": [os.path.relpath(entry, registry.parent) for entry in entries]}
        temporary = registry.parent / (".instances-" + secrets.token_hex(16))
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, registry)
        directory = os.open(registry.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    except OSError as exc:
        raise GatewayError("INSTANCE_REGISTRY_INVALID") from exc
    finally:
        os.close(lock)
    return instance
