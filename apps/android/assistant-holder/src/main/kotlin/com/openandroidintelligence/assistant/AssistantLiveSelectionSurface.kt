package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R as UiR
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose 测试钩子：手势层与轨迹层分开打标签。 */
internal object AssistantLiveSelectionTags {
    const val GESTURE = "assistant_live_selection_gesture"
    const val TRAIL = "assistant_live_selection_trail"
}

/**
 * 定格截图上的实时圈选层：**松手即自动确认**，没有「确认选区」按钮。
 *
 * 与 `ScreenSelectionOverlay` 的关系：裁剪与几何共用（裁剪走同模块的
 * `createAssistantFreehandSelection`），交互层在这里重写。不能直接复用 overlay 的
 * 原因：它的既有语义是「拖动 → 草稿选区 → 用户按确认」，而本需求要求松手就加入附件。
 *
 * 采样步长与面积判定与 `ScreenSelectionOverlay` 的语义保持一致（24dp 见方的最小面积、
 * 4dp 量级的采样步长）；这两个值在那边都是 `internal`，跨模块不可见，因此在
 * [polygonAreaPx] 里复刻同一公式，而不是扩公开 API。
 *
 * 坐标契约与 overlay 一致：**画布坐标 ≡ 帧坐标**，截图按 `FillBounds` 铺满画布，
 * 裁剪时按「截图像素 / 画布像素」换算。
 *
 * 行为：
 * - 起点落在任一排除区（输入栏、横条）内的手势直接放弃，不采样、不产出；
 * - 采样点数上限 [MAX_TRACKED_SAMPLES]，超限后丢弃后续点，防止长手势拖垮裁剪；
 * - 松手自动闭合路径；点数 < 3 或面积过小视为误触：清空轨迹、不产生附件、不弹错误；
 * - 有效落定为一次 `TextHandleMove` 级轻反馈；减少动态时轨迹立即消失，不做淡出；
 * - 槽位已满（[enabled] 为 false）时手势仍被这一层吞掉（不漏给下方 App），
 *   但只回调一次 [onRejected]，不产生附件。
 *
 * @param exclusionRects 控件自报的矩形（与画布同一坐标系）；手势起点落在其中即放弃。
 * @param onSelectionReady 有效选区已裁剪成 PNG 并写入 [previews] 后回调。
 * @param onRejected 需要解释的拒绝（例如附件已达上限）；普通误触不会触发。
 */
