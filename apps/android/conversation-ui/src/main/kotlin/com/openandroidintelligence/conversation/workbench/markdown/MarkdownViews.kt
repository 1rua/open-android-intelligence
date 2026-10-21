package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 当前引用块嵌套深度。段落/引用文本据此切换为从属色与斜体，
 * 嵌套列表也据此降低文字对比度，避免多级引用一片同色。
 */
internal val LocalMarkdownQuoteDepth = compositionLocalOf { 0 }

/**
 * 完整 Markdown 文档渲染器：根据 AST 节点分发渲染至具体组件，
 * 颜色与圆角一律取自 MaterialTheme 语义角色与 AppRadius 令牌。
 */
@Composable
fun MarkdownDocumentView(
    blocks: List<TimelineBlock>,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = defaultMarkdownColors()

    Column(
        modifier = modifier.fillMaxWidth(),
        // 块间距走 8dp 栅格令牌，和消息正文的行间节奏一致。
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        blocks.forEachIndexed { index, block ->
            val isLast = isStreaming && index == blocks.lastIndex
            MarkdownBlockView(block, isLast, colors)
        }
    }
}

/** 单块渲染入口：文档与引用块共用，引用块递归调用即可呈现多级嵌套。 */
@Composable
internal fun MarkdownBlockView(
    block: TimelineBlock,
    isStreaming: Boolean,
    colors: MarkdownColors,
) {
    when (block) {
        is TimelineBlock.Heading -> MarkdownHeadingView(block, isStreaming, colors)
        is TimelineBlock.Paragraph -> MarkdownParagraphView(block, isStreaming, colors)
        is TimelineBlock.CodeBlock -> MarkdownCodeBlock(block, isStreaming = isStreaming)
        is TimelineBlock.Blockquote -> MarkdownBlockquoteView(block, isStreaming, colors)
        is TimelineBlock.ListBlock -> MarkdownListView(block, isStreaming, colors)
        is TimelineBlock.Table -> MarkdownTableView(block, isStreaming, colors)
        is TimelineBlock.MathBlock -> MarkdownMathBlockView(block)
        is TimelineBlock.ThematicBreak -> MarkdownThematicBreakView()
        is TimelineBlock.ThoughtBlock -> MarkdownThoughtBlockView(block, isStreaming = isStreaming)
        is TimelineBlock.ToolCallBlock -> MarkdownToolCallView(block)
    }
}

@Composable
fun MarkdownHeadingView(
    block: TimelineBlock.Heading,
    isStreaming: Boolean,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
) {
    val level = block.level.coerceIn(1, 6)
    val (fontSize, fontWeight, color, showBottomDivider) = when (level) {
        1 -> Quadruple(18.sp, FontWeight.Bold, MaterialTheme.colorScheme.onSurface, true)
        2 -> Quadruple(16.sp, FontWeight.Bold, MaterialTheme.colorScheme.onSurface, false)
        3 -> Quadruple(15.sp, FontWeight.Bold, MaterialTheme.colorScheme.primary, false)
        else -> Quadruple(14.sp, FontWeight.SemiBold, MaterialTheme.colorScheme.primary, false)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
    ) {
        MarkdownInlineText(
            text = block.text,
            colors = colors,
            isStreaming = isStreaming,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = color,
                lineHeight = (fontSize.value * 1.35).sp,
            ),
            color = color,
        )
        if (showBottomDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
fun MarkdownParagraphView(
    block: TimelineBlock.Paragraph,
    isStreaming: Boolean,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
) {
    val quoteDepth = LocalMarkdownQuoteDepth.current
    // 引用内的正文降一级：字号收一档、斜体 + 从属色，与级联引用条一起表达层级。
    val style = if (quoteDepth > 0) {
        MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
    } else {
        MaterialTheme.typography.bodyLarge
    }
    val color = if (quoteDepth > 0) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    MarkdownInlineText(
        text = block.text,
        colors = colors,
        isStreaming = isStreaming,
        modifier = modifier.fillMaxWidth(),
        style = style,
        color = color,
    )
}

@Composable
fun MarkdownBlockquoteView(
    block: TimelineBlock.Blockquote,
    isStreaming: Boolean,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
) {
    val depth = LocalMarkdownQuoteDepth.current
    // 嵌套越深，引用条越淡，避免多层引用抢正文的视觉焦点。
    val barColor = MaterialTheme.colorScheme.primary.copy(
        alpha = (0.85f - depth * 0.18f).coerceAtLeast(0.4f),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(barColor),
        )
        Spacer(Modifier.width(10.dp))
        CompositionLocalProvider(
            LocalMarkdownQuoteDepth provides depth + 1,
            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
            ) {
                block.blocks.forEachIndexed { index, child ->
                    val isLast = isStreaming && index == block.blocks.lastIndex
                    MarkdownBlockView(child, isLast, colors)
                }
            }
        }
    }
}

/**
 * 统一列表视图：有序 / 无序 / GFM 任务 / 多级嵌套都由 [TimelineBlock.ListBlock] 驱动。
 * 每一级额外缩进 [Dimensions.ListIndent]，标记列固定宽度保证正文左缘对齐。
 */
