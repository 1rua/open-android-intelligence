"""Authenticated access to explicitly registered, native Agent sessions."""

from __future__ import annotations

import asyncio
import inspect
import json
import logging
import os
import re
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from typing import Any, Protocol
from pathlib import Path

from aiohttp import web
from open_android_intelligence_gateway.core import GatewayError
from open_android_intelligence_gateway.http import _status

from .auth import Principal
from .operations import NativeOperations, OperationConflict

logger = logging.getLogger(__name__)
PREFIX = "/open-android-intelligence/v2/agents"
CAPABILITIES = ("list", "history", "create", "send", "rename", "archive", "delete", "cancel", "stream")
# Keep one explicit boundary across aiohttp versions and both session bridges.
SESSION_REQUEST_MAX_BYTES = 1024 * 1024


def session_request_json(body: Any) -> str:
    """Use the same UTF-8 JSON representation for validation and host requests."""
    return json.dumps(body, ensure_ascii=False, separators=(",", ":"))


class AdapterError(Exception):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code = code
        self.status = status


class SessionAdapter(Protocol):
    def descriptor(self) -> dict[str, Any]: ...
    async def status(self) -> bool: ...
    async def list_sessions(self, principal: Principal) -> list[dict[str, Any]]: ...
    async def history(self, principal: Principal, session_id: str) -> list[dict[str, Any]]: ...
    async def create(self, principal: Principal, title: str | None) -> dict[str, Any]: ...
    def send(self, principal: Principal, session_id: str, text: str) -> AsyncIterator[dict[str, Any]]: ...
    async def manage(self, principal: Principal, session_id: str, action: str, value: Any = None) -> dict[str, Any]: ...


class AgentRegistry:
    """Configuration is the discovery boundary; there is no ambient host scan."""

    def __init__(self, adapters: list[SessionAdapter] | None = None, *, operations_path: Path | None = None):
        self._adapters: dict[str, SessionAdapter] = {}
        for adapter in adapters or []:
            self.register(adapter)
        self.operations = NativeOperations(operations_path)

    def register(self, adapter: SessionAdapter) -> None:
        descriptor = adapter.descriptor()
        agent_id = descriptor.get("id")
        if not isinstance(agent_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", agent_id):
            raise ValueError("Agent id must be a URL-safe identifier")
        if agent_id in self._adapters:
            raise ValueError("Duplicate Agent id")
        if descriptor.get("sessionScope") not in {"account", "deployment"}:
            raise ValueError("Agent sessionScope must be explicit")
        if descriptor.get("kind") == "hermes" and descriptor["sessionScope"] != "account":
            raise ValueError("Hermes sessions must be account isolated")
        capabilities = descriptor.get("capabilities", {})
        if not isinstance(capabilities, Mapping) or any(type(v) is not bool for v in capabilities.values()):
            raise ValueError("Capabilities must be boolean")
        self._adapters[agent_id] = adapter

    def get(self, agent_id: str, capability: str) -> SessionAdapter:
        adapter = self._adapters.get(agent_id)
        if adapter is None:
            raise AdapterError("AGENT_NOT_FOUND", 404)
        if adapter.descriptor().get("capabilities", {}).get(capability) is not True:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 405)
        return adapter

    async def discover(self) -> list[dict[str, Any]]:
        async def describe(adapter: SessionAdapter) -> dict[str, Any]:
            try:
                online = bool(await asyncio.wait_for(adapter.status(), timeout=5))
            except Exception:
                online = False
            descriptor = adapter.descriptor()
            return {
                "id": descriptor["id"], "name": descriptor.get("name", descriptor["id"]),
                "kind": descriptor.get("kind", descriptor["id"]),
                "sessionScope": descriptor["sessionScope"], "online": online,
                "deviceTools": descriptor.get("deviceTools") is True,
                "capabilities": {key: descriptor.get("capabilities", {}).get(key) is True for key in CAPABILITIES},
            }
        return list(await asyncio.gather(*(describe(a) for a in self._adapters.values())))

    async def close(self) -> None:
        for adapter in self._adapters.values():
            close = getattr(adapter, "close", None)
            if close is not None:
                result = close()
                if inspect.isawaitable(result):
                    await result
        self.operations.close()


