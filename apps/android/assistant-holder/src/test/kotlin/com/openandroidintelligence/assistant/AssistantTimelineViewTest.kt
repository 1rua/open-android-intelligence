package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.workbench.ApprovalCardTags
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 助理时间线的行为回归。
 *
 * 断言的是用户在系统助理浮层里真正能看到、能按到的四类事实：
 * 1. 复用 App 的渲染层：Markdown 正文、思考/工具块、审批卡按钮；
 * 2. 附件：本进程缩略图能显示，语音显示真实时长芯片，没有时长就不编造；
 * 3. 审批决策带着被按下那张卡的 approvalId 交回宿主；
 * 4. 智能跟随：用户翻历史时新消息不拽走阅读位置，「回到最新」能恢复跟随。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantTimelineViewTest {
    @get:Rule val compose = createComposeRule()

    private fun setTimeline(
        entries: List<TimelineEntry>,
        previews: AssistantLocalPreviews = AssistantLocalPreviews(),
        generation: GenerationState? = null,
        onDecide: (String, ApprovalChoice) -> Unit = { _, _ -> },
        emptyContent: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    AssistantTimelineView(
                        entries = entries,
                        previews = previews,
                        threadId = "thread_1",
                        generation = generation,
                        onDecide = onDecide,
                        emptyContent = emptyContent,
                    )
                }
            }
        }
    }

    private fun userEntry(id: String, text: String) = TimelineEntry(
        key = id,
        sender = "user",
        text = text,
        isUser = true,
        timestamp = 1L,
        pendingAcceptance = false,
        batchGroupId = null,
    )

    private fun assistantEntry(
        id: String,
        text: String,
        attachments: List<TimelineAttachment> = emptyList(),
        streaming: Boolean = false,
    ) = TimelineEntry(
        key = id,
        sender = "assistant",
        text = text,
        isUser = false,
        timestamp = 2L,
        pendingAcceptance = false,
        batchGroupId = null,
        attachments = attachments,
        isStreaming = streaming,
    )

    private fun png(width: Int = 40, height: Int = 40): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }

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

    // ---- 1. 复用渲染层 ----

    @Test
    fun markdownAndThinkingBlocksRenderThroughTheSharedTimelineLayer() {
        setTimeline(
            listOf(
                userEntry("u1", "帮我看看"),
                assistantEntry("a1", "<think>先检查磁盘</think>\n\n**结论**：一切正常"),
            ),
        )

        compose.onNodeWithText("帮我看看").assertIsDisplayed()
        // Markdown 行内加粗被渲染成一段正文（语义树里合并为一个文本节点）。
        compose.onNodeWithText("结论", substring = true).assertIsDisplayed()
        // 思考块由共享渲染层折叠成执行段；折叠态标题是"已执行 1 步 · 点击展开"，
        // 辅助技术读到的是同一条可点语义（contentDescription 明确写了"执行过程"）。
        compose.onNodeWithText("已执行 1 步", substring = true).assertIsDisplayed()
    }

    @Test
    fun streamingTailRendersWithoutWaitingForAFinalFrame() {
        setTimeline(
            listOf(userEntry("u1", "说点什么"), assistantEntry("a1", "正在输出", streaming = true)),
            generation = GenerationState.RUNNING,
        )
        // 流式正文带光标，且下方还有"正在输出…"状态行：两处都算真实内容。
        compose.onAllNodesWithText("正在输出", substring = true)
            .fetchSemanticsNodes()
            .also { nodes -> assertTrue("流式正文应当被渲染", nodes.isNotEmpty()) }
        // 已有流式正文时不再叠加"正在思考"占位。
        compose.onNodeWithTag(AssistantTimelineTags.THINKING).assertDoesNotExist()
    }

    @Test
    fun thinkingIndicatorOnlyAppearsWhileGeneratingWithoutStreamedText() {
        setTimeline(listOf(userEntry("u1", "说点什么")), generation = GenerationState.RUNNING)
        compose.onNodeWithTag(AssistantTimelineTags.THINKING).assertIsDisplayed()
    }

    @Test
    fun emptyTimelineShowsTheHostProvidedEmptyStateAndNothingElse() {
        setTimeline(entries = emptyList(), emptyContent = { androidx.compose.material3.Text("还没有消息") })

        compose.onNodeWithText("还没有消息").assertIsDisplayed()
        compose.onNodeWithTag(AssistantTimelineTags.LIST).assertDoesNotExist()
    }

    // ---- 2. 附件 ----

    @Test
    fun aThumbnailHeldByThisProcessIsDecodedAndShown() {
        val previews = AssistantLocalPreviews()
        previews.put("att_image", png())

        setTimeline(
            listOf(
                userEntry("u1", "看这张图").copy(
                    attachments = listOf(
                        TimelineAttachment("att_image", "crop.png", "image/png"),
                    ),
                ),
            ),
            previews = previews,
        )

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("attachment-image:att_image", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("attachment-image:att_image", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun withoutLocalBytesTheImageShowsAnHonestPlaceholderInsteadOfAFakePicture() {
        setTimeline(
            listOf(
                userEntry("u1", "").copy(
                    attachments = listOf(TimelineAttachment("att_missing", "crop.png", "image/png")),
                ),
            ),
        )

        // 没有字节的图片由渲染层给出明确占位（contentDescription 是它对外的一句话）。
        compose.onNodeWithContentDescription("图片预览不可用", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("attachment-image:att_missing", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun voiceAttachmentShowsItsFilenameAndTheRealRecordedDuration() {
        val previews = AssistantLocalPreviews()
        previews.putVoiceDuration("att_voice", 65_000L)

        setTimeline(
            listOf(
                userEntry("u1", "").copy(
                    attachments = listOf(
                        TimelineAttachment("att_voice", "voice_1.m4a", "audio/mp4", localState = AttachmentLocalState.SAVED),
                    ),
                ),
            ),
            previews = previews,
        )

        compose.onNodeWithText("voice_1.m4a").assertIsDisplayed()
        compose.onNodeWithText("1:05").assertIsDisplayed()
        // 语音不是图片，不应出现大图预览入口。
        compose.onNodeWithTag("attachment-image:att_voice", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun voiceAttachmentWithoutAKnownDurationShowsNoInventedNumber() {
        setTimeline(
            listOf(
                userEntry("u1", "").copy(
                    attachments = listOf(TimelineAttachment("att_voice", "voice_2.m4a", "audio/mp4")),
                ),
            ),
        )

        compose.onNodeWithText("voice_2.m4a").assertIsDisplayed()
        compose.onNodeWithTag("attachment-audio-duration:att_voice", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun previewsWrittenAfterTheFirstFrameRefreshTheTimeline() {
        val previews = AssistantLocalPreviews()
        setTimeline(
            listOf(
                userEntry("u1", "").copy(
                    attachments = listOf(TimelineAttachment("att_late", "crop.png", "image/png")),
                ),
            ),
            previews = previews,
        )
        // 没有字节的图片由渲染层给出明确占位（contentDescription 是它对外的一句话）。
        compose.onNodeWithContentDescription("图片预览不可用", substring = true).assertIsDisplayed()

        compose.runOnIdle { previews.put("att_late", png()) }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("attachment-image:att_late", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- 3. 审批 ----

    @Test
    fun pressingAnApprovalTierReportsThatCardsApprovalId() {
        val decisions = mutableListOf<Pair<String, ApprovalChoice>>()
        setTimeline(
            listOf(approvalEntry()),
            onDecide = { id, choice -> decisions += id to choice },
        )

        compose.onNodeWithTag(ApprovalCardTags.ROOT).assertIsDisplayed()
        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.ONCE)).performClick()

        assertEquals(listOf("apr_9" to ApprovalChoice.ONCE), decisions)
    }

    // ---- 4. 智能跟随 ----

    private fun longHistory(count: Int): List<TimelineEntry> = (1..count).flatMap { index ->
        listOf(
            userEntry("u$index", "问题 $index"),
            assistantEntry("a$index", "回答 $index：" + "内容。".repeat(30)),
        )
    }

    @Test
    fun browsingHistoryShowsTheButtonAndAClickReturnsToTheLatestMessage() {
        val history = longHistory(15)
        setTimeline(history)

        // 反向布局：索引 0 是最新一条，索引越大越旧。
        compose.onNodeWithTag(AssistantTimelineTags.LIST).performScrollToIndex(history.lastIndex)
        compose.onNodeWithText("回到最新").assertIsDisplayed()

        compose.onNodeWithText("回到最新").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("回到最新").assertDoesNotExist()
        compose.onNodeWithText("回答 15：" + "内容。".repeat(30)).assertIsDisplayed()
    }

    @Test
    fun aNewMessageDoesNotYankTheReaderBackWhileBrowsingHistory() {
        var entries by mutableStateOf(longHistory(15))
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    AssistantTimelineView(
                        entries = entries,
                        previews = AssistantLocalPreviews(),
                        threadId = "thread_1",
                    )
                }
            }
        }
        compose.onNodeWithTag(AssistantTimelineTags.LIST).performScrollToIndex(entries.lastIndex)
        compose.onNodeWithText("回到最新").assertIsDisplayed()

        val appended = assistantEntry("a_new", "新到的回复：" + "新内容。".repeat(30))
        compose.runOnIdle { entries = entries + appended }
        compose.waitForIdle()

        // 阅读位置不动：最新一条仍不在视口里，按钮还在。
        compose.onNodeWithText("新到的回复：" + "新内容。".repeat(30)).assertDoesNotExist()
        compose.onNodeWithText("回到最新").assertIsDisplayed()
    }

    @Test
    fun anchoredAtTheLatestANewMessageAppearsWithoutPrompting() {
        var entries by mutableStateOf(longHistory(2))
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    AssistantTimelineView(
                        entries = entries,
                        previews = AssistantLocalPreviews(),
                        threadId = "thread_1",
                    )
                }
            }
        }
        compose.onNodeWithText("回到最新").assertDoesNotExist()

        val appended = assistantEntry("a_new", "追加的回复")
        compose.runOnIdle { entries = entries + appended }
        compose.waitForIdle()

        compose.onNodeWithText("追加的回复").assertIsDisplayed()
        compose.onNodeWithText("回到最新").assertDoesNotExist()
    }
}
