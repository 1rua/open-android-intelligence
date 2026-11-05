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
from .gateway.transport import EVENT_STREAM_WS_PROBE_PATH, GatewayTransport
from .gateway.admin import HostApiCompatibility
from .gateway.core import GatewayCore, GatewayError, _now
from .gateway.http import _status, create_gateway_exposure
from .gateway.local_keys import LocalMasterKeyStore, ensure_master_key_file

from .auth import Authentication, Principal, raw_request
from .device_service import DeviceService
from .endpoints import EndpointService, create_endpoint_routes

GATEWAY_REVISION = "b64dda00a0de9ff1a95a375dcf92f62b5a1027b3"
DEVICE_API = "/open-android-intelligence/core/v1"
DEVICE_AUTHORIZATION_HEADER = "X-Open-Android-Intelligence-Device-Authorization"
DEVICE_AUTHORIZATION_MODEL = "android-v1"
CORE_KEY = web.AppKey("oai_core", GatewayCore)
AUTH_KEY = web.AppKey("authentication", Authentication)
DEVICE_KEY = web.AppKey("devices", DeviceService)
ENDPOINT_KEY = web.AppKey("endpoints", EndpointService)
REGISTRY_KEY = web.AppKey("agents", object)
logger = logging.getLogger(__name__)


class OAICore(GatewayCore):
    """Device protocol compatibility with native sessions exposed separately."""

    native_conversation_ui: frozenset[str] = frozenset()
    identity_store: Any = None

    def account_exists(self, account_id: str) -> bool:
        if self.identity_store is None:
            return super().account_exists(account_id)
        try:
            self.identity_store.account(account_id)
            return True
        except GatewayError:
            return False

    def list_gateway_account_ids(self) -> list[str]:
        if self.identity_store is None:
            return super().list_gateway_account_ids()
        with self.identity_store.transaction() as database:
            return [row[0] for row in database.execute("SELECT account_id FROM accounts WHERE status='active' ORDER BY account_id")]

    def open_gateway_account(self, account_id: str):
        if self.identity_store is not None:
            self.identity_store.account(account_id)
        account = super().open_gateway_account(account_id)
        if self.identity_store is not None:
            account.store.identity_store = self.identity_store
            account.credentials.identity_store = self.identity_store
            account.sessions.identity_store = self.identity_store
        return account

    def event_session_failure(self, context, now=None):
        failure = super().event_session_failure(context, now)
        if failure is not None or self.identity_store is None:
            return failure
        from contextlib import closing
        import sqlite3
        from .gateway.account_paths import account_paths
        try:
            path = account_paths(self.storage_root, context.account_id).database
            with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as database:
                row = database.execute("SELECT value FROM account_metadata WHERE key=?", ("core-session-epoch:" + context.session_id,)).fetchone()
            if row is None:
                return "SESSION_REVOKED"
            self.identity_store.assert_epoch(context.account_id, int(row[0]))
        except (GatewayError, ValueError, sqlite3.Error):
            return "SESSION_REVOKED"
        return None

    def _handle_session_password(self, request: Any):
        if self.identity_store is not None:
            from .gateway.core import _failure, _request_body
            body = _request_body(request)
            context = self._pre_auth_context(request, body if isinstance(body, dict) else {})
            try:
                if not isinstance(body, dict) or not self.contracts.validate("session.password", body):
                    raise GatewayError("SCHEMA_INVALID")
                # Verify before bind_negotiation opens/initializes the business
                # account. A wrong password may not create that account's files.
                self.identity_store.authenticate(body["username"], body["password"],
                    purpose="android-password", client_id=body["installation"]["installationId"])
            except GatewayError as error:
                return _failure(context, error.code, error.details)
        return super()._handle_session_password(request)

    def _handle_session_refresh(self, request: Any):
        headers = {str(k).lower(): v for k, v in (request.get("headers") or {}).items()}
        if self.identity_store is not None and "idempotency-key" in headers:
            from .android_refresh import refresh_operation
            return refresh_operation(self, request)
        return super()._handle_session_refresh(request)

    def _handle_pairing_invite(self, request: Any):
        if self.identity_store is not None and self.identity_store._policy()[0]:
            from .gateway.core import _failure, _request_body
            body = _request_body(request)
            return _failure(self._pre_auth_context(request, body if isinstance(body, dict) else {}), "MFA_REQUIRED")
        return super()._handle_pairing_invite(request)

    def _build_negotiation_response(self, body: Any, account: Any = None) -> Any:
        response = dict(super()._build_negotiation_response(body, account))
        features = dict(response["features"])
        # The native-session marker routes these interactions through /agents.
        # Do not promise legacy chat endpoints or unimplemented batch features.
        features.pop("conversationUi", None)
        agreed = [feature for feature in body["features"].get("conversationUi", [])
                  if feature in self.native_conversation_ui]
        if agreed:
            features["conversationUi"] = agreed
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
    config["_configPath"] = str(source)
    if config.get("identityDatabase"):
        config["identityDatabase"] = str((source.parent / config["identityDatabase"]).resolve())
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
    identity_path = Path(config.get("identityDatabase", storage.parent / "security" / "identity.sqlite"))
    if identity_path.exists() or identity_path.is_symlink():
        from .identity import IdentityStore
        core.identity_store = IdentityStore(identity_path, key_store=key_store)
    core.credential_verifier = AccountPasswordVerifier(core)
    # Fail at startup, rather than exposing a listener with unavailable contracts.
    core.contracts.core_schema_hash
    return core


