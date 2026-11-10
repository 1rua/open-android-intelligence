package com.openandroidintelligence.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 顶部横条的行为回归。
 *
 * 锁三件事：
 * 1. 横条始终显示，与焦点、草稿、键盘无关（需求 5.1）；
 * 2. 在横条上向上滑动够远或足够快才触发「进入主 App」，小幅拖动不触发；
 * 3. 触控目标不小于 48dp，并带可朗读的中文说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AssistantDragHandleTest {
    @get:Rule val compose = createComposeRule()

    private fun render(
        enabled: Boolean = true,
        stateDescription: String = "紧凑输入",
        onRequestHandoff: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalMotionPolicy provides MotionPolicy(reduceMotion = false),
            ) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        AssistantDragHandle(
                            enabled = enabled,
                            stateDescription = stateDescription,
                            onRequestHandoff = onRequestHandoff,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun handleIsVisibleWithoutAnyFocusOrDraft() {
        render()

        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("向上滑动在 App 中打开").assertIsDisplayed()
    }

    @Test
    fun handleKeepsTheMinimumTouchTarget() {
        render()

        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE)
            .assertHeightIsAtLeast(Dimensions.MinimumTouchTarget)
    }

    @Test
    fun swipingUpFarEnoughRequestsTheHandoff() {
        var handoffs = 0
        render(onRequestHandoff = { handoffs++ })

        // 屏幕高 891dp、密度 2.625：0.18 阈值约 421px。`swipeUp()` 只在横条自身
        // 48dp（约 126px）内移动，达不到阈值，因此显式上移 600px 再松手。
        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).performTouchInput {
            val start = center
            swipe(start = start, end = Offset(start.x, start.y - 600f), durationMillis = 400)
        }
        compose.waitForIdle()

        assertEquals(1, handoffs)
    }

    @Test
    fun aDisabledHandleNeverRequestsTheHandoff() {
        var handoffs = 0
        render(enabled = false, onRequestHandoff = { handoffs++ })

        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).performTouchInput {
            val start = center
            swipe(start = start, end = Offset(start.x, start.y - 600f), durationMillis = 400)
        }
        compose.waitForIdle()

        assertEquals(0, handoffs)
    }

    // ---- 阈值纯函数 ----

    /**
     * 减少动态：横条不跟手（视觉位移恒为 0），但手势行程仍被累计。
     *
     * 若把「不跟手」实现成「不处理手势」，减少动态用户就再也上滑不进主 App；
     * 若只让进度回调返回 0 而位移照旧，横条又会继续跟手——两者都不对。
     */
    @Test
    fun reduceMotionKeepsTheHandleStillWhileStillTrackingTravel() {
        assertEquals(0f, dragOffsetStep(current = 0f, delta = -300f, reduceMotion = true, screenHeightPx = 2000f))
        val continued = dragOffsetStep(current = -120f, delta = -80f, reduceMotion = true, screenHeightPx = 2000f)
        assertEquals("减少动态下累计拖动也不能产生位移", 0f, continued)
        // 行程照常累计，阈值判定仍可用。
        assertEquals(-300f, dragTravelStep(delta = -300f, screenHeightPx = 2000f))
        assertTrue(shouldHandoffDrag(dragTravelStep(-600f, 2000f), 0f, 2000f, 1200f))
    }

    @Test
    fun aReducedMotionDragStillRequestsTheHandoffWithoutMovingTheHandle() {
        var handoffs = 0
        val progress = mutableListOf<Float>()
        compose.setContent {
            CompositionLocalProvider(
                LocalMotionPolicy provides MotionPolicy(reduceMotion = true),
            ) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        AssistantDragHandle(
                            onRequestHandoff = { handoffs++ },
                            onDragProgress = { progress += it },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).performTouchInput {
            val start = center
            swipe(start = start, end = Offset(start.x, start.y - 600f), durationMillis = 400)
        }
        compose.waitForIdle()

        assertEquals("减少动态不得取消上滑交接", 1, handoffs)
        assertTrue("减少动态下进度回调必须恒为 0：$progress", progress.all { it == 0f })
    }

    @Test
    fun distanceOverEighteenPercentTriggersTheHandoff() {
        // 2000px 屏幕：阈值 360px。
        assertFalse(shouldHandoffDrag(-359f, 0f, 2000f, 1200f))
        assertTrue(shouldHandoffDrag(-361f, 0f, 2000f, 1200f))
    }

    @Test
    fun aFastUpwardFlingTriggersEvenWhenTheDistanceIsShort() {
        assertTrue(shouldHandoffDrag(-40f, -2000f, 2000f, 1200f))
        // 向下甩手不算上滑。
        assertFalse(shouldHandoffDrag(-40f, 2000f, 2000f, 1200f))
    }

    @Test
    fun downwardDragAndUnknownScreenHeightNeverTrigger() {
        assertFalse(shouldHandoffDrag(200f, 0f, 2000f, 1200f))
        assertFalse(shouldHandoffDrag(-1000f, 0f, 0f, 1200f))
    }
}
