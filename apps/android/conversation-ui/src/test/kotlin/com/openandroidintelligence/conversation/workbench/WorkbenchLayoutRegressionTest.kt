package com.openandroidintelligence.conversation.workbench

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class WorkbenchLayoutRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { scope.cancel() }

    @Test fun firstDraftHasAnEnabledSendButtonWithoutAnExistingThread() {
        val controller = WorkbenchController(scope, EmptyGateway(), object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("p", "g", "a", "i") })
        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {}, onLogout = {})
            }
        }
        compose.runOnIdle { controller.editDraft("1111") }
        compose.onNodeWithContentDescription("发送").assertIsEnabled()
    }

    @Test fun initializingBlankPageDoesNotClaimThatApprovalCardsAreUnsupported() {
        val controller = WorkbenchController(scope, EmptyGateway(), object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("p", "g", "a", "i") }, allowSending = false, restoreLastThread = false)
        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {}, onLogout = {},
                    connectionHealth = StreamHealth.CONNECTING)
            }
        }
        compose.onNodeWithText("从一个想法开始").assertIsDisplayed()
        compose.onNodeWithText("正在连接…", substring = true).assertIsDisplayed()
        compose.onNodeWithText("该网关不支持审批卡片，请用 /approve 文本命令回复。").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test fun searchFieldGrowsWithFontScaleInsteadOfClippingItsPlaceholder() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 640.dp)) {
                        ThreadDrawer("gateway", Loadable.Empty, null, {}, {}, {}, {}, {}, onLogout = {})
                    }
                }
            }
        }
        val placeholderHeight = compose.onNodeWithText("搜索会话…", useUnmergedTree = true).textLayoutHeight()
        val field = compose.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "搜索框必须容纳完整占位文字：字段高 ${field.height}px，文字高 ${placeholderHeight}px",
            field.height >= placeholderHeight,
        )
        // 旧实现把高度写死为 44dp，比 Material 文本字段的最小高度（56dp）还小，
        // 装饰区内边距被压缩后占位文字就会被裁掉。
        val materialMinimum = with(compose.density) { OutlinedTextFieldDefaults.MinHeight.roundToPx() }
        assertTrue(
            "搜索框不能低于 Material 文本字段最小高度：字段高 ${field.height}px，最小值 ${materialMinimum}px",
            field.height >= materialMinimum,
        )
    }

    @Test
    fun settingsEntryRemainsVisibleInFooterEvenWithManyThreads() {
        val manyThreads = (1..30).map { i ->
            ConversationSummary(ConversationId("id_$i"), "历史会话 $i", 1000L + i)
        }
        var settingsOpened = false
        var drawerClosed = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 480.dp)) {
                    ThreadDrawer(
                        gatewayLabel = "user@test",
                        threads = Loadable.Ready(manyThreads),
                        activeThreadId = null,
                        onOpenThread = {},
                        onCreateThread = {},
                        onRefresh = {},
                        onOpenSettings = { settingsOpened = true },
                        onCloseDrawer = { drawerClosed = true },
                        onLogout = {},
                    )
                }
            }
        }

        // 即使有 30 条会话撑满列表，固定页脚中的「设置与平台管理」也必须常驻可见，无需滚动
        val settingsNode = compose.onNodeWithText("设置与平台管理")
        settingsNode.assertIsDisplayed()
        settingsNode.performClick()
        assertTrue("点击设置项必须触发 onOpenSettings", settingsOpened)
        assertFalse("切换到设置时不能关闭并重新打开抽屉", drawerClosed)

        // 页脚同级的协议版本号与退出登录也必须常驻可见
        compose.onNodeWithText("Gateway Protocol v2").assertIsDisplayed()
        compose.onNodeWithText("退出登录").assertIsDisplayed()
    }

    @Test
    fun phoneSwitchesToSettingsWithoutClosingAndVerticalScrollDoesNotDismiss() {
        val controller = sidebarController()
        var settingsOpen by mutableStateOf(false)
        val settingsChanges = mutableListOf<Boolean>()
        var mainMounts = 0
        var mainDisposals = 0
        compose.setContent {
            MaterialTheme {
                SidebarWorkbench(controller, settingsOpen, {
                    settingsChanges += it
                    settingsOpen = it
                }, header = {
                    DisposableEffect(Unit) {
                        mainMounts++
                        onDispose { mainDisposals++ }
                    }
                })
            }
        }

        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithText("设置与平台管理").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue("点击后设置应保持打开；状态变化：$settingsChanges", settingsOpen)
        }
        compose.onNodeWithText("测试设置面板").assertIsDisplayed()
        repeat(3) {
            compose.onNodeWithTag("sidebar-test-scroll").performTouchInput { swipeUp() }
            compose.onNodeWithTag("sidebar-test-scroll").performTouchInput { swipeDown() }
        }
        compose.onNodeWithText("测试设置面板").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("纵向滚动不能关闭设置", settingsOpen)
            assertEquals("设置切换不能重建右侧内容", 1, mainMounts)
            assertEquals("设置切换不能销毁右侧内容", 0, mainDisposals)
        }

        compose.onNodeWithText("返回会话").performClick()
        compose.onNodeWithText("Gateway Protocol v2").assertIsDisplayed()
        compose.runOnIdle { assertFalse("返回会话列表时应退出设置", settingsOpen) }
    }

    @Test
    fun phoneSettingsSemanticsActionKeepsDrawerOpen() {
        val controller = sidebarController()
        var settingsOpen by mutableStateOf(false)
        val settingsChanges = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                SidebarWorkbench(controller, settingsOpen, {
                    settingsChanges += it
                    settingsOpen = it
                })
            }
        }

        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithText("设置与平台管理").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertTrue("语义点击后设置应保持打开；状态变化：$settingsChanges", settingsOpen)
        }
        compose.onNodeWithText("测试设置面板").assertIsDisplayed()
    }

    @Test
    fun switchingFromConversationToSettingsKeepsDrawerOnScreenDuringTransition() {
        val controller = sidebarController()
        var settingsOpen by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SidebarWorkbench(
                    controller,
                    settingsOpen,
                    { settingsOpen = it },
                )
            }
        }
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithText("设置与平台管理").assertIsDisplayed()
        val drawerBefore = compose.onNodeWithTag("workbench-drawer", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val initialBounds = drawerBefore.boundsInRoot
        val initialId = drawerBefore.id

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("设置与平台管理").performClick()
            // 覆盖内容切换以及可能错误触发的抽屉关闭、重新打开动画。
            repeat(90) { frame ->
                compose.mainClock.advanceTimeByFrame()
                val drawers = compose.onAllNodesWithTag("workbench-drawer", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                assertEquals("第 ${frame + 1} 帧必须保持唯一的抽屉容器", 1, drawers.size)
                val drawer = drawers.single()
                assertEquals("第 ${frame + 1} 帧不能销毁并重新创建抽屉", initialId, drawer.id)
                assertEquals("第 ${frame + 1} 帧抽屉左边界不能移动", initialBounds.left, drawer.boundsInRoot.left, 1f)
                assertEquals("第 ${frame + 1} 帧抽屉右边界不能移动", initialBounds.right, drawer.boundsInRoot.right, 1f)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()

        compose.onNodeWithText("测试设置面板").assertIsDisplayed()
        compose.runOnIdle { assertTrue("切换后应保持设置面板打开", settingsOpen) }
    }

    @Test
    fun phoneHorizontalDismissClosesSettingsAndSynchronizesItsState() {
        val controller = sidebarController()
        var settingsOpen by mutableStateOf(false)
        compose.setContent {
            MaterialTheme { SidebarWorkbench(controller, settingsOpen, { settingsOpen = it }) }
        }

        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithText("设置与平台管理").performClick()
        compose.onNodeWithTag("sidebar-test-panel").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertFalse("横向关闭抽屉后设置状态也必须关闭", settingsOpen) }
        compose.onNodeWithContentDescription("打开会话抽屉").assertIsDisplayed()
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w1200dp-h900dp-160dpi")
    fun wideSettingsReplaces320dpSidebarWithoutRebuildingMainContent() {
        val controller = sidebarController()
        var settingsOpen by mutableStateOf(false)
        var mainMounts = 0
        var mainDisposals = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 800.dp)) {
                    SidebarWorkbench(controller, settingsOpen, { settingsOpen = it }, header = {
                        DisposableEffect(Unit) {
                            mainMounts++
                            onDispose { mainDisposals++ }
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).testTag("main-content-marker"))
                    })
                }
            }
        }
        compose.runOnIdle { controller.editDraft("保留的草稿") }
        val before = compose.onNodeWithTag("main-content-marker").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("设置与平台管理").performClick()
        compose.onNodeWithTag("sidebar-test-panel").assertWidthIsEqualTo(320.dp)
        val after = compose.onNodeWithTag("main-content-marker").fetchSemanticsNode().boundsInRoot
        assertEquals("设置不能改变右侧位置", before.left, after.left, 1f)
        assertEquals("设置不能改变右侧宽度", before.width, after.width, 1f)
        compose.runOnIdle {
            assertEquals("保留的草稿", controller.state.value.draft)
            assertEquals("设置切换不能重建会话内容", 1, mainMounts)
            assertEquals("设置切换不能销毁会话内容", 0, mainDisposals)
        }
        compose.onNodeWithText("返回会话").performClick()
        compose.onNodeWithText("Gateway Protocol v2").assertIsDisplayed()
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w1200dp-h900dp-160dpi")
    fun settingsSubpageAndDraftSurviveWindowBreakpointChanges() {
        val controller = sidebarController()
        var settingsOpen by mutableStateOf(false)
        var windowWidth by mutableStateOf(411.dp)
        var mainMounts = 0
        var mainDisposals = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(windowWidth, 800.dp)) {
                    SidebarWorkbench(controller, settingsOpen, { settingsOpen = it }, header = {
                        DisposableEffect(Unit) {
                            mainMounts++
                            onDispose { mainDisposals++ }
                        }
                    })
                }
            }
        }
        compose.runOnIdle { controller.editDraft("跨断点保留") }
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithText("设置与平台管理").performClick()
        compose.onNodeWithText("进入设置子页").performClick()
        compose.onNodeWithText("设置子页状态").assertIsDisplayed()

        compose.runOnIdle { windowWidth = 1000.dp }
        compose.onNodeWithTag("sidebar-test-panel").assertWidthIsEqualTo(320.dp)
        compose.onNodeWithText("设置子页状态").assertIsDisplayed()
        compose.runOnIdle { windowWidth = 411.dp }
        compose.onNodeWithText("设置子页状态").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("跨断点不能关闭设置", settingsOpen)
            assertEquals("跨断点保留", controller.state.value.draft)
            assertEquals("跨断点不能重建会话内容", 1, mainMounts)
            assertEquals("跨断点不能销毁会话内容", 0, mainDisposals)
        }
    }

    @Test fun failedSendExplainsItselfInsteadOfShowingABareErrorCode() {
        val readable = noticeText("SEND_FAILED:MASTER_KEY_UNAVAILABLE")
        assertTrue("失败提示必须给出可操作说明：$readable", readable.contains("主密钥") && !readable.contains("MASTER_KEY_UNAVAILABLE"))
        assertTrue(noticeText("STEERING_UNAVAILABLE:CAPABILITY").contains("草稿已保留"))
        assertTrue(noticeText("STEERING_UNAVAILABLE:ATTACHMENTS").contains("只接受文本"))
        assertTrue(noticeText("STEERED:CURRENT_TURN").contains("已交给 Agent"))
        assertEquals("已创建新对话", noticeText("已创建新对话"))
    }

    @Test
    fun thinkingIndicatorRendersWhenThinkingAndDisappearsWhenStreamingDeltaArrives() {
        val eventFlow = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        val gateway = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("conv_streaming"), "新对话", 0)
            override suspend fun submitMessage(message: OutgoingMessage) =
                MessageAcceptance("msg_user_1", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope) = eventFlow
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val controller = WorkbenchController(
            scope,
            gateway,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            { ConversationScope("p", "g", "a", "i") },
        )

        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {}, onLogout = {})
            }
        }

        // Welcome screen is displayed initially, no thinking indicator
        compose.onNodeWithText("从一个想法开始").assertIsDisplayed()
        compose.onNodeWithText("AI 正在思考").assertDoesNotExist()

        // Send a draft to enter QUEUED generation state
        compose.runOnIdle {
            controller.editDraft("你好")
            controller.sendDraft()
        }

        // Thinking indicator must be displayed while QUEUED/RUNNING and no assistant streaming yet
        compose.onNodeWithText("AI 正在思考").assertIsDisplayed()
        compose.onAllNodesWithText("你好").onFirst().assertIsDisplayed()

        // Assistant streaming delta arrives
        compose.runOnIdle {
            eventFlow.tryEmit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "evt_1",
                    occurredAt = 1000L,
                    revision = 1L,
                    message = TimelineMessage(
                        id = "msg_asst_1",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("助理正在回答中")),
                        timestamp = 1000L,
                        state = "STREAMING",
                        conversationId = ConversationId("conv_streaming"),
                    ),
                ),
            )
        }

        // Thinking indicator disappears, streaming indicator and streaming text appear
        compose.onNodeWithText("AI 正在思考").assertDoesNotExist()
        compose.onNodeWithText("正在输出…").assertIsDisplayed()
        compose.onNodeWithText("助理正在回答中", substring = true).assertIsDisplayed()

        // Final completion event arrives
        compose.runOnIdle {
            eventFlow.tryEmit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "evt_2",
                    occurredAt = 1010L,
                    revision = 2L,
                    message = TimelineMessage(
                        id = "msg_asst_1",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("助理正在回答中，回答完毕。")),
                        timestamp = 1000L,
                        state = "CONFIRMED",
                        conversationId = ConversationId("conv_streaming"),
                    ),
                ),
            )
        }

        // Thinking indicator remains absent, streaming tag is gone, complete message is displayed
        compose.onNodeWithText("AI 正在思考").assertDoesNotExist()
        compose.onNodeWithText("正在输出…").assertDoesNotExist()
        compose.onNodeWithText("助理正在回答中，回答完毕。", substring = true).assertIsDisplayed()
    }

    @Test
    fun thinkingIndicatorDisappearsWhenGenerationIsStopped() {
        var cancellationCalls = 0
        val gateway = object : ConversationRepository, GenerationTracker {
            override val generationId = kotlinx.coroutines.flow.MutableStateFlow<String?>("gen_stop")
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("conv_stop"), "新对话", 0)
            override suspend fun submitMessage(message: OutgoingMessage) =
                MessageAcceptance("msg_user_1", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult {
                assertEquals("gen_stop", generationId)
                cancellationCalls++
                this.generationId.value = null
                return CancelGenerationResult(CancelGenerationOutcome.CANCELLED)
            }
        }
        val controller = WorkbenchController(
            scope,
            gateway,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            { ConversationScope("p", "g", "a", "i") },
        )

        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {}, onLogout = {})
            }
        }

        compose.runOnIdle {
            controller.editDraft("正在提问")
            controller.sendDraft()
        }
        compose.onNodeWithText("AI 正在思考").assertIsDisplayed()

        compose.runOnIdle {
            controller.stopGeneration()
        }
        compose.runOnIdle {
            assertEquals(1, cancellationCalls)
            assertEquals(GenerationState.CANCELLED, controller.state.value.generation)
        }
        compose.onNodeWithText("AI 正在思考").assertDoesNotExist()
    }

    /**
     * 顶栏动作收敛后，「重命名对话」从独立图标移进「更多操作」菜单：契约不变——
     * 只有存在活动线程时才可用，点击后打开重命名对话框。这里按菜单契约断言。
     */
    @Test
    fun renameEntryIsEnabledInOverflowOnlyWhenAnActiveThreadExists() {
        val gateway = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("conv_rename"), "原始标题", 0)
            override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("msg_test", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
            override suspend fun updateTitle(conversationId: String, title: String): Boolean = true
        }
        val controller = WorkbenchController(
            scope,
            gateway,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            { ConversationScope("p", "g", "a", "i") },
        )

        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {}, onLogout = {})
            }
        }

        // 没有活动线程时菜单项存在但不可用：动作在，语义明确，不假装能改。
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("重命名对话").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("更多操作").performClick()

        // Open thread
        compose.runOnIdle {
            controller.openThread("conv_rename")
        }

        // 有活动线程后同一个菜单项变为可用，点击即打开重命名对话框。
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("重命名对话").assertIsDisplayed().assertIsEnabled()
    }

    private fun sidebarController(): WorkbenchController = WorkbenchController(
        scope,
        EmptyGateway(),
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        { ConversationScope("p", "g", "a", "i") },
    )

    @Composable
    private fun SidebarWorkbench(
        controller: WorkbenchController,
        settingsOpen: Boolean,
        onSettingsChange: (Boolean) -> Unit,
        header: @Composable () -> Unit = {},
        onPanelPosition: (Float) -> Unit = {},
    ) {
        WorkbenchScreen(
            controller = controller,
            gatewayLabel = "gateway",
            onOpenSettings = { onSettingsChange(true) },
            onPickCamera = {},
            onPickGallery = {},
            onPickDocument = {},
            onVoiceInput = {},
            onLogout = {},
            header = header,
            settingsOpen = settingsOpen,
            onSettingsOpenChange = onSettingsChange,
            settingsPanel = { onClose -> SidebarSettingsFixture(onClose, onPanelPosition) },
        )
    }

    @Composable
    private fun SidebarSettingsFixture(onClose: () -> Unit, onPanelPosition: (Float) -> Unit) {
        var subpageOpen by rememberSaveable { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().testTag("sidebar-test-panel")
                .onGloballyPositioned { onPanelPosition(it.positionInRoot().x) },
        ) {
            Text("测试设置面板")
            if (subpageOpen) {
                Text("设置子页状态")
            } else {
                TextButton(onClick = { subpageOpen = true }) { Text("进入设置子页") }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("sidebar-test-scroll"),
            ) {
                repeat(40) { Text("设置条目 $it") }
            }
            TextButton(onClick = onClose) { Text("返回会话") }
        }
    }

    private fun SemanticsNodeInteraction.textLayoutHeight(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().size.height
    }

    private class EmptyGateway : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = Conversation(ConversationId("conv_test"), "新对话", 0)
        override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("msg_test", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
