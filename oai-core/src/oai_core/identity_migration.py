"""Authenticated, stopped-writer migration from account-local password stores.

Local full snapshots are recovery points, not portable identity exports. No
original snapshot or damaged candidate is silently replaced or removed.
"""
from __future__ import annotations

from contextlib import ExitStack, closing
import json
import os
from pathlib import Path
import secrets
import hashlib
import sqlite3
import time
from typing import Callable

from .gateway.core import GatewayError
from .gateway.credentials import verify_password
from .identity import IdentityStore, _private_dir, _private_file

WRITER = "oai_account_identity_writer"
MODEL = "core-account-v1"
MARKER = "core-account-identity-model"


def read_identity_migration_state(identity_path: Path) -> str | None:
    journal = Path(identity_path).parent.parent / "identity-migrations" / "journal.json"
    if not journal.exists() and not journal.is_symlink():
        return None
    _private_file(journal)
    try:
        state = json.loads(journal.read_text())
        if not isinstance(state, dict) or state.get("version") != 1 or not isinstance(state.get("stage"), str):
            raise ValueError()
        return state["stage"]
    except (ValueError, OSError) as error:
        raise GatewayError("IDENTITY_MIGRATION_RECOVERY_REQUIRED") from error


def register_identity_writer(database: sqlite3.Connection) -> None:
    database.create_function(WRITER, 0, lambda: MODEL, deterministic=True)


def check_identity_writer(database: sqlite3.Connection) -> None:
    if database.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='account_metadata'").fetchone() is None:
        return
    row = database.execute("SELECT value FROM account_metadata WHERE key=?", (MARKER,)).fetchone()
    if row is not None and row[0] != MODEL:
        raise GatewayError("ACCOUNT_IDENTITY_MODEL_UNSUPPORTED")


def _write(path: Path, value: dict) -> None:
    temporary = path.with_name(path.name + "." + secrets.token_hex(8))
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _guard(database: sqlite3.Connection) -> None:
    register_identity_writer(database)
    for table, in database.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*'").fetchall():
        name = '"' + table.replace('"', '""') + '"'
        for operation in ("INSERT", "UPDATE", "DELETE"):
            trigger = '"account_identity_v1_' + table.replace('"', '""') + "_" + operation.lower() + '"'
            database.execute(f"CREATE TRIGGER IF NOT EXISTS {trigger} BEFORE {operation} ON {name} "
                f"WHEN {WRITER}() IS NOT '{MODEL}' BEGIN SELECT RAISE(ABORT, 'ACCOUNT_IDENTITY_MODEL_UNSUPPORTED'); END")


