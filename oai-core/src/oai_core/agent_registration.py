"""Registered Agent instances, account bindings and verified turn origins.

Discovery only reads manifests in the current user's authorised directories. A
manifest is a candidate, not proof: it becomes usable only after an operator
registers its exact contents and verifies a challenge with the bridge key.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import sqlite3
import stat
import time
from collections.abc import Callable, Mapping
from contextlib import closing
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from .gateway.core import GatewayError


def sign_core_request(private_key: Any, payload: Mapping[str, Any]) -> str:
    """Sign one request with the independently provisioned Core instance key."""
    return base64.urlsafe_b64encode(private_key.sign(b"oai-hermes-space-v1\0" + _json(payload))).decode("ascii")


def core_request_payload(core_id: str, hermes_id: str, account_id: str, native_id: str, method: str, path: str,
                         body: bytes, headers: Mapping[str, str], nonce: str, timestamp: int) -> dict[str, Any]:
    headers = {str(key).lower(): str(value) for key, value in headers.items()}
    return {"coreInstanceId": core_id, "hermesInstanceId": hermes_id, "accountId": account_id,
            "nativeId": native_id, "method": method, "path": path, "bodySha256": hashlib.sha256(body).hexdigest(),
            "nonce": nonce, "timestamp": timestamp,
            "deviceId": headers.get("x-oai-device", ""),
            "pairingGeneration": headers.get("x-oai-pairing-generation", ""),
            "grantRevision": headers.get("x-oai-grant-revision", ""),
            "originContext": headers.get("x-oai-origin-context", ""), "observer": headers.get("x-oai-observer", ""),
            "contentSha256": headers.get("x-oai-content-sha256", "")}


def verify_core_request(public_key: bytes, payload: Mapping[str, Any], signature: str) -> bool:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    try:
        raw = base64.b64decode(signature, altchars=b"-_", validate=True)
        Ed25519PublicKey.from_public_bytes(public_key).verify(raw, b"oai-hermes-space-v1\0" + _json(payload))
        return True
    except (ValueError, TypeError, InvalidSignature):
        return False

_ID = re.compile(r"[A-Za-z0-9._~-]{1,128}\Z")
_ENV = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")
_MANIFEST_FIELDS = frozenset({"id", "type", "name", "url", "instanceId", "sessionScope",
                              "secretFile", "tokenEnv", "prefix"})


class AgentHandshakeError(ValueError):
    """A sanitized, machine-readable failure while contacting an Agent bridge."""

    def __init__(self, code: str, message: str):
        self.code = code
        self.message = message
        super().__init__(message)


_HANDSHAKE_ERROR_MESSAGES = {
    "AGENT_BRIDGE_UPGRADE_REQUIRED": "AI 助手连接插件版本过旧（缺少握手验证接口），请升级连接插件。",
    "AGENT_BRIDGE_AUTHENTICATION_FAILED": "AI 助手连接密钥验证未通过，请检查 Core 与助手插件的密钥是否一致。",
    "AGENT_BRIDGE_UNAVAILABLE": "暂时连不上该 AI 助手服务，请确认助手已启动或按提示安装连接插件。",
    "AGENT_HANDSHAKE_FAILED": "与 AI 助手通信握手失败，返回的数据格式不受支持。",
}


def _handshake_error(code: str) -> AgentHandshakeError:
    return AgentHandshakeError(code, _HANDSHAKE_ERROR_MESSAGES[code])


def _identity(value: Any) -> str:
    if not isinstance(value, str) or _ID.fullmatch(value) is None:
        raise ValueError("Invalid Agent identity")
    return value


def _json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def _private_file(path: Path) -> int:
    """Open an owner-only regular file without following its final symlink."""
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise ValueError("Agent secret or database must be an owner-only regular file")
        return fd
    except BaseException:
        os.close(fd)
        raise


def read_bridge_secret(entry: Mapping[str, Any], base_dir: Path) -> str:
    """Read one configured secret source; legacy env and inline values remain compatible."""
    present = [key for key in ("secretFile", "secretFd", "tokenEnv", "token") if key in entry]
    if len(present) != 1:
        raise ValueError("Session bridge requires exactly one secret source")
    source = present[0]
    if source == "secretFile":
        raw_path = entry[source]
        if not isinstance(raw_path, str) or not raw_path:
            raise ValueError("Invalid session bridge secretFile")
        path = Path(raw_path).expanduser()
        if not path.is_absolute():
            path = base_dir / path
        fd = _private_file(path)
        try:
            raw = os.read(fd, 4097)
        finally:
            os.close(fd)
        if len(raw) > 4096:
            raise ValueError("Session bridge secret is too long")
        try:
            value = raw.decode("utf-8").removesuffix("\n")
        except UnicodeDecodeError:
            raise ValueError("Invalid session bridge secret") from None
    elif source == "secretFd":
        fd = entry[source]
        if type(fd) is not int or fd < 0:
            raise ValueError("Invalid session bridge secretFd")
        raw = os.read(fd, 4097)
        if len(raw) > 4096:
            raise ValueError("Session bridge secret is too long")
        try:
            value = raw.decode("utf-8").removesuffix("\n")
        except UnicodeDecodeError:
            raise ValueError("Invalid session bridge secret") from None
    elif source == "tokenEnv":
        name = entry[source]
        if not isinstance(name, str) or _ENV.fullmatch(name) is None:
            raise ValueError("Session bridge requires valid tokenEnv identifier")
        value = os.environ.get(name, "")
    else:
        value = entry[source]
    if not isinstance(value, str) or len(value) < 32 or len(value) > 4096 or any(c in value for c in "\r\n\x00"):
        raise ValueError("Session bridge secret is unavailable or invalid")
    return value


def create_bridge_secret(path: Path) -> None:
    """Explicit provisioning only. Never replace an existing bridge key."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        data = (secrets.token_urlsafe(48) + "\n").encode("ascii")
        while data:
            data = data[os.write(fd, data):]
        os.fsync(fd)
    finally:
        os.close(fd)


