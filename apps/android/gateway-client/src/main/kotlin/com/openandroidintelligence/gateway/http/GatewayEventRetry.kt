package com.openandroidintelligence.gateway.http

import java.io.IOException

internal class EventSnapshotRequestException(val status: Int) : IOException("EVENT_SNAPSHOT_HTTP_FAILED:$status")

class EventStreamForbiddenException : IOException("EVENT_SESSION_FORBIDDEN")
enum class EventStreamTimeoutStage { HANDSHAKE, IDLE }
class EventStreamTimeoutException(val stage: EventStreamTimeoutStage) : IOException("EVENT_STREAM_${stage.name}_TIMEOUT")

/** Small delay policy shared by reconnects and probes; it does not own a connection or a coroutine. */
internal class GatewayEventRetry(
    private val steps: List<Long>,
    private val jitter: (Long) -> Long,
) {
    private var index = 0
    fun reset() { index = 0 }
    fun nextDelayMillis(): Long {
        val base = steps[index]
        if (index < steps.lastIndex) index++
        return jitter(base).coerceIn((base - base / 5).coerceAtLeast(1), minOf(base + base / 5, steps.last()))
    }

    companion object {
        val RECONNECT_MILLIS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
        val PROBE_MILLIS = listOf(60_000L, 120_000L, 300_000L, 600_000L, 900_000L)
        fun jitter(base: Long): Long = base + kotlin.random.Random.nextLong(-base / 5, base / 5 + 1)
    }
}
