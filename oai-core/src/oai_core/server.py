"""Standalone Core listener and authoritative Gateway security runtime."""
from __future__ import annotations

import asyncio
import contextlib
import hashlib
import ipaddress
import json
import logging
import re
import ssl
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from aiohttp import web
from .gateway.verifier import AccountPasswordVerifier
from .gateway.transport import GatewayTransport
from .gateway.admin import HostApiCompatibility
from .gateway.core import GatewayCore, GatewayError, _now
from .gateway.http import _status, create_gateway_exposure
from .gateway.local_keys import LocalMasterKeyStore, ensure_master_key_file

from .auth import Authentication, Principal, raw_request
from .device_service import DeviceService

GATEWAY_REVISION = "b64dda00a0de9ff1a95a375dcf92f62b5a1027b3"
DEVICE_API = "/open-android-intelligence/core/v1"
CORE_KEY = web.AppKey("oai_core", GatewayCore)
AUTH_KEY = web.AppKey("authentication", Authentication)
DEVICE_KEY = web.AppKey("devices", DeviceService)
REGISTRY_KEY = web.AppKey("agents", object)
logger = logging.getLogger(__name__)


class OAICore(GatewayCore):
    """Device protocol compatibility with native sessions exposed separately."""

    def _build_negotiation_response(self, body: Any, account: Any = None) -> Any:
        response = dict(super()._build_negotiation_response(body, account))
        features = dict(response["features"])
        # These optional features promise legacy conversation endpoints.
        # Native adapter capabilities are negotiated by /agents instead.
        features.pop("conversationUi", None)
        response["features"] = features
        return response


def load_config(path: str | Path) -> dict[str, Any]:
    source = Path(path).resolve()
    config = json.loads(source.read_text())
    if not isinstance(config, dict):
        raise ValueError("Core settings must be an object")
    for name in ("storageRoot", "masterKeyFile"):
        if not isinstance(config.get(name), str) or not config[name]:
            raise ValueError(f"Core settings require {name}")
        config[name] = str((source.parent / config[name]).resolve())
    if config.get("contractRoot") is not None:
        if not isinstance(config["contractRoot"], str) or not config["contractRoot"]:
            raise ValueError("contractRoot must be a nonempty path when provided")
        config["contractRoot"] = str((source.parent / config["contractRoot"]).resolve())
    for name in ("certFile", "keyFile"):
        if config.get("tls", {}).get(name):
            config["tls"][name] = str((source.parent / config["tls"][name]).resolve())
    for agent in config.get("agents", []):
        for name in ("codexHome", "gatewayRoot"):
            if agent.get(name):
                agent[name] = str((source.parent / agent[name]).resolve())
    return config


