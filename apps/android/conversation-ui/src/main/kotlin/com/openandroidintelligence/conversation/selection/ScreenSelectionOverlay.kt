package com.openandroidintelligence.conversation.selection

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlin.math.roundToInt

/** 截图铺满画布时压暗的比例：选区内的原始像素保持全亮，便于确认裁剪内容。 */
private const val DimOverlayAlpha = 0.52f

/** 还没有选区时的遮罩强度：整帧压暗但不至于像「已选中」那样重。 */
private const val DimAlphaIdle = 0.32f

/** 自由圈选的采样距离：低于它就合并到上一个点，避免 O(n²) 的点集与抖动。 */
private val FreehandSampleStep = Dimensions.SpaceTiny / 2

/**
 * 覆盖在 Assist 会话截图上的矩形 / 自由圈选器。
 *
 * **坐标契约：画布坐标 ≡ 帧坐标。** 画布尺寸只由本层的 `onSizeChanged` 测量并通过
 * [onCanvasSizeChanged] 上报；截图被拉伸铺满整个画布，因此回调的选区坐标可以按
 * `截图像素 / 画布像素` 的比例直接换算回帧像素（见 `cropPngSelection`）。调用方不得
 * 再加自己的 inset 或二次测量，否则两套坐标会出现漂移。
 *
 * 截图是输入而不是产物：没有截图时明确渲染不可用态，绝不以松手冒充提交；拖动只
 * 产生草稿选区，用户按下「确认选区」后才回调。
 *
 * @param capturing 截图仍在采集/解码：渲染「正在截取屏幕…」中间态，而不是失败文案。
 * @param onCanvasSizeChanged 画布尺寸上报（唯一来源）。
 * @param onFreehandConfirmed 自由圈选的路径回调；为 null 时不提供该模式。
 */
