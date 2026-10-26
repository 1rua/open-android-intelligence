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

from .adapters.hermes import HermesSessionAdapter
from .sessions import AdapterError, SESSION_REQUEST_MAX_BYTES

PREFIX = "/oai/session-adapter/v1"
IDENTITY = re.compile(r"^[A-Za-z0-9._~-]{1,128}$")


class UnsupportedApprovalMixin:
    async def _send_exec_approval_prompt(self, prompt):
        """Deny immediately until the Core UI can render native approvals."""
        from open_android_intelligence_gateway.adapter import SendResult
        from open_android_intelligence_gateway.core import resolve_host_approval
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
        return SendResult(success=True, message_id="unsupported_approval", retryable=False)


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
    """Called by Hermes' standard plugin loader; no monkey-patching required."""
    from open_android_intelligence_gateway.adapter import OpenAndroidPlatformAdapter, NATIVE_HERMES_AVAILABLE
    from open_android_intelligence_gateway.core import create_gateway_core
    from open_android_intelligence_gateway.host_history import HermesHistoryPort
    from open_android_intelligence_gateway.local_keys import resolve_local_master_key_store
    from open_android_intelligence_gateway.plugin import compose_gateway_services, ensure_contract_ready
    from open_android_intelligence_gateway.platform_identity import GATEWAY_PLATFORM_IDS

    token = os.environ.get("OAI_HERMES_SESSION_TOKEN", "")
    if len(token) < 32:
        raise ValueError("Set OAI_HERMES_SESSION_TOKEN in the Hermes process environment")
    root = os.environ.get("OAI_HERMES_GATEWAY_ROOT")
    if not root:
        raise ValueError("Set OAI_HERMES_GATEWAY_ROOT to the existing Gateway accounts directory")
    accounts = json.loads(os.environ.get("OAI_HERMES_ACCOUNTS", "[]"))
    if not isinstance(accounts, list) or not accounts or any(not isinstance(v, str) or not IDENTITY.fullmatch(v) for v in accounts):
        raise ValueError("OAI_HERMES_ACCOUNTS must be an explicit JSON account allowlist")
    port = int(os.environ.get("OAI_HERMES_SESSION_PORT", "8792"))
    if not 1 <= port <= 65535:
        raise ValueError("Invalid OAI_HERMES_SESSION_PORT")

    class ContextProxy:
        # Reuse the exact original account root and master-key resolver; a new
        # plugin data directory must not silently create a second account store.
        gateway_core = create_gateway_core(storage_root=Path(root), secret_store=resolve_local_master_key_store())
        def __getattr__(self, key):
            return getattr(ctx, key)

    services = compose_gateway_services(ContextProxy())
    resolution = ensure_contract_ready(services.core)
    if resolution.root is None:
        raise ValueError("Hermes session adapter requires the pinned Gateway contract assets")
    if any(not services.core.account_exists(account) for account in accounts):
        raise ValueError("Hermes allowlist contains an account absent from the configured Gateway root")

    class SessionOnlyAdapter(UnsupportedApprovalMixin, OpenAndroidPlatformAdapter):
        async def _connect_once(self, *, is_reconnect=False):
            if not NATIVE_HERMES_AVAILABLE:
                return False
            self._loop = asyncio.get_running_loop()
            core = self.services.core
            core.approval_resolver = None
            self._refresh_generation_canceller()
            store = getattr(self, "_session_store", None)
            history = HermesHistoryPort(core, store) if store is not None else None
            core.history_reader = history.read if history is not None else None
            core.conversation_manager = history.manage if history is not None else None
            self._cleanup_inbound_media_cache()
            for account_id in accounts:
                await self._recover_message_dispatches(account_id)
            self._app = create_hermes_bridge_app(HermesSessionAdapter(core, self), token, accounts)
            self._runner = web.AppRunner(self._app)
            await self._runner.setup()
            self._site = web.TCPSite(self._runner, "127.0.0.1", port)
            await self._site.start()
            self._mark_connected()
            return True

    instance = None
    def factory(config):
        nonlocal instance
        if instance is None:
            instance = SessionOnlyAdapter(config, services)
        return instance

    for platform_id in GATEWAY_PLATFORM_IDS:
        ctx.register_platform(name=platform_id, label="OAI Core Hermes Sessions", adapter_factory=factory,
            check_fn=lambda: NATIVE_HERMES_AVAILABLE, validate_config=lambda config: True,
            install_hint="Install oai-core and configure OAI_HERMES_* for the session-only host plugin", emoji="📱")
