package com.openandroidintelligence.conversation.workbench

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

/** 一个可见推进批次。文字进度由宿主负责，绘制与触觉副作用在此解耦。 */
data class StreamingTextPresentation(
    val batchId: Long,
    val sourceRevealStart: Int,
    val sourceRevealEnd: Int,
    val active: Boolean,
    val reduceMotion: Boolean,
    val onVisibleAdvance: (Long) -> Unit = {},
    val hapticSourceRanges: List<IntRange>? = null,
)

internal val LocalStreamingMarkdownDocument = staticCompositionLocalOf<StreamingMarkdownDocument?> { null }
internal val LocalStreamingTextPresentation = staticCompositionLocalOf<StreamingTextPresentation?> { null }
internal val LocalStreamingDecoration = staticCompositionLocalOf<StreamingDecorationState?> { null }
internal val LocalMarkdownBlockSource = staticCompositionLocalOf<MarkdownSourceText?> { null }
internal val LocalMarkdownLeafSource = staticCompositionLocalOf<MarkdownSourceText?> { null }

private const val TextFadeMillis = 120L
private const val CursorTrailMillis = 160L
private const val MaximumFadeBatches = 8

internal data class StreamingFadeBatch(val id: Long, val sourceStart: Int, val sourceEnd: Int, val enteredAt: Long)
internal data class StreamingTextLayout(
    val sourceEnd: Int,
    val coordinates: LayoutCoordinates,
    val cursor: Rect,
    val visibleRanges: List<Rect>,
    val batchId: Long,
)

/** 绘制帧只更新本状态，不读取或重新解析 Markdown。 */
@Stable
internal class StreamingDecorationState {
    // 回调每次组合都可能换实例；它不属于可观察绘制事实，不能靠整个 data class 的
    // equals 驱动重组。批次/终态/布局变化统一通过 invalidation 使 Canvas 失效。
    var presentation: StreamingTextPresentation = StreamingTextPresentation(0, 0, 0, false, false)
    var document: StreamingMarkdownDocument? = null
    var container: LayoutCoordinates? = null
    val batches = ArrayList<StreamingFadeBatch>(MaximumFadeBatches)
    private val layouts = LinkedHashMap<Any, StreamingTextLayout>()
    private val placements = mutableMapOf<Any, Offset>()
    var invalidation by mutableIntStateOf(0)
        private set
    var frameMillis by mutableLongStateOf(0L)
    var cursorPosition: Offset? = null
    var cursorVelocity = Offset.Zero
    var trailOrigin: Offset? = null
    var trailStarted = 0L
    var previousTarget: Offset? = null
    var previousBatch = Long.MIN_VALUE
    private var notifiedBatch = Long.MIN_VALUE
    private var batchEnteredAt = 0L

    fun update(document: StreamingMarkdownDocument, next: StreamingTextPresentation) {
        this.document = document
        val changed = next.batchId != presentation.batchId
        val changedPolicy = next.active != presentation.active || next.reduceMotion != presentation.reduceMotion
        presentation = next
        if (changed) batchEnteredAt = SystemClock.uptimeMillis()
        if (changed && next.sourceRevealEnd <= next.sourceRevealStart) {
            // 权威改写的批次没有新增范围：旧透明度和坐标不可投射到更正后的正文。
            batches.clear()
            cursorPosition = null
            previousTarget = null
            cursorVelocity = Offset.Zero
            trailOrigin = null
        }
        if (changed && next.sourceRevealEnd > next.sourceRevealStart && !next.reduceMotion) {
            batches.add(StreamingFadeBatch(next.batchId, next.sourceRevealStart, next.sourceRevealEnd, batchEnteredAt))
            if (batches.size > MaximumFadeBatches) {
                val first = batches.removeAt(0)
                val second = batches.removeAt(0)
                batches.add(0, second.copy(sourceStart = minOf(first.sourceStart, second.sourceStart),
                    enteredAt = minOf(first.enteredAt, second.enteredAt)))
            }
        }
        if (!next.active) trailOrigin = null
        if (next.reduceMotion) {
            batches.clear()
            trailOrigin = null
        }
        if (changed || changedPolicy) invalidation++
    }

