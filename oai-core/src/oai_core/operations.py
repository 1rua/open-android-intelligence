"""Durable native-operation receipts; never a copy of native session history."""
from __future__ import annotations

import asyncio
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from contextvars import copy_context
import hashlib
import json
from pathlib import Path
import sqlite3
import time
import threading
from typing import Any

from .native_diagnostics import trace_native_stage


class OperationConflict(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _wal_reset_fix_available(version: tuple[int, ...]) -> bool:
    # SQLite's March 2026 WAL-reset fix and its two supported backport lines.
    return version >= (3, 51, 3) or (3, 50, 7) <= version < (3, 51, 0) or (3, 44, 6) <= version < (3, 45, 0)


class _BatchInvalidated(RuntimeError):
    """SQLite rolled back more than one operation; no cached result is reliable."""


class NativeOperations:
    def __init__(self, path: Path | None = None):
        if path is not None:
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.database = sqlite3.connect(str(path) if path is not None else ":memory:")
        if path is not None:
            path.chmod(0o600)
            # WAL + FULL keeps each acknowledged commit synced while avoiding
            # rollback-journal fsync churn on the message/create critical path.
            desired = "WAL" if _wal_reset_fix_available(sqlite3.sqlite_version_info) else "DELETE"
            mode = self.database.execute("PRAGMA journal_mode=" + desired).fetchone()[0]
            if mode.lower() not in {"wal", "delete"} or (desired == "DELETE" and mode.lower() != "delete"):
                self.database.close()
                raise RuntimeError("Operation ledger journal mode is unavailable")
            self.database.execute("PRAGMA synchronous=FULL")
        self.database.execute("CREATE TABLE IF NOT EXISTS native_operations (account TEXT NOT NULL, request_id TEXT NOT NULL, digest TEXT NOT NULL, receipt TEXT, PRIMARY KEY(account,request_id))")
        self.database.execute("CREATE TABLE IF NOT EXISTS native_operation_scopes ("
                              "account TEXT NOT NULL, request_id TEXT NOT NULL, agent_id TEXT NOT NULL, "
                              "PRIMARY KEY(account,request_id))")
        self.database.commit()
        self._batch_active = False

    @contextmanager
    def batch(self):
        # One durable commit for already queued operations; no timer delays a
        # solitary request. Per-operation savepoints preserve error isolation.
        with self.database:
            self.database.execute("BEGIN IMMEDIATE")
            self._batch_active = True
            try:
                yield
            finally:
                self._batch_active = False

    @contextmanager
    def _transaction(self):
        if not self._batch_active:
            with self.database:
                self.database.execute("BEGIN IMMEDIATE")
                yield
            return
        if not self.database.in_transaction:
            raise _BatchInvalidated("Operation batch transaction was lost")
        self.database.execute("SAVEPOINT native_operation")
        try:
            yield
        except BaseException:
            try:
                self.database.execute("ROLLBACK TO native_operation")
                self.database.execute("RELEASE native_operation")
                if not self.database.in_transaction:
                    raise _BatchInvalidated("Operation batch transaction was lost")
            except sqlite3.Error:
                raise _BatchInvalidated("Operation batch savepoint was lost") from None
            raise
        else:
            try:
                self.database.execute("RELEASE native_operation")
                if not self.database.in_transaction:
                    raise _BatchInvalidated("Operation batch transaction was lost")
            except sqlite3.Error:
                raise _BatchInvalidated("Operation batch savepoint was lost") from None

    def begin(self, account: str, request_id: str, method: str, target: str, body: Any, *, agent_id: str | None = None) -> dict[str, Any] | None:
        digest = hashlib.sha256((method + "\n" + target + "\n" + json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))).encode()).hexdigest()
        with self._transaction():
            row = self.database.execute("SELECT digest,receipt FROM native_operations WHERE account=? AND request_id=?", (account, request_id)).fetchone()
            if row is not None:
                if row[0] != digest:
                    raise OperationConflict("IDEMPOTENCY_CONFLICT")
                if row[1] is None:
                    raise OperationConflict("OUTCOME_UNKNOWN")
                return json.loads(row[1])
            self.database.execute("INSERT INTO native_operations VALUES (?,?,?,NULL)", (account, request_id, digest))
            if agent_id is not None:
                self.database.execute("INSERT INTO native_operation_scopes VALUES (?,?,?)", (account, request_id, agent_id))
        return None

    def complete(self, account: str, request_id: str, receipt: dict[str, Any]) -> None:
        with self._transaction():
            self.database.execute("UPDATE native_operations SET receipt=? WHERE account=? AND request_id=?", (json.dumps(receipt, ensure_ascii=False), account, request_id))

    def lookup(self, account: str, request_id: str, agent_id: str) -> dict[str, Any] | None:
        row = self.database.execute(
            "SELECT o.receipt FROM native_operations o JOIN native_operation_scopes s "
            "ON s.account=o.account AND s.request_id=o.request_id "
            "WHERE o.account=? AND o.request_id=? AND s.agent_id=?",
            (account, request_id, agent_id),
        ).fetchone()
        if row is None:
            return None
        return {"state": "unknown"} if row[0] is None else {"state": "resolved", "receipt": json.loads(row[0])}

    def count(self) -> int:
        return int(self.database.execute("SELECT COUNT(*) FROM native_operations").fetchone()[0])

    def close(self) -> None:
        self.database.close()


