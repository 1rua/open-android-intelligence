package com.openandroidintelligence.conversation.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openandroidintelligence.capability.ScreenCaptureSource
import com.openandroidintelligence.conversation.assistant.AssistantSurface
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/** 浮动面板里保留的消息条数：足够给出上下文，又不把固定输入坞挤出视野。 */
private const val FloatingMessageWindow = 5

/**
 * App 内浮动对话；复用当前账号的真实状态。
 *
 * 布局契约（规格 §5.5）：
 * - 消息区是可滚动内容，输入坞是**固定 footer**，两者都不再塞进同一个 verticalScroll，
 *   所以面板一打开就能看到输入框；
 * - 命令菜单走 overlay 槽位，锚定在输入坞上方，不挤压消息区；
 * - 返回键只在面板/菜单/圈选真的可见时才拦截，并让菜单先吃返回。
 *
 * 圈选的截图来自外部真实采集源 [screenCaptureSource]（宿主装配的
 * MediaProjection 来源，经系统授权对话框显式授予）：来源可用时先采集
 * 再把截图交给圈选层；来源缺失、未授权或采集失败时，圈选层渲染明确的
 * 不可用态，绝不以松手冒充提交。默认 null 时行为与未接入来源完全一致。
 */
@Composable
fun FloatingConversationPanel(
    controller: WorkbenchController, onClose: () -> Unit,
    onPickCamera: () -> Unit, onPickGallery: () -> Unit, onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit, modifier: Modifier = Modifier,
    screenCaptureSource: ScreenCaptureSource? = null,
    onNeedScreenCaptureAuthorization: (() -> Unit)? = null,
    onPasteImages: ((PastedImageContent) -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(true) }
    var explainSelection by remember { mutableStateOf(false) }

    // 命令菜单的呈现状态由输入内容驱动；本地关闭只影响当前这次「/」输入，
    // 这样返回键可以先关菜单，而不是直接收起整个助理。
    var commandMenuDismissed by remember { mutableStateOf(false) }
    var previousDraft by remember { mutableStateOf(state.draft) }
    LaunchedEffect(state.draft) {
        // 与 CommandMenu 自己的收起规则一致：重新开始一次「/」输入或删短查询时，
        // 之前按返回键关掉的菜单重新出现。
        if (!state.draft.startsWith("/") ||
            (previousDraft.startsWith("/") && state.draft.length < previousDraft.length)
        ) {
            commandMenuDismissed = false
        }
        previousDraft = state.draft
    }
    val commandMenuVisible = state.draft.startsWith("/") && !commandMenuDismissed

    // 始终接管返回：停靠成球时按返回应当结束助理并回到工作台，
    // 条件式启用会把 else->onClose() 变成不可达，返回键直接退出 App。
    BackHandler {
        when {
            explainSelection -> explainSelection = false
            commandMenuVisible -> commandMenuDismissed = true
            expanded -> expanded = false
            else -> onClose()
        }
    }

    val entries = (state.timeline as? Loadable.Ready)?.value.orEmpty()
    // 先按完整会话折叠执行段，再截取浮动面板窗口：否则尾部窗口可能把
    // 最终回答与它的中间过程拆开，聚合面板会看不到前面的步骤。
    val displayItems = rememberTimelineDisplayItems(entries, state.activeThreadId, state.generation)
    val visibleItems = remember(displayItems) { displayItems.takeLast(FloatingMessageWindow) }
    val latestStreaming = entries.lastOrNull()?.let { !it.isUser && it.isStreaming } == true
    val isThinking = (state.generation == GenerationState.QUEUED ||
        state.generation == GenerationState.RUNNING) && !latestStreaming

    Box(modifier = modifier.fillMaxSize()) {
        AssistantSurface(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            onClose = onClose,
            onStartSelection = {
                val source = screenCaptureSource
                if (source == null || source.isAvailable) {
                    explainSelection = true
                } else {
                    // 来源已装配但尚未经用户显式授权：先走系统授权对话框，
                    // 而不是把「未授权」静默渲染成不可用态。宿主未提供授权
                    // 入口时保持既有降级（overlay 自己渲染不可用）。
                    val requestAuthorization = onNeedScreenCaptureAuthorization
                    if (requestAuthorization != null) requestAuthorization() else explainSelection = true
                }
            },
            title = state.activeThreadTitle.ifBlank { "浮动对话" },
            footer = {
                ComposerBar(
                    editorKey = controller to state.activeThreadId,
                    onPasteImages = onPasteImages,
                    draft = state.draft,
                    onDraftChange = controller::editDraft,
                    generation = state.generation,
                    canSend = state.activeThreadId != null && state.canSend,
                    onSend = controller::sendDraft,
                    onQueue = {
                        controller.sendDraft(
                            com.openandroidintelligence.conversation.ports.MessageDeliveryMode.QUEUE,
                        )
                    },
                    onSteer = {
                        controller.sendDraft(
                            com.openandroidintelligence.conversation.ports.MessageDeliveryMode.STEER,
                        )
                    },
                    onStop = controller::stopGeneration,
                    onPickCamera = onPickCamera,
                    onPickGallery = onPickGallery,
                    onPickDocument = onPickDocument,
                    onVoiceInput = onVoiceInput,
                    attachments = state.attachments,
                    inFlightAttachmentIds = state.inFlightAttachmentIds,
                    hasUnknownSubmissionOutcome = state.hasUnknownSubmissionOutcome,
                    pendingDeliveries = state.pendingDeliveries,
                    onRetryPendingDelivery = controller::retryPendingDeliveries,
                    voiceInputEnabled = controller.canSendVoiceAttachment(state.activeThreadId),
                    onRemoveAttachment = controller::removeAttachment,
                    onRetryAttachment = controller::retryAttachment,
                    // IME 由助理表面统一给展开态输入坞，避免两层同时加高。
                    applyImePadding = false,
                )
                Text(
                    text = "浮动对话仅在此 App 内显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimensions.SpaceSmall),
                )
            },
            overlay = {
                CommandMenu(
                    catalogState = state.catalog,
                    query = state.draft,
                    onSelect = controller::selectCommand,
                    onRetry = controller::loadCatalog,
                    visible = commandMenuVisible,
                    onDismissRequest = { commandMenuDismissed = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            },
        ) {
            FloatingMessageList(
                owner = controller,
                displayItems = visibleItems,
                previewAttachments = entries.flatMap { it.attachments },
                threadId = state.activeThreadId,
                isThinking = isThinking,
                onCreateThread = controller::createThread,
                onDecide = controller::decideApproval,
                onDownloadAttachment =
                    if (controller.downloadsSupported) controller::downloadAttachment else null,
                onRequestOriginalAttachment = controller::requestOriginalImage,
            )
        }
        if (explainSelection) {
            ScreenSelectionOverlayHost(
                screenCaptureSource = screenCaptureSource,
                onCancel = { explainSelection = false },
                onConfirmCrop = { crop ->
                    // 确认先于回调（规格：确认后才能回调，不以松手冒充提交）。
                    // 圈选产物走与本地附件同一条真实三步上传链路，让「确认」
                    // 有真实下文；无活动对话时 addAttachment 的 coordinator
                    // 缺失分支会给出明确的 notice，而不是静默丢弃。
                    controller.addAttachment(
                        LocalAttachmentSelection(
                            filename = "screen-crop-${System.currentTimeMillis()}.png",
                            mediaType = "image/png",
                            contentSource = AttachmentContentSource.fromWriter(crop::writePngTo),
                            recoverAfterRestart = false,
                        ),
                    )
                    explainSelection = false
                },
            )
        }
    }
}

/**
 * 浮动面板的消息区。
 *
 * 用 `reverseLayout` 让最新一条永远贴着输入坞：新消息追加时不需要「整段跳转」
 * 就能看见（规格 M12 的锚定语义），历史消息仍按时间顺序向上排列。
 * 只保留最近 [FloatingMessageWindow] 条，配合稳定的条目 key 与 `animateItem`
 * 让「追加」有进入节奏，而不是替换上一屏内容。
 */
@Composable
private fun FloatingMessageList(
    owner: Any,
    displayItems: List<TimelineDisplayItem>,
    previewAttachments: List<com.openandroidintelligence.conversation.model.TimelineAttachment>,
    threadId: String?,
    isThinking: Boolean,
    onCreateThread: () -> Unit,
    onDecide: (approvalId: String, choice: ApprovalChoice) -> Unit,
    onDownloadAttachment: ((String) -> Unit)?,
    onRequestOriginalAttachment: (String) -> Unit,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val executionTiming = rememberExecutionTimingStore(threadId)
    val freshKeys = rememberFreshTimelineKeys(threadId, displayItems.map { it.key })
    val listState = rememberLazyListState()
    if (displayItems.isEmpty() && !isThinking) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            Text(
                text = "与当前 Gateway 继续对话",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (threadId == null) {
                TextButton(onClick = onCreateThread) { Text("新建对话") }
            }
        }
        return
    }
    AttachmentPreviewHost(
        attachments = previewAttachments,
        onDownload = onDownloadAttachment,
        onRequestOriginal = onRequestOriginalAttachment,
        modifier = Modifier.fillMaxSize(),
    ) { openPreview ->
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = Dimensions.SpaceSmall),
        verticalArrangement = Arrangement.spacedBy(Dimensions.TimelineSpacing),
    ) {
        if (isThinking) {
            item(key = "floating_thinking_indicator") {
                ThinkingIndicator(
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = MotionSpecs.fade(reduceMotion),
                            placementSpec = MotionSpecs.morph(reduceMotion),
                        )
                        .padding(vertical = Dimensions.SpaceSmall),
                )
            }
        }
        items(displayItems.asReversed(), key = { it.key }) { item ->
            val fresh = item.key in freshKeys
            TimelineDisplayRow(
                item = item,
                evidenceOwner = owner to threadId,
                onDecide = onDecide,
                timing = executionTiming,
                onDownloadAttachment = onDownloadAttachment,
                onRequestOriginalAttachment = onRequestOriginalAttachment,
                onOpenAttachmentPreview = openPreview,
                modifier = Modifier
                    // M04：只有真正新增的消息淡入 + 8dp 上浮；历史不重放。
                    .animateItem(
                        fadeInSpec = if (fresh) MotionSpecs.fade(reduceMotion) else null,
                        placementSpec = MotionSpecs.morph(reduceMotion),
                    )
                    .timelineItemRise(fresh = fresh, reduced = reduceMotion)
                    .fillMaxWidth(),
            )
        }
    }
    }
}
