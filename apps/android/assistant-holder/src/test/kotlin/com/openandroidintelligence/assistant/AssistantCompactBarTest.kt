package com.openandroidintelligence.assistant

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.voice.ActiveVoiceRecording
import com.openandroidintelligence.conversation.voice.formatVoiceDuration
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
 * 底部紧凑输入栏的行为回归。
 *
 * 断言用户能看见的四件事：
 * 1. 多行输入自然增高，到 6 行封顶；
 * 2. 空草稿禁用发送、有内容启用、生成中变成停止键；
 * 3. 录音态整条内容被替换（输入框、麦克风、圈选都不在），取消/发送走各自的回调；
 * 4. 每个可点元素都有中文 contentDescription，计时状态可被朗读。
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AssistantCompactBarTest {
    @get:Rule val compose = createComposeRule()

    private var sends = 0
    private var stops = 0
    private var recordings = 0
    private var selections = 0
    private var added = 0
    private var cancels = 0
    private var finishes = 0

    /** 编辑器的状态由测试直接写入：不依赖 IME 输入，断言的是尺寸而不是输入法行为。 */
    private fun render(
        editorText: String = "",
        isGenerating: Boolean = false,
        isStopping: Boolean = false,
        enabled: Boolean = true,
        hasUnknownOutcome: Boolean = false,
        attachments: List<AssistantCompactAttachment> = emptyList(),
        recording: ActiveVoiceRecording? = null,
        onRemoveAttachment: (String) -> Unit = {},
        reduceMotion: Boolean = false,
    ): TextFieldState {
        val editor = TextFieldState(editorText)
        compose.setContent {
            CompositionLocalProvider(
                LocalMotionPolicy provides MotionPolicy(reduceMotion = reduceMotion),
            ) {
                MaterialTheme {
                    AssistantCompactBar(
                        editor = editor,
                        onDraftChange = {},
                        isGenerating = isGenerating,
                        isStopping = isStopping,
                        onSend = { sends++ },
                        onStop = { stops++ },
                        onStartRecording = { recordings++ },
                        onStartScreenSelection = { selections++ },
                        onAddAttachment = { added++ },
                        enabled = enabled,
                        hasUnknownOutcome = hasUnknownOutcome,
                        attachments = attachments,
                        onRemoveAttachment = onRemoveAttachment,
                        recording = recording,
                        onCancelRecording = { cancels++ },
                        onFinishRecording = { finishes++ },
                    )
                }
            }
        }
        return editor
    }

    private fun inputHeight(): Float {
        val bounds = compose.onNodeWithTag(AssistantCompactBarTags.INPUT).getUnclippedBoundsInRoot()
        return bounds.bottom.value - bounds.top.value
    }

    private fun type(editor: TextFieldState, text: String) {
        compose.runOnIdle { editor.setTextAndPlaceCursorAtEnd(text) }
        compose.waitForIdle()
    }

    @Test
    fun inputBarGrowsWithEveryLineAndStopsAtSixLines() {
        val editor = render()
        val empty = inputHeight()

        type(editor, "第一行")
        val oneLine = inputHeight()
        type(editor, "第一行\n第二行")
        val twoLines = inputHeight()
        type(editor, "第一行\n第二行\n第三行")
        val threeLines = inputHeight()

        assertTrue(empty > 0f)
        assertTrue("第二行应让输入框增高", twoLines > oneLine)
        assertTrue("第三行应继续增高", threeLines > twoLines)

        // 6 行是上限：再多的行只会在框内滚动，高度不再增长。
        type(editor, List(6) { "第${it + 1}行" }.joinToString("\n"))
        val sixLines = inputHeight()
        type(editor, List(12) { "第${it + 1}行" }.joinToString("\n"))
        val twelveLines = inputHeight()

        assertEquals("超过 6 行后不得继续增高", sixLines, twelveLines, 1f)
        assertTrue(sixLines > threeLines)
    }

    @Test
    fun sendIsDisabledUntilTheDraftHasContent() {
        val editor = render()

        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()

        type(editor, "你好")
        compose.onNodeWithContentDescription("发送消息").assertIsEnabled()
    }

    @Test
    fun whitespaceOnlyDraftCannotBeSent() {
        render(editorText = "   ")

        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
    }

    @Test
    fun generatingReplacesSendWithStopAndCallsOnlyStop() {
        render(editorText = "你好", isGenerating = true)

        compose.onNodeWithContentDescription("发送消息").assertDoesNotExist()
        compose.onNodeWithContentDescription("停止生成").assertIsEnabled().performClick()

        assertEquals(0, sends)
        assertEquals(1, stops)
    }

    @Test
    fun stoppingDisablesTheStopKey() {
        render(editorText = "你好", isGenerating = true, isStopping = true)

        compose.onNodeWithContentDescription("正在停止生成").assertIsNotEnabled()
    }

    @Test
    fun disabledComposerBlocksSendEvenWithContent() {
        render(editorText = "你好", enabled = false)

        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
    }

    @Test
    fun unknownOutcomeBlocksSendEvenWithContent() {
        render(editorText = "你好", hasUnknownOutcome = true)

        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
    }

    /**
     * 输入栏最左侧是「＋」附件入口；圈选按钮已按需求移除（改为唤起后直接画圈）。
     *
     * `onStartScreenSelection` 参数仍保留：ASSIST 兜底页要用它显示「本入口无系统截图」。
     */
    @Test
    fun addAttachmentButtonIsReachableAndScreenSelectionButtonIsGone() {
        render()

        compose.onNodeWithContentDescription("添加附件").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled().performClick()

        assertEquals(1, added)
        assertEquals(1, recordings)
        compose.onNodeWithContentDescription("屏幕圈选").assertDoesNotExist()
        assertEquals(0, selections)
    }

    @Test
    fun recordingModeReplacesTheWholeInputRow() {
        render(recording = ActiveVoiceRecording(elapsedMs = 7_400L, level = 0.5f))

        compose.onNodeWithTag(AssistantCompactBarTags.RECORDING).assertIsDisplayed()
        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).assertDoesNotExist()
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送消息").assertDoesNotExist()
        compose.onNodeWithContentDescription("取消录音").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送语音").assertIsDisplayed()
    }

    @Test
    fun recordingTimerIsReadableAndCancelAndSendAreSeparateActions() {
        render(recording = ActiveVoiceRecording(elapsedMs = 65_000L))

        compose.onNodeWithContentDescription("正在录音").assertIsDisplayed()

        compose.onNodeWithContentDescription("取消录音").performClick()
        assertEquals(1, cancels)
        assertEquals(0, finishes)

        compose.onNodeWithContentDescription("发送语音").performClick()
        assertEquals(1, finishes)
    }

    @Test
    fun recordingDurationIsFormattedAsMinutesAndSeconds() {
        assertEquals("0:00", formatVoiceDuration(0L))
        assertEquals("0:07", formatVoiceDuration(7_400L))
        assertEquals("1:05", formatVoiceDuration(65_000L))
        // 负时长这类异常输入也不能产出负数。
        assertEquals("0:00", formatVoiceDuration(-1_000L))
    }

    @Test
    fun everyInteractiveElementHasChineseContentDescription() {
        render(
            editorText = "你好",
            attachments = listOf(
                AssistantCompactAttachment(id = "a1", filename = "截图.png", mediaType = "image/png"),
            ),
        )

        listOf("助理消息输入", "添加附件", "语音输入", "发送消息").forEach { label ->
            compose.onNodeWithContentDescription(label).assertExists()
        }
        compose.onNodeWithContentDescription("附件：截图.png", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("移除附件：截图.png").assertExists()
    }

    @Test
    fun attachmentStripListsAddedFilesAndRemovalTargetsTheRightOne() {
        val removed = mutableListOf<String>()
        render(
            editorText = "你好",
            attachments = listOf(
                AssistantCompactAttachment(id = "img", filename = "截图.png", mediaType = "image/png"),
                AssistantCompactAttachment(id = "voice", filename = "voice_1.m4a", mediaType = "audio/mp4"),
            ),
            onRemoveAttachment = { removed += it },
        )

        compose.onNodeWithTag(AssistantCompactBarTags.ATTACHMENTS).assertIsDisplayed()
        compose.onNodeWithTag(AssistantCompactBarTags.attachment("img")).assertIsDisplayed()
        compose.onNodeWithTag(AssistantCompactBarTags.attachment("voice")).assertIsDisplayed()

        compose.onNodeWithContentDescription("移除附件：voice_1.m4a").performClick()
        assertEquals(listOf("voice"), removed)
    }

    @Test
    fun attachmentsAreAbsentWhenTheDraftHasNone() {
        render()

        compose.onNodeWithTag(AssistantCompactBarTags.ATTACHMENTS).assertDoesNotExist()
    }

    @Test
    fun primaryActionsKeepTheMinimumTouchTarget() {
        render(editorText = "你好")

        compose.onNodeWithContentDescription("发送消息").assertHeightIsAtLeast(Dimensions.MinimumTouchTarget)
        compose.onNodeWithContentDescription("语音输入").assertHeightIsAtLeast(Dimensions.MinimumTouchTarget)
        compose.onNodeWithContentDescription("添加附件").assertHeightIsAtLeast(Dimensions.MinimumTouchTarget)
    }

    @Test
    fun emptyDraftStillShowsTheInputHint() {
        render()

        compose.onNodeWithContentDescription("助理消息输入").assertIsDisplayed()
    }

    // ---- 进场与展开动效 ----

    @Test
    fun reducedMotionEntranceLandsImmediatelyWithVisibleContent() {
        // 减少动态时进场直接落位：不播放位移/缩放，首帧就是可用状态。
        render(reduceMotion = true)

        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).assertIsDisplayed()
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送消息").assertExists()
    }

    /**
     * 横条已经从输入栏搬到 [AssistantSessionRoot] 的顶部（需求 5.1「始终显示」）。
     *
     * 输入栏自身不再持有任何拖动指示条：整个助手只有最顶部那一个。
     */
    @Test
    fun theCompactBarNeverOwnsTheDragHandle() {
        render()

        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).assertDoesNotExist()

        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(AssistantCompactBarTags.HANDLE).assertDoesNotExist()
        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).assertIsDisplayed()
    }

    @Test
    fun expandedBarStillAcceptsText() {
        val editor = render()

        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(AssistantCompactBarTags.INPUT).performTextInput("你好")
        compose.waitForIdle()

        assertEquals("你好", editor.text.toString())
        // 展开态下发送键随内容可用，展开不影响原有发送语义。
        compose.onNodeWithContentDescription("发送消息").assertIsEnabled()
    }
}
