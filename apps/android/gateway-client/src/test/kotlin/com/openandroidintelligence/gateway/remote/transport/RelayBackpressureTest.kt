package com.openandroidintelligence.gateway.remote.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayBackpressureTest {
    @Test
    fun 缓冲超过五百一十二KiB会等待而到阈值即可发送() = runTest {
        val socket = Socket(bufferedAmount = 512 * 1024 + 1L)
        val waiting = async { RelayBackpressure.waitForCapacity(socket) { testScheduler.currentTime } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        socket.bufferedAmount = 512 * 1024L
        advanceTimeBy(10); runCurrent()
        waiting.await()
    }

    @Test
    fun 持续超阈值超过十秒才超时且断开立即失败() = runTest {
        val socket = Socket(bufferedAmount = 600_000)
        val waiting = async { runCatching { RelayBackpressure.waitForCapacity(socket) { testScheduler.currentTime } } }
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertFalse(waiting.isCompleted)
        advanceTimeBy(10); runCurrent()
        assertEquals("RELAY_SLOW_OR_CLOSED", (waiting.await().exceptionOrNull() as RtcTransportException).code)
        socket.isOpen = false
        socket.bufferedAmount = 0
        assertEquals("RELAY_CLOSED", (runCatching { RelayBackpressure.waitForCapacity(socket) }.exceptionOrNull() as RtcTransportException).code)
    }

    private class Socket(override var isOpen: Boolean = true, override var bufferedAmount: Long) : BufferedRelaySocket
}
