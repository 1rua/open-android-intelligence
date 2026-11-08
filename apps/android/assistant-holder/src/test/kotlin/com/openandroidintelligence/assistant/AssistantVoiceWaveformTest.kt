package com.openandroidintelligence.assistant

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 录音波形滚动窗口的回归。
 *
 * 波形是「真实音量的历史」，不是装饰：柱数必须恒定（宽度不跳）、右侧必须是最新样本、
 * 超出窗口的旧样本必须被丢弃，未填满的位置必须是空白基线而不是伪造的随机高度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AssistantVoiceWaveformTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun anEmptyWindowIsAFixedRowOfIdleBars() {
        val window = AssistantVoiceWaveformWindow(barCount = 24)

        val bars = window.bars()

        assertEquals(24, bars.size)
        assertTrue("没有样本时只能是空白基线，不能伪造高度", bars.all { it == 0f })
        assertEquals(0, window.size)
    }

    @Test
    fun samplesEnterFromTheRightAndTheWindowWidthNeverChanges() {
        val window = AssistantVoiceWaveformWindow(barCount = 4)

        window.push(0.25f)
        assertEquals(listOf(0f, 0f, 0f, 0.25f), window.bars())

        window.push(0.5f)
        assertEquals(listOf(0f, 0f, 0.25f, 0.5f), window.bars())
        assertEquals(2, window.size)
    }

    @Test
    fun theWindowKeepsOnlyTheMostRecentTwentyFourFrames() {
        val window = AssistantVoiceWaveformWindow()
        (1..30).forEach { window.push(it / 100f) }

        assertEquals(AssistantVoiceWaveformDefaults.BarCount, window.size)
        // 最近 24 帧是 0.07..0.30，右侧为最新。
        assertEquals(0.07f, window.bars().first(), 0.0001f)
        assertEquals(0.30f, window.bars().last(), 0.0001f)
    }

    @Test
    fun aSmallerWindowKeepsOnlyItsOwnMostRecentFrames() {
        val window = AssistantVoiceWaveformWindow(barCount = 3)
        (1..5).forEach { window.push(it / 10f) }

        assertEquals(listOf(0.3f, 0.4f, 0.5f), window.bars())
        assertEquals(3, window.size)
    }

    @Test
    fun levelsOutsideZeroToOneAreClampedInsteadOfDrawingPastTheStrip() {
        val window = AssistantVoiceWaveformWindow(barCount = 2)

        window.push(9f)
        window.push(-4f)

        assertEquals(listOf(1f, 0f), window.bars())
    }

    @Test
    fun fallingLevelsAreRecordedAsTheyAreNotSmoothedAway() {
        val window = AssistantVoiceWaveformWindow(barCount = 3)

        window.push(0.8f)
        window.push(0.2f)
        window.push(0.05f)

        assertEquals(listOf(0.8f, 0.2f, 0.05f), window.bars())
    }

    @Test
    fun clearingResetsTheWindowToTheIdleBaseline() {
        val window = AssistantVoiceWaveformWindow(barCount = 3)
        window.push(0.9f)

        window.clear()

        assertEquals(0, window.size)
        assertEquals(listOf(0f, 0f, 0f), window.bars())
    }

    @Test
    fun theWaveformRendersUnderReducedMotionWithoutAnyAnimation() {
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    AssistantVoiceWaveform(
                        level = 0.6f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // 减少动态时柱子直接落在真实值上；组件仍要在位并给读屏一条描述。
        compose.onNodeWithContentDescription("录音波形").assertIsDisplayed()
    }

    @Test
    fun theWaveformKeepsRenderingAtTheExtremeLevels() {
        val level = mutableStateOf(0f)
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    AssistantVoiceWaveform(
                        level = level.value,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        listOf(0f, 0.5f, 1f, 4f, -1f).forEach { next ->
            compose.runOnIdle { level.value = next }
            compose.onNodeWithContentDescription("录音波形").assertIsDisplayed()
        }
    }
}
