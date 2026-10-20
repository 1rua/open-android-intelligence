package com.openandroidintelligence.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.components.stitchGuide
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 助理界面的共享内容层：会话浮层与 ASSIST 兜底页用的是同一批组件。
 *
 * 只做呈现：所有数据来自 [AssistantState]（主进程真实发布的状态），
 * 所有动作通过回调交回宿主，不在界面里保留第二份会话真相。
 */

/** 顶部标题：当前对话标题 + Gateway 状态；左侧是品牌缝线。 */
@Composable
internal fun AssistantPanelHeader(
    state: AssistantState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
    ) {
        SignalStitch(modifier = Modifier.size(width = Dimensions.StrokeStitch * 4, height = Dimensions.SpaceLarge))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { "智能助理" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.gatewayStatusLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 消息区或明确的空态。
 *
 * 未连接 / 未绑定 / 无消息是三种不同的空态，各自给出可执行的下一步；
 * 任何情况下都不显示"示例对话"或占位消息。
 */
@Composable
internal fun AssistantConversationArea(
    state: AssistantState,
    consentPromptVisible: Boolean,
    threadListVisible: Boolean,
    onConfirmConsent: () -> Unit,
    onDismissConsent: () -> Unit,
    onRequestConsent: () -> Unit,
    onSelectThread: (String) -> Unit,
    onDismissThreadList: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    if (threadListVisible) {
        AssistantThreadList(
            state = state,
            onSelectThread = onSelectThread,
            onDismiss = onDismissThreadList,
            modifier = modifier,
        )
        return
    }
    if (consentPromptVisible) {
        AssistantConsentCard(
            onConfirm = onConfirmConsent,
            onDismiss = onDismissConsent,
            modifier = modifier,
        )
        return
    }
    if (state.messages.isEmpty()) {
        AssistantEmptyState(
            state = state,
            onRequestConsent = onRequestConsent,
            onOpenApp = onOpenApp,
            modifier = modifier,
        )
        return
    }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // 最新一条贴着输入坞：reverseLayout 让"追加"天然可见，不需要跳转。
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = Dimensions.SpaceSmall),
        verticalArrangement = Arrangement.spacedBy(Dimensions.TimelineSpacing),
    ) {
        items(state.messages.asReversed(), key = { it.id }) { message ->
            AssistantMessageRow(
                message = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = MotionSpecs.fade(reduceMotion),
                        placementSpec = MotionSpecs.morph(reduceMotion),
                    ),
            )
        }
    }
}

@Composable
private fun AssistantEmptyState(
    state: AssistantState,
    onRequestConsent: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
    ) {
        when {
            !state.bridgeAvailable -> {
                AssistantGuidanceCard(
                    title = "助理服务未连接",
                    body = "助理进程无法访问主进程的对话状态。请先打开 App，再从系统助理重新唤起。",
                )
                Button(onClick = onOpenApp) { Text("打开 App") }
            }

            !state.connected -> {
                AssistantGuidanceCard(
                    title = "请先在 App 中连接 Gateway",
                    body = "助理只展示当前账号已连接的 Gateway 对话；未连接时不会显示任何内容。",
                )
                Button(onClick = onOpenApp) { Text("打开 App 连接 Gateway") }
            }

            !state.consent -> {
                AssistantGuidanceCard(
                    title = "绑定当前对话后才会显示内容",
                    body = "助理将显示当前账号的对话。消息和屏幕内容仅在你点击发送后提交。",
                )
                Button(onClick = onRequestConsent) { Text("绑定当前对话") }
            }

            else -> {
                AssistantGuidanceCard(
                    title = "还没有消息",
                    body = "这里会显示与当前对话的真实消息，输入内容后发送即可开始。",
                )
            }
        }
    }
}

@Composable
private fun AssistantGuidanceCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(AppRadius.Medium),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.SpaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一条消息：用户气泡右对齐，助手正文左侧连续缝线。 */
@Composable
private fun AssistantMessageRow(
    message: AssistantMessage,
    modifier: Modifier = Modifier,
) {
    if (message.fromUser) {
        UserMessageRow(message = message, modifier = modifier)
    } else {
        AssistantMessageBody(message = message, modifier = modifier)
    }
}

