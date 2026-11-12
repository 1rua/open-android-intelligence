package com.openandroidintelligence.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.components.readableFailure
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.workbench.AttachmentPreviewHost
import com.openandroidintelligence.conversation.workbench.TimelineDisplayRow
import com.openandroidintelligence.conversation.workbench.rememberExecutionTimingStore
import com.openandroidintelligence.conversation.workbench.rememberTimelineDisplayItems
import com.openandroidintelligence.conversation.workbench.rememberStreamingPresentations
import com.openandroidintelligence.conversation.workbench.ComposerBar
import com.openandroidintelligence.conversation.workbench.ConversationBrowser
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The system embeds this Activity inside a native conversation bubble. */
class BubbleConversationActivity : ComponentActivity() {
    private var target by mutableStateOf<ReplyNotificationTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = targetFromIntent(intent)
        val app = application as? OpenAndroidIntelligenceApplication
        val runtime = app?.gatewayRuntime
        // 系统气泡窗口与主界面共用同一份外观偏好：深浅、动态取色、减少动态都跟随用户设置，
        // 而不是永远跟随系统 + 品牌色。首帧前先把系统栏图标对齐，避免出现反向配色的状态栏。
        applySystemBarContrast(appearanceIsDark(app?.appearancePreferences?.settings?.value ?: AppearanceSettings()))
        setContent {
            val fallbackAppearance = remember { MutableStateFlow(AppearanceSettings()) }
            val appearance by (app?.appearancePreferences?.settings ?: fallbackAppearance).collectAsState()
            val isDark = when (appearance.theme) {
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
            }
            LaunchedEffect(isDark) { applySystemBarContrast(isDark) }
            OpenAndroidIntelligenceTheme(
                darkTheme = isDark,
                dynamicColor = appearance.dynamicColor,
                palette = appearance.palette,
                reduceMotion = appearance.reduceMotion,
                streamingHapticFeedback = appearance.streamingHapticFeedback,
            ) {
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
        private const val AGENT = "bubble_agent_id"

        internal fun intent(context: Context, target: ReplyNotificationTarget): Intent =
            Intent(context, BubbleConversationActivity::class.java)
                .setAction(ACTION)
                .setData(Uri.Builder().scheme("oai").authority("bubble").appendPath("conversation")
                    .appendPath(target.profileId).appendPath(target.accountId).appendPath(target.conversationId)
                    .also { builder -> target.agentId?.let(builder::appendPath) }.build())
                .putExtra(PROFILE, target.profileId).putExtra(ACCOUNT, target.accountId)
                .putExtra(CONVERSATION, target.conversationId)
                .putExtra(AGENT, target.agentId)

        internal fun targetFromIntent(intent: Intent): ReplyNotificationTarget? {
            val uri = intent.data ?: return null
            if (intent.action != ACTION || uri.scheme != "oai" || uri.host != "bubble" ||
                uri.query != null || uri.fragment != null) return null
            val parts = uri.pathSegments
            // conversation/{profile}/{account}/{conversation}[/{agent}]
            if (parts.size !in 4..5 || parts[0] != "conversation" || parts.drop(1).any(String::isBlank)) return null
            val target = ReplyNotificationTarget(parts[1], parts[2], parts[3], parts.getOrNull(4))
            if (intent.getStringExtra(PROFILE) != target.profileId || intent.getStringExtra(ACCOUNT) != target.accountId ||
                intent.getStringExtra(CONVERSATION) != target.conversationId ||
                intent.getStringExtra(AGENT) != target.agentId) return null
            return target
        }
    }
}

@Composable
private fun BubbleConversationContent(runtime: GatewayRuntime, target: ReplyNotificationTarget?, onClose: () -> Unit) {
    val phase by runtime.phase.collectAsState()
    val sessions by runtime.nativeConversationNotifications.sessions.collectAsState()
    // A replacement authenticated workspace invalidates every old picker and draft destination.
    val activeSession by runtime.controller.collectAsState()
    // 原生模式要等 Agent 发现完成才知道目标是否可用；不订阅这份状态，气泡会在
    // 发现完成后仍停在“正在连接”，除非用户把窗口关了重开。
    val nativeAgents by runtime.nativeAgents.collectAsState()
    val nativeAgentsState = nativeAgents?.state?.collectAsState()?.value
    LaunchedEffect(runtime) { runtime.restoreSessionIfAvailable() }
    // 目标所属 Agent 一旦被本账号发现，原生气泡就不再依赖主界面；在列表还没到达
    // 之前这里保持“正在连接”，发现完成后自动继续。
    val agentDiscovered = target?.agentId == null ||
        nativeAgentsState?.agents?.any { it.id == target.agentId } == true
    // 直接求值而不缓存：目标可用性还依赖工作区与连接阶段。
    val valid = target != null && agentDiscovered && runtime.isBubbleTargetCurrent(target)
    // 原生会话由气泡自己的控制器承载，不依赖主界面当前打开的是哪个会话/Agent；
    // 旧链路仍要求主账号工作台存在。
    val session: Any? = target?.let { current ->
        if (current.agentId != null) current else activeSession
    }.takeIf { valid }
    val window: BubbleConversationWindow = viewModel()
    LaunchedEffect(runtime, target, session, valid) {
        window.bind(session, target, runtime::createBubbleConversation, runtime::releaseBubbleConversation)
    }
    val bound = valid && window.isBoundTo(session, target)
    val selectedId = window.selectedConversationId.takeIf { bound }
    val selectedTarget = target?.let { current -> selectedId?.let { current.copy(conversationId = it) } }
    val windowController = window.controller
    val windowState = windowController?.state?.collectAsState()?.value
    val chat = windowController?.takeIf { bound && selectedId != null && windowState?.activeThreadId == selectedId }
    val chatState = windowState.takeIf { chat != null }
    LaunchedEffect(bound, selectedId, activeSession) {
        // 会话列表由气泡自己的控制器读取；旧链路没有独立控制器时才借主账号工作台。
        if (bound && selectedId == null) (windowController ?: activeSession)?.refreshThreads()
    }
    val attachmentPickers = rememberConversationAttachmentPickers(chat) { current ->
        current === window.controller && selectedTarget != null && runtime.isBubbleTargetCurrent(selectedTarget) &&
            (target?.agentId != null || runtime.controller.value === activeSession) &&
            window.selectedConversationId == selectedTarget.conversationId
    }

    if (!valid) {
        val message = when {
            target == null -> "会话链接无效"
            phase == ConnectionPhase.Negotiating || phase == ConnectionPhase.Authenticating -> "正在连接 Gateway…"
            // 已连上但还没发现这个 Agent：说清楚是在等待，而不是暗示账号不对。
            phase is ConnectionPhase.Connected && !agentDiscovered -> "正在查找该 Agent…"
            phase is ConnectionPhase.Connected -> "此气泡属于其他账号，无法读取或回复"
            phase is ConnectionPhase.Failed -> "Gateway 连接失败，请在应用中重新连接"
            else -> "Gateway 尚未连接，请在应用中连接后重试"
        }
        BubbleUnavailable(message, onClose)
        return
    }
    val launchTarget = target ?: return
    val selected = sessions.firstOrNull { it.target == selectedTarget }
    BackHandler(enabled = selectedId != null) { window.showBrowser() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimensions.SpaceSmall), verticalAlignment = Alignment.CenterVertically) {
            if (selectedId != null) TextButton(onClick = window::showBrowser) { Text("会话列表") }
            Text(if (selectedId == null) "全部会话" else selected?.title ?: chatState?.activeThreadTitle?.ifBlank { "Agent 会话" } ?: "Agent 会话",
                modifier = Modifier.weight(1f).padding(Dimensions.SpaceSmall), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("关闭") }
        }
        HorizontalDivider()
        window.notice?.let { Text(it, Modifier.padding(Dimensions.SpaceCompact), color = MaterialTheme.colorScheme.error) }
        if (selectedId == null) {
            if (window.creatingThread) LinearProgressIndicator(Modifier.fillMaxWidth())
            // 原生气泡的会话列表来自气泡自己的控制器；旧链路借用主账号工作台。
            val history = if (target?.agentId != null) windowController else activeSession
            if (!bound || history == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val historyState by history.state.collectAsState()
                val notificationInHistory = (historyState.threads as? Loadable.Ready)?.value.orEmpty()
                    .any { it.id.value == launchTarget.conversationId && !it.archived }
                if (!notificationInHistory) {
                    TextButton(onClick = { window.select(launchTarget.conversationId) }, modifier = Modifier.fillMaxWidth()) {
                        Text("打开通知对应的会话")
                    }
                }
                ConversationBrowser(
                    controller = history,
                    onOpenThread = window::select,
                    onCreateThread = { window.createThread(runtime::createBubbleThread) },
                    activeThreadId = windowState?.activeThreadId,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            if (chat == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("会话连接已失效，请返回列表重试") }
            else key(chat) { BubbleChat(chat, selected?.status, attachmentPickers, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun BubbleChat(
    controller: WorkbenchController,
    status: NativeConversationStatus?,
    pickers: ConversationAttachmentPickers,
    modifier: Modifier,
) {
    val state by controller.state.collectAsState()
    val entries = (state.timeline as? Loadable.Ready)?.value.orEmpty()
    val displayItems = rememberTimelineDisplayItems(entries, state.activeThreadId, state.generation)
    val presentations = rememberStreamingPresentations(entries, controller to state.activeThreadId, generation = state.generation)
    val executionTiming = rememberExecutionTimingStore(state.activeThreadId)
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val downloadAction: ((String) -> Unit)? =
        if (controller.downloadsSupported) controller::downloadAttachment else null
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followLatest by remember(state.activeThreadId) { mutableStateOf(true) }
    val nearLatest by remember { derivedStateOf {
        val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
        lastVisible >= list.layoutInfo.totalItemsCount - 2
    } }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) followLatest = false }
    LaunchedEffect(list, state.activeThreadId) {
        snapshotFlow { list.isScrollInProgress to nearLatest }.collect { (scrolling, latest) ->
            if (!scrolling) followLatest = latest
        }
    }
    var tailHeight by remember(state.activeThreadId) { mutableIntStateOf(0) }
    var tailKey by remember(state.activeThreadId) { mutableStateOf<String?>(null) }
    LaunchedEffect(displayItems.lastOrNull()?.key, entries.lastOrNull()?.text) {
        if (displayItems.isNotEmpty() && followLatest && !list.isScrollInProgress) {
            list.animateScrollToItem(displayItems.lastIndex)
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (status != null) Text(status.label(), Modifier.padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceTiny), style = MaterialTheme.typography.labelMedium)
        when (val timeline = state.timeline) {
            Loadable.Idle, Loadable.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Loadable.Failed -> Column(Modifier.weight(1f).padding(Dimensions.SpaceCompact)) {
                Text(readableFailure(timeline.code))
                TextButton(onClick = controller::retryTimeline) { Text("重试") }
            }
            else -> AttachmentPreviewHost(
                attachments = entries.flatMap { it.attachments },
                onDownload = downloadAction,
                onRequestOriginal = controller::requestOriginalImage,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { openPreview ->
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(Dimensions.SpaceCompact), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    if (displayItems.isEmpty()) item { Text("此会话暂无消息") }
                    items(displayItems, key = { it.key }) { item ->
                        TimelineDisplayRow(
                            item = item,
                            presentations = presentations,
                            onDecide = controller::decideApproval,
                            timing = executionTiming,
                            onDownloadAttachment = downloadAction,
                            onRequestOriginalAttachment = controller::requestOriginalImage,
                            onOpenAttachmentPreview = openPreview,
                            modifier = Modifier.onSizeChanged { measured ->
                                if (item.key == displayItems.lastOrNull()?.key) {
                                    val previous = if (tailKey == item.key) tailHeight else measured.height
                                    tailKey = item.key
                                    tailHeight = measured.height
                                    val growth = measured.height - previous
                                    // 后续吐字只改变真实行高，不改变 receivedText；贴底跟随必须消费布局增长。
                                    if (growth > 0 && followLatest && !dragging) scope.launch { list.scrollBy(growth.toFloat()) }
                                }
                            }.animateItem(
                                fadeInSpec = null,
                                placementSpec = MotionSpecs.morph(reduceMotion),
                            ),
                        )
                    }
                }
            }
        }
        state.notice?.let { notice ->
            Text(noticeText(notice), Modifier.fillMaxWidth().padding(horizontal = Dimensions.SpaceCompact), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        ComposerBar(
            editorKey = controller to state.activeThreadId,
            onPasteImages = pickers.paste,
            draft = state.draft,
            onDraftChange = controller::editDraft,
            generation = state.generation,
            canSend = state.canSend,
            onSend = controller::sendDraft,
            onQueue = { controller.sendDraft(com.openandroidintelligence.conversation.ports.MessageDeliveryMode.QUEUE) },
            onSteer = { controller.sendDraft(com.openandroidintelligence.conversation.ports.MessageDeliveryMode.STEER) },
            pendingDeliveries = state.pendingDeliveries,
            onRetryPendingDelivery = controller::retryPendingDeliveries,
            onStop = controller::stopGeneration,
            onPickCamera = pickers.camera,
            onPickGallery = pickers.gallery,
            onPickDocument = pickers.document,
            onVoiceInput = {},
            voiceInputEnabled = false,
            attachments = state.attachments,
            inFlightAttachmentIds = state.inFlightAttachmentIds,
            onRemoveAttachment = controller::removeAttachment,
            onRetryAttachment = controller::retryAttachment,
            applyImePadding = false,
            modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceTiny),
        )
    }
}

@Composable
private fun BubbleUnavailable(message: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimensions.ScreenHorizontal), verticalArrangement = Arrangement.Center,
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
    NativeConversationStatus.ENDED -> "本次处理已结束"
    NativeConversationStatus.DISCONNECTED -> "连接中断，等待恢复"
}
