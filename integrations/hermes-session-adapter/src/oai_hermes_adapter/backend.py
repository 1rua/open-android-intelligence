"""Read the existing Hermes account bindings without a device runtime.

Each legacy account uses one exclusive SQLite connection. A running old Gateway
cannot write the same database, and a busy database makes activation fail closed.
The database layout and encrypted-record purposes remain those owned by Core.
"""
from __future__ import annotations

import base64
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import logging
import sqlite3
import threading
from types import SimpleNamespace

from oai_core.gateway.account_paths import account_paths
from oai_core.gateway.audit import AuditStore
from oai_core.gateway.core import (AccountStore, AgentSessionBindings, ApprovalRequests, AttachmentStore,
    ContractRegistry, ConversationPort, DEFAULT_ATTACHMENT_POLICY, EventStore, GatewayError)
from oai_core.gateway.local_keys import (
    LocalMasterKeyStore,
    count_decryptable_account_records,
    default_master_key_path,
    default_session_key_path,
    master_key_file,
    resolve_local_master_key_store,
)

logger = logging.getLogger(__name__)


class LockedCursor:
    """Fetching/iterating also steps SQLite and must use the connection lock."""
    def __init__(self, cursor, database):
        self._cursor, self._database = cursor, database

    def _call(self, method, *args, **kwargs):
        with self._database.lock:
            self._database._assert_usable()
            value = getattr(self._cursor, method)(*args, **kwargs)
            return self if value is self._cursor else value

    def fetchone(self):
        return self._call("fetchone")

    def fetchmany(self, *args):
        return self._call("fetchmany", *args)

    def fetchall(self):
        return self._call("fetchall")

    def execute(self, *args, **kwargs):
        return self._call("execute", *args, **kwargs)

    def executemany(self, *args, **kwargs):
        return self._call("executemany", *args, **kwargs)

    def executescript(self, *args, **kwargs):
        return self._call("executescript", *args, **kwargs)

    def close(self):
        with self._database.lock:
            self._cursor.close()

    def __iter__(self):
        return self

    def __next__(self):
        return self._call("__next__")

    def __getattr__(self, name):
        with self._database.lock:
            self._database._assert_usable()
            return getattr(self._cursor, name)


class LockedDatabase:
    """Serialize SQL on the persistent account connection across host threads."""
    def __init__(self, connection, lock):
        self.connection, self.lock = connection, lock
        self.failed = False

    def _assert_usable(self):
        if self.failed:
            raise GatewayError("HOST_INCOMPATIBLE", {"reason": "SESSION_STORAGE_RECOVERY_REQUIRED"})

    def close(self):
        with self.lock:
            self.failed = True
            try:
                self.connection.close()
            except sqlite3.Error:
                # A failed recovery may already have closed the connection.
                # The guard remains active even if SQLite cannot close again.
                pass

    def execute(self, *args, **kwargs):
        with self.lock:
            self._assert_usable()
            return LockedCursor(self.connection.execute(*args, **kwargs), self)

    def executemany(self, *args, **kwargs):
        with self.lock:
            self._assert_usable()
            return LockedCursor(self.connection.executemany(*args, **kwargs), self)

    def executescript(self, *args, **kwargs):
        with self.lock:
            self._assert_usable()
            return LockedCursor(self.connection.executescript(*args, **kwargs), self)

    def cursor(self, *args, **kwargs):
        with self.lock:
            self._assert_usable()
            return LockedCursor(self.connection.cursor(*args, **kwargs), self)

    def commit(self):
        with self.lock:
            self._assert_usable()
            return self.connection.commit()

    def rollback(self):
        with self.lock:
            self._assert_usable()
            return self.connection.rollback()

    def __getattr__(self, name):
        with self.lock:
            self._assert_usable()
            return getattr(self.connection, name)


