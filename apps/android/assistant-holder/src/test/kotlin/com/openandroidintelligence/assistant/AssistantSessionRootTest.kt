package com.openandroidintelligence.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.workbench.ApprovalCardTags
import com.openandroidintelligence.conversation.workbench.ComposerAttachmentSheetTags
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 助理根组合的行为回归。
 *
 * 这里锁两件在真机上很难复现的事：
 * 1. **状态机集成**：根组合发出的每个用户动作都走同一条 reducer，只有主进程真正
 *    接收的发送才展开会话；失败保持紧凑输入，草稿不受影响；
 * 2. **窗口高度回传**：根节点测量到的像素高度必须真的回传给宿主（窗口高度由它决定），
 *    而且内容变化（展开会话）后要上报新的高度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantSessionRootTest {
    @get:Rule val compose = createComposeRule()

    private fun userEntry(id: String, text: String) = TimelineEntry(
        key = id,
        sender = "user",
        text = text,
        isUser = true,
        timestamp = 1L,
        pendingAcceptance = false,
        batchGroupId = null,
    )

    private fun approvalEntry(): TimelineEntry = TimelineEntry(
        key = "approval_row",
        sender = "assistant",
        text = "",
        isUser = false,
        timestamp = 3L,
        pendingAcceptance = false,
        batchGroupId = null,
        approval = ApprovalCardState.Waiting(
            ApprovalRequest(
                approvalId = ApprovalId("apr_9"),
                conversationId = null,
                command = "reboot",
                reason = "需要重启设备",
                severity = null,
                options = listOf(
                    ApprovalOption(ApprovalChoice.ONCE, "允许一次", ApprovalOptionStyle.PRIMARY),
                    ApprovalOption(ApprovalChoice.DENY, "拒绝", ApprovalOptionStyle.DANGER),
                ),
                timeoutSeconds = 60L,
                requestedAt = System.currentTimeMillis(),
            ),
        ),
    )

    private fun render(
        draft: String = "",
        onSend: () -> Boolean = { true },
        controller: AssistantSessionUiController = AssistantSessionUiController(),
        timeline: List<TimelineEntry> = emptyList(),
        heights: MutableList<Int> = mutableListOf(),
        attachments: List<AssistantCompactAttachment> = emptyList(),
        onRemoveAttachment: (String) -> Unit = {},
        onDecide: (String, ApprovalChoice) -> Unit = { _, _ -> },
    ): AssistantSessionUiController {
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        AssistantSessionRoot(
                            draft = draft,
                            onDraftChange = {},
                            enabled = true,
                            isGenerating = false,
                            isStopping = false,
                            hasUnknownOutcome = false,
                            onSend = onSend,
                            onStop = {},
                            onExit = {},
                            controller = controller,
                            timeline = timeline,
                            threadId = "thread_1",
                            onContentHeightChanged = { heights += it },
                            attachments = attachments,
                            onRemoveAttachment = onRemoveAttachment,
                            onDecide = onDecide,
                        )
                    }
                }
            }
        }
        return controller
    }

    // ---- 状态机集成 ----

    @Test
    fun compactInputIsTheDefaultSurface() {
        val controller = render()

        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).assertIsDisplayed()
        // 会话框容器在紧凑态不组合：没有消息区，也没有输入栏槽位。
        compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG).assertDoesNotExist()
        assertEquals(AssistantSurfaceState.COMPACT_INPUT, (controller.state as AssistantUiState.Visible).surface)
    }

    @Test
    fun acceptedSendExpandsTheConversationThroughTheSharedReducer() {
        val controller = render(onSend = { true })

        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).assertIsDisplayed()
        compose.runOnIdle {
            controller.dispatch(AssistantUiEvent.SendRequested(accepted = true))
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG).assertIsDisplayed()
        assertEquals(
            AssistantSurfaceState.EXPANDED_CONVERSATION,
            (controller.state as AssistantUiState.Visible).surface,
        )
    }

    @Test
    fun rejectedSendKeepsTheCompactInput() {
        val controller = render(onSend = { false })

        compose.runOnIdle {
            controller.dispatch(AssistantUiEvent.SendRequested(accepted = false))
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).assertIsDisplayed()
    }

    @Test
    fun expandedConversationRendersTimelineRowsAndKeepsTheInputBar() {
        val controller = render(
            timeline = listOf(userEntry("u1", "帮我看看"), userEntry("u2", "再看一张")),
            onSend = { true },
        )
        // 唤起会重置成紧凑输入：展开必须由一次被接收的发送触发，而不是直接塞入旧状态。
        compose.runOnIdle {
            controller.dispatch(AssistantUiEvent.SendRequested(accepted = true))
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG).assertIsDisplayed()
        compose.onNodeWithText("帮我看看").assertIsDisplayed()
        compose.onNodeWithText("再看一张").assertIsDisplayed()
        // 输入栏固定在会话框底部，永不被滚动带走。
        compose.onNodeWithTag(ASSISTANT_INPUT_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).assertIsDisplayed()
        assertEquals(
            AssistantSurfaceState.EXPANDED_CONVERSATION,
            (controller.state as AssistantUiState.Visible).surface,
        )
    }

    @Test
    fun sessionShownDispatchedOnEntryResetsToCompactInput() {
        val stale = AssistantSessionUiController(
            AssistantUiState.Visible(
                surface = AssistantSurfaceState.EXPANDED_CONVERSATION,
                overlay = AssistantOverlay.SELECTING_SCREEN,
                expandedSince = 42L,
            ),
        )
        render(controller = stale)
        compose.waitForIdle()

        // 每次唤起都是新会话：根组合进入组合时发一次 SessionShown，旧形态被重置。
        val resolved = stale.state as AssistantUiState.Visible
        assertEquals(AssistantSurfaceState.COMPACT_INPUT, resolved.surface)
        assertEquals(AssistantOverlay.NONE, resolved.overlay)
    }

    @Test
    fun backPressedDispatchEndsTheSessionAndNotifiesTheHost() {
        var exits = 0
        val controller = AssistantSessionUiController()
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        AssistantSessionRoot(
                            draft = "",
                            onDraftChange = {},
                            enabled = true,
                            isGenerating = false,
                            isStopping = false,
                            hasUnknownOutcome = false,
                            onSend = { true },
                            onStop = {},
                            onExit = { exits++ },
                            controller = controller,
                        )
                    }
                }
            }
        }

        compose.runOnIdle { controller.dispatch(AssistantUiEvent.BackPressed) }
        compose.waitUntil(5_000) { exits == 1 }

        assertTrue(controller.state is AssistantUiState.Terminated)
        // 结束态不再渲染旧界面，宿主收到一次退出通知。
        compose.onNodeWithTag(AssistantSessionRootTags.ROOT).assertDoesNotExist()
        assertEquals(1, exits)
    }

    @Test
    fun bridgeNoticeIsRenderedAndDismissable() {
        var dismissed = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        AssistantSessionRoot(
                            draft = "",
                            onDraftChange = {},
                            enabled = true,
                            isGenerating = false,
                            isStopping = false,
                            hasUnknownOutcome = false,
                            onSend = { true },
                            onStop = {},
                            onExit = {},
                            notice = "未连接，草稿已保留",
                            onDismissNotice = { dismissed++ },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag(AssistantSessionRootTags.NOTICE).assertIsDisplayed()
        compose.onNodeWithText("未连接，草稿已保留").assertIsDisplayed()
        // 本进程提示可关闭，且关闭动作交回宿主（主进程的 notice 不会在本地被抹掉）。
        compose.onNodeWithContentDescription("关闭提示").performClick()
        assertEquals(1, dismissed)
    }

    // ---- 接线：审批 / 附件 / 录音 ----

    @Test
    fun approvalButtonsForwardTheCardIdAndThePressedChoiceToTheHost() {
        val decisions = mutableListOf<Pair<String, ApprovalChoice>>()
        val controller = render(timeline = listOf(approvalEntry()), onDecide = { id, choice -> decisions += id to choice })
        compose.runOnIdle {
            controller.dispatch(AssistantUiEvent.SendRequested(accepted = true))
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.DENY)).performClick()
        compose.waitForIdle()

        // 按键必须真的交回宿主，而且带着被按下那张卡的 approvalId。
        assertEquals(listOf("apr_9" to ApprovalChoice.DENY), decisions)
    }

    @Test
    fun draftAttachmentsAreShownInTheInputBarAndRemovalTargetsTheirDraftId() {
        val removed = mutableListOf<String>()
        render(
            attachments = listOf(
                AssistantCompactAttachment(id = "draft-1", filename = "截图.png", mediaType = "image/png"),
                AssistantCompactAttachment(id = "draft-2", filename = "voice_1.m4a", mediaType = "audio/mp4"),
            ),
            onRemoveAttachment = { removed += it },
        )

        compose.onNodeWithTag(AssistantCompactBarTags.ATTACHMENTS).assertIsDisplayed()
        compose.onNodeWithTag(AssistantCompactBarTags.attachment("draft-1")).assertIsDisplayed()
        compose.onNodeWithTag(AssistantCompactBarTags.attachment("draft-2")).assertIsDisplayed()

        compose.onNodeWithContentDescription("移除附件：voice_1.m4a").performClick()
        assertEquals(listOf("draft-2"), removed)
    }

    @Test
    fun noAttachmentsMeansNoStrip() {
        render()

        compose.onNodeWithTag(AssistantCompactBarTags.ATTACHMENTS).assertDoesNotExist()
    }

    // ---- 顶部横条（需求 5.1） ----

    @Test
    fun theHandleIsShownFromTheFirstMomentInTheCompactForm() {
        render()

        // 初次唤起即显示：不需要点输入框、不需要有草稿。
        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("向上滑动在 App 中打开").assertIsDisplayed()
    }

    @Test
    fun theSameHandleStaysAtTheTopWhenTheConversationExpands() {
        val controller = render()

        compose.runOnIdle { controller.dispatch(AssistantUiEvent.SendRequested(accepted = true)) }
        compose.waitForIdle()

        // 展开后仍然只有一个横条，且位于整个会话面板之上。
        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("向上滑动在 App 中打开").assertExists()
        val handle = compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag(ASSISTANT_EXPANDED_PANEL_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "横条必须在会话面板之上，handle.bottom=${handle.bottom} panel.top=${panel.top}",
            handle.bottom <= panel.top + 1.dp,
        )
    }

    @Test
    fun swipingUpTheHandleRequestsTheHandoffFromTheHost() {
        var handoffs = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    AssistantSessionRoot(
                        draft = "",
                        onDraftChange = {},
                        enabled = true,
                        isGenerating = false,
                        isStopping = false,
                        hasUnknownOutcome = false,
                        onSend = { true },
                        onStop = {},
                        onExit = {},
                        onRequestHandoff = { handoffs++ },
                    )
                }
            }
        }

        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).performTouchInput {
            val start = center
            swipe(start = start, end = Offset(start.x, start.y - 600f), durationMillis = 400)
        }
        compose.waitForIdle()

        assertEquals(1, handoffs)
    }

    @Test
    fun tappingAddAttachmentReachesTheHost() {
        val adds = mutableListOf<AssistantAttachmentKind>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    AssistantSessionRoot(
                        draft = "",
                        onDraftChange = {},
                        enabled = true,
                        isGenerating = false,
                        isStopping = false,
                        hasUnknownOutcome = false,
                        onSend = { true },
                        onStop = {},
                        onExit = {},
                        onAddAttachment = { adds += it },
                    )
                }
            }
        }

        // 「＋」先展开与主 App 共用的底部卡片，再选来源：三条选择器路径都要能到。
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithTag(ComposerAttachmentSheetTags.GALLERY).performClick()
        assertEquals(listOf(AssistantAttachmentKind.MEDIA), adds)
    }

    // ---- 窗口高度回传 ----

    @Test
    fun measuredHeightIsReportedToTheHost() {
        val heights = mutableListOf<Int>()
        render(heights = heights)

        compose.waitForIdle()

        assertTrue("根节点应当回传一次测量高度", heights.isNotEmpty())
        assertTrue("窗口高度必须是正数，实际 $heights", heights.last() > 0)
    }

    @Test
    fun expandingTheConversationReportsANewTallerHeight() {
        val heights = mutableListOf<Int>()
        val controller = render(heights = heights)
        compose.waitForIdle()
        val compactHeight = heights.last()

        compose.runOnIdle {
            controller.dispatch(AssistantUiEvent.SendRequested(accepted = true))
        }
        compose.waitForIdle()
        val expandedHeight = heights.last()

        // 展开后窗口必须变高：会话框出现，窗口高度跟随测量而不是停在输入栏高度。
        assertTrue(
            "展开后高度应大于紧凑态，compact=$compactHeight expanded=$expandedHeight",
            expandedHeight > compactHeight,
        )
    }

    @Test
    fun heightIsReportedForEveryMeasurementNotOnlyTheFirst() {
        val heights = mutableListOf<Int>()
        var draft by mutableStateOf("")
        val controller = AssistantSessionUiController()
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        AssistantSessionRoot(
                            draft = draft,
                            onDraftChange = {},
                            enabled = true,
                            isGenerating = false,
                            isStopping = false,
                            hasUnknownOutcome = false,
                            onSend = { true },
                            onStop = {},
                            onExit = {},
                            controller = controller,
                            onContentHeightChanged = { heights += it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val first = heights.size

        // 输入栏随草稿增高：这一步会重新测量，高度必须再次回传，而不是只在首帧上报。
        compose.runOnIdle { draft = List(4) { "第${it + 1}行" }.joinToString("\n") }
        compose.waitForIdle()

        assertTrue("首次测量应当上报", first > 0)
        assertTrue("后续测量也应当上报，实际次数 ${heights.size}（首次 $first）", heights.size > first)
    }
}
