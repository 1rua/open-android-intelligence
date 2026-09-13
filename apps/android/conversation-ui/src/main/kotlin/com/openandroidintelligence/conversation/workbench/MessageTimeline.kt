package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.Dimensions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 消息 key 由上层惰性列表保存；不把空文本猜测为已删除，也不模拟逐字输出。 */
@Composable
fun MessageTimeline(entries: List<TimelineEntry>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium)) {
        entries.forEach { entry ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (entry.isUser) Arrangement.End else Arrangement.Start) {
                if (!entry.isUser) {
                    SignalStitch(modifier = Modifier.height(Dimensions.MinimumTouchTarget))
                    Spacer(Modifier.width(Dimensions.SpaceSmall))
                }
                Surface(color = if (entry.isUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = Dimensions.MessageWidth)) {
                    Column(Modifier.padding(if (entry.isUser) Dimensions.SpaceMedium else Dimensions.SpaceTiny),
                        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
                        SelectionContainer {
                            Text(entry.text.ifBlank { "此消息没有可显示的文本" }, style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(if (entry.pendingAcceptance) "等待 Gateway 接收" else formatTime(entry.timestamp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(if (entry.isUser) Alignment.End else Alignment.Start))
                    }
                }
            }
        }
    }
}

private fun formatTime(epochMillis: Long): String = if (epochMillis <= 0) "" else
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
