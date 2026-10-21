package com.openandroidintelligence.conversation.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 执行耗时记录器：只按假时钟验证「真实观察区间」的语义（规格 §3.2 / §7.1）。
 */
class ExecutionTimingTest {

    private class FakeClock(var now: Long = 0L)

    @Test
    fun recordsObservedRunningInterval() {
        val clock = FakeClock()
        val store = ExecutionTimingStore { clock.now }

        store.observe("entry#0", running = true)
        clock.now = 2_400L
        store.observe("entry#0", running = false)

        assertEquals(2_400L, store.durationOf("entry#0"))
    }

    @Test
    fun repeatedObserveIsIdempotent() {
        val clock = FakeClock()
        val store = ExecutionTimingStore { clock.now }

        store.observe("entry#0", running = true)
        clock.now = 1_000L
        // 重复上报运行中不能重置开始时刻。
        store.observe("entry#0", running = true)
        clock.now = 2_000L
        store.observe("entry#0", running = false)
        // 重复上报结束不能再累加一次。
        store.observe("entry#0", running = false)

        assertEquals(2_000L, store.durationOf("entry#0"))
    }

    @Test
    fun neverObservedStepHasNoFabricatedDuration() {
        val store = ExecutionTimingStore { 0L }
        assertNull(store.durationOf("entry#0"))
    }

    @Test
    fun observingOnlyStopWithoutStartDoesNotFabricateDuration() {
        val clock = FakeClock(now = 5_000L)
        val store = ExecutionTimingStore { clock.now }

        store.observe("entry#0", running = false)

        assertNull(store.durationOf("entry#0"))
    }

    @Test
    fun restartingStepResetsPreviousDuration() {
        val clock = FakeClock()
        val store = ExecutionTimingStore { clock.now }

        store.observe("entry#0", running = true)
        clock.now = 500L
        store.observe("entry#0", running = false)
        assertEquals(500L, store.durationOf("entry#0"))

        // 同一步骤重新开始观察：旧时长清空，重新计时。
        clock.now = 1_000L
        store.observe("entry#0", running = true)
        assertNull(store.durationOf("entry#0"))
        clock.now = 1_600L
        store.observe("entry#0", running = false)
        assertEquals(600L, store.durationOf("entry#0"))
    }

    @Test
    fun separateStepsAreTrackedIndependently() {
        val clock = FakeClock()
        val store = ExecutionTimingStore { clock.now }

        store.observe("entry#0", running = true)
        clock.now = 300L
        store.observe("entry#1", running = true)
        clock.now = 1_000L
        store.observe("entry#0", running = false)
        clock.now = 1_500L
        store.observe("entry#1", running = false)

        assertEquals(1_000L, store.durationOf("entry#0"))
        assertEquals(1_200L, store.durationOf("entry#1"))
    }

    @Test
    fun clearForgetsObservedDurations() {
        val clock = FakeClock()
        val store = ExecutionTimingStore { clock.now }
        store.observe("entry#0", running = true)
        clock.now = 800L
        store.observe("entry#0", running = false)
        assertEquals(800L, store.durationOf("entry#0"))

        store.clear()

        assertNull(store.durationOf("entry#0"))
    }

    @Test
    fun formatsDurationsForTitles() {
        assertEquals("不到 1 秒", formatExecutionDuration(0L))
        assertEquals("不到 1 秒", formatExecutionDuration(999L))
        assertEquals("1 秒", formatExecutionDuration(1_000L))
        assertEquals("2 秒", formatExecutionDuration(2_000L))
        assertEquals("2.4 秒", formatExecutionDuration(2_400L))
        assertEquals("2.5 秒", formatExecutionDuration(2_500L))
        assertEquals("1 分 0 秒", formatExecutionDuration(60_000L))
        assertEquals("1 分 5 秒", formatExecutionDuration(65_000L))
        assertEquals("不到 1 秒", formatExecutionDuration(-500L))
    }
}
