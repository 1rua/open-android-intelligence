package com.openandroidintelligence.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.LocalAppStateColors

/** Native session UI shares only the authenticated Core transport with device plugins. */
@Composable
internal fun NativeAgentSessionsScreen(
    controller: NativeAgentsController,
    runtime: GatewayRuntime,
    username: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val profiles by runtime.savedProfiles.collectAsState()
    var accountsVisible by remember { mutableStateOf(false) }
    var renameVisible by remember(state.selectedAgentId, state.selectedSessionId) { mutableStateOf(false) }
    var deleteVisible by remember(state.selectedAgentId, state.selectedSessionId) { mutableStateOf(false) }
    var title by remember(state.selectedAgentId, state.selectedSessionId) { mutableStateOf("") }
    val agent = state.agent
    val capabilities = agent?.capabilities
    val writable = agent?.online == true && !state.busy

    val stateColors = LocalAppStateColors.current

    Column(modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { accountsVisible = true }, enabled = !state.busy) { Text("OAI · $username") }
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.agents.forEach { entry ->
                val statusText = if (entry.online) "在线" else "离线"
                val dotColor = if (entry.online) stateColors.success else MaterialTheme.colorScheme.outlineVariant
                FilterChip(
                    selected = entry.id == state.selectedAgentId,
                    onClick = { controller.selectAgent(entry.id) },
                    enabled = !state.busy,
                    modifier = Modifier.semantics {
                        contentDescription = "${entry.name}，$statusText"
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(dotColor, CircleShape)
                        )
                    },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(entry.name)
                            Text(
                                text = "· $statusText",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (entry.online) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                )
            }
            TextButton(onClick = controller::refresh, enabled = !state.busy) { Text("刷新") }
        }
        state.error?.let { code ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (code == "OUTCOME_UNKNOWN") "回复流已中断，执行结果暂不确定。请刷新原生历史后再决定是否重试。"
                        else "原生会话操作未完成：$code", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = controller::dismissError) { Text("关闭提示") }
                }
            }
        }
        if (state.agents.isEmpty()) {
            Text(if (state.error == null) "此 OAI Core 尚未接入 Agent。" else "Agent 列表暂不可用，请刷新重试。",
                style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        agent?.let {
            Text(if (it.sessionScope == "account") "此 Agent 的会话按当前 OAI 账号隔离。"
                else "此 Core 已接入的共享原生会话，所有已认证 OAI 账号均可访问。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.selectedSessionId == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("原生会话", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = controller::createSession,
                    enabled = writable && capabilities?.create == true) { Text("新建会话") }
            }
            if (capabilities?.list != true) Text("此 Agent 未提供会话列表能力。")
            else if (state.sessions.isEmpty() && !state.loadingSessions && state.error == null) Text("没有可读取的原生会话。")
            if (state.loadingSessions) CircularProgressIndicator()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sessions, key = { it.id }) { session ->
                    Card(onClick = { controller.selectSession(session.id) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(session.title?.takeIf(String::isNotBlank) ?: "新对话",
                                style = MaterialTheme.typography.titleMedium)
                            session.updatedAt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (session.archived) Text("已归档", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { controller.leaveSession() }, enabled = !state.busy) { Text("返回会话") }
                TextButton(onClick = { title = state.session?.title.orEmpty(); renameVisible = true },
                    enabled = writable && capabilities?.rename == true) { Text("重命名") }
                TextButton(onClick = controller::archiveSession,
                    enabled = writable && capabilities?.archive == true) { Text("归档") }
                TextButton(onClick = { deleteVisible = true },
                    enabled = writable && capabilities?.delete == true) { Text("删除") }
            }
            Text(state.session?.title?.takeIf(String::isNotBlank) ?: "新对话",
                style = MaterialTheme.typography.titleMedium)
            if (capabilities?.history != true) Text("此 Agent 未提供历史读取能力。")
            if (state.loadingHistory) CircularProgressIndicator()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.messages, key = { it.id }) { message ->
                    Column {
                        Text(message.role, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (state.streamingText.isNotEmpty()) item(key = "native-live-response") {
                    Column {
                        Text("${agent?.name.orEmpty()} · ${if (state.cancelled) "已取消" else if (state.sending) "回复中" else if (state.streamCompleted) "回复已完成" else "未完成回复"}",
                            style = MaterialTheme.typography.labelMedium)
                        Text(state.streamingText, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (capabilities?.send != true || capabilities.stream != true) Text("此 Agent 未提供可用的流式消息发送能力。")
            OutlinedTextField(value = state.draft, onValueChange = controller::editDraft,
                enabled = writable && capabilities?.send == true && capabilities.stream,
                label = { Text("消息") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.busy) CircularProgressIndicator(Modifier.padding(12.dp))
                if (capabilities?.cancel == true) TextButton(onClick = controller::cancelGeneration,
                    enabled = agent?.online == true && state.sending && state.generationId != null && !state.cancelRequested && !state.cancelled) { Text("停止生成") }
                Button(onClick = controller::send,
                    enabled = writable && capabilities?.send == true && capabilities.stream && state.draft.isNotBlank()) {
                    Text("发送")
                }
            }
        }
    }

    if (renameVisible) AlertDialog(onDismissRequest = { renameVisible = false }, title = { Text("重命名原生会话") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }) },
        confirmButton = { TextButton(onClick = { controller.renameSession(title); renameVisible = false },
            enabled = title.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renameVisible = false }) { Text("取消") } })
    if (deleteVisible) AlertDialog(onDismissRequest = { deleteVisible = false }, title = { Text("删除原生会话？") },
        text = { Text("这会调用 ${agent?.name.orEmpty()} 的原生删除接口。") },
        confirmButton = { TextButton(onClick = { controller.deleteSession(); deleteVisible = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteVisible = false }) { Text("取消") } })
    if (accountsVisible) AlertDialog(onDismissRequest = { accountsVisible = false }, title = { Text("OAI 账号") },
        text = {
            Column {
                profiles.forEach { profile ->
                    TextButton(onClick = { accountsVisible = false; runtime.selectSavedAccount(profile.localProfileId) }) {
                        Text("${profile.username} · ${profile.gatewayBaseUrl}")
                    }
                }
                TextButton(onClick = { accountsVisible = false; runtime.logout(revokeRefresh = true) }) { Text("退出当前账号") }
            }
        }, confirmButton = { TextButton(onClick = { accountsVisible = false }) { Text("关闭") } })
}
