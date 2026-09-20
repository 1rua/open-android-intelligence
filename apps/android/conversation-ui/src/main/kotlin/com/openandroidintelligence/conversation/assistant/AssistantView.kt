package com.openandroidintelligence.conversation.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The one visual identity used by both the expanded assistant bar and the
 * docked ball.
 *
 * [expanded] is deliberately controlled by the caller. The surface itself is
 * kept in the composition and its content is kept mounted while its bounds,
 * corner radius, and content emphasis change. This prevents a late
 * visibility removal from replacing the object during a morph.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantSurface(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onStartSelection: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    AssistantSurfaceLayout(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        onClose = onClose,
        onStartSelection = onStartSelection,
        modifier = modifier,
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantSurfaceLayout(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onStartSelection: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val density = LocalDensity.current
    var dockOnLeft by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .fillMaxSize(),
    ) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val availableHeight = with(density) { maxHeight.toPx() }
        val ballSizePx = with(density) { Dimensions.DockedBall.toPx() }
        val dockMarginPx = with(density) { Dimensions.SpaceSmall.toPx() }
        val geometryMetrics = AssistantGeometryMetrics(
            ballSizePx = ballSizePx,
            dockMarginPx = dockMarginPx,
            expandedMarginPx = with(density) { Dimensions.SpaceMedium.toPx() },
            maxExpandedWidthPx = with(density) { Dimensions.ReadingWidth.toPx() },
            minimumExpandedHeightPx = ballSizePx + dockMarginPx * 2f,
            preferredExpandedHeightPx = ballSizePx * 3f + dockMarginPx * 2f,
        )
        val target = assistantGeometry(
            expanded = expanded,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            dockOnLeft = dockOnLeft,
            metrics = geometryMetrics,
        )

        // Initial values are taken from the first measured target. Subsequent
        // targets animate from the current presentation value, including
        // after an interruption or a window/IME size change.
        val x = remember { Animatable(target.x) }
        val y = remember { Animatable(target.y) }
        val width = remember { Animatable(target.width) }
        val height = remember { Animatable(target.height) }

        LaunchedEffect(target, reduceMotion, dragging) {
            if (dragging) return@LaunchedEffect
            coroutineScope {
                launch { x.animateTo(target.x, MotionSpecs.spatial<Float>(reduceMotion)) }
                launch { y.animateTo(target.y, MotionSpecs.spatial<Float>(reduceMotion)) }
                launch { width.animateTo(target.width, MotionSpecs.spatial<Float>(reduceMotion)) }
                launch { height.animateTo(target.height, MotionSpecs.spatial<Float>(reduceMotion)) }
            }
        }

        val contentAlpha by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = MotionSpecs.fade<Float>(reduceMotion),
            label = "assistant-content-alpha",
        )
        val contentScale by animateFloatAsState(
            targetValue = if (expanded) 1f else 0.94f,
            animationSpec = MotionSpecs.spatial<Float>(reduceMotion),
            label = "assistant-content-scale",
        )
        val ballAlpha by animateFloatAsState(
            targetValue = if (!expanded) 1f else 0f,
            animationSpec = MotionSpecs.fade<Float>(reduceMotion),
            label = "assistant-ball-alpha",
        )
        val cornerRadius by animateDpAsState(
            targetValue = if (expanded) 20.dp else Dimensions.DockedBall / 2,
            animationSpec = MotionSpecs.spatial<Dp>(reduceMotion),
            label = "assistant-corner-radius",
        )
        val containerColor by animateColorAsState(
            targetValue = if (expanded) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            animationSpec = MotionSpecs.fade<Color>(reduceMotion),
            label = "assistant-container-color",
        )
        val outlineColor = MaterialTheme.colorScheme.outlineVariant
        val ballPrimaryColor = MaterialTheme.colorScheme.primary
        val ballAccentColor = MaterialTheme.colorScheme.tertiary

        val surfaceWidth = with(density) { width.value.toDp() }
        val surfaceHeight = with(density) { height.value.toDp() }
        val minX = dockMarginPx
        val maxX = (availableWidth - ballSizePx - dockMarginPx).coerceAtLeast(minX)
        val minY = dockMarginPx
        val maxY = (availableHeight - ballSizePx - dockMarginPx).coerceAtLeast(minY)

        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = containerColor,
                shape = RoundedCornerShape(cornerRadius),
                tonalElevation = 3.dp,
                shadowElevation = if (expanded) 8.dp else 6.dp,
                border = BorderStroke(
                    width = 1.dp,
                    color = outlineColor,
                ),
                modifier = Modifier
                    .offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }
                    .size(surfaceWidth, surfaceHeight)
                    .pointerInput(
                        expanded,
                        availableWidth,
                        availableHeight,
                        dockOnLeft,
                    ) {
                        if (!expanded) {
                            coroutineScope {
                                val tracker = VelocityTracker()
                                var moved = false
                                var dragX = x.value
                                var dragY = y.value
                                var motionJob: Job? = null

                                fun schedulePosition(nextX: Float, nextY: Float) {
                                    motionJob?.cancel()
                                    motionJob = launch {
                                        x.stop()
                                        y.stop()
                                        x.snapTo(nextX)
                                        y.snapTo(nextY)
                                    }
                                }

                                detectDragGestures(
                                    onDragStart = {
                                        motionJob?.cancel()
                                        tracker.resetTracking()
                                        moved = false
                                        dragX = x.value
                                        dragY = y.value
                                        dragging = true
                                    },
                                    onDrag = { change, dragAmount ->
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        dragX = rubberbanded(dragX + dragAmount.x, minX, maxX, availableWidth)
                                        dragY = rubberbanded(dragY + dragAmount.y, minY, maxY, availableHeight)
                                        schedulePosition(dragX, dragY)
                                        moved = moved || dragAmount.getDistance() > 0.5f
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        val velocity = tracker.calculateVelocity()
                                        val finalX = dragX
                                        val finalY = dragY
                                        val didMove = moved
                                        motionJob?.cancel()
                                        motionJob = launch {
                                            try {
                                                x.stop()
                                                y.stop()
                                                x.snapTo(finalX)
                                                y.snapTo(finalY)
                                                if (didMove) {
                                                    val settleLeft = shouldDockOnLeft(
                                                        position = finalX,
                                                        velocityPxPerSecond = velocity.x,
                                                        availableWidth = availableWidth,
                                                    )
                                                    val targetX = if (settleLeft) minX else maxX
                                                    val targetY = settledPosition(
                                                        position = finalY,
                                                        velocityPxPerSecond = velocity.y,
                                                        minimum = minY,
                                                        maximum = maxY,
                                                    )
                                                    dockOnLeft = settleLeft
                                                    if (reduceMotion) {
                                                        x.snapTo(targetX)
                                                        y.snapTo(targetY)
                                                    } else {
                                                        coroutineScope {
                                                            launch {
                                                                x.animateTo(
                                                                    targetValue = targetX,
                                                                    animationSpec = MotionSpecs.MomentumSpring,
                                                                    initialVelocity = velocity.x,
                                                                )
                                                            }
                                                            launch {
                                                                y.animateTo(
                                                                    targetValue = targetY,
                                                                    animationSpec = MotionSpecs.MomentumSpring,
                                                                    initialVelocity = velocity.y,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            } finally {
                                                dragging = false
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        motionJob?.cancel()
                                        dragging = false
                                    },
                                )
                            }
                        }
                    }
                    .clickable(
                        enabled = !expanded,
                        role = Role.Button,
                        onClick = { onExpandedChange(true) },
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = if (expanded) "智能助理，已展开" else "智能助理，已收起"
                        stateDescription = if (expanded) "已展开" else "已收起"
                        customActions = listOf(
                            CustomAccessibilityAction("展开助理") {
                                if (!expanded) {
                                    onExpandedChange(true)
                                    true
                                } else {
                                    false
                                }
                            },
                            CustomAccessibilityAction("收起助理") {
                                if (expanded) {
                                    onExpandedChange(false)
                                    true
                                } else {
                                    false
                                }
                            },
                            CustomAccessibilityAction("移动到左侧") {
                                dockOnLeft = true
                                if (expanded) onExpandedChange(false)
                                true
                            },
                            CustomAccessibilityAction("移动到右侧") {
                                dockOnLeft = false
                                if (expanded) onExpandedChange(false)
                                true
                            },
                            CustomAccessibilityAction("结束助理") {
                                onClose()
                                true
                            },
                        )
                    },
            ) {
                // Both layers stay composed. The crossfade only changes which
                // layer is legible while the clipped Surface morphs beneath it.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = Dimensions.SpaceMedium,
                            vertical = Dimensions.SpaceSmall,
                        )
                        .graphicsLayer {
                            alpha = contentAlpha
                            scaleX = contentScale
                            scaleY = contentScale
                        }
                        .semantics {
                            if (!expanded) invisibleToUser()
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimensions.MinimumTouchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "智能助理",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "当前会话",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onStartSelection) {
                            Icon(
                                imageVector = Icons.Outlined.CropFree,
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.size(Dimensions.SpaceTiny))
                            Text("圈选")
                        }
                        IconButton(onClick = { onExpandedChange(false) }) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "收起助理",
                            )
                        }
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "结束助理",
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // This slot is the real controller-owned assistant
                        // content. It is never replaced by fixture responses.
                        content()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(ballAlpha)
                        .semantics {
                            if (expanded) invisibleToUser()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(Dimensions.DockedBall - Dimensions.SpaceSmall)) {
                        val strokeWidth = Dimensions.StrokeStitch.toPx()
                        drawArc(
                            color = ballPrimaryColor,
                            startAngle = 28f,
                            sweepAngle = 296f,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                        drawCircle(
                            color = ballAccentColor,
                            radius = strokeWidth,
                            center = Offset(size.width * 0.78f, size.height * 0.22f),
                        )
                    }
                }
            }
        }
    }
}
