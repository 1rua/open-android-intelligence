"""Durable native-operation receipts; never a copy of native session history."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3
from typing import Any


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
        self.database.commit()

    def begin(self, account: str, request_id: str, method: str, target: str, body: Any) -> dict[str, Any] | None:
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
        return None

    def complete(self, account: str, request_id: str, receipt: dict[str, Any]) -> None:
        with self.database:
            self.database.execute("UPDATE native_operations SET receipt=? WHERE account=? AND request_id=?", (json.dumps(receipt, ensure_ascii=False), account, request_id))

    def close(self) -> None:
        self.database.close()