class AsyncNativeOperations:
    """The existing ledger, owned by one bounded worker instead of the I/O loop.

    Cancellation does not release admission capacity until the actual SQLite
    job finishes. No connection or cursor crosses the owning thread boundary.
    """
    def __init__(self, path: Path | None = None, *, capacity: int = 64):
        self._path = path
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="native-operations")
        self._slots = asyncio.Semaphore(capacity)
        self._store: NativeOperations | None = None
        self._closed = False
        self._pending = 0
        self._jobs = deque()
        self._jobs_lock = threading.Lock()
        self._scheduled = False

    def _drain(self) -> None:
        while True:
            with self._jobs_lock:
                if not self._jobs:
                    self._scheduled = False
                    return
                jobs = [self._jobs.popleft() for _ in range(min(len(self._jobs), 64))]
            outcomes = []
            started = time.monotonic_ns()
            try:
                if self._store is None:
                    self._store = NativeOperations(self._path)
                with self._store.batch():
                    for method, args, kwargs, context, future, queued in jobs:
                        try:
                            value = context.run(getattr(self._store, method), *args, **kwargs)
                            outcomes.append((value, None))
                        except _BatchInvalidated:
                            raise
                        except Exception as failure:
                            if not self._store.database.in_transaction:
                                raise _BatchInvalidated("Operation batch transaction was lost") from None
                            outcomes.append((None, failure))
                # No observer can see a successful result before the one real
                # commit above. A failed commit makes every result uncertain.
            except Exception as failure:
                outcomes = [(None, failure)] * len(jobs)
            deliveries = {}
            for job, (value, failure) in zip(jobs, outcomes):
                method, _args, _kwargs, context, future, queued = job
                context.run(trace_native_stage, "core.operation.storage", operation=method,
                    started_ns=started, queue_wait_ms=round((started - queued) / 1_000_000, 3),
                    pending_operations=len(jobs))
                deliveries.setdefault(future.get_loop(), []).append((future, value, failure))
            for loop, results in deliveries.items():
                try:
                    loop.call_soon_threadsafe(self._deliver, results)
                except RuntimeError:
                    # A closed process observer cannot undo the durable commit.
                    pass

    @staticmethod
    def _deliver(results) -> None:
        for future, value, failure in results:
            if future.done():
                continue
            if failure is None:
                future.set_result(value)
            else:
                future.set_exception(failure)

    async def _call(self, method: str, *args: Any, **kwargs: Any) -> Any:
        if self._closed:
            raise RuntimeError("Operation ledger is closed")
        queued = time.monotonic_ns()
        await self._slots.acquire()
        if self._closed:
            self._slots.release()
            raise RuntimeError("Operation ledger is closed")
        self._pending += 1
        context = copy_context()
        pending = asyncio.get_running_loop().create_future()
        try:
            with self._jobs_lock:
                self._jobs.append((method, args, kwargs, context, pending, queued))
                if not self._scheduled:
                    self._scheduled = True
                    self._executor.submit(self._drain)
        except BaseException:
            self._pending -= 1
            self._slots.release()
            raise
        result = pending
        def completed(future: asyncio.Future) -> None:
            # The wrapped future finishes only after the real SQLite job. One
            # loop callback both releases admission and consumes exceptions;
            # cancelled HTTP waiters never cancel this shielded inner future.
            self._pending -= 1
            self._slots.release()
            if not future.cancelled():
                future.exception()
        result.add_done_callback(completed)
        return await asyncio.shield(result)

    async def begin(self, account: str, request_id: str, method: str, target: str, body: Any,
                    *, agent_id: str | None = None) -> dict[str, Any] | None:
        return await self._call("begin", account, request_id, method, target, body, agent_id=agent_id)

    async def complete(self, account: str, request_id: str, receipt: dict[str, Any]) -> None:
        await self._call("complete", account, request_id, receipt)

    async def lookup(self, account: str, request_id: str, agent_id: str) -> dict[str, Any] | None:
        return await self._call("lookup", account, request_id, agent_id)

    async def count(self) -> int:
        return await self._call("count")

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        def close_store() -> None:
            if self._store is not None:
                self._store.close()
        pending = self._executor.submit(close_store)
        try:
            await asyncio.shield(asyncio.wrap_future(pending))
        finally:
            self._executor.shutdown(wait=False)