def migrate_legacy_installation(identity_path: Path, storage_root: Path, username: str, password: str, *,
                                confirmed: bool, old_writers_stopped: Callable[[], bool]) -> IdentityStore:
    """Verify first; then stop/verify old writers, snapshot and activate one authority."""
    identity_path, storage_root = Path(identity_path).absolute(), Path(storage_root).absolute()
    if confirmed is not True:
        raise GatewayError("CONFIRMATION_REQUIRED")
    before = IdentityStore._legacy_accounts(storage_root)
    selected = next((record for record in before if record[1] == username), None)
    if selected is None or not verify_password(password, selected[2]):
        raise GatewayError("AUTHENTICATION_FAILED")
    if not callable(old_writers_stopped) or old_writers_stopped() is not True:
        raise GatewayError("OLD_WRITERS_ACTIVE")
    if before != IdentityStore._legacy_accounts(storage_root):
        raise GatewayError("LEGACY_STATE_CHANGED")
    if identity_path.exists() or identity_path.is_symlink():
        raise GatewayError("IDENTITY_ALREADY_INITIALIZED")
    runtime = storage_root.parent
    _private_dir(runtime)
    migration_dir = runtime / "identity-migrations"
    _private_dir(migration_dir, create=True)
    journal = migration_dir / "journal.json"
    if journal.exists() or journal.is_symlink():
        if read_identity_migration_state(identity_path) != "rolled_back":
            raise GatewayError("IDENTITY_MIGRATION_RECOVERY_REQUIRED")
        retained_journal = migration_dir / ("rolled-back-" + secrets.token_hex(8) + ".json")
        os.replace(journal, retained_journal)
    batch = migration_dir / (str(int(time.time())) + "-" + secrets.token_hex(8))
    _private_dir(batch, create=True)
    state = {"version": 1, "stage": "preparing", "selectedAccountId": selected[0], "accounts": []}
    legacy_digests = {record[0]: record[2] for record in before}
    store = None
    with ExitStack() as stack:
        connections = []
        try:
            for account_id, *_ in before:
                from .gateway.account_paths import account_paths
                path = account_paths(storage_root, account_id).database
                _private_dir(path.parent)
                if path.is_symlink() or not path.is_file() or path.stat().st_uid != os.geteuid():
                    raise GatewayError("IDENTITY_PERMISSIONS_INVALID")
                # Legacy SQLite files were protected by their 0700 account
                # directory. Tighten the file only after successful verification.
                path.chmod(0o600)
                _private_file(path)
                database = sqlite3.connect(path, isolation_level=None, timeout=0)
                stack.callback(database.close)
                register_identity_writer(database)
                # Existing Android authority guards also need this writer identity.
                from .gateway.device_authority import register_authority_writer
                register_authority_writer(database)
                database.execute("BEGIN IMMEDIATE")
                connections.append(database)
                snapshot = batch / (path.parent.name + ".sqlite")
                fd = os.open(snapshot, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                os.close(fd)
                with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as source:
                    with closing(sqlite3.connect(snapshot)) as target:
                        source.backup(target)
                        if target.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                            raise GatewayError("IDENTITY_BACKUP_INVALID")
                with snapshot.open("rb") as stream:
                    os.fsync(stream.fileno())
                state["accounts"].append({"accountId": account_id, "snapshot": os.path.relpath(snapshot, runtime),
                    "snapshotSha256": hashlib.sha256(snapshot.read_bytes()).hexdigest()})
            _write(journal, state)
            if before != IdentityStore._legacy_accounts(storage_root):
                raise GatewayError("LEGACY_STATE_CHANGED")
            store = IdentityStore(identity_path, create=True)
            store.migrate_legacy(storage_root, username, password, confirmed=True)
            with store.transaction() as identity_db:
                identity_db.execute("INSERT INTO metadata VALUES ('migration_state','preparing')")
            state["stage"] = "guarding"
            _write(journal, state)
            for database in connections:
                database.execute("INSERT OR REPLACE INTO account_metadata(key,value) VALUES (?,?)", (MARKER, MODEL))
                # Retained original digest lives only in the owner-only snapshot.
                database.execute("UPDATE account_credentials SET password_hash='migrated-to-core-identity'")
                _guard(database)
                database.commit()
            with store.transaction() as identity_db:
                identity_db.execute("UPDATE metadata SET value='active' WHERE key='migration_state'")
                store._audit("identity.migration_completed", selected[0], None, {"accounts": len(before)})
            state["stage"] = "committed"
            _write(journal, state)
            return store
        except BaseException:
            for database in connections:
                if database.in_transaction:
                    database.rollback()
            if store is not None:
                store.close()
            # Only the stopped, not-yet-activated upgrade may undo its own
            # recorded password retirement. Preserve all business rows and
            # snapshots; no authenticated new session has been issued yet.
            restored = True
            for account_id, *_ in before:
                from .gateway.account_paths import account_paths
                try:
                    with closing(sqlite3.connect(account_paths(storage_root, account_id).database)) as database:
                        register_identity_writer(database)
                        from .gateway.device_authority import register_authority_writer
                        register_authority_writer(database)
                        database.execute("UPDATE account_credentials SET password_hash=? WHERE credential_id='password'",
                            (legacy_digests[account_id],))
                        for trigger, in database.execute("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'account_identity_v1_%'").fetchall():
                            database.execute('DROP TRIGGER "' + trigger.replace('"', '""') + '"')
                        database.execute("DELETE FROM account_metadata WHERE key=?", (MARKER,))
                        database.commit()
                except (OSError, sqlite3.Error):
                    restored = False
            if restored and identity_path.exists():
                retained = batch / "failed-identity.sqlite"
                if retained.exists():
                    restored = False
                else:
                    os.replace(identity_path, retained)
            state["stage"] = "rolled_back" if restored else "recovery_required"
            _write(journal, state)
            raise
