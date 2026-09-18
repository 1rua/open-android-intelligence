package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.Composable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 思维链呈现卡片 (Thought Card)：展示推理思考过程。
 * 流式增量阶段默认展开并显示跳动点与光标，思考闭合后平滑自动折叠，支持用户手动展开/收起。
 */
@Composable
fun MarkdownThoughtBlockView(
    block: TimelineBlock.ThoughtBlock,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val motionPolicy = LocalMotionPolicy.current
    val reduceMotion = motionPolicy.reduceMotion

    // 记录用户手动干预偏好，避免流式后续更新强制覆盖用户展开/收起选择
    var userOverridden by remember { mutableStateOf(false) }
    var userExpanded by remember { mutableStateOf(!block.isComplete) }

    val isExpanded = if (userOverridden) userExpanded else !block.isComplete

    val chevronRotation = if (reduceMotion) {
        if (isExpanded) 180f else 0f
    } else {
        animateFloatAsState(
            targetValue = if (isExpanded) 180f else 0f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
            label = "thought-chevron",
        ).value
    }

    val containerModifier = if (reduceMotion) {
        modifier
    } else {
        modifier.animateContentSize(
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
        )
    }

    Surface(
        modifier = containerModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // 标题栏 (可点击折叠/展开)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (isExpanded) "收起思考过程" else "展开思考过程",
                    ) {
                        userOverridden = true
                        userExpanded = !isExpanded
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    if (!block.isComplete) {
                        val titleText = if (isExpanded) "AI 正在思考" else "AI 正在思考 · 点击展开"
                        Text(
                            text = titleText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = (13 * 1.4).sp,
                        )
                        TypingDots(reduced = reduceMotion)
                    } else {
                        val headerText = if (isExpanded) {
                            "思考过程 · 共 ${block.thought.length} 字 · 点击收起"
                        } else {
                            "已折叠思考过程 · 共 ${block.thought.length} 字 · 点击展开"
                        }
                        Text(
                            text = headerText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = (13 * 1.4).sp,
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "收起思考过程" else "展开思考过程",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation),
                )
            }

            // 正文内容区
            if (isExpanded) {
                Spacer(Modifier.height(8.dp))
                if (block.thought.isEmpty() && !block.isComplete) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                        StreamingCursor()
                    }
                } else {
                    val showCursor = !block.isComplete
                    val annotatedThought = remember(block.thought, showCursor) {
                        buildAnnotatedString {
                            append(block.thought)
                            if (showCursor) {
                                append(" ")
                                appendInlineContent("streaming_cursor", "[cursor]")
                            }
                        }
                    }
                    val inlineContent = remember(showCursor) {
                        streamingCursorInlineContent(showCursor, cursorHeight = 13.sp)
                    }

                    val contentModifier = if (isStreaming && !block.isComplete) {
                        Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    } else {
                        Modifier.fillMaxWidth()
                    }

                    SelectionContainer {
                        Text(
                            text = annotatedThought,
                            inlineContent = inlineContent,
                            fontSize = 13.sp,
                            lineHeight = (13 * 1.4).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }
}