@Composable
fun MarkdownListView(
    block: TimelineBlock.ListBlock,
    isStreaming: Boolean,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
) {
    val quoteDepth = LocalMarkdownQuoteDepth.current
    val baseColor = if (quoteDepth > 0) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        block.items.forEachIndexed { index, item ->
            val isLast = isStreaming && index == block.items.lastIndex
            val checked = item.taskState == TaskState.CHECKED
            val itemColor = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else baseColor

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimensions.ListIndent * item.level),
                verticalAlignment = Alignment.Top,
            ) {
                ListItemMarker(item = item)
                Spacer(Modifier.width(Dimensions.SpaceSmall))
                MarkdownInlineText(
                    text = item.text,
                    colors = colors,
                    isStreaming = isLast,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    color = itemColor,
                )
            }
        }
    }
}

@Composable
private fun ListItemMarker(item: MarkdownListItem) {
    Box(
        modifier = Modifier
            .width(Dimensions.ListMarkerWidth)
            .padding(top = if (item.taskState != TaskState.NONE) 4.dp else 10.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        when {
            item.taskState != TaskState.NONE -> {
                val checked = item.taskState == TaskState.CHECKED
                Icon(
                    imageVector = if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = if (checked) "已完成" else "未完成",
                    tint = if (checked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(16.dp),
                )
            }

            item.number != null -> Text(
                text = "${item.number}.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.5.sp,
                color = if (item.level > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )

            item.level == 0 -> Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )

            else -> Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@Composable
fun MarkdownMathBlockView(
    block: TimelineBlock.MathBlock,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimensions.SpaceTiny),
        contentAlignment = Alignment.Center,
    ) {
        MarkdownMathBlock(latex = block.latex)
    }
}

@Composable
fun MarkdownTableView(
    block: TimelineBlock.Table,
    isStreaming: Boolean,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val evenBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val oddBg = MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        shape = RoundedCornerShape(AppRadius.Small),
        border = BorderStroke(1.dp, borderColor),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            Column {
                // 表头
                Row(
                    modifier = Modifier
                        .background(headerBg)
                        .height(IntrinsicSize.Min),
                ) {
                    block.headers.forEachIndexed { colIdx, header ->
                        val align = block.alignments.getOrElse(colIdx) { TableAlignment.LEFT }
                        val textAlign = when (align) {
                            TableAlignment.LEFT -> TextAlign.Start
                            TableAlignment.CENTER -> TextAlign.Center
                            TableAlignment.RIGHT -> TextAlign.End
                        }
                        Box(
                            modifier = Modifier
                                .widthIn(min = 90.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = when (align) {
                                TableAlignment.LEFT -> Alignment.CenterStart
                                TableAlignment.CENTER -> Alignment.Center
                                TableAlignment.RIGHT -> Alignment.CenterEnd
                            },
                        ) {
                            MarkdownInlineText(
                                text = header,
                                colors = colors,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                fontSize = 13.sp,
                                textAlign = textAlign,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (colIdx < block.headers.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(borderColor),
                            )
                        }
                    }
                }

                HorizontalDivider(color = borderColor, thickness = 1.dp)

                // 表体行
                block.rows.forEachIndexed { rowIdx, rowCells ->
                    val rowBg = if (rowIdx % 2 == 0) evenBg else oddBg
                    Row(
                        modifier = Modifier
                            .background(rowBg)
                            .height(IntrinsicSize.Min),
                    ) {
                        block.headers.indices.forEach { colIdx ->
                            val cellText = rowCells.getOrElse(colIdx) { "" }
                            val align = block.alignments.getOrElse(colIdx) { TableAlignment.LEFT }
                            val textAlign = when (align) {
                                TableAlignment.LEFT -> TextAlign.Start
                                TableAlignment.CENTER -> TextAlign.Center
                                TableAlignment.RIGHT -> TextAlign.End
                            }
                            val isLastCell = isStreaming &&
                                rowIdx == block.rows.lastIndex &&
                                colIdx == block.headers.lastIndex
                            Box(
                                modifier = Modifier
                                    .widthIn(min = 90.dp)
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                contentAlignment = when (align) {
                                    TableAlignment.LEFT -> Alignment.CenterStart
                                    TableAlignment.CENTER -> Alignment.Center
                                    TableAlignment.RIGHT -> Alignment.CenterEnd
                                },
                            ) {
                                MarkdownInlineText(
                                    text = cellText,
                                    colors = colors,
                                    isStreaming = isLastCell,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    textAlign = textAlign,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (colIdx < block.headers.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(borderColor),
                                )
                            }
                        }
                    }
                    if (rowIdx < block.rows.lastIndex) {
                        HorizontalDivider(color = borderColor, thickness = 1.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownThematicBreakView(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun MarkdownCodeBlock(
    block: TimelineBlock.CodeBlock,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // 沿用最高一级容器角色，让代码块与正文和卡片拉开一层深浅，避免写死编辑器主题色。
    val containerBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        shape = RoundedCornerShape(AppRadius.Medium),
        color = containerBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.ExtraSmall))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(block.code))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "复制代码",
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = if (copied) "已复制" else "复制",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                val cursorInlineId = "streaming_code_cursor"
                val annotatedCode = remember(block.code, isStreaming) {
                    AnnotatedString.Builder().apply {
                        append(block.code)
                        if (isStreaming) {
                            append(" ")
                            appendInlineContent(cursorInlineId, "[cursor]")
                        }
                    }.toAnnotatedString()
                }
                val inlineContent = streamingCursorInlineContent(isStreaming, cursorInlineId, 14.sp)
                SelectionContainer {
                    Text(
                        text = annotatedCode,
                        inlineContent = inlineContent,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
