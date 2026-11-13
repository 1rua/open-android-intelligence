"""Authoritative host-independent Gateway Protocol v2 runtime.

Optional Session Adapters may use these stores; the device state machine is
owned exclusively by this package. No host SDK or platform is loaded here.
"""
from .core import GatewayCore, GatewayError, create_gateway_core
from .verifier import AccountPasswordVerifier, GatewayRequestVerifier

__all__ = ["GatewayCore", "GatewayError", "create_gateway_core",
           "AccountPasswordVerifier", "GatewayRequestVerifier"]
