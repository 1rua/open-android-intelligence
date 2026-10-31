"""Durable native-operation receipts; never a copy of native session history."""
from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextvars import copy_context
import hashlib
import json
from pathlib import Path
import sqlite3
import time
from typing import Any

from .native_diagnostics import trace_native_stage


class OperationConflict(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class NativeOperations:
    def __init__(self, path: Path | None = None):
        if path is not None:
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.database = sqlite3.connect(str(path) if path is not None else ":memory:")
        if path is not None:
            path.chmod(0o600)
        self.database.execute("CREATE TABLE IF NOT EXISTS native_operations (account TEXT NOT NULL, request_id TEXT NOT NULL, digest TEXT NOT NULL, receipt TEXT, PRIMARY KEY(account,request_id))")
        self.database.execute("CREATE TABLE IF NOT EXISTS native_operation_scopes ("
                              "account TEXT NOT NULL, request_id TEXT NOT NULL, agent_id TEXT NOT NULL, "
                              "PRIMARY KEY(account,request_id))")
        self.database.commit()

    def begin(self, account: str, request_id: str, method: str, target: str, body: Any, *, agent_id: str | None = None) -> dict[str, Any] | None:
        digest = hashlib.sha256((method + "\n" + target + "\n" + json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))).encode()).hexdigest()
        with self.database:
            self.database.execute("BEGIN IMMEDIATE")
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
        with self.database:
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

    def _run(self, method: str, *args: Any, **kwargs: Any) -> Any:
        if self._store is None:
            self._store = NativeOperations(self._path)
        started = time.monotonic_ns()
        try:
            return getattr(self._store, method)(*args, **kwargs)
        finally:
            trace_native_stage("core.operation.storage", operation=method, started_ns=started)

    async def _call(self, method: str, *args: Any, **kwargs: Any) -> Any:
        if self._closed:
            raise RuntimeError("Operation ledger is closed")
        queued = time.monotonic_ns()
        await self._slots.acquire()
        if self._closed:
            self._slots.release()
            raise RuntimeError("Operation ledger is closed")
        loop = asyncio.get_running_loop()
        self._pending += 1
        trace_native_stage("core.operation.queued", operation=method, pending_operations=self._pending)
        context = copy_context()
        def work() -> Any:
            trace_native_stage("core.operation.dequeued", operation=method, started_ns=queued)
            return self._run(method, *args, **kwargs)
        try:
            pending = self._executor.submit(context.run, work)
        except BaseException:
            self._pending -= 1
            self._slots.release()
            raise
        def release() -> None:
            self._pending -= 1
            self._slots.release()
        def finished(_future: Any) -> None:
            try:
                loop.call_soon_threadsafe(release)
            except RuntimeError:
                pass
        pending.add_done_callback(finished)
        result = asyncio.wrap_future(pending)
        result.add_done_callback(lambda future: future.exception() if not future.cancelled() else None)
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
