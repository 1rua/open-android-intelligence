package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationSortMode
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.resources.R as UiR
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 抽屉中按当前 Agent 下的工作区分类展示的单组工作区及会话列表。 */
data class WorkspaceConversationGroup(
    val workspaceKey: String,
    val title: String,
    val subtitle: String? = null,
    val isActive: Boolean = false,
    val threads: Loadable<List<ConversationSummary>> = Loadable.Empty,
)

/** 会话菜单的 Agent / 工作区分层浏览状态与交互契约。 */
data class WorkspaceMenuState(
    val agentTitle: String? = null,
    val groups: List<WorkspaceConversationGroup> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: Boolean = false,
    val onHydrate: () -> Unit = {},
    val onRefreshCatalog: () -> Unit = {},
    val onOpenWorkspaceThread: (workspaceKey: String, threadId: String) -> Unit = { _, _ -> },
    val onCreateWorkspaceThread: (workspaceKey: String) -> Unit = {},
)

private const val WORKSPACE_FILTER_ALL = "all"
private const val WORKSPACE_FILTER_ARCHIVED = "archived"

private fun workspaceThreadRowKey(workspaceKey: String, threadId: String): String =
    "ws-row:$workspaceKey:$threadId"

/**
 * 基于工作区分组的可折叠会话浏览器：
 * 彻底移除旧的自定义文件夹创建/管理/移动入口，自动展示当前 Agent 下的全部工作区与下属会话，
 * 支持单工作区折叠/展开、按工作区新建、跨工作区切换、排序与当前工作区拖拽调序及会话删除。
 */
