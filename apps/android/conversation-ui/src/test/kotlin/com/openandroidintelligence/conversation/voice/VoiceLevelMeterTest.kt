package com.openandroidintelligence.conversation.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLevelMeterTest {
    @Test fun rawAmplitudeIsClampedAndNormalizedToUnitRange() {
        assertEquals(0f, VoiceLevelMeter.normalizeAmplitude(-5), 0f)
        assertEquals(0f, VoiceLevelMeter.normalizeAmplitude(0), 0f)
        assertEquals(1f, VoiceLevelMeter.normalizeAmplitude(32_767), 0f)
        assertEquals(1f, VoiceLevelMeter.normalizeAmplitude(99_999), 0f)
        assertEquals(0.5f, VoiceLevelMeter.normalizeAmplitude(16_383), 0.001f)
    }

    @Test fun pollsTheRecorderAboutEveryFiftyMilliseconds() = runTest {
        var amplitude = 0
        val meter = VoiceLevelMeter({ amplitude }, dispatcher = StandardTestDispatcher(testScheduler))
        meter.start(this)
        runCurrent()
        assertEquals(0f, meter.level.value, 0f)

        amplitude = 16_383
        advanceTimeBy(50)
        runCurrent()
        assertTrue(meter.level.value in 0.49f..0.51f)

        amplitude = 32_767
        advanceTimeBy(50)
        runCurrent()
        assertEquals(1f, meter.level.value, 0f)

        meter.close()
        assertEquals(0f, meter.level.value, 0f)
    }

    @Test fun stoppedMeterIgnoresFurtherSamples() = runTest {
        var amplitude = 32_767
        val meter = VoiceLevelMeter({ amplitude }, dispatcher = StandardTestDispatcher(testScheduler))
        meter.start(this)
        runCurrent()
        assertEquals(1f, meter.level.value, 0f)

        meter.stop()
        amplitude = 0
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0f, meter.level.value, 0f)
    }
}
