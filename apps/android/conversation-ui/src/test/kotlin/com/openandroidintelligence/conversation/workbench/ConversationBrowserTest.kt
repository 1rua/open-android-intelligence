package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.ConversationFolder
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationSortMode
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.state.Loadable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
// Exercise popup placement and drag hit targets with the native graphics pipeline.
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ConversationBrowserTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun recentSortIsTheDefaultAndCustomOrderIsReflectedAfterTheSaveCallback() {
        val rows = listOf(
            thread("a", "Alpha", updatedAt = 1),
            thread("b", "Beta", updatedAt = 2),
            thread("c", "Gamma", updatedAt = 3),
        )
        val organization = mutableStateOf(
            ConversationOrganization(customOrder = listOf("a", "c", "b")),
        )
        val savedSorts = mutableListOf<ConversationSortMode>()

        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    ConversationBrowser(
                        threads = Loadable.Ready(rows),
                        activeThreadId = null,
                        onOpenThread = {},
                        onCreateThread = {},
                        onRefresh = {},
                        organization = organization.value,
                        managementSupported = true,
                        actions = ConversationBrowserActions(
                            sort = { mode ->
                                savedSorts += mode
                                organization.value = organization.value.copy(sortMode = mode)
                            },
                        ),
                    )
                }
            }
        }

        assertEquals(listOf("c", "b", "a"), visibleThreadOrder("a", "b", "c"))
        compose.onNodeWithText("最近更新").performClick()
        compose.onNodeWithText("自定义顺序").performClick()

        compose.runOnIdle {
            assertEquals(listOf(ConversationSortMode.CUSTOM), savedSorts)
        }
        assertEquals(listOf("a", "c", "b"), visibleThreadOrder("a", "b", "c"))
        compose.onNodeWithText("自定义顺序").assertIsDisplayed()
    }

    @Test
    fun longPressDragInFolderPreservesThePositionsOfThreadsOutsideTheFolder() {
        val rows = listOf(
            thread("a", "Alpha", 4),
            thread("outside", "Outside folder", 3),
            thread("b", "Beta", 2),
            thread("d", "Delta", 1),
        )
        val organization = ConversationOrganization(
            sortMode = ConversationSortMode.CUSTOM,
            customOrder = listOf("a", "outside", "b", "d"),
            folders = listOf(ConversationFolder("project", "Project")),
            assignments = mapOf("a" to "project", "b" to "project", "d" to "project"),
        )
        var savedOrder: List<String>? = null

        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    ConversationBrowser(
                        threads = Loadable.Ready(rows),
                        activeThreadId = null,
                        onOpenThread = {},
                        onCreateThread = {},
                        onRefresh = {},
                        organization = organization,
                        managementSupported = true,
                        actions = ConversationBrowserActions(reorder = { savedOrder = it }),
                    )
                }
            }
        }

        compose.onNodeWithText("全部会话").performClick()
        compose.onNode(hasText("Project") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithTag("conversation:a").assertIsDisplayed()
        compose.onNodeWithTag("conversation:outside").assertDoesNotExist()

        val firstBounds = compose.onNodeWithTag("conversation:a").fetchSemanticsNode().boundsInRoot
        val secondBounds = compose.onNodeWithTag("conversation:b").fetchSemanticsNode().boundsInRoot
        val verticalDelta = secondBounds.center.y - firstBounds.center.y
        compose.onNodeWithTag("conversation:a").performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(center + Offset(0f, verticalDelta))
            up()
        }

        compose.runOnIdle {
            // The folder order becomes [b, a, d], while the outside conversation stays in slot 1.
            assertEquals(listOf("b", "outside", "a", "d"), savedOrder)
        }
    }

    @Test
    fun archivedFilterHidesActiveThreadsAndTheOldestHistoryCanStillBeOpened() {
        val rows = (1..35).map { number ->
            thread(
                id = "thread_$number",
                title = if (number == 1) "Oldest history" else "History $number",
                updatedAt = number.toLong(),
                archived = number == 5 || number == 12,
            )
        }
        var opened: String? = null

        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ConversationBrowser(
                        threads = Loadable.Ready(rows),
                        activeThreadId = null,
                        onOpenThread = { opened = it },
                        onCreateThread = {},
                        onRefresh = {},
                    )
                }
            }
        }

        compose.onNodeWithText("全部会话").performClick()
        compose.onNode(hasText("已归档") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithTag("conversation:thread_5").assertExists()
        compose.onNodeWithTag("conversation:thread_12").assertExists()
        compose.onNodeWithTag("conversation:thread_10").assertDoesNotExist()

        compose.onNode(hasText("已归档") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.onNode(hasText("全部会话") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithTag("conversation-browser").performScrollToNode(hasTestTag("conversation:thread_1"))
        compose.onNodeWithTag("conversation:thread_1").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals("thread_1", opened) }
    }

    @Test
    // Robolectric dialogs with editable text loop above its default 320dp width:
    // https://github.com/robolectric/robolectric/issues/8460
    @org.robolectric.annotation.Config(qualifiers = "w320dp-h891dp-420dpi")
    fun folderCreateRenameAssignmentAndDeleteReachTheirCallbacks() {
        val organization = mutableStateOf(ConversationOrganization())
        val calls = mutableListOf<String>()

        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    ConversationBrowser(
                        threads = Loadable.Ready(listOf(thread("inbox", "Inbox", 1))),
                        activeThreadId = null,
                        onOpenThread = {},
                        onCreateThread = {},
                        onRefresh = {},
                        organization = organization.value,
                        managementSupported = true,
                        actions = ConversationBrowserActions(
                            createFolder = { name ->
                                calls += "create:$name"
                                organization.value = organization.value.copy(
                                    folders = organization.value.folders + ConversationFolder("new", name),
                                )
                            },
                            renameFolder = { id, name ->
                                calls += "rename:$id:$name"
                                organization.value = organization.value.copy(
                                    folders = organization.value.folders.map { if (it.folderId == id) it.copy(name = name) else it },
                                )
                            },
                            deleteFolder = { id ->
                                calls += "delete:$id"
                                organization.value = organization.value.copy(
                                    folders = organization.value.folders.filterNot { it.folderId == id },
                                )
                            },
                            assignFolder = { threadId, folderId ->
                                calls += "assign:$threadId:$folderId"
                                organization.value = organization.value.copy(
                                    assignments = if (folderId == null) organization.value.assignments - threadId
                                    else organization.value.assignments + (threadId to folderId),
                                )
                            },
                        ),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("管理文件夹").performClick()
        compose.onNodeWithText("新建文件夹").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("管理文件夹").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("管理文件夹").assertIsDisplayed()
        compose.onNodeWithText("新建文件夹").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Work")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("完成").performClick()

        compose.onNodeWithContentDescription("管理文件夹").performClick()
        compose.onNodeWithContentDescription("重命名文件夹 Work").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Projects")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("完成").performClick()

        compose.onNodeWithContentDescription("管理会话 Inbox").performClick()
        compose.onNodeWithText("移动到文件夹").performClick()
        compose.onNodeWithText("Projects").performClick()

        compose.onNodeWithContentDescription("管理文件夹").performClick()
        compose.onNodeWithContentDescription("删除文件夹 Projects").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("删除").performClick()

        compose.runOnIdle {
            assertEquals(
                listOf("create:Work", "rename:new:Projects", "assign:inbox:new", "delete:new"),
                calls,
            )
        }
    }

    @Test
    fun busyThreadCannotBeArchivedOrDeleted() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    ConversationBrowser(
                        threads = Loadable.Ready(listOf(thread("busy", "Busy thread", 1))),
                        activeThreadId = null,
                        onOpenThread = {},
                        onCreateThread = {},
                        onRefresh = {},
                        managementSupported = true,
                        busyThreadIds = setOf("busy"),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("管理会话 Busy thread").performClick()
        compose.onNodeWithText("归档对话").assertIsNotEnabled()
        compose.onNodeWithText("删除对话").assertIsNotEnabled()
        compose.onNodeWithText("任务结束后可归档或删除").assertIsDisplayed()
    }

    @Test
    fun unsupportedGatewayDisablesManagementAndNeverInvokesManagementActions() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    ConversationBrowser(
                        threads = Loadable.Ready(listOf(thread("unsupported", "Old thread", 1))),
                        activeThreadId = null,
                        onOpenThread = {},
                        onCreateThread = {},
                        onRefresh = {},
                        managementSupported = false,
                        actions = ConversationBrowserActions(
                            rename = { _, _ -> calls += "rename" },
                            archive = { _, _ -> calls += "archive" },
                            delete = { calls += "delete" },
                            createFolder = { calls += "createFolder" },
                            sort = { calls += "sort" },
                            reorder = { calls += "reorder" },
                        ),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("管理文件夹").assertIsNotEnabled()
        compose.onNodeWithText("最近更新").assertIsNotEnabled()
        compose.onNodeWithContentDescription("管理会话 Old thread").performClick()
        compose.onNodeWithText("重命名对话").assertIsNotEnabled()
        compose.onNodeWithText("移动到文件夹").assertIsNotEnabled()
        compose.onNodeWithText("归档对话").assertIsNotEnabled()
        compose.onNodeWithText("删除对话").assertIsNotEnabled()

        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    private fun visibleThreadOrder(vararg ids: String): List<String> = ids
        .map { id -> id to compose.onNodeWithTag("conversation:$id").fetchSemanticsNode().boundsInRoot.top }
        .sortedBy { it.second }
        .map { it.first }

    private fun thread(id: String, title: String, updatedAt: Long, archived: Boolean = false) =
        ConversationSummary(ConversationId(id), title, updatedAt, archived)
}
