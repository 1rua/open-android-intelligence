package com.openandroidintelligence.mobile.e2e

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.remote.RemoteAttachmentCoordinator
import com.openandroidintelligence.conversation.data.remote.RemoteHistoryPage
import com.openandroidintelligence.conversation.data.remote.RemoteModelCatalog
import com.openandroidintelligence.conversation.data.remote.RemoteModelSelection
import com.openandroidintelligence.conversation.data.remote.RemoteSessionCarrier
import com.openandroidintelligence.conversation.data.remote.RemoteSessionPage
import com.openandroidintelligence.conversation.data.remote.RemoteSessionRow
import com.openandroidintelligence.conversation.data.remote.RemoteWorkspace
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.SessionCapability
import com.openandroidintelligence.conversation.sanitizer.SystemPromptSanitizer
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.workbench.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.*
import com.openandroidintelligence.mobile.util.ContentResolverExtensions
import com.openandroidintelligence.ui.resources.R as UiR
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.BaseCursor
import org.robolectric.shadows.ShadowNotificationManager

// ==========================================
// 真实控制器轻量级测试 Harness (支持 F7 语音状态机与发送锁)
// ==========================================

private class FakeWorkbenchGateway(
    var timelineMessages: List<TimelineMessage> = emptyList(),
) :
    ConversationRepository,
    GenerationTracker,
    MessageOutcomeQuery {
    override val generationId = MutableStateFlow<String?>(null)
    override val generationState = MutableStateFlow(GenerationState.IDLE)

    override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
        ConversationPage(emptyList(), null)
    override suspend fun timeline(conversationId: String, page: PageRequest) =
        TimelinePage(timelineMessages, null)
    override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
        Conversation(ConversationId("conv_test"), "", 0)
    override suspend fun submitMessage(message: OutgoingMessage) =
        MessageAcceptance("msg_test", message.clientMessageId.value)
    override suspend fun submitMessage(conversationId: String, message: OutgoingMessage) =
        MessageAcceptance("msg_test", message.clientMessageId.value)
    override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
    override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
    override suspend fun cancelGeneration(generationId: String, requestId: String) =
        CancelGenerationResult(CancelGenerationOutcome.CANCELLED)
    override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId) = null
}

private class FakeAttachmentCoordinator : AttachmentDraftCoordinator {
    override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft =
        AttachmentDraft(AttachmentDraftId("draft_voice"), selection.filename, selection.mediaType, 100L, "hash", AttachmentState.VERIFIED)
    override suspend fun armSubmission(draftId: String, revision: Long) =
        PendingSubmissionIntent(SubmitIntentId("intent"), ClientMessageId("client"), revision, "")
    override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
    override fun observe(draftId: String) =
        MutableStateFlow(AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.VERIFIED))
    override fun retry(draftId: String) = Unit
    override suspend fun discard(draftId: String) = Unit
    override suspend fun releaseAfterSubmit(draftId: String) = Unit
    override fun remoteAttachmentId(draftId: String) = "remote_$draftId"
}

private fun createWorkbenchController(
    scope: CoroutineScope,
    gateway: FakeWorkbenchGateway,
    coordinator: AttachmentDraftCoordinator = FakeAttachmentCoordinator(),
): WorkbenchController = WorkbenchController(
    scope = scope,
    repository = gateway,
    catalogRepository = object : AgentCommandCatalogRepository {
        override suspend fun get(gatewayId: String, languageCode: String) =
            AgentCommandCatalog(CatalogVersion("v1"), emptyList())
    },
    scopeFactory = { ConversationScope("profile", "gateway", "account", "install") },
    attachmentCoordinator = coordinator,
    allowSending = true,
    restoreLastThread = false,
)

// ==========================================
// 真实多模态图片轻量级测试 Harness (支持 F8 图片装配与压缩)
// ==========================================

private fun e2eObj(vararg fields: Pair<String, Any?>) = Json.of(linkedMapOf(*fields)) as JsonValue.JObject

private class E2EAttachmentRecovery(var records: List<RecoveredAttachmentDraft> = emptyList()) : AttachmentDraftRecoveryStore {
    override fun load() = records
    override fun save(records: List<RecoveredAttachmentDraft>) { this.records = records }
}

private class E2EAttachmentStaging(val files: MutableMap<String, ByteArray> = mutableMapOf()) : LocalAttachmentStagingStore {
    override fun stage(selection: LocalAttachmentSelection, onBytesStaged: (Long) -> Unit, isCancelled: () -> Boolean): StagedAttachmentContent {
        val bytes = selection.contentSource.openStream().use { it.readBytes() }
        val id = "staged_${files.size + 1}"
        files[id] = bytes
        return StagedAttachmentContent(id, bytes.size.toLong(), "hash_${files.size}")
    }
    override fun openStream(stagedId: String) = ByteArrayInputStream(files[stagedId] ?: error("FILE_NOT_FOUND: $stagedId"))
    override fun delete(stagedId: String) { files.remove(stagedId) }
    override fun cleanupExpired(nowMillis: Long, maxAgeMillis: Long) = Unit
    override fun cleanup() = Unit
}

private class E2ERpcChannel(override val capabilities: Set<String> = emptySet()) : RemoteRpcChannel {
    override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
    override val events = emptyFlow<RemoteRpcEvent>()
    val calls = mutableListOf<String>()
    override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
        calls += method
        return e2eObj()
    }
    override suspend fun close() = Unit
}

