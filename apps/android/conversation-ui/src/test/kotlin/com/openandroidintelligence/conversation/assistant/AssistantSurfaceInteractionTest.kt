package com.openandroidintelligence.conversation.assistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 收起态的命中门控（A1 高危误触回归）。
 *
 * Material 3 的 `clip` 只裁绘制：表面收起成 48dp 球之后，内容层里的「圈选」
 * 与关闭按钮仍留在组合中并参与命中。这三条测试钉住修复后的语义：
 * ① 语义点击球 = 展开，绝不触发圈选；
 * ② 真实触摸点击球 = 展开，绝不触发内容按钮；
 * ③ 展开态内容按钮照常可点（门控不能把正常交互一起锁死）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantSurfaceInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun collapsedSurfaceExpandsFromSemanticClickWithoutStartingSelection() {
        val expansions = mutableListOf<Boolean>()
        var selectionCalls = 0
        compose.setContent {
            MaterialTheme {
                AssistantSurface(
                    expanded = false,
                    onExpandedChange = { expansions += it },
                    onClose = {},
                    onStartSelection = { selectionCalls++ },
                    content = {
                        TextButton(onClick = { selectionCalls++ }) { Text("内容里的圈选") }
                    },
                )
            }
        }

        compose.onNodeWithContentDescription("智能助理，已收起").performClick()
        compose.waitForIdle()

        assertEquals(listOf(true), expansions)
        assertEquals("收起态不能触发圈选回调", 0, selectionCalls)
    }

    @Test
    fun collapsedSurfaceTouchOnBallExpandsInsteadOfHittingContentButtons() {
        val expansions = mutableListOf<Boolean>()
        var selectionCalls = 0
        compose.setContent {
            MaterialTheme {
                AssistantSurface(
                    expanded = false,
                    onExpandedChange = { expansions += it },
                    onClose = {},
                    onStartSelection = { selectionCalls++ },
                    content = {
                        TextButton(onClick = { selectionCalls++ }) { Text("内容里的圈选") }
                    },
                )
            }
        }

        // 真实指针事件：走表面自己的收起态手势，不是语义点击。
        compose.onNodeWithContentDescription("智能助理，已收起").performTouchInput { click() }
        compose.waitForIdle()

        assertEquals(listOf(true), expansions)
        assertEquals("点球不能拉起屏幕采集授权", 0, selectionCalls)
    }

    @Test
    fun expandedSurfaceContentButtonStillHandlesClicks() {
        var selectionCalls = 0
        compose.setContent {
            MaterialTheme {
                AssistantSurface(
                    expanded = true,
                    onExpandedChange = {},
                    onClose = {},
                    onStartSelection = { selectionCalls++ },
                    content = {
                        TextButton(onClick = { selectionCalls++ }) { Text("展开态里的圈选") }
                    },
                )
            }
        }

        compose.onNodeWithText("展开态里的圈选").performClick()
        compose.waitForIdle()

        assertEquals("展开态内容必须照常可交互", 1, selectionCalls)
    }

    @Test
    fun expandedSurfaceIsTallEnoughToShowTheComposer() {
        compose.setContent {
            MaterialTheme {
                AssistantSurface(
                    expanded = true,
                    onExpandedChange = {},
                    onClose = {},
                    onStartSelection = {},
                    footer = { Text("固定输入坞") },
                ) {
                    Text("消息区")
                }
            }
        }

        val bounds = compose.onNodeWithContentDescription("智能助理，已展开").getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue(
            "展开高度必须让输入坞可见（>= 240dp），实际 $height",
            height >= 240.dp,
        )
    }
}
