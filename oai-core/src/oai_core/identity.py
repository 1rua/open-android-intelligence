"""Instance-wide identity, administrative authorization and optional MFA.

The constructor never provisions missing state.  Bootstrap and migration are
explicit, exclusive operations; incomplete provisioning is retained and refused.
"""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import stat
import threading
import time
from typing import Any

from . import mfa
from .gateway.account_paths import WIRE_ID_PATTERN, sha256_hex
from .gateway.audit import scrub
from .gateway.core import GatewayError
from .gateway.credentials import hash_password, verify_password

MANAGEMENT_LIFETIME = 900
CHALLENGE_LIFETIME = 300
SCOPES = frozenset({"accounts.read", "accounts.write", "security", "config", "configuration.write", "setup", "install", "service", "uninstall", "agent:manage", "agents.manage"})
_BOOTSTRAP_PROOFS: dict[str, tuple[str, str, float]] = {}
_BOOTSTRAP_LOCK = threading.Lock()


def _error(code: str, details: dict[str, Any] | None = None) -> GatewayError:
    return GatewayError(code, details)


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _iso(value: float) -> str:
    return datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")


def _private_dir(path: Path, *, create: bool = False) -> None:
    if create:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise _error("IDENTITY_PERMISSIONS_INVALID")


def _private_file(path: Path) -> None:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise _error("IDENTITY_PERMISSIONS_INVALID")


def _new_install(database: Path, storage_root: Path) -> None:
    if database.exists() or database.is_symlink():
        raise _error("IDENTITY_ALREADY_INITIALIZED")
    if storage_root.is_symlink() or (storage_root.exists() and not storage_root.is_dir()):
        raise _error("BOOTSTRAP_UNSAFE")
    if storage_root.exists() and any(storage_root.iterdir()):
        raise _error("BOOTSTRAP_NOT_NEW")
    security = database.parent
    if security.is_symlink():
        raise _error("BOOTSTRAP_UNSAFE")
    if security.exists() and any(security.iterdir()):
        raise _error("BOOTSTRAP_NOT_NEW")
    # Any configuration journal or preserved security backup is evidence that
    # this is not a provably new installation; no implicit reset is allowed.
    runtime = storage_root.parent
    for name in ("configuration-journal.json", "configuration-backups", "identity-journal.json"):
        if (runtime / name).exists() or (runtime / name).is_symlink():
            raise _error("BOOTSTRAP_NOT_NEW")


def issue_bootstrap_proof(database: Path, storage_root: Path, *, confirmation_fd: int) -> str:
    """Receive the literal confirmation through a terminal/pipe/socket, not argv.

    Returns an in-process, five-minute, one-use authorization bound to both paths.
    Issuing and consuming it must happen in the same trusted management process.
    """
    database, storage_root = Path(database).absolute(), Path(storage_root).absolute()
    info = os.fstat(confirmation_fd)
    if not (stat.S_ISFIFO(info.st_mode) or stat.S_ISSOCK(info.st_mode) or os.isatty(confirmation_fd)):
        raise _error("BOOTSTRAP_CONFIRMATION_CHANNEL_INVALID")
    value = bytearray()
    while len(value) <= 64:
        char = os.read(confirmation_fd, 1)
        if not char or char == b"\n":
            break
        value.extend(char)
    if bytes(value) != b"bootstrap":
        raise _error("BOOTSTRAP_CONFIRMATION_REQUIRED")
    _new_install(database, storage_root)
    proof = secrets.token_urlsafe(32)
    with _BOOTSTRAP_LOCK:
        now = time.monotonic()
        expired = [key for key, record in _BOOTSTRAP_PROOFS.items() if record[2] <= now]
        for key in expired:
            del _BOOTSTRAP_PROOFS[key]
        _BOOTSTRAP_PROOFS[_hash(proof)] = (str(database), str(storage_root), now + 300)
    return proof


def _consume_bootstrap(proof: str | None, database: Path) -> Path:
    with _BOOTSTRAP_LOCK:
        record = _BOOTSTRAP_PROOFS.pop(_hash(proof), None) if isinstance(proof, str) else None
    if record is None or record[0] != str(database) or time.monotonic() >= record[2]:
        raise _error("BOOTSTRAP_PROOF_REQUIRED")
    storage = Path(record[1])
    _new_install(database, storage)
    return storage


