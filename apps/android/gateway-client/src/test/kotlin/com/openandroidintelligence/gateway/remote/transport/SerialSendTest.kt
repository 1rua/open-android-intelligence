package com.openandroidintelligence.gateway.remote.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerialSendTest {
    @Test
    fun 并发受理严格按顺序执行整个加密发送块() = runTest {
        val queue = SerialSend()
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<Int>()
        val first = launch { queue.run(1) { seen += 1; gate.await(); seen += 2 } }
        runCurrent()
        val second = launch { queue.run(1) { seen += 3 } }
        runCurrent()
        assertEquals(listOf(1), seen)
        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf(1, 2, 3), seen)
        queue.close()
    }

    @Test
    fun 待发字节与条数遵守固定上游上限且完成后释放() = runTest {
        val queue = SerialSend()
        val gate = CompletableDeferred<Unit>()
        val first = launch { queue.run(5 * 1024 * 1024) { gate.await() } }
        runCurrent()
        val limit = runCatching { queue.run(4 * 1024 * 1024) { error("不应执行") } }.exceptionOrNull()
        assertEquals("SEND_QUEUE_LIMIT", (limit as RtcTransportException).code)
        val pending = List(255) { launch { queue.run(0) { } } }
        runCurrent()
        assertTrue(runCatching { queue.run(0) { } }.exceptionOrNull() is RtcTransportException)
        gate.complete(Unit)
        first.join(); pending.forEach { it.join() }
        assertEquals(7, queue.run(8 * 1024 * 1024) { 7 })
        queue.close()
    }

    @Test
    fun 取消排队项不会让后继越过仍在发送的前驱() = runTest {
        val queue = SerialSend()
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<Int>()
        val first = launch { queue.run(1) { seen += 1; gate.await(); seen += 2 } }
        runCurrent()
        val cancelled = launch { queue.run(1) { seen += 99 } }
        runCurrent()
        val third = launch { queue.run(1) { seen += 3 } }
        runCurrent()
        cancelled.cancelAndJoin()
        runCurrent()
        assertEquals(listOf(1), seen)
        gate.complete(Unit)
        first.join(); third.join()
        assertEquals(listOf(1, 2, 3), seen)
        queue.close()
    }

    @Test
    fun 关闭立即拒绝排队项且迟到完成不能复活队列() = runTest {
        val queue = SerialSend()
        val gate = CompletableDeferred<Unit>()
        val first = launch { queue.run(1) { gate.await() } }
        runCurrent()
        val pending = async { runCatching { queue.run(1) { error("不应执行") } } }
        runCurrent()
        queue.close()
        runCurrent()
        assertEquals("RTC_CLOSED", (pending.await().exceptionOrNull() as RtcTransportException).code)
        gate.complete(Unit); first.join()
        assertTrue(runCatching { queue.run(1) { } }.exceptionOrNull() is RtcTransportException)
        queue.close()
    }

    @Test
    fun 取消的排队项在前驱完成前保留额度以防无界依赖链() = runTest {
        val queue = SerialSend()
        val gate = CompletableDeferred<Unit>()
        val first = launch { queue.run(1) { gate.await() } }
        runCurrent()
        val waiting = List(255) { launch { queue.run(0) { } } }
        runCurrent()
        waiting.forEach { it.cancelAndJoin() }
        assertTrue(runCatching { queue.run(0) { } }.exceptionOrNull() is RtcTransportException)
        gate.complete(Unit); first.join()
        assertEquals(1, queue.run(0) { 1 })
        queue.close()
    }
}
