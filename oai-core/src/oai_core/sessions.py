"""Authenticated access to explicitly registered, native Agent sessions."""

from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import logging
import os
import re
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from dataclasses import dataclass
from importlib.metadata import entry_points
from pathlib import Path
from typing import Any, Protocol

from aiohttp import web
from .gateway.core import APPROVAL_CHOICES, ContractRegistry, GatewayError, iso_millis
from .gateway.http import _status

from .auth import Principal
from .operations import NativeOperations, OperationConflict

logger = logging.getLogger(__name__)
PREFIX = "/open-android-intelligence/v2/agents"
CAPABILITIES = (
    "list", "history", "create", "send", "steer", "rename", "archive", "delete",
    "cancel", "stream", "commands", "approvals", "attachments",
)
STREAM_EVENT_TYPES = frozenset({"delta", "done", "error", "generation", "approval_requested", "approval_resolved"})
PROTOCOL_VERSIONS = ("1.0", "v1")
# Keep one explicit boundary across aiohttp versions and both session bridges.
SESSION_REQUEST_MAX_BYTES = 1024 * 1024


def session_request_json(body: Any) -> str:
    """Use the same UTF-8 JSON representation for validation and host requests."""
    return json.dumps(body, ensure_ascii=False, separators=(",", ":"))


class AdapterError(Exception):
    def __init__(self, code: str, status: int = 400, *, details: Mapping[str, Any] | None = None):
        super().__init__(code)
        self.code = code
        self.status = status
        self.details = dict(details or {})


@dataclass(frozen=True)
class NativeAttachment:
    """A verified account-owned attachment, read in bounded decrypted chunks."""
    core: Any
    principal: Principal
    metadata: dict[str, Any]
    is_active: Callable[[], Awaitable[bool]] | None = None

    async def assert_active(self) -> None:
        if self.is_active is not None and not await self.is_active():
            raise AdapterError("SESSION_REVOKED", 401)

    def chunks(self):
        account = self.core.open_gateway_account(self.principal.account_id)
        try:
            _validate_attachment_owner(account, self.principal, self.metadata["attachmentId"])
            yield from account.attachments.open_verified_stream(self.metadata["attachmentId"])
        finally:
            account.close()


def _validate_attachment_owner(account: Any, principal: Principal, attachment_id: str) -> None:
    row = account.store.database.execute(
        "SELECT owner_device_id, owner_pairing_generation FROM attachments WHERE attachment_id=?", (attachment_id,)).fetchone()
    if (row is None or not principal.device_id or principal.pairing_generation is None
            or row["owner_device_id"] != principal.device_id
            or row["owner_pairing_generation"] != principal.pairing_generation):
        raise AdapterError("ATTACHMENT_ACCESS_DENIED", 403)
    account.attachments.require_verified_for_message(attachment_id)


def native_attachments(core: Any, principal: Principal, attachment_ids: list[str],
                       is_active: Callable[[], Awaitable[bool]] | None = None) -> list[NativeAttachment]:
    if not attachment_ids:
        return []
    if core is None:
        raise AdapterError("ATTACHMENTS_UNAVAILABLE", 503)
    account = core.open_gateway_account(principal.account_id)
    try:
        result = []
        for attachment_id in attachment_ids:
            _validate_attachment_owner(account, principal, attachment_id)
            result.append(NativeAttachment(core, principal, account.attachments.get_record(attachment_id), is_active))
        return result
    finally:
        account.close()


class SessionAdapter(Protocol):
    def descriptor(self) -> dict[str, Any]: ...
    async def status(self) -> bool: ...
    async def list_sessions(self, principal: Principal) -> list[dict[str, Any]]: ...
    async def history(self, principal: Principal, session_id: str) -> list[dict[str, Any]]: ...
    async def create(self, principal: Principal, title: str | None) -> dict[str, Any]: ...
    def send(self, principal: Principal, session_id: str, text: str) -> AsyncIterator[dict[str, Any]]: ...
    async def current_generation(self, principal: Principal, session_id: str) -> dict[str, Any] | None: ...
    async def generation(self, principal: Principal, session_id: str, *, client_message_id: str | None = None,
                         generation_id: str | None = None, after_revision: int | None = None,
                         wait_ms: int = 0) -> dict[str, Any] | None: ...
    async def steer(self, principal: Principal, session_id: str, text: str, generation_id: str,
                    client_message_id: str | None = None) -> dict[str, Any]: ...
    async def manage(self, principal: Principal, session_id: str, action: str, value: Any = None) -> dict[str, Any]: ...