_SCHEMA = """
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE accounts(account_id TEXT PRIMARY KEY, username TEXT UNIQUE, password_hash TEXT,
 role TEXT NOT NULL CHECK(role IN ('admin','user')), status TEXT NOT NULL CHECK(status IN ('active','disabled','deleted')),
 auth_epoch INTEGER NOT NULL, mfa_version INTEGER NOT NULL, mfa_secret BLOB, last_step INTEGER NOT NULL DEFAULT -1,
 local_recovery_generation TEXT);
CREATE TABLE recovery_codes(account_id TEXT NOT NULL REFERENCES accounts(account_id), digest TEXT NOT NULL,
 used INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account_id,digest));
CREATE TABLE challenges(digest TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(account_id),
 purpose TEXT NOT NULL, client_id TEXT, auth_epoch INTEGER NOT NULL, mfa_version INTEGER NOT NULL,
 policy_version INTEGER NOT NULL, issued REAL NOT NULL, expires REAL NOT NULL, failed INTEGER NOT NULL DEFAULT 0,
 completed INTEGER NOT NULL DEFAULT 0, used INTEGER NOT NULL DEFAULT 0);
CREATE TABLE enrollments(challenge_digest TEXT PRIMARY KEY REFERENCES challenges(digest), account_id TEXT NOT NULL,
 enrollment_digest TEXT NOT NULL, encrypted_secret BLOB NOT NULL, expires REAL NOT NULL);
CREATE TABLE management_handles(digest TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(account_id),
 auth_epoch INTEGER NOT NULL, scopes TEXT NOT NULL, issued REAL NOT NULL, expires REAL NOT NULL,
 monotonic_issued REAL NOT NULL, monotonic_expires REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE audit_events(id INTEGER PRIMARY KEY AUTOINCREMENT, at REAL NOT NULL, action TEXT NOT NULL,
 actor TEXT, subject TEXT, metadata TEXT NOT NULL);
"""


