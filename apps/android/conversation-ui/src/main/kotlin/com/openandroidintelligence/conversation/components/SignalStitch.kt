package com.openandroidintelligence.conversation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 信号缝线（Signal Stitch）：产品唯一主要视觉签名。
 * 一段 2–3dp 的墨绿短线与一个茶金小点：
 * - 助手正文左侧是竖向引导线；
 * - 欢迎页与品牌区展示标志性缝线；
 * - 失败时颜色变为错误色并静止。
 * 遵循规格：不无限旋转、不过度抢夺注意力、保持品牌气质。
 */
@Composable
fun SignalStitch(
    failed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val line = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    // 茶金小点走 tertiary 角色：开启动态取色后会跟随系统调色，
    // 但「一条主色短线 + 一个小圆点」的结构签名保持不变。
    val dot = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier
            .widthIn(min = 6.dp)
            .defaultMinSize(minWidth = 8.dp, minHeight = 24.dp),
    ) {
        val stroke = Dimensions.StrokeStitch.toPx().coerceAtLeast(2f)
        val center = size.width / 2f
        val dotRadius = stroke * 1.15f
        val dotCenterY = size.height - dotRadius
        val lineStartY = stroke
        val lineEndY = (dotCenterY - stroke * 2.5f).coerceAtLeast(lineStartY)

        drawLine(
            color = line,
            start = Offset(center, lineStartY),
            end = Offset(center, lineEndY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        drawCircle(
            color = dot,
            radius = dotRadius,
            center = Offset(center, dotCenterY),
        )
    }
}
