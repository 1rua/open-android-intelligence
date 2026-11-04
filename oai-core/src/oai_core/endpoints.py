"""Leased, credential-owned push routes and short-lived encrypted delivery buffers.

Android owns consent and the long-lived queue. This module authenticates routes,
retains deduplication/ACK facts, and never interprets a label as an identity.
"""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
from typing import Any, Iterator
import uuid

from aiohttp import web

from .auth import Authentication, Principal
from .gateway.core import GatewayError, _failure, _jcs, _now, _success, iso_millis

CORE_PREFIX = "/open-android-intelligence/core/v1"
PUSH_PREFIX = "/open-android-intelligence/v2/push"
PAYLOAD_TTL_SECONDS = 60
MAX_PAYLOAD_BYTES = 65536
MAX_ACCOUNT_BUFFER_BYTES = 1024 * 1024
MAX_ACCOUNT_PENDING = 128
MAX_ACCOUNT_ENDPOINTS = 64
MAX_EVENT_SECONDS = 7 * 86400


def timestamp(value: float) -> str:
    return iso_millis(datetime.fromtimestamp(value, timezone.utc))


class EndpointService:
    def __init__(self, core: Any, authentication: Authentication, database: Path):
        self.core, self.authentication = core, authentication
        self.database = sqlite3.connect(database, isolation_level=None)
        database.chmod(0o600)
        self.database.row_factory = sqlite3.Row
        self.database.execute("PRAGMA journal_mode=WAL")
        self.database.execute("PRAGMA synchronous=FULL")
        self.database.execute("PRAGMA secure_delete=ON")
        self.database.executescript("""
            CREATE TABLE IF NOT EXISTS push_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS push_endpoints (
                endpoint_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, device_id TEXT NOT NULL,
                pairing_generation INTEGER NOT NULL, registration_key TEXT NOT NULL,
                owner_token TEXT NOT NULL, generation INTEGER NOT NULL, label TEXT NOT NULL,
                lease_expires REAL NOT NULL, retired INTEGER NOT NULL DEFAULT 0,
                UNIQUE(account_id, device_id, pairing_generation, registration_key)
            );
            CREATE TABLE IF NOT EXISTS push_deliveries (
                delivery_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, device_id TEXT NOT NULL,
                pairing_generation INTEGER NOT NULL, endpoint_id TEXT NOT NULL,
                endpoint_generation INTEGER NOT NULL, event_id TEXT NOT NULL,
                digest TEXT NOT NULL, event_type TEXT NOT NULL, occurred_at TEXT NOT NULL,
                expires_at REAL NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL DEFAULT '',
                payload_bytes INTEGER NOT NULL DEFAULT 0, payload_expires REAL,
                received_at TEXT, acknowledged_at TEXT,
                UNIQUE(account_id,device_id,pairing_generation,endpoint_id,endpoint_generation,event_id)
            );
            CREATE INDEX IF NOT EXISTS push_delivery_pending ON push_deliveries(endpoint_id,state);
            CREATE TABLE IF NOT EXISTS push_revocations (
                account_id TEXT NOT NULL, device_id TEXT NOT NULL,
                pairing_generation INTEGER NOT NULL, endpoint_id TEXT NOT NULL,
                endpoint_generation INTEGER NOT NULL, event_id TEXT NOT NULL,
                delivery_id TEXT NOT NULL UNIQUE, expires_at REAL NOT NULL,
                PRIMARY KEY(account_id,device_id,pairing_generation,endpoint_id,endpoint_generation,event_id)
            );
            CREATE INDEX IF NOT EXISTS push_revocation_expiry ON push_revocations(expires_at);
        """)
        with self.transaction():
            self.database.execute("INSERT OR IGNORE INTO push_metadata VALUES ('coreInstanceId',?)", ("core_" + uuid.uuid4().hex,))
            self.instance_id = self.database.execute("SELECT value FROM push_metadata WHERE key='coreInstanceId'").fetchone()[0]

    def close(self) -> None:
        self.database.close()

    @contextmanager
    def transaction(self) -> Iterator[None]:
        self.database.execute("BEGIN IMMEDIATE")
        try:
            yield
            self.database.execute("COMMIT")
        except BaseException:
            if self.database.in_transaction:
                self.database.execute("ROLLBACK")
            raise

    def _validate(self, schema: str, value: Any) -> None:
        if not self.core.contracts.validate(schema, value):
            raise GatewayError("SCHEMA_INVALID")

    def _scope(self, account_id: str) -> str:
        return "acct_" + hashlib.sha256(_jcs([self.instance_id, account_id]).encode()).hexdigest()[:32]

    def _route(self, endpoint: sqlite3.Row, generation: int | None = None) -> dict[str, Any]:
        return {"coreInstanceId": self.instance_id, "accountScope": self._scope(endpoint["account_id"]),
                "endpointId": endpoint["endpoint_id"], "generation": generation if generation is not None else endpoint["generation"]}

    def _descriptor(self, row: sqlite3.Row) -> dict[str, Any]:
        return {"route": self._route(row), "label": row["label"], "leaseExpiresAt": timestamp(row["lease_expires"])}

    def _active(self, endpoint: sqlite3.Row) -> bool:
        if endpoint["retired"] or endpoint["lease_expires"] <= _now().timestamp():
            return False
        token = self.authentication.database.execute("SELECT * FROM core_tokens WHERE digest=?", (endpoint["owner_token"],)).fetchone()
        if token is None:
            return False
        if (token["account_id"], token["device_id"], token["generation"]) != (endpoint["account_id"], endpoint["device_id"], endpoint["pairing_generation"]):
            return False
        try:
            self.authentication.assert_current(Principal(token["account_id"], device_id=token["device_id"],
                pairing_generation=token["generation"], grant_revision=token["revision"], token_id=token["digest"]))
        except GatewayError:
            return False
        return True

    def _endpoint(self, principal: Principal, endpoint_id: str, *, owner: bool = False) -> sqlite3.Row:
        self.authentication.assert_current(principal)
        row = self.database.execute("SELECT * FROM push_endpoints WHERE endpoint_id=?", (endpoint_id,)).fetchone()
        if (row is None or row["account_id"] != principal.account_id or row["device_id"] != principal.device_id
                or row["pairing_generation"] != principal.pairing_generation
                or owner and row["owner_token"] != principal.token_id):
            raise GatewayError("ENDPOINT_ACCESS_DENIED")
        return row

    def _release(self, where: str, parameters: tuple, state: str) -> None:
        self.database.execute("UPDATE push_deliveries SET state=?,payload='',payload_bytes=0,payload_expires=NULL WHERE " + where + " AND state IN ('pending','payload_expired')", (state, *parameters))

    def sweep(self) -> None:
        now = _now().timestamp()
        with self.transaction():
            self._release("expires_at<=?", (now,), "expired")
            self.database.execute("DELETE FROM push_revocations WHERE rowid IN (SELECT rowid FROM push_revocations WHERE expires_at<=? ORDER BY expires_at LIMIT 1000)", (now,))
            self.database.execute("UPDATE push_deliveries SET state='payload_expired',payload='',payload_bytes=0,payload_expires=NULL WHERE state='pending' AND payload_expires<=?", (now,))
            for row in self.database.execute("SELECT * FROM push_endpoints WHERE retired=0").fetchall():
                if not self._active(row):
                    self._release("endpoint_id=? AND endpoint_generation=?", (row["endpoint_id"], row["generation"]), "revoked")
        # Checkpoint freed ciphertext pages rather than retaining it in the WAL.
        self.database.execute("PRAGMA wal_checkpoint(TRUNCATE)")

    def register(self, principal: Principal, body: Any) -> dict[str, Any]:
        self._validate("push.registration", body)
        self.authentication.assert_current(principal)
        if principal.token_id is None:
            raise GatewayError("AUTHENTICATION_REQUIRED")
        self.sweep()
        with self.transaction():
            row = self.database.execute("SELECT * FROM push_endpoints WHERE account_id=? AND device_id=? AND pairing_generation=? AND registration_key=?",
                (principal.account_id, principal.device_id, principal.pairing_generation, body["registrationKey"])).fetchone()
            if row is None or not self._active(row):
                now = _now().timestamp()
                active_rows = self.database.execute("SELECT * FROM push_endpoints WHERE account_id=? AND retired=0 AND lease_expires>?",
                    (principal.account_id, now)).fetchall()
                active_count = sum(1 for active_row in active_rows if self._active(active_row))
                if active_count >= MAX_ACCOUNT_ENDPOINTS:
                    raise GatewayError("ENDPOINT_CAPACITY_EXCEEDED")
            if row is None:
                endpoint_id, generation = "endpoint_" + uuid.uuid4().hex, 1
            else:
                endpoint_id = row["endpoint_id"]
                generation = row["generation"] + int(not self._active(row) or row["owner_token"] != principal.token_id)
                if generation != row["generation"]:
                    self._release("endpoint_id=?", (endpoint_id,), "revoked")
            expires = _now().timestamp() + body.get("ttlSeconds", 3600)
            self.database.execute("INSERT INTO push_endpoints VALUES (?,?,?,?,?,?,?,?,?,0) ON CONFLICT(endpoint_id) DO UPDATE SET owner_token=excluded.owner_token,generation=excluded.generation,label=excluded.label,lease_expires=excluded.lease_expires,retired=0",
                (endpoint_id, principal.account_id, principal.device_id, principal.pairing_generation, body["registrationKey"], principal.token_id, generation, body["label"].strip(), expires))
            return self._descriptor(self._endpoint(principal, endpoint_id, owner=True))

    def discover(self, principal: Principal) -> dict[str, Any]:
        self.authentication.assert_current(principal)
        self.sweep()
        rows = self.database.execute("SELECT * FROM push_endpoints WHERE account_id=? AND device_id=? AND pairing_generation=? ORDER BY endpoint_id",
            (principal.account_id, principal.device_id, principal.pairing_generation)).fetchall()
        return {"coreInstanceId": self.instance_id, "accountScope": self._scope(principal.account_id),
                "endpoints": [self._descriptor(row) for row in rows if self._active(row)]}

    def unregister(self, principal: Principal, endpoint_id: str) -> dict[str, Any]:
        with self.transaction():
            row = self._endpoint(principal, endpoint_id, owner=True)
            self.database.execute("UPDATE push_endpoints SET retired=1 WHERE endpoint_id=?", (endpoint_id,))
            self._release("endpoint_id=?", (endpoint_id,), "revoked")
            return {"route": self._route(row), "retired": True}

    def _delivery(self, principal: Principal, delivery_id: str) -> sqlite3.Row:
        self.authentication.assert_current(principal)
        row = self.database.execute("SELECT * FROM push_deliveries WHERE delivery_id=?", (delivery_id,)).fetchone()
        if (row is None or row["account_id"] != principal.account_id or row["device_id"] != principal.device_id
                or row["pairing_generation"] != principal.pairing_generation):
            raise GatewayError("DELIVERY_ACCESS_DENIED")
        return row

    def _receipt(self, row: sqlite3.Row) -> dict[str, Any]:
        endpoint = self.database.execute("SELECT * FROM push_endpoints WHERE endpoint_id=?", (row["endpoint_id"],)).fetchone()
        return {"deliveryId": row["delivery_id"], "eventId": row["event_id"], "route": self._route(endpoint, row["endpoint_generation"]),
                "state": row["state"], "payloadExpiresAt": timestamp(row["payload_expires"]) if row["payload_expires"] else None,
                "acknowledgedAt": row["acknowledged_at"]}

    def _revocation_receipt(self, row: sqlite3.Row, endpoint: sqlite3.Row) -> dict[str, Any]:
        return {"deliveryId": row["delivery_id"], "eventId": row["event_id"],
                "route": self._route(endpoint, row["endpoint_generation"]), "state": "revoked",
                "payloadExpiresAt": None, "acknowledgedAt": None}

    def submit(self, principal: Principal, body: Any) -> dict[str, Any]:
        self._validate("push.delivery", body)
        self.authentication.assert_current(principal)
        route = body["route"]
        if route["coreInstanceId"] != self.instance_id or route["accountScope"] != self._scope(principal.account_id):
            raise GatewayError("ENDPOINT_ROUTE_MISMATCH")
        encoded = _jcs(body["payload"])
        if len(encoded.encode()) > MAX_PAYLOAD_BYTES:
            raise GatewayError("PUSH_PAYLOAD_TOO_LARGE")
        digest = hashlib.sha256(_jcs(body).encode()).hexdigest()
        expires = _now(body["expiresAt"]).timestamp()
        self.sweep()
        with self.transaction():
            row = self.database.execute("SELECT * FROM push_deliveries WHERE account_id=? AND device_id=? AND pairing_generation=? AND endpoint_id=? AND endpoint_generation=? AND event_id=?",
                (principal.account_id, principal.device_id, principal.pairing_generation, route["endpointId"], route["generation"], body["eventId"])).fetchone()
            if row is not None:
                if row["digest"] != digest:
                    raise GatewayError("IDEMPOTENCY_CONFLICT")
                if row["state"] != "payload_expired":
                    return self._receipt(row)
            endpoint = self._endpoint(principal, route["endpointId"])
            revoked = self.database.execute(
                "SELECT * FROM push_revocations WHERE account_id=? AND device_id=? AND pairing_generation=? "
                "AND endpoint_id=? AND endpoint_generation=? AND event_id=? AND expires_at>?",
                (principal.account_id, principal.device_id, principal.pairing_generation,
                 route["endpointId"], route["generation"], body["eventId"], _now().timestamp()),
            ).fetchone()
            if revoked is not None:
                # Revoke may commit before the in-flight event ever arrives.
                # Return only its durable metadata, before encrypting/storing
                # any content or treating this route as newly authorized.
                return self._revocation_receipt(revoked, endpoint)
            if route != self._route(endpoint):
                raise GatewayError("ENDPOINT_ROUTE_MISMATCH")
            if not self._active(endpoint):
                raise GatewayError("ENDPOINT_UNAVAILABLE")
            now = _now().timestamp()
            occurred = _now(body["occurredAt"]).timestamp()
            if expires <= now or expires <= occurred or expires > now + MAX_EVENT_SECONDS or occurred > now + 300:
                raise GatewayError("EVENT_EXPIRED" if expires <= now else "SCHEMA_INVALID")
            count, used = self.database.execute("SELECT count(*),coalesce(sum(payload_bytes),0) FROM push_deliveries WHERE account_id=? AND state='pending'", (principal.account_id,)).fetchone()
            if count >= MAX_ACCOUNT_PENDING or used + len(encoded.encode()) > MAX_ACCOUNT_BUFFER_BYTES:
                raise GatewayError("PUSH_BUFFER_FULL")
            delivery_id = row["delivery_id"] if row else "delivery_" + uuid.uuid4().hex
            account = self.core.open_gateway_account(principal.account_id)
            try:
                sealed = account.store.seal_json(body["payload"], f"push:{self.instance_id}:{principal.account_id}:{delivery_id}")
            finally:
                account.close()
            payload_expires = min(now + PAYLOAD_TTL_SECONDS, expires, endpoint["lease_expires"])
            self.database.execute("INSERT INTO push_deliveries(delivery_id,account_id,device_id,pairing_generation,endpoint_id,endpoint_generation,event_id,digest,event_type,occurred_at,expires_at,state,payload,payload_bytes,payload_expires) VALUES (?,?,?,?,?,?,?,?,?,?,?,'pending',?,?,?) ON CONFLICT(delivery_id) DO UPDATE SET state='pending',payload=excluded.payload,payload_bytes=excluded.payload_bytes,payload_expires=excluded.payload_expires",
                (delivery_id, principal.account_id, principal.device_id, principal.pairing_generation, route["endpointId"], route["generation"], body["eventId"], digest, body["eventType"], body["occurredAt"], expires, sealed, len(encoded.encode()), payload_expires))
            return self._receipt(self._delivery(principal, delivery_id))

    def status(self, principal: Principal, delivery_id: str) -> dict[str, Any]:
        self.sweep()
        self.authentication.assert_current(principal)
        revoked = self.database.execute(
            "SELECT * FROM push_revocations WHERE delivery_id=? AND account_id=? AND device_id=? "
            "AND pairing_generation=? AND expires_at>?",
            (delivery_id, principal.account_id, principal.device_id, principal.pairing_generation, _now().timestamp()),
        ).fetchone()
        if revoked is not None:
            return self._revocation_receipt(revoked, self._endpoint(principal, revoked["endpoint_id"]))
        return self._receipt(self._delivery(principal, delivery_id))

    def receive(self, principal: Principal, endpoint_id: str) -> dict[str, Any]:
        self.sweep()
        with self.transaction():
            endpoint = self._endpoint(principal, endpoint_id, owner=True)
            if not self._active(endpoint):
                raise GatewayError("ENDPOINT_UNAVAILABLE")
            rows = self.database.execute("SELECT * FROM push_deliveries WHERE endpoint_id=? AND endpoint_generation=? AND state='pending' ORDER BY rowid LIMIT 32", (endpoint_id, endpoint["generation"])).fetchall()
            deliveries = []
            account = self.core.open_gateway_account(principal.account_id)
            try:
                for row in rows:
                    data = account.store.open_json(row["payload"], f"push:{self.instance_id}:{principal.account_id}:{row['delivery_id']}")
                    deliveries.append({**self._receipt(row), "eventType": row["event_type"], "occurredAt": row["occurred_at"],
                                       "expiresAt": timestamp(row["expires_at"]), "payload": data})
                    self.database.execute("UPDATE push_deliveries SET received_at=coalesce(received_at,?) WHERE delivery_id=?", (iso_millis(), row["delivery_id"]))
            finally:
                account.close()
            return {"deliveries": deliveries}

    def acknowledge(self, principal: Principal, endpoint_id: str, delivery_id: str, body: Any) -> dict[str, Any]:
        self._validate("push.ack", body)
        self.sweep()
        with self.transaction():
            endpoint = self._endpoint(principal, endpoint_id, owner=True)
            row = self._delivery(principal, delivery_id)
            if row["endpoint_id"] != endpoint_id or row["event_id"] != body["eventId"]:
                raise GatewayError("DELIVERY_ACCESS_DENIED")
            if row["state"] == "acknowledged":
                return self._receipt(row)
            if not self._active(endpoint) or row["endpoint_generation"] != endpoint["generation"]:
                raise GatewayError("ENDPOINT_UNAVAILABLE")
            if row["state"] not in {"pending", "payload_expired"}:
                raise GatewayError("DELIVERY_NOT_ACTIVE")
            if row["received_at"] is None:
                raise GatewayError("DELIVERY_NOT_RECEIVED")
            self.database.execute("UPDATE push_deliveries SET state='acknowledged',payload='',payload_bytes=0,payload_expires=NULL,acknowledged_at=? WHERE delivery_id=?", (iso_millis(), delivery_id))
            return self._receipt(self._delivery(principal, delivery_id))

    def revoke(self, principal: Principal, body: Any) -> dict[str, Any]:
        self._validate("push.revoke", body)
        route = body["route"]
        endpoint = self._endpoint(principal, route["endpointId"])
        if route["coreInstanceId"] != self.instance_id or route["accountScope"] != self._scope(principal.account_id):
            raise GatewayError("ENDPOINT_ROUTE_MISMATCH")
        with self.transaction():
            where = "account_id=? AND device_id=? AND pairing_generation=? AND endpoint_id=? AND endpoint_generation=?"
            parameters = (principal.account_id, principal.device_id, principal.pairing_generation, endpoint["endpoint_id"], route["generation"])
            if "eventIds" in body:
                # The Android queue selects exactly the events whose consent
                # was withdrawn. Fence even unknown IDs for the full maximum
                # event TTL so a late original POST cannot restore content.
                expires_at = _now().timestamp() + MAX_EVENT_SECONDS
                self.database.executemany(
                    "INSERT INTO push_revocations VALUES (?,?,?,?,?,?,?,?) "
                    "ON CONFLICT(account_id,device_id,pairing_generation,endpoint_id,endpoint_generation,event_id) "
                    "DO UPDATE SET expires_at=MAX(push_revocations.expires_at,excluded.expires_at)",
                    [(*parameters, event_id, "delivery_" + uuid.uuid4().hex, expires_at) for event_id in body["eventIds"]],
                )
                where += " AND event_id IN (" + ",".join("?" for _ in body["eventIds"]) + ")"
                parameters += tuple(body["eventIds"])
            self._release(where, parameters, "revoked")
        return {"route": route, "revoked": True}


