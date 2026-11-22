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

/** 禁用态前景内容不透明度（对齐 MD3 Disabled Content 0.38）。 */
private const val DisabledContentAlpha = 0.38f

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
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (interactive && pressed && !reduced) pressedScale else 1f,
        animationSpec = MotionSpecs.press(reduced),
        label = "m3-expressive-card-scale",
    )
    val targetCorner = when {
        interactive && pressed -> pressedCornerRadius
        selected -> activeCornerRadius
        else -> restingCornerRadius
    }
    val animatedCorner by animateDpAsState(
        targetValue = targetCorner,
        animationSpec = MotionSpecs.spatialFast(reduced),
        label = "m3-expressive-card-corner",
    )
    val shape = RoundedCornerShape(animatedCorner.coerceAtLeast(0.dp))

    val rawTargetContainerColor = if (selected) selectedContainerColor else containerColor
    val absoluteElevation = LocalAbsoluteTonalElevation.current + tonalElevation
    val targetContainerColor = if (
        rawTargetContainerColor == MaterialTheme.colorScheme.surface &&
        tonalElevation > 0.dp &&
        LocalTonalElevationEnabled.current
    ) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(absoluteElevation)
    } else {
        rawTargetContainerColor
    }
    val baseContentColor = if (selected) selectedContentColor else contentColor
    val targetContentColor = if (onClick != null && !enabled) {
        baseContentColor.copy(alpha = baseContentColor.alpha * DisabledContentAlpha)
    } else {
        baseContentColor
    }
    val animatedContainerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = MotionSpecs.effectsFast(reduced),
        label = "m3-expressive-card-container",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = MotionSpecs.effectsFast(reduced),
        label = "m3-expressive-card-content",
    )

    val touchTargetModifier = if (onClick != null) {
        Modifier.defaultMinSize(
            minWidth = Dimensions.MinimumTouchTarget,
            minHeight = Dimensions.MinimumTouchTarget,
        )
    } else {
        Modifier
    }
    val sharedModifier = if (sharedBoundsKey != null) {
        Modifier.m3SharedBounds(key = sharedBoundsKey, clipShape = shape)
    } else {
        Modifier
    }
    val elevationModifier = if (shadowElevation > 0.dp) {
        Modifier.shadow(elevation = shadowElevation, shape = shape, clip = false)
    } else {
        Modifier
    }
    val borderModifier = if (border != null) {
        Modifier.border(border = border, shape = shape)
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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(sharedModifier)
            .then(elevationModifier)
            .background(color = animatedContainerColor, shape = shape)
            .then(borderModifier)
            .clip(shape)
            .then(interactionModifier),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides animatedContentColor,
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
 * 仅将点击区域、按压反馈与 M3 Ripple 挂载在 [header] 容器上（最小触摸高度
 * [Dimensions.MinimumTouchTarget]），避免拦截展开体内的文本选中与复制按钮手势；
 * 外层卡片随展开与按压动态弹簧插值圆角，展开内容由 [ExpandableRegion] 统一承载。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun M3ExpandableExpressiveCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    expandedContainerColor: Color = containerColor,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
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
    val headerInteraction = remember { MutableInteractionSource() }
    val headerPressed by headerInteraction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (enabled && headerPressed && !reduced) pressedScale else 1f,
        animationSpec = MotionSpecs.press(reduced),
        label = "m3-expandable-card-scale",
    )
    val targetCorner = when {
        enabled && headerPressed -> pressedCornerRadius
        expanded -> expandedCornerRadius
        else -> collapsedCornerRadius
    }
    val animatedCorner by animateDpAsState(
        targetValue = targetCorner,
        animationSpec = MotionSpecs.spatialFast(reduced),
        label = "m3-expandable-card-corner",
    )
    val cornerRadius = animatedCorner.coerceAtLeast(0.dp)
    val shape = RoundedCornerShape(cornerRadius)

    val animatedContainerColor by animateColorAsState(
        targetValue = if (expanded) expandedContainerColor else containerColor,
        animationSpec = MotionSpecs.effectsFast(reduced),
        label = "m3-expandable-card-container",
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

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(sharedModifier)
            .background(color = animatedContainerColor, shape = shape)
            .then(borderModifier)
            .clip(shape),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Dimensions.MinimumTouchTarget)
                        .clip(shape)
                        .clickable(
                            interactionSource = headerInteraction,
                            indication = LocalIndication.current,
                            enabled = enabled,
                            onClickLabel = toggleClickLabel,
                            role = Role.Button,
                            onClick = { onExpandedChange(!expanded) },
                        )
                        .then(
                            if (toggleStateDescription != null) {
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
