package com.openandroidintelligence.conversation.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.Dimensions
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
                                Box(modifier = Modifier.size(36.dp)) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.SmartToy,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .align(Alignment.BottomEnd)
                                            .clip(CircleShape)
                                            .background(Color(0xFF4ADE80))
                                            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
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
                                        text = "${gatewayLabel.removePrefix("https://")} · 已连接",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            IconButton(onClick = controller::createThread) {
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
                                                    MessageTimeline(listOf(entry))
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
                                if (!followLatest && entries.isNotEmpty()) {
                                    FilledTonalButton(
                                        onClick = { followLatest = true },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(Dimensions.SpaceSmall),
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, null)
                                        Spacer(Modifier.width(Dimensions.SpaceSmall))
                                        Text("回到最新")
                                    }
                                }
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
                    Icon(Icons.Default.Add, null)
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