@web.middleware
async def errors(request: web.Request, handler: Any) -> web.StreamResponse:
    try:
        return await handler(request)
    except GatewayError as exc:
        body = {"error": {"code": exc.code, "message": exc.code, "retryable": exc.code == "DEVICE_OFFLINE", "details": exc.details}}
        status = _status(body)
        if exc.code in {"CAPABILITY_DENIED", "GRANT_STALE", "PAIRING_GENERATION_STALE", "IDENTITY_OVERRIDE_REJECTED", "DEVICE_ACCESS_DENIED", "DEVICE_REQUEST_ACCESS_DENIED", "ADMIN_REQUIRED", "MANAGEMENT_REQUIRED"}:
            status = 403
        if exc.code in {"MFA_REQUIRED", "MFA_ENROLLMENT_REQUIRED", "AUTHENTICATION_EXPIRED", "SESSION_EXPIRED", "SESSION_REVOKED", "LOGIN_REQUIRED"}:
            status = 401
        if exc.code == "DEVICE_SELECTION_REQUIRED":
            status = 409
        return web.json_response(body, status=status)
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return web.json_response({"error": {"code": "SCHEMA_INVALID"}}, status=400)


class CoreTransport(GatewayTransport):
    """Public device routes over the host-independent transport."""

    async def dispatch(self, request: web.Request) -> web.StreamResponse:
        path = request.path
        negotiating = request.method == "POST" and path == "/open-android-intelligence/v2/negotiate"
        authorization_models = request.headers.getall(DEVICE_AUTHORIZATION_HEADER, []) if negotiating else []
        account_auth = request.headers.getall("X-OAI-Account-Auth", []) if negotiating else []
        if account_auth and account_auth != ["v1"]:
            raise GatewayError("SCHEMA_INVALID")
        if authorization_models and authorization_models != [DEVICE_AUTHORIZATION_MODEL]:
            raise GatewayError("SCHEMA_INVALID", {"reason": "DEVICE_AUTHORIZATION_MODEL_INVALID"})
        if negotiating:
            registry = request.app[REGISTRY_KEY]
            self.services.core.native_conversation_ui = registry.conversation_ui(await registry.discover())
        if request.method == "PUT" and re.fullmatch(r"/open-android-intelligence/v2/attachments/[^/]+/content", path):
            return await self._handle_attachment_content_stream(request)
        raw = raw_request(request, await request.read())
        if path == EVENT_STREAM_WS_PROBE_PATH:
            return await self._handle_ws_probe(request, raw)
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
            if account_auth and self.services.core.identity_store is not None:
                headers["X-OAI-Account-Auth"] = "v1"
            if (request.headers.getall("X-Open-Android-Intelligence-Refresh-Replay", []) == ["v1"]
                    and self.services.core.identity_store is not None):
                headers["X-Open-Android-Intelligence-Refresh-Replay"] = "v1"
            if authorization_models:
                # Optional, explicit semantics outside the closed JSON schema.
                # A legacy client must never infer this agreement from v2 alone.
                headers[DEVICE_AUTHORIZATION_HEADER] = DEVICE_AUTHORIZATION_MODEL
        return web.json_response(result["body"], status=result["statusCode"], headers=headers)


