package com.openandroidintelligence.conversation.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 展开态高度下界：低于它输入坞与一条消息无法同时可见（规格 §5.5）。 */
private val ExpandedMinHeight = 240.dp

/** 内容层里消息区的期望高度下限：至少让一行对话可读。 */
private val ContentMinHeight = Dimensions.MinimumTouchTarget

/** 内容错峰（M16）：标题先到，消息区与输入坞晚一步进入，总窗口约 270ms。 */
private const val ContentStaggerDelayMillis = 60

/**
 * 展开态助理栏与停靠球共用的唯一视觉身份。
 *
 * [expanded] 由调用方受控。表面始终留在组合中：只有边界、圆角、位置与内容强调在形变，
 * 从不通过 `if (expanded)` 重建子树。内容层永远挂载，因此展开/收起的动画期间不需要
 * 重新测量富文本——形变时它按**展开目标尺寸**布局，再用 `graphicsLayer` 缩放到当前
 * 表面尺寸。
 *
 * @param footer 固定输入坞（不参与滚动，展开态独占 IME inset）。
 * @param overlay 浮层槽位（例如命令菜单）：绝对定位在输入坞上方，不挤压消息区。
 * @param content 可滚动内容（消息区），由调用方承载懒列表。
 */
@Composable
fun AssistantSurface(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onStartSelection: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "智能助理",
    subtitle: String = "当前会话",
    footer: @Composable ColumnScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    AssistantSurfaceLayout(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        onClose = onClose,
        onStartSelection = onStartSelection,
        title = title,
        subtitle = subtitle,
        footer = footer,
        overlay = overlay,
        content = content,
        modifier = modifier,
    )
}

