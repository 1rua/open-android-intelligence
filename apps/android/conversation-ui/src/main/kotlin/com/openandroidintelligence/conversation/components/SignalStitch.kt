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

/** 缝线圆点半径：略大于线宽的一半，让圆点在缩略尺寸下仍然是一个"点"而不是鼓包。 */
private val StitchDotRadius = 2.3.dp

/**
 * 信号缝线（Signal Stitch）：产品唯一主要视觉签名。
 * 一段 2dp 的墨绿短竖线与一个茶金小圆点（半径约 2.3dp），两端圆头：
 * - 助手正文左侧是竖向引导线（`Modifier.stitchGuide` 由内容高度驱动）；
 * - 欢迎页、登录页与顶栏品牌位展示标志性缝线；
 * - 失败时整条缝线（线与点）变为错误色并保持静止。
 *
 * 遵循规格：不无限旋转、不做呼吸/发光、不抢夺正文注意力。
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
            .widthIn(min = Dimensions.StrokeStitch * 4)
            .defaultMinSize(minWidth = Dimensions.StrokeStitch * 4, minHeight = Dimensions.SpaceLarge),
    ) {
        val stroke = Dimensions.StrokeStitch.toPx()
        val dotRadiusPx = StitchDotRadius.toPx()
        val center = size.width / 2f
        val lineStartY = stroke / 2f
        val dotCenterY = (size.height - dotRadiusPx).coerceAtLeast(dotRadiusPx)
        // 线与点之间留一个线宽的呼吸位，避免小尺寸下糊成一团。
        val lineEndY = (dotCenterY - dotRadiusPx - stroke).coerceAtLeast(lineStartY)

        drawLine(
            color = line,
            start = Offset(center, lineStartY),
            end = Offset(center, lineEndY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        drawCircle(
            color = dot,
            radius = dotRadiusPx,
            center = Offset(center, dotCenterY),
        )
    }
}
