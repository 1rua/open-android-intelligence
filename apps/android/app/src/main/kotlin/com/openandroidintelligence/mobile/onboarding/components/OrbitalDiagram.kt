package com.openandroidintelligence.mobile.onboarding.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.min

/*
 * 以下常量是原型 SVG 的「设计单位」，与 dp 无关：整张图以 348×226 的设计画布绘制，
 * 运行时按传入尺寸等比缩放。把设计值集中在这里，是为了让后续对照原型改图时
 * 只改一处，而不是在 draw 调用里散落魔法数字。
 */
private const val DesignWidth = 348f
private const val DesignHeight = 226f

/** 三层同心圆半径：外 98 / 中 74 / 内 52（原型 viewBox 坐标）。 */
private const val OuterRadius = 98f
private const val MiddleRadius = 74f
private const val InnerRadius = 52f

/** 中点轨道弧的半径与外轨一致：弧就是"外圈这一段"。 */
private const val TrackRadius = 98f

/** 左右连接节点半径。 */
private const val NodeRadius = 4.5f

/** 顶部远端网关节点半径。 */
private const val AccentDotRadius = 3.5f

/** 坐标十字短线总长（跨内外各一半）。 */
private const val CrossTickLength = 18f

/** 各条线的设计线宽。 */
private const val HairlineWidth = 1f
private const val InnerCircleWidth = 1.5f
private const val TrackWidth = 1.25f
private const val NodeStrokeWidth = 1.25f

/**
 * 轨道图（Orbital Diagram）：首次启动欢迎页的品牌主视觉。
 *
 * 画面语言是「一个设备、一条链路、一个远端：
 * - 三层同心圆表示信标/覆盖范围，中线是虚线（未完成的补充轨道）；
 * - 上半圈实线弧 + 下半圈虚线弧表示「手机 → 网关」这一条主链路；
 * - 四向坐标十字把图形钉在图纸上，暗示这是工程制图而不是插画；
 * - 左右端点的小圆是链路两端，顶部强调色圆点是远端网关应达的位置。
 *
 * 实现约束：
 * 1. **纯装饰**：无障碍语义被显式清空，读屏不会把它当成内容朗读；
 * 2. **零位图**：所有几何都用 Canvas 现画，不引资源、不随主题换图；
 * 3. **等比缩放**：所有长度都乘同一个 scale（取宽高约束中更小的一档），
 *    因此在任何容器里都不会被拉扁或裁切，也不需要外层再包 aspectRatio；
 * 4. 线宽在缩放后按 1 物理像素兜底，避免小尺寸下图线消失。
 *
 * @param accentDot 是否绘制顶部强调色圆点。多页复用时若该页已有别的强调元素，
 *   可关掉它，让画面回到纯线框。
 */
@Composable
fun OrbitalDiagram(
    modifier: Modifier = Modifier,
    accentDot: Boolean = true,
) {
    // 取色一律走语义角色：展开动态取色（Monet）后整张图会跟着主题走。
    // 原型里 --line / --line-strong / --ink-2 的层级关系在这里对应
    // outlineVariant（最弱）→ outline（中）→ onSurfaceVariant（最清楚）。
    val subtleLine = MaterialTheme.colorScheme.outlineVariant
    val strongLine = MaterialTheme.colorScheme.outline
    val trackLine = MaterialTheme.colorScheme.onSurfaceVariant
    // 端点圆填充 surface，让圆"挖空"线框，读起来是一个节点而不是线上的鼓包。
    val nodeFill = MaterialTheme.colorScheme.surface
    val nodeStroke = MaterialTheme.colorScheme.onSurfaceVariant
    // 强调色点与品牌缝线的茶金小点同一角色，保持全局只有一个强调色。
    val accentColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        // 清空语义树：这张图没有可朗读内容，读屏应直接跳过。
        modifier = modifier.clearAndSetSemantics {},
    ) {
        // 设计画布到实际像素的缩放比。取两者更小值：容器比设计图更扁时按宽度收，
        // 更高时按高度收，任何比例下图形都完整落在画布内。
        val scale = min(size.width / DesignWidth, size.height / DesignHeight)
        if (scale <= 0f) return@Canvas

        val center = Offset(size.width / 2f, size.height / 2f)
        // 缩放的线宽至少保留 1 物理像素，否则细线在低密度设备上会整体消失。
        fun hairline(): Float = (HairlineWidth * scale).coerceAtLeast(1f)

        drawConcentricRings(
            center = center,
            scale = scale,
            subtleLine = subtleLine,
            strongLine = strongLine,
        )
        drawTracks(
            center = center,
            scale = scale,
            strongLine = trackLine,
            subtleLine = subtleLine,
        )
        drawCoordinateCross(
            center = center,
            scale = scale,
            color = strongLine,
            strokePx = hairline(),
        )
        drawEndpoints(
            center = center,
            scale = scale,
            fill = nodeFill,
            stroke = nodeStroke,
            strokePx = (NodeStrokeWidth * scale).coerceAtLeast(1f),
        )
        if (accentDot) {
            drawCircle(
                color = accentColor,
                radius = AccentDotRadius * scale,
                center = Offset(center.x, center.y - TrackRadius * scale),
            )
        }
    }
}