def create_app(config: dict[str, Any], registry: Any = None, core: GatewayCore | None = None) -> web.Application:
    from .sessions import create_agent_routes
    core = core if core is not None else create_core(config)
    auth = Authentication(core, Path(config["storageRoot"]) / "core-tokens.sqlite")
    devices = DeviceService(core, auth)
    endpoints = EndpointService(core, auth, Path(config["storageRoot"]) / "push-endpoints.sqlite")
    if core.identity_store is not None:
        from .agent_registration import AgentRegistration

        def origin_principal_active(principal: Principal) -> bool:
            try:
                auth.assert_current(principal)
                return True
            except GatewayError:
                return False

        def origin_active(facts: Any) -> bool:
            from .gateway.core import VerifiedRequestContext
            try:
                context = VerifiedRequestContext(account_id=facts["accountId"], device_id=facts["deviceId"],
                    session_id=facts["deviceSessionId"], request_id=facts["turnId"], correlation_id=facts["turnId"],
                    pairing_generation=facts["pairingGeneration"], grant_revision=facts["grantRevision"])
                return core.is_event_session_active(context)
            except (GatewayError, KeyError, ValueError, TypeError):
                return False

        instance_id = core.identity_store._policy()[2]
        core.agent_registration = AgentRegistration(instance_id, core.identity_store,
            origin_principal_active, origin_active, core_signing_key=core.secret_store.instance_signing_key(instance_id))
    if registry is None:
        from .sessions import build_registry
        registry = build_registry(config, core)
    app = web.Application(middlewares=[errors], client_max_size=1024 * 1024)
    app[CORE_KEY], app[AUTH_KEY], app[DEVICE_KEY], app[REGISTRY_KEY] = core, auth, devices, registry
    app[ENDPOINT_KEY] = endpoints
    routes = web.RouteTableDef()

    @routes.post(DEVICE_API + "/tokens")
    async def login(request: web.Request) -> web.Response:
        return web.json_response(await auth.async_login(await request.json(), request.remote or ""), headers={"Cache-Control": "no-store"})

    @routes.post(DEVICE_API + "/tokens/complete")
    async def complete_login(request: web.Request) -> web.Response:
        return web.json_response(auth.complete_login(await request.json()), headers={"Cache-Control": "no-store"})

    @routes.post(DEVICE_API + "/tokens/refresh")
    async def refresh_login(request: web.Request) -> web.Response:
        return web.json_response(auth.refresh(await request.json()), headers={"Cache-Control": "no-store"})

    @routes.post(DEVICE_API + "/tokens/revoke")
    async def revoke_login(request: web.Request) -> web.Response:
        auth.revoke_refresh(await request.json())
        return web.json_response({"revoked": True}, headers={"Cache-Control": "no-store"})

    def mfa_challenge(body: Any) -> tuple[Any, str]:
        if core.identity_store is None or not isinstance(body, dict) or not isinstance(body.get("challengeId"), str):
            raise GatewayError("AUTHENTICATION_FAILED")
        identity = core.identity_store
        with identity.transaction() as database:
            row = database.execute("SELECT client_id FROM challenges WHERE digest=? AND purpose='login'",
                (hashlib.sha256(body["challengeId"].encode()).hexdigest(),)).fetchone()
            if row is None:
                raise GatewayError("AUTHENTICATION_FAILED")
            account_id = identity.challenge_account(body["challengeId"], purpose="login", client_id=row[0])
        return identity, account_id

    @routes.post(DEVICE_API + "/mfa/enroll")
    async def enroll_mfa(request: web.Request) -> web.Response:
        body = await request.json()
        if not isinstance(body, dict) or set(body) != {"challengeId"}:
            raise GatewayError("SCHEMA_INVALID")
        identity, account_id = mfa_challenge(body)
        result = identity.begin_mfa_enrollment(account_id, body["challengeId"])
        return web.json_response({"enrollmentId": result["enrollmentId"], "provisioningUri": result.get("uri", result.get("provisioningUri")),
            **({"secret": result["secret"]} if result.get("secret") else {})}, headers={"Cache-Control": "no-store"})

    @routes.post(DEVICE_API + "/mfa/confirm")
    async def confirm_mfa(request: web.Request) -> web.Response:
        body = await request.json()
        if not isinstance(body, dict) or set(body) != {"challengeId", "enrollmentId", "otp"}:
            raise GatewayError("SCHEMA_INVALID")
        identity, account_id = mfa_challenge(body)
        return web.json_response(identity.confirm_mfa_enrollment(account_id, body["challengeId"], body["otp"],
            enrollment_id=body["enrollmentId"]), headers={"Cache-Control": "no-store"})

    @routes.delete(DEVICE_API + "/tokens/current")
    async def logout(request: web.Request) -> web.Response:
        auth.revoke(await auth.authenticate(request))
        return web.json_response({"revoked": True})

    @routes.get(DEVICE_API + "/devices")
    async def list_devices(request: web.Request) -> web.Response:
        return web.json_response(devices.devices(await auth.authenticate(request)))

    @routes.put(DEVICE_API + "/devices/default")
    async def set_default(request: web.Request) -> web.Response:
        return web.json_response(devices.set_default(await auth.authenticate(request), await request.json()))

    @routes.get(DEVICE_API + "/capabilities")
    async def capabilities(request: web.Request) -> web.Response:
        if set(request.query) - {"deviceId"} or len(request.query.getall("deviceId", [])) > 1:
            raise GatewayError("SCHEMA_INVALID")
        return web.json_response(devices.capabilities(await auth.authenticate(request), request.query.get("deviceId")))

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
        raw = await request.read()
        body = json.loads(raw) if raw else {}
        if not isinstance(body, dict) or set(body) - {"resultDigest"} or "resultDigest" in body and not isinstance(body["resultDigest"], str):
            raise GatewayError("SCHEMA_INVALID")
        return web.json_response(devices.acknowledge(await auth.authenticate(request), request.match_info["requestId"], body.get("resultDigest")))

    def is_active(principal: Principal) -> bool:
        try:
            auth.assert_current(principal)
            return True
        except GatewayError:
            return False

    app.add_routes(routes)
    if core.identity_store is not None:
        from .android_auth import AndroidAccountAuthentication
        android_auth = AndroidAccountAuthentication(core)
        app.add_routes(android_auth.routes())
    app.add_routes(create_endpoint_routes(endpoints, auth))
    app.add_routes(create_agent_routes(registry, auth.authenticate, is_active, core.contracts, core=core))
    # This compatibility range describes the pinned Core Python interface. It
    # makes no claim that a Hermes host process is installed or online.
    exposure = create_gateway_exposure("host-route", core=core, host_version="2.1.0",
        host_api=HostApiCompatibility("2.1.0", "2.1.0", GATEWAY_REVISION), verify_request=auth.verifier)
    transport = CoreTransport(SimpleNamespace(core=core, exposure=exposure))
    app.router.add_route("*", "/open-android-intelligence/v2/{tail:.*}", transport.dispatch)

    async def maintenance() -> None:
        while True:
            try:
                endpoints.sweep()
            except Exception:
                logger.warning("Core push buffer maintenance failed; retrying on the next sweep")
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
        await transport.close_websockets()
        await registry.close()

    async def cleanup(_: web.Application) -> None:
        endpoints.close()
        auth.close()

    app.on_startup.append(startup)
    app.on_shutdown.append(shutdown)
    app.on_cleanup.append(cleanup)
    return app


def run(config_path: str | Path) -> None:
    config = load_config(config_path)
    identity_path = Path(config.get("identityDatabase", Path(config["storageRoot"]).parent / "security" / "identity.sqlite"))
    if not identity_path.is_file():
        raise GatewayError("IDENTITY_DATABASE_MISSING", {"reason": "Run the authenticated installation or migration before starting Core"})
    listener = config.get("listen", {})
    host, port = listener.get("host", "0.0.0.0"), int(listener.get("port", 11451))
    tls = config.get("tls", {})
    ssl_context = None
    if tls.get("certFile") and tls.get("keyFile"):
        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.minimum_version = ssl.TLSVersion.TLSv1_2
        ssl_context.load_cert_chain(tls["certFile"], tls["keyFile"])
    web.run_app(create_app(config), host=host, port=port, ssl_context=ssl_context)
