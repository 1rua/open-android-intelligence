"""SQLite safeguards for the Android device-authorization model boundary."""

from __future__ import annotations

import os
import sqlite3
from contextlib import closing
from itertools import zip_longest

from .account_paths import AccountPaths


AUTHORITY_BACKUP_KEY = "device-authorization-backup:v1"
AUTHORITY_MODEL_KEY = "device-authorization-model:v1"
AUTHORITY_MODEL = "android-v1"
WRITER_FUNCTION = "oai_device_authorization_writer"


class AuthorityBackupConflict(RuntimeError):
    """An existing pre-model snapshot cannot safely represent this database."""


def read_authority_model(database: sqlite3.Connection) -> str | None:
    if database.execute(
        "SELECT 1 FROM sqlite_schema WHERE type='table' AND name='account_metadata'",
    ).fetchone() is None:
        return None
    row = database.execute(
        "SELECT value FROM account_metadata WHERE key=?", (AUTHORITY_MODEL_KEY,),
    ).fetchone()
    return str(row[0]) if row is not None else None


def register_authority_writer(database: sqlite3.Connection) -> None:
    # Function registration belongs to a connection, so recovery and native
    # session stores using AccountStore receive the same version identity.
    database.create_function(WRITER_FUNCTION, 0, lambda: AUTHORITY_MODEL, deterministic=True)


def _writer_guards(database: sqlite3.Connection):
    tables = database.execute(
        "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT GLOB 'sqlite_*' ORDER BY name",
    ).fetchall()
    for row in tables:
        for operation in ("INSERT", "UPDATE", "DELETE"):
            yield row[0], operation, f"device_authority_android_v1_{row[0]}_{operation.lower()}"


def writer_guards_installed(database: sqlite3.Connection) -> bool:
    existing = {row[0] for row in database.execute("SELECT name FROM sqlite_schema WHERE type='trigger'")}
    return all(name in existing for _, _, name in _writer_guards(database))


def install_authority_writer_guards(database: sqlite3.Connection) -> None:
    """Persist fail-closed guards in the same transaction as the model marker.

    SQLite recompiles old prepared statements after this schema change. Both
    previously opened connections and later binaries without the registered
    function fail before modifying any existing business table.
    """
    for table, operation, name in _writer_guards(database):
        quoted_table = '"' + table.replace('"', '""') + '"'
        quoted_name = '"' + name.replace('"', '""') + '"'
        database.execute(
            f"CREATE TRIGGER IF NOT EXISTS {quoted_name} BEFORE {operation} ON {quoted_table} "
            f"WHEN {WRITER_FUNCTION}() IS NOT '{AUTHORITY_MODEL}' "
            "BEGIN SELECT RAISE(ABORT, 'DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED'); END"
        )


def backup_legacy_device_authority(paths: AccountPaths) -> str:
    """Preserve a complete old-model database while the caller holds its write lock.

    The source must be a separate read-only connection: SQLite backup on the
    connection that owns BEGIN IMMEDIATE would wait on its own transaction.
    A failed migration reuses its one retained snapshot only after comparing
    SQLite's complete logical export under the write lock. Different data or an
    incomplete snapshot requires operator maintenance, preventing unbounded
    full-database copies when an account repeatedly fails to open. No snapshot
    is replaced or removed. The original master key stays outside this copy.
    """
    directory = paths.root / "backups"
    if directory.is_symlink():
        raise OSError("device authorization backup directory must be account-local")
    directory.mkdir(mode=0o700, exist_ok=True)
    directory.chmod(0o700)
    destination = directory / "device-authority-pre-android-v1.sqlite"
    try:
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        if destination.is_symlink():
            raise AuthorityBackupConflict("existing snapshot must be account-local")
        created = False
    else:
        try:
            os.fchmod(descriptor, 0o600)
        finally:
            os.close(descriptor)
        created = True
    with closing(sqlite3.connect(paths.database.resolve().as_uri() + "?mode=ro", uri=True)) as source:
        if created:
            with closing(sqlite3.connect(destination.resolve().as_uri() + "?mode=rw", uri=True)) as snapshot:
                source.backup(snapshot)
                # Leave a self-contained database, without depending on a
                # backup connection's WAL or the active account's WAL files.
                snapshot.execute("PRAGMA journal_mode=DELETE")
                if snapshot.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise OSError("device authorization snapshot failed its integrity check")
        else:
            try:
                with closing(sqlite3.connect(destination.resolve().as_uri() + "?mode=ro", uri=True)) as snapshot:
                    if snapshot.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                        raise AuthorityBackupConflict("existing snapshot failed its integrity check")
                    # These are streaming, reversible SQL exports of every
                    # schema object, table row and sequence, including bodies
                    # and identities. They are compared without logging them.
                    if any(current != archived for current, archived in zip_longest(source.iterdump(), snapshot.iterdump())):
                        raise AuthorityBackupConflict("database changed since its retained snapshot")
            except sqlite3.Error as exc:
                raise AuthorityBackupConflict("existing snapshot cannot be verified") from exc
    with destination.open("rb") as completed:
        os.fsync(completed.fileno())
    for parent in (directory, paths.root):
        descriptor = os.open(parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    return destination.relative_to(paths.root).as_posix()
