package com.openandroidintelligence.conversation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.motion.m3SharedBounds
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import kotlin.math.roundToInt

/**
 * 形变按钮在常驻、形变与禁用三态下的颜色表。
 *
 * 所有颜色均应来自 [MaterialTheme.colorScheme] 语义角色，严禁硬编码色值。
 */
@Immutable
data class M3MorphingButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val morphedContainerColor: Color,
    val morphedContentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
)

/**
 * [M3MorphingButton] 与 [M3MorphingIconButton] 的 M3 默认配色工厂。
 */
object M3MorphingButtonDefaults {
    private const val DisabledContainerAlpha = 0.12f
    private const val DisabledContentAlpha = 0.38f

    @Composable
    fun primaryColors(
        containerColor: Color = MaterialTheme.colorScheme.primary,
        contentColor: Color = MaterialTheme.colorScheme.onPrimary,
        morphedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
        morphedContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
        disabledContainerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContainerAlpha),
        disabledContentColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha),
    ): M3MorphingButtonColors = M3MorphingButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        morphedContainerColor = morphedContainerColor,
        morphedContentColor = morphedContentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
    )

    @Composable
    fun tonalColors(
        containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
        morphedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
        morphedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledContainerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContainerAlpha),
        disabledContentColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha),
    ): M3MorphingButtonColors = M3MorphingButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        morphedContainerColor = morphedContainerColor,
        morphedContentColor = morphedContentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
    )

    @Composable
    fun sendStopColors(
        containerColor: Color = MaterialTheme.colorScheme.primary,
        contentColor: Color = MaterialTheme.colorScheme.onPrimary,
        morphedContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
        morphedContentColor: Color = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
        disabledContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DisabledContentAlpha),
    ): M3MorphingButtonColors = M3MorphingButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        morphedContainerColor = morphedContainerColor,
        morphedContentColor = morphedContentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
    )
}

