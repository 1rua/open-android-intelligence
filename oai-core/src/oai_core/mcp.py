"""Standard MCP stdio transport over the same authenticated Core client.

The MCP Python SDK renamed ``FastMCP`` to ``MCPServer`` in 2.0 while keeping the
decorator/run surface. This module imports through one compatibility shim so
Core works with either major version, and a plugin install never has to
downgrade the MCP package that a newer Hermes (or the system Python) ships.

2.0 also stopped forwarding unexpected exception text to the client: only an
anticipated ``ToolError`` keeps its message. Core's tool failures are expected
domain errors (``CoreClientError``/``ValueError``) with actionable text, so they
are re-raised as the SDK's own ``ToolError`` to preserve that text in both
majors.
"""

from __future__ import annotations

import asyncio
from typing import Any, Callable

try:  # mcp 1.x
    from mcp.server.fastmcp import FastMCP
    from mcp.server.fastmcp.exceptions import ToolError
except ModuleNotFoundError:  # mcp 2.x renamed the class and moved its module
    from mcp.server import MCPServer as FastMCP
    from mcp.server.mcpserver.exceptions import ToolError

from .client import CoreClient, CoreClientError


def _tool(server: Any, name: str) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Register a tool whose expected failures keep their operator-facing text."""
    import inspect

    def decorate(function: Callable[..., Any]) -> Callable[..., Any]:
        async def tool(**arguments: Any) -> Any:
            try:
                return await function(**arguments)
            except (CoreClientError, ValueError) as error:
                raise ToolError(str(error)) from error

        tool.__name__ = function.__name__
        tool.__doc__ = function.__doc__
        tool.__signature__ = inspect.signature(function)
        tool.__annotations__ = dict(getattr(function, "__annotations__", {}))
        return server.tool(name=name)(tool)

    return decorate


def create_mcp(client: CoreClient) -> FastMCP:
    server = FastMCP("OAI", instructions=(
        "Android operations use the authenticated OAI Core account and its selected device. "
        "List devices or inspect capabilities first. Submit once with an idempotency key, then read status. "
        "If reauthentication is required, run oai login in a terminal; do not enter secrets into MCP. "
        "A cancellation request does not prove that a claimed side effect was prevented. "
        "Acknowledge results after they have been consumed."
    ))

    @_tool(server, "oai_devices")
    async def devices() -> dict[str, Any]:
        """List paired devices visible to the authenticated account and its default."""
        return await asyncio.to_thread(client.devices)

    @_tool(server, "oai_capabilities")
    async def capabilities(device_id: str | None = None) -> dict[str, Any]:
        """Read capability schemas for an optional device selected by Core."""
        return await asyncio.to_thread(client.capabilities, device_id)

    @_tool(server, "oai_invoke")
    async def invoke(capability_id: str, capability_version: str,
                     parameters: dict[str, Any], idempotency_key: str,
                     agent_name: str | None = None, purpose: str | None = None,
                     device_id: str | None = None) -> dict[str, Any]:
        """Submit through Core. agent_name and purpose are mandatory untrusted display declarations.

        Missing declarations return CALLER_DECLARATION_REQUIRED, including calls
        from older MCP clients. Reuse the idempotency key for transport retries.
        """
        if not idempotency_key:
            raise ValueError("IDEMPOTENCY_KEY_REQUIRED")
        return await asyncio.to_thread(client.invoke, capability_id, capability_version, parameters, idempotency_key,
                                       agent_name=agent_name, purpose=purpose, device_id=device_id)

    @_tool(server, "oai_request_status")
    async def status(request_id: str) -> dict[str, Any]:
        """Read the authoritative state and any available result of an existing request."""
        return await asyncio.to_thread(client.status, request_id)

    @_tool(server, "oai_cancel_request")
    async def cancel(request_id: str) -> dict[str, Any]:
        """Ask Core to cancel a request; cancel_requested means the outcome is still unknown."""
        return await asyncio.to_thread(client.cancel, request_id)

    @_tool(server, "oai_ack_result")
    async def acknowledge(request_id: str, result_digest: str | None = None) -> dict[str, Any]:
        """Confirm result consumption so Core can discard transient device content."""
        return await asyncio.to_thread(client.acknowledge, request_id, result_digest)

    @_tool(server, "oai_register_endpoint")
    async def register_endpoint(registration_key: str, label: str, ttl_seconds: int = 3600) -> dict[str, Any]:
        """Register a leased receive route. The Android user must select its stable route before sending data."""
        return await asyncio.to_thread(client.register_endpoint, registration_key, label, ttl_seconds)

    @_tool(server, "oai_endpoints")
    async def endpoints() -> dict[str, Any]:
        """Discover routes visible through this credential's existing device binding."""
        return await asyncio.to_thread(client.endpoints)

    @_tool(server, "oai_unregister_endpoint")
    async def unregister_endpoint(endpoint_id: str) -> dict[str, Any]:
        """Retire an owned route and release its pending content."""
        return await asyncio.to_thread(client.unregister_endpoint, endpoint_id)

    @_tool(server, "oai_receive_deliveries")
    async def receive_deliveries(endpoint_id: str) -> dict[str, Any]:
        """Read pending content for an owned endpoint; reading never acknowledges consumption."""
        return await asyncio.to_thread(client.receive_deliveries, endpoint_id)

    @_tool(server, "oai_ack_delivery")
    async def ack_delivery(endpoint_id: str, delivery_id: str, event_id: str) -> dict[str, Any]:
        """Acknowledge an actually consumed event. Repeat the same ACK safely after transport failure."""
        return await asyncio.to_thread(client.acknowledge_delivery, endpoint_id, delivery_id, event_id)

    return server


def run(client: CoreClient) -> None:
    create_mcp(client).run(transport="stdio")