class SessionStore(AccountStore):
    def __init__(self, *args, lock, **kwargs):
        self.lock = lock
        super().__init__(*args, **kwargs)
        self.database = LockedDatabase(self.database, lock)

    @contextmanager
    def transaction(self, *args, **kwargs):
        with self.lock:
            with super().transaction(*args, **kwargs) as database:
                yield database

    def _reopen_after_unknown(self):
        # Core's device Store may reconnect after uncertain commits. A session
        # Store must retain its exact exclusive connection: closing it would
        # permit a legacy writer to enter before a replacement acquired a lock.
        with self.lock:
            connection = getattr(self.database, "connection", self.database)
            try:
                connection.rollback()
                mode = connection.execute("PRAGMA locking_mode").fetchone()[0]
                if mode.lower() != "exclusive":
                    raise sqlite3.OperationalError("exclusive session ownership lost")
                connection.execute("BEGIN EXCLUSIVE")
                connection.execute("COMMIT")
                self._transaction_depth = 0
            except Exception as exc:
                # No ordinary connection is substituted. The existing account
                # lease and every future lease fail closed until reactivation.
                if isinstance(self.database, LockedDatabase):
                    self.database.close()
                else:
                    try:
                        connection.close()
                    except sqlite3.Error:
                        pass
                raise GatewayError("HOST_INCOMPATIBLE", {"reason": "SESSION_STORAGE_RECOVERY_REQUIRED"}) from exc


class SessionAccount:
    """A lease on shared session storage; backend lifetime owns the connection."""
    def __init__(self, shared):
        self.__dict__.update(shared.__dict__)
        self._closed = False

    def close(self):
        self._closed = True


def _select_session_key_store(storage_root: Path, accounts: tuple[str, ...]):
    """Prefer whichever existing local key decrypts the most records in ``storage_root``."""
    if master_key_file() is None and accounts:
        candidates: list[LocalMasterKeyStore] = []
        for path in (default_session_key_path(), default_master_key_path()):
            if path.is_file() and not path.is_symlink():
                store = LocalMasterKeyStore(path)
                try:
                    store.validate()
                except Exception:
                    continue
                candidates.append(store)
        if len(candidates) > 1:
            best_store = candidates[0]
            best_matches = -1
            for candidate in candidates:
                matches = 0
                for account_id in accounts:
                    db_file = account_paths(storage_root, account_id).database
                    matched, _ = count_decryptable_account_records(candidate, db_file)
                    matches += matched
                if matches > best_matches:
                    best_matches = matches
                    best_store = candidate
            return best_store
    return resolve_local_master_key_store(fallback_path=default_session_key_path())


