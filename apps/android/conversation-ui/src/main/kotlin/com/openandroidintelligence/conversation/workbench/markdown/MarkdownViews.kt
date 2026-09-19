package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.AppRadius

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
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            val isLast = isStreaming && index == blocks.lastIndex
            when (block) {
                is TimelineBlock.Heading -> MarkdownHeadingView(block, isLast, colors)
                is TimelineBlock.Paragraph -> MarkdownParagraphView(block, isLast, colors)
                is TimelineBlock.CodeBlock -> MarkdownCodeBlock(block, isStreaming = isLast)
                is TimelineBlock.Blockquote -> MarkdownBlockquoteView(block, isLast, colors)
                is TimelineBlock.UnorderedList -> MarkdownUnorderedListView(block, isLast, colors)
                is TimelineBlock.OrderedList -> MarkdownOrderedListView(block, isLast, colors)
                is TimelineBlock.Table -> MarkdownTableView(block, isLast, colors)
                is TimelineBlock.ThematicBreak -> MarkdownThematicBreakView()
                is TimelineBlock.ThoughtBlock -> MarkdownThoughtBlockView(block, isStreaming = isLast)
                is TimelineBlock.ToolCallBlock -> MarkdownToolCallView(block)
            }
        }
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
        val annotatedText = remember(block.text, isStreaming, colors) {
            buildMarkdownAnnotatedString(block.text, colors, isStreaming)
        }
        SelectionContainer {
            Text(
                text = annotatedText,
                inlineContent = streamingCursorInlineContent(isStreaming, cursorHeight = (fontSize.value * 0.9).sp),
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = color,
                lineHeight = (fontSize.value * 1.35).sp,
            )
        }
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
    val uriHandler = LocalUriHandler.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotatedText = remember(block.text, isStreaming, colors) {
        buildMarkdownAnnotatedString(block.text, colors, isStreaming)
    }

    SelectionContainer {
        Text(
            text = annotatedText,
            inlineContent = streamingCursorInlineContent(isStreaming),
            onTextLayout = { layoutResult = it },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = modifier
                .fillMaxWidth()
                .pointerInput(annotatedText) {
                    detectTapGestures { offset ->
                        layoutResult?.let { layout ->
                            val position = layout.getOffsetForPosition(offset)
                            annotatedText
                                .getStringAnnotations("URL", position, position)
                                .firstOrNull()
                                ?.let { annotation ->
                                    runCatching { uriHandler.openUri(annotation.item) }
                                }
                        }
                    }
                },
        )
    }
}

@Composable
fun MarkdownBlockquoteView(
    block: TimelineBlock.Blockquote,
    isStreaming: Boolean,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        val annotated = remember(block.text, isStreaming, colors) {
            buildMarkdownAnnotatedString(block.text, colors, isStreaming)
        }
        SelectionContainer {
            Text(
                text = annotated,
                inlineContent = streamingCursorInlineContent(isStreaming),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun MarkdownUnorderedListView(
    block: TimelineBlock.UnorderedList,
    isStreaming: Boolean,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        block.items.forEachIndexed { idx, itemText ->
            val isLast = isStreaming && idx == block.items.lastIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                val annotated = remember(itemText, isLast, colors) {
                    buildMarkdownAnnotatedString(itemText, colors, isLast)
                }
                SelectionContainer {
                    Text(
                        text = annotated,
                        inlineContent = streamingCursorInlineContent(isLast),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownOrderedListView(
    block: TimelineBlock.OrderedList,
    isStreaming: Boolean,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        block.items.forEachIndexed { idx, itemText ->
            val isLast = isStreaming && idx == block.items.lastIndex
            val numStr = "${block.startNumber + idx}."
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = numStr,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 1.dp),
                )
                val annotated = remember(itemText, isLast, colors) {
                    buildMarkdownAnnotatedString(itemText, colors, isLast)
                }
                SelectionContainer {
                    Text(
                        text = annotated,
                        inlineContent = streamingCursorInlineContent(isLast),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
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
                            val annotated = remember(header, colors) {
                                buildMarkdownAnnotatedString(header, colors)
                            }
                            Text(
                                text = annotated,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
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
                                val annotated = remember(cellText, isLastCell, colors) {
                                    buildMarkdownAnnotatedString(cellText, colors, isLastCell)
                                }
                                SelectionContainer {
                                    Text(
                                        text = annotated,
                                        inlineContent = streamingCursorInlineContent(isLastCell, cursorHeight = 13.sp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        textAlign = textAlign,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
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
