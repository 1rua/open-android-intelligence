"""Standard MCP stdio transport over the same authenticated Core client."""

from __future__ import annotations

import asyncio
from typing import Any

from mcp.server.fastmcp import FastMCP

from .client import CoreClient


def create_mcp(client: CoreClient) -> FastMCP:
    server = FastMCP("OAI", instructions=(
        "Android operations use the authenticated OAI Core credential and its paired device. "
        "Inspect capabilities first. Submit once with an idempotency key, then read status. "
        "A cancellation request does not prove that a claimed side effect was prevented. "
        "Acknowledge results after they have been consumed."
    ))

    @server.tool(name="oai_capabilities")
    async def capabilities() -> dict[str, Any]:
        """Read this credential's paired Android capability schemas and permissions."""
        return await asyncio.to_thread(client.capabilities)

    @server.tool(name="oai_invoke")
    async def invoke(capability_id: str, capability_version: str,
                     parameters: dict[str, Any], idempotency_key: str) -> dict[str, Any]:
        """Submit a device operation to Core. Reuse the key for retries of this operation."""
        if not idempotency_key:
            raise ValueError("IDEMPOTENCY_KEY_REQUIRED")
        return await asyncio.to_thread(client.invoke, capability_id, capability_version, parameters, idempotency_key)

    @server.tool(name="oai_request_status")
    async def status(request_id: str) -> dict[str, Any]:
        """Read the authoritative state and any available result of an existing request."""
        return await asyncio.to_thread(client.status, request_id)

    @server.tool(name="oai_cancel_request")
    async def cancel(request_id: str) -> dict[str, Any]:
        """Ask Core to cancel a request; cancel_requested means the outcome is still unknown."""
        return await asyncio.to_thread(client.cancel, request_id)

    @server.tool(name="oai_ack_result")
    async def acknowledge(request_id: str) -> dict[str, Any]:
        """Confirm result consumption so Core can discard transient device content."""
        return await asyncio.to_thread(client.acknowledge, request_id)

    return server


def run(client: CoreClient) -> None:
    create_mcp(client).run(transport="stdio")
