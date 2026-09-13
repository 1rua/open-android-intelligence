package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.openandroidintelligence.conversation.components.LoadableRegion
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.Dimensions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 对话是主任务；全部内容来自同一 controller，HTML 只提供视觉层次。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    controller: WorkbenchController, gatewayLabel: String, onOpenSettings: () -> Unit,
    onPickCamera: () -> Unit, onPickGallery: () -> Unit, onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit, modifier: Modifier = Modifier,
    onOpenAssistant: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var followLatest by remember(state.activeThreadId) { mutableStateOf(true) }
    val entries = (state.timeline as? Loadable.Ready)?.value.orEmpty()
    var showAttachmentLibrary by remember { mutableStateOf(false) }

    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            controller.dismissNotice()
            snackbar.showSnackbar(notice)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .distinctUntilChanged().collect { (scrolling, below) -> if (scrolling) followLatest = !below }
    }
    LaunchedEffect(state.activeThreadId, entries.lastOrNull()?.key, entries.lastOrNull()?.text, followLatest) {
        // 真实 delta 直接增长；只在用户保持跟随时定位尾部，不逐 token 播放动画。
        if (followLatest && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex, Int.MAX_VALUE)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= Dimensions.ExpandedWindow
        val drawerContent: @Composable () -> Unit = {
            ThreadDrawer(gatewayLabel, state.threads, state.activeThreadId,
                onOpenThread = { controller.openThread(it); scope.launch { drawer.close() } },
                onCreateThread = { controller.createThread(); scope.launch { drawer.close() } },
                onRefresh = controller::refreshThreads,
                onOpenSettings = { scope.launch { drawer.close() }; onOpenSettings() },
                onCloseDrawer = { scope.launch { drawer.close() } }, showClose = !expanded,
                onOpenAttachments = { scope.launch { drawer.close() }; showAttachmentLibrary = true })
        }
        val conversation: @Composable () -> Unit = {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(state.activeThreadTitle.ifBlank { "对话" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(gatewayLabel, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        navigationIcon = {
                            if (!expanded) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "打开会话列表") }
                        },
                        actions = {
                            onOpenAssistant?.let { open ->
                                IconButton(onClick = open) { Icon(Icons.Default.PictureInPictureAlt, "浮动对话") }
                            }
                            IconButton(onClick = { showAttachmentLibrary = true }) { Icon(Icons.Default.AttachFile, "附件库") }
                            IconButton(onClick = controller::createThread) { Icon(Icons.Default.Add, "新建对话") }
                            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置与平台管理") }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = Dimensions.ReadingWidth).fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            if (state.timeline == Loadable.Empty || (state.activeThreadId == null && state.timeline == Loadable.Idle)) {
                                ConversationWelcome(onCreate = if (state.activeThreadId == null) controller::createThread else null)
                            } else {
                                LoadableRegion(state.timeline, "写下第一条消息，开始这段对话", controller::retryTimeline,
                                    modifier = Modifier.fillMaxSize(), ready = { rows ->
                                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(Dimensions.SpaceMedium),
                                            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceLarge)) {
                                            items(rows, key = { it.key }) { entry -> MessageTimeline(listOf(entry)) }
                                        }
                                    })
                            }
                            if (!followLatest && entries.isNotEmpty()) {
                                FilledTonalButton(onClick = { followLatest = true },
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(Dimensions.SpaceSmall)) {
                                    Icon(Icons.Default.ArrowDownward, null)
                                    Spacer(Modifier.width(Dimensions.SpaceSmall)); Text("回到最新")
                                }
                            }
                        }
                        CommandMenu(state.catalog, state.draft, controller::selectCommand, controller::loadCatalog)
                        PendingBatchStrip(state.pendingBatch)
                        ComposerBar(state.draft, controller::editDraft, state.generation,
                            canSend = state.activeThreadId != null && (state.draft.isNotBlank() || state.attachments.isNotEmpty()),
                            onSend = controller::sendDraft, onStop = controller::stopGeneration,
                            onPickCamera = onPickCamera, onPickGallery = onPickGallery, onPickDocument = onPickDocument,
                            onVoiceInput = onVoiceInput, attachments = state.attachments,
                            onRemoveAttachment = controller::removeAttachment, onRetryAttachment = controller::retryAttachment,
                            modifier = Modifier.padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceSmall))
                    }
                }
            }
        }
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(Dimensions.DrawerWidth).fillMaxHeight()) { drawerContent() }
                VerticalDivider()
                Box(Modifier.weight(1f)) { conversation() }
            }
        } else {
            ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = true,
                drawerContent = { ModalDrawerSheet(Modifier.width(Dimensions.DrawerWidth)) { drawerContent() } }) { conversation() }
        }
        if (showAttachmentLibrary) {
            androidx.activity.compose.BackHandler { showAttachmentLibrary = false }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
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
    }
}

@Composable
private fun ConversationWelcome(onCreate: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = Dimensions.FormWidth).padding(Dimensions.SpaceXLarge),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium)) {
            SignalStitch(modifier = Modifier.height(Dimensions.BrandMark))
            Text("从一个想法开始", style = MaterialTheme.typography.headlineMedium)
            Text("与自己的 Agent 对话，分享你选中的图片和文件。", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onCreate != null) Button(onClick = onCreate) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(Dimensions.SpaceSmall)); Text("新建对话") }
            else Text("输入 / 可查看此 Gateway 提供的命令。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
