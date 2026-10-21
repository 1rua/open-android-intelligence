package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.ports.ConversationFolder
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationSortMode
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        selectedFolderId = state.selectedFolderId,
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
            selectFolder = controller::selectFolder,
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
    val selectFolder: (String?) -> Unit = {},
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
    selectedFolderId: String? = null,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(selectedFolderId?.let { FOLDER_PREFIX + it } ?: ALL_THREADS) }
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
    LaunchedEffect(selectedFolderId) {
        if (selectedFolderId != null) filter = FOLDER_PREFIX + selectedFolderId
        else if (filter.startsWith(FOLDER_PREFIX)) filter = ALL_THREADS
    }
    LaunchedEffect(folderId, organization.folders) {
        if (folderId != null && selectedFolder == null) {
            filter = ALL_THREADS
            actions.selectFolder(null)
        }
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
    // 列表首次进入做一次错峰入场（M19）；筛选变化不重放，避免每次搜索都闪一遍。
    var rowsRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { rowsRevealed = true }
    val rowsById = ordered.associateBy { it.id.value }
    val displayed = previewIds?.mapNotNull(rowsById::get) ?: filtered
    val currentOrdered by rememberUpdatedState(ordered)
    val currentFiltered by rememberUpdatedState(filtered)
    val currentReorder by rememberUpdatedState(actions.reorder)
    val currentCanManage by rememberUpdatedState(canManage)
    // 相对时间每秒推进一次：抽屉/气泡列表常驻时，"刚刚 / N 分钟前" 必须继续走，
    // 否则一整段会话都停在进入那一刻的文案。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

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
        contentPadding = PaddingValues(
            start = Dimensions.SpaceCompact,
            top = Dimensions.SpaceSmall,
            end = Dimensions.SpaceCompact,
            bottom = DrawerListBottomSpace,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        item(key = "browser-search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索会话…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清除搜索")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(AppRadius.Medium),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "browser-controls") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Dimensions.SpaceTiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { showFilter = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(Dimensions.SmallIcon))
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
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                filter = value; actions.selectFolder(null); showFilter = false
                            })
                        }
                        organization.folders.forEach { folder ->
                            DropdownMenuItem(text = { Text(folder.name) }, onClick = {
                                filter = FOLDER_PREFIX + folder.folderId
                                actions.selectFolder(folder.folderId); showFilter = false
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("已归档") }, onClick = {
                            filter = ARCHIVED_THREADS; actions.selectFolder(null); showFilter = false
                        })
                    }
                }
                IconButton(onClick = { showFolders = true }, enabled = managementSupported) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "管理文件夹")
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "刷新会话") }
                IconButton(onClick = onCreateThread, enabled = online) {
                    Icon(Icons.Default.Add, contentDescription = "新建会话")
                }
            }
        }
        item(key = "browser-group-header") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = Dimensions.SpaceSmall)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "会话",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${filtered.size} 个会话",
                        style = AppTextStyles.MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        TextButton(onClick = { showSort = true }, enabled = canManage,
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(if (organization.sortMode == ConversationSortMode.CUSTOM) "自定义顺序" else "最近更新")
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "会话排序", modifier = Modifier.size(Dimensions.SmallIcon))
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
                    modifier = Modifier.padding(bottom = Dimensions.SpaceTiny),
                )
            }
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
        itemsIndexed(displayed, key = { _, thread -> thread.id.value }) { index, thread ->
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
                entranceIndex = index,
                entranceVisible = rowsRevealed,
                relativeTime = relativeThreadTime(thread.updatedAt, now),
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

/**
 * 抽屉/气泡共用的会话行：48–56dp、标题一行省略、右侧相对时间（等宽小字）、
 * 选中态用 `surfaceContainerHighest` + 左侧 3dp 主色指示条，不复用 `secondaryContainer`。
 * 首次进入按 [MotionSpecs.staggerEnter] 错峰淡入 + 上浮（M19）。
 */
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
    entranceIndex: Int = 0,
    entranceVisible: Boolean = true,
    relativeTime: String = "",
) {
    var menu by remember { mutableStateOf(false) }
    val reduced = LocalMotionPolicy.current.reduceMotion
    val entranceSpec = MotionSpecs.staggerEnter<Float>(entranceIndex, reduced)
    val alpha by animateFloatAsState(
        targetValue = if (entranceVisible) 1f else 0f,
        animationSpec = entranceSpec,
        label = "drawer-row-alpha",
    )
    val rise by animateFloatAsState(
        // 减少动态时只保留淡化：位移本身就是要被关掉的那个效果。
        targetValue = if (entranceVisible || reduced) 0f else DrawerRowEnterRise,
        animationSpec = entranceSpec,
        label = "drawer-row-rise",
    )
    val risePx = with(LocalDensity.current) { rise.dp.toPx() }
    val indicatorColor = MaterialTheme.colorScheme.primary
    // 相对时间放在副标题里而不是行尾：抽屉只有 320dp 宽，行尾再占一块等宽时间
    // 会把标题压到 120dp 左右（"Call the open_..." 就是这么被截断的）。
    val details = listOfNotNull(
        if (busy) "任务进行中" else null,
        if (thread.archived) "已归档" else null,
        folder,
        relativeTime.takeIf { it.isNotEmpty() },
    )

    ListItem(
        headlineContent = {
            Text(
                text = thread.title.ifBlank { "未命名对话" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (details.isNotEmpty()) {
            {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        leadingContent = if (canManage) {
            {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
            }
        } else {
            null
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            "管理会话 ${thread.title.ifBlank { "未命名对话" }}",
                            modifier = Modifier.size(Dimensions.SmallIcon),
                        )
                    }
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
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                Color.Transparent
            },
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.Medium))
            .drawWithContent {
                drawContent()
                if (selected) {
                    val barWidth = 3.dp.toPx()
                    drawRoundRect(
                        color = indicatorColor,
                        size = androidx.compose.ui.geometry.Size(barWidth, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth),
                    )
                }
            }
            .bouncyClickable(
                // 列表项不声明 Role.Button：它已经带有 onClick 语义与 onClickLabel，
                // 再叠加 button 角色会让 TalkBack 把「会话行」读成按钮，也会和抽屉里
                // 真正的按钮（筛选、管理）产生语义重复。
                onClickLabel = "打开会话",
                onClick = onOpen,
            )
            .graphicsLayer {
                this.alpha = alpha
                translationY = risePx
            }
            .drawBehind {
                if (selected) {
                    drawRect(color = indicatorColor, size = Size(DrawerSelectionWidth.toPx(), size.height))
                }
            },
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

/**
 * 会话行的相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前，更早的显示日期。
 * 时间戳来自 Gateway 的真实 `updatedAt`（毫秒），没有可读值时返回空串，不编造。
 */
internal fun relativeThreadTime(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    if (updatedAt <= 0L) return ""
    val elapsed = now - updatedAt
    return when {
        elapsed < MINUTE_MILLIS -> "刚刚"
        elapsed < HOUR_MILLIS -> "${elapsed / MINUTE_MILLIS} 分钟前"
        elapsed < DAY_MILLIS -> "${elapsed / HOUR_MILLIS} 小时前"
        elapsed < WEEK_MILLIS -> "${elapsed / DAY_MILLIS} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(updatedAt))
    }
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val WEEK_MILLIS = 7 * DAY_MILLIS

/** 会话行进入时的上浮位移（M19：淡入 + 小位移，不做逐 token 炫技）。 */
private const val DrawerRowEnterRise = 12f

/** 选中行左侧指示条宽度。 */
private val DrawerSelectionWidth = 3.dp

/** 列表底部留白：避免最后一项贴住页脚/导航栏。 */
private val DrawerListBottomSpace = 24.dp
