"""Durable account login families and authenticated refresh-result replay."""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import hmac
import json
import secrets
import sqlite3
import time
from typing import Any

from .gateway.core import GatewayError


ACCESS_LIFETIME = 15 * 60
RECEIPT_LIFETIME = 15 * 60


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def iso(value: float) -> str:
    return datetime.fromtimestamp(value, timezone.utc).isoformat()


def _text(value: Any, limit: int = 256) -> bool:
    return isinstance(value, str) and 0 < len(value) <= limit and not any(ord(c) < 32 for c in value)


class AccountSessions:
    """Use SQLite transactions for rotation; never store credentials in plaintext."""

    def __init__(self, core: Any, database: sqlite3.Connection):
        self.core = core
        self.database = database
        self.database.executescript("""
            CREATE TABLE IF NOT EXISTS account_client_bindings (
                account_id TEXT NOT NULL, client_id TEXT NOT NULL, proof_digest TEXT NOT NULL,
                PRIMARY KEY(account_id, client_id));
            CREATE TABLE IF NOT EXISTS account_token_families (
                family_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, client_id TEXT NOT NULL,
                auth_epoch INTEGER NOT NULL, created REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS account_access_tokens (
                digest TEXT PRIMARY KEY, family_id TEXT NOT NULL, expires REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS account_refresh_credentials (
                digest TEXT PRIMARY KEY, family_id TEXT NOT NULL, consumed INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS account_refresh_receipts (
                credential_digest TEXT NOT NULL, operation_id TEXT NOT NULL, successor_digest TEXT NOT NULL,
                expires REAL NOT NULL, ciphertext BLOB NOT NULL,
                PRIMARY KEY(credential_digest, operation_id));
        """)

    @contextmanager
    def _transaction(self):
        self.database.execute("BEGIN IMMEDIATE")
        try:
            yield
            self.database.commit()
        except BaseException:
            self.database.rollback()
            raise

    def _identity(self):
        identity = getattr(self.core, "identity_store", None)
        if identity is None:
            raise GatewayError("IDENTITY_DATABASE_MISSING")
        return identity

    def _aead(self):
        try:
            store = self.core.secret_store
            return store.get_or_create_aead("oai-core:account-refresh-receipt:v1")
        except Exception as exc:
            raise GatewayError("REFRESH_KEY_UNAVAILABLE") from exc

    @staticmethod
    def _associated(family: sqlite3.Row, credential_digest: str, operation_id: str) -> bytes:
        return json.dumps(["oai-core:account-refresh:v1", family["family_id"], family["account_id"],
                           family["client_id"], credential_digest, operation_id],
                          ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    @staticmethod
    def _new_bundle(account_id: str, family_id: str, client_id: str) -> tuple[dict[str, Any], float]:
        token = secrets.token_urlsafe(32)
        expires = time.time() + ACCESS_LIFETIME
        return {"token": token, "accessToken": token, "expiresAt": iso(expires),
                "refreshCredential": secrets.token_urlsafe(32), "loginSessionId": family_id,
                "clientId": client_id, "accountId": account_id}, expires

    def issue(self, verified: dict[str, Any], client_id: str, client_proof: str | None) -> dict[str, Any]:
        account_id, epoch = verified["accountId"], verified["authEpoch"]
        identity = self._identity()
        with identity.transaction():
            identity.account(account_id)
            identity.assert_epoch(account_id, epoch)
            with self._transaction():
                binding = self.database.execute(
                    "SELECT proof_digest FROM account_client_bindings WHERE account_id=? AND client_id=?",
                    (account_id, client_id)).fetchone()
                if binding is not None:
                    if not client_proof or not hmac.compare_digest(binding[0], digest(client_proof)):
                        raise GatewayError("CLIENT_PROOF_REQUIRED")
                    proof = client_proof
                else:
                    if client_proof is not None:
                        raise GatewayError("CLIENT_PROOF_INVALID")
                    proof = secrets.token_urlsafe(32)
                    self.database.execute("INSERT INTO account_client_bindings VALUES (?,?,?)",
                                          (account_id, client_id, digest(proof)))
                family_id = "login_" + secrets.token_urlsafe(24)
                self.database.execute("INSERT INTO account_token_families VALUES (?,?,?,?,?,0)",
                                      (family_id, account_id, client_id, epoch, time.time()))
                bundle, expires = self._new_bundle(account_id, family_id, client_id)
                self.database.execute("INSERT INTO account_access_tokens VALUES (?,?,?)",
                                      (digest(bundle["token"]), family_id, expires))
                self.database.execute("INSERT INTO account_refresh_credentials VALUES (?,?,0)",
                                      (digest(bundle["refreshCredential"]), family_id))
        bundle["clientProof"] = proof
        return bundle

    def _family_for_refresh(self, credential: str, client_id: str) -> sqlite3.Row:
        row = self.database.execute("""
            SELECT f.*, r.digest AS credential_digest, r.consumed
            FROM account_refresh_credentials r JOIN account_token_families f ON f.family_id=r.family_id
            WHERE r.digest=? AND f.client_id=?
        """, (digest(credential), client_id)).fetchone()
        if row is None:
            raise GatewayError("AUTHENTICATION_REQUIRED")
        return row

    def refresh(self, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict) or set(body) != {"refreshCredential", "clientId", "operationId"} or not all(_text(body[k], 512) for k in body):
            raise GatewayError("SCHEMA_INVALID")
        credential, client_id, operation_id = body["refreshCredential"], body["clientId"], body["operationId"]
        identity = self._identity()
        initial = self._family_for_refresh(credential, client_id)
        replay_attack = False
        with identity.transaction():
            identity.account(initial["account_id"])
            identity.assert_epoch(initial["account_id"], initial["auth_epoch"])
            with self._transaction():
                family = self._family_for_refresh(credential, client_id)
                if family["revoked"]:
                    raise GatewayError("AUTHENTICATION_REQUIRED")
                credential_digest = family["credential_digest"]
                if family["consumed"]:
                    receipt = self.database.execute("""
                        SELECT * FROM account_refresh_receipts WHERE credential_digest=? AND operation_id=?
                    """, (credential_digest, operation_id)).fetchone()
                    successor = None if receipt is None else self.database.execute(
                        "SELECT consumed FROM account_refresh_credentials WHERE digest=? AND family_id=?",
                        (receipt["successor_digest"], family["family_id"])).fetchone()
                    now = time.time()
                    if (receipt is not None and receipt["expires"] - RECEIPT_LIFETIME <= now < receipt["expires"]
                            and successor is not None and not successor[0]):
                        try:
                            payload = self._aead().decrypt(receipt["ciphertext"], self._associated(family, credential_digest, operation_id))
                            bundle = json.loads(payload)
                        except GatewayError:
                            raise
                        except Exception as exc:
                            raise GatewayError("REFRESH_RECEIPT_INVALID") from exc
                    elif receipt is not None:
                        # An exact but stale retry is not evidence of theft; never
                        # return credentials that are expired or already rotated.
                        raise GatewayError("AUTHENTICATION_REQUIRED")
                    else:
                        self.database.execute("UPDATE account_token_families SET revoked=1 WHERE family_id=?", (family["family_id"],))
                        replay_attack = True
                else:
                    # Obtain the AEAD before consuming the old credential, and persist
                    # the encrypted receipt in the same transaction as its successor.
                    provider = self._aead()
                    bundle, expires = self._new_bundle(family["account_id"], family["family_id"], client_id)
                    successor_digest = digest(bundle["refreshCredential"])
                    receipt = provider.encrypt(json.dumps(bundle, separators=(",", ":")).encode("utf-8"),
                                               self._associated(family, credential_digest, operation_id))
                    self.database.execute("UPDATE account_refresh_credentials SET consumed=1 WHERE digest=?", (credential_digest,))
                    self.database.execute("INSERT INTO account_access_tokens VALUES (?,?,?)", (digest(bundle["token"]), family["family_id"], expires))
                    self.database.execute("INSERT INTO account_refresh_credentials VALUES (?,?,0)", (successor_digest, family["family_id"]))
                    self.database.execute("INSERT INTO account_refresh_receipts VALUES (?,?,?,?,?)",
                                          (credential_digest, operation_id, successor_digest, time.time() + RECEIPT_LIFETIME, receipt))
        if replay_attack:
            raise GatewayError("REFRESH_REPLAY_DETECTED")
        return bundle

    def revoke_refresh(self, body: Any) -> None:
        if not isinstance(body, dict) or set(body) != {"refreshCredential", "clientId"} or not all(_text(body[k], 512) for k in body):
            raise GatewayError("SCHEMA_INVALID")
        with self._transaction():
            family = self._family_for_refresh(body["refreshCredential"], body["clientId"])
            self.database.execute("UPDATE account_token_families SET revoked=1 WHERE family_id=?", (family["family_id"],))

    def revoke_family(self, family_id: str) -> None:
        with self._transaction():
            self.database.execute("UPDATE account_token_families SET revoked=1 WHERE family_id=?", (family_id,))

    def assert_family(self, account_id: str, family_id: str) -> sqlite3.Row:
        family = self.database.execute("SELECT * FROM account_token_families WHERE account_id=? AND family_id=?",
                                       (account_id, family_id)).fetchone()
        if family is None or family["revoked"]:
            raise GatewayError("AUTHENTICATION_REQUIRED")
        self._identity().assert_epoch(account_id, family["auth_epoch"])
        return family

    def access(self, token_digest: str) -> sqlite3.Row | None:
        row = self.database.execute("""
            SELECT a.expires, f.* FROM account_access_tokens a
            JOIN account_token_families f ON f.family_id=a.family_id WHERE a.digest=?
        """, (token_digest,)).fetchone()
        if row is None:
            return None
        self.assert_family(row["account_id"], row["family_id"])
        if row["expires"] <= time.time():
            raise GatewayError("SESSION_EXPIRED")
        return row