@Composable
fun WorkspaceConversationBrowser(
    workspaceMenu: WorkspaceMenuState,
    activeThreadId: String?,
    onOpenThread: (String) -> Unit,
    onCreateThread: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    organization: ConversationOrganization = ConversationOrganization(),
    managementSupported: Boolean = false,
    deleteSupported: Boolean = managementSupported,
    archiveSupported: Boolean = managementSupported,
    unarchiveSupported: Boolean = archiveSupported,
    renameSupported: Boolean = managementSupported,
    managing: Boolean = false,
    online: Boolean = true,
    busyThreadIds: Set<String> = emptySet(),
    actions: ConversationBrowserActions = ConversationBrowserActions(),
) {
    LocalWorkbenchCompositionObserver.current?.invoke("workspace_browser")
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(WORKSPACE_FILTER_ALL) }
    var showFilter by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var collapsedKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    val canManage = managementSupported && online && !managing
    val defaultTitle = stringResource(UiR.string.conv_title_new_default)
    val moveUpLabel = stringResource(UiR.string.conv_browser_action_move_up)
    val moveDownLabel = stringResource(UiR.string.conv_browser_action_move_down)
    val listState = rememberLazyListState()

    val activeGroup = workspaceMenu.groups.firstOrNull { it.isActive }
    val activeWorkspaceKey = activeGroup?.workspaceKey
    val activeOrdered = remember(activeGroup?.threads, organization) {
        orderConversationRows((activeGroup?.threads as? Loadable.Ready)?.value.orEmpty(), organization)
    }
    val activeFiltered = remember(activeOrdered, query, filter, defaultTitle) {
        activeOrdered.filter { thread ->
            val archiveMatched = if (filter == WORKSPACE_FILTER_ARCHIVED) thread.archived else !thread.archived
            archiveMatched && (
                query.isEmpty() ||
                    ConversationTitleResolver.resolve(thread.title, defaultTitle).contains(query, ignoreCase = true)
                )
        }
    }
    val visibleRowsByWorkspace = remember(workspaceMenu.groups, activeFiltered, query, filter, defaultTitle) {
        workspaceMenu.groups.associate { group ->
            val rows = if (group.isActive) {
                activeFiltered
            } else {
                (group.threads as? Loadable.Ready)?.value.orEmpty()
                    .asSequence()
                    .filter { thread ->
                        val archiveMatched = if (filter == WORKSPACE_FILTER_ARCHIVED) thread.archived else !thread.archived
                        archiveMatched && (
                            query.isEmpty() ||
                                ConversationTitleResolver.resolve(thread.title, defaultTitle).contains(query, ignoreCase = true)
                            )
                    }
                    .sortedWith(compareByDescending<ConversationSummary> { it.updatedAt }.thenBy { it.id.value })
                    .toList()
            }
            group.workspaceKey to rows
        }
    }
    val activeRowsById = remember(activeOrdered) { activeOrdered.associateBy { it.id.value } }

    var draggedId by remember { mutableStateOf<String?>(null) }
    var previewIds by remember { mutableStateOf<List<String>?>(null) }
    var originalIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragStartOffset by remember { mutableFloatStateOf(0f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }

    val currentOrdered by rememberUpdatedState(activeOrdered)
    val currentFiltered by rememberUpdatedState(activeFiltered)
    val currentReorder by rememberUpdatedState(actions.reorder)
    val currentCanManage by rememberUpdatedState(canManage)
    val currentActiveWorkspaceKey by rememberUpdatedState(activeWorkspaceKey)

    fun cancelDrag() {
        draggedId = null
        previewIds = null
        dragDistance = 0f
    }

    fun reorderUnderPointer() {
        val wsKey = currentActiveWorkspaceKey ?: return
        val id = draggedId ?: return
        val ids = previewIds ?: return
        val center = dragStartOffset + dragDistance + dragHeight / 2f
        val prefix = "ws-row:$wsKey:"
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val rawKey = item.key as? String ?: return@firstOrNull false
            if (!rawKey.startsWith(prefix)) return@firstOrNull false
            val itemId = rawKey.removePrefix(prefix)
            itemId in ids && itemId != id && center >= item.offset && center <= item.offset + item.size
        }?.key as? String ?: return
        val targetId = target.removePrefix(prefix)
        val from = ids.indexOf(id)
        val to = ids.indexOf(targetId)
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
    LaunchedEffect(canManage, query, filter, activeWorkspaceKey) {
        if (!canManage || draggedId != null) cancelDrag()
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("workspace-conversation-browser"),
        state = listState,
        contentPadding = PaddingValues(
            start = Dimensions.SpaceCompact,
            top = Dimensions.SpaceSmall,
            end = Dimensions.SpaceCompact,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        item(key = "ws-browser-search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(UiR.string.conv_browser_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(UiR.string.conv_browser_search_clear_cd),
                            )
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(AppRadius.Medium),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "ws-browser-controls") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Dimensions.SpaceTiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { showFilter = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(
                            text = stringResource(
                                if (filter == WORKSPACE_FILTER_ARCHIVED) UiR.string.conv_browser_filter_archived
                                else UiR.string.conv_browser_filter_all
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = showFilter, onDismissRequest = { showFilter = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.conv_browser_filter_all)) },
                            onClick = { filter = WORKSPACE_FILTER_ALL; showFilter = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.conv_browser_filter_archived)) },
                            onClick = { filter = WORKSPACE_FILTER_ARCHIVED; showFilter = false },
                        )
                    }
                }
                Box {
                    TextButton(
                        onClick = { showSort = true },
                        enabled = canManage,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.testTag("workspace-browser-sort-button"),
                    ) {
                        Text(
                            text = stringResource(
                                if (organization.sortMode == ConversationSortMode.CUSTOM) {
                                    UiR.string.conv_browser_sort_custom
                                } else {
                                    UiR.string.conv_browser_sort_recent
                                }
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(UiR.string.conv_browser_sort_cd),
                            modifier = Modifier.size(Dimensions.SmallIcon),
                        )
                    }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.conv_browser_sort_by_update_desc)) },
                            onClick = { showSort = false; actions.sort(ConversationSortMode.UPDATED_DESC) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.conv_browser_sort_custom)) },
                            onClick = { showSort = false; actions.sort(ConversationSortMode.CUSTOM) },
                        )
                    }
                }
                IconButton(onClick = { workspaceMenu.onRefreshCatalog(); onRefresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(UiR.string.conv_browser_refresh_cd))
                }
                IconButton(onClick = onCreateThread, enabled = online) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(UiR.string.conv_browser_new_session_cd))
                }
            }
        }

        item(key = "ws-browser-hint") {
            val subtitle = workspaceMenu.agentTitle?.takeIf { it.isNotBlank() }
                ?: stringResource(UiR.string.conv_workspace_header_hint)
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val managementHint = when {
                    !managementSupported -> stringResource(UiR.string.conv_browser_mgmt_unsupported)
                    !online -> stringResource(UiR.string.conv_browser_mgmt_need_online)
                    managing -> stringResource(UiR.string.conv_browser_mgmt_saving)
                    else -> stringResource(UiR.string.conv_browser_mgmt_drag_hint)
                }
                Text(
                    text = managementHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (workspaceMenu.catalogLoading && workspaceMenu.groups.isEmpty()) {
            item(key = "ws-catalog-loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon))
                }
            }
        } else if (workspaceMenu.catalogError && workspaceMenu.groups.isEmpty()) {
            item(key = "ws-catalog-failed") {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(UiR.string.conv_workspace_catalog_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = workspaceMenu.onRefreshCatalog) {
                        Text(stringResource(UiR.string.common_action_retry))
                    }
                }
            }
        } else if (workspaceMenu.groups.isEmpty()) {
            item(key = "ws-catalog-empty") {
                Text(
                    text = stringResource(UiR.string.conv_workspace_catalog_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
        }

        workspaceMenu.groups.forEach { group ->
            val collapsed = group.workspaceKey in collapsedKeys
            val visibleRows = visibleRowsByWorkspace[group.workspaceKey].orEmpty()
            val displayedRows = if (group.isActive) {
                previewIds?.mapNotNull { id -> activeRowsById[id] } ?: visibleRows
            } else {
                visibleRows
            }

            item(key = "ws-header:${group.workspaceKey}") {
                WorkspaceGroupHeader(
                    group = group,
                    visibleCount = visibleRows.size,
                    collapsed = collapsed,
                    online = online,
                    onToggleCollapse = {
                        collapsedKeys = if (collapsed) collapsedKeys - group.workspaceKey else collapsedKeys + group.workspaceKey
                    },
                    onCreateInWorkspace = { workspaceMenu.onCreateWorkspaceThread(group.workspaceKey) },
                )
            }

            if (!collapsed) {
                when (group.threads) {
                    Loadable.Idle, Loadable.Loading -> item(key = "ws-loading:${group.workspaceKey}") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(Dimensions.SmallIcon), strokeWidth = Dimensions.StrokeStitch)
                        }
                    }
                    is Loadable.Failed -> item(key = "ws-failed:${group.workspaceKey}") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(UiR.string.conv_browser_load_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                if (group.isActive) onRefresh() else workspaceMenu.onRefreshCatalog()
                            }) {
                                Text(stringResource(UiR.string.common_action_retry))
                            }
                        }
                    }
                    else -> if (displayedRows.isEmpty()) {
                        item(key = "ws-empty:${group.workspaceKey}") {
                            val emptyText = when {
                                query.isNotBlank() -> stringResource(UiR.string.conv_browser_empty_search)
                                filter == WORKSPACE_FILTER_ARCHIVED -> stringResource(UiR.string.conv_browser_empty_archived)
                                else -> stringResource(UiR.string.conv_workspace_no_sessions)
                            }
                            Text(
                                text = emptyText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 28.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                            )
                        }
                    } else {
                        items(
                            items = displayedRows,
                            key = { thread -> workspaceThreadRowKey(group.workspaceKey, thread.id.value) },
                        ) { thread ->
                            val id = thread.id.value
                            val isActiveThread = group.isActive && id == activeThreadId
                            val busy = group.isActive && id in busyThreadIds
                            val isDragged = group.isActive && id == draggedId
                            val dragOffset = if (isDragged) dragDistance else 0f
                            val rowModifier = Modifier
                                .testTag("workspace-thread:${group.workspaceKey}:$id")
                                .let { base ->
                                    if (!group.isActive) base
                                    else base
                                        .zIndex(if (isDragged) 1f else 0f)
                                        .offset { IntOffset(0, dragOffset.roundToInt()) }
                                        .semantics {
                                            if (canManage) {
                                                customActions = listOf(-1 to moveUpLabel, 1 to moveDownLabel).map { (delta, label) ->
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
                                        .pointerInput(id, canManage, group.workspaceKey) {
                                            if (canManage) {
                                                val itemKey = workspaceThreadRowKey(group.workspaceKey, id)
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
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
                                            }
                                        }
                                }
                            WorkspaceThreadRow(
                                thread = thread,
                                selected = isActiveThread,
                                busy = busy,
                                showManageMenu = group.isActive,
                                canReorder = group.isActive && canManage,
                                canRename = group.isActive && canManage && renameSupported,
                                canArchive = group.isActive && canManage && archiveSupported && (!thread.archived || unarchiveSupported),
                                canDelete = group.isActive && canManage && deleteSupported,
                                deleteSupported = deleteSupported,
                                relativeTime = relativeThreadTime(thread.updatedAt, now),
                                onOpen = {
                                    if (group.isActive) onOpenThread(id)
                                    else workspaceMenu.onOpenWorkspaceThread(group.workspaceKey, id)
                                },
                                onRename = { renameTarget = thread },
                                onArchive = { actions.archive(id, !thread.archived) },
                                onDelete = { deleteTarget = thread },
                                modifier = rowModifier,
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { thread ->
        var value by rememberSaveable(thread.id.value, thread.title) { mutableStateOf(thread.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(UiR.string.conv_browser_dialog_rename_thread_title)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(UiR.string.conv_browser_dialog_rename_thread_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.rename(thread.id.value, value.trim())
                        renameTarget = null
                    },
                    enabled = value.isNotBlank(),
                ) {
                    Text(stringResource(UiR.string.common_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(UiR.string.common_action_cancel))
                }
            },
        )
    }

    deleteTarget?.let { thread ->
        val displayTitle = resolveDisplayConversationTitle(thread.title)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(UiR.string.conv_browser_dialog_delete_thread_title)) },
            text = { Text(stringResource(UiR.string.conv_browser_dialog_delete_thread_message, displayTitle)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.delete(thread.id.value)
                        deleteTarget = null
                    },
                ) {
                    Text(
                        text = stringResource(UiR.string.common_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(UiR.string.common_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkspaceGroupHeader(
    group: WorkspaceConversationGroup,
    visibleCount: Int,
    collapsed: Boolean,
    online: Boolean,
    onToggleCollapse: () -> Unit,
    onCreateInWorkspace: () -> Unit,
) {
    Surface(
        color = if (group.isActive) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(AppRadius.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimensions.SpaceTiny)
            .clip(RoundedCornerShape(AppRadius.Medium)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleCollapse)
                .testTag("workspace-group-header:${group.workspaceKey}")
                .padding(start = Dimensions.SpaceSmall, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (collapsed) UiR.string.conv_workspace_expand_cd else UiR.string.conv_workspace_collapse_cd,
                    group.title,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (group.isActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (group.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (group.isActive) {
                        Text(
                            text = stringResource(UiR.string.conv_workspace_badge_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(UiR.string.conv_browser_session_count, visibleCount),
                        style = AppTextStyles.MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                group.subtitle?.takeIf { it.isNotBlank() }?.let { path ->
                    Text(
                        text = path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onCreateInWorkspace,
                enabled = online,
                modifier = Modifier.testTag("workspace-group-create:${group.workspaceKey}"),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(UiR.string.conv_workspace_new_session_cd, group.title),
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceThreadRow(
    thread: ConversationSummary,
    selected: Boolean,
    busy: Boolean,
    showManageMenu: Boolean,
    canReorder: Boolean,
    canRename: Boolean,
    canArchive: Boolean,
    canDelete: Boolean,
    deleteSupported: Boolean,
    relativeTime: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val indicatorColor = MaterialTheme.colorScheme.primary
    val title = resolveDisplayConversationTitle(thread.title)
    val details = listOfNotNull(
        if (busy) stringResource(UiR.string.conv_browser_item_busy_status) else null,
        if (thread.archived) stringResource(UiR.string.conv_browser_filter_archived) else null,
        relativeTime.takeIf { it.isNotEmpty() },
    )

    ListItem(
        headlineContent = {
            Text(
                text = title,
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
        } else null,
        leadingContent = if (canReorder) {
            {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
            }
        } else null,
        trailingContent = if (showManageMenu) {
            {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(UiR.string.conv_browser_item_manage_cd, title),
                            modifier = Modifier.size(Dimensions.SmallIcon),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.conv_browser_menu_rename)) },
                            enabled = canRename,
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (thread.archived) UiR.string.conv_browser_menu_unarchive
                                        else UiR.string.conv_browser_menu_archive
                                    )
                                )
                            },
                            enabled = canArchive && !busy,
                            onClick = { menu = false; onArchive() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(UiR.string.conv_browser_menu_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            enabled = canDelete && !busy,
                            onClick = { menu = false; onDelete() },
                        )
                        if (!deleteSupported) {
                            Text(
                                text = stringResource(UiR.string.conv_browser_agent_delete_unsupported),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        if (busy) {
                            Text(
                                text = stringResource(UiR.string.conv_browser_agent_busy_action_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp)
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
                onClickLabel = stringResource(UiR.string.conv_browser_action_open_thread),
                onClick = onOpen,
            ),
    )
}
