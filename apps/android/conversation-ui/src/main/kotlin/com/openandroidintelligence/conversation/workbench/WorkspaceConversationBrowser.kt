package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.resources.R as UiR
import kotlinx.coroutines.delay

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

/**
 * 基于工作区分组的可折叠会话浏览器：
 * 彻底移除旧的自定义文件夹创建/管理/移动入口，自动展示当前 Agent 下的全部工作区与下属会话，
 * 支持单工作区折叠/展开、按工作区新建与跨工作区直接切换会话。
 */
@Composable
fun WorkspaceConversationBrowser(
    workspaceMenu: WorkspaceMenuState,
    activeThreadId: String?,
    onOpenThread: (String) -> Unit,
    onCreateThread: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    managementSupported: Boolean = false,
    archiveSupported: Boolean = managementSupported,
    unarchiveSupported: Boolean = archiveSupported,
    renameSupported: Boolean = managementSupported,
    managing: Boolean = false,
    online: Boolean = true,
    busyThreadIds: Set<String> = emptySet(),
    actions: ConversationBrowserActions = ConversationBrowserActions(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(WORKSPACE_FILTER_ALL) }
    var showFilter by remember { mutableStateOf(false) }
    var collapsedKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    val canManage = managementSupported && online && !managing
    val defaultTitle = stringResource(UiR.string.conv_title_new_default)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("workspace-conversation-browser"),
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
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
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
            val readyRows = (group.threads as? Loadable.Ready)?.value.orEmpty()
                .sortedWith(compareByDescending<ConversationSummary> { it.updatedAt }.thenBy { it.id.value })
            val visibleRows = readyRows.filter { thread ->
                ConversationTitleResolver.resolve(thread.title, defaultTitle).contains(query, ignoreCase = true) &&
                    if (filter == WORKSPACE_FILTER_ARCHIVED) thread.archived else !thread.archived
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
                    else -> if (visibleRows.isEmpty()) {
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
                            items = visibleRows,
                            key = { thread -> "ws-row:${group.workspaceKey}:${thread.id.value}" },
                        ) { thread ->
                            val id = thread.id.value
                            val isActiveThread = group.isActive && id == activeThreadId
                            val busy = group.isActive && id in busyThreadIds
                            WorkspaceThreadRow(
                                thread = thread,
                                selected = isActiveThread,
                                busy = busy,
                                showManageMenu = group.isActive,
                                canRename = group.isActive && canManage && renameSupported,
                                canArchive = group.isActive && canManage && archiveSupported && (!thread.archived || unarchiveSupported),
                                relativeTime = relativeThreadTime(thread.updatedAt, now),
                                onOpen = {
                                    if (group.isActive) onOpenThread(id)
                                    else workspaceMenu.onOpenWorkspaceThread(group.workspaceKey, id)
                                },
                                onRename = { renameTarget = thread },
                                onArchive = { actions.archive(id, !thread.archived) },
                                modifier = Modifier.testTag("workspace-thread:${group.workspaceKey}:$id"),
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
    canRename: Boolean,
    canArchive: Boolean,
    relativeTime: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
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
