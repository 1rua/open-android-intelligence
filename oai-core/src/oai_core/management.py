"""Local administrative inputs and independently registered recovery codes.

Recovery uses a separate, private SQLite database attached to the identity
transaction.  Only the account password can be reset through this path; it does
not switch off MFA or provide a general administrator session.
"""
from __future__ import annotations

import getpass
import hashlib
import os
from pathlib import Path
import secrets
import sqlite3
import stat
import sys
import time
import warnings

from .gateway.credentials import hash_password
from .identity import IdentityStore, _error, _private_dir, _private_file


def secret_input(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise _error("TERMINAL_REQUIRED")
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            value = getpass.getpass(prompt)
        except (getpass.GetPassWarning, EOFError) as exc:
            raise _error("TERMINAL_REQUIRED") from exc
    if not value:
        raise _error("AUTHENTICATION_FAILED")
    return value


def read_secret_fd(fd: int, *, limit: int = 4096) -> str:
    """Read a single secret from an inherited pipe/socket, never a regular file."""
    info = os.fstat(fd)
    if not (stat.S_ISFIFO(info.st_mode) or stat.S_ISSOCK(info.st_mode)):
        raise _error("SECRET_CHANNEL_INVALID")
    value = bytearray()
    while len(value) <= limit:
        chunk = os.read(fd, 1)
        if not chunk or chunk == b"\n":
            break
        value.extend(chunk)
    if not value or len(value) > limit:
        raise _error("SECRET_CHANNEL_INVALID")
    try:
        return value.decode("utf-8")
    except UnicodeError as exc:
        raise _error("SECRET_CHANNEL_INVALID") from exc


def _digest(instance_id: str, account_id: str, generation: str, code: str) -> str:
    return hashlib.sha256(("oai-core-local-admin-recovery\0" + instance_id + "\0" + account_id
                           + "\0" + generation + "\0" + code).encode("utf-8")).hexdigest()


def _connect_registry(path: Path, *, create: bool = False) -> sqlite3.Connection:
    _private_dir(path.parent)
    new_registry = False
    if create and not path.exists() and not path.is_symlink():
        try:
            fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
            os.close(fd)
            new_registry = True
        except FileExistsError:
            pass
    try:
        _private_file(path)
        connection = sqlite3.connect(path.as_uri() + "?mode=rw", uri=True, isolation_level=None, timeout=10)
        if create and new_registry:
            connection.executescript("""
                CREATE TABLE recovery_codes(
                    instance_id TEXT NOT NULL, account_id TEXT NOT NULL, generation TEXT NOT NULL,
                    digest TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(instance_id, account_id, generation, digest));
                CREATE TABLE audit_events(
                    id INTEGER PRIMARY KEY, at REAL NOT NULL, action TEXT NOT NULL,
                    instance_id TEXT NOT NULL, account_id TEXT NOT NULL, generation TEXT NOT NULL);
            """)
        connection.execute("SELECT instance_id,account_id,generation,digest,used FROM recovery_codes LIMIT 0")
        connection.execute("SELECT at,action,instance_id,account_id,generation FROM audit_events LIMIT 0")
        return connection
    except (OSError, sqlite3.Error) as exc:
        if "connection" in locals():
            connection.close()
        raise _error("LOCAL_RECOVERY_UNAVAILABLE") from exc


class LocalRecovery:
    """Separate administrator recovery registration and narrow local reset."""

    def __init__(self, store: IdentityStore):
        if not isinstance(store, IdentityStore) or store.database is None:
            raise _error("IDENTITY_DATABASE_MISSING")
        self.store = store
        self.path = store.database_path.parent / "local-recovery.sqlite"

    def _attach(self, *, create: bool = False) -> None:
        connection = _connect_registry(self.path, create=create)
        connection.close()
        if any(row[1] == "local_recovery" for row in self.store.database.execute("PRAGMA database_list")):
            raise _error("LOCAL_RECOVERY_BUSY")
        self.store.database.execute("ATTACH DATABASE ? AS local_recovery", (str(self.path),))

    def register(self, handle: str, *, confirmed: bool) -> dict:
        """Register fresh one-use codes after live admin authorization."""
        if confirmed is not True:
            raise _error("CONFIRMATION_REQUIRED")
        # Do not create the registry or lock files before authorization.
        self.store.require_management(handle, "security")
        # Recreating a lost registry would erase its independent consumption
        # history, so only the first-ever registration may initialize it.
        if not self.path.exists() and not self.path.is_symlink():
            if self.store.database.execute(
                "SELECT 1 FROM accounts WHERE local_recovery_generation IS NOT NULL LIMIT 1"
            ).fetchone():
                raise _error("LOCAL_RECOVERY_UNAVAILABLE")
        generation = secrets.token_urlsafe(24)
        codes = [secrets.token_urlsafe(32) for _ in range(10)]
        with self.store._lock:
            self._attach(create=True)
            try:
                with self.store.transaction() as db:
                    actor = self.store.require_management(handle, "security")
                    row = self.store._row(actor["accountId"])
                    instance = actor["instanceId"]
                    db.executemany("INSERT INTO local_recovery.recovery_codes VALUES (?,?,?,?,0)",
                                   [(instance, row["account_id"], generation,
                                     _digest(instance, row["account_id"], generation, code)) for code in codes])
                    db.execute("UPDATE accounts SET local_recovery_generation=? WHERE account_id=?",
                               (generation, row["account_id"]))
                    db.execute("INSERT INTO local_recovery.audit_events(at,action,instance_id,account_id,generation) VALUES (?,?,?,?,?)",
                               (time.time(), "recovery.registered", instance, row["account_id"], generation))
                    self.store._audit("recovery.registered", row["account_id"], row["account_id"], {})
            finally:
                self.store.database.execute("DETACH DATABASE local_recovery")
        return {"accountId": actor["accountId"], "recoveryCodes": codes}

    def reset_password(self, username: str, recovery_code: str, new_password: str, *, confirmed: bool) -> dict:
        """Consume an independent proof locally and atomically invalidate sessions."""
        if confirmed is not True:
            raise _error("CONFIRMATION_REQUIRED")
        if not isinstance(recovery_code, str) or not recovery_code or len(recovery_code) > 256:
            raise _error("AUTHENTICATION_FAILED")
        digest = hash_password(new_password)
        with self.store._lock:
            self._attach()
            try:
                with self.store.transaction() as db:
                    row = self.store._by_username(username)
                    if row["role"] != "admin" or not row["local_recovery_generation"]:
                        raise _error("AUTHENTICATION_FAILED")
                    instance = self.store._policy()[2]
                    generation = row["local_recovery_generation"]
                    code_digest = _digest(instance, row["account_id"], generation, recovery_code)
                    # Keep consumption in both independently stored authorities:
                    # restoring only the recovery registry cannot revive a code.
                    db.execute("CREATE TABLE IF NOT EXISTS local_recovery_consumed (digest TEXT PRIMARY KEY, account_id TEXT NOT NULL)")
                    if db.execute("SELECT 1 FROM local_recovery_consumed WHERE digest=?", (code_digest,)).fetchone():
                        raise _error("AUTHENTICATION_FAILED")
                    registered = db.execute(
                        "SELECT 1 FROM local_recovery.audit_events WHERE instance_id=? AND account_id=? "
                        "AND generation=? AND action='recovery.registered' LIMIT 1",
                        (instance, row["account_id"], generation),
                    ).fetchone()
                    if not registered:
                        raise _error("LOCAL_RECOVERY_UNAVAILABLE")
                    used = db.execute(
                        "UPDATE local_recovery.recovery_codes SET used=1 WHERE instance_id=? AND account_id=? "
                        "AND generation=? AND digest=? AND used=0",
                        (instance, row["account_id"], generation,
                         code_digest),
                    )
                    if used.rowcount != 1:
                        raise _error("AUTHENTICATION_FAILED")
                    db.execute("INSERT INTO local_recovery_consumed VALUES (?,?)", (code_digest, row["account_id"]))
                    db.execute("UPDATE accounts SET password_hash=?,auth_epoch=auth_epoch+1 WHERE account_id=?",
                               (digest, row["account_id"]))
                    db.execute("UPDATE management_handles SET revoked=1 WHERE account_id=?", (row["account_id"],))
                    db.execute("INSERT INTO local_recovery.audit_events(at,action,instance_id,account_id,generation) VALUES (?,?,?,?,?)",
                               (time.time(), "recovery.password_reset", instance, row["account_id"], generation))
                    self.store._audit("recovery.password_reset", row["account_id"], row["account_id"], {})
                    return self.store._public(self.store._row(row["account_id"]))
            finally:
                self.store.database.execute("DETACH DATABASE local_recovery")


def require_management_fd(store: IdentityStore, fd: int, scope: str) -> dict:
    """A helper verifies the actual persisted handle, not a caller assertion."""
    handle = read_secret_fd(fd)
    try:
        return store.require_management(handle, scope)
    finally:
        handle = ""