def _repair_unreadable_session_records(store: SessionStore, account_id: str) -> None:
    """Quarantine orphaned AEAD ciphertexts left by a lost or volatile historical key.

    Hermes keeps long-lived conversation transcripts, titles, and session state
    in its own native ``SessionDB``. ``SessionStore`` holds only wire bindings,
    transient event replay buffers, and per-conversation metadata caches. When an
    earlier key was lost or ephemeral, unreadable ``aead-v1:`` rows would cause
    every session list, event sync, and reply append to fail with
    ``DECRYPTION_FAILED``. Removing unreadable ephemeral ciphertexts while
    recording conservative event-prune sequence markers lets clients cleanly
    re-snapshot from native Hermes history without fabricating state.
    """
    dead_meta_keys: list[str] = []
    for key, value in store.database.execute(
        "SELECT key, value FROM account_metadata WHERE value LIKE 'aead-v1:%'"
    ).fetchall():
        try:
            store.open_bytes(value, key)
        except GatewayError as exc:
            if exc.code == "DECRYPTION_FAILED":
                dead_meta_keys.append(key)
            else:
                raise

    for key, value in store.database.execute(
        "SELECT key, value FROM account_metadata WHERE key LIKE 'native-event-cursor:v1:%:ack'"
    ).fetchall():
        purpose = key[:-4]
        try:
            parsed = json.loads(value)
            cursor = parsed["cursor"]
            sealed = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode("ascii")
            store.open_bytes(sealed, purpose)
        except Exception:
            dead_meta_keys.append(key)

    dead_event_sequences: list[int] = []
    for seq, event_id, payload_json in store.database.execute(
        "SELECT sequence, event_id, payload_json FROM events WHERE payload_json LIKE 'aead-v1:%'"
    ).fetchall():
        try:
            store.open_bytes(payload_json, f"event:{event_id}:payload")
        except GatewayError as exc:
            if exc.code == "DECRYPTION_FAILED":
                dead_event_sequences.append(int(seq))
            else:
                raise

    dead_message_ids: list[str] = []
    for message_id, text in store.database.execute(
        "SELECT message_id, text FROM messages WHERE text LIKE 'aead-v1:%'"
    ).fetchall():
        try:
            store.open_bytes(text, f"message:{message_id}:text")
        except GatewayError as exc:
            if exc.code == "DECRYPTION_FAILED":
                dead_message_ids.append(message_id)
            else:
                raise

    dead_ledger_rowids: list[int] = []
    for rowid, outcome_json in store.database.execute(
        "SELECT rowid, outcome_json FROM idempotency_ledger WHERE outcome_json LIKE 'aead-v1:%'"
    ).fetchall():
        # Legacy Gateway ledger rows are unused by pure SessionBackend, so only
        # drop rows if the account already exhibited unreadable session records.
        if dead_meta_keys or dead_event_sequences or dead_message_ids:
            dead_ledger_rowids.append(int(rowid))

    if not (dead_meta_keys or dead_event_sequences or dead_message_ids or dead_ledger_rowids):
        return

    with store.transaction():
        if dead_meta_keys:
            store.database.executemany(
                "DELETE FROM account_metadata WHERE key = ?",
                [(k,) for k in dead_meta_keys],
            )
        if dead_event_sequences:
            max_seq = str(max(dead_event_sequences))
            conversation_ids = [
                str(r[0]) for r in store.database.execute(
                    "SELECT conversation_id FROM conversations"
                ).fetchall() if r[0]
            ]
            for cid in conversation_ids:
                marker_key = "conversation-event-pruned:v1:" + hashlib.sha256(cid.encode()).hexdigest()
                store.database.execute(
                    "INSERT INTO account_metadata(key,value) VALUES (?,?) "
                    "ON CONFLICT(key) DO UPDATE SET value=CAST(MAX(CAST(value AS INTEGER),CAST(excluded.value AS INTEGER)) AS TEXT)",
                    (marker_key, max_seq),
                )
            store.database.executemany(
                "DELETE FROM events WHERE sequence = ?",
                [(seq,) for seq in dead_event_sequences],
            )
        if dead_message_ids:
            store.database.executemany(
                "UPDATE messages SET text = '' WHERE message_id = ?",
                [(mid,) for mid in dead_message_ids],
            )
        if dead_ledger_rowids:
            store.database.executemany(
                "DELETE FROM idempotency_ledger WHERE rowid = ?",
                [(rid,) for rid in dead_ledger_rowids],
            )
    logger.warning(
        "Quarantined unreadable legacy session ciphertexts accountId=%s metadata=%d events=%d messages=%d ledger=%d",
        account_id, len(dead_meta_keys), len(dead_event_sequences), len(dead_message_ids), len(dead_ledger_rowids),
    )