def create_endpoint_routes(service: EndpointService, authentication: Authentication) -> web.RouteTableDef:
    routes = web.RouteTableDef()

    def route(method: str, path: str, action: Any, *, phone: bool = False, body: bool = False):
        async def handler(request: web.Request) -> web.Response:
            principal = await authentication.authenticate(request)
            if phone != (principal.context is not None):
                raise GatewayError("AUTHENTICATION_REQUIRED")
            try:
                if request.query:
                    raise GatewayError("SCHEMA_INVALID")
                arguments = [principal, *request.match_info.values()]
                if body:
                    arguments.append(await request.json())
                value = action(*arguments)
                authentication.assert_current(principal)
                if phone:
                    ctx = principal.context
                    value = _success({"requestId": ctx.request_id, "correlationId": ctx.correlation_id}, value)
                return web.json_response(value, headers={"Cache-Control": "no-store"})
            except GatewayError as exc:
                if not phone:
                    raise
                from .gateway.http import _status
                failure = _failure({"requestId": principal.context.request_id, "correlationId": principal.context.correlation_id}, exc.code, exc.details)
                value = {**failure, "error": {**failure["error"], "retryable": exc.code in {"PUSH_BUFFER_FULL", "ENDPOINT_UNAVAILABLE"}}}
                return web.json_response(value, status=_status(value), headers={"Cache-Control": "no-store"})
        routes.route(method, path)(handler)

    route("POST", CORE_PREFIX + "/endpoints", service.register, body=True)
    route("GET", CORE_PREFIX + "/endpoints", service.discover)
    route("DELETE", CORE_PREFIX + "/endpoints/{endpoint_id}", service.unregister)
    route("GET", CORE_PREFIX + "/endpoints/{endpoint_id}/deliveries", service.receive)
    route("POST", CORE_PREFIX + "/endpoints/{endpoint_id}/deliveries/{delivery_id}/ack", service.acknowledge, body=True)
    route("GET", PUSH_PREFIX + "/endpoints", service.discover, phone=True)
    route("POST", PUSH_PREFIX + "/deliveries", service.submit, phone=True, body=True)
    route("POST", PUSH_PREFIX + "/deliveries/revoke", service.revoke, phone=True, body=True)
    route("GET", PUSH_PREFIX + "/deliveries/{delivery_id}", service.status, phone=True)
    return routes