/**
 * Material 3 Expressive 形状形变按钮。
 *
 * 将按压微缩放（[MotionSpecs.press]）、圆角百分比弹簧插值（限制在 `0f..50f` 防止过冲崩溃）、
 * 固定宽高切换或自适应宽度形变（[MotionSpecs.morph]）、临界阻尼配色过渡
 * （[MotionSpecs.effectsFast]）及 M3 Ripple 整合到同一容器内。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun M3MorphingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    morphed: Boolean = false,
    enabled: Boolean = true,
    restingWidth: Dp? = null,
    morphedWidth: Dp? = restingWidth,
    height: Dp = Dimensions.MinimumTouchTarget,
    restingCornerPercent: Float = 50f,
    morphedCornerPercent: Float = 36f,
    pressedCornerPercent: Float = 32f,
    pressedScale: Float = MotionSpecs.PressScaleCompact,
    colors: M3MorphingButtonColors = M3MorphingButtonDefaults.primaryColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimensions.SpaceMedium),
    sharedBoundsKey: Any? = null,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.(morphed: Boolean) -> Unit,
) {
    val reduced = LocalMotionPolicy.current.isReduced
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed && !reduced) pressedScale else 1f,
        animationSpec = MotionSpecs.press(reduced),
        label = "m3-morphing-button-scale",
    )
    val targetCornerPercent = when {
        enabled && pressed -> pressedCornerPercent
        morphed -> morphedCornerPercent
        else -> restingCornerPercent
    }
    val animatedCornerPercent by animateFloatAsState(
        targetValue = targetCornerPercent,
        animationSpec = MotionSpecs.spatialFast(reduced),
        label = "m3-morphing-button-corner",
    )
    val cornerPercent = animatedCornerPercent.coerceIn(0f, 50f).roundToInt()
    val shape = RoundedCornerShape(percent = cornerPercent)

    val widthModifier = if (restingWidth != null && morphedWidth != null) {
        val targetWidth = if (morphed) morphedWidth else restingWidth
        val animatedWidth by animateDpAsState(
            targetValue = targetWidth,
            animationSpec = MotionSpecs.spatialFast(reduced),
            label = "m3-morphing-button-width",
        )
        Modifier.width(animatedWidth.coerceAtLeast(Dimensions.MinimumTouchTarget))
    } else {
        Modifier
            .defaultMinSize(minWidth = Dimensions.MinimumTouchTarget)
            .animateContentSize(MotionSpecs.morph(reduced))
    }

    val targetContainerColor = when {
        !enabled -> colors.disabledContainerColor
        morphed -> colors.morphedContainerColor
        else -> colors.containerColor
    }
    val targetContentColor = when {
        !enabled -> colors.disabledContentColor
        morphed -> colors.morphedContentColor
        else -> colors.contentColor
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = MotionSpecs.effectsFast(reduced),
        label = "m3-morphing-button-container",
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = MotionSpecs.effectsFast(reduced),
        label = "m3-morphing-button-content",
    )

    val sharedModifier = if (sharedBoundsKey != null) {
        Modifier.m3SharedBounds(key = sharedBoundsKey, clipShape = shape)
    } else {
        Modifier
    }
    val borderModifier = if (border != null) {
        Modifier.border(border = border, shape = shape)
    } else {
        Modifier
    }
    val minHeight = height.coerceAtLeast(Dimensions.MinimumTouchTarget)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(sharedModifier)
            .then(widthModifier)
            .heightIn(min = minHeight)
            .background(color = containerColor, shape = shape)
            .then(borderModifier)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(
                    modifier = Modifier.padding(contentPadding),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    content(morphed)
                }
            }
        }
    }
}

/**
 * 基于 [M3MorphingButton] 的图标形变按钮，在常驻态与形变态之间执行图标淡入微缩切换。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun M3MorphingIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    morphed: Boolean = false,
    morphedIcon: ImageVector = icon,
    morphedContentDescription: String? = contentDescription,
    enabled: Boolean = true,
    restingWidth: Dp = Dimensions.MinimumTouchTarget,
    morphedWidth: Dp = restingWidth,
    height: Dp = Dimensions.MinimumTouchTarget,
    restingCornerPercent: Float = 50f,
    morphedCornerPercent: Float = 36f,
    pressedCornerPercent: Float = 32f,
    pressedScale: Float = MotionSpecs.PressScaleCompact,
    colors: M3MorphingButtonColors = M3MorphingButtonDefaults.primaryColors(),
    border: BorderStroke? = null,
    iconSize: Dp = Dimensions.Icon,
    sharedBoundsKey: Any? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val reduced = LocalMotionPolicy.current.isReduced
    M3MorphingButton(
        onClick = onClick,
        modifier = modifier,
        morphed = morphed,
        enabled = enabled,
        restingWidth = restingWidth,
        morphedWidth = morphedWidth,
        height = height,
        restingCornerPercent = restingCornerPercent,
        morphedCornerPercent = morphedCornerPercent,
        pressedCornerPercent = pressedCornerPercent,
        pressedScale = pressedScale,
        colors = colors,
        border = border,
        contentPadding = PaddingValues(0.dp),
        sharedBoundsKey = sharedBoundsKey,
        onClickLabel = if (morphed) morphedContentDescription else contentDescription,
        interactionSource = interactionSource,
    ) { isMorphed ->
        AnimatedContent(
            targetState = isMorphed,
            transitionSpec = {
                if (reduced) {
                    fadeIn(MotionSpecs.reducedFade()) togetherWith fadeOut(MotionSpecs.reducedFade())
                } else {
                    (fadeIn(MotionSpecs.effectsFast(false)) +
                        scaleIn(MotionSpecs.spatialFast(false), initialScale = 0.8f))
                        .togetherWith(
                            fadeOut(MotionSpecs.effectsFast(false)) +
                                scaleOut(MotionSpecs.spatialFast(false), targetScale = 0.8f),
                        )
                }
            },
            label = "m3-morphing-icon",
        ) { state ->
            Icon(
                imageVector = if (state) morphedIcon else icon,
                contentDescription = if (state) morphedContentDescription else contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
