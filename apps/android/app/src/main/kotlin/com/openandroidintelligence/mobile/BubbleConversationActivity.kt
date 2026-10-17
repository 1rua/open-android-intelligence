package com.openandroidintelligence.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.workbench.TimelineRow

/** The system embeds this Activity inside a native conversation bubble. */
class BubbleConversationActivity : ComponentActivity() {
    private var target by mutableStateOf<ReplyNotificationTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = targetFromIntent(intent)
        val runtime = (application as? OpenAndroidIntelligenceApplication)?.gatewayRuntime
        setContent {
            OpenAndroidIntelligenceTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (runtime == null) BubbleUnavailable("Gateway 运行环境不可用", ::finish)
                    else BubbleConversationContent(runtime, target, ::finish)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = targetFromIntent(intent)
    }

    companion object {
        private const val ACTION = "com.openandroidintelligence.mobile.OPEN_CONVERSATION_BUBBLE"
        private const val PROFILE = "bubble_profile_id"
        private const val ACCOUNT = "bubble_account_id"
        private const val CONVERSATION = "bubble_conversation_id"

        internal fun intent(context: Context, target: ReplyNotificationTarget): Intent =
            Intent(context, BubbleConversationActivity::class.java)
                .setAction(ACTION)
                .setData(Uri.Builder().scheme("oai").authority("bubble").appendPath("conversation")
                    .appendPath(target.profileId).appendPath(target.accountId).appendPath(target.conversationId).build())
                .putExtra(PROFILE, target.profileId).putExtra(ACCOUNT, target.accountId)
                .putExtra(CONVERSATION, target.conversationId)

        internal fun targetFromIntent(intent: Intent): ReplyNotificationTarget? {
            val uri = intent.data ?: return null
            if (intent.action != ACTION || uri.scheme != "oai" || uri.host != "bubble" ||
                uri.query != null || uri.fragment != null) return null
            val parts = uri.pathSegments
            if (parts.size != 4 || parts[0] != "conversation" || parts.drop(1).any(String::isBlank)) return null
            val target = ReplyNotificationTarget(parts[1], parts[2], parts[3])
            if (intent.getStringExtra(PROFILE) != target.profileId || intent.getStringExtra(ACCOUNT) != target.accountId ||
                intent.getStringExtra(CONVERSATION) != target.conversationId) return null
            return target
        }
    }
}

/** The tapped conversation remains reachable when it finishes or the process loses its projection. */
internal fun bubbleSessionRows(
    sessions: List<NativeConversationSession>, target: ReplyNotificationTarget,
): List<NativeConversationSession> = sessions
    .filter { it.target.profileId == target.profileId && it.target.accountId == target.accountId &&
        (it.isRunning || it.target == target) }
    .distinctBy { it.target }
    .sortedWith(compareByDescending<NativeConversationSession> { it.isRunning }.thenByDescending { it.updatedAt })

