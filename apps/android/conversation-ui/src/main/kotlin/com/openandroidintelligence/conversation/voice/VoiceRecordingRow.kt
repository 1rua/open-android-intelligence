package com.openandroidintelligence.conversation.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 录音中的输入行：`× 取消 / 波形 + 计时 / 发送`。
 *
 * 主会话的输入栏与默认数字助手的紧凑输入栏共用这一份实现：波形、计时与两个操作按钮
 * 只有一个来源，两处不会随时间漂移成两种动画。计时与柱高都来自宿主的真实录音状态，
 * 组件不自行计时、也不生成伪波形。
 *
 * 无障碍：波形与计时合并成一个朗读节点（「正在录音 / 已录制 N 秒」），
 * 取消与发送保持独立可聚焦，TalkBack 能分别操作。
 *
 * @param elapsedMs 已经录制的真实时长（毫秒）。
 * @param level 当前音量（0..1），用来推进波形窗口。
 */
@Composable
fun VoiceRecordingRow(
    elapsedMs: Long,
    level: Float,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    Surface(
        shape = RoundedCornerShape(AppRadius.Bubble),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Dimensions.SpaceTiny, vertical = Dimensions.SpaceTiny)
                .heightIn(min = Dimensions.ComposerMinHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
        ) {
            RecordingIconAction(
                icon = Icons.Outlined.Close,
                contentDescription = "取消录音",
                onClick = onCancel,
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimensions.SpaceSmall)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "正在录音"
                        stateDescription = "已录制 $totalSeconds 秒"
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                RecordingWaveform(
                    level = level,
                    // 以已录时长为采样键：安静时音量不变，波形也要按真实采样节奏向前滚动。
                    sampleKey = elapsedMs,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatVoiceDuration(elapsedMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RecordingSendAction(onClick = onSend)
        }
    }
}

/** 录音态里的圆形次要操作（取消）：视觉 44dp，触摸目标由 48dp 兜底。 */
@Composable
private fun RecordingIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Dimensions.MinimumTouchTarget)
            .clip(CircleShape)
            .bouncyClickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(Dimensions.ComposerControl),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.Icon),
                )
            }
        }
    }
}

/** 录音态的主操作（发送语音）：与输入态的发送键同尺寸、同圆角、同底色。 */
@Composable
private fun RecordingSendAction(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(Dimensions.ComposerPrimaryAction)
            .height(Dimensions.ComposerPrimaryAction)
            .clip(CircleShape)
            .bouncyClickable(role = Role.Button, onClick = onClick)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "发送语音",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(Dimensions.Icon),
        )
    }
}
