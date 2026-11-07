"""Hermes platform entrypoint exposing sessions to Core over loopback only.

The existing Hermes Gateway package supplies native session dispatch and account
bindings. This platform deliberately registers no device tools or device routes.
"""
from __future__ import annotations

import asyncio
import fcntl
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import stat
import threading
import time
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from aiohttp import web

from .sessions import HermesSessionAdapter
from .approvals import NativeApprovalMixin
from oai_core.gateway.core import APPROVAL_CHOICES, GatewayError
from oai_core.sessions import AdapterError, SESSION_REQUEST_MAX_BYTES, native_event_query
from oai_core.native_diagnostics import trace_context

PREFIX = "/oai/session-adapter/v1"
IDENTITY = re.compile(r"^[A-Za-z0-9._~-]{1,128}$")


# Compatibility import for plugins built against adapter 0.2.0.
UnsupportedApprovalMixin = NativeApprovalMixin


def _load_trusted_cores(path: Path) -> dict[str, bytes]:
    from oai_core.agent_registration import _private_file
    fd = _private_file(Path(path))
    try:
        data = os.read(fd, 65537)
    finally:
        os.close(fd)
    if len(data) > 65536:
        raise ValueError("Trusted Core manifest too large")
    document = json.loads(data)
    if not isinstance(document, dict):
        raise ValueError("Invalid trusted Core manifest")
    return {key: bytes.fromhex(value) for key, value in document.items()}