    fun register(key: Any, layout: StreamingTextLayout) {
        val host = container?.takeIf { it.isAttached }
        val origin = if (host != null && layout.coordinates.isAttached) {
            host.localPositionOf(layout.coordinates, Offset.Zero)
        } else Offset.Zero
        val oldOrigin = placements.put(key, origin)
        val old = layouts.put(key, layout)
        if (old == null || old.sourceEnd != layout.sourceEnd || old.coordinates !== layout.coordinates ||
            old.cursor != layout.cursor || old.visibleRanges != layout.visibleRanges || old.batchId != layout.batchId ||
            oldOrigin != origin) invalidation++
        notifyVisible(layout)
    }

    fun positionContainer(coordinates: LayoutCoordinates) {
        container = coordinates
        layouts.values.forEach(::notifyVisible)
    }

    fun unregister(key: Any) {
        placements.remove(key)
        if (layouts.remove(key) != null) invalidation++
    }

    fun expireFades() { invalidation++ }

    fun notifyVisible(layout: StreamingTextLayout) {
        val current = presentation
        if (layout.batchId != current.batchId || current.batchId == notifiedBatch ||
            current.sourceRevealEnd <= current.sourceRevealStart ||
            SystemClock.uptimeMillis() - batchEnteredAt > 200L) return
        val host = container?.takeIf { it.isAttached } ?: return
        if (!layout.coordinates.isAttached) return
        val window = host.boundsInWindow()
        if (window.width <= 0f || window.height <= 0f) return
        val visible = layout.visibleRanges.any { rect ->
            val topLeft = layout.coordinates.localToWindow(rect.topLeft)
            val bottomRight = layout.coordinates.localToWindow(rect.bottomRight)
            val actual = Rect(topLeft, bottomRight)
            actual.overlaps(window) && actual.width > 0f && actual.height > 0f
        }
        if (visible) {
            notifiedBatch = current.batchId
            current.onVisibleAdvance(current.batchId)
        }
    }

    fun target(): Pair<Offset, Float>? {
        val host = container?.takeIf { it.isAttached } ?: return null
        // 叶节点上报完整消息的原文位置，按来源顺序选尾端；表格和双向文本都不能按屏幕坐标排序。
        val last = layouts.values.filter { it.coordinates.isAttached }.maxByOrNull { it.sourceEnd } ?: return null
        val origin = host.localPositionOf(last.coordinates, last.cursor.topLeft)
        return origin to last.cursor.height.coerceAtLeast(1f)
    }

    fun hasAnimation(now: Long): Boolean = !presentation.reduceMotion &&
        (batches.any { now - it.enteredAt < TextFadeMillis } ||
            trailOrigin != null && now - trailStarted < CursorTrailMillis ||
            previousTarget?.let { cursorPosition?.let { position -> (it - position).getDistance() > 0.15f } } == true)
}

/**
 * 在消息正文内部放置唯一绘制层；内容可使用 document.blocks 的任意子列表。
 * 源文、AST 与来源图属于同一个解析结果，不能由文本相似度猜测节点身份。
 */