@Composable
private fun AssistantSurfaceLayout(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onStartSelection: () -> Unit,
    title: String,
    subtitle: String,
    footer: @Composable ColumnScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    modifier: Modifier,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val density = LocalDensity.current
    var dockOnLeft by rememberSaveable { mutableStateOf(false) }

    // 手势回调用最新引用，避免 pointerInput 因外部 lambda 变化而重启。
    val currentOnExpandedChange = rememberUpdatedState(onExpandedChange)
    val currentOnToggleExpanded = rememberUpdatedState<(Boolean) -> Unit> { onExpandedChange(it) }

    BoxWithConstraints(
        // 外层只吃系统栏与挖孔，**不含 IME**：键盘高度由 input 坞自己的 imePadding 承担，
        // 否则键盘弹起会把整个浮层可用矩形缩短，面板会先缩一次再位移一次。
        modifier = modifier
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .fillMaxSize(),
    ) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val availableHeight = with(density) { maxHeight.toPx() }
        val ballSizePx = with(density) { Dimensions.DockedBall.toPx() }
        val dockMarginPx = with(density) { Dimensions.SpaceSmall.toPx() }
        val expandedMarginPx = with(density) { Dimensions.SpaceMedium.toPx() }
        val minContentPx = with(density) { ContentMinHeight.toPx() }
        val verticalPaddingPx = with(density) { Dimensions.SpaceSmall.toPx() }
        val gapPx = with(density) { Dimensions.SpaceSmall.toPx() }

        // 内容驱动的高度：标题栏 + 消息区下限 + 输入坞（含内部间距与内边距）。
        var headerHeightPx by remember { mutableFloatStateOf(0f) }
        var footerHeightPx by remember { mutableFloatStateOf(0f) }
        val contentHeightPx = headerHeightPx + footerHeightPx +
            minContentPx + gapPx + verticalPaddingPx * 2f

        val geometryMetrics = AssistantGeometryMetrics(
            ballSizePx = ballSizePx,
            dockMarginPx = dockMarginPx,
            expandedMarginPx = expandedMarginPx,
            maxExpandedWidthPx = with(density) { Dimensions.ReadingWidth.toPx() },
            minimumExpandedHeightPx = with(density) { ExpandedMinHeight.toPx() },
            preferredExpandedHeightPx = with(density) { ExpandedMinHeight.toPx() },
        )
        val target = assistantGeometry(
            expanded = expanded,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            dockOnLeft = dockOnLeft,
            metrics = geometryMetrics,
            contentHeightPx = contentHeightPx,
        )
        // 内容层始终按展开目标尺寸布局：收起态只把它缩放/淡化，绝不重排。
        val expandedTarget = assistantGeometry(
            expanded = true,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            dockOnLeft = dockOnLeft,
            metrics = geometryMetrics,
            contentHeightPx = contentHeightPx,
        )

        // 位置是唯一可被手势直接写的量：拖动期用普通 float 状态 1:1 跟手，
        // 松手后才交给 Animatable 带初速度收敛，不再每帧 cancel/launch。
        var positionX by remember { mutableFloatStateOf(target.x) }
        var positionY by remember { mutableFloatStateOf(target.y) }
        var settling by remember { mutableStateOf(false) }
        // 吸附动画的代际：取消旧动画时它的 finally 不能再把新的 settling 清成 false。
        var settleGeneration by remember { mutableStateOf(0) }

        val containerCorner = animateDpAsState(
            targetValue = if (expanded) AppRadius.Bubble else Dimensions.DockedBall / 2,
            animationSpec = MotionSpecs.morph(reduceMotion),
            label = "assistant-corner-radius",
        )
        val containerColor = animateColorAsState(
            targetValue = if (expanded) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            animationSpec = MotionSpecs.morph(reduceMotion),
            label = "assistant-container-color",
        )
        val animatedWidth = animateFloatAsState(
            targetValue = target.width,
            animationSpec = MotionSpecs.morph(reduceMotion),
            label = "assistant-width",
        )
        val animatedHeight = animateFloatAsState(
            targetValue = target.height,
            animationSpec = MotionSpecs.morph(reduceMotion),
            label = "assistant-height",
        )
        val contentAlpha = animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = MotionSpecs.fade(reduceMotion),
            label = "assistant-content-alpha",
        )
        val ballAlpha = animateFloatAsState(
            targetValue = if (!expanded) 1f else 0f,
            animationSpec = MotionSpecs.fade(reduceMotion),
            label = "assistant-ball-alpha",
        )
        // M16 的内容错峰：标题先出现，消息区与输入坞晚 60ms 跟上（退出时不延迟）。
        val detailAlpha = animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (reduceMotion) {
                    MotionSpecs.ReducedMotionCrossfadeDuration
                } else {
                    MotionSpecs.Fast + ContentStaggerDelayMillis
                },
                delayMillis = if (expanded && !reduceMotion) ContentStaggerDelayMillis else 0,
                easing = MotionSpecs.LinearOutSlowInEasing,
            ),
            label = "assistant-detail-alpha",
        )

        val scope = rememberCoroutineScope()
        val positionJobHolder = remember { mutableStateOf<Job?>(null) }

        // 展开/收起的几何形变：位置与尺寸用同一条临界阻尼弹簧族，同步收敛。
        // 目标位置也在 key 里：内容高度是首帧测量出来的，height 变化会改变展开态
        // 的落点，位置必须跟着同一次形变走，否则面板会停在旧落点上。
        // 拖动期不参与（位置由手指直接写），松手动画进行中也不抢占。
        LaunchedEffect(expanded, target.x, target.y, availableWidth, availableHeight, settling) {
            // settling 变化也要重新触发：吸附刚结束时位置在球侧，展开态必须再收敛到居中落点。
            if (settling) return@LaunchedEffect
            val restMinX = dockMarginPx
            val restMaxX = (availableWidth - ballSizePx - dockMarginPx).coerceAtLeast(restMinX)
            val restMinY = dockMarginPx
            val restMaxY = (availableHeight - ballSizePx - dockMarginPx).coerceAtLeast(restMinY)
            val destinationX: Float
            val destinationY: Float
            if (expanded) {
                destinationX = target.x
                destinationY = target.y
            } else {
                destinationX = if (dockOnLeft) restMinX else restMaxX
                destinationY = positionY.coerceIn(restMinY, restMaxY)
            }
            positionJobHolder.value?.cancel()
            coroutineScope {
                launch {
                    val animation = Animatable(positionX)
                    animation.animateTo(destinationX, MotionSpecs.morph(reduceMotion)) {
                        positionX = value
                    }
                }
                launch {
                    val animation = Animatable(positionY)
                    animation.animateTo(destinationY, MotionSpecs.morph(reduceMotion)) {
                        positionY = value
                    }
                }
            }
        }

        val surfaceWidth = with(density) { animatedWidth.value.toDp() }
        val surfaceHeight = with(density) { animatedHeight.value.toDp() }
        val contentWidth = with(density) { expandedTarget.width.toDp() }
        val contentHeight = with(density) { expandedTarget.height.toDp() }
        val contentScaleX =
            if (expandedTarget.width > 0f) animatedWidth.value / expandedTarget.width else 1f
        val contentScaleY =
            if (expandedTarget.height > 0f) animatedHeight.value / expandedTarget.height else 1f
        val minX = dockMarginPx
        val maxX = (availableWidth - ballSizePx - dockMarginPx).coerceAtLeast(minX)
        val minY = dockMarginPx
        val maxY = (availableHeight - ballSizePx - dockMarginPx).coerceAtLeast(minY)

        val outlineColor = MaterialTheme.colorScheme.outlineVariant
        val ballPrimaryColor = MaterialTheme.colorScheme.primary
        val ballAccentColor = MaterialTheme.colorScheme.tertiary

        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = containerColor.value,
                shape = RoundedCornerShape(containerCorner.value),
                // M3 层级只用 tonal surface 表达：不再叠加 tonalElevation 二次着色，
                // 也不铺厚阴影（规格 §4.4）。
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(width = Dimensions.StrokeHairline, color = outlineColor),
                modifier = Modifier
                    .offset { IntOffset(positionX.roundToInt(), positionY.roundToInt()) }
                    .size(surfaceWidth, surfaceHeight)
                    .pointerInput(expanded, availableWidth, availableHeight, reduceMotion) {
                        if (expanded) return@pointerInput
                        // 收起态：球是唯一手势目标。在 Initial pass 抢先消费，
                        // 内容层里的「圈选」/关闭按钮永远收不到指针事件。
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            down.consume()
                            var dragX = positionX
                            var dragY = positionY
                            var moved = false
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    if (moved) {
                                        val velocity = tracker.calculateVelocity()
                                        val settleLeft = shouldDockOnLeft(
                                            position = dragX,
                                            velocityPxPerSecond = velocity.x,
                                            availableWidth = availableWidth,
                                        )
                                        dockOnLeft = settleLeft
                                        val destinationX = if (settleLeft) minX else maxX
                                        val destinationY = settledPosition(
                                            position = dragY,
                                            velocityPxPerSecond = velocity.y,
                                            minimum = minY,
                                            maximum = maxY,
                                        )
                                        if (reduceMotion) {
                                            positionX = destinationX
                                            positionY = destinationY
                                            settling = false
                                        } else {
                                            // 松手才创建动画：从当前位置起飞并继承瞬时速度
                                            // （M17），拖动期没有任何协程在逐帧 cancel。
                                            positionJobHolder.value?.cancel()
                                            val generation = settleGeneration + 1
                                            settleGeneration = generation
                                            settling = true
                                            positionJobHolder.value = scope.launch {
                                                try {
                                                    coroutineScope {
                                                        launch {
                                                            val animation = Animatable(positionX)
                                                            animation.animateTo(
                                                                targetValue = destinationX,
                                                                animationSpec = MotionSpecs.MomentumSpring,
                                                                initialVelocity = velocity.x,
                                                            ) { positionX = value }
                                                        }
                                                        launch {
                                                            val animation = Animatable(positionY)
                                                            animation.animateTo(
                                                                targetValue = destinationY,
                                                                animationSpec = MotionSpecs.MomentumSpring,
                                                                initialVelocity = velocity.y,
                                                            ) { positionY = value }
                                                        }
                                                    }
                                                } finally {
                                                    // 只有「仍然是当前这一代」才释放：被新动画/手势
                                                    // 取代的旧动画不得把状态清掉。
                                                    if (settleGeneration == generation) settling = false
                                                }
                                            }
                                        }
                                    } else {
                                        settling = false
                                        currentOnExpandedChange.value(true)
                                    }
                                    break
                                }
                                val travelled = (change.position - down.position).getDistance()
                                if (!moved && travelled < viewConfiguration.touchSlop) {
                                    change.consume()
                                    continue
                                }
                                moved = true
                                if (!settling) {
                                    // 用户重新抓住球：先停掉旧的吸附动画，再进入手势驱动，
                                    // 形变 effect 在此期间不再写位置（单一 owner）。
                                    settling = true
                                    settleGeneration += 1
                                    positionJobHolder.value?.cancel()
                                }
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val delta = change.positionChange()
                                dragX = rubberbanded(dragX + delta.x, minX, maxX, availableWidth)
                                dragY = rubberbanded(dragY + delta.y, minY, maxY, availableHeight)
                                positionX = dragX
                                positionY = dragY
                                change.consume()
                            }
                        }
                    }
                    .semantics {
                        contentDescription = if (expanded) "智能助理，已展开" else "智能助理，已收起"
                        stateDescription = if (expanded) "已展开" else "已收起"
                        if (!expanded) {
                            role = Role.Button
                            onClick(label = "展开助理") {
                                currentOnToggleExpanded.value(true)
                                true
                            }
                        }
                        customActions = listOf(
                            CustomAccessibilityAction("展开助理") {
                                if (!expanded) {
                                    currentOnToggleExpanded.value(true)
                                    true
                                } else {
                                    false
                                }
                            },
                            CustomAccessibilityAction("收起助理") {
                                if (expanded) {
                                    currentOnToggleExpanded.value(false)
                                    true
                                } else {
                                    false
                                }
                            },
                            CustomAccessibilityAction("移动到左侧") {
                                dockOnLeft = true
                                if (expanded) currentOnToggleExpanded.value(false)
                                true
                            },
                            CustomAccessibilityAction("移动到右侧") {
                                dockOnLeft = false
                                if (expanded) currentOnToggleExpanded.value(false)
                                true
                            },
                            CustomAccessibilityAction("结束助理") {
                                onClose()
                                true
                            },
                        )
                    },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 球层：展开态不可见也不可聚焦。
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(ballAlpha.value)
                            .then(if (expanded) Modifier.clearAndSetSemantics {} else Modifier),
                    ) {
                        if (ballAlpha.value <= 0.01f) return@Canvas
                        val strokeWidth = Dimensions.StrokeStitch.toPx()
                        val diameter = (minOf(size.width, size.height) - Dimensions.SpaceSmall.toPx())
                            .coerceAtLeast(strokeWidth * 4f)
                        val topLeft = Offset(
                            x = (size.width - diameter) / 2f,
                            y = (size.height - diameter) / 2f,
                        )
                        drawArc(
                            color = ballPrimaryColor,
                            startAngle = 28f,
                            sweepAngle = 296f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = Size(diameter, diameter),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                        drawCircle(
                            color = ballAccentColor,
                            radius = strokeWidth,
                            center = Offset(
                                x = topLeft.x + diameter * 0.78f,
                                y = topLeft.y + diameter * 0.22f,
                            ),
                        )
                    }

                    // 内容层：始终挂载、始终按展开目标尺寸布局；靠缩放贴合当前表面。
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .requiredSize(contentWidth, contentHeight)
                            .graphicsLayer {
                                scaleX = contentScaleX
                                scaleY = contentScaleY
                                alpha = contentAlpha.value
                            }
                            .then(if (expanded) Modifier else Modifier.blockChildPointers())
                            .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {}),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        horizontal = Dimensions.SpaceMedium,
                                        vertical = Dimensions.SpaceSmall,
                                    ),
                            ) {
                                AssistantHeader(
                                    title = title,
                                    subtitle = subtitle,
                                    onStartSelection = onStartSelection,
                                    onCollapse = { currentOnToggleExpanded.value(false) },
                                    onClose = onClose,
                                    onSizeChanged = { headerHeightPx = it.toFloat() },
                                )
                                Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .graphicsLayer { alpha = detailAlpha.value },
                                ) {
                                    content()
                                }
                                Spacer(
                                    modifier = Modifier.height(
                                        with(density) { footerHeightPx.toDp() },
                                    ),
                                )
                            }

                            // 固定输入坞：不参与滚动，展开态独占 IME；高度只用于给消息区留位。
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .wrapContentHeight(
                                        align = Alignment.Bottom,
                                        unbounded = true,
                                    )
                                    .then(if (expanded) Modifier.imePadding() else Modifier)
                                    .onSizeChanged { footerHeightPx = it.height.toFloat() }
                                    .graphicsLayer { alpha = detailAlpha.value }
                                    .padding(horizontal = Dimensions.SpaceMedium)
                                    .padding(bottom = Dimensions.SpaceSmall),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                content = footer,
                            )

                            // 浮层槽位：锚定在输入坞上方，绝对定位，不影响消息区高度。
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        bottom = with(density) {
                                            footerHeightPx.toDp() + Dimensions.SpaceSmall
                                        },
                                    )
                                    .then(if (expanded) Modifier.imePadding() else Modifier),
                            ) {
                                overlay()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantHeader(
    title: String,
    subtitle: String,
    onStartSelection: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onSizeChanged: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.MinimumTouchTarget)
            .onSizeChanged { onSizeChanged(it.height) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 标题只给一行：浮窗里放不下长会话标题，换行会把「圈选/收起/关闭」挤成三行头。
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        IconButton(onClick = onCollapse) {
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
}

/**
 * 收起态的内容层指针门控（第二道防线）。
 *
 * 第一道防线是表面自己在 **Initial pass** 消费按下与移动（父到子顺序），
 * 实测把这一道去掉后，`AssistantSurfaceInteractionTest` 的触摸用例立即变红。
 * 这一层是冗余的兜底：即使将来有人改了表面手势的消费顺序，内容层也收不到指针。
 * 另外 Compose 的 `graphicsLayer` 缩放会把命中区域映射到当前视觉尺寸，
 * 所以内容层实际可点范围本身也只剩球那么大 —— 两者叠加，点球不会拉起屏幕采集。
 */
private fun Modifier.blockChildPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
