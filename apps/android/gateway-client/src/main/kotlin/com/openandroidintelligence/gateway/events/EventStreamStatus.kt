package com.openandroidintelligence.gateway.events

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The observable health of the Gateway event stream.
 *
 * The transport used to swallow every failure and silently reconnect forever,
 * so nothing above it could tell "connected" from "dead". The status is now
 * reported on every transition, and [FAILED] is what a screen shows when the
 * stream has actually given up rather than what it guesses from silence.
 */
enum class EventStreamStatus {
    /** No attempt has been made yet. */
    IDLE,

    /** Opening a channel (WebSocket handshake or SSE request). */
    CONNECTING,

    /** A channel is open and has delivered at least one event. */
    LIVE,

    /** The last attempt failed and another one is already scheduled. */
    RECONNECTING,

    /** The stream stopped retrying; a human has to act. */
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
