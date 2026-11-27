package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class WorkspaceConversationBrowserTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        scope.cancel()
    }

    private fun buildController(): WorkbenchController = WorkbenchController(
        scope = scope,
        repository = StubConversationRepository(),
        catalogRepository = object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        scopeFactory = { ConversationScope("p", "g", "a", "i") },
        supportsConversationManagement = true,
        supportsConversationRename = true,
        supportsConversationArchive = true,
    )

    private fun sampleGroups(): List<WorkspaceConversationGroup> = listOf(
        WorkspaceConversationGroup(
            workspaceKey = "ws-one",
            title = "核心仓库",
            subtitle = "/repo/core",
            isActive = true,
            threads = Loadable.Ready(
                listOf(
                    ConversationSummary(ConversationId("t1"), "工作区一对话", 1000L, false),
                )
            ),
        ),
        WorkspaceConversationGroup(
            workspaceKey = "ws-two",
            title = "文档仓库",
            subtitle = "/repo/docs",
            isActive = false,
            threads = Loadable.Ready(
                listOf(
                    ConversationSummary(ConversationId("t2"), "工作区二对话", 2000L, false),
                )
            ),
        ),
    )

    @Test
    fun `刷新会话按钮存在强制恢复回调时只执行完整恢复`() {
        var forceRefreshCount = 0
        var catalogRefreshCount = 0
        var currentListRefreshCount = 0
        val menu = WorkspaceMenuState(
            agentTitle = "主力电脑 · DSH",
            groups = sampleGroups(),
            onRefreshCatalog = { catalogRefreshCount++ },
            onForceRefresh = { forceRefreshCount++ },
        )

        compose.setContent {
            MaterialTheme {
                WorkspaceConversationBrowser(
                    workspaceMenu = menu,
                    activeThreadId = null,
                    onOpenThread = {},
                    onCreateThread = {},
                    onRefresh = { currentListRefreshCount++ },
                )
            }
        }

        compose.onNodeWithContentDescription("刷新会话").performClick()

        assertEquals(1, forceRefreshCount)
        assertEquals(0, catalogRefreshCount)
        assertEquals(0, currentListRefreshCount)
    }

    @Test
    fun `刷新会话按钮缺少强制恢复回调时保持目录与列表刷新语义`() {
        var catalogRefreshCount = 0
        var currentListRefreshCount = 0
        val menu = WorkspaceMenuState(
            agentTitle = "主力电脑 · DSH",
            groups = sampleGroups(),
            onRefreshCatalog = { catalogRefreshCount++ },
        )

        compose.setContent {
            MaterialTheme {
                WorkspaceConversationBrowser(
                    workspaceMenu = menu,
                    activeThreadId = null,
                    onOpenThread = {},
                    onCreateThread = {},
                    onRefresh = { currentListRefreshCount++ },
                )
            }
        }

        compose.onNodeWithContentDescription("刷新会话").performClick()

        assertEquals(1, catalogRefreshCount)
        assertEquals(1, currentListRefreshCount)
    }

    @Test
    fun `打开抽屉自动触发工作区水合并按工作区分组展示且移除文件夹管理`() {
        val controller = buildController()
        var hydrateCount = 0
        val menu = WorkspaceMenuState(
            agentTitle = "主力电脑 · DSH",
            groups = sampleGroups(),
            onHydrate = { hydrateCount++ },
        )

        compose.setContent {
            MaterialTheme {
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

        // 打开抽屉后自动触发水合
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.waitForIdle()
        assertTrue("打开抽屉时应自动触发 onHydrate()", hydrateCount >= 1)

        // 同时展示多个工作区及其下属会话
        compose.onNodeWithTag("workspace-group-header:ws-one").assertIsDisplayed()
        compose.onNodeWithTag("workspace-group-header:ws-two").assertIsDisplayed()
        compose.onNodeWithText("工作区一对话").assertIsDisplayed()
        compose.onNodeWithText("工作区二对话").assertIsDisplayed()

        // 彻底移除文件夹创建/管理/移动入口
        compose.onNodeWithContentDescription("管理文件夹").assertDoesNotExist()
        compose.onNodeWithText("新建文件夹").assertDoesNotExist()
        compose.onNodeWithText("移动到文件夹").assertDoesNotExist()
    }

    @Test
    fun `点击工作区头部可折叠与展开其下会话列表`() {
        val menu = WorkspaceMenuState(
            agentTitle = "主力电脑 · DSH",
            groups = sampleGroups(),
        )

        compose.setContent {
            MaterialTheme {
                ThreadDrawer(
                    gatewayLabel = "https://remote.example",
                    threads = Loadable.Empty,
                    activeThreadId = "t1",
                    onOpenThread = {},
                    onCreateThread = {},
                    onRefresh = {},
                    onOpenSettings = {},
                    onCloseDrawer = {},
                    onLogout = {},
                    managementSupported = true,
                    online = true,
                    workspaceMenu = menu,
                )
            }
        }

        compose.onNodeWithText("工作区二对话").assertIsDisplayed()
        compose.onNodeWithTag("workspace-group-header:ws-two").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("工作区二对话").assertDoesNotExist()

        compose.onNodeWithTag("workspace-group-header:ws-two").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("工作区二对话").assertIsDisplayed()
    }

    @Test
    fun `点击非当前工作区会话与工作区新建按钮正确触发对应回调`() {
        val opened = mutableListOf<Pair<String, String>>()
        val created = mutableListOf<String>()
        var closedDrawer = 0
        val menu = WorkspaceMenuState(
            agentTitle = "主力电脑 · DSH",
            groups = sampleGroups(),
            onOpenWorkspaceThread = { ws, threadId -> opened += ws to threadId },
            onCreateWorkspaceThread = { ws -> created += ws },
        )

        compose.setContent {
            MaterialTheme {
                ThreadDrawer(
                    gatewayLabel = "https://remote.example",
                    threads = Loadable.Empty,
                    activeThreadId = "t1",
                    onOpenThread = {},
                    onCreateThread = {},
                    onRefresh = {},
                    onOpenSettings = {},
                    onCloseDrawer = { closedDrawer++ },
                    onLogout = {},
                    managementSupported = true,
                    online = true,
                    workspaceMenu = menu,
                )
            }
        }

        compose.onNodeWithTag("workspace-thread:ws-two:t2").performClick()
        compose.onNodeWithTag("workspace-group-create:ws-two").performClick()
        compose.runOnIdle {
            assertEquals(listOf("ws-two" to "t2"), opened)
            assertEquals(listOf("ws-two"), created)
            assertEquals(2, closedDrawer)
        }
    }

    @Test
    fun `工作区浏览器保留排序切换与受控删除能力且不展示文件夹入口`() {
        val sortedModes = mutableListOf<ConversationSortMode>()
        val deletedIds = mutableListOf<String>()
        val menu = WorkspaceMenuState(
            agentTitle = "主力电脑 · DSH",
            groups = sampleGroups(),
        )
        var deleteSupported by mutableStateOf(true)

        compose.setContent {
            MaterialTheme {
                WorkspaceConversationBrowser(
                    workspaceMenu = menu,
                    activeThreadId = "t1",
                    onOpenThread = {},
                    onCreateThread = {},
                    onRefresh = {},
                    organization = ConversationOrganization(),
                    managementSupported = true,
                    deleteSupported = deleteSupported,
                    archiveSupported = true,
                    renameSupported = true,
                    online = true,
                    actions = ConversationBrowserActions(
                        sort = { sortedModes += it },
                        delete = { deletedIds += it },
                    ),
                )
            }
        }

        // 排序切换生效
        compose.onNodeWithTag("workspace-browser-sort-button").performClick()
        compose.onNodeWithText("自定义顺序").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ConversationSortMode.CUSTOM), sortedModes)
        }

        // 支持删除时打开更多菜单并确认删除
        compose.onNodeWithContentDescription("管理会话 工作区一对话").performClick()
        compose.onNodeWithText("删除对话").performClick()
        compose.onNodeWithText("确定删除“工作区一对话”？对话记录将从 Gateway 删除。").assertIsDisplayed()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle {
            assertEquals(listOf("t1"), deletedIds)
        }

        // 禁用删除时给出不支持提示，且始终不存在文件夹入口
        deleteSupported = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription("管理会话 工作区一对话").performClick()
        compose.onNodeWithText("当前 Agent 不支持删除对话").assertIsDisplayed()
        compose.onNodeWithContentDescription("管理文件夹").assertDoesNotExist()
        compose.onNodeWithText("新建文件夹").assertDoesNotExist()
        compose.onNodeWithText("移动到文件夹").assertDoesNotExist()
    }

    private class StubConversationRepository : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(emptyList(), null)

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("new"), "", 0)

        override suspend fun timeline(conversationId: String, page: PageRequest) =
            TimelinePage(emptyList(), null)

        override suspend fun submitMessage(message: OutgoingMessage) =
            MessageAcceptance("msg", message.clientMessageId.value)

        override suspend fun submitBatch(batch: MessageBatch) =
            BatchAcceptance(batch.batchId)

        override suspend fun pendingDeliveries(conversationId: String) = emptyList<PendingDelivery>()
        override suspend fun retryPendingDeliveries(conversationId: String) = Unit
        override suspend fun retryAllPendingDeliveries() = Unit
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
