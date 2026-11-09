package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.voice.ActiveVoiceRecording
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主会话录音态的行为回归。
 *
 * 验收点：点语音后不再出现任何中央卡片，录音行直接出现在底部输入坞里，
 * 时间线仍然在它上方；取消与发送各自只触发自己的回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class WorkbenchVoiceRecordingTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun close() { scope.cancel() }

    private fun controller() = WorkbenchController(
        scope,
        EmptyGateway(),
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        { ConversationScope("p", "g", "a", "i") },
        allowSending = false,
        restoreLastThread = false,
    )

    @Test
    fun recordingReplacesTheComposerRowWithoutAnyDialog() {
        var recording by mutableStateOf<ActiveVoiceRecording?>(null)
        var cancels = 0
        var sends = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    WorkbenchScreen(
                        controller = controller(),
                        gatewayLabel = "gateway",
                        onOpenSettings = {},
                        onPickCamera = {},
                        onPickGallery = {},
                        onPickDocument = {},
                        onVoiceInput = {},
                        recording = recording,
                        onCancelRecording = { cancels++ },
                        onFinishRecording = { sends++ },
                        onLogout = {},
                    )
                }
            }
        }

        // 未录音：输入坞有麦克风入口，没有录音控件，也没有任何对话框。
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在录音").assertDoesNotExist()
        compose.onNodeWithContentDescription("取消录音").assertDoesNotExist()
        compose.onNodeWithText("正在录音").assertDoesNotExist()

        compose.runOnIdle { recording = ActiveVoiceRecording(elapsedMs = 4_500L, level = 0.6f) }
        compose.waitForIdle()

        // 录音中：波形/计时/取消/发送都在底部输入坞里，输入入口让位。
        compose.onNodeWithContentDescription("正在录音").assertIsDisplayed()
        compose.onNodeWithContentDescription("录音波形", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("0:04").assertExists()
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").assertDoesNotExist()
        // 没有任何「结束并发送」的弹窗按钮：录音交互已完全内联。
        compose.onNodeWithText("结束并发送").assertDoesNotExist()
        compose.onNodeWithText("录音结束后会将原始音频直接发送给 Agent。当前草稿和附件会保留。离开应用将取消录音。")
            .assertDoesNotExist()

        compose.onNodeWithContentDescription("取消录音").performClick()
        assertEquals(1, cancels)
        assertEquals(0, sends)

        recording = null
        compose.waitForIdle()
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在录音").assertDoesNotExist()
    }

    private class EmptyGateway : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(emptyList<ConversationSummary>(), null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("conv_test"), "新对话", 0)
        override suspend fun submitMessage(message: OutgoingMessage) =
            MessageAcceptance("msg_test", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