@Composable
private fun UserMessageRow(
    message: AssistantMessage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = AppRadius.Bubble,
                topEnd = AppRadius.Bubble,
                bottomStart = AppRadius.Bubble,
                // 尾部方向角 4dp：方向感来自形状，不靠第二个强调色。
                bottomEnd = AppRadius.ExtraSmall,
            ),
            modifier = Modifier
                .widthIn(max = Dimensions.MessageWidth)
                .fillMaxWidth(Dimensions.MessageWidthFraction),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = Dimensions.SpaceMedium,
                    vertical = Dimensions.SpaceCompact,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                MessageAttachments(message.attachments)
                if (message.text.isNotBlank()) {
                    SelectionContainer { Text(text = message.text, style = MaterialTheme.typography.bodyLarge) }
                }
                MessageStateLabel(message = message, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun AssistantMessageBody(
    message: AssistantMessage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 连续缝线由内容高度驱动：短消息不会拖一条长线，长正文之间也不会断开。
            .stitchGuide()
            .padding(start = Dimensions.StitchGutter),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        MessageAttachments(message.attachments)
        if (message.text.isNotBlank()) {
            SelectionContainer { Text(text = message.text, style = MaterialTheme.typography.bodyLarge) }
        }
        MessageStateLabel(message = message, modifier = Modifier.align(Alignment.Start))
    }
}

/** 附件只显示主进程发布的文件名；不在助理进程解析任何位置。 */
@Composable
private fun MessageAttachments(filenames: List<String>) {
    if (filenames.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
        filenames.forEach { filename ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(AppRadius.Small),
                border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    text = filename,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceTiny)
                        .semantics { contentDescription = "附件：$filename" },
                )
            }
        }
    }
}

/** 状态文字：流式/完成都用文字表达，颜色只是辅助。 */
@Composable
private fun MessageStateLabel(
    message: AssistantMessage,
    modifier: Modifier = Modifier,
) {
    if (!message.streaming) return
    Text(
        text = "正在输出…",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 提示条：本进程的一次性提示（截图缺失等）与主进程 notice 共用同一种呈现。
 *
 * 颜色用 M3 之外的语义 warning 角色（`LocalAppStateColors`）而不是手写色值，
 * 并且始终给出文字结论。
 */
@Composable
internal fun AssistantBanner(
    text: String,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppStateColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.warningContainer,
        contentColor = colors.onWarningContainer,
        shape = RoundedCornerShape(AppRadius.Medium),
        border = BorderStroke(Dimensions.StrokeHairline, colors.warning),
    ) {
        Row(
            modifier = Modifier.padding(
                start = Dimensions.SpaceMedium,
                end = if (onDismiss == null) Dimensions.SpaceMedium else Dimensions.SpaceTiny,
                top = Dimensions.SpaceCompact,
                bottom = Dimensions.SpaceCompact,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = "关闭提示")
                }
            }
        }
    }
}

/**
 * 输入坞：胶囊输入框 + 发送/停止（48dp 触摸目标）。
 *
 * 禁用时在下方直接给出原因文字，而不是只把按钮变灰；提示与状态文字用 `labelMedium`，
 * 可编辑正文本身保持 `bodyLarge`，与工作台输入坞的排版一致。
 */
@Composable
internal fun AssistantComposerDock(
    state: AssistantState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    initialText: String?,
    onUseInitialText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.canInteract
    val blockReason = state.composerBlockReason()
    val sendEnabled = enabled && draft.isNotBlank() && !state.isGenerating

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
        val trimmedInitial = initialText?.takeIf { it.isNotBlank() }
        if (trimmedInitial != null && enabled && draft != trimmedInitial) {
            TextButton(onClick = onUseInitialText) {
                Text("使用所选文字", style = MaterialTheme.typography.labelLarge)
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(AppRadius.Bubble),
            border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .padding(start = Dimensions.SpaceMedium, end = Dimensions.SpaceTiny)
                    .heightIn(min = Dimensions.ComposerMinHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = Dimensions.SpaceSmall),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = if (enabled) "输入消息" else "助理尚不可用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        enabled = enabled,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "助理消息输入" },
                    )
                }
                if (state.isGenerating) {
                    FilledIconButton(
                        onClick = onStop,
                        enabled = !state.isStopping,
                        modifier = Modifier.size(Dimensions.ComposerPrimaryAction),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Stop,
                            contentDescription = if (state.isStopping) "正在停止生成" else "停止生成",
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = sendEnabled,
                        modifier = Modifier.size(Dimensions.ComposerPrimaryAction),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "发送消息",
                        )
                    }
                }
            }
        }
        val statusText = when {
            state.isStopping -> "正在停止生成…"
            state.isGenerating -> "正在生成回复，可随时停止"
            blockReason != null -> blockReason
            draft.isBlank() -> "输入消息后即可发送"
            else -> "发送给当前 Gateway 对话"
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = Dimensions.SpaceTiny)
                .semantics {
                    stateDescription = statusText
                    contentDescription = "输入状态：$statusText"
                },
        )
    }
}