def bridge_proof(secret: str, challenge: str, instance_id: str, session_scope: str,
                 online: bool, capabilities: Mapping[str, bool]) -> str:
    """Sign a fresh challenge and the exact live capability declaration."""
    _identity(instance_id)
    if (not isinstance(secret, str) or len(secret) < 32
            or not isinstance(challenge, str) or re.fullmatch(r"[0-9a-f]{64}", challenge) is None
            or session_scope not in {"account", "deployment"} or type(online) is not bool
            or not isinstance(capabilities, Mapping)
            or any(not isinstance(k, str) or type(v) is not bool for k, v in capabilities.items())):
        raise ValueError("Invalid Agent handshake")
    payload = {"challenge": challenge, "instanceId": instance_id, "sessionScope": session_scope,
               "online": online, "capabilities": dict(capabilities)}
    return hmac.new(secret.encode("utf-8"), b"oai-agent-handshake-v1\0" + _json(payload), hashlib.sha256).hexdigest()


def verify_bridge_proof(secret: str, challenge: str, response: Mapping[str, Any], *,
                        instance_id: str, session_scope: str) -> bool:
    try:
        if (not isinstance(response, Mapping) or response.get("instanceId") != instance_id
                or response.get("sessionScope") != session_scope):
            return False
        expected = bridge_proof(secret, challenge, instance_id, session_scope,
                                response.get("online"), response.get("capabilities"))
        supplied = response.get("proof")
        return isinstance(supplied, str) and hmac.compare_digest(expected, supplied)
    except (ValueError, TypeError, AttributeError):
        return False


def _endpoint(entry: Mapping[str, Any]) -> tuple[str, str, int]:
    url = urlsplit(entry.get("url", ""))
    if (url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path not in {"", "/"}
            or url.scheme == "http" and url.hostname not in {"localhost", "127.0.0.1", "::1"}):
        raise ValueError("Agent bridge requires a secure HTTP origin")
    host = url.hostname.rstrip(".").encode("idna").decode("ascii").lower()
    try:
        address = ipaddress.ip_address(host)
        host = "loopback" if address.is_loopback else address.compressed
    except ValueError:
        if host == "localhost":
            host = "loopback"
    return url.scheme, host, url.port or (443 if url.scheme == "https" else 80)