/**
 * 三层同心圆：最外实线细圈、中线虚线（未完成轨道）、内圈实线略粗（实体核心）。
 *
 * 三者共用一个圆心，只有半径与线型不同——这是「覆盖范围」的读法，
 * 所以不画成同心圆以外的任何形状。
 */
private fun DrawScope.drawConcentricRings(
    center: Offset,
    scale: Float,
    subtleLine: Color,
    strongLine: Color,
) {
    drawCircle(
        color = subtleLine,
        radius = OuterRadius * scale,
        center = center,
        style = Stroke(width = (HairlineWidth * scale).coerceAtLeast(1f)),
    )
    drawCircle(
        color = subtleLine,
        radius = MiddleRadius * scale,
        center = center,
        style = Stroke(
            width = (HairlineWidth * scale).coerceAtLeast(1f),
            // 2-4 的短虚线：近距离看是断线，远看仍是一圈，强调"计划中"。
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(2f * scale, 4f * scale),
            ),
        ),
    )
    drawCircle(
        color = strongLine,
        radius = InnerRadius * scale,
        center = center,
        style = Stroke(width = (InnerCircleWidth * scale).coerceAtLeast(1f)),
    )
}

/**
 * 主链路的两段半弧：上半圈实线（已建立的链路）、下半圈虚线（尚未连通的回程）。
 *
 * 两段都从左右端点出发，因此端点的圆正好落在弧线端头，不需要另算坐标。
 */
private fun DrawScope.drawTracks(
    center: Offset,
    scale: Float,
    strongLine: Color,
    subtleLine: Color,
) {
    val radiusPx = TrackRadius * scale
    val topLeft = Offset(center.x - radiusPx, center.y - radiusPx)
    val arcSize = Size(radiusPx * 2f, radiusPx * 2f)
    val strokePx = (TrackWidth * scale).coerceAtLeast(1f)

    // 180° 起、顺时针 180°：从左侧端点经顶部到右侧端点。
    drawArc(
        color = strongLine,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokePx, cap = StrokeCap.Round),
    )
    // 0° 起、顺时针 180°：从右侧端点经底部回到左侧端点，虚线表示回程待建立。
    drawArc(
        color = subtleLine,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(
            width = (HairlineWidth * scale).coerceAtLeast(1f),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(3f * scale, 5f * scale),
            ),
        ),
    )
}

/**
 * 四向坐标十字：上下左右各一条 18 长的短线，跨越最外圈圆边。
 *
 * 它的作用不是装饰而是"定标"——告诉读者这张图有中心、有轴向，
 * 所以四条线等长、对称，且都以最外圈半径为中心点向内外各伸出半条。
 */
private fun DrawScope.drawCoordinateCross(
    center: Offset,
    scale: Float,
    color: Color,
    strokePx: Float,
) {
    val half = CrossTickLength * scale / 2f
    val radiusPx = OuterRadius * scale

    fun tick(start: Offset, end: Offset) {
        drawLine(color = color, start = start, end = end, strokeWidth = strokePx)
    }

    // 上
    tick(
        Offset(center.x, center.y - radiusPx - half),
        Offset(center.x, center.y - radiusPx + half),
    )
    // 下
    tick(
        Offset(center.x, center.y + radiusPx - half),
        Offset(center.x, center.y + radiusPx + half),
    )
    // 左
    tick(
        Offset(center.x - radiusPx - half, center.y),
        Offset(center.x - radiusPx + half, center.y),
    )
    // 右
    tick(
        Offset(center.x + radiusPx - half, center.y),
        Offset(center.x + radiusPx + half, center.y),
    )
}

/**
 * 链路两端节点：surface 填充 + onSurfaceVariant 细描边。
 *
 * 填充 surface 是为了让圆盖住弧线端头，端点看起来是独立节点；
 * 描边用比填充强的角色，保证在深浅两套主题下都能看到圆边界。
 */
private fun DrawScope.drawEndpoints(
    center: Offset,
    scale: Float,
    fill: Color,
    stroke: Color,
    strokePx: Float,
) {
    val radiusPx = NodeRadius * scale
    val radius = TrackRadius * scale
    listOf(
        Offset(center.x - radius, center.y),
        Offset(center.x + radius, center.y),
    ).forEach { nodeCenter ->
        drawCircle(color = fill, radius = radiusPx, center = nodeCenter)
        drawCircle(
            color = stroke,
            radius = radiusPx,
            center = nodeCenter,
            style = Stroke(width = strokePx),
        )
    }
}
