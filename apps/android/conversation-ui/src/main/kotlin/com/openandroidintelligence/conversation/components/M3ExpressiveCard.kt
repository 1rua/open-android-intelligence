package com.openandroidintelligence.conversation.components

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.motion.m3SharedBounds
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced

/** 禁用态容器不透明度（对齐 MD3 Disabled Container 0.12，DESIGN.md §3.1）。 */
private const val DisabledContainerAlpha = 0.12f

/** 禁用态前景内容不透明度（对齐 MD3 Disabled Content 0.38，DESIGN.md §3.1）。 */
private const val DisabledContentAlpha = 0.38f

private data class ExpressiveCardVisuals(
    val shape: RoundedCornerShape,
    val contentColor: Color,
    val cardModifier: Modifier,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberExpressiveCardVisuals(
    reduced: Boolean,
    interactive: Boolean,
    pressed: Boolean,
    targetCorner: Dp,
    targetContainerColor: Color,
    targetContentColor: Color,
    pressedScale: Float,
    border: BorderStroke?,
    sharedBoundsKey: Any?,
    shadowElevation: Dp,
    labelPrefix: String,
): ExpressiveCardVisuals {
    val scale by animateFloatAsState(
        targetValue = if (interactive && pressed && !reduced) pressedScale else 1f,
        animationSpec = MotionSpecs.press(reduced),
        label = "$labelPrefix-scale",
    )
    val animatedCorner by animateDpAsState(
        targetValue = targetCorner,
        animationSpec = MotionSpecs.spatialFast(reduced),
        label = "$labelPrefix-corner",
    )
    val shape = RoundedCornerShape(animatedCorner.coerceAtLeast(0.dp))
    val animatedContainerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = MotionSpecs.effectsFast(reduced),
        label = "$labelPrefix-container",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = MotionSpecs.effectsFast(reduced),
        label = "$labelPrefix-content",
    )
    val cardModifier = Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (sharedBoundsKey != null) {
                Modifier.m3SharedBounds(key = sharedBoundsKey, clipShape = shape)
            } else {
                Modifier
            },
        )
        .then(
            if (shadowElevation > 0.dp) {
                Modifier.shadow(elevation = shadowElevation, shape = shape, clip = false)
            } else {
                Modifier
            },
        )
        .background(color = animatedContainerColor, shape = shape)
        .then(
            if (border != null) {
                Modifier.border(border = border, shape = shape)
            } else {
                Modifier
            },
        )
        .clip(shape)
    return ExpressiveCardVisuals(
        shape = shape,
        contentColor = animatedContentColor,
        cardModifier = cardModifier,
    )
}