@Composable
fun StreamingMarkdownContainer(
    sourceText: String,
    presentation: StreamingTextPresentation,
    modifier: Modifier = Modifier,
    document: StreamingMarkdownDocument? = null,
    content: @Composable () -> Unit,
) {
    val resolved = document ?: rememberStreamingMarkdownDocument(sourceText)
    val state = remember { StreamingDecorationState() }
    val latestCallback = rememberUpdatedState(presentation.onVisibleAdvance)
    val next = presentation.copy(onVisibleAdvance = { latestCallback.value(it) })
    SideEffect { state.update(resolved, next) }
    val primary = MaterialTheme.colorScheme.primary
    val lineWidth = with(androidx.compose.ui.platform.LocalDensity.current) { 1.5.dp.toPx() }
    val epoch = state.invalidation
    LaunchedEffect(state, epoch, presentation.active, presentation.reduceMotion) {
        if (!presentation.active && state.batches.isEmpty()) return@LaunchedEffect
        var previousTime = 0L
        do {
            withFrameNanos { frame ->
                val now = SystemClock.uptimeMillis()
                val target = state.target()?.first
                val current = state.cursorPosition
                val changedText = state.previousBatch != state.presentation.batchId
                if (target != null) {
                    val dt = if (previousTime == 0L) 1f / 60f else ((frame - previousTime) / 1_000_000_000f).coerceIn(0f, 0.032f)
                    val oldTarget = state.previousTarget
                    val layoutChanged = oldTarget != null && !changedText && (target - oldTarget).getDistance() > 0.5f
                    val distant = current != null && (target - current).getDistance() > 96f
                    if (current == null || presentation.reduceMotion || layoutChanged) {
                        state.cursorPosition = target
                        state.cursorVelocity = Offset.Zero
                        state.trailOrigin = null
                    } else {
                        if (changedText && oldTarget != null && (target - oldTarget).getDistance() > 0.25f) {
                            // 换行允许光标继续插值，轨迹仅留在目标附近，绝不横贯整条气泡。
                            state.trailOrigin = if (distant) target + Offset(-lineWidth * 4, 0f) else current
                            state.trailStarted = now
                        }
                        val stiffness = 500f
                        val damping = 38f
                        val acceleration = (target - current) * stiffness - state.cursorVelocity * damping
                        state.cursorVelocity += acceleration * dt
                        state.cursorPosition = current + state.cursorVelocity * dt
                    }
                    state.previousTarget = target
                    state.previousBatch = state.presentation.batchId
                }
                previousTime = frame
                state.frameMillis = now
                if (state.batches.removeAll { now - it.enteredAt >= TextFadeMillis }) state.expireFades()
                if (now - state.trailStarted >= CursorTrailMillis) state.trailOrigin = null
            }
        } while (state.hasAnimation(SystemClock.uptimeMillis()))
    }
    CompositionLocalProvider(
        LocalStreamingDecoration provides state,
        LocalStreamingMarkdownDocument provides resolved,
        LocalStreamingTextPresentation provides next,
    ) {
        Box(
            modifier.onGloballyPositioned {
                state.positionContainer(it)
            }.drawWithContent {
                drawContent()
                state.invalidation
                state.frameMillis
                if (!state.presentation.active) return@drawWithContent
                val target = state.target() ?: return@drawWithContent
                val position = if (state.presentation.reduceMotion) target.first else state.cursorPosition ?: target.first
                val height = target.second
                val trail = state.trailOrigin
                if (trail != null && !state.presentation.reduceMotion) {
                    val age = ((SystemClock.uptimeMillis() - state.trailStarted).toFloat() / CursorTrailMillis).coerceIn(0f, 1f)
                    val distance = position - trail
                    val scale = minOf(1f, height * 2.5f / distance.getDistance().coerceAtLeast(1f))
                    val start = position - distance * scale * (1f - age)
                    drawLine(primary.copy(alpha = 0.20f * (1f - age)), start + Offset(0f, height * 0.5f),
                        position + Offset(0f, height * 0.5f), strokeWidth = lineWidth * (1f + (1f - age)))
                }
                drawLine(primary, position, position + Offset(0f, height), strokeWidth = lineWidth)
            },
        ) { content() }
    }
}

internal fun sourceOffsetsFor(result: MarkdownInlineResult, source: MarkdownSourceText?): IntArray =
    IntArray(result.annotated.length) { index ->
        // 内联公式由自身真实组件边界参与渐入和触觉；父 Text 不能重复处理占位替代文本。
        if (result.inlineMath.any { index in it.start until it.end }) return@IntArray -1
        val local = result.sourceOffsets.getOrNull(index) ?: index
        source?.offsets?.getOrNull(local) ?: -1
    }

/** 公式作为一个实际测量的绘制对象，测量变化只更新坐标，不生成新的文字推进。 */
@Composable
internal fun streamingAtomicModifier(
    source: MarkdownSourceText?,
    modifier: Modifier,
    measured: Boolean = true,
): Modifier {
    val state = LocalStreamingDecoration.current ?: return modifier
    if (source == null) return modifier
    val key = remember { Any() }
    val progress = LocalStreamingTextPresentation.current ?: state.presentation
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun publish() {
        val position = coordinates?.takeIf { it.isAttached } ?: return
        if (!measured) return
        val width = position.size.width.toFloat()
        val height = position.size.height.toFloat()
        // 公式内容后续增长仍是可见推进；只消费 LaTeX 正文映射，不因闭合定界符或测量回调补震。
        val liveRanges = progress.hapticSourceRanges ?: listOf(progress.sourceRevealStart until progress.sourceRevealEnd)
        val added = source.text.indices.any { index ->
            !source.text[index].isWhitespace() && liveRanges.any { source.offsets[index] in it }
        }
        state.register(key, StreamingTextLayout(
            source.end, position, Rect(width, 0f, width + 1f, height),
            if (added) listOf(Rect(0f, 0f, width, height)) else emptyList(), progress.batchId,
        ))
    }
    SideEffect { publish() }
    DisposableEffect(state, key) { onDispose { state.unregister(key) } }
    return modifier.onGloballyPositioned { coordinates = it; publish() }.graphicsLayer {
        state.invalidation
        state.frameMillis
        val batch = state.batches.lastOrNull { source.start in it.sourceStart until it.sourceEnd }
        alpha = if (state.presentation.reduceMotion || batch == null) 1f else {
            val age = ((SystemClock.uptimeMillis() - batch.enteredAt).toFloat() / TextFadeMillis).coerceIn(0f, 1f)
            0.45f + 0.55f * age
        }
    }
}

