"""Read the existing Hermes account bindings without a device runtime.

Each legacy account uses one exclusive SQLite connection. A running old Gateway
cannot write the same database, and a busy database makes activation fail closed.
The database layout and encrypted-record purposes remain those owned by Core.
"""
from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
import sqlite3
import threading
from types import SimpleNamespace

from oai_core.gateway.account_paths import account_paths
from oai_core.gateway.audit import AuditStore
from oai_core.gateway.core import (AccountStore, AgentSessionBindings, AttachmentStore,
    ContractRegistry, ConversationPort, DEFAULT_ATTACHMENT_POLICY, EventStore, GatewayError)
from oai_core.gateway.local_keys import (LocalMasterKeyStore, default_master_key_path,
                                        master_key_file)


class LockedDatabase:
    """Serialize SQL on the persistent account connection across host threads."""
    def __init__(self, connection, lock):
        self.connection, self.lock = connection, lock

    def execute(self, *args, **kwargs):
        with self.lock:
            return self.connection.execute(*args, **kwargs)

    def executemany(self, *args, **kwargs):
        with self.lock:
            return self.connection.executemany(*args, **kwargs)

    def executescript(self, *args, **kwargs):
        with self.lock:
            return self.connection.executescript(*args, **kwargs)

    def __getattr__(self, name):
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


class SessionAccount:
    """A lease on shared session storage; backend lifetime owns the connection."""
    def __init__(self, shared):
        self.__dict__.update(shared.__dict__)
        self._closed = False

    def close(self):
        self._closed = True


class SessionBackend:
    """Only native conversation data; no device queue, pairing or credentials."""
    def __init__(self, root: Path, accounts: list[str], *, secret_store=None):
        self.storage_root = root.resolve()
        if not self.storage_root.is_dir():
            raise ValueError("Hermes accounts directory does not exist; no new account store is created")
        self.accounts = tuple(accounts)
        self.secret_store = secret_store or LocalMasterKeyStore(master_key_file() or default_master_key_path())
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
                        agent_sessions=AgentSessionBindings(store))
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
            sink(account_id, event)

    def close(self):
        with self._lock:
            stores = tuple(self._stores.values())
            self._stores.clear()
        # A commit may deliver an event while holding its account lock. Release
        # the backend lock before waiting for that account, avoiding lock inversion.
        for shared in stores:
            with shared.store.lock:
                shared.store.database.connection.close()
