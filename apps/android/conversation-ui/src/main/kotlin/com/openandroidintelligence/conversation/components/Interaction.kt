package com.openandroidintelligence.conversation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 带按压形变的可点击修饰符。
 *
 * Material 3 的 ripple 只告诉用户"我收到了这一下"，但对自绘的圆形按钮、列表项、
 * 附件卡片来说，**按下时轻微内收**（0.97，90ms）才是"这个东西按得动"的物理暗示。
 * 用 [composed] 包一层，让组件继续用 `.bouncyClickable { }` 的写法，不必各自维护
 * 交互源与动画状态。
 *
 * - 只在按下时缩放，松手立即回收（[MotionSpecs.press]）；
 * - 减少动态时不做缩放，ripple 与点击语义不变；
 * - 语义（role / onClickLabel）与 [clickable] 完全一致，TalkBack 行为不受影响。
 */
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalMotionPolicy.current.reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = MotionSpecs.press(reduced),
        label = "bouncy-press",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}

/**
 * 助手正文左侧的连续引导线（信号缝线）。
 *
 * 每个消息块各画一段固定高度的缝线时，长段落之间会断开、短消息又会拖一条过长的线。
 * 这里由内容自己的高度驱动：线从正文顶部延伸到末尾的茶金标记点，只画在背景层，
 * 不参与布局测量，也不会因为字体缩放而错位。
 *
 * @param failed 失败时整条线改为错误色并保持静止（不再有额外的循环动画）。
 * @param insetStart 线中心相对内容左边缘的偏移。
 */
fun Modifier.stitchGuide(
    failed: Boolean = false,
    insetStart: Dp = 4.dp,
    topInset: Dp = 6.dp,
): Modifier = composed {
    val lineColor = if (failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val dotColor = if (failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    val stroke = with(LocalDensity.current) { Dimensions.StrokeStitch.toPx() }.coerceAtLeast(2f)
    val insetPx = with(LocalDensity.current) { insetStart.toPx() }
    val topPx = with(LocalDensity.current) { topInset.toPx() }

    drawWithCache {
        val x = insetPx + stroke / 2f
        val dotRadius = stroke * 1.15f
        onDrawBehind {
            val dotCenterY = size.height - dotRadius
            val lineEnd = (dotCenterY - stroke * 2.4f).coerceAtLeast(topPx)
            drawLine(
                color = lineColor,
                start = Offset(x, topPx),
                end = Offset(x, lineEnd),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(color = dotColor, radius = dotRadius, center = Offset(x, dotCenterY))
        }
    }
}
