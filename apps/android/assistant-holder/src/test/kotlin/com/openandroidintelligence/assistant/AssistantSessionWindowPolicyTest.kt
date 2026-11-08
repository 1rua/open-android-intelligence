package com.openandroidintelligence.assistant

import androidx.compose.foundation.lazy.LazyListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 展开会话框的窗口几何与跟随策略回归。
 *
 * 这些是「多高算高」「什么时候算在最新处」的纯逻辑：算错不会崩，
 * 但会让会话框盖住状态栏、被键盘挤爆，或在用户翻历史时把内容拽走。
 * 界面测试只能观察到「按钮出现/消失」，边界值必须在这一层钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantSessionWindowPolicyTest {

    @Test fun maxHeightTakesSeventyPercentWhenKeyboardDoesNotConstrain() {
        assertEquals(700, AssistantSessionWindowPolicy.calculateMaxHeight(
            availableHeight = 1000, imeHeight = 0, topInset = 0,
        ))
    }

    @Test fun keyboardAndTopInsetWinWhenTheyLeaveLessRoomThanTheFraction() {
        // min(700, 1000 - 400 - 24) = 576
        assertEquals(576, AssistantSessionWindowPolicy.calculateMaxHeight(
            availableHeight = 1000, imeHeight = 400, topInset = 24,
        ))
    }

    @Test fun aTallKeyboardKeepsOnlyTheActuallyUsableStrip() {
        // min(700, 1000 - 700 - 50) = 250
        assertEquals(250, AssistantSessionWindowPolicy.calculateMaxHeight(
            availableHeight = 1000, imeHeight = 700, topInset = 50,
        ))
    }

    @Test fun fractionUsesIntegerFloorSoThePanelNeverOverflows() {
        // 999 * 7 / 10 = 699.3 → 699（向下取整，绝不向上凑满）
        assertEquals(699, AssistantSessionWindowPolicy.calculateMaxHeight(
            availableHeight = 999, imeHeight = 0, topInset = 0,
        ))
    }

    @Test fun degenerateGeometryCollapsesToZeroInsteadOfNegativeOrHugeValues() {
        assertEquals(0, AssistantSessionWindowPolicy.calculateMaxHeight(
            availableHeight = 0, imeHeight = 0, topInset = 0,
        ))
        assertEquals(0, AssistantSessionWindowPolicy.calculateMaxHeight(
            availableHeight = -100, imeHeight = 0, topInset = 0,
        ))
        // 键盘与安全区已经吃满全部高度：压到 0，而不是画出负高度。
        assertEquals(0, AssistantSessionWindowPolicy.calculateMaxHeight(
            availableHeight = 600, imeHeight = 500, topInset = 200,
        ))
    }

    @Test fun negativeInsetsAreTreatedAsAbsentRatherThanAddingRoom() {
        assertEquals(700, AssistantSessionWindowPolicy.calculateMaxHeight(
            availableHeight = 1000, imeHeight = -300, topInset = -50,
        ))
        assertEquals(1000, AssistantSessionWindowPolicy.keyboardAvailableHeight(
            availableHeight = 1000, imeHeight = -300, topInset = -50,
        ))
    }

    @Test fun keyboardAvailableHeightNeverGoesBelowZero() {
        assertEquals(576, AssistantSessionWindowPolicy.keyboardAvailableHeight(
            availableHeight = 1000, imeHeight = 400, topInset = 24,
        ))
        assertEquals(0, AssistantSessionWindowPolicy.keyboardAvailableHeight(
            availableHeight = 300, imeHeight = 200, topInset = 300,
        ))
        assertEquals(0, AssistantSessionWindowPolicy.keyboardAvailableHeight(
            availableHeight = 0, imeHeight = 0, topInset = 0,
        ))
    }

    @Test fun followLatestHoldsOnlyWhileTheListIsAnchoredAtTheNewestItem() {
        assertTrue(AssistantSessionWindowPolicy.shouldFollowLatest(0, 0))
        // 布局取整造成的 1~2 像素抖动仍视为贴底。
        assertTrue(AssistantSessionWindowPolicy.shouldFollowLatest(0, AssistantSessionWindowPolicy.LATEST_EDGE_TOLERANCE_PX))
        // 一旦上滑到历史（索引变大或偏移超出容差）就停止跟随。
        assertFalse(AssistantSessionWindowPolicy.shouldFollowLatest(0, AssistantSessionWindowPolicy.LATEST_EDGE_TOLERANCE_PX + 1))
        assertFalse(AssistantSessionWindowPolicy.shouldFollowLatest(1, 0))
        assertFalse(AssistantSessionWindowPolicy.shouldFollowLatest(12, 240))
    }

    @Test fun followLatestReadsTheRealLazyListState() {
        assertTrue(AssistantSessionWindowPolicy.shouldFollowLatest(LazyListState(0, 0)))
        assertFalse(AssistantSessionWindowPolicy.shouldFollowLatest(LazyListState(0, 48)))
        assertFalse(AssistantSessionWindowPolicy.shouldFollowLatest(LazyListState(3, 0)))
    }
}
