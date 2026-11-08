package com.openandroidintelligence.gateway.http

/** An authoritative stream authentication rejection must not reconnect forever. */
class EventSessionRejectedException @JvmOverloads constructor(
    val rejectedProfile: GatewayProfile? = null,
    /** Known HTTP/SSE/WS handshake status; null means the source did not expose one. */
    val httpStatus: Int? = null,
    /** Known WebSocket close code; null means the rejection did not come from a close frame. */
    val closeCode: Int? = null,
) : java.io.IOException("EVENT_SESSION_REJECTED")
