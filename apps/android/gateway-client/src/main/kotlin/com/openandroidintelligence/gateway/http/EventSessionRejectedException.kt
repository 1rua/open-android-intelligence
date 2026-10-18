package com.openandroidintelligence.gateway.http

/** An authoritative stream authentication rejection must not reconnect forever. */
class EventSessionRejectedException(val rejectedProfile: GatewayProfile? = null) : java.io.IOException("EVENT_SESSION_REJECTED")
