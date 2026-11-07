"""Local master key source for the Gateway (ADR 0023, ADR 0048, ADR 0061).

The accepted design prefers a host Secret Store and, when the host has none,
lets the deployment operator supply a permission-restricted dedicated key file.
Hermes exposes no Secret Store to plugins, so this file is the supported
fallback: it is never written into ordinary configuration, logs, backups or
diagnostics, and it never lives next to the database it protects.

A missing key file is not an operator error: the first start provisions it
automatically. Only an *existing but unsafe* source (symlink, foreign owner,
group/other permission bits, invalid length) stops the Gateway, because
deriving from it would either leak key material or silently break existing
records. When no writable location exists at all, an in-process volatile key
keeps the Gateway loadable instead of deadlocking plugin startup.

Every account gets its own derived key, so one deployment can host many
logically isolated Gateways without sharing key material (ADR 0030).
"""

from __future__ import annotations

import base64
from contextlib import closing
import hashlib
import logging
import os
import sqlite3
import stat
from pathlib import Path
from typing import Any, Mapping

# Primary name follows the plugin's OPEN_ANDROID_INTELLIGENCE_* convention; the
# shorter alias keeps parity with OPEN_ANDROID_GATEWAY_HOST/PORT.
MASTER_KEY_FILE_ENV = "OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE"
MASTER_KEY_FILE_ENV_ALIAS = "OPEN_ANDROID_GATEWAY_MASTER_KEY_FILE"

KEY_BYTES = 32
NONCE_BYTES = 12
ALGORITHM = "AES-256-GCM"
REFERENCE_PREFIX = "local-key-v1:"

logger = logging.getLogger(__name__)


class MasterKeyUnavailable(RuntimeError):
    """The configured key source is missing, unsafe, or unusable."""


DEFAULT_KEY_DIRECTORY_NAME = ".open-android-intelligence"
DEFAULT_KEY_FILE_NAME = "gateway-master-key"
DEFAULT_SESSION_KEY_FILE_NAME = "session-master-key"


def default_master_key_path() -> Path:
    """Deliberately outside the Hermes home: the key never travels with the database."""
    return Path.home() / DEFAULT_KEY_DIRECTORY_NAME / DEFAULT_KEY_FILE_NAME


def default_session_key_path() -> Path:
    """A separate key for a pure session host (Hermes/Dsh), away from both homes.

    A session host only bridges an agent's native conversations, so it must be
    able to prepare its own key without borrowing the device Core's key file.
    Keeping it outside the host's home means backups of that home never carry
    the material that protects it.
    """
    return Path.home() / DEFAULT_KEY_DIRECTORY_NAME / DEFAULT_SESSION_KEY_FILE_NAME


def _write_new_key(target: Path) -> Path:
    directory = target.parent
    created_directory = not directory.exists()
    directory.mkdir(parents=True, exist_ok=True)
    if created_directory:
        # Tighten only the directory this call created: an operator-supplied
        # existing directory may hold unrelated files, and the key's own 0600
        # mode is what actually protects the material.
        try:
            os.chmod(directory, 0o700)
        except OSError:
            pass
    handle = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(handle, os.urandom(KEY_BYTES))
        os.fsync(handle)
    finally:
        os.close(handle)
    os.chmod(target, 0o600)
    return target


def create_master_key_file(path: str | Path) -> Path:
    """Explicit one-shot provisioning kept for existing operators and tests.

    Refuses to overwrite an existing file so a mistyped command cannot destroy a
    key that still protects live data.
    """
    return _write_new_key(Path(path).expanduser())


def ensure_master_key_file(path: str | Path) -> Path:
    """The automatic path an installer or first start takes.

    Installing the plugin is the operator's act of providing the key source, so
    the Gateway provisions it here instead of asking for a manual step. An
    existing file is never rewritten; unsafe files still fail closed later.
    """
    target = Path(path).expanduser()
    if target.exists() or target.is_symlink():
        return target
    return _write_new_key(target)


def master_key_file(environ: Mapping[str, str] | None = None) -> Path | None:
    source = environ if environ is not None else os.environ
    raw = source.get(MASTER_KEY_FILE_ENV) or source.get(MASTER_KEY_FILE_ENV_ALIAS)
    if not isinstance(raw, str) or not raw.strip():
        return None
    return Path(raw.strip()).expanduser()