def _manifest(entry: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(entry, Mapping) or set(entry) - _MANIFEST_FIELDS:
        raise ValueError("Invalid Agent manifest")
    result = dict(entry)
    for key in ("id", "type", "instanceId"):
        _identity(result.get(key))
    if result.get("sessionScope") not in {"account", "deployment"}:
        raise ValueError("Agent session scope must be explicit")
    if result["type"] == "hermes" and result["sessionScope"] != "account":
        raise ValueError("Hermes sessions must be account isolated")
    if sum(key in result for key in ("secretFile", "tokenEnv")) != 1:
        raise ValueError("Registered Agent requires a secret reference")
    for key in ("name", "url", "prefix", "secretFile", "tokenEnv"):
        if key in result and (not isinstance(result[key], str) or not result[key] or len(result[key]) > 4096):
            raise ValueError("Invalid Agent manifest")
    return result


class AgentRegistrationStore:
    """Agent records stored in the existing private identity database.

    The caller supplies the identity layer's management verifier. Initialising or
    changing records requires a live management handle; this class never treats a
    boolean, a bridge key or a local OS user as authorisation.
    """
    def __init__(self, database: Path, require_management: Callable[[str, str], Any] | None = None):
        self.database = Path(database)
        self.require_management = require_management

    def _connect(self) -> sqlite3.Connection:
        fd = _private_file(self.database)
        os.close(fd)
        db = sqlite3.connect(self.database.resolve().as_uri() + "?mode=rw", uri=True, timeout=5)
        db.row_factory = sqlite3.Row
        return db

    def _authorise(self, handle: str) -> None:
        if not callable(self.require_management):
            raise PermissionError("Agent management authorisation required")
        self.require_management(handle, "agents.manage")

    def initialize(self, handle: str) -> None:
        self._authorise(handle)
        self.create_schema()

    def create_schema(self) -> None:
        # Explicit schema migration only. The identity database itself must exist.
        with closing(self._connect()) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS agent_instances (agent_id TEXT PRIMARY KEY, instance_id TEXT NOT NULL, "
                       "kind TEXT NOT NULL, session_scope TEXT NOT NULL, key_digest TEXT NOT NULL, manifest TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS agent_account_links (instance_id TEXT NOT NULL, account_id TEXT NOT NULL, "
                       "native_id TEXT NOT NULL, PRIMARY KEY(instance_id,account_id), UNIQUE(instance_id,native_id))")
            db.execute("CREATE TABLE IF NOT EXISTS agent_auto_instances (instance_id TEXT PRIMARY KEY)")
            db.execute("CREATE TABLE IF NOT EXISTS agent_auto_links (instance_id TEXT NOT NULL, account_id TEXT NOT NULL, "
                       "PRIMARY KEY(instance_id,account_id))")

    def _register_instance(self, handle: str, entry: Mapping[str, Any], secret: str, challenge: str,
                           response: Mapping[str, Any]) -> None:
        self._authorise(handle)
        self._record_verified_instance(entry, secret, challenge, response)

    def _record_verified_instance(self, entry: Mapping[str, Any], secret: str, challenge: str,
                                  response: Mapping[str, Any]) -> None:
        manifest = _manifest(entry)
        if response.get("online") is not True or not verify_bridge_proof(
                secret, challenge, response, instance_id=manifest["instanceId"],
                session_scope=manifest["sessionScope"]):
            raise _handshake_error("AGENT_HANDSHAKE_FAILED")
        digest = hashlib.sha256(secret.encode("utf-8")).hexdigest()
        with closing(self._connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            old_id = db.execute("SELECT * FROM agent_instances WHERE agent_id=?", (manifest["id"],)).fetchone()
            aliases = db.execute("SELECT * FROM agent_instances WHERE instance_id=?", (manifest["instanceId"],)).fetchall()
            # One network endpoint is one host scope even if someone registers
            # different IDs or textual loopback aliases for it.
            for row in db.execute("SELECT instance_id,manifest FROM agent_instances"):
                existing = json.loads(row["manifest"])
                if _endpoint(existing) == _endpoint(manifest) and row["instance_id"] != manifest["instanceId"]:
                    raise ValueError("Agent endpoint belongs to a different registered instance")
            if old_id and (old_id["instance_id"] != manifest["instanceId"] or old_id["key_digest"] != digest
                           or old_id["kind"] != manifest["type"] or old_id["session_scope"] != manifest["sessionScope"]):
                raise ValueError("Agent identity or bridge key changed; explicit migration required")
            if any(row["key_digest"] != digest or row["kind"] != manifest["type"]
                   or row["session_scope"] != manifest["sessionScope"] for row in aliases):
                raise ValueError("Agent instance aliases disagree")
            db.execute("INSERT INTO agent_instances VALUES (?,?,?,?,?,?) "
                       "ON CONFLICT(agent_id) DO UPDATE SET manifest=excluded.manifest",
                       (manifest["id"], manifest["instanceId"], manifest["type"], manifest["sessionScope"],
                        digest, _json(manifest).decode("ascii")))
            if response.get("coreAccepted") is True:
                db.execute("INSERT OR IGNORE INTO agent_auto_instances VALUES (?)", (manifest["instanceId"],))

    def confirm_mapping(self, handle: str, agent_id: str, account_id: str, native_id: str) -> None:
        self.confirm_mappings(handle, agent_id, {account_id: native_id})

    def confirm_mappings(self, handle: str, agent_id: str, accounts: Mapping[str, str],
                         reserved_accounts: Mapping[str, str] | None = None) -> None:
        self._authorise(handle)
        self._record_mappings(agent_id, accounts, reserved_accounts)

    def _record_mappings(self, agent_id: str, accounts: Mapping[str, str],
                         reserved_accounts: Mapping[str, str] | None = None) -> None:
        _identity(agent_id)
        if not isinstance(accounts, Mapping) or not accounts:
            raise ValueError("Explicit account mappings required")
        for account_id, native_id in accounts.items():
            _identity(account_id), _identity(native_id)
        with closing(self._connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            instance = db.execute("SELECT instance_id FROM agent_instances WHERE agent_id=?", (agent_id,)).fetchone()
            if instance is None:
                raise ValueError("Agent instance is not registered")
            instance_id = instance["instance_id"]
            reserved = dict(reserved_accounts or {})
            owners = {native: owner for owner, native in reserved.items()}
            for account_id, native_id in accounts.items():
                if ((account_id in reserved and reserved[account_id] != native_id)
                        or (native_id in owners and owners[native_id] != account_id)):
                    raise ValueError("Agent account mapping conflicts with existing ownership")
                previous = db.execute("SELECT native_id FROM agent_account_links WHERE instance_id=? AND account_id=?",
                                      (instance_id, account_id)).fetchone()
                if previous and previous[0] != native_id:
                    raise ValueError("Agent account mapping conflicts with existing ownership")
                db.execute("INSERT OR IGNORE INTO agent_account_links VALUES (?,?,?)", (instance_id, account_id, native_id))
                row = db.execute("SELECT account_id FROM agent_account_links WHERE instance_id=? AND native_id=?",
                                 (instance_id, native_id)).fetchone()
                if row is None or row[0] != account_id:
                    raise ValueError("Agent account mapping conflicts with existing ownership")

    def automatic_instance(self, agent_id: str) -> str | None:
        with closing(self._connect()) as db:
            row = db.execute("SELECT i.instance_id FROM agent_instances i JOIN agent_auto_instances a "
                             "ON i.instance_id=a.instance_id WHERE i.agent_id=? AND i.kind='hermes'",
                             (agent_id,)).fetchone()
            return row[0] if row else None

    def automatic_binding(self, agent_id: str, account_id: str) -> bool:
        with closing(self._connect()) as db:
            return db.execute("SELECT 1 FROM agent_auto_links l JOIN agent_instances i ON i.instance_id=l.instance_id "
                              "WHERE i.agent_id=? AND l.account_id=?", (agent_id, account_id)).fetchone() is not None

    def save_automatic_binding(self, agent_id: str, account_id: str, native_id: str,
                               reserved_accounts: Mapping[str, str] | None = None) -> None:
        _identity(account_id)
        _identity(native_id)
        reserved = dict(reserved_accounts or {})
        if ((account_id in reserved and reserved[account_id] != native_id)
                or any(owner != account_id and native == native_id for owner, native in reserved.items())):
            raise ValueError("Agent account mapping conflicts with existing ownership")
        with closing(self._connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            instance = db.execute("SELECT i.instance_id FROM agent_instances i JOIN agent_auto_instances a "
                                  "ON i.instance_id=a.instance_id WHERE i.agent_id=? AND i.kind='hermes'", (agent_id,)).fetchone()
            if instance is None:
                raise ValueError("Automatic Hermes instance not registered")
            instance_id = instance[0]
            existing = db.execute("SELECT native_id FROM agent_account_links WHERE instance_id=? AND account_id=?",
                                  (instance_id, account_id)).fetchone()
            if existing and existing[0] != native_id:
                raise ValueError("Agent account mapping conflicts with existing ownership")
            db.execute("INSERT OR IGNORE INTO agent_account_links VALUES (?,?,?)", (instance_id, account_id, native_id))
            owner = db.execute("SELECT account_id FROM agent_account_links WHERE instance_id=? AND native_id=?",
                               (instance_id, native_id)).fetchone()
            if owner is None or owner[0] != account_id:
                raise ValueError("Agent account mapping conflicts with existing ownership")
            if existing is None:
                db.execute("INSERT INTO agent_auto_links VALUES (?,?)", (instance_id, account_id))

    def mappings(self, agent_id: str) -> dict[str, str]:
        _identity(agent_id)
        with closing(self._connect()) as db, db:
            if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='agent_instances'").fetchone() is None:
                return {}
            rows = db.execute("SELECT l.account_id,l.native_id FROM agent_account_links l "
                              "JOIN agent_instances i ON i.instance_id=l.instance_id WHERE i.agent_id=?", (agent_id,))
            return {row[0]: row[1] for row in rows}

    def entries(self) -> list[dict[str, Any]]:
        with closing(self._connect()) as db, db:
            if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='agent_instances'").fetchone() is None:
                return []
            return [json.loads(row[0]) for row in db.execute("SELECT manifest FROM agent_instances ORDER BY agent_id")]

    def registered(self, entry: Mapping[str, Any], secret: str) -> bool:
        try:
            manifest = _manifest(entry)
            with closing(self._connect()) as db, db:
                row = db.execute("SELECT manifest,key_digest FROM agent_instances WHERE agent_id=?", (manifest["id"],)).fetchone()
            return bool(row and hmac.compare_digest(row[0], _json(manifest).decode("ascii"))
                        and hmac.compare_digest(row[1], hashlib.sha256(secret.encode("utf-8")).hexdigest()))
        except (ValueError, sqlite3.Error, OSError):
            return False


def discover_candidates(roots: list[Path]) -> list[tuple[Path, dict[str, Any]]]:
    """Read immediate JSON manifests in explicitly selected same-user directories.

    Files and directories writable by another OS user, symlinks and other file
    types are ignored. No executables are loaded and no other HOME is scanned.
    """
    found: list[tuple[Path, dict[str, Any]]] = []
    for root in roots:
        root = Path(root).expanduser()
        try:
            info = root.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o022:
                continue
            for path in sorted(root.glob("*.json")):
                fd = None
                try:
                    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
                    info = os.fstat(fd)
                    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                            or info.st_mode & 0o022 or info.st_size > 65536):
                        continue
                    data = os.read(fd, 65537)
                    if len(data) > 65536:
                        continue
                    found.append((path, _manifest(json.loads(data))))
                except (OSError, ValueError, UnicodeDecodeError, TypeError):
                    continue
                finally:
                    if fd is not None:
                        os.close(fd)
        except OSError:
            continue
    return found


class OriginContextAuthority:
    """Short-lived in-process proofs for an already authenticated device turn.

    Only trusted Core code may issue a proof. The account and live revocation
    check are always required; callers may additionally check the bound Agent,
    session and turn. Tokens stop working on restart and are not credentials.
    """
    def __init__(self, instance_id: str, is_active: Callable[[Mapping[str, Any]], bool]):
        self.instance_id = _identity(instance_id)
        if not callable(is_active):
            raise ValueError("Origin revocation check required")
        self._is_active = is_active
        self._key = secrets.token_bytes(32)

    def issue(self, *, account_id: str, agent_id: str, session_id: str, turn_id: str,
              device_id: str, pairing_generation: int, grant_revision: int, ttl: int = 120,
              device_session_id: str | None = None) -> str:
        for value in (account_id, agent_id, session_id, turn_id, device_id):
            _identity(value)
        if device_session_id is not None:
            _identity(device_session_id)
        if (type(pairing_generation) is not int or pairing_generation < 1
                or type(grant_revision) is not int or grant_revision < 1
                or type(ttl) is not int or not 1 <= ttl <= 300):
            raise ValueError("Invalid origin context")
        payload = {"instanceId": self.instance_id, "accountId": account_id, "agentId": agent_id,
                   "sessionId": session_id, "turnId": turn_id, "deviceId": device_id,
                   "pairingGeneration": pairing_generation, "grantRevision": grant_revision,
                   "expires": time.monotonic() + ttl}
        if device_session_id is not None:
            payload["deviceSessionId"] = device_session_id
        raw = _json(payload)
        signature = hmac.new(self._key, b"oai-origin-v1\0" + raw, hashlib.sha256).digest()
        return base64.urlsafe_b64encode(raw + signature).decode("ascii").rstrip("=")

    def verify(self, token: str, *, account_id: str, agent_id: str | None = None, session_id: str | None = None,
               turn_id: str | None = None) -> dict[str, Any]:
        try:
            if not isinstance(token, str) or len(token) > 4096:
                raise ValueError()
            raw = base64.b64decode(token + "=" * (-len(token) % 4), altchars=b"-_", validate=True)
            if base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=") != token:
                raise ValueError()
            payload_raw, signature = raw[:-32], raw[-32:]
            expected = hmac.new(self._key, b"oai-origin-v1\0" + payload_raw, hashlib.sha256).digest()
            if not hmac.compare_digest(signature, expected):
                raise ValueError()
            payload = json.loads(payload_raw)
            if (not isinstance(payload, dict) or payload.get("instanceId") != self.instance_id
                    or payload.get("accountId") != account_id
                    or agent_id is not None and payload.get("agentId") != agent_id
                    or session_id is not None and payload.get("sessionId") != session_id
                    or turn_id is not None and payload.get("turnId") != turn_id
                    or type(payload.get("expires")) not in (float, int) or payload["expires"] <= time.monotonic()
                    or self._is_active(payload) is not True):
                raise ValueError()
            return payload
        except (ValueError, TypeError, UnicodeError, json.JSONDecodeError):
            raise ValueError("Invalid or revoked origin context") from None


class AgentRegistration:
    """Explicit registration and short-lived, Core-issued turn origins.

    Callbacks must check the current authenticated principal and current pairing,
    grant and session state. A bridge secret does not authorise either operation.
    """

    def __init__(self, instance_id: str, identity_store: Any,
                 validate_principal: Callable[[Any], bool],
                 validate_origin: Callable[[Mapping[str, Any]], bool], *, create: bool = False,
                 core_signing_key: Any = None, validate_account_principal: Callable[[Any], bool] | None = None):
        if not callable(validate_principal) or not callable(validate_origin):
            raise ValueError("Principal and origin validators are required")
        database = getattr(identity_store, "database_path", None)
        require_management = getattr(identity_store, "require_management", None)
        if database is None or not callable(require_management):
            raise ValueError("An existing identity store and management verifier are required")
        self.identity_store = identity_store
        self.instance_id = _identity(instance_id)
        self.core_signing_key = core_signing_key
        self.validate_account_principal = validate_account_principal or validate_principal
        self._legacy_mappings: dict[str, dict[str, str]] = {}
        self.store = AgentRegistrationStore(Path(database), require_management)
        if create:
            self.store.create_schema()
        self.validate_principal = validate_principal
        self.origins = OriginContextAuthority(instance_id, validate_origin)

    def initialize(self, handle: str) -> None:
        self.store.initialize(handle)

    def discover(self, handle: str, roots: list[Path]) -> list[tuple[Path, dict[str, Any]]]:
        self.store._authorise(handle)
        return discover_candidates(roots)

    async def _handshake(self, entry: Mapping[str, Any], base_dir: Path, *,
                         timeout_seconds: float = 5.0) -> tuple[dict[str, Any], str, str, Mapping[str, Any]]:
        manifest = _manifest(entry)
        _endpoint(manifest)
        prefix = manifest.get("prefix", "/oai/session-adapter/v1")
        if not re.fullmatch(r"/(?:[A-Za-z0-9._~-]+/)*[A-Za-z0-9._~-]+", prefix):
            raise ValueError("Invalid Agent bridge prefix")
        secret = read_bridge_secret(manifest, Path(base_dir))
        challenge = secrets.token_hex(32)
        body = {"challenge": challenge}
        if self.core_signing_key is not None and manifest["type"] == "hermes":
            payload = {"coreInstanceId": self.instance_id, "hermesInstanceId": manifest["instanceId"],
                       "challenge": challenge}
            body.update(coreInstanceId=self.instance_id, coreSignature=sign_core_request(self.core_signing_key, payload))
        import aiohttp
        try:
            timeout = aiohttp.ClientTimeout(total=timeout_seconds)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.post(manifest["url"].rstrip("/") + prefix + "/handshake",
                                        json=body, headers={"Authorization": "Bearer " + secret},
                                        allow_redirects=False) as response:
                    if response.status == 404:
                        raise _handshake_error("AGENT_BRIDGE_UPGRADE_REQUIRED")
                    if response.status in {401, 403}:
                        raise _handshake_error("AGENT_BRIDGE_AUTHENTICATION_FAILED")
                    if 500 <= response.status <= 599:
                        raise _handshake_error("AGENT_BRIDGE_UNAVAILABLE")
                    if response.status != 200:
                        raise _handshake_error("AGENT_HANDSHAKE_FAILED")
                    if response.content_length is not None and response.content_length > 65536:
                        raise _handshake_error("AGENT_HANDSHAKE_FAILED")
                    data = bytearray()
                    async for chunk in response.content.iter_chunked(16384):
                        data.extend(chunk)
                        if len(data) > 65536:
                            raise _handshake_error("AGENT_HANDSHAKE_FAILED")
                    result = json.loads(data)
        except AgentHandshakeError:
            raise
        except (aiohttp.ClientError, TimeoutError):
            raise _handshake_error("AGENT_BRIDGE_UNAVAILABLE") from None
        except (UnicodeDecodeError, json.JSONDecodeError, RecursionError):
            raise _handshake_error("AGENT_HANDSHAKE_FAILED") from None
        if not isinstance(result, Mapping):
            raise _handshake_error("AGENT_HANDSHAKE_FAILED")
        if "coreSignature" in body and result.get("coreAccepted") is not True:
            raise _handshake_error("AGENT_HANDSHAKE_FAILED")
        if result.get("coreAccepted") is True:
            payload = {"coreInstanceId": self.instance_id, "hermesInstanceId": manifest["instanceId"], "challenge": challenge}
            expected = hmac.new(secret.encode(), b"oai-hermes-core-accepted-v1\0" + _json(payload), hashlib.sha256).hexdigest()
            if "coreSignature" not in body or not hmac.compare_digest(str(result.get("coreProof", "")), expected):
                raise _handshake_error("AGENT_HANDSHAKE_FAILED")
        return manifest, secret, challenge, result

    async def register(self, handle: str, entry: Mapping[str, Any], base_dir: Path) -> None:
        """Challenge the configured live bridge; never accept a caller-supplied proof."""
        self.store._authorise(handle)
        manifest, secret, challenge, result = await self._handshake(entry, base_dir)
        # Authorization may have expired while waiting for the bridge.
        self.store._register_instance(handle, manifest, secret, challenge, result)

    async def register_host_candidate(self, entry: Mapping[str, Any], base_dir: Path, *,
                                      timeout_seconds: float = 2.0) -> None:
        """Register a verified same-host, same-UID candidate discovered by Core."""
        self.store.create_schema()
        manifest, secret, challenge, result = await self._handshake(entry, base_dir, timeout_seconds=timeout_seconds)
        self.store._record_verified_instance(manifest, secret, challenge, result)

    def bind_initial_owner_mapping(self, agent_id: str, account_id: str, native_id: str) -> None:
        """Bind the single local owner's pre-existing native Hermes account if unmapped."""
        self.identity_store.account(account_id)
        self.store._record_mappings(agent_id, {account_id: native_id}, self._legacy_mappings.get(agent_id))

    def confirm_mapping(self, handle: str, agent_id: str, account_id: str, native_id: str) -> None:
        # Ensure the account is active; same-name matching is never evidence.
        self.store._authorise(handle)
        self.identity_store.account(account_id)
        self.store.confirm_mappings(handle, agent_id, {account_id: native_id}, self._legacy_mappings.get(agent_id))

    def import_mappings(self, handle: str, agent_id: str, accounts: Mapping[str, str]) -> None:
        """Import explicit legacy mappings after an operator confirms each owner."""
        if not isinstance(accounts, Mapping) or not accounts:
            raise ValueError("Explicit legacy account mappings required")
        self.store._authorise(handle)
        for account_id in accounts:
            self.identity_store.account(account_id)
        self.store.confirm_mappings(handle, agent_id, accounts, self._legacy_mappings.get(agent_id))

    def reserve_legacy_mappings(self, agent_id: str, mappings: Mapping[str, str]) -> None:
        combined = self._legacy_mappings.setdefault(agent_id, {})
        for owner, native in mappings.items():
            if ((owner in combined and combined[owner] != native)
                    or any(other != owner and value == native for other, value in combined.items())):
                raise ValueError("Hermes account mappings must be one-to-one across Agent aliases")
            combined[owner] = native
        registered = self.store.mappings(agent_id)
        for owner, native in registered.items():
            if ((owner in combined and combined[owner] != native)
                    or any(other != owner and value == native for other, value in combined.items())):
                raise ValueError("Hermes account mappings must be one-to-one across Agent aliases")

    def authenticated_account(self, principal: Any) -> str:
        if self.validate_account_principal(principal) is not True:
            raise GatewayError("AUTHENTICATION_REQUIRED")
        account_id = _identity(getattr(principal, "account_id", None))
        self.identity_store.account(account_id)
        return account_id

    async def resolve_hermes_binding(self, principal: Any, agent_id: str, adapter: Any) -> tuple[str, bool]:
        account_id = self.authenticated_account(principal)
        existing = self.store.mappings(agent_id).get(account_id)
        if existing is not None:
            return existing, self.store.automatic_binding(agent_id, account_id)
        legacy = self._legacy_mappings.get(agent_id, {}).get(account_id)
        if legacy is not None:
            return legacy, False
        instance_id = self.store.automatic_instance(agent_id)
        if instance_id is None or self.core_signing_key is None:
            raise GatewayError("AGENT_ACCOUNT_NOT_CONNECTED")
        payload = {"coreInstanceId": self.instance_id, "hermesInstanceId": instance_id,
                   "accountId": account_id, "nonce": secrets.token_hex(32)}
        response = await adapter._request("POST", "/auto-spaces", {
            **payload, "signature": sign_core_request(self.core_signing_key, payload)})
        native_id = _identity(response.get("nativeId"))
        # Revalidate after network work before attaching the returned space.
        if self.authenticated_account(principal) != account_id:
            raise GatewayError("AUTHENTICATION_REQUIRED")
        self.store.save_automatic_binding(agent_id, account_id, native_id, self._legacy_mappings.get(agent_id))
        return self.store.mappings(agent_id)[account_id], self.store.automatic_binding(agent_id, account_id)

    def automatic_headers(self, agent_id: str, account_id: str, native_id: str, method: str, path: str,
                          body: Any, headers: Mapping[str, str]) -> dict[str, str]:
        from .sessions import session_request_json
        instance_id = self.store.automatic_instance(agent_id)
        if instance_id is None or self.core_signing_key is None:
            raise GatewayError("AGENT_ACCOUNT_NOT_CONNECTED")
        nonce, timestamp = secrets.token_hex(32), int(time.time())
        raw = session_request_json(body).encode("utf-8") if body is not None else b""
        payload = core_request_payload(self.instance_id, instance_id, account_id, native_id, method, path,
                                       raw, headers, nonce, timestamp)
        return {"X-OAI-Core-Instance": self.instance_id, "X-OAI-Core-Account": account_id,
                "X-OAI-Core-Nonce": nonce, "X-OAI-Core-Time": str(timestamp),
                "X-OAI-Core-Proof": sign_core_request(self.core_signing_key, payload)}

    def mappings(self, agent_id: str) -> dict[str, str]:
        return self.store.mappings(agent_id)

    def entries(self) -> list[dict[str, Any]]:
        return self.store.entries()

    def registered(self, entry: Mapping[str, Any], secret: str) -> bool:
        return self.store.registered(entry, secret)

    def issue_origin(self, principal: Any, *, agent_id: str, session_id: str, turn_id: str,
                     ttl: int = 120) -> str:
        context = getattr(principal, "context", None)
        if context is None:
            raise GatewayError("ORIGIN_CONTEXT_INVALID")
        try:
            if (self.validate_principal(principal) is not True
                    or getattr(context, "account_id", None) != getattr(principal, "account_id", None)):
                raise ValueError("Invalid principal")
            token = self.origins.issue(account_id=principal.account_id, agent_id=agent_id, session_id=session_id,
                turn_id=turn_id, device_id=context.device_id, pairing_generation=context.pairing_generation,
                grant_revision=context.grant_revision, device_session_id=context.session_id, ttl=ttl)
            # Refuse to issue an already-revoked pairing or grant.
            self.origins.verify(token, account_id=principal.account_id, agent_id=agent_id,
                                session_id=session_id, turn_id=turn_id)
            return token
        except Exception:
            raise GatewayError("ORIGIN_CONTEXT_INVALID") from None

    def verify_origin(self, token: str, account_id: str) -> dict[str, Any]:
        try:
            return self.origins.verify(token, account_id=account_id)
        except Exception:
            raise GatewayError("ORIGIN_CONTEXT_INVALID") from None
