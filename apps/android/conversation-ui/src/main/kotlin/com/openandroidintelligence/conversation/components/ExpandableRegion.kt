package com.openandroidintelligence.conversation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 全 App 唯一的展开/折叠容器。
 *
 * 统一消费 [AppTransitions.disclosureExpandVertical] 与 [AppTransitions.disclosureCollapseVertical]：
 * 标准态采用无过冲临界阻尼尺寸弹簧（[MotionSpecs.disclosureSize]）+ 透明度弹簧（[MotionSpecs.disclosureFade]）；
 * 系统或应用内开启减少动态时自动降级为纯无位移线性淡化。
 */
@Composable
fun ExpandableRegion(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    reduced: Boolean = LocalMotionPolicy.current.isReduced,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = AppTransitions.disclosureExpandVertical(reduced),
        exit = AppTransitions.disclosureCollapseVertical(reduced),
    ) {
        Column(content = content)
    }
}

/**
 * 共享父级 [Transition] 的展开/折叠容器重载，适用于工作区等多行同频折叠/展开场景：
 * 所有子项挂载在同一 [Transition] 上同步推进并在动画收敛后安全卸载，且与独立 [ExpandableRegion] 共享同一套动效令牌。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ExpandableRegion(
    transition: Transition<Boolean>,
    modifier: Modifier = Modifier,
    reduced: Boolean = LocalMotionPolicy.current.isReduced,
    content: @Composable ColumnScope.() -> Unit,
) {
    transition.AnimatedVisibility(
        visible = { it },
        modifier = modifier,
        enter = AppTransitions.disclosureExpandVertical(reduced),
        exit = AppTransitions.disclosureCollapseVertical(reduced),
    ) {
        Column(content = content)
    }
}

/**
 * 水平展开显现容器：供工具卡片首次出现时从左到右展开显现（RTL 环境下自动镜像为从右到左）。
 *
 * - `!animateEntrance`：跳过入场动画，首帧直接完整呈现（用于历史加载、列表滚动回收与折叠大卡内部子卡）；
 * - `animateEntrance && !reduced`：复用统一折叠曲线 [MotionSpecs.disclosureRevealProgress] 与
 *   [MotionSpecs.disclosureFade]，在绘制层执行圆角水平裁切显现与淡入，首帧测量尺寸稳定，避免文本挤压重排与列表抖动；
 * - `animateEntrance && reduced`：不做水平裁切与位移，降级为 150ms 线性无位移淡化。
 */
@Composable
fun HorizontalDisclosureReveal(
    animateEntrance: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = AppRadius.Compact,
    reduced: Boolean = LocalMotionPolicy.current.isReduced,
    content: @Composable () -> Unit,
) {
    // 仅在首次组合时决定是否播放本次入场动画，避免后续刷新把在途动画打断或回滚。
    val shouldAnimate = remember { animateEntrance }
    if (!shouldAnimate) {
        Box(modifier = modifier) {
            content()
        }
        return
    }

    val initialReveal = remember { if (reduced) 1f else 0f }
    val revealProgress = remember { Animatable(initialReveal) }
    val alphaProgress = remember { Animatable(0f) }
    val clipShapePath = remember { Path() }

    LaunchedEffect(reduced) {
        if (reduced) {
            if (revealProgress.value != 1f) {
                revealProgress.snapTo(1f)
            }
            if (alphaProgress.value < 1f) {
                alphaProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = MotionSpecs.disclosureFade(reduced = true),
                )
            }
        } else {
            coroutineScope {
                if (revealProgress.value < 1f) {
                    launch {
                        revealProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = MotionSpecs.disclosureRevealProgress(reduced = false),
                        )
                    }
                }
                if (alphaProgress.value < 1f) {
                    launch {
                        alphaProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = MotionSpecs.disclosureFade(reduced = false),
                        )
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = alphaProgress.value.coerceIn(0f, 1f)
            }
            .drawWithContent {
                val progress = revealProgress.value.coerceIn(0f, 1f)
                if (progress <= 0f) return@drawWithContent
                if (progress >= 0.999f) {
                    drawContent()
                    return@drawWithContent
                }
                val revealWidth = size.width * progress
                if (revealWidth <= 0f || size.height <= 0f) return@drawWithContent
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - revealWidth else 0f
                val right = if (layoutDirection == LayoutDirection.Rtl) size.width else revealWidth
                val maxRadius = minOf(revealWidth, size.height) / 2f
                val radiusPx = cornerRadius.toPx().coerceIn(0f, maxRadius)
                clipShapePath.rewind()
                clipShapePath.addRoundRect(
                    RoundRect(
                        left = left,
                        top = 0f,
                        right = right,
                        bottom = size.height,
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                    ),
                )
                clipPath(clipShapePath) {
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        content()
    }
}

/**
 * 无条件驱动折叠卡片与工作区分组展开指示箭头的旋转角度，统一消费 [MotionSpecs.disclosureChevron]，
 * 确保动态切换 `reduced` 时不破坏组合槽位结构。
 */
@Composable
fun rememberExpandChevronRotation(
    expanded: Boolean,
    reduced: Boolean = LocalMotionPolicy.current.isReduced,
    label: String = "expand-chevron",
    collapsedDegrees: Float = 0f,
    expandedDegrees: Float = 180f,
): Float {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) expandedDegrees else collapsedDegrees,
        animationSpec = MotionSpecs.disclosureChevron(reduced),
        label = label,
    )
    return rotation
}