/**
 * 面板内的对话列表。
 *
 * 刻意不用 `DropdownMenu` / `ModalBottomSheet`：系统助理会话的窗口令牌不是 Activity
 * 令牌，在会话窗口里再开子窗口（菜单、弹层、对话框）并不可靠；助理界面因此把所有
 * 浮层能力都做面板内视图。列表内容仍来自主进程发布的真实 `threads`，没有就明确说
 * "还没有可切换的对话"。
 */
@Composable
internal fun AssistantThreadList(
    state: AssistantState,
    onSelectThread: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(AppRadius.Medium),
    ) {
        Column(modifier = Modifier.padding(vertical = Dimensions.SpaceCompact)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimensions.SpaceMedium, end = Dimensions.SpaceTiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "切换对话",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = "关闭对话列表")
                }
            }
            if (state.threads.isEmpty()) {
                Text(
                    text = if (state.canInteract) {
                        "还没有可切换的对话。在 App 里新建或打开一个对话后再试。"
                    } else {
                        "绑定当前对话后才能切换。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceSmall),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.threads, key = { it.id }) { thread ->
                        Text(
                            text = thread.title.ifBlank { "未命名对话" },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = "切换到该对话") { onSelectThread(thread.id) }
                                .heightIn(min = Dimensions.DrawerItemHeight)
                                .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceCompact),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底部动作行：切换对话、屏幕圈选、收起到侧边、打开 App、关闭。
 *
 * 线程列表直接来自桥接状态（`threads`），没有线程时按钮禁用而不是弹一个空菜单；
 * 每个图标按钮都有 contentDescription，触摸目标 48dp。
 */
@Composable
internal fun AssistantActionRow(
    state: AssistantState,
    showScreenSelection: Boolean,
    showDockAction: Boolean,
    showCloseAction: Boolean,
    onOpenThreadList: () -> Unit,
    onStartSelection: () -> Unit,
    onDock: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        TextButton(
            onClick = onOpenThreadList,
            // 允许在"还没有对话"时打开列表：这样空态能给出准确文字，而不是一个点不动的按钮。
            enabled = state.canInteract,
            contentPadding = PaddingValues(horizontal = Dimensions.SpaceCompact),
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceTiny))
            Text("对话", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.weight(1f))
        if (showScreenSelection) {
            IconButton(onClick = onStartSelection) {
                Icon(
                    imageVector = Icons.Outlined.CropFree,
                    contentDescription = "屏幕圈选",
                )
            }
        }
        if (showDockAction) {
            IconButton(onClick = onDock) {
                Icon(
                    imageVector = Icons.Outlined.Minimize,
                    contentDescription = "收起到侧边",
                )
            }
        }
        IconButton(onClick = onOpenApp) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = "打开 App",
            )
        }
        if (showCloseAction) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭助理",
                )
            }
        }
    }
}

/**
 * 绑定当前对话前的确认卡片。
 *
 * 刻意不用 `Dialog`：系统助理会话的窗口令牌不是 Activity 令牌，
 * 在会话里新开一个 `TYPE_APPLICATION` 窗口会被 WindowManager 拒绝。
 * 这里用面板内的卡片承载同一段知情说明，视觉与语义都保持"先解释再提交"。
 */
@Composable
internal fun AssistantConsentCard(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(AppRadius.Medium),
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.SpaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            Text(text = "绑定当前 Gateway 对话", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "助理将显示当前账号的对话。消息和屏幕内容仅在你点击发送后提交，" +
                    "关闭助理不会取消已经被 Gateway 接收的消息。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = onConfirm) { Text("允许") }
            }
        }
    }
}
