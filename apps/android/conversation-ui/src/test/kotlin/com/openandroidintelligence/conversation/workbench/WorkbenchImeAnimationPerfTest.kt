package com.openandroidintelligence.conversation.workbench

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentCommand
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
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.sanitizer.ContextInjectionFilter
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchImeAnimationPerfTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        scope.cancel()
    }

    @Test
    fun `多工作区场景下IME逐帧上推保持贴底可见且无性能退化`() {
        val controller = buildControllerWithHistory()
        val workspaceMenu = buildMultiWorkspaceMenu(workspaceCount = 5, threadsPerWorkspace = 40)
        var hostView: View? = null
        val counts = mutableMapOf<String, Int>()
        fun count(tag: String): Int = counts[tag] ?: 0
        fun resetCounts() = counts.clear()

        compose.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(
                LocalWorkbenchCompositionObserver provides { tag -> counts[tag] = (counts[tag] ?: 0) + 1 },
            ) {
                MaterialTheme {
                    Box(Modifier.size(411.dp, 891.dp)) {
                        WorkbenchScreen(
                            controller = controller,
                            gatewayLabel = "https://remote.example",
                            onOpenSettings = {},
                            onPickCamera = {},
                            onPickGallery = {},
                            onPickDocument = {},
                            onVoiceInput = {},
                            onLogout = {},
                            workspaceMenu = workspaceMenu,
                        )
                    }
                }
            }
        }

        compose.runOnIdle { controller.openThread("thread-active") }
        compose.waitForIdle()
        compose.onNodeWithText("最新消息结尾标记", substring = true).assertIsDisplayed()
        assertTrue("首次挂载应记录 workbench 组合", count("workbench") >= 1)
        assertTrue("首次挂载应记录 workspace_browser 组合", count("workspace_browser") >= 1)

        val view = requireNotNull(hostView)
        val inputNode = compose.onNodeWithText("输入消息或 / 命令...")
        val initialTop = inputNode.fetchSemanticsNode().boundsInRoot.top

        // 1) 点击输入框获焦：描边动画在绘制层执行，最多因 isFocused 翻转组合 1 次 composer_input
        resetCounts()
        inputNode.performClick()
        compose.waitForIdle()
        val focusWorkbench = count("workbench")
        val focusWsBrowser = count("workspace_browser")
        val focusComposerInput = count("composer_input")

        // 2) IME 20 帧上推：WorkbenchScreen、关闭态 WorkspaceConversationBrowser 与 ComposerInputField 均为 0 次重组
        resetCounts()
        val steps = 20
        val startNs = System.nanoTime()
        for (frame in 1..steps) {
            val imeBottomPx = frame * 40
            compose.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(view, insets)
            }
            compose.waitForIdle()
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val imeWorkbench = count("workbench")
        val imeWsBrowser = count("workspace_browser")
        val imeComposerInput = count("composer_input")
        val liftedTop = inputNode.fetchSemanticsNode().boundsInRoot.top

        // 3) 阳性对照 + 打字隔离：修改草稿会触发 workbench 重组，但关闭态 workspace_browser 仍应跳过（0 次）
        resetCounts()
        compose.runOnIdle { controller.editDraft("验证探针阳性对照") }
        compose.waitForIdle()
        val draftWorkbench = count("workbench")
        val draftWsBrowser = count("workspace_browser")

        println(
            "[BENCH-IME-FIXED] initialTop=$initialTop liftedTop=$liftedTop " +
                "focus(wb=$focusWorkbench,ws=$focusWsBrowser,input=$focusComposerInput) " +
                "ime20(wb=$imeWorkbench,ws=$imeWsBrowser,input=$imeComposerInput) " +
                "draftControl(wb=$draftWorkbench,ws=$draftWsBrowser) " +
                "totalMs=${"%.2f".format(elapsedMs)} perFrameMs=${"%.2f".format(elapsedMs / steps)}"
        )

        assertTrue("输入框应随 IME 上推：initialTop=$initialTop liftedTop=$liftedTop", liftedTop < initialTop - 500f)
        // 停留在底部时，键盘推起后最新消息尾部仍应保持可见
        compose.onNodeWithText("最新消息结尾标记", substring = true).assertIsDisplayed()
        assertEquals("点击输入框获焦不应触发 WorkbenchScreen 重组", 0, focusWorkbench)
        assertEquals("点击输入框获焦不应触发关闭态工作区浏览器重组", 0, focusWsBrowser)
        assertTrue(
            "聚焦描边动画下沉绘制层后 composer_input 最多因聚焦态翻转重组 1 次（当前 $focusComposerInput）",
            focusComposerInput <= 1,
        )
        assertEquals("IME 20 帧上推期间 WorkbenchScreen 不应重组", 0, imeWorkbench)
        assertEquals("IME 20 帧上推期间关闭态工作区浏览器不应重组", 0, imeWsBrowser)
        assertEquals("IME 20 帧上推期间输入框内容不应重组", 0, imeComposerInput)
        assertTrue("阳性对照应观测到草稿变更触发 WorkbenchScreen 重组", draftWorkbench >= 1)
        assertEquals("打字触发草稿变更时关闭态工作区浏览器仍应跳过重组", 0, draftWsBrowser)
    }

    @Test
    fun `上滑查看历史消息期间唤起键盘不会强行拉回底部`() {
        val controller = buildControllerWithHistory()
        var hostView: View? = null

        compose.setContent {
            hostView = LocalView.current
            MaterialTheme {
                Box(Modifier.size(411.dp, 891.dp)) {
                    WorkbenchScreen(
                        controller = controller,
                        gatewayLabel = "https://remote.example",
                        onOpenSettings = {},
                        onPickCamera = {},
                        onPickGallery = {},
                        onPickDocument = {},
                        onVoiceInput = {},
                        onLogout = {},
                        workspaceMenu = buildMultiWorkspaceMenu(workspaceCount = 3, threadsPerWorkspace = 10),
                    )
                }
            }
        }

        compose.runOnIdle { controller.openThread("thread-active") }
        compose.waitForIdle()

        val timeline = compose.onNode(
            hasScrollToIndexAction() and !hasTestTag("workspace-conversation-browser") and !hasTestTag("conversation-browser"),
        )
        timeline.performScrollToIndex(1)
        timeline.performTouchInput { swipeUp(durationMillis = 300) }
        compose.waitForIdle()
        compose.onNodeWithTag("message-bubble:msg-latest").assertDoesNotExist()

        val view = requireNotNull(hostView)
        for (frame in 1..10) {
            compose.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, frame * 60))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(view, insets)
            }
            compose.waitForIdle()
        }

        compose.onNodeWithTag("message-bubble:msg-latest").assertDoesNotExist()
        compose.onNodeWithText("回到最新").assertIsDisplayed()
    }

    @Test
    fun `斜杠命令浮层在布局阶段避让抬起的输入坞且不与输入框重叠`() {
        val controller = buildControllerWithHistory()
        var hostView: View? = null

        compose.setContent {
            hostView = LocalView.current
            MaterialTheme {
                Box(Modifier.size(411.dp, 891.dp)) {
                    WorkbenchScreen(
                        controller = controller,
                        gatewayLabel = "https://remote.example",
                        onOpenSettings = {},
                        onPickCamera = {},
                        onPickGallery = {},
                        onPickDocument = {},
                        onVoiceInput = {},
                        onLogout = {},
                    )
                }
            }
        }

        compose.runOnIdle {
            controller.openThread("thread-active")
            controller.editDraft("/")
        }
        compose.waitForIdle()
        compose.onNodeWithText("/help", substring = true).assertIsDisplayed()

        val view = requireNotNull(hostView)
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 600))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()

        val popupBounds = compose.onNodeWithText("/help", substring = true).fetchSemanticsNode().boundsInRoot
        val composerBounds = compose.onNodeWithContentDescription("添加附件").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "命令浮层应位于输入坞上方：popupBottom=${popupBounds.bottom}, composerTop=${composerBounds.top}",
            popupBounds.bottom <= composerBounds.top + 4f,
        )
    }

    @Test
    fun `多工作区抽屉缓存过滤后搜索仍能按净化后的标题匹配`() {
        val controller = buildControllerWithHistory()
        val menu = WorkspaceMenuState(
            agentTitle = "主力电脑 · DSH",
            groups = listOf(
                WorkspaceConversationGroup(
                    workspaceKey = "ws-1",
                    title = "活跃工作区",
                    isActive = true,
                    threads = Loadable.Ready(
                        listOf(
                            ConversationSummary(ConversationId("a1"), "普通任务甲", 2000L),
                        ),
                    ),
                ),
                WorkspaceConversationGroup(
                    workspaceKey = "ws-2",
                    title = "次工作区",
                    isActive = false,
                    threads = Loadable.Ready(
                        listOf(
                            ConversationSummary(
                                ConversationId("b1"),
                                "<environment_context><cwd>/repo</cwd></environment_context>\n排查数据库锁超时",
                                3000L,
                            ),
                            ConversationSummary(ConversationId("b2"), "界面样式调整", 1000L),
                        ),
                    ),
                ),
            ),
        )

        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(411.dp, 891.dp)) {
                    WorkbenchScreen(
                        controller = controller,
                        gatewayLabel = "https://remote.example",
                        onOpenSettings = {},
                        onPickCamera = {},
                        onPickGallery = {},
                        onPickDocument = {},
                        onVoiceInput = {},
                        onLogout = {},
                        workspaceMenu = menu,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("排查数据库锁超时").assertIsDisplayed()
        compose.onNodeWithText("界面样式调整").assertIsDisplayed()

        // 按关键词搜索：应匹配净化后的真实标题，且过滤掉无关会话与已剥离的注入标签
        val searchBox = compose.onNode(hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("workspace-conversation-browser")))
        searchBox.performClick()
        searchBox.performTextInput("锁超时")
        compose.waitForIdle()
        compose.onNodeWithText("排查数据库锁超时").assertIsDisplayed()
        compose.onNodeWithText("界面样式调整").assertDoesNotExist()
    }

    @Test
    fun `sanitizeSummaryText 快速路径对常规与含标点标题耗时显著降低且语义不变`() {
        val titles = (1..200).map { i ->
            when (i % 4) {
                0 -> "排查 Android 键盘动画掉帧问题 $i"
                1 -> "Fix PR-207 workspace hydration $i"
                2 -> "修复: 登录页与工作区切换 $i"
                else -> "普通对话记录 $i"
            }
        }
        // 预热
        titles.forEach { assertEquals(it, ContextInjectionFilter.sanitizeSummaryText(it)) }

        val startNs = System.nanoTime()
        repeat(20) {
            for (title in titles) {
                ContextInjectionFilter.sanitizeSummaryText(title)
            }
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        println("[BENCH-SANITIZE-FIXED] 200 titles x 20 frames = 4000 calls: ${"%.2f".format(elapsedMs)}ms (${"%.3f".format(elapsedMs / 20.0)}ms/frame)")
        assertTrue(
            "200 条常规标题单帧净化应低于 1ms（当前 ${"%.3f".format(elapsedMs / 20.0)}ms/frame）",
            elapsedMs / 20.0 < 1.0,
        )
    }

    private fun buildControllerWithHistory(): WorkbenchController {
        val history = (1..15).map { i ->
            TimelineMessage(
                id = "msg-$i",
                sender = if (i % 2 == 1) "user" else "assistant",
                parts = listOf(MessagePart.Text("第 $i 条对话内容：" + "这是一段正常的历史消息正文。".repeat(12))),
                timestamp = 10_000L + i,
                conversationId = ConversationId("thread-active"),
            )
        } + TimelineMessage(
            id = "msg-latest",
            sender = "assistant",
            parts = listOf(MessagePart.Text("这是最新一条助手回复，用于确认键盘弹起后依然保持在可视区域底部：最新消息结尾标记")),
            timestamp = 10_100L,
            conversationId = ConversationId("thread-active"),
        )
        val repo = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(
                    (1..40).map { t ->
                        ConversationSummary(
                            id = ConversationId(if (t == 1) "thread-active" else "t-$t"),
                            title = if (t % 2 == 0) "活跃工作区会话 $t" else "修复: 任务-1-$t 排查",
                            updatedAt = 20_000L - t,
                        )
                    },
                    null,
                )
            override suspend fun timeline(conversationId: String, page: PageRequest) =
                TimelinePage(history, null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("thread-new"), "", 0L)
            override suspend fun submitMessage(message: OutgoingMessage) =
                MessageAcceptance("m1", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        return WorkbenchController(
            scope = scope,
            repository = repo,
            catalogRepository = object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(
                        CatalogVersion("v1"),
                        listOf(AgentCommand("/help", "查看可用命令")),
                    )
            },
            scopeFactory = { ConversationScope("p", "g", "a", "i") },
        )
    }

    private fun buildMultiWorkspaceMenu(workspaceCount: Int, threadsPerWorkspace: Int): WorkspaceMenuState {
        val groups = (1..workspaceCount).map { ws ->
            val threads = (1..threadsPerWorkspace).map { t ->
                ConversationSummary(
                    id = ConversationId(if (ws == 1 && t == 1) "thread-active" else "ws${ws}_t$t"),
                    title = if (t % 2 == 0) "工作区 $ws 会话记录 $t" else "修复: 任务-$ws-$t 排查",
                    updatedAt = 100_000L - t * 10L,
                    archived = false,
                )
            }
            WorkspaceConversationGroup(
                workspaceKey = "ws-$ws",
                title = "工作区 $ws",
                subtitle = "/repo/workspace-$ws",
                isActive = ws == 1,
                threads = Loadable.Ready(threads),
            )
        }
        return WorkspaceMenuState(
            agentTitle = "主力电脑 · DSH",
            groups = groups,
        )
    }
}