@Composable
internal fun AssistantLiveSelectionSurface(
    screenshot: Bitmap,
    previews: AssistantLocalPreviews,
    localKey: String,
    onSelectionReady: (AssistantSelectionResult) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    exclusionRects: () -> List<Rect> = { emptyList() },
    onRejected: () -> Unit = {},
) {
    val source = screenshot.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 } ?: return
    val image = remember(source) { source.asImageBitmap() }
    val density = LocalDensity.current
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary

    // 画布尺寸只来自本层的测量（与 overlay 同一来源）；裁剪时按它做坐标换算。
    var canvasSize by remember(source) { mutableStateOf(IntSize.Zero) }
    // 点集用不可变列表整体替换：绘制层只在组合期拿到快照，不在绘制阶段读快照状态，
    // 避免 draw 与状态互相触发反复失效（Robolectric 的 idle 检测会因此一直等不到静止）。
    var points by remember(source) { mutableStateOf<List<Offset>>(emptyList()) }
    var pathVersion by remember(source) { mutableIntStateOf(0) }
    // 松手后轨迹淡出：淡出期间仍可开始下一笔，用户感觉不到被锁住。
    // 用组合作用域的 `animateFloatAsState` 而不是自行持有 Animatable：
    // 动画随组合取消，测试与真实宿主都不会留下无人回收的动画协程。
    var trailVisible by remember(source) { mutableStateOf(true) }
    val trailAlpha by animateFloatAsState(
        targetValue = if (trailVisible) 1f else 0f,
        animationSpec = MotionSpecs.fade(reduceMotion),
        label = "assistant-live-selection-trail",
    )
    var confirming by remember(source) { mutableStateOf(false) }
    // 本次手势是否因起点落在控件区域而被放弃：放弃后同一手势的后续移动与松手都忽略。
    // `detectDragGestures` 在 onDragStart 之后仍会继续派发 onDrag/onDragEnd，只用
    // "点集为空" 兜底是不够的：那样仍会让下层控件收不到这次拖动。
    var gestureIgnored by remember(source) { mutableStateOf(false) }

    val minimumAreaPx = with(density) { Dimensions.SpaceLarge.toPx() }.let { it * it }
    val sampleStepPx = with(density) { (Dimensions.SpaceTiny / 2).toPx() }
    val strokePx = with(density) { Dimensions.StrokeStitch.toPx() }

    val currentOnSelectionReady by rememberUpdatedState(onSelectionReady)
    val currentOnRejected by rememberUpdatedState(onRejected)
    val currentPreviews by rememberUpdatedState(previews)
    val currentExclusions by rememberUpdatedState(exclusionRects)
    val currentEnabled by rememberUpdatedState(enabled)
    // 每次成功交付后递增：连续圈选的两条选区必须各有独立本地键，缩略图才不会互相覆盖。
    var committedCount by remember(source) { mutableIntStateOf(0) }

    fun resetTrail() {
        points = emptyList()
        pathVersion++
    }

    fun showTrail() {
        trailVisible = true
    }

    fun discardTrail() {
        resetTrail()
        trailVisible = true
    }

    fun confirm(pointsSnapshot: List<Offset>) {
        if (confirming) return
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()
        if (width <= 0f || height <= 0f) return
        confirming = true
        scope.launch {
            val key = liveSelectionLocalKey(localKey, committedCount)
            val result = withContext(Dispatchers.Default) {
                createAssistantFreehandSelection(
                    source = source,
                    points = pointsSnapshot,
                    canvasWidth = width,
                    canvasHeight = height,
                    localKey = key,
                    previews = currentPreviews,
                )
            }
            confirming = false
            if (result == null) {
                // 裁剪判定的退化在采样阶段已经拦下；走到这里说明尺寸/编码异常。
                // 保持定格态，只清空轨迹：用户可以再画一笔。
                discardTrail()
                return@launch
            }
            committedCount++
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnSelectionReady(result)
            // 减少动态时轨迹立即消失，不做淡出；否则先淡出再清空点集。
            if (reduceMotion) {
                resetTrail()
            } else {
                trailVisible = false
                withFrameNanos { }
                delay(TRAIL_FADE_MILLIS)
                resetTrail()
                trailVisible = true
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .semantics { contentDescription = stringResource(UiR.string.assistant_selection_surface_desc) },
    ) {
        // 底层：定格截图（系统截图，不重新截图），铺满画布。
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        // 中层：实时轨迹。绘制只读状态；路径在绘制阶段按点集重建。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(AssistantLiveSelectionTags.TRAIL)
                .drawTrail(
                    points = points,
                    pathVersion = pathVersion,
                    alpha = trailAlpha,
                    color = primary,
                    strokePx = strokePx,
                ),
        )
        // 上层：手势。只有起点不在排除区内的按下才进入本次圈选。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(AssistantLiveSelectionTags.GESTURE)
                .then(
                    if (!currentEnabled) {
                        // 禁用时仍要吞掉手势：不能把按下漏给下层 App，也不能静默吞掉用户的意图。
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                // 手势已被这一层吞掉，但用户仍需要知道为什么没反应。
                                currentOnRejected()
                            }
                        }
                    } else {
                        Modifier.pointerInput(source, reduceMotion) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    // 控件区域不参与圈选：双重保险的第一道（层级顺序在界面侧已保证）。
                                    gestureIgnored = isExcludedPoint(start, currentExclusions())
                                    if (gestureIgnored) return@detectDragGestures
                                    resetTrail()
                                    showTrail()
                                    points = listOf(start)
                                    pathVersion++
                                },
                                onDrag = { change, _ ->
                                    if (gestureIgnored) return@detectDragGestures
                                    val last = points.lastOrNull() ?: return@detectDragGestures
                                    if (points.size >= MAX_TRACKED_SAMPLES) return@detectDragGestures
                                    val point = change.position
                                    // 按采样步长抽稀：不是每个 move 事件都进点集。
                                    if (abs(point.x - last.x) + abs(point.y - last.y) >= sampleStepPx) {
                                        points = points + point
                                        pathVersion++
                                    }
                                },
                                onDragEnd = {
                                    if (gestureIgnored) {
                                        gestureIgnored = false
                                        return@detectDragGestures
                                    }
                                    val snapshot = points
                                    if (snapshot.size < 3) {
                                        discardTrail()
                                        return@detectDragGestures
                                    }
                                    if (polygonAreaPx(snapshot) < minimumAreaPx) {
                                        discardTrail()
                                        return@detectDragGestures
                                    }
                                    confirm(snapshot)
                                },
                                onDragCancel = {
                                    gestureIgnored = false
                                    discardTrail()
                                },
                            )
                        }
                    },
                ),
        )
    }
}