class AutoAccountSpaces:
    """Create private native accounts only for Core instance keys confirmed by the operator."""

    def __init__(self, root: Path, backend: Any, trusted_cores: dict[str, bytes], *,
                 trusted_file: Path | None = None):
        self.storage_root = Path(root).resolve()
        self.backend = backend
        self.trusted_file = Path(trusted_file) if trusted_file is not None else None
        info = self.storage_root.lstat()
        if stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and (info.st_mode & 0o077):
            os.chmod(self.storage_root, 0o700)
            info = self.storage_root.lstat()
        if not stat.S_ISDIR(info.st_mode) or (info.st_uid != os.getuid() and os.getuid() != 0) or info.st_mode & 0o077:
            raise ValueError("Hermes account root must be private")
        self.trusted_cores: dict[str, bytes] = {}
        self.root = self.storage_root / "oai-core-auto-spaces"
        self.root.mkdir(mode=0o700, exist_ok=True)
        info = self.root.lstat()
        if not stat.S_ISDIR(info.st_mode) or (info.st_uid != os.getuid() and os.getuid() != 0) or info.st_mode & 0o077:
            raise ValueError("Automatic account directory must be private")
        self.database = self.root / "bindings.sqlite"
        self._lock = threading.RLock()
        with closing(self._connect()) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS bindings (core_id TEXT NOT NULL, account_id TEXT NOT NULL, "
                       "native_id TEXT NOT NULL UNIQUE, state TEXT NOT NULL, "
                       "device INTEGER NOT NULL, inode INTEGER NOT NULL, "
                       "PRIMARY KEY(core_id, account_id))")
            db.execute("CREATE TABLE IF NOT EXISTS trusted_cores (core_id TEXT PRIMARY KEY, key_digest TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS request_nonces (core_id TEXT NOT NULL, nonce TEXT NOT NULL, "
                       "expires INTEGER NOT NULL, PRIMARY KEY(core_id,nonce))")
        self._merge_trusted_cores(trusted_cores)

    def _merge_trusted_cores(self, candidates: dict[str, bytes]) -> None:
        if any(not isinstance(key, str) or not IDENTITY.fullmatch(key) or not isinstance(value, bytes) or len(value) != 32
               for key, value in candidates.items()):
            raise ValueError("Invalid trusted Core instance keys")
        with self._lock, closing(self._connect()) as db, db:
            for core_id, key in candidates.items():
                digest = hashlib.sha256(key).hexdigest()
                row = db.execute("SELECT key_digest FROM trusted_cores WHERE core_id=?", (core_id,)).fetchone()
                if row and not secrets.compare_digest(row[0], digest):
                    continue
                db.execute("INSERT OR IGNORE INTO trusted_cores VALUES (?,?)", (core_id, digest))
                self.trusted_cores[core_id] = key

    def _reload_trusted_cores(self) -> None:
        if self.trusted_file is None or not self.trusted_file.is_file():
            return
        try:
            self._merge_trusted_cores(_load_trusted_cores(self.trusted_file))
        except (OSError, ValueError, TypeError):
            return

    def _connect(self):
        fd = os.open(self.database, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or (info.st_uid != os.getuid() and os.getuid() != 0) or info.st_mode & 0o077:
                raise ValueError("Automatic account database must be private")
        finally:
            os.close(fd)
        return sqlite3.connect(self.database, timeout=10)

    def verified(self, instance_id: str, payload: dict[str, Any], signature: str) -> bool:
        from oai_core.agent_registration import verify_core_request
        key = self.trusted_cores.get(instance_id)
        if key is None:
            self._reload_trusted_cores()
            key = self.trusted_cores.get(instance_id)
        return key is not None and verify_core_request(key, payload, signature)

    def consume_nonce(self, core_id: str, nonce: str, timestamp: int) -> bool:
        now = int(time.time())
        if abs(now - timestamp) > 120:
            return False
        try:
            with closing(self._connect()) as db, db:
                db.execute("DELETE FROM request_nonces WHERE expires<?", (now,))
                db.execute("INSERT INTO request_nonces VALUES (?,?,?)", (core_id, nonce, timestamp + 121))
            return True
        except sqlite3.IntegrityError:
            return False

    def prepare(self, core_id: str, account_id: str) -> str:
        # SQLite serializes the durable binding; this lock also serializes the
        # multi-step filesystem recovery across different bridge processes.
        fd = os.open(self.root / "provision.lock", os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or (info.st_uid != os.getuid() and os.getuid() != 0) or info.st_mode & 0o077:
                raise ValueError("Automatic account lock must be private")
            with self._lock:
                fcntl.flock(fd, fcntl.LOCK_EX)
                return self._prepare_locked(core_id, account_id)
        finally:
            os.close(fd)

    def _prepare_locked(self, core_id: str, account_id: str) -> str:
        from oai_core.gateway.account_paths import account_paths
        from oai_core.gateway.core import AccountStore
        if core_id not in self.trusted_cores or not IDENTITY.fullmatch(account_id):
            raise ValueError("Untrusted Core instance or account")
        with self._lock, closing(self._connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT native_id,state,device,inode FROM bindings WHERE core_id=? AND account_id=?",
                             (core_id, account_id)).fetchone()
            if row is None:
                native_id = "oai_" + secrets.token_hex(32)
                staging = self.root / ("pending-" + secrets.token_hex(32))
                staging.mkdir(mode=0o700)
                info = staging.lstat()
                device, inode = info.st_dev, info.st_ino
                paths = account_paths(self.storage_root, native_id)
                if paths.root.exists() or paths.root.is_symlink():
                    raise ValueError("Native account path already exists")
                # Link the empty, exclusive reservation into the account root.
                # The binding and inode are committed before any account data.
                os.rename(staging, paths.root)
                for parent in (self.root, self.storage_root):
                    directory = os.open(parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
                    try:
                        os.fsync(directory)
                    finally:
                        os.close(directory)
                db.execute("INSERT INTO bindings VALUES (?,?,?,'pending',?,?)",
                           (core_id, account_id, native_id, device, inode))
                db.commit()
                state = "pending"
            else:
                native_id, state, device, inode = row
                db.commit()
                paths = account_paths(self.storage_root, native_id)
            if state == "pending":
                info = paths.root.lstat()
                if (not stat.S_ISDIR(info.st_mode) or (info.st_uid != os.getuid() and os.getuid() != 0) or info.st_mode & 0o077
                        or (info.st_dev, info.st_ino) != (device, inode)):
                    raise ValueError("Unsafe pending account directory")
                cipher = self.backend.secret_store.get_or_create_aead(f"open-android-intelligence-gateway/{native_id}")
                account = AccountStore(paths, cipher.reference, aead=cipher, account_id=native_id)
                account.close()
                db.execute("UPDATE bindings SET state='ready' WHERE core_id=? AND account_id=? AND state='pending'",
                           (core_id, account_id))
                db.commit()
            elif state != "ready" or not paths.database.is_file():
                raise ValueError("Automatic account store is missing or invalid")
            info = paths.root.lstat()
            if not stat.S_ISDIR(info.st_mode) or (info.st_dev, info.st_ino) != (device, inode):
                raise ValueError("Automatic account ownership changed")
            with self.backend._lock:
                if native_id not in self.backend.accounts:
                    self.backend.accounts = (*self.backend.accounts, native_id)
                self.backend.acquire_accounts()
            return native_id

    def restore(self) -> None:
        with closing(self._connect()) as db:
            bindings = db.execute("SELECT core_id,account_id FROM bindings ORDER BY core_id,account_id").fetchall()
        for core_id, account_id in bindings:
            if core_id in self.trusted_cores:
                self.prepare(core_id, account_id)

    def authorize(self, core_id: str, account_id: str, native_id: str) -> bool:
        with closing(self._connect()) as db:
            row = db.execute("SELECT state FROM bindings WHERE core_id=? AND account_id=? AND native_id=?",
                             (core_id, account_id, native_id)).fetchone()
        if not row or row[0] != "ready":
            return False
        return self.prepare(core_id, account_id) == native_id


def create_hermes_bridge_app(adapter: HermesSessionAdapter, token: str, accounts: list[str], *,
                             instance_id: str | None = None, auto_spaces: AutoAccountSpaces | None = None) -> web.Application:
    if not isinstance(token, str) or len(token) < 32:
        raise ValueError("OAI_HERMES_SESSION_TOKEN requires at least 32 characters")
    if not isinstance(accounts, list) or not accounts and auto_spaces is None or any(
            not isinstance(value, str) or not IDENTITY.fullmatch(value) for value in accounts):
        raise ValueError("Hermes bridge requires an explicit account allowlist")
    allowed = frozenset(accounts)
    current_token = [token]

    def _reload_token() -> str | None:
        try:
            from oai_core.agent_registration import read_bridge_secret
            dotenv = _hermes_dotenv_defaults()
            source = {}
            for variable, key in (("OAI_HERMES_SESSION_SECRET_FILE", "secretFile"),
                                  ("OAI_HERMES_SESSION_SECRET_FD", "secretFd"),
                                  ("OAI_HERMES_SESSION_TOKEN", "token")):
                if variable in os.environ:
                    value = os.environ[variable]
                    source[key] = int(value) if key == "secretFd" and value.isdecimal() else value
            if not source:
                for variable, key in (("OAI_HERMES_SESSION_SECRET_FILE", "secretFile"),
                                      ("OAI_HERMES_SESSION_TOKEN", "token")):
                    if variable in dotenv:
                        source[key] = dotenv[variable]
            if source:
                return read_bridge_secret(source, Path.cwd())
        except Exception:
            pass
        return None

    @web.middleware
    async def authenticate(request, handler):
        supplied = request.headers.get("Authorization", "")
        if not secrets.compare_digest(supplied, "Bearer " + current_token[0]):
            new_tok = _reload_token()
            if new_tok and secrets.compare_digest(supplied, "Bearer " + new_tok):
                current_token[0] = new_tok
            else:
                return web.json_response({"code": "AUTHENTICATION_REQUIRED"}, status=401)
        try:
            generation_query = (request.method == "GET"
                                and request.path.startswith(PREFIX + "/sessions/")
                                and request.path.endswith("/generation"))
            events_query = (request.method == "GET" and request.path.startswith(PREFIX + "/sessions/")
                            and request.path.endswith("/events"))
            if request.query_string and not (request.path == PREFIX + "/commands" or generation_query or events_query):
                raise AdapterError("SCHEMA_INVALID", 400)
            if generation_query and (set(request.query) - {"clientMessageId", "generationId", "afterRevision", "waitMs"}
                                     or any(len(request.query.getall(key)) != 1
                                            for key in request.query)):
                raise AdapterError("SCHEMA_INVALID", 400)
            if request.path not in {PREFIX + "/status", PREFIX + "/commands", PREFIX + "/handshake", PREFIX + "/auto-spaces"}:
                account = request.headers.get("X-OAI-Account")
                if account not in allowed:
                    core_id = request.headers.get("X-OAI-Core-Instance")
                    core_account = request.headers.get("X-OAI-Core-Account")
                    from oai_core.agent_registration import core_request_payload
                    proof = request.headers.get("X-OAI-Core-Proof", "")
                    nonce = request.headers.get("X-OAI-Core-Nonce", "")
                    timestamp = request.headers.get("X-OAI-Core-Time", "")
                    if (auto_spaces is None or not re.fullmatch(r"[a-f0-9]{64}", nonce)
                            or not re.fullmatch(r"[0-9]{1,12}", timestamp)):
                        raise AdapterError("AGENT_ACCOUNT_NOT_CONNECTED", 403)
                    uploading = request.method == "PUT" and request.path.endswith("/content")
                    if uploading and not re.fullmatch(r"[a-f0-9]{64}", request.headers.get("X-OAI-Content-SHA256", "")):
                        raise AdapterError("AGENT_ACCOUNT_NOT_CONNECTED", 403)
                    raw = b"" if uploading else await request.read()
                    payload = core_request_payload(core_id, instance_id, core_account, account, request.method,
                                                   request.raw_path, raw, request.headers, nonce, int(timestamp))
                    if (not auto_spaces.verified(core_id, payload, proof)
                            or not await asyncio.to_thread(auto_spaces.consume_nonce, core_id, nonce, int(timestamp))
                            or not await asyncio.to_thread(auto_spaces.authorize, core_id, core_account, account)):
                        raise AdapterError("AGENT_ACCOUNT_NOT_CONNECTED", 403)
                device = request.headers.get("X-OAI-Device")
                if device is not None and not IDENTITY.fullmatch(device):
                    raise AdapterError("SCHEMA_INVALID", 400)
                def number(header):
                    value = request.headers.get(header)
                    if value is None:
                        return None
                    if not value.isdecimal() or not 1 <= int(value) <= 2 ** 53 - 1:
                        raise AdapterError("SCHEMA_INVALID", 400)
                    return int(value)
                origins = request.headers.getall("X-OAI-Origin-Context", [])
                if len(origins) > 1 or origins and (not origins[0] or len(origins[0]) > 4096):
                    raise AdapterError("SCHEMA_INVALID", 400)
                request["principal"] = SimpleNamespace(account_id=account, device_id=device,
                    pairing_generation=number("X-OAI-Pairing-Generation"), grant_revision=number("X-OAI-Grant-Revision"),
                    origin_context=origins[0] if origins else None)
            with trace_context(request.headers, request_id=request.headers.get("X-OAI-Request-Id")):
                response = await handler(request)
            response.headers["Cache-Control"] = "no-store"
            return response
        except GatewayError as exc:
            return web.json_response({"code": exc.code}, status=400)
        except AdapterError as exc:
            return web.json_response({"code": exc.code,
                **({"details": exc.details} if exc.details else {})}, status=exc.status)
        except web.HTTPRequestEntityTooLarge:
            return web.json_response({"code": "MESSAGE_TOO_LARGE"}, status=413)
        except (ValueError, json.JSONDecodeError, TypeError):
            return web.json_response({"code": "SCHEMA_INVALID"}, status=400)

    app = web.Application(middlewares=[authenticate], client_max_size=SESSION_REQUEST_MAX_BYTES)

    async def status(request):
        return web.json_response({"online": await adapter.status(), "capabilities": adapter.descriptor()["capabilities"]})

    async def handshake(request):
        from oai_core.agent_registration import _json, bridge_proof
        body = await body_for(request)
        if (instance_id is None or not isinstance(body, dict)
                or set(body) not in ({"challenge"}, {"challenge", "coreInstanceId", "coreSignature"})
                or not isinstance(body["challenge"], str) or not re.fullmatch(r"[0-9a-f]{64}", body["challenge"])):
            raise AdapterError("HOST_INCOMPATIBLE", 400)
        online = await adapter.status()
        capabilities = adapter.descriptor()["capabilities"]
        result = {"instanceId": instance_id, "sessionScope": "account", "online": online,
                  "capabilities": capabilities,
                  "proof": bridge_proof(current_token[0], body["challenge"], instance_id, "account", online, capabilities)}
        if "coreInstanceId" in body:
            core_id = body["coreInstanceId"]
            if not isinstance(core_id, str) or not IDENTITY.fullmatch(core_id):
                raise AdapterError("SCHEMA_INVALID", 400)
            payload = {"coreInstanceId": core_id, "hermesInstanceId": instance_id, "challenge": body["challenge"]}
            if auto_spaces is None or not auto_spaces.verified(core_id, payload, body["coreSignature"]):
                raise AdapterError("AUTHENTICATION_REQUIRED", 403)
            result.update(coreAccepted=True, coreProof=hmac.new(
                current_token[0].encode(), b"oai-hermes-core-accepted-v1\0" + _json(payload), hashlib.sha256).hexdigest())
        return web.json_response(result)

    async def auto_space(request):
        body = await body_for(request)
        fields = {"coreInstanceId", "hermesInstanceId", "accountId", "nonce", "signature"}
        if (not isinstance(body, dict) or set(body) != fields
                or any(not isinstance(body[name], str) for name in fields)
                or body["hermesInstanceId"] != instance_id
                or not IDENTITY.fullmatch(body["coreInstanceId"])
                or not IDENTITY.fullmatch(body["accountId"])
                or not re.fullmatch(r"[a-f0-9]{64}", body["nonce"])):
            raise AdapterError("SCHEMA_INVALID", 400)
        payload = {name: body[name] for name in fields if name != "signature"}
        if auto_spaces is None or not auto_spaces.verified(body["coreInstanceId"], payload, body["signature"]):
            raise AdapterError("AUTHENTICATION_REQUIRED", 403)
        native_id = await asyncio.to_thread(auto_spaces.prepare, body["coreInstanceId"], body["accountId"])
        return web.json_response({"nativeId": native_id})

    async def commands(request):
        language_code = request.query.get("languageCode", "en")
        cmd_fn = getattr(adapter, "commands", None)
        cmds = cmd_fn(language_code) if callable(cmd_fn) else []
        return web.json_response({"commands": cmds})

    async def body_for(request):
        if len(await request.read()) >= SESSION_REQUEST_MAX_BYTES:
            raise AdapterError("MESSAGE_TOO_LARGE", 413)
        return await request.json()

    async def sessions(request):
        principal = request["principal"]
        if request.method == "GET":
            return web.json_response({"sessions": await adapter.list_sessions(principal)})
        body = await body_for(request)
        if not isinstance(body, dict) or set(body) - {"title"}:
            raise AdapterError("SCHEMA_INVALID", 400)
        title = body.get("title")
        if title is not None and (not isinstance(title, str) or not title.strip()):
            raise AdapterError("SCHEMA_INVALID", 400)
        return web.json_response({"session": await adapter.create(principal, title)})

    async def session_operation(request):
        principal, session_id, action = request["principal"], request.match_info["session_id"], request.match_info["action"]
        if request.method == "GET" and action == "history":
            return web.json_response({"messages": await adapter.history(principal, session_id)})
        if request.method == "GET" and action == "events":
            return web.json_response(await adapter.event_page(principal, session_id,
                consumer_id=observer_for(request), **native_event_query(request.query)))
        if request.method == "GET" and action == "generation":
            if request.query:
                def integer(key, default):
                    value = request.query.get(key)
                    if value is None:
                        return default
                    if not value.isascii() or not value.isdecimal():
                        raise AdapterError("SCHEMA_INVALID", 400)
                    return int(value)
                generation = await adapter.generation(
                    principal, session_id, client_message_id=request.query.get("clientMessageId"),
                    generation_id=request.query.get("generationId"),
                    after_revision=integer("afterRevision", None), wait_ms=integer("waitMs", 0),
                )
            else:
                generation = await adapter.current_generation(principal, session_id)
            return web.json_response({
                "generation": generation,
            })
        if request.method != "POST" or action not in {"messages", "steer", "rename", "archive", "delete", "cancel"}:
            raise AdapterError("NOT_FOUND", 404)
        body = await body_for(request)
        if not isinstance(body, dict):
            raise AdapterError("SCHEMA_INVALID", 400)
        if action == "steer":
            if set(body) - {"text", "generationId", "clientMessageId"}:
                raise AdapterError("SCHEMA_INVALID", 400)
            text, generation_id = body.get("text"), body.get("generationId")
            client_message_id = body.get("clientMessageId")
            if (not isinstance(text, str) or not text.strip()
                    or generation_id is not None and (not isinstance(generation_id, str) or not generation_id)
                    or client_message_id is not None and (
                        not isinstance(client_message_id, str) or not IDENTITY.fullmatch(client_message_id)
                    )):
                raise AdapterError("SCHEMA_INVALID", 400)
            return web.json_response({
                "steering": await adapter.steer(
                    principal, session_id, text, generation_id, client_message_id,
                ),
            })
        if action != "messages":
            if set(body) - {"value"}:
                raise AdapterError("SCHEMA_INVALID", 400)
            return web.json_response(await adapter.manage(principal, session_id, action, body.get("value")))
        attachment_ids = body.get("attachmentIds", [])
        client_message_id = body.get("clientMessageId")
        if (set(body) - {"text", "attachmentIds", "clientMessageId"} or not isinstance(body.get("text", ""), str)
                or not isinstance(attachment_ids, list) or len(attachment_ids) > 256
                or any(not isinstance(item, str) or not IDENTITY.fullmatch(item) for item in attachment_ids)
                or len(set(attachment_ids)) != len(attachment_ids)
                or not body.get("text", "").strip() and not attachment_ids
                or client_message_id is not None and (not isinstance(client_message_id, str) or not IDENTITY.fullmatch(client_message_id))):
            raise AdapterError("SCHEMA_INVALID", 400)
        stream = adapter.send(principal, session_id, body.get("text", ""), attachment_ids=attachment_ids,
                              client_message_id=client_message_id)
        # Authorization and binding checks run before committing response headers.
        first = await anext(stream, None)
        response = web.StreamResponse(headers={"Content-Type": "application/x-ndjson", "Cache-Control": "no-store"})
        try:
            await response.prepare(request)
            if first is not None:
                await response.write((json.dumps(first) + "\n").encode())
            async for event in stream:
                await response.write((json.dumps(event) + "\n").encode())
            await response.write_eof()
            return response
        except AdapterError as exc:
            await response.write((json.dumps({"type": "error", "code": exc.code}) + "\n").encode())
            return response
        finally:
            await stream.aclose()

    async def attachment_create(request):
        if not adapter.descriptor()["capabilities"].get("attachments"):
            raise AdapterError("UNSUPPORTED_CAPABILITY", 405)
        body = await body_for(request)
        fields = {"clientAttachmentId", "filename", "mediaType", "sizeBytes", "sha256"}
        if (not isinstance(body, dict) or set(body) != fields
                or any(not isinstance(body[key], str) or not body[key] for key in ("clientAttachmentId", "filename", "mediaType", "sha256"))
                or not IDENTITY.fullmatch(body["clientAttachmentId"]) or not re.fullmatch(r"[a-f0-9]{64}", body["sha256"])
                or type(body["sizeBytes"]) is not int or not 0 <= body["sizeBytes"] <= 2**63 - 1):
            raise AdapterError("SCHEMA_INVALID", 400)
        principal = request["principal"]
        if not principal.device_id:
            raise AdapterError("DEVICE_CONTEXT_REQUIRED", 403)
        def create():
            with adapter._account(principal) as account:
                result = account.attachments.create(**body, deviceId=principal.device_id,
                    pairingGeneration=principal.pairing_generation or 0, correlationId="core-attachment-upload")
                result["state"] = account.attachments.get_record(result["attachmentId"])["state"]
            return result
        result = await asyncio.to_thread(create)
        return web.json_response(result)

    async def attachment_content(request):
        principal, attachment_id = request["principal"], request.match_info["attachment_id"]
        def begin():
            with adapter._account(principal) as account:
                row = account.store.database.execute(
                    "SELECT owner_device_id, owner_pairing_generation FROM attachments WHERE attachment_id=?", (attachment_id,)).fetchone()
                if (row is None or not principal.device_id or row["owner_device_id"] != principal.device_id
                        or row["owner_pairing_generation"] != (principal.pairing_generation or 0)):
                    raise AdapterError("ATTACHMENT_ACCESS_DENIED", 403)
                record = account.attachments.get_record(attachment_id)
                supplied_digest = request.headers.get("X-OAI-Content-SHA256")
                if supplied_digest is not None and not secrets.compare_digest(supplied_digest, record["sha256"].removeprefix("sha256:")):
                    raise AdapterError("ATTACHMENT_ACCESS_DENIED", 403)
                return account.attachments.begin_content_upload_stream(attachment_id, record["sizeBytes"], record["sha256"])
        upload = await asyncio.to_thread(begin)
        try:
            digest = hashlib.sha256()
            async for chunk in request.content.iter_chunked(64 * 1024):
                digest.update(chunk)
                await asyncio.to_thread(upload.write, chunk)
            expected = request.headers.get("X-OAI-Content-SHA256")
            if expected is not None and not secrets.compare_digest(digest.hexdigest(), expected):
                raise AdapterError("ATTACHMENT_READ_FAILED", 409)
            staged = await asyncio.to_thread(upload.finish)
            def commit():
                with adapter._account(principal) as account:
                    account.attachments.accept_streamed_upload(staged)
                    return account.attachments.commit(attachment_id)
            result = await asyncio.to_thread(commit)
        except BaseException as exc:
            await asyncio.to_thread(upload.abort)
            if isinstance(exc, GatewayError):
                raise AdapterError(exc.code, 404 if exc.code in {"SCHEMA_INVALID", "CONVERSATION_NOT_FOUND"} else 409) from exc
            raise
        return web.json_response(result)

    async def approval_decision(request):
        body = await body_for(request)
        if not isinstance(body, dict) or set(body) != {"decision"} or not isinstance(body["decision"], str) or body["decision"] not in APPROVAL_CHOICES:
            raise AdapterError("SCHEMA_INVALID", 400)
        return web.json_response(await adapter.decide_approval(request["principal"], request.match_info["session_id"],
            request.match_info["approval_id"], body["decision"]))

    def observer_for(request):
        values = request.headers.getall("X-OAI-Observer", [])
        if len(values) != 1 or re.fullmatch(r"[a-f0-9]{64}", values[0]) is None:
            raise AdapterError("INVALID_EVENT_OBSERVER", 400)
        return values[0]

    async def events_ack(request):
        body = await body_for(request)
        if not isinstance(body, dict) or set(body) != {"cursor"}:
            raise AdapterError("SCHEMA_INVALID", 400)
        return web.json_response(await adapter.ack_events(request["principal"], request.match_info["session_id"],
            consumer_id=observer_for(request), cursor=body["cursor"]))

    app.router.add_post(PREFIX + "/sessions/{session_id}/events/ack", events_ack)
    app.router.add_post(PREFIX + "/attachments", attachment_create)
    app.router.add_put(PREFIX + "/attachments/{attachment_id}/content", attachment_content)
    app.router.add_post(PREFIX + "/handshake", handshake)
    app.router.add_post(PREFIX + "/auto-spaces", auto_space)
    app.router.add_get(PREFIX + "/status", status)
    app.router.add_get(PREFIX + "/commands", commands)
    app.router.add_get(PREFIX + "/sessions", sessions)
    app.router.add_post(PREFIX + "/sessions", sessions)
    app.router.add_post(PREFIX + "/sessions/{session_id}/approvals/{approval_id}/decisions", approval_decision)
    app.router.add_route("*", PREFIX + "/sessions/{session_id}/{action}", session_operation)
    return app


def _hermes_dotenv_defaults() -> dict[str, str]:
    raw_home = os.environ.get("HERMES_HOME")
    if not raw_home:
        return {}
    env_file = Path(raw_home).expanduser() / ".env"
    if not env_file.is_file() or env_file.is_symlink():
        return {}
    try:
        info = env_file.lstat()
        if not stat.S_ISREG(info.st_mode) or (info.st_uid != os.getuid() and os.getuid() != 0):
            return {}
        from oai_core.configuration import _parse_dotenv
        return _parse_dotenv(env_file.read_text(encoding="utf-8", errors="replace"))
    except OSError:
        return {}


def _bridge_configuration() -> dict[str, Any]:
    """Read the bridge settings without touching the account store.

    Only environment/config is validated here so plugin discovery and
    ``hermes gateway setup`` can list and configure the platform. Everything
    that needs the account root (and its derived key) is built on first use.
    """
    from oai_core.agent_registration import read_bridge_secret
    dotenv = _hermes_dotenv_defaults()
    def _env_get(name: str, default: str | None = None) -> str | None:
        if name in os.environ:
            return os.environ[name]
        return dotenv.get(name, default)

    source = {}
    for variable, key in (("OAI_HERMES_SESSION_SECRET_FILE", "secretFile"),
                          ("OAI_HERMES_SESSION_SECRET_FD", "secretFd"),
                          ("OAI_HERMES_SESSION_TOKEN", "token")):
        if variable in os.environ:
            value = os.environ[variable]
            source[key] = int(value) if key == "secretFd" and value.isdecimal() else value
    if not source:
        for variable, key in (("OAI_HERMES_SESSION_SECRET_FILE", "secretFile"),
                              ("OAI_HERMES_SESSION_TOKEN", "token")):
            if variable in dotenv:
                source[key] = dotenv[variable]
    token = read_bridge_secret(source, Path.cwd())
    root = _env_get("OAI_HERMES_GATEWAY_ROOT")
    if not root:
        raise ValueError("Set OAI_HERMES_GATEWAY_ROOT to the existing Hermes Gateway accounts directory")
    store_root = Path(root).expanduser()
    if not store_root.is_dir():
        raise ValueError("OAI_HERMES_GATEWAY_ROOT must point to an existing Hermes Gateway accounts directory")
    accounts = json.loads(_env_get("OAI_HERMES_ACCOUNTS", "[]") or "[]")
    default_trusted = store_root.resolve().parent / "trusted-cores.json"
    trusted_file = _env_get("OAI_HERMES_TRUSTED_CORES_FILE")
    trusted_path = Path(trusted_file) if trusted_file else default_trusted
    if (not isinstance(accounts, list) or len(set(accounts)) != len(accounts)
            or any(not isinstance(v, str) or not IDENTITY.fullmatch(v) for v in accounts)
            or not accounts and not trusted_file and not default_trusted.is_file()):
        raise ValueError("Hermes requires explicit accounts or confirmed Core instance keys")
    port = int(_env_get("OAI_HERMES_SESSION_PORT", "8792") or "8792")
    if not 1 <= port <= 65535:
        raise ValueError("Invalid OAI_HERMES_SESSION_PORT")
    instance_id = _env_get("OAI_HERMES_INSTANCE_ID") or "hermes"
    return {"token": token, "root": store_root, "accounts": accounts,
            "trusted_file": trusted_file, "trusted_path": trusted_path, "port": port,
            "instance_id": instance_id}


def _configured_platform(config: Any = None) -> bool:
    """Passive ``is_connected`` probe: never builds the store or writes keys."""
    del config
    try:
        _bridge_configuration()
    except (ValueError, TypeError, json.JSONDecodeError, OSError):
        return False
    return True


def register(ctx: Any) -> None:
    """Register the optional session platform; loading never requires key setup.

    Registration stays side-effect free and succeeds even before the operator
    fills in ``OAI_HERMES_*``: the setup menu then shows the platform as
    "not configured" with the exact variables it needs, instead of the plugin
    failing to load and disappearing from the list entirely. Settings are read
    again on the first factory call, so values an install prompt just wrote to
    ``.env`` take effect without a process restart.
    """
    from .runtime import SessionOnlyAdapter
    from .backend import SessionBackend
    from .platform_identity import GATEWAY_PLATFORM_IDS, gateway_enabled
    instance = None

    def factory(config):
        nonlocal instance
        if instance is None:
            try:
                configuration = _bridge_configuration()
            except (ValueError, TypeError, json.JSONDecodeError) as error:
                raise ValueError(
                    f"OAI Core Hermes 会话平台尚未配置：{error}。"
                    "请在 Hermes 的环境文件（~/.hermes/.env）中设置 OAI_HERMES_* 后重启网关。"
                ) from error
            root, trusted_file, trusted_path = (configuration["root"], configuration["trusted_file"],
                                                configuration["trusted_path"])
            backend = SessionBackend(root, configuration["accounts"])
            trusted_cores = {}
            if trusted_file or trusted_path.is_file():
                trusted_cores = _load_trusted_cores(trusted_path)
            os.environ.setdefault("OAI_HERMES_INSTANCE_ID", configuration.get("instance_id") or "hermes")
            instance = SessionOnlyAdapter(config, backend, configuration["token"],
                                          configuration["accounts"], configuration["port"])
            if trusted_cores or root.is_dir():
                instance.auto_spaces = AutoAccountSpaces(root, backend, trusted_cores,
                                                         trusted_file=trusted_path)
        return instance

    for platform_id in GATEWAY_PLATFORM_IDS:
        ctx.register_platform(name=platform_id, label="OAI Core Hermes Sessions", adapter_factory=factory,
            check_fn=gateway_enabled, validate_config=lambda config: True,
            is_connected=_configured_platform,
            required_env=["OAI_HERMES_SESSION_TOKEN", "OAI_HERMES_GATEWAY_ROOT", "OAI_HERMES_ACCOUNTS"],
            install_hint="配置 OAI_HERMES_* 后重启 Hermes 网关即可连接 OAI Core", emoji="📱")
    register_hook = getattr(ctx, "register_hook", None)
    if callable(register_hook):
        from .tool_events import pre_tool_call, post_tool_call
        register_hook("pre_tool_call", pre_tool_call)
        register_hook("post_tool_call", post_tool_call)
