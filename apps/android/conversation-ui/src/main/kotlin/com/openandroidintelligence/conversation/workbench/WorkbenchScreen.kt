package com.openandroidintelligence.conversation.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.LoadableRegion
import com.openandroidintelligence.conversation.components.connectionLabel
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 对话工作台界面：严格遵守产品主导航边界（账号/Gateway、对话、附件）。
 * 工作台的主体是对话任务本身，坚决不引入二级 Tab 切换、虚假运行看板或无意义硬件指标。
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
    onLogout: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var followLatest by remember(state.activeThreadId) { mutableStateOf(true) }
    val entries = (state.timeline as? Loadable.Ready)?.value.orEmpty()
    var showAttachmentLibrary by remember { mutableStateOf(false) }
    var commandPopupDismissed by remember(state.activeThreadId) { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf("") }
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    val isThinking = (state.generation == GenerationState.QUEUED ||
        state.generation == GenerationState.RUNNING) &&
        entries.none { !it.isUser && it.isStreaming }

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
        if (state.streamHealth == com.openandroidintelligence.conversation.model.StreamHealth.FAILED) {
            snackbar.showSnackbar("实时通道已断开，暂时收不到新回复。请检查网络或 Gateway 服务。")
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .distinctUntilChanged().collect { (scrolling, below) -> if (scrolling) followLatest = !below }
    }
    LaunchedEffect(state.activeThreadId, entries.lastOrNull()?.key, entries.lastOrNull()?.text, isThinking, followLatest) {
        if (followLatest) {
            val totalItems = entries.size + if (isThinking) 1 else 0
            if (totalItems > 0) listState.scrollToItem(totalItems - 1, 0)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= Dimensions.ExpandedWindow

        val drawerContent: @Composable () -> Unit = {
            ThreadDrawer(
                gatewayLabel = gatewayLabel,
                threads = state.threads,
                activeThreadId = state.activeThreadId,
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
                    onLogout?.invoke()
                },
            )
        }

        val mainContent: @Composable () -> Unit = {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(modifier = Modifier.size(Dimensions.LeadingIconContainer)) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(Dimensions.LeadingIconContainer),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.SmartToy,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(Dimensions.SmallIcon),
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(Dimensions.SpaceCompact)
                                            .align(Alignment.BottomEnd)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .border(Dimensions.StrokeStitch, MaterialTheme.colorScheme.surface, CircleShape),
                                    )
                                }
                                Column {
                                    Text(
                                        text = state.activeThreadTitle.ifBlank { "AI 助手" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${gatewayLabel.removePrefix("https://")} · " +
                                            connectionLabel(state.streamHealth),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (state.streamHealth ==
                                            com.openandroidintelligence.conversation.model.StreamHealth.FAILED
                                        ) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (!expanded) {
                                IconButton(onClick = { scope.launch { drawer.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "打开会话抽屉")
                                }
                            }
                        },
                        actions = {
                            if (state.activeThreadId != null) {
                                IconButton(onClick = {
                                    renameDraft = state.activeThreadTitle
                                    showRenameDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "重命名对话")
                                }
                            }
                            onOpenAssistant?.let { open ->
                                IconButton(onClick = open) {
                                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = "浮动助理")
                                }
                            }
                            IconButton(onClick = { showAttachmentLibrary = true }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "附件库")
                            }
                            IconButton(onClick = controller::createThread, enabled = !state.creatingThread) {
                                Icon(Icons.Default.Add, contentDescription = "新建对话")
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    var bottomDockHeightPx by remember { mutableIntStateOf(0) }
                    val density = LocalDensity.current
                    val isCommandPopupVisible = !commandPopupDismissed && state.draft.startsWith("/")

                    if (isCommandPopupVisible) {
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
                            // a) 会话消息区域
                            Box(Modifier.weight(1f)) {
                                if (state.timeline == Loadable.Empty || (state.activeThreadId == null && state.timeline == Loadable.Idle)) {
                                    ConversationWelcome(onCreate = if (state.activeThreadId == null) controller::createThread else null)
                                } else {
                                    LoadableRegion(
                                        state.timeline,
                                        "写下第一条消息，开始这段对话",
                                        controller::retryTimeline,
                                        modifier = Modifier.fillMaxSize(),
                                        ready = { rows ->
                                            LazyColumn(
                                                state = listState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(Dimensions.SpaceMedium),
                                                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceLarge),
                                            ) {
                                                items(rows, key = { it.key }) { entry ->
                                                    val jumpTarget = entry.systemThreadId
                                                    if (jumpTarget != null) {
                                                        CreatedThreadJumpRow(
                                                            label = entry.text,
                                                            onClick = { controller.openThread(jumpTarget) },
                                                        )
                                                    } else {
                                                        MessageTimeline(listOf(entry))
                                                    }
                                                }
                                                if (state.creatingThread) {
                                                    // Waiting is a state the user must be able to see
                                                    // and leave: silence here is exactly what made the
                                                    // button look like it did nothing.
                                                    item(key = "creating_thread") {
                                                        CreatingThreadRow(
                                                            onCancel = controller::cancelThreadCreation,
                                                            modifier = Modifier.padding(vertical = Dimensions.SpaceSmall),
                                                        )
                                                    }
                                                }
                                                if (isThinking) {
                                                    item(key = "thinking_indicator") {
                                                        ThinkingIndicator(modifier = Modifier.padding(vertical = Dimensions.SpaceSmall))
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                                ScrollToBottomButton(
                                    visible = !followLatest && entries.isNotEmpty(),
                                    reduceMotion = reduceMotion,
                                    onClick = { followLatest = true },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }

                            // b) Bottom dock
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coordinates ->
                                        bottomDockHeightPx = coordinates.size.height
                                    },
                            ) {
                                PendingBatchStrip(state.pendingBatch)
                                ComposerBar(
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
                                    onStop = controller::stopGeneration,
                                    onPickCamera = onPickCamera,
                                    onPickGallery = onPickGallery,
                                    onPickDocument = onPickDocument,
                                    onVoiceInput = onVoiceInput,
                                    attachments = state.attachments,
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

@Composable
private fun ConversationWelcome(onCreate: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                style = MaterialTheme.typography.bodyLarge,
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
            } else {
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
private fun CreatedThreadJumpRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
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
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
                strokeWidth = 2.dp,
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

@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MotionSpecs.fade(reduceMotion)) + scaleIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialScale = 0.85f,
        ),
        exit = fadeOut(MotionSpecs.fade(reduceMotion)) + scaleOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetScale = 0.85f,
        ),
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.padding(Dimensions.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(Modifier.width(Dimensions.SpaceSmall))
            Text("回到最新")
        }
    }
}