/**
 * 画轨迹的修饰符：把「路径与颜色」的读取留在绘制阶段，组合期只传引用。
 *
 * 拆出来是因为 `drawWithCache` 的构建块不能读取 Compose 主题（非组合上下文），
 * 颜色必须在组合期取好后传进来。
 */
private fun Modifier.drawTrail(
    points: List<Offset>,
    pathVersion: Int,
    alpha: Float,
    color: Color,
    strokePx: Float,
): Modifier = drawWithCache {
    val path = Path().apply {
        if (pathVersion > 0) {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
    }
    val lastPoint = points.lastOrNull()
    onDrawBehind {
        val safeAlpha = alpha.coerceIn(0f, 1f)
        if (pathVersion <= 0 || safeAlpha <= 0f) return@onDrawBehind
        drawPath(
            path = path,
            color = color.copy(alpha = safeAlpha),
            style = Stroke(
                width = strokePx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.cornerPathEffect(strokePx),
            ),
        )
        if (lastPoint != null) {
            drawCircle(color = color.copy(alpha = safeAlpha), radius = strokePx, center = lastPoint)
        }
    }
}

/**
 * 单次手势的采样上限：与 `ScreenSelectionOverlay` 的 `MAX_FREEHAND_SAMPLES` 对齐（4096）。
 *
 * 超过这个点数，裁剪路径的 CPU 成本已经明显大于收益；多出来的点直接丢弃。
 */
internal const val MAX_TRACKED_SAMPLES = 4096

/** 松手后轨迹淡出的时长；与 `MotionSpecs.fade(false)` 的 200ms 一致。 */
private const val TRAIL_FADE_MILLIS = 200L

/**
 * 手势起点是否落在某个排除区（输入栏、横条等控件自报的矩形）内。
 *
 * 排除区与画布使用同一坐标系。任何非有限坐标都按「不排除」处理以外的保守方式：
 * 返回 true（放弃这次圈选），避免把一次控件点击误判成圈选起点。
 */
internal fun isExcludedPoint(point: Offset, rectangles: List<Rect>): Boolean {
    if (!point.x.isFinite() || !point.y.isFinite()) return true
    return rectangles.any { it.contains(point) }
}

/**
 * 每个选区独立的本地键：同一次会话里连续圈选不能共用同一个键，否则缩略图互相覆盖。
 *
 * 第一次用调用方给的基键（与既有 `localKey` 语义一致），之后追加序号。
 */
internal fun liveSelectionLocalKey(baseKey: String, index: Int): String =
    if (index <= 0) baseKey else "$baseKey-$index"

/**
 * 多边形面积（像素²），用鞋带公式。
 *
 * 与 `ScreenSelectionOverlay.polygonArea` 的语义保持一致；那边是 `internal`，
 * 跨模块不可见，因此在这里复刻同一公式而不是扩公开 API。任何非有限坐标都按无效处理。
 */
internal fun polygonAreaPx(points: List<Offset>): Float {
    if (points.size < 3) return 0f
    var sum = 0.0
    for (index in points.indices) {
        val current = points[index]
        val next = points[(index + 1) % points.size]
        if (!current.x.isFinite() || !current.y.isFinite() || !next.x.isFinite() || !next.y.isFinite()) {
            return 0f
        }
        sum += current.x.toDouble() * next.y - next.x.toDouble() * current.y
    }
    val area = abs(sum).toFloat() / 2f
    return if (area.isFinite()) area else 0f
}