AdapterFactory = Callable[[dict[str, Any], Path, Any], SessionAdapter]


class AgentRegistry:
    """Configuration is the discovery boundary; there is no ambient host scan."""

    _factories: dict[str, AdapterFactory] = {}

    def __init__(self, adapters: list[SessionAdapter] | None = None, *, operations_path: Path | None = None):
        self._adapters: dict[str, SessionAdapter] = {}
        for adapter in adapters or []:
            self.register(adapter)
        self.operations = NativeOperations(operations_path)

    @classmethod
    def register_factory(cls, kind: str, factory: AdapterFactory) -> None:
        """Register an adapter factory for a specific kind/type identifier."""
        if not isinstance(kind, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", kind):
            raise ValueError("Factory kind must be a URL-safe identifier")
        cls._factories[kind] = factory

    @classmethod
    def get_factory(cls, kind: str) -> AdapterFactory | None:
        return cls._factories.get(kind)

    def register(self, adapter: SessionAdapter) -> None:
        descriptor = adapter.descriptor()
        agent_id = descriptor.get("id")
        if not isinstance(agent_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", agent_id):
            raise ValueError("Agent id must be a URL-safe identifier")
        if agent_id in self._adapters:
            raise ValueError("Duplicate Agent id")
        session_scope = descriptor.get("sessionScope")
        if session_scope not in {"account", "deployment"}:
            raise ValueError("Agent sessionScope must be explicit")
        
        # Capability and declaration validation
        capabilities = descriptor.get("capabilities", {})
        if not isinstance(capabilities, Mapping) or any(type(v) is not bool for v in capabilities.values()):
            raise ValueError("Capabilities must be boolean")

        # Specific safety rule validations declared by adapters or known conventions
        if descriptor.get("kind") == "hermes" and descriptor["sessionScope"] != "account":
            raise ValueError("Hermes sessions must be account isolated")
        if descriptor.get("kind") == "hermes":
            self._validate_hermes_account_mapping(adapter)

        # Multi-instance account mapping collision prevention for any adapter with account mapping
        if hasattr(adapter, "accounts") and hasattr(adapter, "account_scope"):
            self._validate_account_isolation(adapter)

        self._adapters[agent_id] = adapter

    def _validate_hermes_account_mapping(self, adapter: SessionAdapter) -> None:
        from .adapters.hermes import HermesRemoteSessionAdapter

        if not isinstance(adapter, HermesRemoteSessionAdapter):
            return
        for existing in self._adapters.values():
            if not isinstance(existing, HermesRemoteSessionAdapter) or existing.account_scope != adapter.account_scope:
                continue
            native_owners = {native: owner for owner, native in existing.accounts.items()}
            for owner, native in adapter.accounts.items():
                if ((native in native_owners and native_owners[native] != owner)
                        or (owner in existing.accounts and existing.accounts[owner] != native)):
                    raise ValueError("Hermes account mappings must be one-to-one across Agent aliases")

    def _validate_account_isolation(self, adapter: Any) -> None:
        for existing in self._adapters.values():
            if existing is adapter or not hasattr(existing, "accounts") or not hasattr(existing, "account_scope"):
                continue
            if getattr(existing, "account_scope", None) != getattr(adapter, "account_scope", None):
                continue
            native_owners = {native: owner for owner, native in existing.accounts.items()}
            for owner, native in adapter.accounts.items():
                if ((native in native_owners and native_owners[native] != owner)
                        or (owner in existing.accounts and existing.accounts[owner] != native)):
                    raise ValueError("Adapter account mappings must be one-to-one across shared host scope")

    def get(self, agent_id: str, capability: str | None = None) -> SessionAdapter:
        adapter = self._adapters.get(agent_id)
        if adapter is None:
            raise AdapterError("AGENT_NOT_FOUND", 404)
        if capability is not None and adapter.descriptor().get("capabilities", {}).get(capability) is not True:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 405)
        return adapter

    async def commands(self, language_code: str = "en") -> list[dict[str, Any]]:
        """Collect and deduplicate commands from all registered agents that provide them."""
        entries: dict[str, dict[str, Any]] = {}
        for adapter in self._adapters.values():
            cmd_fn = getattr(adapter, "commands", None)
            if not callable(cmd_fn):
                continue
            try:
                res = cmd_fn(language_code)
                items = await res if inspect.isawaitable(res) else res
                for item in items or []:
                    if isinstance(item, dict) and "invocation" in item and item["invocation"] not in entries:
                        entries[item["invocation"]] = item
            except Exception as err:
                logger.warning("Failed to collect commands from adapter '%s': %s", getattr(adapter, "agent_id", "unknown"), err)
        return list(entries.values())

    @staticmethod
    def conversation_ui(agents: list[dict[str, Any]]) -> frozenset[str]:
        supported: set[str] = set()
        for agent in agents:
            if not agent.get("online"):
                continue
            capabilities = agent.get("capabilities", {})
            if capabilities.get("commands") is True:
                supported.add("agent-command-catalog-v1")
            if capabilities.get("send") is not True or capabilities.get("stream") is not True:
                continue
            if capabilities.get("cancel") is True:
                supported.add("generation-cancel-v1")
            if capabilities.get("approvals") is True:
                supported.add("agent-approval-cards-v1")
        return frozenset(supported)

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


def _create_codex_adapter(entry: dict[str, Any], base_dir: Path, core: Any) -> SessionAdapter:
    from .adapters.codex import CodexSessionAdapter
    agent_id = entry.get("id", "codex")
    name = entry.get("name", "Codex")
    if not isinstance(entry.get("codexHome"), str) or not entry["codexHome"].strip():
        raise ValueError("Codex requires an explicitly connected codexHome")
    command = entry.get("command")
    if not isinstance(command, list):
        raise ValueError("Codex requires an explicit command argument list")
    env_from = entry.get("envFrom", {})
    if not isinstance(env_from, dict) or any(
        not isinstance(k, str) or not isinstance(v, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", v)
        for k, v in env_from.items()
    ):
        raise ValueError("Codex envFrom must map provider variables to environment names")
    environment = {}
    for key, source in env_from.items():
        value = os.environ.get(source)
        if not value:
            raise ValueError("A configured Codex provider environment variable is unavailable")
        environment[key] = value
    return CodexSessionAdapter(command, Path(entry["codexHome"]), adapter_id=agent_id, name=name, env=environment)


def _create_dsh_adapter(entry: dict[str, Any], base_dir: Path, core: Any) -> SessionAdapter:
    from .adapters.dsh import DshSessionAdapter
    agent_id = entry.get("id", "dsh")
    name = entry.get("name", "DSH")
    token_env = entry.get("tokenEnv")
    if not isinstance(token_env, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", token_env):
        raise ValueError("Session bridge requires tokenEnv")
    token = os.environ.get(token_env, "")
    if len(token) < 32:
        raise ValueError("Session bridge token environment variable is unavailable")
    if not isinstance(entry.get("url"), str):
        raise ValueError("Session bridge requires an explicit url")
    return DshSessionAdapter(entry["url"], token, agent_id=agent_id, name=name)


def _create_hermes_adapter(entry: dict[str, Any], base_dir: Path, core: Any) -> SessionAdapter:
    from .adapters.hermes import HermesRemoteSessionAdapter
    agent_id = entry.get("id", "hermes")
    name = entry.get("name", "Hermes")
    token_env = entry.get("tokenEnv")
    if not isinstance(token_env, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", token_env):
        raise ValueError("Session bridge requires tokenEnv")
    token = os.environ.get(token_env, "")
    if len(token) < 32:
        raise ValueError("Session bridge token environment variable is unavailable")
    if not isinstance(entry.get("url"), str):
        raise ValueError("Session bridge requires an explicit url")
    accounts = entry.get("accounts")
    if not isinstance(accounts, dict) or not accounts or any(
        not isinstance(k, str) or not k or not isinstance(v, str) or not v for k, v in accounts.items()
    ):
        raise ValueError("Hermes requires explicit Core-to-Hermes account mappings")
    if len(set(accounts.values())) != len(accounts):
        raise ValueError("Hermes account mappings must be one-to-one")
    return HermesRemoteSessionAdapter(entry["url"], token, accounts, agent_id=agent_id, name=name, attachment_core=core)


def _create_remote_adapter(entry: dict[str, Any], base_dir: Path, core: Any) -> SessionAdapter:
    from .adapters.remote import RemoteSessionAdapter
    kind = str(entry.get("type", entry.get("kind", "remote")))
    agent_id = entry.get("id", kind)
    name = entry.get("name", agent_id.capitalize())
    token_env = entry.get("tokenEnv")
    token = ""
    if token_env:
        if not isinstance(token_env, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", token_env):
            raise ValueError("Session bridge requires valid tokenEnv identifier")
        token = os.environ.get(token_env, "")
        if len(token) < 32:
            raise ValueError("Session bridge token environment variable is unavailable or under 32 chars")
    elif isinstance(entry.get("token"), str):
        token = entry["token"]
        if len(token) < 32:
            raise ValueError("Session bridge token must be at least 32 characters")
    else:
        raise ValueError("Remote adapter requires tokenEnv or token")

    if not isinstance(entry.get("url"), str):
        raise ValueError("Session bridge requires an explicit url")

    session_scope = entry.get("sessionScope", "deployment")
    if session_scope not in {"account", "deployment"}:
        raise ValueError("sessionScope must be 'account' or 'deployment'")

    device_tools = bool(entry.get("deviceTools", False))
    prefix = str(entry.get("prefix", "/oai/session-adapter/v1"))

    # Custom account mappings if provided
    accounts = entry.get("accounts")
    headers_builder = None
    if accounts is not None:
        if not isinstance(accounts, dict) or not accounts or any(
            not isinstance(k, str) or not k or not isinstance(v, str) or not v for k, v in accounts.items()
        ):
            raise ValueError("Account mappings must be a non-empty mapping of string IDs")
        if len(set(accounts.values())) != len(accounts):
            raise ValueError("Account mappings must be one-to-one")

        def _headers(principal: Principal) -> dict[str, str]:
            account_id = accounts.get(getattr(principal, "account_id", None))
            if account_id is None:
                raise AdapterError("AGENT_ACCOUNT_NOT_CONNECTED", 403)
            result = {"X-OAI-Account": account_id}
            device_id = getattr(principal, "device_id", None)
            if device_id:
                result["X-OAI-Device"] = device_id
            return result

        headers_builder = _headers

    adapter = RemoteSessionAdapter(
        entry["url"],
        token,
        agent_id=agent_id,
        name=name,
        kind=kind,
        session_scope=session_scope,
        prefix=prefix,
        device_tools=device_tools,
        headers_builder=headers_builder,
    )
    if accounts is not None:
        setattr(adapter, "accounts", dict(accounts))
        from yarl import URL
        import ipaddress
        origin = URL(entry["url"])
        host = origin.raw_host.rstrip(".")
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            host = "loopback" if host == "localhost" else host
        else:
            host = "loopback" if address.is_loopback else address.compressed
        setattr(adapter, "account_scope", (origin.scheme, host, origin.port or (443 if origin.scheme == "https" else 80)))
    return adapter


# Register standard built-in factories
AgentRegistry.register_factory("codex", _create_codex_adapter)
AgentRegistry.register_factory("dsh", _create_dsh_adapter)
AgentRegistry.register_factory("hermes", _create_hermes_adapter)
AgentRegistry.register_factory("remote", _create_remote_adapter)


def _load_entry_point_factories() -> None:
    """Discover explicitly installed plugin adapter factories via Python entry points."""
    try:
        eps = entry_points(group="open_android_intelligence.agent_adapters")
        for ep in eps:
            try:
                factory = ep.load()
                if callable(factory):
                    AgentRegistry.register_factory(ep.name, factory)
            except Exception as err:
                logger.warning("Failed to load agent adapter plugin '%s': %s", ep.name, err)
    except Exception:
        pass


_load_entry_point_factories()


def build_registry(config: Mapping[str, Any], core: Any) -> AgentRegistry:
    """Only administrator-declared instances are eligible for native discovery."""
    entries = config.get("agents", [])
    if not isinstance(entries, list):
        raise ValueError("agents must be a list of explicit deployments")
    storage_root = Path(config["storageRoot"]) if "storageRoot" in config else Path(".")
    adapters = []
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("Agent configuration must be an object")
        kind = entry.get("type", entry.get("kind"))
        if not kind or not isinstance(kind, str):
            raise ValueError("Agent entry missing 'type' or 'kind'")
        
        factory = AgentRegistry.get_factory(kind)
        if factory is not None:
            adapter = factory(entry, storage_root, core)
            adapters.append(adapter)
        elif kind == "openclaw":
            raise ValueError("Unsupported Agent type; OpenClaw is not part of this migration")
        else:
            raise ValueError(f"Unknown or uninstalled Agent type: '{kind}'")

    return AgentRegistry(adapters, operations_path=storage_root / "native-operations.sqlite")


Authenticate = Callable[[web.Request], Awaitable[Principal]]
IsActive = Callable[[Principal], bool | Awaitable[bool]]


def create_agent_routes(registry: AgentRegistry, authenticate: Authenticate, is_active: IsActive,
                        contracts: ContractRegistry | None = None, *, core: Any = None) -> web.RouteTableDef:
    routes = web.RouteTableDef()
    contracts = contracts if contracts is not None else ContractRegistry()

    def validate_approval(approval: Any, *, resolved: bool) -> None:
        event_type = "conversation.approval.resolved" if resolved else "conversation.approval.requested"
        if not contracts.validate_dispatched(contracts.dispatched_registry_id,
                {"kind": "event", "eventType": event_type},
                {"correlationId": "native-approval", "occurredAt": iso_millis(), "payload": approval}):
            raise AdapterError("INVALID_AGENT_EVENT", 502)

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
                return web.json_response({"error": {"code": exc.code,
                    **({"details": exc.details} if exc.details else {})}}, status=exc.status)
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

    @routes.get(PREFIX + "/{agent_id}/commands")
    @endpoint
    async def agent_commands(request: web.Request) -> web.Response:
        principal = await principal_for(request)
        agent_id = request.match_info["agent_id"]
        adapter = registry.get(agent_id, "commands")
        cmd_fn = getattr(adapter, "commands", None)
        if not callable(cmd_fn):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        language_code = request.query.get("languageCode", "en")
        if not language_code or len(language_code) > 80 or set(request.query) - {"languageCode"}:
            raise AdapterError("INVALID_REQUEST")
        kwargs = {"principal": principal} if "principal" in inspect.signature(cmd_fn).parameters else {}
        res = cmd_fn(language_code, **kwargs)
        cmds = await res if inspect.isawaitable(res) else res
        if not isinstance(cmds, list):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        catalog = {"format": "agent-command-catalog-1.0", "languageCode": language_code,
                   "catalogVersion": "cmdcat_" + hashlib.sha256(
                       json.dumps(cmds, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:16],
                   "commands": cmds}
        if not contracts.validate("agent.commandCatalog", catalog):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        return web.json_response(catalog, headers={"Cache-Control": "no-store"})

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

    @routes.get(PREFIX + "/{agent_id}/sessions/{session_id}/generation")
    @endpoint
    async def current_generation(request: web.Request) -> web.Response:
        principal = await principal_for(request)
        session_id = safe_session_id(request)
        query = request.query
        if (set(query) - {"clientMessageId", "generationId", "afterRevision", "waitMs"}
                or any(len(query.getall(key)) != 1 for key in query)):
            raise AdapterError("INVALID_GENERATION_QUERY")
        client_message_id, generation_id = query.get("clientMessageId"), query.get("generationId")
        if (client_message_id is not None and re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", client_message_id) is None
                or generation_id is not None and (not generation_id or len(generation_id) > 1024)):
            raise AdapterError("INVALID_GENERATION_QUERY")
        specific = client_message_id is not None or generation_id is not None
        wait_options: dict[str, int] = {}
        for key, option, maximum in (("afterRevision", "after_revision", 2**63 - 1), ("waitMs", "wait_ms", 25000)):
            if key in query:
                value = query[key]
                if not specific or re.fullmatch(r"0|[1-9][0-9]{0,18}", value) is None or int(value) > maximum:
                    raise AdapterError("INVALID_GENERATION_QUERY")
                wait_options[option] = int(value)
        if wait_options.get("wait_ms", 0) and "after_revision" not in wait_options:
            raise AdapterError("INVALID_GENERATION_QUERY")
        # Durable receipts remain readable after the host stops accepting new
        # work. Authentication and the adapter's account/session checks still apply.
        adapter = registry.get(request.match_info["agent_id"], None if specific else "send")
        current = getattr(adapter, "generation" if specific else "current_generation", None)
        if not callable(current):
            raise AdapterError("UNSUPPORTED_CAPABILITY", 501)
        generation = (await current(principal, session_id, client_message_id=client_message_id, generation_id=generation_id, **wait_options)
                      if specific else await current(principal, session_id))
        if specific and not await active(principal):
            raise AdapterError("SESSION_REVOKED", 401)
        states = {"queued", "running", "unknown", "completed", "failed", "cancelled"} if specific else {"queued", "running", "unknown"}
        if generation is not None and (
            not isinstance(generation, dict)
            or not isinstance(generation.get("generationId"), str)
            or not generation["generationId"]
            or generation.get("conversationId") != session_id
            or generation.get("state") not in states
            or specific and (
                client_message_id is not None and generation.get("clientMessageId") != client_message_id
                or generation_id is not None and generation.get("generationId") != generation_id
                or type(generation.get("revision")) is not int or generation["revision"] < 0)
        ):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        return web.json_response({"generation": generation}, headers={"Cache-Control": "no-store"})

    @routes.post(PREFIX + "/{agent_id}/sessions/{session_id}/messages")
    @endpoint
    async def send(request: web.Request) -> web.StreamResponse:
        principal = await principal_for(request)
        adapter = registry.get(request.match_info["agent_id"], "send")
        registry.get(request.match_info["agent_id"], "stream")
        session_id = safe_session_id(request)
        body = await body_for(request, {"text", "attachmentIds", "clientMessageId"})
        text = body.get("text", "")
        attachment_ids = body.get("attachmentIds", [])
        client_message_id = body.get("clientMessageId")
        if (not isinstance(attachment_ids, list) or len(attachment_ids) > 256
                or any(not isinstance(item, str) or not re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", item) for item in attachment_ids)
                or len(set(attachment_ids)) != len(attachment_ids)):
            raise AdapterError("INVALID_ATTACHMENT")
        if (not isinstance(text, str) or (not text.strip() and not attachment_ids) or len(text) > 262144
                or client_message_id is not None and (not isinstance(client_message_id, str)
                    or re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", client_message_id) is None)):
            raise AdapterError("INVALID_MESSAGE")
        kwargs = {}
        if attachment_ids:
            registry.get(request.match_info["agent_id"], "attachments")
            kwargs["attachments"] = await asyncio.to_thread(native_attachments, core, principal, attachment_ids, lambda: active(principal))
        if client_message_id is not None and "client_message_id" in inspect.signature(adapter.send).parameters:
            kwargs["client_message_id"] = client_message_id
        request_id, receipt = operation(request, principal, body)
        if receipt is not None:
            # The transcript is read from its native host, never replayed from Core.
            raise AdapterError("REQUEST_ALREADY_EXECUTED", 409)
        stream = adapter.send(principal, session_id, text, **kwargs)
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
                if not isinstance(event, dict) or event.get("type") not in STREAM_EVENT_TYPES:
                    raise AdapterError("INVALID_AGENT_EVENT", 502)
                if event["type"] == "generation" and (
                        not isinstance(event.get("generationId"), str) or not event["generationId"]):
                    raise AdapterError("INVALID_AGENT_EVENT", 502)
                if event["type"] in {"approval_requested", "approval_resolved"}:
                    registry.get(request.match_info["agent_id"], "approvals")
                    validate_approval(event.get("approval"), resolved=event["type"] == "approval_resolved")
                    if event["approval"]["conversationId"] != session_id or set(event) - {"type", "approval", "generationId"}:
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

    @routes.post(PREFIX + "/{agent_id}/sessions/{session_id}/steer")
    @endpoint
    async def steer(request: web.Request) -> web.Response:
        principal = await principal_for(request)
        adapter = registry.get(request.match_info["agent_id"])
        session_id = safe_session_id(request)
        body = await body_for(request, {"text", "generationId", "clientMessageId"})
        text = body.get("text")
        generation_id = body.get("generationId")
        client_message_id = body.get("clientMessageId")
        if (not isinstance(text, str) or not text.strip() or len(text) > 262144
                or generation_id is not None and (
                    not isinstance(generation_id, str) or not generation_id or len(generation_id) > 1024
                )
                or client_message_id is not None and (
                    not isinstance(client_message_id, str)
                    or re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", client_message_id) is None
                )):
            raise AdapterError("INVALID_REQUEST")
        steer_fn = getattr(adapter, "steer", None)
        if not callable(steer_fn):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        request_id, receipt = operation(request, principal, body)
        if receipt is None:
            if generation_id is None:
                current = getattr(adapter, "current_generation", None)
                if not callable(current):
                    raise AdapterError("HOST_INCOMPATIBLE", 502)
                generation = await current(principal, session_id)
                if generation is None:
                    receipt = {"error": {"code": "STEER_UNAVAILABLE"}}
                    registry.operations.complete(principal.account_id, request_id, receipt)
                    return web.json_response(receipt, status=409, headers={"Cache-Control": "no-store"})
                if (not isinstance(generation, dict)
                        or generation.get("conversationId") != session_id
                        or not isinstance(generation.get("generationId"), str)
                        or not generation["generationId"]
                        or generation.get("state") not in {"queued", "running", "unknown"}):
                    raise AdapterError("HOST_INCOMPATIBLE", 502)
                generation_id = generation["generationId"]
            try:
                steering = await steer_fn(principal, session_id, text, generation_id, client_message_id)
            except AdapterError as exc:
                if exc.code != "STEER_UNAVAILABLE":
                    raise
                receipt = {"error": {"code": exc.code}}
                registry.operations.complete(principal.account_id, request_id, receipt)
                return web.json_response(receipt, status=409, headers={"Cache-Control": "no-store"})
            if (not isinstance(steering, dict)
                    or set(steering) != {"generationId", "outcome"}
                    or steering.get("generationId") != generation_id
                    or steering.get("outcome") not in {"accepted"}):
                raise AdapterError("HOST_INCOMPATIBLE", 502)
            receipt = {"steering": steering}
            registry.operations.complete(principal.account_id, request_id, receipt)
        status = 409 if receipt.get("error", {}).get("code") == "STEER_UNAVAILABLE" else 200
        return web.json_response(receipt, status=status, headers={"Cache-Control": "no-store"})

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
        principal = await principal_for(request)
        body = await body_for(request, {"generationId"}) if await request.read() else {}
        generation_id = body.get("generationId")
        if "generationId" in body and (not isinstance(generation_id, str) or not generation_id or len(generation_id) > 1024):
            raise AdapterError("INVALID_REQUEST")
        return await manage(request, principal, "cancel", body if body else None)

    @routes.post(PREFIX + "/{agent_id}/sessions/{session_id}/approvals/{approval_id}/decisions")
    @endpoint
    async def approval_decision(request: web.Request) -> web.Response:
        principal = await principal_for(request)
        adapter = registry.get(request.match_info["agent_id"], "approvals")
        session_id = safe_session_id(request)
        approval_id = request.match_info["approval_id"]
        if not approval_id or len(approval_id) > 1024 or any(c in approval_id for c in ("/", "\\", "\x00")) or approval_id in {".", ".."}:
            raise AdapterError("INVALID_REQUEST")
        body = await body_for(request, {"decision"})
        decision = body.get("decision")
        if decision not in APPROVAL_CHOICES:
            raise AdapterError("APPROVAL_DECISION_INVALID")
        resolver = getattr(adapter, "resolve_approval", None)
        if not callable(resolver):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        request_id, receipt = operation(request, principal, body)
        terminal_errors = {"APPROVAL_ALREADY_RESOLVED": 409, "APPROVAL_EXPIRED": 409,
                           "APPROVAL_NOT_FOUND": 404, "APPROVAL_DECISION_INVALID": 400}
        if receipt is None:
            try:
                receipt = await resolver(principal, session_id, approval_id, decision)
            except AdapterError as exc:
                if exc.details:
                    validate_approval(exc.details, resolved=True)
                    if (exc.details["approvalId"] != approval_id
                            or exc.details["conversationId"] != session_id):
                        raise AdapterError("HOST_INCOMPATIBLE", 502) from None
                if exc.code not in terminal_errors:
                    raise
                receipt = {"error": {"code": exc.code, **({"details": exc.details} if exc.details else {})}}
                registry.operations.complete(principal.account_id, request_id, receipt)
                return web.json_response(receipt, status=terminal_errors[exc.code], headers={"Cache-Control": "no-store"})
            if not isinstance(receipt, dict) or set(receipt) != {"approval"}:
                raise AdapterError("HOST_INCOMPATIBLE", 502)
            validate_approval(receipt["approval"], resolved=True)
            if (receipt["approval"]["approvalId"] != approval_id
                    or receipt["approval"]["conversationId"] != session_id
                    or receipt["approval"]["decision"] != decision):
                raise AdapterError("HOST_INCOMPATIBLE", 502)
            registry.operations.complete(principal.account_id, request_id, receipt)
        status = terminal_errors.get(receipt.get("error", {}).get("code"), 200)
        return web.json_response(receipt, status=status, headers={"Cache-Control": "no-store"})

    return routes
