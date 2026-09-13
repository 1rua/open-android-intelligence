package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.openandroidintelligence.conversation.components.LoadableRegion
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.theme.Dimensions

@Composable
fun ThreadDrawer(gatewayLabel: String, threads: Loadable<List<ConversationSummary>>, activeThreadId: String?,
    onOpenThread: (String) -> Unit, onCreateThread: () -> Unit, onRefresh: () -> Unit,
    onOpenSettings: () -> Unit, onCloseDrawer: () -> Unit, modifier: Modifier = Modifier, showClose: Boolean = true,
    onOpenAttachments: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimensions.SpaceMedium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalStitch(modifier = Modifier.height(Dimensions.MinimumTouchTarget))
                Text("会话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = Dimensions.SpaceSmall))
                if (showClose) IconButton(onClick = onCloseDrawer) { Icon(Icons.Default.Close, "关闭会话列表") }
            }
            Text(gatewayLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = Dimensions.SpaceSmall))
            FilledTonalButton(onClick = onCreateThread, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(Dimensions.SpaceSmall)); Text("新建对话")
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("筛选已加载会话") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.SpaceSmall))
            LoadableRegion(threads, "创建一个对话，与 Agent 开始交流", onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth(), ready = { items ->
                    val filtered = items.filter { it.title.contains(query, ignoreCase = true) }
                    if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有匹配的会话") }
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
                        items(filtered, key = { it.id.value }) { thread ->
                            NavigationDrawerItem(label = { Text(thread.title.ifBlank { "未命名对话" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                selected = thread.id.value == activeThreadId, onClick = { onOpenThread(thread.id.value) },
                                icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) })
                        }
                    }
                })
            HorizontalDivider(Modifier.padding(vertical = Dimensions.SpaceSmall))
            onOpenAttachments?.let { openAttachments ->
                TextButton(onClick = openAttachments, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(Dimensions.SpaceSmall)); Text("附件库")
                }
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(Dimensions.SpaceSmall)); Text("刷新会话") }
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(Dimensions.SpaceSmall)); Text("设置与平台管理") }
        }
    }
}
