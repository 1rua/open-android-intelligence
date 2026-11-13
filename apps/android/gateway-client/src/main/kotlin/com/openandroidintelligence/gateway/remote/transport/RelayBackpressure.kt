/*
 * 改写自 ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7 的 packages/webrtc/src/websocket-backpressure.ts。
 * Copyright (c) 2026 DSH Remote contributors，MIT 许可。
 * 完整许可见 gateway-client/src/test/resources/dsh-remote/LICENSE.upstream。
 */
package com.openandroidintelligence.gateway.remote.transport

import kotlinx.coroutines.delay

/** Relay 专用规则，不把 WebSocket 的阈值误施加给 RTC。必须在串行发送块内调用。 */
object RelayBackpressure {
    suspend fun waitForCapacity(socket: BufferedRelaySocket, clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
        val started = clockMillis()
        while (socket.bufferedAmount > MAX_BUFFERED_BYTES) {
            if (!socket.isOpen || clockMillis() - started > MAX_WAIT_MS) fail("RELAY_SLOW_OR_CLOSED")
            delay(POLL_INTERVAL_MS)
        }
        if (!socket.isOpen) fail("RELAY_CLOSED")
    }

    const val MAX_BUFFERED_BYTES = 512 * 1024L
    const val MAX_WAIT_MS = 10_000L
    const val POLL_INTERVAL_MS = 10L
}

interface BufferedRelaySocket {
    val isOpen: Boolean
    val bufferedAmount: Long
}
