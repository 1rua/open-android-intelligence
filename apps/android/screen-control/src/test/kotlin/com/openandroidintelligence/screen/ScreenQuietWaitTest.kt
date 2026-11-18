package com.openandroidintelligence.screen

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScreenQuietWaitTest {
    @Test fun quietRequiresFull400MillisAfterActionCompletion() = runTest {
        val events = MutableStateFlow(0L)
        val result = async { ScreenQuietWait(events) { testScheduler.currentTime }.await() }
        runCurrent(); advanceTimeBy(399); assertFalse(result.isCompleted)
        advanceTimeBy(1); runCurrent(); assertEquals(ScreenSettled(true, "quiet"), result.await())
    }
    @Test fun layoutChangeRestartsQuietWindow() = runTest {
        val events = MutableStateFlow(0L)
        val result = async { ScreenQuietWait(events) { testScheduler.currentTime }.await() }
        runCurrent(); advanceTimeBy(350); events.value++; runCurrent()
        advanceTimeBy(399); assertFalse(result.isCompleted)
        advanceTimeBy(1); runCurrent(); assertEquals(ScreenSettled(true, "quiet"), result.await())
        assertEquals(750, testScheduler.currentTime)
    }
    @Test fun continuousAnimationStopsAtHard3000MillisWithoutRetry() = runTest {
        val events = MutableStateFlow(0L)
        val changes = launch { repeat(40) { delay(100); events.value++ } }
        val result = async { ScreenQuietWait(events) { testScheduler.currentTime }.await() }
        runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(ScreenSettled(false, "timeout"), result.await())
        assertEquals(3000, testScheduler.currentTime); changes.cancel()
    }
}
