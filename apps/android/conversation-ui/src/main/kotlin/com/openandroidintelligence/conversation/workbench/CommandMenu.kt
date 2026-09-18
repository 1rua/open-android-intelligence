package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 标准内置通用命令（当网关离线、目录为空或加载中时的兜底命令集合）。
 */
val BuiltInCommands: List<AgentCommand> = listOf(
    AgentCommand(
        command = "/models",
        description = "切换或查看活动大模型与参数",
        argumentHint = "[provider]",
    ),
    AgentCommand(
        command = "/status",
        description = "查看当前网关连通性、时延与配对状态",
        argumentHint = null,
    ),
    AgentCommand(
        command = "/review",
        description = "审查代码变更、规范或当前文档",
        argumentHint = "[diff]",
    ),
    AgentCommand(
        command = "/gateway",
        description = "切换或添加连接的 Agent Gateway",
        argumentHint = "<url>",
    ),
    AgentCommand(
        command = "/clear",
        description = "清空当前时间线临时渲染状态",
        argumentHint = null,
    ),
    AgentCommand(
        command = "/help",
        description = "查看所有可用指令与使用指南",
        argumentHint = "[command]",
    ),
    AgentCommand(
        command = "/new",
        description = "结束当前会话并创建全新对话线程",
        argumentHint = null,
    ),
)

/**
 * 将网关目录命令与内置兜底命令合并。网关命令优先，且同名指令去重。
 */
fun mergeCommandCatalogs(
    catalogCommands: List<AgentCommand>?,
    builtInCommands: List<AgentCommand> = BuiltInCommands,
): List<AgentCommand> {
    if (catalogCommands.isNullOrEmpty()) return builtInCommands
    val normalizedCatalog = catalogCommands.map { cmd ->
        if (cmd.command.startsWith("/")) cmd else cmd.copy(command = "/${cmd.command}")
    }
    val existingKeys = normalizedCatalog.map { it.command.lowercase() }.toSet()
    val missingBuiltIns = builtInCommands.filter { it.command.lowercase() !in existingKeys }
    return normalizedCatalog + missingBuiltIns
}

/**
 * 根据网关加载状态解析最终可用指令列表。
 */
fun resolveCommands(
    catalogState: Loadable<AgentCommandCatalog>,
    builtInCommands: List<AgentCommand> = BuiltInCommands,
): List<AgentCommand> {
    val catalogCommands = when (catalogState) {
        is Loadable.Ready -> catalogState.value.commands
        else -> emptyList()
    }
    return mergeCommandCatalogs(catalogCommands, builtInCommands)
}

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
 * 遵循 mobile_motion_preview.html 与规范 Section 5 的视觉层级与动效。
 */
@Composable
fun CommandAutocompletePopup(
    catalogState: Loadable<AgentCommandCatalog>,
    query: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val commands = remember(catalogState) {
        resolveCommands(catalogState)
    }
    CommandAutocompletePopup(
        commands = commands,
        query = query,
        onSelect = onSelect,
        modifier = modifier,
    )
}

@Composable
fun CommandAutocompletePopup(
    commands: List<AgentCommand>,
    query: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion

    AnimatedVisibility(
        visible = query.startsWith("/"),
        enter = if (reduced) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = tween(150)) + slideInVertically(
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
            ) { it / 4 }
        },
        exit = if (reduced) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = tween(100)) + slideOutVertically(
                animationSpec = tween(100),
            ) { it / 4 }
        },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
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
                    .padding(8.dp),
            ) {
                if (matchedCommands.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "未找到匹配命令，回车可原样发送",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(matchedCommands, key = { it.command }) { command ->
                            CommandItemRow(
                                command = command,
                                onSelect = onSelect,
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
    val backgroundColor = if (isHovered || isPressed) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        onClick = { onSelect(command.command) },
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimensions.MinimumTouchTarget),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = command.command,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val hint = command.argumentHint
                if (!hint.isNullOrBlank()) {
                    Text(
                        text = hint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = command.description,
                fontSize = 12.sp,
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
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    CommandAutocompletePopup(
        catalogState = catalogState,
        query = query,
        onSelect = onSelect,
        modifier = modifier,
        onRetry = onRetry,
    )
}