def _read_key_bytes(path: Path) -> bytes:
    try:
        info = path.lstat()
    except FileNotFoundError as exc:
        raise MasterKeyUnavailable(
            f"主密钥文件不存在：{path}。请确认该路径可写，或移除对应的环境变量以使用默认位置。"
        ) from exc
    if stat.S_ISLNK(info.st_mode):
        raise MasterKeyUnavailable(f"主密钥文件不能是符号链接：{path}")
    if not stat.S_ISREG(info.st_mode):
        raise MasterKeyUnavailable(f"主密钥文件必须是普通文件：{path}")
    if info.st_uid != os.getuid():
        raise MasterKeyUnavailable(f"主密钥文件必须属于当前用户：{path}")
    if info.st_mode & 0o077:
        raise MasterKeyUnavailable(
            f"主密钥文件权限过于宽松（{oct(info.st_mode & 0o777)}）：{path}，必须是 0600。"
        )
    raw = path.read_bytes()
    if len(raw) == KEY_BYTES:
        return raw
    text = raw.decode("utf-8", errors="strict").strip()
    if len(text) == KEY_BYTES * 2:
        try:
            decoded = bytes.fromhex(text)
        except ValueError as exc:
            raise MasterKeyUnavailable(f"主密钥文件既不是 32 字节原始密钥，也不是十六进制：{path}") from exc
        if len(decoded) == KEY_BYTES:
            return decoded
    raise MasterKeyUnavailable(
        f"主密钥内容长度不合法：{path}，需要 32 字节原始密钥或 64 位十六进制。"
    )


class LocalAeadProvider:
    """AES-256-GCM with the reference/algorithm shape the Core validates."""

    algorithm = ALGORITHM
    authenticated = True

    def __init__(self, reference: str, key: bytes) -> None:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM

        self.reference = reference
        self.key_reference = reference
        self._aead = AESGCM(key)

    def encrypt(self, plaintext: bytes, associated: bytes) -> bytes:
        nonce = os.urandom(NONCE_BYTES)
        return nonce + self._aead.encrypt(nonce, bytes(plaintext), bytes(associated))

    def decrypt(self, ciphertext: bytes, associated: bytes) -> bytes:
        raw = bytes(ciphertext)
        if len(raw) <= NONCE_BYTES:
            raise ValueError("MASTER_KEY_CIPHERTEXT_TOO_SHORT")
        return self._aead.decrypt(raw[:NONCE_BYTES], raw[NONCE_BYTES:], bytes(associated))


class _DerivedKeyStore:
    """Per-account derived keys from one 32-byte source.

    Available on both stores so callers do not branch on how the material was
    obtained. The reference string depends only on the account name, exactly as
    before, so an existing account record keeps its reference when a deployment
    moves from a provisioned file to the volatile fallback.
    """

    ephemeral = False

    def _master_bytes(self) -> bytes:  # pragma: no cover - implemented below
        raise NotImplementedError

    def validate(self) -> None:
        self._master_bytes()

    def _derive(self, name: str) -> bytes:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.kdf.hkdf import HKDF

        return HKDF(
            algorithm=hashes.SHA256(), length=KEY_BYTES, salt=None,
            info=str(name).encode("utf-8"),
        ).derive(self._master_bytes())

    def instance_signing_key(self, instance_id: str):
        """Derive a purpose-isolated stable Core instance key without another file."""
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        return Ed25519PrivateKey.from_private_bytes(self._derive("oai-core:instance-signing:v1:" + instance_id))

    def get_or_create_aead(self, name: str) -> LocalAeadProvider:
        # "get_or_create" is the host-facing name: this source never invents a
        # per-account key, it derives a stable one from the deployment source.
        reference = REFERENCE_PREFIX + hashlib.sha256(str(name).encode("utf-8")).hexdigest()[:16]
        return LocalAeadProvider(reference, self._derive(name))


