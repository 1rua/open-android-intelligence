package com.openandroidintelligence.conversation.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.LoadableRegion
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.components.connectionLabel
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 对话工作台界面：严格遵守产品主导航边界（账号/Gateway、对话、附件）。
 * 工作台的主体是对话任务本身，坚决不引入二级 Tab 切换、虚假运行看板或无意义硬件指标。
 *
 * 这一层只负责「框架」：顶栏、时间线的滚动编排、底部输入坞的位置与安全区，
 * 单条消息的视觉在 [MessageTimeline] 里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    controller: WorkbenchController,
    gatewayLabel: String,
    onOpenSettings: () -> Unit,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAssistant: (() -> Unit)? = null,
    isDarkTheme: Boolean = true,
    onToggleTheme: (() -> Unit)? = null,
    onLogout: () -> Unit,
    pluginCards:@Composable ()->Unit = {},
    /**
     * 顶部通告槽位（离线镜像、Gateway 操作结果等）。
     *
     * 必须渲染在 Scaffold 内容区里，而不是宿主在它外面再包一层 Column —— 那样横幅会
     * 抢在状态栏之前、把抽屉挤成半屏，且顶栏的安全区会算错。宿主只负责提供内容，
     * 位置与宽度由工作台统一约束。
     */
    header: @Composable () -> Unit = {},
    onPasteImages: ((PastedImageContent) -> Unit)? = null,
    accountName: String = "",
    agentName: String? = null,
    accountAvatar: androidx.compose.ui.graphics.ImageBitmap? = null,
    onChangeAvatar: (() -> Unit)? = null,
    connectionHealth: StreamHealth? = null,
) {
    val state by controller.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // 抽屉打开时返回键先关抽屉：交给平台默认处理会把用户直接退出 App。
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var followLatest by remember(state.activeThreadId) { mutableStateOf(true) }
    val entries = (state.timeline as? Loadable.Ready)?.value.orEmpty()
    // 时间线按“展示项”渲染：普通消息逐条，执行段在最终回答出现后收成一个折叠面板。
    val displayItems = rememberTimelineDisplayItems(entries, state.activeThreadId, state.generation)
    LaunchedEffect(state.activeThreadId, displayItems, listState) {
        if (!com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidenceEnabled) return@LaunchedEffect
        val userRows = displayItems.filterIsInstance<TimelineDisplayItem.Single>()
            .filter { it.entry.isUser }.takeLast(64).map { item ->
                mapOf("uiKey" to item.key, "messageId" to item.entry.key,
                    "clientMessageId" to item.entry.clientMessageId?.value,
                    "generationId" to item.entry.generationId,
                    "state" to item.entry.messageStatus?.name,
                    "pendingAcceptance" to item.entry.pendingAcceptance)
            }
        snapshotFlow {
            val layout = listState.layoutInfo
            Triple(layout.viewportStartOffset, layout.viewportEndOffset,
                layout.visibleItemsInfo.take(64).map { item ->
                    mapOf("uiKey" to item.key.toString(), "index" to item.index,
                        "offset" to item.offset, "size" to item.size)
                })
        }.distinctUntilChanged().collect { (start, end, visible) ->
            com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence("T9.list_layout", mapOf(
                "conversationId" to state.activeThreadId, "itemCount" to displayItems.size,
                "viewportStart" to start, "viewportEnd" to end, "userRows" to userRows,
                "visibleItems" to visible))
        }
    }
    val executionTiming = rememberExecutionTimingStore(state.activeThreadId)
    var showAttachmentLibrary by remember { mutableStateOf(false) }
    var commandPopupDismissed by remember(state.activeThreadId) { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf("") }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    // The "no approval cards here" hint explains a Gateway without the feature;
    // once read, it would only be noise on every screen for the whole session.
    var approvalHintDismissed by remember { mutableStateOf(false) }
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    // 顶栏与输入坞都量过了才知道 Snackbar 要抬多高；dock 高度包含它自己的安全区。
    var bottomDockHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val timelineReady = state.timeline is Loadable.Ready
    val isThinking = (state.generation == GenerationState.QUEUED ||
        state.generation == GenerationState.RUNNING) &&
        entries.none { !it.isUser && it.isStreaming }
    val creatingThreadInThisThread = state.creatingThread &&
        state.creationSourceThreadId == state.activeThreadId
    // item 0 永远是插件卡片槽位；其余是消息展示项、创建中、思考中。
    val itemCount = 1 + displayItems.size +
        (if (creatingThreadInThisThread) 1 else 0) +
        (if (isThinking) 1 else 0)
    val lastItemIndex = itemCount - 1
    // 同一帧内折叠会让条目数变少，先前算出的索引可能越界：
    // 目标索引以当前布局的条目数为上限，取不到合法目标时就不滚动。
    fun latestScrollTarget(): Int? {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) return lastItemIndex.takeIf { it >= 0 }
        return minOf(lastItemIndex, total - 1).takeIf { it >= 0 }
    }
    val latestItemVisible by remember(listState, lastItemIndex) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.index == lastItemIndex }
        }
    }

    val freshEntryKeys = rememberFreshTimelineKeys(
        threadId = state.activeThreadId,
        keys = displayItems.map { it.key },
    )

    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            controller.dismissNotice()
            // A failed send must explain itself: an error code alone reads as "no reaction".
            snackbar.showSnackbar(noticeText(notice))
        }
    }
    // A dead reply channel is the one cause of "sent but nothing came back"
    // that the user cannot see from the timeline, so it says so out loud.
    LaunchedEffect(state.streamHealth) {
        if (state.streamHealth == StreamHealth.FAILED) {
            snackbar.showSnackbar(state.notice?.let(::noticeText)
                ?: "实时通道已断开，暂时收不到新回复。请检查网络或 Gateway 服务。")
        }
    }

    // M12：用户手指一落下就停止跟随，而不是等到滚动结束 —— 否则流式增长会在用户
    // 正在阅读时把内容继续往下拽。
    val timelineDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(timelineDragged) {
        if (timelineDragged) followLatest = false
    }
    // 用户自己滑回底部后恢复跟随（滑动手势或惯性都算），避免「已经在底部却还显示回到最新」。
    LaunchedEffect(state.activeThreadId, listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .distinctUntilChanged()
            .collect { (scrolling, below) ->
                if (!scrolling && !below) followLatest = true
            }
    }

    // 打开 / 切换到某个会话：直接定位到最新一条。这是「读到这里」的位置语义，
    // 不是流式跟随，所以这里允许一次瞬时定位。
    LaunchedEffect(state.activeThreadId, timelineReady) {
        if (timelineReady) {
            followLatest = true
            latestScrollTarget()?.takeIf { it > 0 }?.let { target -> listState.scrollToItem(target) }
        }
    }

    // 尾部形状变化（新消息、思考点出现/消失、创建中）：平滑跟到最新，不整段硬跳。
    // 用「条目数 + 尾巴身份」作为签名，而不是只看条目数：思考点变成流式正文时
    // 条目数不变，但尾巴换了、内容高度也变了，同样需要重新贴底。
    val tailKey: String = when {
        isThinking -> "thinking_indicator"
        creatingThreadInThisThread -> "creating_thread"
        else -> displayItems.lastOrNull()?.key ?: "protected_plugin_cards"
    }
    val tailSignature = "$itemCount|$tailKey"
    var previousTailSignature by remember(state.activeThreadId, timelineReady) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.activeThreadId, timelineReady, tailSignature) {
        val previous = previousTailSignature
        previousTailSignature = tailSignature
        if (previous == null) return@LaunchedEffect
        if (!followLatest) return@LaunchedEffect
        val target = latestScrollTarget()?.takeIf { it > 0 } ?: return@LaunchedEffect
        if (reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target)
    }

    // M05/M12：文字在流式增长时不做逐 token 动画，只按「最后一条长高了多少」增量贴底。
    var lastEntryHeightPx by remember(state.activeThreadId) { mutableIntStateOf(0) }
    var lastEntryKey by remember(state.activeThreadId) { mutableStateOf<String?>(null) }
    // 键盘弹出、附件条出现会让输入坞变高（可视区变矮）；停留在底部时跟着贴底。
    LaunchedEffect(bottomDockHeightPx) {
        if (!followLatest) return@LaunchedEffect
        val target = latestScrollTarget()?.takeIf { it > 0 } ?: return@LaunchedEffect
        if (listState.canScrollForward) {
            if (reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= Dimensions.ExpandedWindow

        val drawerContent: @Composable () -> Unit = {
            ThreadDrawer(
                accountName = accountName,
                agentName = agentName,
                avatar = accountAvatar,
                onChangeAvatar = onChangeAvatar,
                streamHealth = connectionHealth ?: state.streamHealth,
                gatewayLabel = gatewayLabel,
                threads = state.threads,
                activeThreadId = state.activeThreadId,
                organization = state.browserOrganization,
                selectedFolderId = state.selectedFolderId,
                managementSupported = state.conversationManagementSupported,
                deleteSupported = state.conversationDeleteSupported,
                archiveSupported = state.conversationArchiveSupported,
                unarchiveSupported = state.conversationUnarchiveSupported,
                renameSupported = state.conversationRenameSupported,
                organizationStoredLocally = state.organizationStoredLocally,
                managing = state.managingThreads,
                online = state.isOnline,
                busyThreadIds = state.busyThreadIds,
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
                onOpenThread = {
                    controller.openThread(it)
                    scope.launch { drawer.close() }
                },
                onCreateThread = {
                    controller.createThread()
                    scope.launch { drawer.close() }
                },
                onRefresh = controller::refreshThreads,
                onOpenSettings = {
                    scope.launch { drawer.close() }
                    onOpenSettings()
                },
                onCloseDrawer = { scope.launch { drawer.close() } },
                showClose = !expanded,
                onOpenAttachments = {
                    scope.launch { drawer.close() }
                    showAttachmentLibrary = true
                },
                onLogout = {
                    scope.launch { drawer.close() }
                    onLogout()
                },
            )
        }

        val mainContent: @Composable () -> Unit = {
            // M01：列表离开顶部后，顶栏与内容之间出现 1dp 分隔线（150ms alpha）。
            val timelineScrolled by remember(state.activeThreadId) {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                }
            }
            val dividerAlpha by animateFloatAsState(
                targetValue = if (timelineScrolled) 1f else 0f,
                animationSpec = if (reduceMotion) snap() else tween(MotionSpecs.Fast, easing = MotionSpecs.StandardEasing),
                label = "topbar-divider",
            )

            Scaffold(
                snackbarHost = {
                    SnackbarHost(snackbar) { data ->
                        Snackbar(
                            snackbarData = data,
                            shape = RoundedCornerShape(AppRadius.Small),
                            modifier = Modifier.padding(
                                start = Dimensions.TimelineHorizontal,
                                end = Dimensions.TimelineHorizontal,
                                // M15：Snackbar 必须避让输入坞 + 系统手势条。
                                bottom = with(density) { bottomDockHeightPx.toDp() } + Dimensions.SpaceSmall,
                            ),
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    // M3 TopAppBar 的高度是固定值（默认 64dp）并裁剪内容，两行标题在
                    // 系统字号放大到约 160% 以上时会被裁掉。这里按字体缩放决定第二行
                    // 放在顶栏内（紧凑）还是顶栏下方（大字号，随内容增高），
                    // 保证 200% 字号下连接状态依然完整可读；布局整体仍只占一块顶栏区域。
                    val stackedContext = density.fontScale > 1.55f
                    Column {
                        WorkbenchTopBar(
                            threadTitle = state.activeThreadTitle,
                            gatewayLabel = gatewayLabel,
                            streamHealth = connectionHealth ?: state.streamHealth,
                            expanded = expanded,
                            canRename = state.activeThreadId != null,
                            creatingThread = state.creatingThread,
                            overflowExpanded = overflowMenuExpanded,
                            showAssistant = onOpenAssistant != null,
                            subtitleInBar = !stackedContext,
                            onOpenDrawer = { scope.launch { drawer.open() } },
                            onCreateThread = controller::createThread,
                            onRenameThread = {
                                renameDraft = state.activeThreadTitle
                                showRenameDialog = true
                            },
                            onOpenAttachments = { showAttachmentLibrary = true },
                            onOpenAssistant = onOpenAssistant,
                            onOpenSettings = onOpenSettings,
                            onOverflowExpandedChange = { overflowMenuExpanded = it },
                        )
                        if (stackedContext) {
                            WorkbenchContextLine(
                                gatewayLabel = gatewayLabel,
                                streamHealth = connectionHealth ?: state.streamHealth,
                                modifier = Modifier.padding(
                                    start = Dimensions.SpaceMedium,
                                    end = Dimensions.SpaceMedium,
                                    bottom = Dimensions.SpaceSmall,
                                ),
                            )
                        }
                        HorizontalDivider(
                            thickness = Dimensions.StrokeHairline,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha),
                        )
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        // 横屏刘海与侧边手势区：Scaffold 内容窗口 inset 为零，
                        // 左右安全区由这里补上（顶栏自己消费顶部）。
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val isCommandPopupVisible = !commandPopupDismissed && state.draft.startsWith("/")

                    // 抽屉盖在上面时它才是「当前可见的一层」：不能让背后看不见的
                    // 命令菜单先吃掉返回键（否则按一次返回抽屉纹丝不动）。
                    if (isCommandPopupVisible && !drawer.isOpen) {
                        BackHandler {
                            commandPopupDismissed = true
                        }
                    }

                    Box(
                        modifier = Modifier
                            .widthIn(max = Dimensions.ReadingWidth)
                            .fillMaxSize(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            header()
                            // a) 会话消息区域
                            Box(Modifier.weight(1f)) {
                                if (state.timeline == Loadable.Empty || (state.activeThreadId == null && state.timeline == Loadable.Idle)) {
                                    Column(Modifier.fillMaxSize()) {
                                        pluginCards()
                                        ConversationWelcome(
                                            onCreate = if (state.activeThreadId == null) controller::createThread else null,
                                            catalog = state.catalog,
                                            onSelectCommand = controller::selectCommand,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                } else {
                                    LoadableRegion(
                                        state.timeline,
                                        "写下第一条消息，开始这段对话",
                                        controller::retryTimeline,
                                        modifier = Modifier.fillMaxSize(),
                                        ready = { _ ->
                                            val downloadAction =
                                                if (controller.downloadsSupported) controller::downloadAttachment else null
                                            AttachmentPreviewHost(
                                                attachments = entries.flatMap { it.attachments },
                                                onDownload = downloadAction,
                                                onRequestOriginal = controller::requestOriginalImage,
                                                modifier = Modifier.fillMaxSize(),
                                            ) { openPreview ->
                                            LazyColumn(
                                                state = listState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(
                                                    start = Dimensions.TimelineHorizontal,
                                                    end = Dimensions.TimelineHorizontal,
                                                    top = Dimensions.SpaceSmall,
                                                    bottom = Dimensions.SpaceLarge,
                                                ),
                                                verticalArrangement = Arrangement.spacedBy(Dimensions.TimelineSpacing),
                                            ) {
                                                item(key = "protected_plugin_cards") {
                                                    Box(Modifier.animateItem(
                                                        fadeInSpec = null,
                                                        placementSpec = MotionSpecs.morph(reduceMotion),
                                                    )) { pluginCards() }
                                                }
                                                items(displayItems, key = { it.key }, contentType = { it.contentType }) { item ->
                                                    val fresh = item.key in freshEntryKeys
                                                    val isTail = item.key == displayItems.lastOrNull()?.key
                                                    val jumpTarget = (item as? TimelineDisplayItem.Single)?.entry?.systemThreadId
                                                    val rowModifier = Modifier
                                                        // M04：只有真正新增的消息淡入 + 8dp 上浮；
                                                        // 历史消息滚进视口不重放。
                                                        .animateItem(
                                                            fadeInSpec = if (fresh) MotionSpecs.fade(reduceMotion) else null,
                                                            placementSpec = MotionSpecs.morph(reduceMotion),
                                                        )
                                                        .timelineItemRise(fresh = fresh, reduced = reduceMotion)
                                                        .then(
                                                            if (isTail) {
                                                                Modifier.onSizeChanged { size ->
                                                                    val previous = lastEntryHeightPx
                                                                    // 只有「同一条消息继续长高」才补差值。
                                                                    // 尾巴换成下一条消息时高度变化属于新条目，
                                                                    // 由条目数量变化那条路径平滑跟随，不能再叠加一次。
                                                                    val sameRow = lastEntryKey == item.key
                                                                    lastEntryKey = item.key
                                                                    lastEntryHeightPx = size.height
                                                                    // 只补「长高」的差值：缩短不应把用户往下拽。
                                                                    if (sameRow && followLatest && previous > 0 && size.height > previous) {
                                                                        scope.launch {
                                                                            listState.scrollBy((size.height - previous).toFloat())
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                Modifier
                                                            },
                                                        )
                                                    if (jumpTarget != null) {
                                                        CreatedThreadJumpRow(
                                                            label = (item as TimelineDisplayItem.Single).entry.text,
                                                            onClick = { controller.openThread(jumpTarget) },
                                                            modifier = rowModifier,
                                                        )
                                                    } else {
                                                        TimelineDisplayRow(
                                                            item = item,
                                                            evidenceOwner = controller to state.activeThreadId,
                                                            onDecide = controller::decideApproval,
                                                            timing = executionTiming,
                                                            onDownloadAttachment = downloadAction,
                                                            onRequestOriginalAttachment = controller::requestOriginalImage,
                                                            onOpenAttachmentPreview = openPreview,
                                                            modifier = rowModifier,
                                                        )
                                                    }
                                                }
                                                if (creatingThreadInThisThread) {
                                                    // Waiting is a state the user must be able to see
                                                    // and leave: silence here is exactly what made the
                                                    // button look like it did nothing. Scoped to the
                                                    // thread it was sent from — the conversation the user
                                                    // switched to is not the one waiting.
                                                    item(key = "creating_thread") {
                                                        CreatingThreadRow(
                                                            onCancel = controller::cancelThreadCreation,
                                                            modifier = Modifier
                                                                .animateItem(
                                                                    fadeInSpec = MotionSpecs.fade(reduceMotion),
                                                                    placementSpec = MotionSpecs.morph(reduceMotion),
                                                                )
                                                                .padding(vertical = Dimensions.SpaceSmall),
                                                        )
                                                    }
                                                }
                                                if (isThinking) {
                                                    item(key = "thinking_indicator") {
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
                                            }
                                            }
                                        },
                                    )
                                }
                                ScrollToLatestButton(
                                    visible = !followLatest && entries.isNotEmpty() && !latestItemVisible,
                                    onClick = {
                                        followLatest = true
                                        scope.launch {
                                            val target = latestScrollTarget() ?: return@launch
                                            if (reduceMotion) {
                                                listState.scrollToItem(target)
                                            } else {
                                                listState.animateScrollToItem(target)
                                            }
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }

                            // b) Bottom dock：输入坞外围负责系统手势条安全区。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(
                                        WindowInsets.navigationBars.only(
                                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                                        ),
                                    )
                                    .onGloballyPositioned { coordinates ->
                                        bottomDockHeightPx = coordinates.size.height
                                    },
                            ) {
                                PendingBatchStrip(state.pendingBatch)
                                if (state.hasRecoverableSubmissions) TextButton(onClick=controller::retryPendingSubmissions,enabled=state.isOnline) { Text("确认并查询未确认消息") }
                                // Honest degradation: a Gateway that cannot take
                                // a decision gets no card at all, and the user is
                                // told the text command is the way to answer.
                                if (state.isOnline && !state.approvalCardsSupported && !approvalHintDismissed) {
                                    ApprovalUnsupportedHint(onDismiss = { approvalHintDismissed = true })
                                }
                                ComposerBar(
                                    editorKey = controller to state.activeThreadId,
                                    onPasteImages = onPasteImages,
                                    draft = state.draft,
                                    onDraftChange = { newDraft ->
                                        if (commandPopupDismissed && (newDraft.isEmpty() || !newDraft.startsWith(state.draft))) {
                                            commandPopupDismissed = false
                                        } else if (newDraft != state.draft && !newDraft.startsWith("/")) {
                                            commandPopupDismissed = false
                                        }
                                        controller.editDraft(newDraft)
                                    },
                                    generation = state.generation,
                                    canSend = state.canSend,
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
                                    voiceInputEnabled = controller.canSendVoiceAttachment(state.activeThreadId),
                                    attachments = state.attachments,
                                    inFlightAttachmentIds = state.inFlightAttachmentIds,
                                    hasUnknownSubmissionOutcome = state.hasUnknownSubmissionOutcome,
                                    pendingDeliveries = state.pendingDeliveries,
                                    onRetryPendingDelivery = controller::retryPendingDeliveries,
                                    onRemoveAttachment = controller::removeAttachment,
                                    onRetryAttachment = controller::retryAttachment,
                                    modifier = Modifier.padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceSmall),
                                )
                            }
                        }

                        // c) Command autocomplete popup overlay
                        val bottomOffset = with(density) { bottomDockHeightPx.toDp() } + 8.dp
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(bottom = bottomOffset),
                        ) {
                            CommandMenu(
                                catalogState = state.catalog,
                                query = state.draft,
                                onSelect = { cmd ->
                                    commandPopupDismissed = true
                                    controller.selectCommand(cmd)
                                },
                                onRetry = controller::loadCatalog,
                                visible = isCommandPopupVisible,
                                onDismissRequest = { commandPopupDismissed = true },
                            )
                        }
                    }
                }
            }
        }

        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(Dimensions.DrawerWidth).fillMaxHeight()) { drawerContent() }
                VerticalDivider()
                Box(Modifier.weight(1f)) { mainContent() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = true,
                drawerContent = { ModalDrawerSheet(Modifier.width(Dimensions.DrawerWidth)) { drawerContent() } },
            ) {
                mainContent()
            }
        }

        if (showAttachmentLibrary) {
            ModalBottomSheet(
                onDismissRequest = { showAttachmentLibrary = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                AttachmentLibraryScreen(
                    attachments = state.attachments,
                    onPickGallery = onPickGallery,
                    onPickDocument = onPickDocument,
                    onPickCamera = onPickCamera,
                    onRemoveAttachment = controller::removeAttachment,
                    onRetryAttachment = controller::retryAttachment,
                    onClose = { showAttachmentLibrary = false },
                    historical=entries.flatMap { it.attachments }.distinctBy { it.draftId },
                    onDownloadHistorical=if (controller.downloadsSupported) controller::downloadAttachment else null,
                    onRequestOriginalHistorical=controller::requestOriginalImage,
                )
            }
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("重命名对话") },
                text = {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        singleLine = true,
                        label = { Text("对话标题") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val trimmed = renameDraft.trim()
                            if (trimmed.isNotEmpty()) {
                                controller.renameActiveThread(trimmed)
                            }
                            showRenameDialog = false
                        },
                    ) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

/**
 * 顶栏：品牌缝线 + 两行标题 + 收敛后的动作。
 *
 * 之前这里挂了 5 个动作图标并把标题挤成 170px，而 `windowInsets = WindowInsets(0,0,0,0)`
 * 让整条 bar 落在系统状态栏触摸区里 —— 两个问题都在这一个组件里修掉：
 * 1. 使用 [TopAppBarDefaults.windowInsets]，标题不再压状态栏、按钮点得到；
 * 2. 直接动作只有「新建对话」，其余（重命名、附件库、浮助理、设置）收进溢出菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkbenchTopBar(
    threadTitle: String,
    gatewayLabel: String,
    streamHealth: StreamHealth,
    expanded: Boolean,
    canRename: Boolean,
    creatingThread: Boolean,
    overflowExpanded: Boolean,
    showAssistant: Boolean,
    subtitleInBar: Boolean,
    onOpenDrawer: () -> Unit,
    onCreateThread: () -> Unit,
    onRenameThread: () -> Unit,
    onOpenAttachments: () -> Unit,
    onOpenAssistant: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOverflowExpandedChange: (Boolean) -> Unit,
) {
    TopAppBar(
        windowInsets = TopAppBarDefaults.windowInsets,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = threadTitle.ifBlank { "新对话" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitleInBar) {
                        WorkbenchContextLine(
                            gatewayLabel = gatewayLabel,
                            streamHealth = streamHealth,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            // 展开宽度下抽屉常驻在左侧，再给一个开抽屉按钮只会重复。
            if (!expanded) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "打开会话抽屉")
                }
            }
        },
        actions = {
            FilledIconButton(
                onClick = onCreateThread,
                enabled = !creatingThread,
                modifier = Modifier.size(Dimensions.MinimumTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建对话",
                    modifier = Modifier.size(Dimensions.Icon),
                )
            }
            Spacer(Modifier.width(Dimensions.SpaceSmall))
            Box {
                IconButton(onClick = { onOverflowExpandedChange(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { onOverflowExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名对话") },
                        enabled = canRename,
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            onOverflowExpandedChange(false)
                            onRenameThread()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("附件库") },
                        leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                        onClick = {
                            onOverflowExpandedChange(false)
                            onOpenAttachments()
                        },
                    )
                    if (showAssistant) {
                        DropdownMenuItem(
                            text = { Text("浮动助理") },
                            leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, contentDescription = null) },
                            onClick = {
                                onOverflowExpandedChange(false)
                                onOpenAssistant?.invoke()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("设置") },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            onOverflowExpandedChange(false)
                            onOpenSettings()
                        },
                    )
                }
            }
        },
    )
}

/**
 * 顶栏第二行：`Gateway 主机名 · 连接状态`。
 *
 * 主机名是技术元数据，用等宽字面承载（去掉了 `https://` 前缀，只留可读主机名）；
 * 连接状态用中文短语，颜色只在通道断开时变为 error —— 文字本身已经说明了状态，
 * 颜色不是唯一信号。
 */
@Composable
private fun WorkbenchContextLine(
    gatewayLabel: String,
    streamHealth: StreamHealth,
    modifier: Modifier = Modifier,
) {
    val host = gatewayLabel.removePrefix("https://").removePrefix("http://").trimEnd('/')
    Text(
        text = buildAnnotatedString {
            if (host.isNotEmpty()) {
                withStyle(SpanStyle(fontFamily = AppTextStyles.MonoSmall.fontFamily)) {
                    append(host)
                }
                append(" · ")
            }
            append(connectionLabel(streamHealth))
        },
        style = MaterialTheme.typography.labelMedium,
        fontSize = 11.sp,
        color = if (streamHealth == StreamHealth.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 空态 / 欢迎页：品牌缝线 + 一句标题 + 一句说明，垂直居中。
 *
 * 建议项只在 Gateway 真的返回了命令目录时出现（[AgentCommandCatalog]），
 * 不编造「推荐提问」这类并不存在的数据。
 */
@Composable
private fun ConversationWelcome(
    onCreate: (() -> Unit)?,
    catalog: Loadable<AgentCommandCatalog>,
    onSelectCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val commands: List<AgentCommand> = (catalog as? Loadable.Ready)?.value?.commands.orEmpty()
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = Dimensions.FormWidth)
                .padding(Dimensions.SpaceXLarge),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
            SignalStitch(modifier = Modifier.height(Dimensions.BrandMark))
            Text("从一个想法开始", style = MaterialTheme.typography.headlineMedium)
            Text(
                "与自己的 Agent 对话，分享你选中的图片和文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onCreate != null) {
                Button(onClick = onCreate) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                    Spacer(Modifier.width(Dimensions.SpaceSmall))
                    Text("新建对话")
                }
            }
            if (commands.isNotEmpty()) {
                // 真实命令目录：点击只是把命令填进输入框，不代替用户发送。
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                ) {
                    commands.take(3).forEach { command ->
                        AssistChip(
                            onClick = { onSelectCommand(command.command) },
                            label = { Text("/${command.command}") },
                        )
                    }
                }
            } else if (onCreate == null) {
                Text(
                    "输入 / 可查看此 Gateway 提供的命令。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 来源线程里的「已创建新对话」跳转项。
 *
 * 这是本机导航的收据，不是 Gateway 正文：`/new` 之后用户随时能回到 Agent 真正
 * 创建的那个会话，即使指令返回时他已经离开了来源线程。
 */
@Composable
private fun CreatedThreadJumpRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = Dimensions.SpaceMedium,
                vertical = Dimensions.SpaceMedium,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
        }
    }
}

/**
 * 等待 Agent 创建新会话时的可见状态。
 *
 * 等待本身必须看得见，也必须能退出：静默的等待正是「点了按钮没反应」的来源。
 * 取消只停止等待，不伪造会话、也不把用户挪出原会话。
 */
@Composable
private fun CreatingThreadRow(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = Dimensions.SpaceMedium,
                vertical = Dimensions.SpaceCompact,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
        ) {
            CircularProgressIndicator(
                strokeWidth = Dimensions.StrokeStitch,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Text(
                text = "正在由 Agent 创建新对话…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}
