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
from oai_core.sessions import AdapterError, SESSION_REQUEST_MAX_BYTES

PREFIX = "/oai/session-adapter/v1"
IDENTITY = re.compile(r"^[A-Za-z0-9._~-]{1,128}$")


class UnsupportedApprovalMixin:
    @staticmethod
    def _unsupported_approval_result():
        from gateway.platforms.base import SendResult
        return SendResult(success=True, message_id="unsupported_approval", retryable=False)

    async def _send_exec_approval_prompt(self, prompt):
        """Deny immediately until the Core UI can render native approvals."""
        from .approvals import resolve_host_approval
        metadata = getattr(prompt, "metadata", None) or {}
        chat_id = str(getattr(prompt, "chat_id", "") or "")
        account_id = metadata.get("account_id") or self._conv_to_account.get(chat_id)
        await asyncio.to_thread(resolve_host_approval, str(getattr(prompt, "session_key", "") or ""),
                                "deny", request_id=metadata.get("request_id"))
        if account_id:
            self.services.core.deliver_event(account_id, {"eventType": "core.approval.unsupported",
                "payload": {"conversationId": chat_id, "code": "UNSUPPORTED_APPROVAL"}})
        # Handled means the host must not replace the denied request with a
        # plain-text approval prompt that the session-only client cannot answer.
        return self._unsupported_approval_result()


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
            if request.query_string:
                raise AdapterError("SCHEMA_INVALID", 400)
            if request.path != PREFIX + "/status":
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
        except AdapterError as exc:
            return web.json_response({"code": exc.code}, status=exc.status)
        except web.HTTPRequestEntityTooLarge:
            return web.json_response({"code": "MESSAGE_TOO_LARGE"}, status=413)
        except (ValueError, json.JSONDecodeError, TypeError):
            return web.json_response({"code": "SCHEMA_INVALID"}, status=400)

    app = web.Application(middlewares=[authenticate], client_max_size=SESSION_REQUEST_MAX_BYTES)

    async def status(request):
        return web.json_response({"online": await adapter.status(), "capabilities": adapter.descriptor()["capabilities"]})

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
        if request.method != "POST" or action not in {"messages", "rename", "archive", "delete", "cancel"}:
            raise AdapterError("NOT_FOUND", 404)
        body = await body_for(request)
        if not isinstance(body, dict):
            raise AdapterError("SCHEMA_INVALID", 400)
        if action != "messages":
            if set(body) - {"value"}:
                raise AdapterError("SCHEMA_INVALID", 400)
            return web.json_response(await adapter.manage(principal, session_id, action, body.get("value")))
        if set(body) != {"text"} or not isinstance(body["text"], str) or not body["text"].strip():
            raise AdapterError("SCHEMA_INVALID", 400)
        stream = adapter.send(principal, session_id, body["text"])
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

    app.router.add_get(PREFIX + "/status", status)
    app.router.add_get(PREFIX + "/sessions", sessions)
    app.router.add_post(PREFIX + "/sessions", sessions)
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