@Composable
private fun BubbleConversationContent(runtime: GatewayRuntime, target: ReplyNotificationTarget?, onClose: () -> Unit) {
    val phase by runtime.phase.collectAsState()
    val sessions by runtime.nativeConversationNotifications.sessions.collectAsState()
    // A credential refresh replaces the session even when Connected's display fields stay equal.
    val activeSession by runtime.controller.collectAsState()
    LaunchedEffect(runtime) { runtime.restoreSessionIfAvailable() }
    val valid = target != null && runtime.isBubbleTargetCurrent(target)
    var selectedId by rememberSaveable(target?.profileId, target?.accountId, target?.conversationId) { mutableStateOf<String?>(null) }
    val selectedTarget = target?.let { current -> selectedId?.let { current.copy(conversationId = it) } }
    var opened by remember(selectedTarget, activeSession, valid) { mutableStateOf(false) }
    val controller by produceState<WorkbenchController?>(null, selectedTarget, activeSession, valid) {
        val created = if (valid && selectedTarget != null) runtime.createBubbleConversation(selectedTarget) else null
        value = created
        opened = true
        awaitDispose { if (created != null) runtime.releaseBubbleConversation(created) }
    }

    if (!valid) {
        val message = when {
            target == null -> "会话链接无效"
            phase == ConnectionPhase.Negotiating || phase == ConnectionPhase.Authenticating -> "正在连接 Gateway…"
            phase is ConnectionPhase.Connected -> "此气泡属于其他账号，无法读取或回复"
            phase is ConnectionPhase.Failed -> "Gateway 连接失败，请在应用中重新连接"
            else -> "Gateway 尚未连接，请在应用中连接后重试"
        }
        BubbleUnavailable(message, onClose)
        return
    }
    val launchTarget = target ?: return
    val rows = bubbleSessionRows(sessions, launchTarget)
    val selected = sessions.firstOrNull { it.target == selectedTarget }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectedId != null) TextButton(onClick = { selectedId = null }) { Text("会话列表") }
            Text(if (selectedId == null) "运行中的会话" else selected?.title ?: "Agent 会话",
                modifier = Modifier.weight(1f).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("关闭") }
        }
        HorizontalDivider()
        if (selectedId == null) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rows.none { it.isRunning }) item { Text("当前没有正在运行的会话", style = MaterialTheme.typography.bodyMedium) }
                items(rows, key = { it.target.conversationId }) { session ->
                    BubbleSessionCard(session.title, session.status.label(), session.latestReply) { selectedId = session.target.conversationId }
                }
                if (rows.none { it.target == launchTarget }) item {
                    BubbleSessionCard("通知会话", "查看此通知对应的会话", "") { selectedId = launchTarget.conversationId }
                }
            }
        } else {
            val chat = controller?.takeIf { it.state.value.activeThreadId == selectedId }
            if (!opened) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (chat == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("会话连接已失效，请返回列表重试") }
            else key(chat) { BubbleChat(chat, selected?.status, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun BubbleSessionCard(title: String, status: String, reply: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (reply.isNotBlank()) Text(reply, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BubbleChat(controller: WorkbenchController, status: NativeConversationStatus?, modifier: Modifier) {
    val state by controller.state.collectAsState()
    val entries = (state.timeline as? Loadable.Ready)?.value.orEmpty()
    val list = rememberLazyListState()
    val followLatest by remember { derivedStateOf {
        val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
        lastVisible >= list.layoutInfo.totalItemsCount - 2
    } }
    LaunchedEffect(entries.lastOrNull()?.key, entries.lastOrNull()?.text) {
        if (entries.isNotEmpty() && followLatest && !list.isScrollInProgress) list.animateScrollToItem(entries.lastIndex)
    }
    Column(modifier.fillMaxWidth()) {
        if (status != null) Text(status.label(), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        when (val timeline = state.timeline) {
            Loadable.Idle, Loadable.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Loadable.Failed -> Column(Modifier.weight(1f).padding(12.dp)) {
                Text("读取会话失败：${timeline.code}")
                TextButton(onClick = controller::onForegrounded) { Text("重试") }
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entries.isEmpty()) item { Text("此会话暂无消息") }
                items(entries, key = { it.key }) { entry ->
                    TimelineRow(entry, controller::decideApproval, onDownloadAttachment = controller::downloadAttachment)
                }
            }
        }
        state.notice?.let { notice ->
            Text(notice, Modifier.fillMaxWidth().padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.draft, controller::editDraft, Modifier.weight(1f), placeholder = { Text("回复此会话") },
                enabled = state.isOnline, maxLines = 3)
            Column {
                if (state.generation in setOf(GenerationState.QUEUED, GenerationState.RUNNING)) {
                    TextButton(onClick = controller::stopGeneration) { Text("停止") }
                }
                Button(onClick = controller::sendDraft, enabled = state.canSend) { Text("发送") }
            }
        }
    }
}

@Composable
private fun BubbleUnavailable(message: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message)
        TextButton(onClick = onClose) { Text("关闭") }
    }
}

private fun NativeConversationStatus.label(): String = when (this) {
    NativeConversationStatus.QUEUED -> "排队中"
    NativeConversationStatus.RUNNING -> "Agent 正在处理"
    NativeConversationStatus.COMPLETED -> "已完成"
    NativeConversationStatus.FAILED -> "任务失败"
    NativeConversationStatus.CANCELLED -> "已取消"
    NativeConversationStatus.DISCONNECTED -> "连接中断，等待恢复"
}