class LocalMasterKeyStore(_DerivedKeyStore):
    """Per-account derived keys from one operator-provided key file."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path).expanduser()

    def _master_bytes(self) -> bytes:
        return _read_key_bytes(self.path)


class VolatileMasterKeyStore(_DerivedKeyStore):
    """In-memory key used when no writable key location exists.

    This is a deliberate degraded mode, not a silent replacement: the Gateway
    still starts and never claims a missing key source. Records written in this
    mode cannot be decrypted after the process exits, so callers log the
    warning returned by :func:`resolve_local_master_key_store` and the operator
    fixes the path permissions. Unsafe *existing* files are still refused
    outright instead of falling back here.
    """

    ephemeral = True

    def __init__(self) -> None:
        self._key = os.urandom(KEY_BYTES)

    def _master_bytes(self) -> bytes:
        return self._key


def _state_key_path(environ: Mapping[str, str] | None = None) -> Path:
    """A third, XDG-style location used only when the primary ones are read-only."""
    source = environ if environ is not None else os.environ
    base = source.get("XDG_STATE_HOME")
    root = Path(base).expanduser() if base else Path.home() / ".local" / "state"
    return root / "open-android-intelligence" / DEFAULT_KEY_FILE_NAME


def resolve_local_master_key_store(
    environ: Mapping[str, str] | None = None, *, fallback_path: str | Path | None = None,
) -> _DerivedKeyStore:
    """The key source used when the host exposes no Secret Store.

    Resolution order: the configured environment path; otherwise an existing
    key is reused wherever it already lives (the caller's dedicated
    ``fallback_path`` first, then the historical default), because switching
    sources would make existing records undecryptable. When no file exists yet,
    one is provisioned at the first writable candidate — the caller's
    ``fallback_path``, the historical default, then an XDG state directory.
    Only when none is writable does the store degrade to an in-process volatile
    key, so plugin startup is never blocked by key preparation.
    """
    configured = master_key_file(environ)
    if configured is not None:
        # An explicitly configured path is the operator's choice: use only it so
        # a later-appearing file cannot silently change which key is in use.
        return _provision([configured]) or VolatileMasterKeyStore()
    dedicated = Path(fallback_path).expanduser() if fallback_path is not None else None
    existing = [path for path in (dedicated, default_master_key_path())
                if path is not None and (path.exists() or path.is_symlink())]
    if existing:
        # An existing but unsafe file still fails closed in the caller's
        # validate(); it is never replaced by a freshly generated key.
        return LocalMasterKeyStore(existing[0])
    return _provision([path for path in (dedicated, default_master_key_path(), _state_key_path(environ))
                       if path is not None]) or VolatileMasterKeyStore()


def _provision(candidates: list[Path]) -> LocalMasterKeyStore | None:
    errors: list[str] = []
    for path in candidates:
        try:
            return LocalMasterKeyStore(ensure_master_key_file(path))
        except OSError as exc:
            errors.append(f"{path}: {exc}")
    if errors:
        logger.warning(
            "主密钥位置不可写（%s）。本次运行改用进程内临时密钥：新建数据重启后无法解密，"
            "请修正该目录权限或通过 %s 指定可写路径。", "；".join(errors), MASTER_KEY_FILE_ENV,
        )
    return None


def count_decryptable_account_records(
    store: _DerivedKeyStore, database_path: str | Path, *, sample_limit: int = 16,
) -> tuple[int, int]:
    """Return ``(matched_count, sampled_aead_count)`` for an existing account DB.

    Used only to disambiguate multiple existing local key files (e.g. legacy
    ``gateway-master-key`` vs ``session-master-key`` or cross-user setup) before
    taking exclusive ownership of the database. Never mutates the database and
    gracefully returns ``(0, 0)`` if the file is absent or locked.
    """
    db_file = Path(database_path)
    if not db_file.is_file() or db_file.is_symlink():
        return 0, 0
    try:
        uri = db_file.resolve().as_uri() + "?mode=ro"
        with closing(sqlite3.connect(uri, uri=True, timeout=0.1)) as conn:
            row = conn.execute(
                "SELECT value FROM account_metadata WHERE key='gateway_account_id'"
            ).fetchone()
            account_id = row[0] if row and isinstance(row[0], str) and row[0] else None
            if not account_id:
                return 0, 0
            aead = store.get_or_create_aead(f"open-android-intelligence-gateway/{account_id}")
            samples: list[tuple[str, str]] = []
            for key, value in conn.execute(
                "SELECT key, value FROM account_metadata WHERE value LIKE 'aead-v1:%' LIMIT ?",
                (sample_limit,),
            ).fetchall():
                if isinstance(key, str) and isinstance(value, str):
                    samples.append((value, key))
            if len(samples) < sample_limit:
                for event_id, payload_json in conn.execute(
                    "SELECT event_id, payload_json FROM events WHERE payload_json LIKE 'aead-v1:%' LIMIT ?",
                    (sample_limit - len(samples),),
                ).fetchall():
                    if isinstance(event_id, str) and isinstance(payload_json, str):
                        samples.append((payload_json, f"event:{event_id}:payload"))
            if len(samples) < sample_limit:
                for message_id, text in conn.execute(
                    "SELECT message_id, text FROM messages WHERE text LIKE 'aead-v1:%' LIMIT ?",
                    (sample_limit - len(samples),),
                ).fetchall():
                    if isinstance(message_id, str) and isinstance(text, str):
                        samples.append((text, f"message:{message_id}:text"))
    except Exception:
        return 0, 0
    matched = 0
    for sealed, purpose in samples:
        try:
            ciphertext = base64.b64decode(sealed[8:], altchars=b"-_", validate=True)
            aead.decrypt(ciphertext, purpose.encode("utf-8"))
            matched += 1
        except Exception:
            continue
    return matched, len(samples)


def master_key_unavailable_reason(core: Any) -> str | None:
    """Why the Gateway must refuse to start, or None when a key source is usable."""
    store = getattr(core, "secret_store", None)
    if store is None:
        return (
            "主密钥来源不可用：宿主未提供 Secret Store，且未能建立本地密钥来源。"
            f"请检查 {default_master_key_path()} 所在目录是否可写，或通过 "
            f"{MASTER_KEY_FILE_ENV} 指定一个可写的专用路径。"
        )
    validate = getattr(store, "validate", None)
    if callable(validate):
        try:
            validate()
        except Exception as exc:  # noqa: BLE001 - the reason is the operator-facing message
            return f"主密钥来源不可用：{exc}"
    return None
