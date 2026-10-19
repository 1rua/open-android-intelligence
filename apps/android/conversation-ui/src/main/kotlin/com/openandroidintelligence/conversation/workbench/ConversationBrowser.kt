package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.openandroidintelligence.conversation.ports.ConversationFolder
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationSortMode
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.AppRadius
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The same history and organization controls are used in the drawer and the native bubble. */
@Composable
fun ConversationBrowser(
    controller: WorkbenchController,
    onOpenThread: (String) -> Unit,
    onCreateThread: () -> Unit,
    modifier: Modifier = Modifier,
    activeThreadId: String? = null,
) {
    val state by controller.state.collectAsState()
    Column(modifier) {
        state.notice?.let { notice ->
            Text(noticeText(notice), Modifier.fillMaxWidth().padding(12.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    ConversationBrowser(
        threads = state.threads,
        organization = state.organization,
        activeThreadId = activeThreadId ?: state.activeThreadId,
        managementSupported = state.conversationManagementSupported,
        managing = state.managingThreads,
        online = state.isOnline,
        busyThreadIds = state.busyThreadIds,
        onOpenThread = onOpenThread,
        onCreateThread = onCreateThread,
        onRefresh = controller::refreshThreads,
        actions = ConversationBrowserActions(
            rename = controller::renameThread,
            archive = controller::archiveThread,
            delete = controller::deleteThread,
            createFolder = controller::createFolder,
            renameFolder = controller::renameFolder,
            deleteFolder = controller::deleteFolder,
            assignFolder = controller::assignThreadToFolder,
            sort = controller::setThreadSortMode,
            reorder = controller::reorderThreads,
        ),
        modifier = Modifier.weight(1f),
    )
    }
}

data class ConversationBrowserActions(
    val rename: (String, String) -> Unit = { _, _ -> },
    val archive: (String, Boolean) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val createFolder: (String) -> Unit = {},
    val renameFolder: (String, String) -> Unit = { _, _ -> },
    val deleteFolder: (String) -> Unit = {},
    val assignFolder: (String, String?) -> Unit = { _, _ -> },
    val sort: (ConversationSortMode) -> Unit = {},
    val reorder: (List<String>) -> Unit = {},
)

private const val ALL_THREADS = "all"
private const val UNFILED_THREADS = "unfiled"
private const val ARCHIVED_THREADS = "archived"
private const val FOLDER_PREFIX = "folder:"

@Composable
fun ConversationBrowser(
    threads: Loadable<List<ConversationSummary>>,
    activeThreadId: String?,
    onOpenThread: (String) -> Unit,
    onCreateThread: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    organization: ConversationOrganization = ConversationOrganization(),
    managementSupported: Boolean = false,
    managing: Boolean = false,
    online: Boolean = true,
    busyThreadIds: Set<String> = emptySet(),
    actions: ConversationBrowserActions = ConversationBrowserActions(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ALL_THREADS) }
    var showFilter by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var renameThread by remember { mutableStateOf<ConversationSummary?>(null) }
    var classifyThread by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteThread by remember { mutableStateOf<ConversationSummary?>(null) }
    var createFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<ConversationFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<ConversationFolder?>(null) }
    val canManage = managementSupported && online && !managing
    val listState = rememberLazyListState()
    val ordered = orderConversationRows((threads as? Loadable.Ready)?.value.orEmpty(), organization)
    val folderId = filter.takeIf { it.startsWith(FOLDER_PREFIX) }?.removePrefix(FOLDER_PREFIX)
    val selectedFolder = organization.folders.firstOrNull { it.folderId == folderId }
    LaunchedEffect(folderId, organization.folders) {
        if (folderId != null && selectedFolder == null) filter = ALL_THREADS
    }
    val filtered = ordered.filter { thread ->
        thread.title.contains(query, ignoreCase = true) && when (filter) {
            ARCHIVED_THREADS -> thread.archived
            UNFILED_THREADS -> !thread.archived && organization.assignments[thread.id.value] == null
            ALL_THREADS -> !thread.archived
            else -> !thread.archived && organization.assignments[thread.id.value] == folderId
        }
    }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var previewIds by remember { mutableStateOf<List<String>?>(null) }
    var originalIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragStartOffset by remember { mutableFloatStateOf(0f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    val rowsById = ordered.associateBy { it.id.value }
    val displayed = previewIds?.mapNotNull(rowsById::get) ?: filtered
    val currentOrdered by rememberUpdatedState(ordered)
    val currentFiltered by rememberUpdatedState(filtered)
    val currentReorder by rememberUpdatedState(actions.reorder)
    val currentCanManage by rememberUpdatedState(canManage)

    fun cancelDrag() {
        draggedId = null
        previewIds = null
        dragDistance = 0f
    }
    fun reorderUnderPointer() {
        val id = draggedId ?: return
        val ids = previewIds ?: return
        val center = dragStartOffset + dragDistance + dragHeight / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key in ids && it.key != id && center >= it.offset && center <= it.offset + it.size
        }?.key as? String ?: return
        val from = ids.indexOf(id)
        val to = ids.indexOf(target)
        if (from >= 0 && to >= 0 && from != to) {
            previewIds = ids.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
    val edgeSize = with(LocalDensity.current) { 48.dp.toPx() }
    val scrollStep = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            val center = dragStartOffset + dragDistance + dragHeight / 2f
            val viewport = listState.layoutInfo
            val scroll = when {
                center < viewport.viewportStartOffset + edgeSize -> -scrollStep
                center > viewport.viewportEndOffset - edgeSize -> scrollStep
                else -> 0f
            }
            if (scroll != 0f) {
                listState.scrollBy(scroll)
                reorderUnderPointer()
            }
            delay(16)
        }
    }
    LaunchedEffect(canManage, query, filter) { if (!canManage || draggedId != null) cancelDrag() }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("conversation-browser"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "browser-create") {
            Button(onClick = onCreateThread, enabled = online, shape = RoundedCornerShape(AppRadius.Small),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("新建对话")
            }
        }
        item(key = "browser-search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索会话…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(AppRadius.Small),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "browser-controls") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { showFilter = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(when (filter) {
                            ARCHIVED_THREADS -> "已归档"
                            UNFILED_THREADS -> "未分类"
                            else -> selectedFolder?.name ?: "全部会话"
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = showFilter, onDismissRequest = { showFilter = false }) {
                        listOf(ALL_THREADS to "全部会话", UNFILED_THREADS to "未分类").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { filter = value; showFilter = false })
                        }
                        organization.folders.forEach { folder ->
                            DropdownMenuItem(text = { Text(folder.name) }, onClick = {
                                filter = FOLDER_PREFIX + folder.folderId; showFilter = false
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("已归档") }, onClick = { filter = ARCHIVED_THREADS; showFilter = false })
                    }
                }
                IconButton(onClick = { showFolders = true }, enabled = managementSupported) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "管理文件夹")
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "刷新会话") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} 个会话", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { showSort = true }, enabled = canManage,
                        contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(if (organization.sortMode == ConversationSortMode.CUSTOM) "自定义顺序" else "最近更新")
                        Icon(Icons.Default.Sort, contentDescription = "会话排序", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        DropdownMenuItem(text = { Text("按更新时间倒序") }, onClick = {
                            showSort = false; actions.sort(ConversationSortMode.UPDATED_DESC)
                        })
                        DropdownMenuItem(text = { Text("自定义顺序") }, onClick = {
                            showSort = false; actions.sort(ConversationSortMode.CUSTOM)
                        })
                    }
                }
            }
            Text(
                when {
                    !managementSupported -> "此 Gateway 暂不支持会话管理，请更新 Gateway。"
                    !online -> "连接 Gateway 后可管理会话。"
                    managing -> "正在保存会话设置…"
                    else -> "长按会话并拖动可调整顺序"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        when (threads) {
            Loadable.Idle, Loadable.Loading -> item(key = "browser-loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            is Loadable.Failed -> item(key = "browser-error") {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("历史会话加载失败，请重试。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh) { Text("重试") }
                }
            }
            else -> if (displayed.isEmpty()) item(key = "browser-empty") {
                Text(if (query.isNotBlank()) "暂无匹配对话" else if (filter == ARCHIVED_THREADS) "暂无已归档对话" else "暂无历史对话",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        items(displayed, key = { it.id.value }) { thread ->
            val id = thread.id.value
            val isDragged = draggedId == id
            val itemOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: 0
            val dragOffset = if (isDragged) dragStartOffset + dragDistance - itemOffset else 0f
            ConversationBrowserRow(
                thread = thread,
                selected = id == activeThreadId,
                folder = organization.folders.firstOrNull { it.folderId == organization.assignments[id] }?.name,
                busy = id in busyThreadIds,
                canManage = canManage,
                onOpen = { onOpenThread(id) },
                onRename = { renameThread = thread },
                onClassify = { classifyThread = thread },
                onArchive = { actions.archive(id, !thread.archived) },
                onDelete = { deleteThread = thread },
                modifier = Modifier
                    .testTag("conversation:$id")
                    .zIndex(if (isDragged) 1f else 0f)
                    .offset { IntOffset(0, dragOffset.roundToInt()) }
                    .semantics {
                        if (canManage) {
                            customActions = listOf(-1 to "上移会话", 1 to "下移会话").map { (delta, label) ->
                                CustomAccessibilityAction(label) {
                                    val ids = currentFiltered.map { it.id.value }
                                    val from = ids.indexOf(id)
                                    val to = from + delta
                                    if (from >= 0 && to in ids.indices) {
                                        val moved = ids.toMutableList().apply { add(to, removeAt(from)) }
                                        currentReorder(reorderVisibleConversationIds(currentOrdered.map { it.id.value }, moved))
                                        true
                                    } else false
                                }
                            }
                        }
                    }
                    .pointerInput(id, canManage) {
                        if (canManage) detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                                if (item != null && currentCanManage) {
                                    originalIds = currentFiltered.map { it.id.value }
                                    previewIds = originalIds
                                    draggedId = id
                                    dragStartOffset = item.offset.toFloat()
                                    dragHeight = item.size
                                    dragDistance = 0f
                                }
                            },
                            onDragCancel = { cancelDrag() },
                            onDragEnd = {
                                val moved = previewIds
                                if (moved != null && moved != originalIds && currentCanManage) {
                                    currentReorder(reorderVisibleConversationIds(currentOrdered.map { it.id.value }, moved))
                                }
                                cancelDrag()
                            },
                            onDrag = { change, amount ->
                                if (draggedId == id) {
                                    change.consume()
                                    dragDistance += amount.y
                                    reorderUnderPointer()
                                }
                            },
                        )
                    },
            )
        }
    }

    renameThread?.let { thread ->
        NameDialog("重命名对话", "对话标题", thread.title, onDismiss = { renameThread = null }) {
            actions.rename(thread.id.value, it); renameThread = null
        }
    }
    classifyThread?.let { thread ->
        AlertDialog(
            onDismissRequest = { classifyThread = null },
            title = { Text("移动到文件夹") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item { TextButton(onClick = { actions.assignFolder(thread.id.value, null); classifyThread = null }) { Text("未分类") } }
                    items(organization.folders, key = { it.folderId }) { folder ->
                        TextButton(onClick = { actions.assignFolder(thread.id.value, folder.folderId); classifyThread = null }) { Text(folder.name) }
                    }
                    if (organization.folders.isEmpty()) item { Text("请先在“管理文件夹”中新建文件夹。") }
                }
            },
            confirmButton = { TextButton(onClick = { classifyThread = null }) { Text("取消") } },
        )
    }
    deleteThread?.let { thread ->
        ConfirmDeleteDialog("删除对话", "确定删除“${thread.title.ifBlank { "未命名对话" }}”？对话记录将从 Gateway 删除。",
            onDismiss = { deleteThread = null }, onConfirm = { actions.delete(thread.id.value); deleteThread = null })
    }
    // Keep one folder dialog visible so the active editor owns window and keyboard focus.
    // showFolders stays true to return to the manager after confirming or cancelling.
    if (showFolders && !createFolder && renameFolder == null && deleteFolder == null) {
        AlertDialog(
            onDismissRequest = { showFolders = false },
            title = { Text("管理文件夹") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (organization.folders.isEmpty()) item { Text("暂无文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(organization.folders, key = { it.folderId }) { folder ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(folder.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { renameFolder = folder }, enabled = canManage) { Icon(Icons.Default.Edit, "重命名文件夹 ${folder.name}") }
                            IconButton(onClick = { deleteFolder = folder }, enabled = canManage) { Icon(Icons.Default.DeleteOutline, "删除文件夹 ${folder.name}") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { createFolder = true }, enabled = canManage) { Text("新建文件夹") } },
            dismissButton = { TextButton(onClick = { showFolders = false }) { Text("完成") } },
        )
    }
    if (createFolder) NameDialog("新建文件夹", "文件夹名称", "", onDismiss = { createFolder = false }) {
        actions.createFolder(it); createFolder = false
    }
    renameFolder?.let { folder ->
        NameDialog("重命名文件夹", "文件夹名称", folder.name, onDismiss = { renameFolder = null }) {
            actions.renameFolder(folder.folderId, it); renameFolder = null
        }
    }
    deleteFolder?.let { folder ->
        ConfirmDeleteDialog("删除文件夹", "删除“${folder.name}”后，其中的会话将移到未分类。", onDismiss = { deleteFolder = null },
            onConfirm = { actions.deleteFolder(folder.folderId); deleteFolder = null })
    }
}

@Composable
private fun ConversationBrowserRow(
    thread: ConversationSummary,
    selected: Boolean,
    folder: String?,
    busy: Boolean,
    canManage: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onClassify: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val details = listOfNotNull(if (busy) "任务进行中" else null, if (thread.archived) "已归档" else null, folder)
    ListItem(
        headlineContent = { Text(thread.title.ifBlank { "未命名对话" }, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details.isNotEmpty()) ({ Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
        leadingContent = { Icon(if (canManage) Icons.Default.DragHandle else Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "管理会话 ${thread.title.ifBlank { "未命名对话" }}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名对话") }, enabled = canManage, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("移动到文件夹") }, enabled = canManage, onClick = { menu = false; onClassify() })
                    DropdownMenuItem(text = { Text(if (thread.archived) "取消归档" else "归档对话") }, enabled = canManage && !busy,
                        onClick = { menu = false; onArchive() })
                    DropdownMenuItem(text = { Text("删除对话", color = MaterialTheme.colorScheme.error) }, enabled = canManage && !busy,
                        onClick = { menu = false; onDelete() })
                    if (busy) Text("任务结束后可归档或删除", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.Medium)).clickable(onClick = onOpen),
    )
}

@Composable
private fun NameDialog(title: String, label: String, initialValue: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by rememberSaveable(title, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun orderConversationRows(rows: List<ConversationSummary>, organization: ConversationOrganization): List<ConversationSummary> {
    val latest = compareByDescending<ConversationSummary> { it.updatedAt }.thenBy { it.id.value }
    if (organization.sortMode != ConversationSortMode.CUSTOM) return rows.sortedWith(latest)
    val positions = organization.customOrder.withIndex().associate { it.value to it.index }
    return rows.sortedWith(compareBy<ConversationSummary> { positions[it.id.value] ?: Int.MAX_VALUE }.then(latest))
}

/** Reordering a filtered folder preserves the positions of conversations outside that view. */
internal fun reorderVisibleConversationIds(allIds: List<String>, visibleIds: List<String>): List<String> {
    val retained = visibleIds.filter { it in allIds }.distinct()
    val moving = retained.toSet()
    val iterator = retained.iterator()
    return allIds.map { if (it in moving) iterator.next() else it }
}