class SessionBackend:
    """Only native conversation data; no device queue, pairing or credentials."""
    def __init__(self, root: Path, accounts: list[str], *, secret_store=None):
        self.storage_root = root.resolve()
        if not self.storage_root.is_dir():
            raise ValueError("Hermes accounts directory does not exist; no new account store is created")
        self.accounts = tuple(accounts)
        # The session host prepares its own derived key (separate from the
        # device Core's) so loading a pure session bridge never depends on the
        # device encryption key an operator may not have provisioned.
        self.secret_store = secret_store or _select_session_key_store(self.storage_root, self.accounts)
        self.secret_store.validate()
        self.attachment_policy = DEFAULT_ATTACHMENT_POLICY
        self.contracts = ContractRegistry()
        self.history_reader = self.conversation_manager = self.generation_canceller = None
        self._sinks = []
        self._stores = {}
        self._lock = threading.RLock()
        for account in self.accounts:
            if not account_paths(self.storage_root, account).database.is_file():
                raise ValueError("Hermes allowlist contains an account absent from the existing account root")

    def account_exists(self, account_id):
        return account_id in self.accounts and account_paths(self.storage_root, account_id).database.is_file()

    def list_gateway_account_ids(self):
        return list(self.accounts)

    def acquire_accounts(self):
        with self._lock:
            try:
                for account_id in self.accounts:
                    if account_id in self._stores:
                        continue
                    paths = account_paths(self.storage_root, account_id)
                    connection = sqlite3.connect(str(paths.database), isolation_level=None,
                                                 check_same_thread=False, timeout=0.1)
                    try:
                        connection.row_factory = sqlite3.Row
                        connection.execute("PRAGMA locking_mode=EXCLUSIVE")
                        connection.execute("BEGIN EXCLUSIVE")
                        connection.execute("COMMIT")
                        name = f"open-android-intelligence-gateway/{account_id}"
                        cipher = self.secret_store.get_or_create_aead(name)
                        store = SessionStore(paths, cipher.reference, aead=cipher,
                            account_id=account_id, event_sink=self.deliver_event,
                            database=connection, lock=threading.RLock())
                        _repair_unreadable_session_records(store, account_id)
                    except BaseException:
                        connection.close()
                        raise
                    audit = AuditStore(store, account_id)
                    events = EventStore(store, self.contracts)
                    attachments = AttachmentStore(account_id, paths, store, audit, self.attachment_policy, events)
                    conversations = ConversationPort(account_id, store, attachments, audit, events, self.attachment_policy)
                    self._stores[account_id] = SimpleNamespace(account_id=account_id, accountId=account_id,
                        paths=paths, store=store, audit=audit, events=events, attachments=attachments,
                        attachment_policy=self.attachment_policy, conversations=conversations,
                        agent_sessions=AgentSessionBindings(store), approvals=ApprovalRequests(store))
            except BaseException:
                self.close()
                raise

    def open_gateway_account(self, account_id):
        if not self.account_exists(account_id):
            raise GatewayError("AUTHENTICATION_REQUIRED")
        with self._lock:
            if account_id not in self._stores:
                raise GatewayError("HOST_INCOMPATIBLE", {"reason": "SESSION_BACKEND_NOT_CONNECTED"})
            shared = self._stores[account_id]
            shared.store.database._assert_usable()
            shared.conversations.history_reader = self.history_reader
            return SessionAccount(shared)

    def register_event_sink(self, sink):
        with self._lock:
            if sink not in self._sinks:
                self._sinks.append(sink)

    def unregister_event_sink(self, sink):
        with self._lock:
            self._sinks = [current for current in self._sinks if current is not sink]

    def deliver_event(self, account_id, event):
        with self._lock:
            sinks = tuple(self._sinks)
        for sink in sinks:
            try:
                sink(account_id, event)
            except Exception as exc:
                # The event is committed already. A detached observer cannot
                # suppress other subscribers or leak content through its error.
                logger.warning("Native session event observer failed errorType=%s", type(exc).__name__)

    def close(self):
        with self._lock:
            stores = tuple(self._stores.values())
            self._stores.clear()
        # A commit may deliver an event while holding its account lock. Release
        # the backend lock before waiting for that account, avoiding lock inversion.
        for shared in stores:
            with shared.store.lock:
                shared.store.database.close()