private class E2ESessionCarrier(
    override val backend: String = "dsh",
    override val channel: RemoteRpcChannel = E2ERpcChannel(),
) : RemoteSessionCarrier {
    override val capabilities: Set<SessionCapability> = setOf(SessionCapability.SEND, SessionCapability.STREAM)
    override suspend fun workspaces(): List<RemoteWorkspace> = emptyList()
    override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest): RemoteSessionPage = RemoteSessionPage(emptyList())
    override suspend fun create(workspace: RemoteWorkspace): RemoteSessionRow = RemoteSessionRow("1", "", 0, 0)
    override suspend fun history(sessionId: String, page: PageRequest): RemoteHistoryPage = RemoteHistoryPage(emptyList())
    override fun follow(sessionId: String) = emptyFlow<JsonValue>()
    override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?): JsonValue = e2eObj()
    override suspend fun interrupt(sessionId: String, generationId: String): JsonValue = e2eObj()
    override suspend fun respond(sessionId: String, handle: String, allow: Boolean): JsonValue = e2eObj()
    override suspend fun models(sessionId: String): RemoteModelCatalog = RemoteModelCatalog(emptyList(), emptyMap())
    override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection): RemoteModelSelection = selection
}

/**
 * 4-Tier 覆盖矩阵测试套件：
 * - Tier 3: 跨特性成对组合测试 (Pairwise Cross-Feature Combinations, 8 组)
 * - Tier 4: 真实实机交互端到端场景验证 (Real-World Application Scenarios, 5 组)
 *
 * 覆盖特性：
 * F1 通用工具解析 | F2 消除空气泡 | F3 异构时序无空行 | F4 通知生命周期 |
 * F5 思维链流式输出 | F6 提示词剥离 | F7 麦克风与发送锁 | F8 多模态图片发送
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FullTurnInteractionE2ETest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager
    private lateinit var replySettings: ReplyNotificationSettings
    private lateinit var publisher: AndroidReplyNotificationPublisher
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        replyCompletionPreferences(context, "profile_e2e", "account_e2e").edit().clear().commit()
        context.getSharedPreferences("agent-reply-alert-settings", 0).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = shadowOf(notificationManager)
        notificationManager.cancelAll()
        replySettings = ReplyNotificationSettings(context)
        publisher = AndroidReplyNotificationPublisher(context, replySettings)
    }

    @After
    fun tearDown() {
        coroutineScope.cancel()
    }

    private fun registerTestImage(
        uriString: String,
        width: Int,
        height: Int,
        mimeType: String = "image/png",
    ): Uri {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val shadowResolver = shadowOf(resolver)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        val format = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.PNG
        }
        bitmap.compress(format, 90, baos)
        val bytes = baos.toByteArray()
        bitmap.recycle()

        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        shadowResolver.setCursor(
            uri,
            object : BaseCursor() {
                override fun moveToFirst() = true
                override fun getColumnIndex(columnName: String) =
                    if (columnName == OpenableColumns.DISPLAY_NAME) 0 else -1
                override fun getString(columnIndex: Int) = "test_image.${mimeType.substringAfter('/')}"
                override fun close() = Unit
            },
        )
        return uri
    }

    private fun assistantEntry(
        key: String = "msg_e2e",
        text: String = "",
        streaming: Boolean = false,
        reasoning: List<MessageReasoning> = emptyList(),
        attachments: List<TimelineAttachment> = emptyList(),
    ) = TimelineEntry(
        key = key,
        sender = "assistant",
        text = text,
        isUser = false,
        timestamp = System.currentTimeMillis(),
        pendingAcceptance = false,
        batchGroupId = null,
        attachments = attachments,
        isStreaming = streaming,
        reasoning = reasoning,
    )

    // ==========================================
    // Tier 3: 跨特性成对组合测试 (8组 Combo)
    // ==========================================

    @Test
    fun t3_Combo_01_Heterogeneous_Order_With_General_Tools() {
        // Combo 1 (F1 + F3): 异构时序流中穿插通用工具调用与 Markdown 文本
        // 验证思考 -> 通用工具(read_file) -> 叙述文本 -> 通用工具(bash) 严格线性排列且无大空行
        val text = """
            <tool_call name="read_file">
              <command>{"path": "build.gradle.kts"}</command>
              <result status="success">plugins { id("com.android.application") }</result>
            </tool_call>
            文件读取完毕，接下来运行校验。
            <tool_call name="bash">
              <command>./gradlew test</command>
              <result status="success">BUILD SUCCESSFUL</result>
            </tool_call>
            所有测试均已成功通过！
        """.trimIndent()
        val reasoning = listOf(MessageReasoning("r1", "准备读取工程配置并执行校验", 0, true))
        val entry = assistantEntry(text = text, reasoning = reasoning)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        // 验证回合结束后折叠为「2 次工具调用」大卡，点击展开后保持异构步骤严格线性顺序，且不出现旧版文案与空气泡
        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()

        val summaryTop = compose.onNodeWithText("2 次工具调用").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTopCollapsed = compose.onNodeWithText("所有测试均已成功通过！").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("折叠大卡必须在最终回答之上", summaryTop < finalTopCollapsed)

        compose.onNodeWithText("2 次工具调用").performClick()
        val thoughtTop = compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val readTop = compose.onNodeWithText("[read_file]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val narrativeTop = compose.onNodeWithText("文件读取完毕，接下来运行校验。").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val bashTop = compose.onNodeWithText("[bash]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTop = compose.onNodeWithText("所有测试均已成功通过！").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue("思考卡必须在 [read_file] 之上", thoughtTop < readTop)
        assertTrue("[read_file] 必须在中间叙述之上", readTop < narrativeTop)
        assertTrue("中间叙述必须在 [bash] 之上", narrativeTop < bashTop)
        assertTrue("[bash] 必须在最终回答之上", bashTop < finalTop)
    }

    @Test
    fun t3_Combo_02_Air_Bubble_And_Prompt_Sanitization() {
        // Combo 2 (F2 + F6): 消息仅包含系统提示词与时区，三层脱敏剥离后变为空
        // 验证：真实 SystemPromptSanitizer 清洗能力、空消息拦截判定、UI 绝无兜底空气泡
        val systemPromptOnly = """
            <system-reminder>Browser time zone for this request: Asia/Shanghai</system-reminder>
            Browser time zone for this request: Asia/Shanghai
        """.trimIndent()

        // 1. 真实脱敏清洗验证
        val sanitizedText = SystemPromptSanitizer.sanitize(systemPromptOnly)
        assertTrue("系统提示词及标签必须被完全剥离清洗为空", sanitizedText.isBlank())

        // 2. 真实空消息丢弃判定验证
        val shouldDrop = SystemPromptSanitizer.shouldDropMessage(
            text = sanitizedText,
            hasAttachments = false,
            hasSteps = false,
            hasReasoning = false,
            isMeta = false,
        )
        assertTrue("纯系统提示词脱敏后为空且无附件/步骤/思考的消息，必须判定为丢弃不上屏", shouldDrop)

        // 3. UI 渲染出口兜底防线校验（通过真实 MessageTimeline 宿主）
        val entry = assistantEntry(text = sanitizedText)
        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
        compose.onNodeWithText("Browser time zone", substring = true).assertDoesNotExist()
        compose.onNodeWithText("system-reminder", substring = true).assertDoesNotExist()

        // 4. 控制器 UI 出口规范化校验：isMeta == true 的消息在 renderTimeline 中直接丢弃不上屏
        val metaGateway = FakeWorkbenchGateway(
            timelineMessages = listOf(
                TimelineMessage(
                    id = "meta_msg_1",
                    sender = "user",
                    parts = listOf(MessagePart.Text("隐藏元数据指令")),
                    timestamp = 100L,
                    isMeta = true,
                ),
                TimelineMessage(
                    id = "visible_msg_1",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("真实可见回复")),
                    timestamp = 200L,
                    isMeta = false,
                ),
            ),
        )
        val controller = createWorkbenchController(coroutineScope, metaGateway)
        controller.openThread("session_meta_test")
        compose.waitUntil(3_000) { controller.state.value.timeline is Loadable.Ready }
        val rendered = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("isMeta == true 的消息必须被控制器丢弃不上屏", listOf("visible_msg_1"), rendered.map { it.key })
        controller.cancel()
    }

    @Test
    fun t3_Combo_03_Streaming_Thought_With_Tool_Call() {
        // Combo 3 (F3 + F5): 思维链流式输出进行中，模型同时产生工具调用事件
        // 通过真实 MessageTimeline 宿主验证思考卡流式状态与工具卡就绪展示，两卡片按序平铺且不合并。
        val toolXml = """
            <tool_call name="search_db">
              <command>SELECT * FROM users</command>
              <result status="success">2 records found</result>
            </tool_call>
        """.trimIndent()
        val reasoning = listOf(MessageReasoning("r1", "正在分析数据库表结构...", 0, false))
        val entry = assistantEntry(text = toolXml, streaming = true, reasoning = reasoning)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.RUNNING)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        val thoughtTop = compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val toolTop = compose.onNodeWithText("[search_db]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("流式思考卡片必须在工具卡片之上", thoughtTop < toolTop)
    }

    @Test
    fun t3_Combo_04_Voice_Completed_Cancels_Live_Notif_Resets_Lock() {
        // Combo 4 (F4 + F7): 会话发送语音消息后，进入 RUNNING 发布常驻通知；
        // 收到最终回复完成 (COMPLETED)，常驻通知取消，弹出可滑动清除的完成通知，麦克风恢复就绪
        val gateway = FakeWorkbenchGateway()
        val controller = createWorkbenchController(coroutineScope, gateway)
        controller.openThread("session_1")
        compose.waitForIdle()

        val target = ReplyNotificationTarget("profile_e2e", "account_e2e", "session_1")

        // 1. 发送语音后进入 RUNNING 状态：发布真实常驻进度通知，麦克风锁定
        gateway.generationId.value = "gen_turn_1"
        gateway.generationState.value = GenerationState.RUNNING
        compose.waitForIdle()
        assertFalse("任务进行中麦克风门禁处于锁定状态", controller.canSendVoiceAttachment("session_1"))

        val runningSession = NativeConversationSession(
            target = target,
            generationId = "gen_turn_1",
            title = "语音问答会话",
            status = NativeConversationStatus.RUNNING,
            latestReply = "语音识别完成，助手处理中...",
            updatedAt = System.currentTimeMillis(),
        )
        publisher.update(runningSession, alert = false)

        val runningNotifs = shadowNotificationManager.allNotifications
        assertEquals("运行中必须仅存在一条通知", 1, runningNotifs.size)
        val ongoingNotif = runningNotifs.single()
        assertEquals(REPLY_CHANNEL_ID, ongoingNotif.channelId)
        assertTrue("运行态通知必须是常驻 ongoing 状态 (FLAG_ONGOING_EVENT)", ongoingNotif.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse("运行态通知绝不可自动清除", ongoingNotif.flags and Notification.FLAG_AUTO_CANCEL != 0)

        // 2. Turn 完成转移到 COMPLETED：常驻通知取消，弹出可滑动清除的完成通知
        val completedSession = runningSession.copy(
            status = NativeConversationStatus.COMPLETED,
            latestReply = "这是给您的语音回复最终答案",
            updatedAt = System.currentTimeMillis(),
        )
        publisher.update(completedSession, alert = true)

        val finishNotifs = shadowNotificationManager.allNotifications
        assertEquals("完成态必须保留一条终态通知", 1, finishNotifs.size)
        val finishNotif = finishNotifs.single()
        assertEquals(REPLY_CHANNEL_ID, finishNotif.channelId)
        assertEquals("终态通知绝不能标记为 ongoing", 0, finishNotif.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue("终态通知必须设置 autoCancel 可滑动清除", finishNotif.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val style = Notification.Builder.recoverBuilder(context, finishNotif).style as? Notification.MessagingStyle
        val finishTitle = style?.conversationTitle?.toString() ?: finishNotif.extras.getString(Notification.EXTRA_TITLE)
        assertEquals("完成通知必须显示真实会话标题", "语音问答会话", finishTitle)
        assertFalse("完成通知标题绝不包含编码 ID", finishTitle?.contains("session_1") == true)

        // 3. 服务端进入终态：注意 generationId 残留，验证 R7 修复
        gateway.generationState.value = GenerationState.COMPLETED
        compose.waitForIdle()

        // 4. 真实断言：麦克风在终态原子复位解锁
        assertTrue("终态通知触发时麦克风原子复位解锁（R7 修复保证）", controller.canSendVoiceAttachment("session_1"))
        assertEquals(GenerationState.COMPLETED, controller.state.value.generation)
        controller.cancel()
    }

    @Test
    fun t3_Combo_05_Streaming_Interrupted_ByUser() {
        // Combo 5 (F4 + F5 + F7): 思维链流式刷字中途用户主动点击打断 (CANCELLED)
        // 验证：UI 呈现中断标记、常驻进度通知取消转为非 ongoing 打断提醒，麦克风与发送锁恢复可用
        val gateway = FakeWorkbenchGateway()
        val controller = createWorkbenchController(coroutineScope, gateway)
        controller.openThread("session_1")
        compose.waitForIdle()

        val target = ReplyNotificationTarget("profile_e2e", "account_e2e", "conv_interrupt_1")

        // 1. 运行态：流式输出中，麦克风锁定，发布常驻通知
        gateway.generationId.value = "gen_stream_1"
        gateway.generationState.value = GenerationState.RUNNING
        compose.waitForIdle()
        assertFalse("思维链流式刷字进行中麦克风处于锁定状态", controller.canSendVoiceAttachment("session_1"))

        val runningSession = NativeConversationSession(
            target = target,
            generationId = "gen_stream_1",
            title = "中断测试会话",
            status = NativeConversationStatus.RUNNING,
            latestReply = "推导中...",
            updatedAt = System.currentTimeMillis(),
        )
        publisher.update(runningSession, alert = false)
        assertTrue(shadowNotificationManager.allNotifications.single().flags and Notification.FLAG_ONGOING_EVENT != 0)

        // 2. 界面展示思维链打断卡片
        val reasoning = listOf(MessageReasoning("r1", "推导步骤尚未完成", 0, false))
        val entry = assistantEntry(text = "", streaming = false, reasoning = reasoning)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.CANCELLED)
            }
        }
        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("思考已中断", substring = true).assertIsDisplayed()

        // 3. 用户打断状态机跃迁：CANCEL_REQUESTED -> CANCELLED
        gateway.generationState.value = GenerationState.CANCEL_REQUESTED
        compose.waitForIdle()
        assertFalse("打断请求在途中麦克风保持闭合", controller.canSendVoiceAttachment("session_1"))

        gateway.generationState.value = GenerationState.CANCELLED
        compose.waitForIdle()

        // 打断进入 CANCELLED 态通知更新
        val cancelledSession = runningSession.copy(
            status = NativeConversationStatus.CANCELLED,
            latestReply = "任务已被用户取消",
            updatedAt = System.currentTimeMillis(),
        )
        publisher.update(cancelledSession, alert = true)

        val notif = shadowNotificationManager.allNotifications.single()
        assertEquals("打断后通知绝不能标记为 ongoing", 0, notif.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue("打断后通知必须具备 autoCancel", notif.flags and Notification.FLAG_AUTO_CANCEL != 0)

        // 4. 打断终态验证：麦克风与发送锁立即恢复就绪
        assertTrue("打断后麦克风及发送锁立即复位恢复就绪", controller.canSendVoiceAttachment("session_1"))
        assertEquals(GenerationState.CANCELLED, controller.state.value.generation)
        controller.cancel()
    }

    @Test
    fun t3_Combo_06_Multimodal_Image_And_Tool_Analysis() {
        // Combo 6 (F1 + F8): 多模态会话中发送图片并调用分析工具
        // 1. 注册 2560x1440 高分辨率真实虚拟图片
        val imageUri = registerTestImage("content://test.picker/diagram_large", 2560, 1440, "image/png")

        // 2. 调用生产组件真实压缩：采样下采样、缩放至 1920px、WebP 编码
        val (compressedBytes, mimeType) = ContentResolverExtensions.compressImageUri(context.contentResolver, imageUri)

        // 3. 真实压缩断言：严格保证 WebP 格式且体积 <= 1MB
        assertEquals("image/webp", mimeType)
        assertTrue("压缩输出流不得为空", compressedBytes.isNotEmpty())
        assertTrue("图片体积必须压缩至 1MB 以内", compressedBytes.size <= 1024 * 1024)
        val decoded = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
        assertNotNull("压缩产物必须可重新解码", decoded)
        assertTrue("长边必须等比缩放至 1920px 以内 (当前: ${maxOf(decoded.width, decoded.height)})", maxOf(decoded.width, decoded.height) <= 1920)
        decoded.recycle()

        // 4. 将真实的压缩元数据接入会话条目附件
        val attachment = TimelineAttachment(
            draftId = "att_img_combo",
            filename = "diagram.webp",
            mediaType = mimeType,
        )
        val toolXml = """
            <tool_call name="ocr_image">
              <command>{"attachment": "diagram.webp"}</command>
              <result status="success">Text extracted: System Architecture</result>
            </tool_call>
            图片解析结果如上。
        """.trimIndent()
        val entry = assistantEntry(text = toolXml, attachments = listOf(attachment))

        // 5. Compose UI 渲染断言（通过真实 MessageTimeline 宿主）
        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1 次工具调用").assertIsDisplayed().performClick()
        compose.onNodeWithText("[ocr_image]").assertIsDisplayed()
        compose.onNodeWithText("图片解析结果如上。").assertIsDisplayed()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t3_Combo_07_System_Prompt_Filtered_With_Title_Update() {
        // Combo 7 (F2 + F4 + F6): 事件流夹带系统提示词与通知标题更新
        // 验证：真实提示词脱敏、空消息丢弃防空气泡、通知标题更新及未提供标题时的回退逻辑

        // 1. 系统提示词脱敏与丢弃判定 (F6 + F2)
        val rawEventText = """
            Instructions from: ~/.dsh/AGENTS.md
            - Rule A: Decoy
            - Rule B: Chinese
            
            <system-reminder>System prompt secret</system-reminder>
        """.trimIndent()
        val filteredText = SystemPromptSanitizer.sanitize(rawEventText)
        assertTrue("提示词块必须被完全清洗为空", filteredText.isBlank())
        assertTrue(
            "脱敏后为空且无附件/步骤的消息必须丢弃不上屏",
            SystemPromptSanitizer.shouldDropMessage(filteredText)
        )

        val entry = assistantEntry(text = filteredText)
        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
        compose.onNodeWithText("AGENTS.md", substring = true).assertDoesNotExist()
        compose.onNodeWithText("system-reminder", substring = true).assertDoesNotExist()

        // 2. 真实通知标题更新与回退测试 (F4)
        val target = ReplyNotificationTarget("profile_e2e", "account_e2e", "conv_opaque_b64_987654321")
        val defaultLocalizedTitle = context.getString(UiR.string.notif_conv_default_title)

        // 场景 A: 标题未就绪 (title 为空)，必须回退为本地化默认标题，绝不泄露编码 ID 或截断 Base64
        val sessionWithoutTitle = NativeConversationSession(
            target = target,
            generationId = "gen_c7_1",
            title = "",
            status = NativeConversationStatus.RUNNING,
            latestReply = "处理中...",
            updatedAt = System.currentTimeMillis()
        )
        publisher.update(sessionWithoutTitle, alert = false)

        val notifA = shadowNotificationManager.allNotifications.single()
        val styleA = Notification.Builder.recoverBuilder(context, notifA).style as? Notification.MessagingStyle
        val titleA = styleA?.conversationTitle?.toString() ?: notifA.extras.getString(Notification.EXTRA_TITLE)
        assertEquals("未提供标题时回退到默认本地化标题", defaultLocalizedTitle, titleA)
        assertFalse("回退标题绝不可包含会话编码 ID", titleA?.contains("987654321") == true)
        assertFalse("回退标题绝不可使用旧版截断前缀'会话 '", titleA?.startsWith("会话 ") == true)

        // 场景 B: 收到真实标题更新（"修复多模态图片发送"）并转为终态 COMPLETED
        val realUpdatedTitle = "修复多模态图片发送"
        val sessionWithUpdatedTitle = sessionWithoutTitle.copy(
            title = realUpdatedTitle,
            status = NativeConversationStatus.COMPLETED,
            latestReply = "任务处理完毕",
            updatedAt = System.currentTimeMillis()
        )
        publisher.update(sessionWithUpdatedTitle, alert = true)

        val notifB = shadowNotificationManager.allNotifications.single()
        assertEquals("终态常驻标记必须清除", 0, notifB.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue("终态必须支持滑动清除", notifB.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val styleB = Notification.Builder.recoverBuilder(context, notifB).style as? Notification.MessagingStyle
        val titleB = styleB?.conversationTitle?.toString() ?: notifB.extras.getString(Notification.EXTRA_TITLE)
        assertEquals("更新后通知必须展示真实会话标题", realUpdatedTitle, titleB)
        assertFalse("真实标题绝不可包含编码 ID", titleB?.contains("987654321") == true)
        assertFalse("真实标题绝不以旧版'会话 '开头", titleB?.startsWith("会话 ") == true)
    }

    @Test
    fun t3_Combo_08_Voice_Stream_Queue_To_Finish_Flow() {
        // Combo 8 (F5 + F7): 录音发送排队 -> 流式刷字 -> 结束恢复
        val gateway = FakeWorkbenchGateway()
        val controller = createWorkbenchController(coroutineScope, gateway)
        controller.openThread("session_1")
        compose.waitForIdle()

        // 1. 排队态：麦克风处于禁用锁定状态
        gateway.generationState.value = GenerationState.QUEUED
        compose.waitForIdle()
        assertFalse("排队态麦克风必须处于禁用锁定状态", controller.canSendVoiceAttachment("session_1"))
        assertEquals(GenerationState.QUEUED, controller.state.value.generation)

        // 2. 流式态：思考与正文逐 Token 刷新，麦克风保持禁用
        gateway.generationState.value = GenerationState.RUNNING
        compose.waitForIdle()
        val streamState = mutableStateOf("正在组织语言...")
        compose.setContent {
            MaterialTheme {
                MessageTimeline(
                    entries = listOf(assistantEntry(text = streamState.value, streaming = true)),
                    generation = GenerationState.RUNNING,
                )
            }
        }
        compose.onNodeWithText("正在组织语言...").assertIsDisplayed()
        assertFalse("流式刷字进行中麦克风保持禁用锁定", controller.canSendVoiceAttachment("session_1"))

        // 3. 完成态：麦克风重置使能
        gateway.generationState.value = GenerationState.COMPLETED
        compose.waitForIdle()
        assertTrue("生成完成后麦克风门禁重置使能", controller.canSendVoiceAttachment("session_1"))
        assertEquals(GenerationState.COMPLETED, controller.state.value.generation)
        controller.cancel()
    }

    // ==========================================
    // Tier 4: 真实实机交互端到端工作负载 (5个完整 Turn 场景)
    // ==========================================

    @Test
    fun t4_Scenario_01_Full_Turn_Voice_Conversation() {
        // Scenario 1: 用户语音输入 -> 思考刷字 -> 工具调用 -> 回答完成 -> 通知刷新 -> 麦克风重置
        val gateway = FakeWorkbenchGateway()
        val controller = createWorkbenchController(coroutineScope, gateway)
        controller.openThread("session_1")
        compose.waitForIdle()

        // 1. 发送前检查麦克风就绪
        assertTrue("会话开启初始麦克风就绪可用", controller.canSendVoiceAttachment("session_1"))

        val target = ReplyNotificationTarget("profile_e2e", "account_e2e", "conv_voice_turn_1")

        // 2. 用户发送语音，常驻通知挂起 (真实通知组件)
        gateway.generationId.value = "gen_turn_1"
        gateway.generationState.value = GenerationState.RUNNING
        compose.waitForIdle()
        assertFalse("语音发送及执行中麦克风门禁闭合锁定", controller.canSendVoiceAttachment("session_1"))

        val runningSession = NativeConversationSession(
            target = target,
            generationId = "gen_turn_1",
            title = "系统控制会话",
            status = NativeConversationStatus.RUNNING,
            latestReply = "正在处理语音指令...",
            updatedAt = System.currentTimeMillis(),
        )
        publisher.update(runningSession, alert = false)

        val runningNotifs = shadowNotificationManager.allNotifications
        assertEquals(1, runningNotifs.size)
        val ongoingNotif = runningNotifs.single()
        assertEquals(REPLY_CHANNEL_ID, ongoingNotif.channelId)
        assertTrue("任务执行中必须保持 ongoing 常驻通知", ongoingNotif.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(ongoingNotif.flags and Notification.FLAG_AUTO_CANCEL != 0)

        // 3. 服务端返回流式思考与工具调用与最终回复
        val text = """
            <tool_call name="device_volume">
              <command>set_volume 80</command>
              <result status="success">Volume updated to 80%</result>
            </tool_call>
            音量已成功调至 80%。
        """.trimIndent()
        val reasoning = listOf(MessageReasoning("r1", "分析用户调音意图", 0, true))
        val entry = assistantEntry(text = text, streaming = false, reasoning = reasoning)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        // 4. UI 验证
        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1 次工具调用").assertIsDisplayed().performClick()
        compose.onNodeWithText("[device_volume]").assertIsDisplayed()
        compose.onNodeWithText("音量已成功调至 80%。").assertIsDisplayed()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()

        // 5. 任务完成，常驻通知取消并弹出可滑动清除的完成通知
        val completedSession = runningSession.copy(
            status = NativeConversationStatus.COMPLETED,
            latestReply = "音量已成功调至 80%。",
            updatedAt = System.currentTimeMillis(),
        )
        publisher.update(completedSession, alert = true)

        val finalNotifs = shadowNotificationManager.allNotifications
        assertEquals(1, finalNotifs.size)
        val finalNotif = finalNotifs.single()
        assertEquals(REPLY_CHANNEL_ID, finalNotif.channelId)
        assertEquals("任务完成时常驻通知必须被取消，不再是 ongoing", 0, finalNotif.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue("任务完成通知必须具备 autoCancel 可滑动清除", finalNotif.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val style = Notification.Builder.recoverBuilder(context, finalNotif).style as? Notification.MessagingStyle
        val notifTitle = style?.conversationTitle?.toString() ?: finalNotif.extras.getString(Notification.EXTRA_TITLE)
        assertEquals("完成通知必须显示真实会话标题", "系统控制会话", notifTitle)
        assertFalse("通知标题绝不可包含会话 ID", notifTitle?.contains("conv_voice_turn_1") == true)

        // 6. 麦克风恢复：验证真实状态机门禁，即使 generationId 残留也允许下一次录音 (R7 核心断言)
        gateway.generationState.value = GenerationState.COMPLETED
        compose.waitForIdle()

        assertTrue("Turn 结束后麦克风恢复就绪，可录制并发送下一轮语音", controller.canSendVoiceAttachment("session_1"))
        assertEquals(GenerationState.COMPLETED, controller.state.value.generation)
        controller.cancel()
    }

    @Test
    fun t4_Scenario_02_Multimodal_Image_Consultation() {
        // Scenario 2 (R8 E2E): 高清照片下采样 WebP 压缩，标准多模态图片块装配与发送，无 Gateway v2 桩错误
        // 1. 注册 3840x2160 (4K) 高清照片
        val photoUri = registerTestImage("content://test.picker/camera_high_res", 3840, 2160, "image/jpeg")

        // 2. 直接调用生产选图解析入口 ContentResolverExtensions.resolveAttachment（不绕过选图管线）
        val selection = ContentResolverExtensions.resolveAttachment(context.contentResolver, photoUri)
        assertEquals("选图解析后的媒体类型必须对齐为 image/webp", "image/webp", selection.mediaType)
        val compressedBytes = selection.contentSource.openStream().use { it.readBytes() }
        assertTrue("压缩产物不得为空", compressedBytes.isNotEmpty())
        assertTrue("图片体积控制在 1MB 内防超帧", compressedBytes.size <= 1024 * 1024)
        assertTrue("图片体积严格小于 4MB WebRTC 限制", compressedBytes.size < 4 * 1024 * 1024)
        val decoded = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
        assertNotNull("压缩产物必须可重新解码", decoded)
        assertTrue("4K 图片缩放后长边不得超过 1920px", maxOf(decoded.width, decoded.height) <= 1920)
        decoded.recycle()

        // 3. 直接将 resolveAttachment 产出的 selection 传入 RemoteAttachmentCoordinator 暂存与装配
        val channel = E2ERpcChannel(capabilities = emptySet()) // 不声明 workspace.upload.v1
        val carrier = E2ESessionCarrier(backend = "dsh", channel = channel)
        val staging = E2EAttachmentStaging()
        val recovery = E2EAttachmentRecovery()
        val coordinator = RemoteAttachmentCoordinator(carrier, CoroutineScope(Dispatchers.Unconfined), staging, recovery)

        val draft = runBlocking { coordinator.prepare(selection) }
        val verifiedState = runBlocking { coordinator.observe(draft.id.value).first { it.state == AttachmentState.VERIFIED } }
        assertEquals(AttachmentState.VERIFIED, verifiedState.state)

        // 4. 调用 promptContent 获取多模态图片内容块
        val promptBlocks = runBlocking { coordinator.promptContent("session_e2e_photo", draft.id.value) }

        // 5. 验证解耦与标准多模态协议 JSON 契约
        assertTrue("多模态图片不得触发旧版 workspace.upload.* 远端接口", channel.calls.isEmpty())
        assertEquals(1, promptBlocks.size)
        val block = promptBlocks.single() as? JsonValue.JObject
        assertNotNull(block)
        assertEquals("image", JsonFields.string(block, "type"))
        assertEquals("image/webp", JsonFields.string(block, "mimeType"))
        assertEquals("image/webp", JsonFields.string(block, "mediaType"))

        val expectedBase64 = Base64.getEncoder().encodeToString(compressedBytes)
        assertEquals("顶层 data 必须匹配真实压缩字节的 Base64 编码", expectedBase64, JsonFields.string(block, "data"))

        val source = JsonFields.obj(JsonFields.field(block, "source"))
        assertNotNull("必须包含符合标准多模态协议的 source 嵌套对象", source)
        assertEquals("base64", JsonFields.string(source, "type"))
        assertEquals("image/webp", JsonFields.string(source, "media_type"))
        assertEquals("source.data 必须匹配真实压缩字节的 Base64 编码", expectedBase64, JsonFields.string(source, "data"))

        // 6. 会话回复 UI 呈现闭环（通过真实 MessageTimeline 宿主）
        val replyText = "已成功接收并分析该高清架构图，识别到三层解耦服务架构。"
        val entry = assistantEntry(
            text = replyText,
            attachments = listOf(TimelineAttachment(draft.id.value, selection.filename, selection.mediaType)),
        )
        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }
        compose.onNodeWithText(replyText).assertIsDisplayed()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t4_Scenario_03_Multi_Step_Tool_Calls_Without_Narrative() {
        // Scenario 3: 思考中连续调用两次工具，中间无任何正文输出
        // 通过真实 MessageTimeline 宿主验证仅顺序渲染两张卡片，无集中折叠框与空气泡
        val doubleToolText = """
            <tool_call name="search_file">
              <command>find . -name "*.kt"</command>
              <result status="success">5 files found</result>
            </tool_call>
            <tool_call name="grep_code">
              <command>grep "fun main" *.kt</command>
              <result status="success">Main.kt: fun main()</result>
            </tool_call>
        """.trimIndent()
        val entry = assistantEntry(text = doubleToolText)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()

        val firstTop = compose.onNodeWithText("[search_file]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("[grep_code]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("两次工具调用必须按顺序纵向排列", firstTop < secondTop)
    }

    @Test
    fun t4_Scenario_04_Unclosed_System_Prompt_Streaming_Sanitization() {
        // Scenario 4: 流式输出中夹带未闭合系统指令，剥离后纯净正文流畅刷出
        val streamedWithSystem = "正在查询数据... <system-reminder>Unfinished system injection block"

        // 1. 调用真实 SystemPromptSanitizer 验证未闭合流式片段截断能力
        val sanitized = SystemPromptSanitizer.sanitize(streamedWithSystem)
        assertEquals("正在查询数据...", sanitized)
        assertFalse("未闭合标签内部内容必须被完全剔除", sanitized.contains("Unfinished system injection block"))
        assertFalse("标签名本身不可残留", sanitized.contains("<system-reminder>"))

        // 2. 验证非空正文不丢弃
        assertFalse(
            "包含有效正文的消息绝不能被判定为丢弃",
            SystemPromptSanitizer.shouldDropMessage(text = sanitized, hasAttachments = false, hasSteps = false, hasReasoning = false, isMeta = false)
        )

        // 3. UI 呈现纯净正文，无提示词无空气泡（通过真实 MessageTimeline 宿主）
        val entry = assistantEntry(text = sanitized, streaming = true)
        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.RUNNING)
            }
        }

        compose.onNodeWithText("正在查询数据...").assertIsDisplayed()
        compose.onNodeWithText("Unfinished system injection block").assertDoesNotExist()
        compose.onNodeWithText("<system-reminder>", substring = true).assertDoesNotExist()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t4_Scenario_05_Exception_And_Interrupt_Recovery() {
        // Scenario 5: 语音发送或流式生成中异常断开或打断，硬件释放、发送锁解开、通知取消
        val gateway = FakeWorkbenchGateway()
        val controller = createWorkbenchController(coroutineScope, gateway)
        controller.openThread("session_1")
        compose.waitForIdle()

        val target = ReplyNotificationTarget("profile_e2e", "account_e2e", "conv_err_1")

        // 1. 运行态：发送锁激活、麦克风锁定，挂起常驻通知
        gateway.generationId.value = "gen_err_1"
        gateway.generationState.value = GenerationState.RUNNING
        compose.waitForIdle()
        assertFalse("运行中麦克风处于锁定状态", controller.canSendVoiceAttachment("session_1"))

        val runningSession = NativeConversationSession(
            target = target,
            generationId = "gen_err_1",
            title = "故障恢复测试会话",
            status = NativeConversationStatus.RUNNING,
            latestReply = "任务执行中...",
            updatedAt = System.currentTimeMillis()
        )
        publisher.update(runningSession, alert = false)
        val runningNotif = shadowNotificationManager.allNotifications.single()
        assertTrue("运行态必须是常驻通知", runningNotif.flags and Notification.FLAG_ONGOING_EVENT != 0)

        // 2. 发生异常转为 FAILED：常驻通知取消，弹出非 ongoing、autoCancel 的失败通知
        val failedSession = runningSession.copy(
            status = NativeConversationStatus.FAILED,
            latestReply = "连接断开，任务执行失败",
            updatedAt = System.currentTimeMillis(),
        )
        publisher.update(failedSession, alert = true)

        val activeNotifs = shadowNotificationManager.allNotifications
        assertEquals(1, activeNotifs.size)
        val failedNotif = activeNotifs.single()
        assertEquals(REPLY_CHANNEL_ID, failedNotif.channelId)
        assertEquals("失败终态常驻通知被取消，FLAG_ONGOING_EVENT 必须为 0", 0, failedNotif.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue("失败通知必须设置 autoCancel 可滑动清除", failedNotif.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val style = Notification.Builder.recoverBuilder(context, failedNotif).style as? Notification.MessagingStyle
        val failedTitle = style?.conversationTitle?.toString() ?: failedNotif.extras.getString(Notification.EXTRA_TITLE)
        assertEquals("失败通知必须保留真实会话标题", "故障恢复测试会话", failedTitle)

        gateway.generationState.value = GenerationState.FAILED
        compose.waitForIdle()

        // 3. 真实发送锁与麦克风断言：状态正确，麦克风门禁自动恢复就绪
        assertEquals(GenerationState.FAILED, controller.state.value.generation)
        assertTrue("异常终止后发送锁解除，麦克风门禁自动恢复就绪", controller.canSendVoiceAttachment("session_1"))

        // 4. 模拟打断恢复验证 (CANCELLED)
        gateway.generationState.value = GenerationState.CANCELLED
        compose.waitForIdle()
        assertEquals(GenerationState.CANCELLED, controller.state.value.generation)
        assertTrue("打断恢复后麦克风门禁保持可用", controller.canSendVoiceAttachment("session_1"))
        controller.cancel()
    }
}
