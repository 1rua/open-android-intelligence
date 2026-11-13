package com.openandroidintelligence.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.openandroidintelligence.conversation.theme.AppRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 展开会话框的行为回归。
 *
 * 这里断言的是容器契约：展开/收起、高度上限、内部滚动与「回到最新」，
 * 而不是某条消息的排版。容器一旦被内容撑破上限，或者用户翻历史时被新消息
 * 拽回底部，实机上很难复现，必须在这一层锁住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantExpandedPanelTest {
    @get:Rule val compose = createComposeRule()

    private fun messages(count: Int): List<String> =
        (1..count).map { index -> "消息 $index：" + "内容。".repeat(30) }

    private fun setPanel(
        expanded: Boolean = true,
        messages: List<String> = messages(30),
        composerVisible: Boolean = true,
    ) {
        compose.setContent {
            MaterialTheme {
                // 640dp 的宿主高度 → 上限 448dp（0.7），便于断言真实测量值。
                Box(Modifier.size(360.dp, 640.dp)) {
                    AssistantExpandedPanel(
                        expanded = expanded,
                        messages = messages,
                        itemKey = { it },
                        composer = if (composerVisible) {
                            { Text("输入栏占位") }
                        } else {
                            null
                        },
                        itemContent = { message ->
                            Text(
                                text = message,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("message:$message"),
                            )
                        },
                    )
                }
            }
        }
    }

    @Test fun collapsedPanelDoesNotRenderItsContent() {
        setPanel(expanded = false)
        compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(ASSISTANT_SESSION_LIST_TAG).assertDoesNotExist()
    }

    @Test fun expandedPanelIsMeasuredNoTallerThanSeventyPercentOfTheHost() {
        setPanel(expanded = true)
        compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG).assertIsDisplayed()
        val heightDp = compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG)
            .getUnclippedBoundsInRoot().height
        // 消息足以填满：面板应当正好落在 640dp × 0.7 = 448dp 的封顶值上，
        // 既没有超出宿主，也没有缩成一条缝。
        assertTrue("展开面板应封顶在可用高度 × 0.7（448dp），实际 $heightDp", heightDp <= 448.dp + 1.dp)
        assertTrue("内容充足时面板应当用满上限，实际 $heightDp", heightDp >= 440.dp)
    }

    @Test fun overflowingContentScrollsInsideTheListInsteadOfGrowingThePanel() {
        setPanel(expanded = true, messages = messages(40))
        compose.onNodeWithTag(ASSISTANT_SESSION_LIST_TAG).assert(hasScrollToIndexAction())

        val panelHeightDp = compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG)
            .getUnclippedBoundsInRoot().height
        assertTrue("内容再多，面板也只能到上限，实际 $panelHeightDp", panelHeightDp <= 448.dp + 1.dp)
    }

    @Test fun conversationBoxAndInputBarShareOneRoundedCard() {
        assertEquals(CornerSize(AppRadius.ExtraLarge), ConversationBoxShape.topStart)
        assertEquals(CornerSize(AppRadius.ExtraLarge), ConversationBoxShape.topEnd)
        assertEquals(CornerSize(0.dp), ConversationBoxShape.bottomStart)
        assertEquals(CornerSize(0.dp), ConversationBoxShape.bottomEnd)

        assertEquals(CornerSize(0.dp), InputBarShape.topStart)
        assertEquals(CornerSize(0.dp), InputBarShape.topEnd)
        assertEquals(CornerSize(AppRadius.ExtraLarge), InputBarShape.bottomStart)
        assertEquals(CornerSize(AppRadius.ExtraLarge), InputBarShape.bottomEnd)
    }

    /**
     * 宿主显式给出可用高度时（助手根组合的真实路径），0.7 只能乘一次。
     *
     * 回归 issue #110：根层先按屏幕高度算一次上限、面板又把「已扣过 IME 的高度」
     * 当作可用高度再算一次，键盘一弹出面板只剩约 200px。
     */
    @Test fun aHostSuppliedAvailableHeightIsOnlyFilledOnceByThePanel() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    AssistantExpandedPanel(
                        expanded = true,
                        messages = messages(30),
                        itemKey = { it },
                        // 与 2400px 屏 / 1000px 键盘 / 100px 顶部安全区同量级（换算成 dp 后
                        // 只验证「只乘一次 0.7」这一条性质）。
                        availableHeightPx = with(compose.density) { 600.dp.roundToPx() },
                        itemContent = { message ->
                            Text(
                                text = message,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("message:$message"),
                            )
                        },
                    )
                }
            }
        }

        val panelHeight = compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG)
            .getUnclippedBoundsInRoot().height
        // 600dp × 0.7 = 420dp：既明显高于「二次扣减后的一条消息高」，也绝不超出宿主。
        assertTrue("显式可用高度下上限只乘一次 0.7，实际 $panelHeight", panelHeight >= 415.dp)
        assertTrue("不得超过可用高度 × 0.7，实际 $panelHeight", panelHeight <= 425.dp)
    }

    /**
     * 会话框是 28dp 圆角的 `Surface`，会按形状裁剪子内容；消息列表必须有横向内边距，
     * 否则气泡两侧直接顶到卡片边缘被切掉（issue #111）。
     */
    @Test fun theMessageListLeavesHorizontalRoomForTheRoundedCard() {
        val list = messages(3)
        setPanel(expanded = true, messages = list)

        val panel = compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("message:${list.last()}").getUnclippedBoundsInRoot()

        assertTrue(
            "消息左缘必须在面板内并留出圆角余量，实际 left=${row.left} panel=${panel.left}",
            row.left >= panel.left,
        )
        assertTrue(
            "消息右缘必须留出圆角余量，实际 right=${row.right} panel=${panel.right}",
            row.right <= panel.right,
        )
    }

    @Test fun composerRendersAsItsOwnBarUnderTheConversationBox() {        setPanel(expanded = true, messages = messages(3))
        compose.onNodeWithTag(ASSISTANT_INPUT_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithText("输入栏占位").assertIsDisplayed()
    }

    @Test fun browsingHistoryShowsTheButtonAndClickingItReturnsToTheLatestMessage() {
        val list = messages(30)
        setPanel(expanded = true, messages = list)
        // 反向布局里索引 0 是最新一条，索引越大越旧。
        compose.onNodeWithTag(ASSISTANT_SESSION_LIST_TAG).performScrollToIndex(list.lastIndex)

        compose.onNodeWithText("回到最新").assertIsDisplayed()
        compose.onNodeWithText("回到最新").performClick()

        compose.waitForIdle()
        compose.onNodeWithText("回到最新").assertDoesNotExist()
        compose.onNodeWithText(list.last()).assertIsDisplayed()
    }

    @Test fun aNewMessageDoesNotYankTheReaderBackWhileTheyAreBrowsingHistory() {
        var current by mutableStateOf(messages(30))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    AssistantExpandedPanel(
                        expanded = true,
                        messages = current,
                        itemKey = { it },
                        itemContent = { message ->
                            Text(
                                text = message,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("message:$message"),
                            )
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag(ASSISTANT_SESSION_LIST_TAG)
            .performScrollToIndex(current.lastIndex)
        compose.onNodeWithText("回到最新").assertIsDisplayed()

        val appended = "新到的消息：" + "新内容。".repeat(30)
        compose.runOnIdle { current = current + appended }
        compose.waitForIdle()

        // 阅读位置不动：最新一条仍不在视口里，按钮还在。
        compose.onNodeWithTag("message:$appended").assertDoesNotExist()
        compose.onNodeWithText("回到最新").assertIsDisplayed()
    }

    @Test fun newMessagesWhileAnchoredAtTheLatestAppendIntoView() {
        var current by mutableStateOf(messages(5))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    AssistantExpandedPanel(
                        expanded = true,
                        messages = current,
                        itemKey = { it },
                        itemContent = { message ->
                            Text(
                                text = message,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("message:$message"),
                            )
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("回到最新").assertDoesNotExist()
        val appended = "追加消息"
        compose.runOnIdle { current = current + appended }
        compose.waitForIdle()

        compose.onNodeWithTag("message:$appended").assertIsDisplayed()
        compose.onNodeWithText("回到最新").assertDoesNotExist()
    }
}