def create_core(config: dict[str, Any]) -> GatewayCore:
    storage = Path(config["storageRoot"])
    key = Path(config["masterKeyFile"])
    if key.resolve().is_relative_to(storage.resolve()):
        raise ValueError("masterKeyFile must be outside storageRoot so backups exclude keys")
    if not key.exists() and any(storage.glob("*/gateway.sqlite")):
        raise ValueError("Existing Core accounts require their original master key; restore it before starting")
    key_store = LocalMasterKeyStore(ensure_master_key_file(key))
    key_store.validate()
    spki = config.get("tlsSpkiSha256")
    if config.get("tls", {}).get("certFile"):
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
        cert = x509.load_pem_x509_certificate(Path(config["tls"]["certFile"]).read_bytes())
        spki = "sha256:" + hashlib.sha256(cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()
    core = OAICore(storage_root=storage, secret_store=key_store, contract_root=config.get("contractRoot"), command_catalog=(), tls_spki_sha256=spki)
    core.credential_verifier = AccountPasswordVerifier(core)
    # Fail at startup, rather than exposing a listener with unavailable contracts.
    core.contracts.core_schema_hash
    return core


@web.middleware
async def errors(request: web.Request, handler: Any) -> web.StreamResponse:
    try:
        return await handler(request)
    except GatewayError as exc:
        body = {"error": {"code": exc.code, "message": exc.code, "retryable": False, "details": exc.details}}
        status = _status(body)
        if exc.code in {"CAPABILITY_DENIED", "GRANT_STALE", "PAIRING_GENERATION_STALE", "IDENTITY_OVERRIDE_REJECTED"}:
            status = 403
        return web.json_response(body, status=status)
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return web.json_response({"error": {"code": "SCHEMA_INVALID"}}, status=400)


class CoreTransport(GatewayTransport):
    """Public device routes over the host-independent transport."""

    async def dispatch(self, request: web.Request) -> web.StreamResponse:
        path = request.path
        if request.method == "PUT" and re.fullmatch(r"/open-android-intelligence/v2/attachments/[^/]+/content", path):
            return await self._handle_attachment_content_stream(request)
        raw = raw_request(request, await request.read())
        if path in {"/open-android-intelligence/v2/events", "/open-android-intelligence/v2/events/ws"}:
            if "websocket" in request.headers.get("Upgrade", "").lower():
                return await self._handle_ws_stream(request, raw)
            if "text/event-stream" in request.headers.get("Accept", "").lower():
                return await self._handle_sse_stream(request, raw)
        if path.startswith("/open-android-intelligence/v2/conversations") or path.startswith("/open-android-intelligence/v2/conversation-organization"):
            raise GatewayError("CAPABILITY_UNSUPPORTED")
        route = next((r for r in self.services.exposure.routes if r.path == path or r.match == "prefix" and path.startswith(r.path)), None)
        if route is None:
            raise web.HTTPNotFound()
        # Password hashing is CPU work. The upstream Core bounds its admissions.
        result = await asyncio.to_thread(route._handle_raw, raw) if path.endswith("/sessions/password") else route._handle_raw(raw)
        if result.get("stream") is not None:
            raise GatewayError("CAPABILITY_UNSUPPORTED")
        headers = {k: v for k, v in result["headers"].items() if k.lower() != "content-type"}
        if path == "/open-android-intelligence/v2/negotiate" and result["statusCode"] == 200:
            headers["X-OAI-Agent-Sessions"] = "1"
        return web.json_response(result["body"], status=result["statusCode"], headers=headers)


def create_app(config: dict[str, Any], registry: Any = None, core: GatewayCore | None = None) -> web.Application:
    from .sessions import create_agent_routes
    core = core if core is not None else create_core(config)
    auth = Authentication(core, Path(config["storageRoot"]) / "core-tokens.sqlite")
    devices = DeviceService(core, auth)
    if registry is None:
        from .sessions import build_registry
        registry = build_registry(config, core)
    # If Core was initialized without command_catalog, wire registry.commands to provide host agent commands
    if core.command_catalog == ():
        core.command_catalog = lambda: registry.commands("en")
    app = web.Application(middlewares=[errors], client_max_size=1024 * 1024)
    app[CORE_KEY], app[AUTH_KEY], app[DEVICE_KEY], app[REGISTRY_KEY] = core, auth, devices, registry
    routes = web.RouteTableDef()

    @routes.post(DEVICE_API + "/tokens")
    async def login(request: web.Request) -> web.Response:
        return web.json_response(await auth.async_login(await request.json(), request.remote or ""), headers={"Cache-Control": "no-store"})

    @routes.delete(DEVICE_API + "/tokens/current")
    async def logout(request: web.Request) -> web.Response:
        auth.revoke(await auth.authenticate(request))
        return web.json_response({"revoked": True})

    @routes.get(DEVICE_API + "/devices")
    async def list_devices(request: web.Request) -> web.Response:
        return web.json_response(devices.devices(await auth.authenticate(request)))

    @routes.get(DEVICE_API + "/capabilities")
    async def capabilities(request: web.Request) -> web.Response:
        return web.json_response(devices.capabilities(await auth.authenticate(request)))

    @routes.post(DEVICE_API + "/device-requests")
    async def submit(request: web.Request) -> web.Response:
        principal = await auth.authenticate(request)
        keys = request.headers.getall("Idempotency-Key", [])
        if len(keys) != 1:
            raise GatewayError("IDEMPOTENCY_KEY_REQUIRED")
        return web.json_response(devices.submit(principal, await request.json(), keys[0]))

    @routes.get(DEVICE_API + "/device-requests/{requestId}")
    async def get_request(request: web.Request) -> web.Response:
        return web.json_response(devices.get(await auth.authenticate(request), request.match_info["requestId"]))

    @routes.post(DEVICE_API + "/device-requests/{requestId}/cancel")
    async def cancel(request: web.Request) -> web.Response:
        if await request.read() not in (b"", b"{}"):
            raise GatewayError("SCHEMA_INVALID")
        return web.json_response(devices.cancel(await auth.authenticate(request), request.match_info["requestId"]))

    @routes.post(DEVICE_API + "/device-requests/{requestId}/ack")
    async def acknowledge(request: web.Request) -> web.Response:
        if await request.read() not in (b"", b"{}"):
            raise GatewayError("SCHEMA_INVALID")
        return web.json_response(devices.acknowledge(await auth.authenticate(request), request.match_info["requestId"]))

    def is_active(principal: Principal) -> bool:
        try:
            auth.assert_current(principal)
            return True
        except GatewayError:
            return False

    app.add_routes(routes)
    app.add_routes(create_agent_routes(registry, auth.authenticate, is_active))
    # This compatibility range describes the pinned Core Python interface. It
    # makes no claim that a Hermes host process is installed or online.
    exposure = create_gateway_exposure("host-route", core=core, host_version="2.1.0",
        host_api=HostApiCompatibility("2.1.0", "2.1.0", GATEWAY_REVISION), verify_request=auth.verifier)
    transport = CoreTransport(SimpleNamespace(core=core, exposure=exposure))
    app.router.add_route("*", "/open-android-intelligence/v2/{tail:.*}", transport.dispatch)

    async def maintenance() -> None:
        while True:
            for account_id in core.list_gateway_account_ids():
                account = None
                try:
                    account = core.open_gateway_account(account_id)
                    with account.store.transaction():
                        # Use the same native expiry transition as claim/result;
                        # it also removes parameters from retained event frames.
                        rows = account.store.database.execute("SELECT * FROM device_requests WHERE expires_at<=? AND state IN ('pending','claimed','cancel_requested') LIMIT 1000", (_now().isoformat(timespec="milliseconds").replace("+00:00", "Z"),)).fetchall()
                        for row in rows:
                            account.device_requests._expire_if_due(row, _now())
                        account.device_requests.purge_terminal_payloads()
                        account.events.purge_expired()
                except Exception:
                    logger.warning("Core account maintenance failed; retrying on the next sweep")
                finally:
                    if account is not None:
                        account.close()
            await asyncio.sleep(1)

    async def startup(_: web.Application) -> None:
        transport._running = True
        transport._loop = asyncio.get_running_loop()
        core.register_event_sink(transport._event_sink)
        transport._maintenance_task = asyncio.create_task(maintenance())

    async def shutdown(_: web.Application) -> None:
        transport._running = False
        core.unregister_event_sink(transport._event_sink)
        if transport._maintenance_task is not None:
            transport._maintenance_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await transport._maintenance_task
        await registry.close()

    async def cleanup(_: web.Application) -> None:
        auth.close()

    app.on_startup.append(startup)
    app.on_shutdown.append(shutdown)
    app.on_cleanup.append(cleanup)
    return app


def run(config_path: str | Path) -> None:
    config = load_config(config_path)
    listener = config.get("listen", {})
    host, port = listener.get("host", "0.0.0.0"), int(listener.get("port", 11451))
    tls = config.get("tls", {})
    ssl_context = None
    if tls.get("certFile") and tls.get("keyFile"):
        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.minimum_version = ssl.TLSVersion.TLSv1_2
        ssl_context.load_cert_chain(tls["certFile"], tls["keyFile"])
    web.run_app(create_app(config), host=host, port=port, ssl_context=ssl_context)
