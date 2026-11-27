package com.openandroidintelligence.conversation.workbench

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.openandroidintelligence.conversation.assistant.AssistantApprovalDto
import com.openandroidintelligence.conversation.assistant.AssistantAppearanceDto
import com.openandroidintelligence.conversation.assistant.AssistantStateDto
import com.openandroidintelligence.conversation.assistant.AssistantTimelineEntryDto
import com.openandroidintelligence.conversation.assistant.decodeTimeline
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.LocalSendAtom
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.state.AwaitableWorkbenchPersistence
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.state.WorkbenchCheckpoint
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI 与控制器全链路一致性回归测试：
 * 覆盖本地发送回显、SSE/WS 实时流分片防闪烁、HTTP 历史加载、本地持久恢复、
 * 摘要标题解析、端侧特殊卡片保留、以及缺失/内部 sender 渲染防御。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextInjectionPipelineUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = mutableListOf<WorkbenchController>()

    @After
    fun tearDown() {
        controllers.forEach { it.close() }
        testScope.cancel()
    }

    private fun scope() = ConversationScope("profile", "gateway", "account", "install")

    private fun catalogRepo() = object : AgentCommandCatalogRepository {
        override suspend fun get(gatewayId: String, languageCode: String) =
            AgentCommandCatalog(CatalogVersion("v1"), emptyList())
    }

    @Test
    fun `本地发送回显与实时流和HTTP历史与本地冷启动四条路径投影完全一致且保留合法卡片`() {
        val threadId = "thread-consistency"
        val eventsFlow = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 32)
        val sentMessages = mutableListOf<OutgoingMessage>()

        val mixedServerMessages = listOf(
            TimelineMessage("m-sys", "system", listOf(MessagePart.Text("System bootstrap")), 10L, conversationId = ConversationId(threadId)),
            TimelineMessage("m-dev", "developer", listOf(MessagePart.Text("Developer spec")), 15L, conversationId = ConversationId(threadId)),
            TimelineMessage("m-missing-sender", "", listOf(MessagePart.Text("Blank sender ghost")), 18L, conversationId = ConversationId(threadId)),
            TimelineMessage("m-hidden", "assistant", listOf(MessagePart.Text("Internal trace")), 19L, conversationId = ConversationId(threadId), visibility = "hidden"),
            TimelineMessage(
                "m-user-raw",
                "user",
                listOf(
                    MessagePart.Text(
                        "Conversation info (untrusted metadata):\n```json\n{\"room\":\"r1\"}\n```\n\n正文讨论 `<system-reminder>` 与日期 [2026-11-20]",
                    ),
                ),
                20L,
                conversationId = ConversationId(threadId),
            ),
            TimelineMessage(
                "m-att-only",
                "user",
                listOf(
                    MessagePart.Text("<environment_context><cwd>/repo</cwd></environment_context>"),
                    MessagePart.Attachment(AttachmentDraftId("att-ui-1"), "log.txt", "text/plain"),
                ),
                30L,
                conversationId = ConversationId(threadId),
            ),
            TimelineMessage(
                "m-assistant-clean",
                "assistant",
                listOf(MessagePart.Text("<system-reminder>hidden</system-reminder>\n排查完毕，问题已修复。")),
                40L,
                conversationId = ConversationId(threadId),
            ),
        )

        var httpTimeline = mixedServerMessages
        val fakePersistence = object : AwaitableWorkbenchPersistence {
            var checkpoint: WorkbenchCheckpoint? = null
            val sendAtoms = mutableMapOf<String, List<LocalSendAtom>>()
            override fun load(): WorkbenchCheckpoint? = checkpoint
            override fun save(checkpoint: WorkbenchCheckpoint) {
                this.checkpoint = checkpoint
            }
            override fun loadSendAtoms(conversationId: String): List<LocalSendAtom> = sendAtoms[conversationId].orEmpty()
            override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) {
                sendAtoms[conversationId] = atoms
            }
            override suspend fun loadConversations() = ConversationPage(
                listOf(ConversationSummary(ConversationId(threadId), "<system-reminder>x</system-reminder>\n一致性排查会话", 100L)),
                null,
            )
            override suspend fun loadTimeline(conversationId: String) = TimelinePage(mixedServerMessages, null)
            override suspend fun loadKnownMediaMessages(): List<TimelineMessage> = emptyList()
            override suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint) {
                this.checkpoint = checkpoint
            }
            override suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>) {
                sendAtoms[conversationId] = atoms
            }
            override suspend fun flush() = Unit
            override suspend fun closeStore() = Unit
        }

        val sendGate = kotlinx.coroutines.CompletableDeferred<MessageAcceptance>()
        val repo = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(
                    listOf(ConversationSummary(ConversationId(threadId), "<available_skills>k</available_skills>\n一致性排查会话", 100L)),
                    null,
                    authoritative = true,
                )
            override suspend fun timeline(conversationId: String, page: PageRequest) =
                TimelinePage(httpTimeline, null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("new-t"), "", 0L)
            override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
                sentMessages += message
                return sendGate.await()
            }
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = eventsFlow
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }

        // 1. 路径 3（HTTP 历史加载） + 路径 5（标题与列表摘要）
        val onlineController = WorkbenchController(
            scope = testScope,
            repository = repo,
            catalogRepository = catalogRepo(),
            scopeFactory = ::scope,
            supportsApprovalCards = true,
            persistence = fakePersistence,
            persistenceDispatcher = Dispatchers.Unconfined,
        ).also { controllers += it }

        compose.runOnIdle { onlineController.openThread(threadId) }
        compose.waitForIdle()

        val httpState = onlineController.state.value
        assertEquals("一致性排查会话", httpState.activeThreadTitle)
        val httpEntries = (httpState.timeline as com.openandroidintelligence.conversation.state.Loadable.Ready).value
        assertEquals(listOf("m-user-raw", "m-att-only", "m-assistant-clean"), httpEntries.map { it.key })
        assertEquals("正文讨论 `<system-reminder>` 与日期 [2026-11-20]", httpEntries[0].text)
        assertEquals("", httpEntries[1].text)
        assertEquals(1, httpEntries[1].attachments.size)
        assertEquals("排查完毕，问题已修复。", httpEntries[2].text)

        // 2. 路径 1（本地发送回显：后端未应答前即时回显已剥离注入信封，同时发往远端保留原文）
        val wrappedDraft = "[System Context]\nenv=prod\n[/System Context]\n请运行回归测试 `[2026-11-20]`"
        compose.runOnIdle {
            onlineController.editDraft(wrappedDraft)
            onlineController.sendDraft()
        }
        compose.waitForIdle()
        val afterLocalSend = (onlineController.state.value.timeline as com.openandroidintelligence.conversation.state.Loadable.Ready).value
        val optimisticBubble = afterLocalSend.firstOrNull { it.key.startsWith("local_") }
            ?: error("Missing local optimistic bubble in ${afterLocalSend.map { it.key to it.text }}")
        assertEquals("请运行回归测试 `[2026-11-20]`", optimisticBubble.text)
        sendGate.complete(MessageAcceptance("srv-1", optimisticBubble.clientMessageId!!.value))
        compose.waitForIdle()

        // 3. 路径 2（实时事件流 + 半截标签防闪烁 + 审批/执行卡片保护 + 内部角色过滤）
        compose.runOnIdle {
            // 内部 system/blank 事件不得污染时间线
            eventsFlow.tryEmit(
                VerifiedConversationEvent.TimelineUpsert(
                    "ev-sys-ignore", 200L, 10L,
                    TimelineMessage("live-sys", "system", listOf(MessagePart.Text("hidden sys")), 200L, conversationId = ConversationId(threadId)),
                ),
            )
            // 半截标签流式 delta 不得闪现
            eventsFlow.tryEmit(
                VerifiedConversationEvent.TimelineUpsert(
                    "ev-stream-1", 210L, 11L,
                    TimelineMessage("live-asst", "assistant", listOf(MessagePart.Text("正在处理<system-")), 210L, state = "STREAMING", conversationId = ConversationId(threadId), generationId = "g-live"),
                ),
            )
        }
        compose.waitForIdle()
        val streamingEntries = (onlineController.state.value.timeline as com.openandroidintelligence.conversation.state.Loadable.Ready).value
        assertFalse(streamingEntries.any { it.key == "live-sys" })
        val liveStreamingBubble = streamingEntries.firstOrNull { it.key == "live-asst" }
            ?: error("Missing live-asst streaming bubble in ${streamingEntries.map { it.key to it.text }}")
        assertEquals("正在处理", liveStreamingBubble.text)

        compose.runOnIdle {
            eventsFlow.tryEmit(
                VerifiedConversationEvent.TimelineUpsert(
                    "ev-stream-2", 220L, 12L,
                    TimelineMessage(
                        "live-asst",
                        "assistant",
                        listOf(MessagePart.Text("正在处理<system-reminder>secret</system-reminder>，全部完成")),
                        210L,
                        state = "CONFIRMED",
                        conversationId = ConversationId(threadId),
                        generationId = "g-live",
                    ),
                ),
            )
            eventsFlow.tryEmit(
                VerifiedConversationEvent.ApprovalRequested(
                    eventId = "ev-appr-1",
                    occurredAt = 230L,
                    request = ApprovalRequest(
                        approvalId = ApprovalId("appr-ui-1"),
                        conversationId = ConversationId(threadId),
                        command = "./gradlew test",
                        reason = "执行测试",
                        options = listOf(ApprovalOption(ApprovalChoice.ONCE, "允许一次", ApprovalOptionStyle.PRIMARY)),
                        timeoutSeconds = 60L,
                        requestedAt = 230L,
                    ),
                    conversationId = ConversationId(threadId),
                ),
            )
            eventsFlow.tryEmit(
                VerifiedConversationEvent.ExecutionStepsUpdated(
                    eventId = "ev-exec-1",
                    occurredAt = 235L,
                    conversationId = ConversationId(threadId),
                    generationId = "g-live",
                    executionRevision = 13L,
                    steps = listOf(NativeExecutionStep("st-1", "ev-exec-1", "bash", "ls", "ok", NativeExecutionStatus.COMPLETED, 13L, sourceMessageId = "live-asst", occurredAt = 235L)),
                ),
            )
        }
        compose.waitForIdle()
        val liveEntries = (onlineController.state.value.timeline as com.openandroidintelligence.conversation.state.Loadable.Ready).value
        val confirmedBubble = liveEntries.firstOrNull { it.key == "live-asst" }
            ?: error("Missing live-asst confirmed bubble in ${liveEntries.map { it.key to it.text }}")
        assertEquals("正在处理，全部完成", confirmedBubble.text)
        assertTrue("审批卡片必须保留", liveEntries.any { it.approval?.request?.approvalId == ApprovalId("appr-ui-1") })

        // 4. 路径 4（本地持久化冷启动恢复）：与 HTTP 路径对比同一批混合消息的一致性
        val coldOfflineRepo = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage =
                throw java.io.IOException("OFFLINE")
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("new"), "", 0L)
            override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("x", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = MutableSharedFlow()
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        fakePersistence.checkpoint = WorkbenchCheckpoint(
            threads = listOf(ConversationSummary(ConversationId(threadId), "<system-reminder>x</system-reminder>\n一致性排查会话", 100L)),
            threadId = threadId,
            title = "<available_skills>x</available_skills>\n一致性排查会话",
            messages = emptyList(),
            revisions = emptyMap(),
            draft = "",
            draftRevision = 1L,
            attachments = emptyList(),
            submission = null,
            renamedThreads = emptySet(),
            eventIds = emptySet(),
        )
        val restoredController = WorkbenchController(
            scope = testScope,
            repository = coldOfflineRepo,
            catalogRepository = catalogRepo(),
            scopeFactory = ::scope,
            persistence = fakePersistence,
            persistenceDispatcher = Dispatchers.Unconfined,
        ).also { controllers += it }
        compose.waitForIdle()

        val restoredEntries = (restoredController.state.value.timeline as com.openandroidintelligence.conversation.state.Loadable.Ready).value
        assertEquals(listOf("m-user-raw", "m-att-only", "m-assistant-clean", "srv-1"), restoredEntries.map { it.key })
        assertEquals(
            httpEntries.map { it.key to it.text },
            restoredEntries.take(3).map { it.key to it.text },
        )
        assertEquals("请运行回归测试 `[2026-11-20]`", restoredEntries[3].text)
        assertEquals("一致性排查会话", restoredController.state.value.activeThreadTitle)
    }

    @Test
    fun `标题解析与AssistantBridge解码过滤非对话角色和注入信封`() {
        assertEquals("新标题", ConversationTitleResolver.resolve("<system-reminder>s</system-reminder>新标题", "新对话"))
        assertEquals("新对话", ConversationTitleResolver.resolve("<system-reminder>only meta</system-reminder>", "新对话"))
        assertTrue(ConversationTitleResolver.isPlaceholderTitle("<environment_context><cwd>/</cwd></environment_context>"))

        val bridgeDto = AssistantStateDto(
            version = 2,
            connected = true,
            timeline = listOf(
                AssistantTimelineEntryDto("b-blank", "", "Missing sender should drop", streaming = false, textTruncated = false),
                AssistantTimelineEntryDto("b-sys", "system", "System prompt", streaming = false, textTruncated = false),
                AssistantTimelineEntryDto("b-user", "user", "[Current time: 10:00 UTC]\n真实提问 `<system-reminder>`", streaming = false, textTruncated = false),
                AssistantTimelineEntryDto("b-exec", "system", "", streaming = false, textTruncated = false, executionFact = true,
                    executionSteps = emptyList(), approval = null),
                AssistantTimelineEntryDto("b-appr", "system", "", streaming = false, textTruncated = false,
                    approval = AssistantApprovalDto("a1", "WAITING", "ls", "check", 10L, emptyList())),
            ),
            attachments = emptyList(),
            notice = null,
            appearance = AssistantAppearanceDto(),
            handoff = null,
        )
        val decoded = decodeTimeline(bridgeDto)
        assertEquals(listOf("b-user", "b-exec", "b-appr"), decoded.map { it.key })
        assertEquals("真实提问 `<system-reminder>`", decoded.first().text)
    }

    @Test
    fun `TimelineRow对缺失或内部sender绝不退化渲染为助手气泡且保留本地回执`() {
        compose.setContent {
            MaterialTheme {
                Column {
                    TimelineRow(
                        entry = TimelineEntry("bad-blank", "", "缺失发送者不能渲染", false, 1L, false, null),
                        onDecide = { _, _ -> },
                    )
                    TimelineRow(
                        entry = TimelineEntry("bad-sys", "system", "未标记系统回执的系统消息不能渲染", false, 2L, false, null),
                        onDecide = { _, _ -> },
                    )
                    TimelineRow(
                        entry = TimelineEntry("good-receipt", "system", "已创建新对话回执", false, 3L, false, null, systemThreadId = "new-thread-99"),
                        onDecide = { _, _ -> },
                    )
                    TimelineRow(
                        entry = TimelineEntry("good-assistant", "assistant", "正常助手气泡可见", false, 4L, false, null),
                        onDecide = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithText("缺失发送者不能渲染").assertDoesNotExist()
        compose.onNodeWithText("未标记系统回执的系统消息不能渲染").assertDoesNotExist()
        compose.onNodeWithText("已创建新对话回执").assertIsDisplayed()
        compose.onNodeWithText("正常助手气泡可见").assertIsDisplayed()
    }
}
