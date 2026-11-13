"""Untrusted caller declarations, separate from authenticated routing identity."""
from __future__ import annotations

from typing import Any


def caller_declaration(value: Any) -> dict[str, str]:
    from .gateway.core import GatewayError

    if (not isinstance(value, dict) or set(value) != {"agentName", "purpose"}
            or any(not isinstance(value.get(key), str) or not value[key].strip()
                   for key in ("agentName", "purpose"))):
        raise GatewayError("CALLER_DECLARATION_REQUIRED")
    if len(value["agentName"]) > 128 or len(value["purpose"]) > 2048:
        raise GatewayError("SCHEMA_INVALID")
    return {key: value[key].strip() for key in ("agentName", "purpose")}
