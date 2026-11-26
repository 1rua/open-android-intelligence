package com.openandroidintelligence.gateway.remote.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteReconnectPolicyTest {
    @Test
    fun `默认策略产生一秒到三十秒的指数退避序列`() {
        val policy = RemoteReconnectPolicy()
        val expectedDelays = listOf(
            0 to 1_000L,
            1 to 2_000L,
            2 to 4_000L,
            3 to 8_000L,
            4 to 16_000L,
            5 to 30_000L,
            20 to 30_000L,
        )

        expectedDelays.forEach { (attempt, expectedDelay) ->
            assertEquals(expectedDelay, policy.delayMs(attempt) { 0.5 })
        }
    }

    @Test
    fun `正负抖动对称并在初始值与上限处钳制`() {
        val policy = RemoteReconnectPolicy()

        assertEquals(8_000L, policy.delayMs(3) { 0.5 })
        assertEquals(6_400L, policy.delayMs(3) { 0.0 })
        assertEquals(9_600L, policy.delayMs(3) { 1.0 })

        assertEquals(1_000L, policy.delayMs(0) { 0.0 })
        assertEquals(24_000L, policy.delayMs(5) { 0.0 })
        assertEquals(30_000L, policy.delayMs(5) { 1.0 })
    }

    @Test
    fun `连接成功后由调用方从零开始重置退避序列`() {
        val policy = RemoteReconnectPolicy()
        val attempts = listOf(0, 1, 2, 3, 0)

        val delays = attempts.map { attempt -> policy.delayMs(attempt) { 0.5 } }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 1_000L), delays)
    }

    @Test
    fun `重复唤醒在等待期间只合并为一次`() = runTest {
        val signal = RemoteReconnectSignal()
        val firstWait = async {
            signal.await(30_000L)
            true
        }
        runCurrent()

        repeat(5) { signal.wake() }
        runCurrent()

        assertTrue(firstWait.await())

        val secondWait = async {
            signal.await(1_000L)
            true
        }
        runCurrent()
        assertFalse(secondWait.isCompleted)

        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(secondWait.await())
    }

    @Test
    fun `唤醒可以立即打断长延迟等待`() = runTest {
        val signal = RemoteReconnectSignal()
        var completed = false
        val wait = launch {
            signal.await(30_000L)
            completed = true
        }
        runCurrent()

        signal.wake()
        runCurrent()

        assertTrue(completed)
        assertTrue(wait.isCompleted)
    }

    @Test
    fun `clear 丢弃旧唤醒且不关闭原语`() = runTest {
        val signal = RemoteReconnectSignal()
        signal.wake()
        signal.clear()

        val wait = async {
            signal.await(1_000L)
            true
        }
        runCurrent()
        assertFalse(wait.isCompleted)

        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(wait.await())

        signal.wake()
        val woken = async {
            signal.await(30_000L)
            true
        }
        runCurrent()
        assertTrue(woken.await())
    }

    @Test
    fun `close 立即结束等待并保持幂等关闭语义`() = runTest {
        val signal = RemoteReconnectSignal()
        val wait = async {
            signal.await(30_000L)
            true
        }
        runCurrent()

        signal.close()
        runCurrent()
        assertTrue(wait.await())

        signal.close()
        signal.wake()
        signal.await(30_000L)
    }
}