/**
 * Material 3 Expressive 动态卡片容器。
 *
 * 将按压微缩放（[MotionSpecs.press]）、动态圆角形变（[MotionSpecs.spatialFast]）、
 * 临界阻尼容器/内容色过渡（[MotionSpecs.effectsFast]）、M3 State Layer Ripple 与
 * 可选共享边界（[m3SharedBounds]）封装为统一容器；开启减少动态时自动降级，
 * 并对圆角弹簧做下界保护防止过冲变负。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun M3ExpressiveCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    selectedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    selectedContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    disabledContainerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContainerAlpha),
    disabledContentColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha),
    border: BorderStroke? = null,
    restingCornerRadius: Dp = AppRadius.Medium,
    pressedCornerRadius: Dp = AppRadius.Large,
    activeCornerRadius: Dp = AppRadius.Large,
    pressedScale: Float = MotionSpecs.PressScaleDefault,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    sharedBoundsKey: Any? = null,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentPadding: PaddingValues = PaddingValues(Dimensions.SpaceMedium),
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalMotionPolicy.current.isReduced
    val interactive = onClick != null && enabled
    val disabledInteractive = onClick != null && !enabled
    val pressed by interactionSource.collectIsPressedAsState()

    val targetCorner = when {
        interactive && pressed -> pressedCornerRadius
        selected -> activeCornerRadius
        else -> restingCornerRadius
    }
    val rawTargetContainerColor = if (selected) selectedContainerColor else containerColor
    val absoluteElevation = LocalAbsoluteTonalElevation.current + tonalElevation
    val resolvedContainerColor = if (
        rawTargetContainerColor == MaterialTheme.colorScheme.surface &&
        tonalElevation > 0.dp &&
        LocalTonalElevationEnabled.current
    ) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(absoluteElevation)
    } else {
        rawTargetContainerColor
    }
    val targetContainerColor = if (disabledInteractive) disabledContainerColor else resolvedContainerColor
    val baseContentColor = if (selected) selectedContentColor else contentColor
    val targetContentColor = if (disabledInteractive) disabledContentColor else baseContentColor

    val visuals = rememberExpressiveCardVisuals(
        reduced = reduced,
        interactive = interactive,
        pressed = pressed,
        targetCorner = targetCorner,
        targetContainerColor = targetContainerColor,
        targetContentColor = targetContentColor,
        pressedScale = pressedScale,
        border = border,
        sharedBoundsKey = sharedBoundsKey,
        shadowElevation = shadowElevation,
        labelPrefix = "m3-expressive-card",
    )

    val touchTargetModifier = if (onClick != null) {
        Modifier.defaultMinSize(
            minWidth = Dimensions.MinimumTouchTarget,
            minHeight = Dimensions.MinimumTouchTarget,
        )
    } else {
        Modifier
    }
    val interactionModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
    } else {
        Modifier.semantics(mergeDescendants = false) {
            isTraversalGroup = true
        }
    }

    Box(
        modifier = modifier
            .then(touchTargetModifier)
            .then(visuals.cardModifier)
            .then(interactionModifier),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides visuals.contentColor,
            LocalAbsoluteTonalElevation provides absoluteElevation,
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}

/**
 * Material 3 Expressive 可展开卡片容器。
 *
 * 当提供 [onExpandedChange] 时，仅将点击区域、按压反馈与 M3 Ripple 挂载在 [header] 容器上
 * （最小触摸高度 [Dimensions.MinimumTouchTarget]），避免拦截展开体内的文本选中与复制按钮手势；
 * 当 [onExpandedChange] 为 `null` 时作为只读信息卡渲染（不暴露按钮语义且不应用禁用态置灰）。
 * 外层卡片随展开与按压动态弹簧插值圆角，展开内容由 [ExpandableRegion] 统一承载。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun M3ExpandableExpressiveCard(
    expanded: Boolean,
    onExpandedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    expandedContainerColor: Color = containerColor,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContainerAlpha),
    disabledContentColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha),
    border: BorderStroke? = null,
    collapsedCornerRadius: Dp = AppRadius.Compact,
    expandedCornerRadius: Dp = AppRadius.Medium,
    pressedCornerRadius: Dp = AppRadius.Medium,
    pressedScale: Float = MotionSpecs.PressScaleDefault,
    sharedBoundsKey: Any? = null,
    toggleClickLabel: String? = null,
    toggleStateDescription: String? = null,
    header: @Composable ColumnScope.() -> Unit,
    expandedContent: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalMotionPolicy.current.isReduced
    val clickableHeader = onExpandedChange != null
    val interactive = clickableHeader && enabled
    val disabledInteractive = clickableHeader && !enabled
    val headerInteraction = remember { MutableInteractionSource() }
    val headerPressed by headerInteraction.collectIsPressedAsState()

    val targetCorner = when {
        interactive && headerPressed -> pressedCornerRadius
        expanded -> expandedCornerRadius
        else -> collapsedCornerRadius
    }
    val targetContainerColor = when {
        disabledInteractive -> disabledContainerColor
        expanded -> expandedContainerColor
        else -> containerColor
    }
    val targetContentColor = if (disabledInteractive) disabledContentColor else contentColor
    val visuals = rememberExpressiveCardVisuals(
        reduced = reduced,
        interactive = interactive,
        pressed = headerPressed,
        targetCorner = targetCorner,
        targetContainerColor = targetContainerColor,
        targetContentColor = targetContentColor,
        pressedScale = pressedScale,
        border = border,
        sharedBoundsKey = sharedBoundsKey,
        shadowElevation = 0.dp,
        labelPrefix = "m3-expandable-card",
    )
    val headerClickModifier = if (onExpandedChange != null) {
        Modifier.clickable(
            interactionSource = headerInteraction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = toggleClickLabel,
            role = Role.Button,
            onClick = { onExpandedChange(!expanded) },
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier.then(visuals.cardModifier),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides visuals.contentColor) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Dimensions.MinimumTouchTarget)
                        .clip(visuals.shape)
                        .then(headerClickModifier)
                        .then(
                            if (clickableHeader && toggleStateDescription != null) {
                                Modifier.semantics { stateDescription = toggleStateDescription }
                            } else {
                                Modifier
                            },
                        ),
                    verticalArrangement = Arrangement.Center,
                    content = header,
                )
                ExpandableRegion(
                    expanded = expanded,
                    reduced = reduced,
                    content = expandedContent,
                )
            }
        }
    }
}
