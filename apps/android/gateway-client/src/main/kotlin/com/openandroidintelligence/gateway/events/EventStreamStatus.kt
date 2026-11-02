package com.openandroidintelligence.gateway.events

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The observable health of the Gateway event stream.
 *
 * Network interruptions report RECONNECTING while bounded retries continue.
 * FAILED is reserved for authentication, identity or local delivery failures
 * that require intervention; it does not stand in for Core HTTP reachability.
 */
enum class EventStreamStatus {
    /** No attempt has been made yet. */
    IDLE,

    /** Opening a channel (WebSocket handshake or SSE request). */
    CONNECTING,

    /** The WS TLS/authenticated upgrade is ready, or SSE has delivered a complete frame. */
    LIVE,

    /** The last attempt failed and another one is already scheduled. */
    RECONNECTING,

    /** A terminal authorization, identity or local delivery failure needs intervention. */
    FAILED,
}

/**
 * The outlet the transport reports its health through.
 *
 * It is a plain state holder rather than an Android type so the module stays
 * testable on a JVM and the app decides where the value is rendered.
 */
class EventStreamStatusSink {
    private val _status = MutableStateFlow(EventStreamStatus.IDLE)
    val status: StateFlow<EventStreamStatus> = _status.asStateFlow()

    fun report(next: EventStreamStatus) {
        _status.value = next
    }
}
