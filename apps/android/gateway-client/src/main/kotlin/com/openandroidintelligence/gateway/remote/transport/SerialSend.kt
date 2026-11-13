/*
 * 基于 ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7 的 packages/webrtc/src/serial-send.ts。
 * Copyright (c) 2026 DSH Remote contributors，MIT 许可。
 * 完整许可见 gateway-client/src/test/resources/dsh-remote/LICENSE.upstream。
 */
package com.openandroidintelligence.gateway.remote.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select

/** 整个加密及发送过程按受理顺序串行执行，额度来自固定上游 SerialSend。 */
class SerialSend : AutoCloseable {
    private val lock = Any()
    private var tail = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val stopped = CompletableDeferred<Unit>()
    private var bytes = 0L
    private var count = 0
    private var closed = false

    suspend fun <T> run(payloadBytes: Int, send: suspend () -> T): T {
        val ticket = synchronized(lock) {
            if (closed) fail("RTC_CLOSED")
            if (payloadBytes < 0 || bytes + payloadBytes > MAX_PENDING_BYTES || count >= MAX_PENDING_SENDS) fail("SEND_QUEUE_LIMIT")
            val next = CompletableDeferred<Unit>()
            Ticket(tail, next).also {
                tail = next
                bytes += payloadBytes
                count += 1
                next.invokeOnCompletion { synchronized(lock) { bytes -= payloadBytes; count -= 1 } }
            }
        }
        try {
            select<Unit> {
                stopped.onAwait { fail("RTC_CLOSED") }
                ticket.previous.onAwait { }
            }
            currentCoroutineContext().ensureActive()
            synchronized(lock) { if (closed) fail("RTC_CLOSED") }
            return send()
        } finally {
            // 等待中的调用取消时仍要保留前驱关系，不能使后面的消息越过正在发送的消息。
            if (ticket.previous.isCompleted) ticket.done.complete(Unit)
            else ticket.previous.invokeOnCompletion { ticket.done.complete(Unit) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stopped.complete(Unit)
        }
    }

    private class Ticket(val previous: CompletableDeferred<Unit>, val done: CompletableDeferred<Unit>)

    companion object {
        const val MAX_PENDING_BYTES = 8 * 1024 * 1024L
        const val MAX_PENDING_SENDS = 256
    }
}