/** 已排版 Text 的唯一范围绘制器：保留原 Text 的选择、链接和无障碍节点。 */
@Composable
internal fun streamingTextModifier(
    text: String,
    sourceOffsets: IntArray,
    modifier: Modifier,
    onLayout: (TextLayoutResult) -> Unit = {},
): Pair<Modifier, (TextLayoutResult) -> Unit> {
    val state = LocalStreamingDecoration.current ?: return modifier to onLayout
    val key = remember { Any() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val current = LocalStreamingTextPresentation.current ?: state.presentation
    val activeRanges = remember(text, sourceOffsets, current.batchId, current.hapticSourceRanges) {
        (current.hapticSourceRanges ?: listOf(current.sourceRevealStart until current.sourceRevealEnd)).flatMap {
            visibleSourceRanges(text, sourceOffsets, it.first, it.last + 1)
        }
    }
    fun publish() {
        val measured = layout ?: return
        val position = coordinates ?: return
        if (!position.isAttached) return
        if (measured.layoutInput.text.text != text) return
        val cursor = measured.getCursorRect(text.length.coerceAtMost(measured.layoutInput.text.length))
        state.register(key, StreamingTextLayout(sourceOffsets.maxOrNull() ?: -1, position, cursor,
            activeRanges.map { measured.getPathForRange(it.first, it.last + 1).getBounds() }, current.batchId))
    }
    SideEffect { publish() }
    DisposableEffect(state, key) { onDispose { state.unregister(key) } }
    // 路径只在文字布局或批次变化时生成；动画帧只读取透明度，不重扫全文或重建 AnnotatedString。
    val fadingPaths = remember(text, sourceOffsets, layout) { mutableMapOf<StreamingFadeBatch, List<Path>>() }
    val layoutModifier = modifier.onGloballyPositioned { coordinates = it; publish() }
    val fadeRanges = state.batches.map { it.sourceStart until it.sourceEnd } +
        if (state.presentation.batchId != current.batchId) listOf(current.sourceRevealStart until current.sourceRevealEnd) else emptyList()
    val needsFadeLayer = !current.reduceMotion && sourceOffsets.any { offset -> fadeRanges.any { offset in it } }
    val decorated = if (!needsFadeLayer) layoutModifier else layoutModifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            state.invalidation
            state.frameMillis
            if (state.presentation.reduceMotion) return@drawWithContent
            val measured = layout ?: return@drawWithContent
            val now = SystemClock.uptimeMillis()
            fadingPaths.keys.retainAll(state.batches.toSet())
            state.batches.forEach { batch ->
                val age = ((now - batch.enteredAt).toFloat() / TextFadeMillis).coerceIn(0f, 1f)
                if (age < 1f) {
                    val paths = fadingPaths.getOrPut(batch) {
                        visibleSourceRanges(text, sourceOffsets, batch.sourceStart, batch.sourceEnd).map {
                            measured.getPathForRange(it.first, it.last + 1)
                        }
                    }
                    paths.forEach { path ->
                        drawPath(path, Color.Black.copy(alpha = 0.55f * (1f - age)), blendMode = BlendMode.DstOut)
                    }
                }
            }
        }
    return decorated to { measured -> layout = measured; onLayout(measured); publish() }
}

/** 空白及 Markdown 隐藏语法不会成为触觉事件；同一范围只由真实渲染节点报告。 */
internal fun visibleSourceRanges(text: String, offsets: IntArray, start: Int, end: Int): List<IntRange> {
    if (end <= start) return emptyList()
    val ranges = ArrayList<IntRange>()
    var rangeStart = -1
    for (index in text.indices) {
        val visible = offsets.getOrNull(index)?.let { it in start until end } == true && !text[index].isWhitespace()
        if (visible && rangeStart < 0) rangeStart = index
        if (!visible && rangeStart >= 0) { ranges.add(rangeStart until index); rangeStart = -1 }
    }
    if (rangeStart >= 0) ranges.add(rangeStart until text.length)
    return ranges
}