@Composable
fun ScreenSelectionOverlay(
    onCropConfirmed: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    screenshot: ImageBitmap? = null,
    onFreehandConfirmed: ((List<Offset>) -> Unit)? = null,
    capturing: Boolean = false,
    onCanvasSizeChanged: ((IntSize) -> Unit)? = null,
) {
    val hasScreenshot = screenshot != null && screenshot.width > 0 && screenshot.height > 0
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .semantics { contentDescription = "屏幕圈选" },
    ) {
        when {
            hasScreenshot -> EditableSelection(
                screenshot = screenshot,
                onCropConfirmed = onCropConfirmed,
                onCancel = onCancel,
                onFreehandConfirmed = onFreehandConfirmed,
                onCanvasSizeChanged = onCanvasSizeChanged,
            )
            capturing -> CapturingSelectionState(onCancel = onCancel, modifier = Modifier.align(Alignment.Center))
            else -> UnavailableSelectionState(onCancel = onCancel, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun EditableSelection(
    screenshot: ImageBitmap,
    onCropConfirmed: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
    onFreehandConfirmed: ((List<Offset>) -> Unit)?,
    onCanvasSizeChanged: ((IntSize) -> Unit)?,
) {
    val density = LocalDensity.current
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()

    // 24dp 的宣称号称必须真的按 dp 换算成像素，否则 3x 屏幕上「至少 24dp」只有 24px。
    val minimumSizePx = with(density) { Dimensions.SpaceLarge.toPx() }
    val handleHitRadiusPx = with(density) { Dimensions.SpaceLarge.toPx() }
    val sampleStepPx = with(density) { FreehandSampleStep.toPx() }

    var canvasSize by remember(screenshot) { mutableStateOf(IntSize.Zero) }
    var selection by remember(screenshot) { mutableStateOf<SelectionBounds?>(null) }
    var draftStart by remember(screenshot) { mutableStateOf<Offset?>(null) }
    var draftCurrent by remember(screenshot) { mutableStateOf<Offset?>(null) }
    var activeHandle by remember(screenshot) { mutableStateOf<SelectionHandle?>(null) }
    var freehand by remember(screenshot) { mutableStateOf(false) }
    var freehandPathVersion by remember(screenshot) { mutableIntStateOf(0) }
    val freehandPoints = remember(screenshot) { mutableStateListOf<Offset>() }
    val freehandPath = remember(screenshot) { Path() }
    var hapticArmed by remember(screenshot) { mutableStateOf(false) }

    val canvasWidth = canvasSize.width.toFloat()
    val canvasHeight = canvasSize.height.toFloat()
    // 自由圈选的有效性只在松手时结算一次：拖动期不再让组合读点集，
    // 每一帧只重绘路径（D15），不触发重组。
    var freehandSelectionValid by remember(screenshot) { mutableStateOf(false) }
    val hasVisibleSelection = selection != null || draftStart != null
    val validSelection = selection?.isValid(
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        minimumSize = minimumSizePx,
    ) == true && (!freehand || freehandSelectionValid)

    // 选区框出现/变化时的描边淡化（reduced 直接到位）。
    val strokeAlpha = animateFloatAsState(
        targetValue = if (hasVisibleSelection) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(MotionSpecs.Fast, easing = MotionSpecs.LinearOutSlowInEasing),
        label = "selection-stroke-alpha",
    )
    val dimAlpha = animateFloatAsState(
        targetValue = if (hasVisibleSelection) DimOverlayAlpha else DimAlphaIdle,
        animationSpec = if (reduceMotion) snap() else tween(MotionSpecs.Standard, easing = MotionSpecs.StandardEasing),
        label = "selection-dim-alpha",
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = onPrimary)
    // 遮罩必须压暗整帧，用 M3 的 scrim 语义角色而不是硬编码黑色。
    val dimColor = MaterialTheme.colorScheme.scrim
    val labelCornerPx = with(density) { AppRadius.ExtraSmall.toPx() }
    val strokePx = with(density) { Dimensions.StrokeStitch.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 图像层：只在截图/尺寸变化时重建缓存，不在每帧重新准备绘制参数。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    canvasSize = it
                    onCanvasSizeChanged?.invoke(it)
                }
                .drawWithCache {
                    val image = screenshot
                    val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    onDrawBehind {
                        // 在绘制阶段读取状态：拖动中只重绘，不重建缓存，也不重排布局。
                        drawImage(image, dstSize = destination)
                        drawRect(color = dimColor.copy(alpha = dimAlpha.value))
                        val bounds = if (draftStart != null && draftCurrent != null) {
                            normalizeSelection(draftStart!!, draftCurrent!!)
                        } else {
                            selection
                        }
                        if (freehand && freehandPathVersion > 0) {
                            clipPath(freehandPath) {
                                drawImage(image, dstSize = destination)
                            }
                        } else if (bounds != null) {
                            // 选区内保持原始亮度，让用户在确认前核对裁剪内容。
                            clipRect(
                                left = bounds.left,
                                top = bounds.top,
                                right = bounds.right,
                                bottom = bounds.bottom,
                            ) {
                                drawImage(image, dstSize = destination)
                            }
                        }
                    }
                },
        )

        // 选区层：描边、控制柄、拖动中的实时尺寸标签。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (freehand && freehandPathVersion > 0) {
                        drawPath(
                            path = freehandPath,
                            color = primaryColor,
                            style = Stroke(width = strokePx),
                        )
                    }
                    val bounds = if (draftStart != null && draftCurrent != null) {
                        normalizeSelection(draftStart!!, draftCurrent!!)
                    } else {
                        selection
                    } ?: return@drawBehind
                    val stroke = strokePx
                    drawRect(
                        color = primaryColor.copy(alpha = strokeAlpha.value),
                        topLeft = Offset(bounds.left, bounds.top),
                        size = Size(bounds.width, bounds.height),
                        style = Stroke(width = stroke),
                    )
                    if (!freehand) {
                        val handleRadius = 5.dp.toPx()
                        val handleColor = tertiaryColor.copy(alpha = strokeAlpha.value)
                        drawCircle(handleColor, handleRadius, Offset(bounds.left, bounds.top))
                        drawCircle(handleColor, handleRadius, Offset(bounds.right, bounds.top))
                        drawCircle(handleColor, handleRadius, Offset(bounds.left, bounds.bottom))
                        drawCircle(handleColor, handleRadius, Offset(bounds.right, bounds.bottom))
                        drawCircle(
                            handleColor,
                            handleRadius * 0.8f,
                            Offset(bounds.left, (bounds.top + bounds.bottom) / 2f),
                        )
                        drawCircle(
                            handleColor,
                            handleRadius * 0.8f,
                            Offset(bounds.right, (bounds.top + bounds.bottom) / 2f),
                        )
                        drawCircle(
                            handleColor,
                            handleRadius * 0.8f,
                            Offset((bounds.left + bounds.right) / 2f, bounds.top),
                        )
                        drawCircle(
                            handleColor,
                            handleRadius * 0.8f,
                            Offset((bounds.left + bounds.right) / 2f, bounds.bottom),
                        )
                    }
                    // 拖动中实时显示尺寸（dp），避免用户靠猜判断是否达到下限。
                    val label = "${(bounds.width / density.density).roundToInt()} × " +
                        "${(bounds.height / density.density).roundToInt()} dp"
                    val measured = textMeasurer.measure(AnnotatedString(label), labelStyle)
                    val padding = with(density) { Dimensions.SpaceTiny.toPx() }
                    val labelWidth = measured.size.width + padding * 2
                    val labelHeight = measured.size.height + padding
                    val labelX = bounds.left.coerceIn(0f, (size.width - labelWidth).coerceAtLeast(0f))
                    val labelY = (bounds.top - labelHeight - 2.dp.toPx())
                        .coerceAtLeast(0f)
                        .coerceAtMost((size.height - labelHeight).coerceAtLeast(0f))
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(labelX, labelY),
                        size = Size(labelWidth, labelHeight),
                        cornerRadius = CornerRadius(labelCornerPx),
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(labelX + padding, labelY + padding / 2),
                    )
                }
                .pointerInput(screenshot, freehand) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val current = selection
                            val handle = if (!freehand && current != null) {
                                selectionHandleAt(offset, current, handleHitRadiusPx)
                            } else {
                                null
                            }
                            activeHandle = handle
                            if (handle == null) {
                                draftStart = offset
                                draftCurrent = offset
                                freehandPoints.clear()
                                freehandPath.reset()
                                if (freehand) {
                                    freehandPoints.add(offset)
                                    freehandPath.moveTo(offset.x, offset.y)
                                    freehandPathVersion++
                                }
                            } else {
                                hapticArmed = current != null &&
                                    current.width >= minimumSizePx &&
                                    current.height >= minimumSizePx
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val handle = activeHandle
                            val current = selection
                            if (handle != null && current != null) {
                                val resized = resizeSelection(
                                    bounds = current,
                                    handle = handle,
                                    deltaX = dragAmount.x,
                                    deltaY = dragAmount.y,
                                    canvasWidth = canvasWidth,
                                    canvasHeight = canvasHeight,
                                    minimumSize = minimumSizePx,
                                )
                                selection = resized
                                val reachedMinimum = resized.width >= minimumSizePx &&
                                    resized.height >= minimumSizePx
                                if (reachedMinimum && !hapticArmed) {
                                    hapticArmed = true
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (!reachedMinimum) {
                                    hapticArmed = false
                                }
                            } else {
                                val point = Offset(
                                    x = change.position.x.coerceIn(0f, canvasWidth),
                                    y = change.position.y.coerceIn(0f, canvasHeight),
                                )
                                draftCurrent = point
                                if (freehand) {
                                    val last = freehandPoints.lastOrNull()
                                    if (last == null || (point - last).getDistance() >= sampleStepPx) {
                                        freehandPoints.add(point)
                                        freehandPath.lineTo(point.x, point.y)
                                        freehandPathVersion++
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            val handle = activeHandle
                            val start = draftStart
                            val current = draftCurrent
                            if (handle == null && start != null && current != null) {
                                selection = if (freehand && freehandPoints.size >= 3) {
                                    SelectionBounds(
                                        left = freehandPoints.minOf { it.x },
                                        top = freehandPoints.minOf { it.y },
                                        right = freehandPoints.maxOf { it.x },
                                        bottom = freehandPoints.maxOf { it.y },
                                    )
                                } else {
                                    normalizeSelection(start, current)
                                }.clamp(canvasWidth = canvasWidth, canvasHeight = canvasHeight)
                                freehandSelectionValid = freehand && freehandPoints.size >= 3 &&
                                    polygonArea(freehandPoints) >= minimumSizePx * minimumSizePx
                                if (freehand && freehandPoints.size < 3) {
                                    selection = null
                                    freehandPoints.clear()
                                    freehandPath.reset()
                                    freehandPathVersion++
                                }
                            }
                            activeHandle = null
                            draftStart = null
                            draftCurrent = null
                            hapticArmed = false
                        },
                        onDragCancel = {
                            freehandSelectionValid = false
                            activeHandle = null
                            draftStart = null
                            draftCurrent = null
                            if (freehand) {
                                freehandPoints.clear()
                                freehandPath.reset()
                                freehandPathVersion++
                            }
                        },
                    )
                },
        )

        if (onFreehandConfirmed != null) {
            // 没有自由圈选回调就不展示模式开关：不给出一个必然失效的入口。
            SelectionModeToggle(
                freehand = freehand,
                onToggle = {
                    freehand = !freehand
                    freehandSelectionValid = false
                    selection = null
                    draftStart = null
                    draftCurrent = null
                    freehandPoints.clear()
                    freehandPath.reset()
                    freehandPathVersion++
                },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        SelectionControls(
            selection = if (freehand) null else selection,
            canvasSize = canvasSize,
            validSelection = validSelection,
            freehand = freehand,
            onSelectionChange = { updated ->
                selection = updated.clamp(
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                )
            },
            onConfirm = {
                val current = selection
                if (current != null && validSelection) {
                    if (freehand) {
                        onFreehandConfirmed?.invoke(freehandPoints.toList())
                    } else {
                        onCropConfirmed(current.left, current.top, current.right, current.bottom)
                    }
                }
            },
            onCancel = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    stateDescription = selection?.let { bounds ->
                        "当前选区 ${(bounds.width / density.density).roundToInt()} × " +
                            "${(bounds.height / density.density).roundToInt()} dp"
                    } ?: "尚未选择区域"
                },
        )
    }
}