def build_registry(config: Mapping[str, Any], core: Any) -> AgentRegistry:
    """Only administrator-declared instances are eligible for native discovery."""
    entries = config.get("agents", [])
    if not isinstance(entries, list):
        raise ValueError("agents must be a list of explicit deployments")
    adapters = []
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("Agent configuration must be an object")
        kind = entry.get("type", entry.get("kind"))
        agent_id, name = entry.get("id", kind), entry.get("name", str(kind).capitalize())
        if kind == "codex":
            from .adapters.codex import CodexSessionAdapter
            if not isinstance(entry.get("codexHome"), str) or not entry["codexHome"].strip():
                raise ValueError("Codex requires an explicitly connected codexHome")
            command = entry.get("command")
            if not isinstance(command, list):
                raise ValueError("Codex requires an explicit command argument list")
            env_from = entry.get("envFrom", {})
            if not isinstance(env_from, dict) or any(not isinstance(k, str) or not isinstance(v, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", v) for k, v in env_from.items()):
                raise ValueError("Codex envFrom must map provider variables to environment names")
            environment = {}
            for key, source in env_from.items():
                value = os.environ.get(source)
                if not value:
                    raise ValueError("A configured Codex provider environment variable is unavailable")
                environment[key] = value
            adapters.append(CodexSessionAdapter(command, Path(entry["codexHome"]), adapter_id=agent_id, name=name, env=environment))
        elif kind in {"dsh", "hermes"}:
            token_env = entry.get("tokenEnv")
            if not isinstance(token_env, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", token_env):
                raise ValueError("Session bridge requires tokenEnv")
            token = os.environ.get(token_env, "")
            if len(token) < 32:
                raise ValueError("Session bridge token environment variable is unavailable")
            if not isinstance(entry.get("url"), str):
                raise ValueError("Session bridge requires an explicit url")
            if kind == "dsh":
                from .adapters.dsh import DshSessionAdapter
                adapters.append(DshSessionAdapter(entry["url"], token, agent_id=agent_id, name=name))
            else:
                from .adapters.hermes import HermesRemoteSessionAdapter
                accounts = entry.get("accounts")
                if not isinstance(accounts, dict) or not accounts or any(not isinstance(k, str) or not k or not isinstance(v, str) or not v for k, v in accounts.items()):
                    raise ValueError("Hermes requires explicit Core-to-Hermes account mappings")
                if len(set(accounts.values())) != len(accounts):
                    raise ValueError("Hermes account mappings must be one-to-one")
                adapters.append(HermesRemoteSessionAdapter(entry["url"], token, accounts, agent_id=agent_id, name=name))
        else:
            raise ValueError("Unsupported Agent type; OpenClaw is not part of this migration")
    return AgentRegistry(adapters, operations_path=Path(config["storageRoot"]) / "native-operations.sqlite")


Authenticate = Callable[[web.Request], Awaitable[Principal]]
IsActive = Callable[[Principal], bool | Awaitable[bool]]


def create_agent_routes(registry: AgentRegistry, authenticate: Authenticate, is_active: IsActive) -> web.RouteTableDef:
    routes = web.RouteTableDef()

    async def active(principal: Principal) -> bool:
        result = is_active(principal)
        return bool(await result) if inspect.isawaitable(result) else bool(result)

    async def principal_for(request: web.Request) -> Principal:
        principal = await authenticate(request)
        if not await active(principal):
            raise AdapterError("SESSION_REVOKED", 401)
        return principal

    def safe_session_id(request: web.Request) -> str:
        value = request.match_info["session_id"]
        if not value or value in {".", ".."} or any(c in value for c in ("/", "\\", "\x00")) or len(value) > 1024:
            raise AdapterError("INVALID_SESSION_ID")
        return value

    async def body_for(request: web.Request, fields: set[str]) -> dict[str, Any]:
        try:
            if len(await request.read()) >= SESSION_REQUEST_MAX_BYTES:
                raise AdapterError("MESSAGE_TOO_LARGE", 413)
            body = await request.json()
        except (ValueError, UnicodeError):
            raise AdapterError("INVALID_JSON") from None
        # No identity or device authority can be supplied in an Agent request.
        if not isinstance(body, dict) or not set(body).issubset(fields):
            raise AdapterError("INVALID_REQUEST")
        try:
            size = len(session_request_json(body).encode("utf-8"))
        except (ValueError, UnicodeError):
            raise AdapterError("INVALID_JSON") from None
        if size >= SESSION_REQUEST_MAX_BYTES:
            raise AdapterError("MESSAGE_TOO_LARGE", 413)
        return body

    def operation(request: web.Request, principal: Principal, body: Any) -> tuple[str, dict[str, Any] | None]:
        if principal.context is not None:
            request_id = principal.context.request_id
        else:
            keys = request.headers.getall("Idempotency-Key", [])
            if len(keys) != 1 or re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", keys[0]) is None:
                raise AdapterError("IDEMPOTENCY_KEY_REQUIRED")
            request_id = keys[0]
        try:
            receipt = registry.operations.begin(principal.account_id, request_id, request.method, request.raw_path, body)
        except OperationConflict as exc:
            raise AdapterError(exc.code, 409) from None
        return request_id, receipt

    def endpoint(handler: Callable[..., Awaitable[web.StreamResponse]]) -> Callable[..., Awaitable[web.StreamResponse]]:
        async def wrapped(request: web.Request) -> web.StreamResponse:
            try:
                return await handler(request)
            except AdapterError as exc:
                return web.json_response({"error": {"code": exc.code}}, status=exc.status)
            except GatewayError as exc:
                body = {"error": {"code": exc.code}}
                return web.json_response(body, status=_status(body))
            except web.HTTPRequestEntityTooLarge:
                # Reading may happen during signed authentication, before body_for.
                return web.json_response({"error": {"code": "MESSAGE_TOO_LARGE"}}, status=413)
            except web.HTTPException:
                raise
            except Exception:
                logger.exception("Native Agent request failed")
                return web.json_response({"error": {"code": "AGENT_UNAVAILABLE"}}, status=503)
        return wrapped

    @routes.get(PREFIX)
    @endpoint
    async def agents(request: web.Request) -> web.Response:
        await principal_for(request)
        return web.json_response({"agents": await registry.discover()})

    @routes.get(PREFIX + "/{agent_id}/sessions")
    @endpoint
    async def sessions(request: web.Request) -> web.Response:
        principal = await principal_for(request)
        adapter = registry.get(request.match_info["agent_id"], "list")
        return web.json_response({"sessions": await adapter.list_sessions(principal)})

    @routes.post(PREFIX + "/{agent_id}/sessions")
    @endpoint
    async def create(request: web.Request) -> web.Response:
        principal = await principal_for(request)
        adapter = registry.get(request.match_info["agent_id"], "create")
        body = await body_for(request, {"title"})
        title = body.get("title")
        if title is not None and (not isinstance(title, str) or len(title) > 1000):
            raise AdapterError("INVALID_TITLE")
        request_id, receipt = operation(request, principal, body)
        if receipt is None:
            receipt = {"session": await adapter.create(principal, title)}
            registry.operations.complete(principal.account_id, request_id, receipt)
        return web.json_response(receipt)

    @routes.get(PREFIX + "/{agent_id}/sessions/{session_id}/history")
    @endpoint
    async def history(request: web.Request) -> web.Response:
        principal = await principal_for(request)
        adapter = registry.get(request.match_info["agent_id"], "history")
        return web.json_response({"messages": await adapter.history(principal, safe_session_id(request))})

    @routes.post(PREFIX + "/{agent_id}/sessions/{session_id}/messages")
    @endpoint
    async def send(request: web.Request) -> web.StreamResponse:
        principal = await principal_for(request)
        adapter = registry.get(request.match_info["agent_id"], "send")
        registry.get(request.match_info["agent_id"], "stream")
        session_id = safe_session_id(request)
        body = await body_for(request, {"text"})
        text = body.get("text")
        if not isinstance(text, str) or not text.strip() or len(text) > 262144:
            raise AdapterError("INVALID_MESSAGE")
        request_id, receipt = operation(request, principal, body)
        if receipt is not None:
            # The transcript is read from its native host, never replayed from Core.
            raise AdapterError("REQUEST_ALREADY_EXECUTED", 409)
        stream = adapter.send(principal, session_id, text)
        response = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache"})
        await response.prepare(request)

        async def emit(event: dict[str, Any]) -> None:
            await response.write(("event: message\ndata: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode())

        pending: asyncio.Task | None = None
        try:
            completed = False
            iterator = stream.__aiter__()
            while True:
                if not await active(principal):
                    await emit({"type": "error", "code": "SESSION_REVOKED"})
                    completed = True
                    break
                if pending is None:
                    pending = asyncio.create_task(anext(iterator))
                done, _ = await asyncio.wait({pending}, timeout=5)
                if not done:
                    # Observe disconnection and revocation even while the host is idle.
                    await response.write(b": heartbeat\n\n")
                    continue
                try:
                    event = pending.result()
                except StopAsyncIteration:
                    pending = None
                    break
                pending = None
                if not await active(principal):
                    await emit({"type": "error", "code": "SESSION_REVOKED"})
                    completed = True
                    break
                if not isinstance(event, dict) or event.get("type") not in {"delta", "done", "error"}:
                    raise AdapterError("INVALID_AGENT_EVENT", 502)
                await emit(event)
                if event["type"] in {"done", "error"}:
                    registry.operations.complete(principal.account_id, request_id, {"state": event["type"]})
                    completed = True
                    break
            if not completed:
                await emit({"type": "error", "code": "AGENT_STREAM_INTERRUPTED"})
        except AdapterError as exc:
            await emit({"type": "error", "code": exc.code})
        except GatewayError as exc:
            await emit({"type": "error", "code": exc.code})
        except (ConnectionError, asyncio.CancelledError):
            raise
        except Exception:
            logger.exception("Native Agent stream failed")
            await emit({"type": "error", "code": "AGENT_UNAVAILABLE"})
        finally:
            if pending is not None:
                pending.cancel()
                await asyncio.gather(pending, return_exceptions=True)
            close = getattr(stream, "aclose", None)
            if close is not None:
                await close()
        await response.write_eof()
        return response

    async def manage(request: web.Request, principal: Principal, action: str, value: Any = None) -> web.Response:
        adapter = registry.get(request.match_info["agent_id"], action)
        session_id = safe_session_id(request)
        request_id, receipt = operation(request, principal, {"action": action, "value": value})
        if receipt is None:
            receipt = {"session": await adapter.manage(principal, session_id, action, value)}
            registry.operations.complete(principal.account_id, request_id, receipt)
        return web.json_response(receipt)

    @routes.patch(PREFIX + "/{agent_id}/sessions/{session_id}")
    @endpoint
    async def patch(request: web.Request) -> web.Response:
        # Authenticate before parsing the requested operation or resolving any native id.
        principal = await principal_for(request)
        body = await body_for(request, {"action", "value"})
        action = body.get("action")
        if action not in {"rename", "archive"}:
            raise AdapterError("INVALID_ACTION")
        value = body.get("value", True if action == "archive" else None)
        if action == "rename" and (not isinstance(value, str) or not value.strip() or len(value) > 1000):
            raise AdapterError("INVALID_TITLE")
        if action == "archive" and type(value) is not bool:
            raise AdapterError("INVALID_ACTION")
        return await manage(request, principal, action, value)

    @routes.delete(PREFIX + "/{agent_id}/sessions/{session_id}")
    @endpoint
    async def delete(request: web.Request) -> web.Response:
        return await manage(request, await principal_for(request), "delete")

    @routes.post(PREFIX + "/{agent_id}/sessions/{session_id}/cancel")
    @endpoint
    async def cancel(request: web.Request) -> web.Response:
        return await manage(request, await principal_for(request), "cancel")

    return routes
