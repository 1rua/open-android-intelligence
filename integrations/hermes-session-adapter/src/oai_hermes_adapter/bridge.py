"""Hermes platform entrypoint exposing sessions to Core over loopback only.

The existing Hermes Gateway package supplies native session dispatch and account
bindings. This platform deliberately registers no device tools or device routes.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import secrets
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from aiohttp import web

from .sessions import HermesSessionAdapter
from .approvals import NativeApprovalMixin
from oai_core.gateway.core import APPROVAL_CHOICES, GatewayError
from oai_core.sessions import AdapterError, SESSION_REQUEST_MAX_BYTES

PREFIX = "/oai/session-adapter/v1"
IDENTITY = re.compile(r"^[A-Za-z0-9._~-]{1,128}$")


# Compatibility import for plugins built against adapter 0.2.0.
UnsupportedApprovalMixin = NativeApprovalMixin


def create_hermes_bridge_app(adapter: HermesSessionAdapter, token: str, accounts: list[str]) -> web.Application:
    if not isinstance(token, str) or len(token) < 32:
        raise ValueError("OAI_HERMES_SESSION_TOKEN requires at least 32 characters")
    if not accounts or any(not isinstance(value, str) or not IDENTITY.fullmatch(value) for value in accounts):
        raise ValueError("Hermes bridge requires an explicit account allowlist")
    allowed = frozenset(accounts)

    @web.middleware
    async def authenticate(request, handler):
        supplied = request.headers.get("Authorization", "")
        if not secrets.compare_digest(supplied, "Bearer " + token):
            return web.json_response({"code": "AUTHENTICATION_REQUIRED"}, status=401)
        try:
            generation_query = (request.method == "GET"
                                and request.path.startswith(PREFIX + "/sessions/")
                                and request.path.endswith("/generation"))
            if request.query_string and not (request.path == PREFIX + "/commands" or generation_query):
                raise AdapterError("SCHEMA_INVALID", 400)
            if generation_query and (set(request.query) - {"clientMessageId", "generationId", "afterRevision", "waitMs"}
                                     or any(len(request.query.getall(key)) != 1
                                            for key in request.query)):
                raise AdapterError("SCHEMA_INVALID", 400)
            if request.path not in {PREFIX + "/status", PREFIX + "/commands"}:
                account = request.headers.get("X-OAI-Account")
                if account not in allowed:
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
                request["principal"] = SimpleNamespace(account_id=account, device_id=device,
                    pairing_generation=number("X-OAI-Pairing-Generation"), grant_revision=number("X-OAI-Grant-Revision"))
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
        with adapter._account(principal) as account:
            result = account.attachments.create(**body, deviceId=principal.device_id,
                pairingGeneration=principal.pairing_generation or 0, correlationId="core-attachment-upload")
            result["state"] = account.attachments.get_record(result["attachmentId"])["state"]
        return web.json_response(result)

    async def attachment_content(request):
        principal, attachment_id = request["principal"], request.match_info["attachment_id"]
        with adapter._account(principal) as account:
            row = account.store.database.execute(
                "SELECT owner_device_id, owner_pairing_generation FROM attachments WHERE attachment_id=?", (attachment_id,)).fetchone()
            if (row is None or not principal.device_id or row["owner_device_id"] != principal.device_id
                    or row["owner_pairing_generation"] != (principal.pairing_generation or 0)):
                raise AdapterError("ATTACHMENT_ACCESS_DENIED", 403)
            record = account.attachments.get_record(attachment_id)
            upload = account.attachments.begin_content_upload_stream(attachment_id, record["sizeBytes"], record["sha256"])
            try:
                async for chunk in request.content.iter_chunked(64 * 1024):
                    await asyncio.to_thread(upload.write, chunk)
                staged = await asyncio.to_thread(upload.finish)
                await asyncio.to_thread(account.attachments.accept_streamed_upload, staged)
                result = await asyncio.to_thread(account.attachments.commit, attachment_id)
            except BaseException:
                upload.abort()
                raise
        return web.json_response(result)

    async def approval_decision(request):
        body = await body_for(request)
        if not isinstance(body, dict) or set(body) != {"decision"} or not isinstance(body["decision"], str) or body["decision"] not in APPROVAL_CHOICES:
            raise AdapterError("SCHEMA_INVALID", 400)
        return web.json_response(await adapter.decide_approval(request["principal"], request.match_info["session_id"],
            request.match_info["approval_id"], body["decision"]))

    app.router.add_post(PREFIX + "/attachments", attachment_create)
    app.router.add_put(PREFIX + "/attachments/{attachment_id}/content", attachment_content)
    app.router.add_get(PREFIX + "/status", status)
    app.router.add_get(PREFIX + "/commands", commands)
    app.router.add_get(PREFIX + "/sessions", sessions)
    app.router.add_post(PREFIX + "/sessions", sessions)
    app.router.add_post(PREFIX + "/sessions/{session_id}/approvals/{approval_id}/decisions", approval_decision)
    app.router.add_route("*", PREFIX + "/sessions/{session_id}/{action}", session_operation)
    return app


def register(ctx: Any) -> None:
    """Load native Hermes only when its optional plugin is explicitly installed."""
    from .runtime import SessionOnlyAdapter
    from .backend import SessionBackend
    from .platform_identity import GATEWAY_PLATFORM_IDS, gateway_enabled
    token = os.environ.get("OAI_HERMES_SESSION_TOKEN", "")
    if len(token) < 32:
        raise ValueError("OAI_HERMES_SESSION_TOKEN requires at least 32 characters")
    root = os.environ.get("OAI_HERMES_GATEWAY_ROOT")
    if not root:
        raise ValueError("Set OAI_HERMES_GATEWAY_ROOT to the existing Hermes Gateway accounts directory")
    accounts = json.loads(os.environ.get("OAI_HERMES_ACCOUNTS", "[]"))
    if not isinstance(accounts, list) or not accounts or len(set(accounts)) != len(accounts) or any(not isinstance(v, str) or not IDENTITY.fullmatch(v) for v in accounts):
        raise ValueError("OAI_HERMES_ACCOUNTS requires unique explicit Hermes account IDs")
    port = int(os.environ.get("OAI_HERMES_SESSION_PORT", "8792"))
    if not 1 <= port <= 65535:
        raise ValueError("Invalid OAI_HERMES_SESSION_PORT")
    backend = SessionBackend(Path(root), accounts)
    instance = None
    def factory(config):
        nonlocal instance
        if instance is None:
            instance = SessionOnlyAdapter(config, backend, token, accounts, port)
        return instance
    for platform_id in GATEWAY_PLATFORM_IDS:
        ctx.register_platform(name=platform_id, label="OAI Core Hermes Sessions", adapter_factory=factory,
            check_fn=gateway_enabled, validate_config=lambda config: True,
            install_hint="Install the optional oai-hermes-session-adapter and configure OAI_HERMES_*", emoji="📱")
    register_hook = getattr(ctx, "register_hook", None)
    if callable(register_hook):
        from .tool_events import pre_tool_call, post_tool_call
        register_hook("pre_tool_call", pre_tool_call)
        register_hook("post_tool_call", post_tool_call)