@Composable
private fun SelectionModeToggle(
    freehand: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 顶部/右侧安全区：状态栏、挖孔与横屏侧边都不能压住这个开关（规格 §7）。
    Surface(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End),
            )
            .padding(Dimensions.SpaceMedium),
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .toggleable(
                    value = freehand,
                    role = Role.Switch,
                    onValueChange = { onToggle() },
                )
                .heightIn(min = Dimensions.MinimumTouchTarget)
                .padding(horizontal = Dimensions.SpaceMedium),
            contentAlignment = Alignment.Center,
        ) {
            val reduced = LocalMotionPolicy.current.reduceMotion
            Crossfade(
                targetState = freehand,
                animationSpec = if (reduced) snap() else tween(MotionSpecs.Fast),
                label = "selection-mode",
            ) { isFreehand ->
                Text(
                    text = if (!isFreehand) "自由圈选" else "切换矩形选区",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CapturingSelectionState(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(Dimensions.SpaceLarge),
        shape = RoundedCornerShape(AppRadius.ExtraLarge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.SpaceLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.Progress),
            )
            Text("正在截取屏幕…", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "取得这一帧之后才能圈选，请稍候。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = Dimensions.MinimumTouchTarget),
            ) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun UnavailableSelectionState(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(Dimensions.SpaceLarge),
        shape = RoundedCornerShape(AppRadius.ExtraLarge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.SpaceLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(Dimensions.Progress),
            )
            Text("当前内容不允许共享", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "系统没有提供可用的 Assist 截图，无法进行屏幕圈选。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = Dimensions.MinimumTouchTarget),
            ) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun SelectionControls(
    selection: SelectionBounds?,
    canvasSize: IntSize,
    validSelection: Boolean,
    freehand: Boolean,
    onSelectionChange: (SelectionBounds) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                // 底部 + 左右安全区：横屏时不会贴到刘海/手势区。
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                ),
            )
            .padding(Dimensions.SpaceMedium),
        shape = RoundedCornerShape(AppRadius.ExtraLarge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 小屏 / 横屏时边界滑块改为两列，减少面板高度、避免把画布挤没。
            val twoColumns = maxWidth >= Dimensions.FormWidth
            Column(
                modifier = Modifier
                    .padding(Dimensions.SpaceMedium)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CropFree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimensions.Icon),
                )
                // 模式切换用 Crossfade（reduced 直达），文案本身不重复既有断言字符串。
                Crossfade(
                    targetState = freehand,
                    animationSpec = if (reduceMotion) snap() else tween(MotionSpecs.Fast),
                    label = "selection-controls-title",
                ) { isFreehand ->
                    Text(
                        text = when {
                            isFreehand -> "沿目标边缘描绘自由选区"
                            selection == null -> "拖动选择要分享的区域"
                            else -> "调整选区边界后确认"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = when {
                        freehand -> "松手后可继续确认；自由圈选只保留路径内的像素。"
                        selection == null -> "松手后仍可调整，确认后才会加入当前对话。"
                        else -> "拖动角柄或边柄调整，也可用下方四个边界滑块精确调整。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (selection != null && canvasSize != IntSize.Zero) {
                    if (twoColumns) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium)) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                            ) {
                                BoundarySlider(
                                    label = "左边界",
                                    value = selection.left,
                                    maximum = canvasSize.width.toFloat(),
                                    onValueChange = { value ->
                                        onSelectionChange(
                                            selection.copy(left = value.coerceAtMost(selection.right)),
                                        )
                                    },
                                )
                                BoundarySlider(
                                    label = "上边界",
                                    value = selection.top,
                                    maximum = canvasSize.height.toFloat(),
                                    onValueChange = { value ->
                                        onSelectionChange(
                                            selection.copy(top = value.coerceAtMost(selection.bottom)),
                                        )
                                    },
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                            ) {
                                BoundarySlider(
                                    label = "右边界",
                                    value = selection.right,
                                    maximum = canvasSize.width.toFloat(),
                                    onValueChange = { value ->
                                        onSelectionChange(
                                            selection.copy(right = value.coerceAtLeast(selection.left)),
                                        )
                                    },
                                )
                                BoundarySlider(
                                    label = "下边界",
                                    value = selection.bottom,
                                    maximum = canvasSize.height.toFloat(),
                                    onValueChange = { value ->
                                        onSelectionChange(
                                            selection.copy(bottom = value.coerceAtLeast(selection.top)),
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        BoundarySlider(
                            label = "左边界",
                            value = selection.left,
                            maximum = canvasSize.width.toFloat(),
                            onValueChange = { value ->
                                onSelectionChange(selection.copy(left = value.coerceAtMost(selection.right)))
                            },
                        )
                        BoundarySlider(
                            label = "右边界",
                            value = selection.right,
                            maximum = canvasSize.width.toFloat(),
                            onValueChange = { value ->
                                onSelectionChange(selection.copy(right = value.coerceAtLeast(selection.left)))
                            },
                        )
                        BoundarySlider(
                            label = "上边界",
                            value = selection.top,
                            maximum = canvasSize.height.toFloat(),
                            onValueChange = { value ->
                                onSelectionChange(selection.copy(top = value.coerceAtMost(selection.bottom)))
                            },
                        )
                        BoundarySlider(
                            label = "下边界",
                            value = selection.bottom,
                            maximum = canvasSize.height.toFloat(),
                            onValueChange = { value ->
                                onSelectionChange(selection.copy(bottom = value.coerceAtLeast(selection.top)))
                            },
                        )
                    }
                }

                if (selection != null && !validSelection) {
                    Text(
                        text = "选区至少需要 ${Dimensions.SpaceLarge.value.roundToInt()}dp，" +
                            "并且必须位于截图范围内。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimensions.MinimumTouchTarget),
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = validSelection,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimensions.MinimumTouchTarget),
                    ) {
                        Text("确认选区")
                    }
                }
            }
        }
    }
}

@Composable
private fun BoundarySlider(
    label: String,
    value: Float,
    maximum: Float,
    onValueChange: (Float) -> Unit,
) {
    val safeMaximum = maximum.coerceAtLeast(1f)
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(0f, safeMaximum),
            onValueChange = onValueChange,
            valueRange = 0f..safeMaximum,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimensions.MinimumTouchTarget)
                .semantics {
                    contentDescription = "$label，可调节"
                    stateDescription = "${value.roundToInt()}像素"
                },
        )
    }
}

internal fun polygonArea(points: List<Offset>): Float {
    if (points.size < 3) return 0f
    return kotlin.math.abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2f
}