class IdentityStore:
    def __init__(self, database: Path, *, key_store: Any = None, create: bool = False, bootstrap_proof: str | None = None):
        self.database_path = Path(database).absolute()
        self.key_store = key_store
        self.database: sqlite3.Connection | None = None
        self._create = create
        self._bootstrap_proof = bootstrap_proof
        self._lock = threading.RLock()
        if self.database_path.exists() or self.database_path.is_symlink():
            self._open()
        elif not create:
            raise _error("IDENTITY_DATABASE_MISSING")

    def _open(self) -> None:
        try:
            _private_dir(self.database_path.parent)
            _private_file(self.database_path)
            self.database = sqlite3.connect(self.database_path.as_uri() + "?mode=rw", uri=True, isolation_level=None, timeout=10, check_same_thread=False)
            self.database.row_factory = sqlite3.Row
            self.database.execute("PRAGMA foreign_keys=ON")
            if self.database.execute("SELECT value FROM metadata WHERE key='schema_version'").fetchone()[0] != "1":
                raise _error("IDENTITY_SCHEMA_UNSUPPORTED")
            self._policy()
            migration = self.database.execute("SELECT value FROM metadata WHERE key='migration_state'").fetchone()
            if migration is not None and migration[0] != "active":
                raise _error("IDENTITY_MIGRATION_RECOVERY_REQUIRED")
            from .identity_migration import read_identity_migration_state
            migration_state = read_identity_migration_state(self.database_path)
            if migration_state is not None and migration_state != "committed":
                raise _error("IDENTITY_MIGRATION_RECOVERY_REQUIRED")
        except (OSError, sqlite3.Error, TypeError, IndexError) as exc:
            self.close()
            raise _error("IDENTITY_DATABASE_INVALID") from exc
        except BaseException:
            self.close()
            raise

    def _initialize(self, accounts: list[tuple[str, str, str | None, str, str]]) -> None:
        if not self._create or self.database is not None:
            raise _error("IDENTITY_ALREADY_INITIALIZED")
        _private_dir(self.database_path.parent, create=True)
        try:
            fd = os.open(self.database_path, os.O_CREAT | os.O_EXCL | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
            os.close(fd)
        except FileExistsError as exc:
            raise _error("IDENTITY_ALREADY_INITIALIZED") from exc
        try:
            self.database = sqlite3.connect(self.database_path, isolation_level=None, timeout=10, check_same_thread=False)
            self.database.row_factory = sqlite3.Row
            self.database.execute("PRAGMA foreign_keys=ON")
            self.database.executescript("BEGIN IMMEDIATE;\n" + _SCHEMA)
            self.database.executemany("INSERT INTO metadata VALUES (?,?)", [("schema_version", "1"), ("instance_id", secrets.token_urlsafe(24)), ("mfa_required", "0"), ("policy_version", "1")])
            self.database.executemany("INSERT INTO accounts(account_id,username,password_hash,role,status,auth_epoch,mfa_version) VALUES (?,?,?,?,?,1,0)", accounts)
            self._audit("identity.initialized", None, None, {"accountCount": len(accounts)})
            self.database.commit()
        except BaseException:
            if self.database is not None:
                self.database.rollback()
                self.close()
            # The incomplete file is deliberately retained: a retry must not
            # reinterpret evidence of a previous installation as a new one.
            raise

    @contextmanager
    def transaction(self):
        if self.database is None:
            raise _error("IDENTITY_DATABASE_MISSING")
        with self._lock:
            outer = self.database.in_transaction
            if not outer:
                self.database.execute("BEGIN IMMEDIATE")
            try:
                yield self.database
                if not outer:
                    self.database.commit()
            except BaseException:
                if not outer:
                    self.database.rollback()
                raise

    def close(self) -> None:
        if self.database is not None:
            self.database.close()
            self.database = None

    def _policy(self) -> tuple[bool, int, str]:
        if self.database is None:
            raise _error("IDENTITY_DATABASE_MISSING")
        rows = dict(self.database.execute("SELECT key,value FROM metadata WHERE key IN ('mfa_required','policy_version','instance_id')").fetchall())
        if rows.get("mfa_required") not in {"0", "1"} or not rows.get("instance_id"):
            raise _error("IDENTITY_POLICY_INVALID")
        try:
            version = int(rows["policy_version"])
            if version < 1:
                raise ValueError()
        except (ValueError, KeyError):
            raise _error("IDENTITY_POLICY_INVALID") from None
        return rows["mfa_required"] == "1", version, rows["instance_id"]

    @staticmethod
    def _username(username: str) -> str:
        if not isinstance(username, str) or not username or len(username) > 128 or username != username.strip() or any(ord(c) < 32 for c in username):
            raise _error("SCHEMA_INVALID")
        return username

    def bootstrap(self, username: str, password: str, *, confirmed: bool) -> dict:
        if not confirmed:
            raise _error("CONFIRMATION_REQUIRED")
        username = self._username(username)
        digest = hash_password(password)
        _consume_bootstrap(self._bootstrap_proof, self.database_path)
        self._bootstrap_proof = None
        account_id = "acct_" + secrets.token_urlsafe(24)
        self._initialize([(account_id, username, digest, "admin", "active")])
        return self.account(account_id)

    @staticmethod
    def _legacy_accounts(storage_root: Path) -> list[tuple[str, str, str | None, str, str]]:
        if storage_root.is_symlink() or not storage_root.is_dir():
            raise _error("LEGACY_STORAGE_INVALID")
        accounts = []
        seen: set[str] = set()
        for directory in sorted(storage_root.iterdir()):
            if not directory.is_dir() and not directory.is_symlink():
                continue
            if directory.is_symlink():
                raise _error("LEGACY_STORAGE_INVALID")
            path = directory / "gateway.sqlite"
            if not path.exists() and not path.is_symlink():
                raise _error("LEGACY_STORAGE_INVALID")
            if path.is_symlink() or not path.is_file():
                raise _error("LEGACY_STORAGE_INVALID")
            try:
                connection = sqlite3.connect(path.absolute().as_uri() + "?mode=ro", uri=True)
                try:
                    metadata = connection.execute("SELECT value FROM account_metadata WHERE key='gateway_account_id'").fetchone()
                    credential = connection.execute("SELECT password_hash FROM account_credentials WHERE credential_id='password'").fetchone()
                finally:
                    connection.close()
            except sqlite3.Error as exc:
                raise _error("LEGACY_STORAGE_INVALID") from exc
            account_id = metadata[0] if metadata else None
            if not isinstance(account_id, str) or WIRE_ID_PATTERN.fullmatch(account_id) is None or directory.name != sha256_hex(account_id) or account_id in seen:
                raise _error("LEGACY_STORAGE_INVALID")
            digest = credential[0] if credential else None
            if digest is not None and not isinstance(digest, str):
                raise _error("LEGACY_STORAGE_INVALID")
            seen.add(account_id)
            accounts.append((account_id, account_id, digest, "user", "active" if digest else "disabled"))
        return accounts

    def migrate_legacy(self, storage_root: Path, username: str, password: str, *, confirmed: bool) -> dict:
        if not confirmed:
            raise _error("CONFIRMATION_REQUIRED")
        if not self._create or self.database is not None or self.database_path.exists():
            raise _error("IDENTITY_ALREADY_INITIALIZED")
        storage_root = Path(storage_root).absolute()
        security = self.database_path.parent
        if security.is_symlink() or (security.exists() and any(security.iterdir())):
            raise _error("LEGACY_STATE_INCOMPLETE")
        for name in ("configuration-journal.json", "identity-journal.json"):
            path = storage_root.parent / name
            if path.exists() or path.is_symlink():
                try:
                    _private_file(path)
                    state = json.loads(path.read_text())
                    if (name != "configuration-journal.json" or not isinstance(state, dict)
                            or state.get("version") != 1 or state.get("status") not in {"committed", "rolled_back"}):
                        raise ValueError()
                except (OSError, ValueError, GatewayError) as error:
                    raise _error("LEGACY_STATE_INCOMPLETE") from error
        before = self._legacy_accounts(storage_root)
        selected = next((row for row in before if row[1] == username), None)
        if selected is None or not verify_password(password, selected[2]):
            raise _error("AUTHENTICATION_FAILED")
        # The caller must stop old writers before migration.  A second scan also
        # catches changes that happened while verifying the password.
        if before != self._legacy_accounts(storage_root):
            raise _error("LEGACY_STATE_CHANGED")
        records = [(a, u, d, "admin" if u == username else "user", s) for a, u, d, _, s in before]
        self._initialize(records)
        return self.account(selected[0])

    def _row(self, account_id: str):
        row = self.database.execute("SELECT * FROM accounts WHERE account_id=?", (account_id,)).fetchone()
        if row is None or row["status"] != "active":
            raise _error("AUTHENTICATION_FAILED")
        return row

    def _by_username(self, username: str):
        row = self.database.execute("SELECT * FROM accounts WHERE username=? AND status='active'", (username,)).fetchone()
        if row is None:
            raise _error("AUTHENTICATION_FAILED")
        return row

    @staticmethod
    def _public(row) -> dict:
        return {"accountId": row["account_id"], "username": row["username"], "role": row["role"], "authEpoch": row["auth_epoch"], "status": row["status"], "mfaEnrolled": row["mfa_secret"] is not None}

    def account(self, account_id: str) -> dict:
        with self.transaction():
            return self._public(self._row(account_id))

    def resolve_username(self, username: str) -> str:
        with self.transaction():
            return self._by_username(username)["account_id"]

    def password_digest(self, account_id: str) -> str | None:
        with self.transaction():
            return self._row(account_id)["password_hash"]

    def assert_epoch(self, account_id: str, epoch: int) -> None:
        with self.transaction():
            self._policy()
            if self._row(account_id)["auth_epoch"] != epoch:
                raise _error("AUTHENTICATION_EXPIRED")

    def _challenge(self, row, purpose: str, client_id: str | None, policy_version: int) -> tuple[str, dict]:
        if not isinstance(purpose, str) or not purpose or len(purpose) > 128 or (client_id is not None and (not isinstance(client_id, str) or len(client_id) > 256)):
            raise _error("SCHEMA_INVALID")
        challenge = secrets.token_urlsafe(32)
        now = time.time()
        self.database.execute("INSERT INTO challenges(digest,account_id,purpose,client_id,auth_epoch,mfa_version,policy_version,issued,expires) VALUES (?,?,?,?,?,?,?,?,?)", (_hash(challenge), row["account_id"], purpose, client_id, row["auth_epoch"], row["mfa_version"], policy_version, now, now + CHALLENGE_LIFETIME))
        details = {"challengeId": challenge, "methods": ["totp", "recovery-code"], "expiresAt": _iso(now + CHALLENGE_LIFETIME)}
        return challenge, details

    def authenticate(self, username: str, password: str, *, otp: str | None = None, recovery_code: str | None = None, purpose: str = "login", client_id: str | None = None, challenge_id: str | None = None) -> dict:
        if (not isinstance(purpose, str) or not purpose or len(purpose) > 128
                or (client_id is not None and (not isinstance(client_id, str) or len(client_id) > 256))):
            raise _error("SCHEMA_INVALID")
        with self.transaction():
            snapshot = dict(self._by_username(username))
            policy_snapshot = self._policy()
        # Password derivation must not hold the identity write lock: the listener
        # verifies existing sessions while this bounded worker performs scrypt.
        if not verify_password(password, snapshot["password_hash"]):
            raise _error("AUTHENTICATION_FAILED")
        with self.transaction():
            required, policy_version, _ = self._policy()
            row = self._by_username(username)
            if (row["account_id"] != snapshot["account_id"] or row["password_hash"] != snapshot["password_hash"]
                    or row["auth_epoch"] != snapshot["auth_epoch"] or self._policy() != policy_snapshot):
                raise _error("AUTHENTICATION_EXPIRED")
            if not required and purpose != "enroll":
                return {**self._public(row), "mfaVerified": False, "policyVersion": policy_version}
            if challenge_id is None:
                challenge_id, details = self._challenge(row, purpose, client_id, policy_version)
            else:
                challenge = self._challenge_row(challenge_id, purpose, client_id)
                if challenge["account_id"] != row["account_id"]:
                    raise _error("AUTHENTICATION_FAILED")
                details = {"challengeId": challenge_id, "methods": ["totp", "recovery-code"], "expiresAt": _iso(challenge["expires"])}
            enrolled = row["mfa_secret"] is not None
        if not enrolled:
            details["enrollmentRequired"] = True
            if purpose == "enroll" and not required:
                return {**self._public(row), "mfaVerified": False, "policyVersion": policy_version, "enrollmentProof": challenge_id}
            raise _error("MFA_ENROLLMENT_REQUIRED", details)
        if otp is None and recovery_code is None:
            raise _error("MFA_REQUIRED", details)
        return self.complete_authentication(challenge_id, otp=otp, recovery_code=recovery_code, purpose=purpose, client_id=client_id)

    def _challenge_row(self, challenge_id: str, purpose: str, client_id: str | None):
        if not isinstance(challenge_id, str):
            raise _error("AUTHENTICATION_FAILED")
        challenge = self.database.execute("SELECT * FROM challenges WHERE digest=?", (_hash(challenge_id),)).fetchone()
        if challenge is None or challenge["used"] or challenge["failed"] >= 5 or challenge["purpose"] != purpose or challenge["client_id"] != client_id:
            raise _error("AUTHENTICATION_FAILED")
        now = time.time()
        if now < challenge["issued"] or now >= challenge["expires"]:
            raise _error("AUTHENTICATION_EXPIRED")
        _, policy_version, _ = self._policy()
        row = self._row(challenge["account_id"])
        if challenge["auth_epoch"] != row["auth_epoch"] or challenge["mfa_version"] != row["mfa_version"] or challenge["policy_version"] != policy_version:
            raise _error("AUTHENTICATION_EXPIRED")
        return challenge

    def challenge_account(self, challenge_id: str, *, purpose: str = "login", client_id: str | None = None) -> str:
        """Resolve only a live, scope- and version-bound first-factor challenge.

        Enrollment and completion must still revalidate the challenge inside
        their own transactions; this lookup does not authorize another action.
        """
        with self.transaction():
            challenge = self._challenge_row(challenge_id, purpose, client_id)
            return challenge["account_id"]

    def _provider(self, account_id: str):
        if self.key_store is None:
            raise _error("MFA_KEY_UNAVAILABLE")
        _, _, instance = self._policy()
        try:
            return self.key_store.get_or_create_aead(f"oai-core/mfa/{instance}/{account_id}")
        except Exception as exc:
            raise _error("MFA_KEY_UNAVAILABLE") from exc

    def _associated(self, account_id: str) -> bytes:
        return (self._policy()[2] + "\0" + account_id + "\0totp").encode()

    def _decrypt_secret(self, row) -> bytes:
        try:
            secret = bytes(self._provider(row["account_id"]).decrypt(row["mfa_secret"], self._associated(row["account_id"])))
            if len(secret) != 20:
                raise ValueError("MFA_SECRET_INVALID")
            return secret
        except GatewayError:
            raise
        except Exception as exc:
            raise _error("MFA_KEY_UNAVAILABLE") from exc

    def _verify_factor(self, row, otp: str | None, recovery_code: str | None) -> bool:
        if (otp is None) == (recovery_code is None):
            return False
        if otp is not None:
            step = mfa.matching_step(self._decrypt_secret(row), otp, time.time(), row["last_step"])
            if step is None:
                return False
            self.database.execute("UPDATE accounts SET last_step=? WHERE account_id=?", (step, row["account_id"]))
            return True
        if not isinstance(recovery_code, str) or not recovery_code or len(recovery_code) > 256:
            return False
        digest = mfa.recovery_digest(self._policy()[2], row["account_id"], recovery_code)
        result = self.database.execute("UPDATE recovery_codes SET used=1 WHERE account_id=? AND digest=? AND used=0", (row["account_id"], digest))
        return result.rowcount == 1

    def complete_authentication(self, challenge_id: str, *, otp: str | None = None, recovery_code: str | None = None, purpose: str = "login", client_id: str | None = None) -> dict:
        failed = False
        with self.transaction():
            challenge = self._challenge_row(challenge_id, purpose, client_id)
            row = self._row(challenge["account_id"])
            if not challenge["completed"] and (row["mfa_secret"] is None or not self._verify_factor(row, otp, recovery_code)):
                self.database.execute("UPDATE challenges SET failed=failed+1 WHERE digest=?", (_hash(challenge_id),))
                self._audit("mfa.failure", row["account_id"], row["account_id"], {})
                failed = True
            else:
                self.database.execute("UPDATE challenges SET used=1 WHERE digest=?", (_hash(challenge_id),))
                result = {**self._public(row), "mfaVerified": True, "policyVersion": self._policy()[1]}
        if failed:
            raise _error("AUTHENTICATION_FAILED")
        return result

    def begin_mfa_enrollment(self, account_id: str, proof: str) -> dict:
        with self.transaction():
            # Proof is a first-factor challenge, not caller-provided account facts.
            record = self.database.execute("SELECT purpose,client_id FROM challenges WHERE digest=?", (_hash(proof),)).fetchone()
            if record is None:
                raise _error("AUTHENTICATION_FAILED")
            challenge = self._challenge_row(proof, record["purpose"], record["client_id"])
            row = self._row(account_id)
            if challenge["account_id"] != account_id or row["mfa_secret"] is not None or challenge["completed"]:
                raise _error("AUTHENTICATION_FAILED")
            secret = mfa.new_secret()
            encrypted = self._provider(account_id).encrypt(secret, self._associated(account_id))
            enrollment_id = secrets.token_urlsafe(32)
            self.database.execute(
                "INSERT INTO enrollments VALUES (?,?,?,?,?) ON CONFLICT(challenge_digest) DO UPDATE SET "
                "enrollment_digest=excluded.enrollment_digest,encrypted_secret=excluded.encrypted_secret,expires=excluded.expires",
                (_hash(proof), account_id, _hash(enrollment_id), encrypted, challenge["expires"]),
            )
            uri = mfa.enrollment_uri(secret, row["username"])
            return {"enrollmentId": enrollment_id, "secret": mfa.encode_secret(secret), "uri": uri,
                    "provisioningUri": uri, "expiresAt": _iso(challenge["expires"])}

    def confirm_mfa_enrollment(self, account_id: str, proof: str, otp: str, *, enrollment_id: str | None = None) -> dict:
        failed = False
        with self.transaction():
            record = self.database.execute("SELECT purpose,client_id FROM challenges WHERE digest=?", (_hash(proof),)).fetchone()
            if record is None:
                raise _error("AUTHENTICATION_FAILED")
            challenge = self._challenge_row(proof, record["purpose"], record["client_id"])
            row = self._row(account_id)
            enrollment = self.database.execute("SELECT * FROM enrollments WHERE challenge_digest=?", (_hash(proof),)).fetchone()
            if (challenge["account_id"] != account_id or row["mfa_secret"] is not None or enrollment is None
                    or enrollment["expires"] <= time.time() or not isinstance(enrollment_id, str)
                    or _hash(enrollment_id) != enrollment["enrollment_digest"]):
                raise _error("AUTHENTICATION_FAILED")
            try:
                secret = self._provider(account_id).decrypt(enrollment["encrypted_secret"], self._associated(account_id))
            except Exception as exc:
                raise _error("MFA_KEY_UNAVAILABLE") from exc
            step = mfa.matching_step(secret, otp, time.time(), -1)
            if step is None:
                self.database.execute("UPDATE challenges SET failed=failed+1 WHERE digest=?", (_hash(proof),))
                failed = True
            else:
                codes = mfa.new_recovery_codes()
                instance = self._policy()[2]
                self.database.execute("UPDATE accounts SET mfa_secret=?,last_step=?,mfa_version=mfa_version+1,auth_epoch=auth_epoch+1 WHERE account_id=?", (enrollment["encrypted_secret"], step, account_id))
                self.database.executemany("INSERT INTO recovery_codes(account_id,digest) VALUES (?,?)", [(account_id, mfa.recovery_digest(instance, account_id, code)) for code in codes])
                self.database.execute("UPDATE management_handles SET revoked=1 WHERE account_id=?", (account_id,))
                self.database.execute("UPDATE challenges SET completed=1,auth_epoch=auth_epoch+1,mfa_version=mfa_version+1 WHERE digest=?", (_hash(proof),))
                self.database.execute("DELETE FROM enrollments WHERE challenge_digest=?", (_hash(proof),))
                self._audit("mfa.enrolled", account_id, account_id, {})
        if failed:
            raise _error("AUTHENTICATION_FAILED")
        return {"accountId": account_id, "recoveryCodes": codes}

    def begin_management(self, username: str, password: str, *, otp: str | None = None, recovery_code: str | None = None, scopes=None, challenge_id: str | None = None) -> str:
        try:
            if isinstance(scopes, (str, bytes)):
                raise ValueError()
            selected = frozenset(SCOPES if scopes is None else scopes)
        except (TypeError, ValueError):
            raise _error("MANAGEMENT_SCOPE_INVALID") from None
        if not selected or not selected <= SCOPES:
            raise _error("MANAGEMENT_SCOPE_INVALID")
        result = self.authenticate(username, password, otp=otp, recovery_code=recovery_code, purpose="management", challenge_id=challenge_id)
        if result["role"] != "admin":
            raise _error("ADMIN_REQUIRED")
        handle = secrets.token_urlsafe(32)
        now, mono = time.time(), time.monotonic()
        with self.transaction():
            row = self._row(result["accountId"])
            if row["role"] != "admin" or row["auth_epoch"] != result["authEpoch"] or self._policy()[1] != result["policyVersion"]:
                raise _error("AUTHENTICATION_EXPIRED")
            self.database.execute("INSERT INTO management_handles VALUES (?,?,?,?,?,?,?,?,0)", (_hash(handle), row["account_id"], row["auth_epoch"], json.dumps(sorted(selected)), now, now + MANAGEMENT_LIFETIME, mono, mono + MANAGEMENT_LIFETIME))
            self._audit("management.authorized", row["account_id"], row["account_id"], {"scopes": sorted(selected)})
        return handle

    def require_management(self, handle: str, scope: str) -> dict:
        if not isinstance(handle, str) or not handle or len(handle) > 256 or not isinstance(scope, str) or scope not in SCOPES:
            raise _error("MANAGEMENT_REQUIRED")
        with self.transaction():
            policy, _, instance = self._policy()
            saved = self.database.execute("SELECT * FROM management_handles WHERE digest=?", (_hash(handle),)).fetchone()
            if saved is None or saved["revoked"]:
                raise _error("MANAGEMENT_REQUIRED")
            row = self._row(saved["account_id"])
            now, mono = time.time(), time.monotonic()
            scopes = json.loads(saved["scopes"])
            if row["role"] != "admin" or row["auth_epoch"] != saved["auth_epoch"] or scope not in scopes or now < saved["issued"] or now >= saved["expires"] or mono < saved["monotonic_issued"] or mono >= saved["monotonic_expires"] or (policy and row["mfa_secret"] is None):
                raise _error("MANAGEMENT_REQUIRED")
            return {**self._public(row), "instanceId": instance, "scopes": scopes, "expiresAt": _iso(saved["expires"])}

    def revoke_management(self, handle: str) -> None:
        with self.transaction():
            self.database.execute("UPDATE management_handles SET revoked=1 WHERE digest=?", (_hash(handle),))

    def create_account(self, management: str, username: str, password: str) -> dict:
        username, digest = self._username(username), hash_password(password)
        account_id = "acct_" + secrets.token_urlsafe(24)
        with self.transaction():
            actor = self.require_management(management, "accounts.write")
            try:
                self.database.execute("INSERT INTO accounts(account_id,username,password_hash,role,status,auth_epoch,mfa_version) VALUES (?,?,?,'user','active',1,0)", (account_id, username, digest))
            except sqlite3.IntegrityError as exc:
                raise _error("ACCOUNT_EXISTS") from exc
            self._audit("account.created", actor["accountId"], account_id, {})
            return self._public(self._row(account_id))

    def list_accounts(self, management: str) -> dict:
        with self.transaction():
            self.require_management(management, "accounts.read")
            return {"accounts": [self._public(row) for row in self.database.execute("SELECT * FROM accounts WHERE status!='deleted' ORDER BY username")]}

    def _protect_last_admin(self, target_id: str) -> None:
        required, _, _ = self._policy()
        for row in self.database.execute("SELECT * FROM accounts WHERE role='admin' AND status='active' AND account_id!=?", (target_id,)):
            # Exercise the same parser and scrypt implementation as login so a
            # malformed or unusable digest cannot count as a viable admin.
            from .gateway.credentials import KEY_BYTES, _decode, _derive
            try:
                parts = row["password_hash"].split("$")
                if len(parts) != 6 or parts[0] != "scrypt":
                    continue
                cost, block, parallel = map(int, parts[1:4])
                salt, expected = _decode(parts[4]), _decode(parts[5])
                # Bound work when checking a corrupted or attacker-edited hash.
                if (cost < 2 or cost > 2 ** 18 or cost & (cost - 1) or not 1 <= block <= 32
                        or not 1 <= parallel <= 8 or not salt or len(expected) != KEY_BYTES):
                    continue
                _derive("admin-viability-check", salt, cost, block, parallel)
            except (AttributeError, ValueError, TypeError, MemoryError, OverflowError):
                continue
            if required:
                if row["mfa_secret"] is None:
                    continue
                try:
                    self._decrypt_secret(row)
                except GatewayError:
                    # The independent local proof can reset a password, but it
                    # does not satisfy login MFA. An unused ordinary MFA code
                    # is needed if the TOTP key cannot be opened.
                    if not self.database.execute(
                        "SELECT 1 FROM recovery_codes WHERE account_id=? AND used=0 LIMIT 1", (row["account_id"],)
                    ).fetchone():
                        continue
            return
        raise _error("LAST_ADMIN_REQUIRED")

    def reset_password(self, management: str, username: str, password: str) -> None:
        digest = hash_password(password)
        with self.transaction():
            actor = self.require_management(management, "accounts.write")
            row = self._by_username(username)
            self.database.execute("UPDATE accounts SET password_hash=?,auth_epoch=auth_epoch+1 WHERE account_id=?", (digest, row["account_id"]))
            self.database.execute("UPDATE management_handles SET revoked=1 WHERE account_id=?", (row["account_id"],))
            self._audit("account.password_changed", actor["accountId"], row["account_id"], {})

    def delete_account(self, management: str, username: str) -> None:
        with self.transaction():
            actor = self.require_management(management, "accounts.write")
            row = self._by_username(username)
            if row["role"] == "admin":
                self._protect_last_admin(row["account_id"])
            self.database.execute("UPDATE accounts SET username=NULL,status='deleted',auth_epoch=auth_epoch+1 WHERE account_id=?", (row["account_id"],))
            self.database.execute("UPDATE management_handles SET revoked=1 WHERE account_id=?", (row["account_id"],))
            self._audit("account.deleted", actor["accountId"], row["account_id"], {})

    def set_role(self, management: str, username: str, role: str) -> None:
        if role not in {"user", "admin"}:
            raise _error("SCHEMA_INVALID")
        with self.transaction():
            actor = self.require_management(management, "accounts.write")
            row = self._by_username(username)
            if row["role"] == role:
                return
            if row["role"] == "admin":
                self._protect_last_admin(row["account_id"])
            self.database.execute("UPDATE accounts SET role=?,auth_epoch=auth_epoch+1 WHERE account_id=?", (role, row["account_id"]))
            self.database.execute("UPDATE management_handles SET revoked=1 WHERE account_id=?", (row["account_id"],))
            self._audit("account.role_changed", actor["accountId"], row["account_id"], {"role": role})

    def _local_recovery_ready(self, row) -> bool:
        path = self.database_path.parent / "local-recovery.sqlite"
        try:
            _private_file(path)
            connection = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
            try:
                parameters = (self._policy()[2], row["account_id"], row["local_recovery_generation"])
                found = connection.execute(
                    "SELECT 1 FROM recovery_codes WHERE instance_id=? AND account_id=? AND generation=? AND used=0 LIMIT 1",
                    parameters,
                ).fetchone()
                registered = connection.execute(
                    "SELECT 1 FROM audit_events WHERE instance_id=? AND account_id=? AND generation=? "
                    "AND action='recovery.registered' LIMIT 1", parameters,
                ).fetchone()
                return bool(found and registered and row["local_recovery_generation"])
            finally:
                connection.close()
        except (OSError, sqlite3.Error):
            return False

    def set_mfa_required(self, management: str, enabled: bool) -> None:
        if type(enabled) is not bool:
            raise _error("SCHEMA_INVALID")
        with self.transaction():
            actor = self.require_management(management, "security")
            required, _, _ = self._policy()
            if enabled == required:
                return
            row = self._row(actor["accountId"])
            if enabled:
                if (row["mfa_secret"] is None
                        or not self.database.execute("SELECT 1 FROM recovery_codes WHERE account_id=? AND used=0", (row["account_id"],)).fetchone()
                        or not self._local_recovery_ready(row)):
                    raise _error("MFA_RECOVERY_PREPARATION_REQUIRED")
                self._decrypt_secret(row)
            self.database.execute("UPDATE metadata SET value=? WHERE key='mfa_required'", ("1" if enabled else "0",))
            self.database.execute("UPDATE metadata SET value=CAST(value AS INTEGER)+1 WHERE key='policy_version'")
            self.database.execute("UPDATE accounts SET auth_epoch=auth_epoch+1 WHERE status='active'")
            self.database.execute("UPDATE management_handles SET revoked=1")
            self._audit("mfa.policy_changed", actor["accountId"], None, {"enabled": enabled})

    def _audit(self, action: str, actor: str | None, subject: str | None, metadata: dict) -> None:
        def strip_mfa(value):
            if isinstance(value, dict):
                return {key: strip_mfa(child) for key, child in value.items()
                        if not any(marker in str(key).lower() for marker in
                                   ("otp", "totp", "recovery", "uri", "code", "handle", "challenge"))}
            if isinstance(value, (list, tuple)):
                return [strip_mfa(child) for child in value]
            return value

        safe = strip_mfa(scrub(metadata))
        self.database.execute("INSERT INTO audit_events(at,action,actor,subject,metadata) VALUES (?,?,?,?,?)", (time.time(), action, actor, subject, json.dumps(safe, ensure_ascii=False)))

    def audit(self, action: str, actor: str | None = None, subject: str | None = None, metadata: dict | None = None) -> None:
        with self.transaction():
            self._audit(action, actor, subject, metadata or {})

    @classmethod
    def verify_offline_password(cls, database: Path, username: str, password: str) -> dict:
        """Strictly read-only verification; refuses MFA because consuming it writes."""
        path = Path(database).absolute()
        try:
            _private_dir(path.parent)
            _private_file(path)
            conn = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
            conn.row_factory = sqlite3.Row
            try:
                policy = dict(conn.execute("SELECT key,value FROM metadata WHERE key IN ('mfa_required','schema_version')"))
                if policy != {"mfa_required": "0", "schema_version": "1"}:
                    raise _error("OFFLINE_MFA_OR_POLICY_UNAVAILABLE")
                row = conn.execute("SELECT * FROM accounts WHERE username=? AND status='active'", (username,)).fetchone()
                if row is None or not verify_password(password, row["password_hash"]):
                    raise _error("AUTHENTICATION_FAILED")
                return {**cls._public(row), "mfaVerified": False}
            finally:
                conn.close()
        except (OSError, sqlite3.Error) as exc:
            raise _error("IDENTITY_DATABASE_INVALID") from exc
