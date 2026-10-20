package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/** Only commands actually reported by the selected Agent are suggested. */
fun resolveCommands(catalogState: Loadable<AgentCommandCatalog>): List<AgentCommand> =
    (catalogState as? Loadable.Ready)?.value?.commands.orEmpty()

/**
 * 提取输入框中首个空格前的子串作为搜索前缀，执行忽略大小写的前缀匹配。
 */
fun filterCommands(
    commands: List<AgentCommand>,
    query: String,
): List<AgentCommand> {
    if (!query.startsWith("/")) return emptyList()
    val prefix = query.substringBefore(' ')
    return commands.filter { it.command.startsWith(prefix, ignoreCase = true) }
}

/**
 * 斜杠命令补全悬浮窗。
 *
 * 视觉：`surfaceContainerHigh` 浮层 + 16dp 圆角 + 轻 tonal/阴影层级，最大高度
 * [Dimensions.CommandMenuHeight]；出现/消失是 M3 淡化 + 4dp 上浮（M11），
 * 过滤结果本身即时更新、不逐项做动画。
 */
@Composable
fun CommandAutocompletePopup(
    catalogState: Loadable<AgentCommandCatalog>,
    query: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = query.startsWith("/"),
    onDismissRequest: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val commands = remember(catalogState) {
        resolveCommands(catalogState)
    }
    val catalogStatus = when (catalogState) {
        Loadable.Idle -> "尚未加载 Agent 命令目录"
        Loadable.Loading -> "正在加载 Agent 命令目录…"
        Loadable.Empty -> "Agent 尚未提供可用命令，回车可原样发送"
        is Loadable.Failed -> "无法加载 Agent 命令目录（${catalogState.code}）"
        is Loadable.Ready -> null
    }
    val retryAvailable = (catalogState as? Loadable.Failed)?.retryable == true
    CommandAutocompletePopup(
        commands = commands,
        query = query,
        onSelect = onSelect,
        modifier = modifier,
        visible = visible,
        onDismissRequest = onDismissRequest,
        catalogStatus = catalogStatus,
        onRetry = onRetry,
        retryAvailable = retryAvailable,
    )
}

@Composable
fun CommandAutocompletePopup(
    commands: List<AgentCommand>,
    query: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = query.startsWith("/"),
    onDismissRequest: (() -> Unit)? = null,
    catalogStatus: String? = null,
    onRetry: (() -> Unit)? = null,
    retryAvailable: Boolean = false,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    var dismissedLocally by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf(query) }

    if (query != lastQuery) {
        if (!query.startsWith("/") || (lastQuery.startsWith("/") && query.length < lastQuery.length)) {
            dismissedLocally = false
        }
        lastQuery = query
    }

    val isVisible = visible && !dismissedLocally && query.startsWith("/")
    val risePx = with(LocalDensity.current) { CommandMenuRise.roundToPx() }

    AnimatedVisibility(
        visible = isVisible,
        enter = if (reduced) {
            // 减少动态也要有一次淡化：直接出现比 150ms 渐变更生硬，也更难察觉上下文变化。
            fadeIn(animationSpec = tween(MotionSpecs.ReducedMotionCrossfadeDuration))
        } else {
            fadeIn(
                animationSpec = tween(MotionSpecs.Fast, easing = MotionSpecs.EmphasizedDecelerateEasing),
            ) + slideInVertically(
                animationSpec = tween(MotionSpecs.Fast, easing = MotionSpecs.EmphasizedDecelerateEasing),
                initialOffsetY = { risePx },
            )
        },
        exit = if (reduced) {
            fadeOut(animationSpec = tween(MotionSpecs.ReducedMotionCrossfadeDuration))
        } else {
            fadeOut(
                animationSpec = tween(MotionSpecs.Fast, easing = MotionSpecs.EmphasizedAccelerateEasing),
            ) + slideOutVertically(
                animationSpec = tween(MotionSpecs.Fast, easing = MotionSpecs.EmphasizedAccelerateEasing),
                targetOffsetY = { risePx },
            )
        },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(AppRadius.Medium),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.SpaceMedium)
                .heightIn(max = Dimensions.CommandMenuHeight),
        ) {
            val matchedCommands = remember(commands, query) {
                filterCommands(commands, query)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimensions.SpaceSmall),
            ) {
                if (catalogStatus != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Dimensions.SpaceCompact, end = Dimensions.SpaceTiny, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = catalogStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (retryAvailable && onRetry != null) {
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                    }
                } else if (matchedCommands.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceCompact),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "Agent 目录中没有匹配命令，回车可原样发送",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
                    ) {
                        items(matchedCommands, key = { it.command }) { command ->
                            CommandItemRow(
                                command = command,
                                onSelect = { selectedCmd ->
                                    dismissedLocally = true
                                    onSelect(selectedCmd)
                                    onDismissRequest?.invoke()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommandItemRow(
    command: AgentCommand,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    // 浮层本身已经是 surfaceContainerHigh；列表项默认透明，只在悬停/按压时提亮，
    // 避免卡片内部再铺一层几乎同色的色块。
    val backgroundColor = if (isHovered || isPressed) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color.Transparent
    }

    Surface(
        onClick = { onSelect(command.command) },
        shape = RoundedCornerShape(AppRadius.Small),
        color = backgroundColor,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimensions.MinimumTouchTarget),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Text(
                    text = command.command,
                    style = AppTextStyles.Mono,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                val hint = command.argumentHint
                if (!hint.isNullOrBlank()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Dimensions.SpaceSmall))
            Text(
                text = command.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 目录只负责发现与填入；原始命令的含义始终由 Agent 解释。向后兼容包装。 */
@Composable
fun CommandMenu(
    catalogState: Loadable<AgentCommandCatalog>,
    query: String,
    onSelect: (String) -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    visible: Boolean = query.startsWith("/"),
    onDismissRequest: (() -> Unit)? = null,
) {
    CommandAutocompletePopup(
        catalogState = catalogState,
        query = query,
        onSelect = onSelect,
        modifier = modifier,
        visible = visible,
        onDismissRequest = onDismissRequest,
        onRetry = onRetry,
    )
}

/** 命令菜单出现/消失时的上浮位移（M11：4dp）。 */
private val CommandMenuRise = 4.dp
