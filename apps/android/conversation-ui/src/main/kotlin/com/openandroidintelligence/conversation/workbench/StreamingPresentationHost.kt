package com.openandroidintelligence.conversation.workbench

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.LocalStreamingHapticFeedbackEnabled

/** 一次已经提交给正文的推进；不会把临时文本写回会话事实。 */
internal data class StreamingPresentationFrame(
    val text: String,
    val active: Boolean = false,
    val batchId: Long = 0L,
    val start: Int = 0,
    val end: Int = 0,
    val hapticEligible: Boolean = false,
    val advancedGraphemes: Int = 0,
    val liveSourceRanges: List<IntRange> = emptyList(),
    val sourceStart: Int = 0,
)

/** 完整接收事实的最终回答起点；只在事实文本变化时复用原解析器及来源图计算。 */
private class StreamingReplySource {
    private val cache = StreamingMarkdownCache()
    private var receivedText: String? = null
    private var start = 0

    fun start(text: String): Int {
        if (text == receivedText) return start
        receivedText = text
        start = if (!mayContainExecutionMarkup(text)) 0 else {
            val document = cache.parse(text)
            val lastExecution = document.blocks.indexOfLast(::isExecutionBlock)
            if (lastExecution < 0) 0 else {
                var offset = document.trace.blocks[document.blocks[lastExecution]]?.source?.end ?: text.length
                while (offset < text.length && text[offset].isWhitespace()) offset++
                offset
            }
        }
        return start
    }
}

/** 列表外只保存当前活跃正文，列表项回收和执行容器切换不重新播放。 */
class StreamingPresentationRegistry internal constructor(initial: List<TimelineEntry>) {
    private data class Observation(val identity: ReplyIdentity, val revision: Long, val text: String) {
        constructor(entry: TimelineEntry) : this(ReplyIdentity(entry), entry.presentation?.revision ?: -1L, entry.text)
        val length: Int get() = text.length
        fun isPrefixOf(entry: TimelineEntry): Boolean = entry.text.length > length && entry.text.startsWith(text)
    }
    private data class ReplyIdentity(
        val key: String, val generation: String?, val client: String?, val placeholder: Boolean,
    ) {
        constructor(entry: TimelineEntry) : this(entry.key, entry.generationId, entry.clientMessageId?.value, entry.localPlaceholder)
        fun isUpgrade(entry: TimelineEntry): Boolean = placeholder && !entry.localPlaceholder && key != entry.key &&
            (generation != null && entry.generationId == generation || client != null && entry.clientMessageId?.value == client) &&
            (generation == null || entry.generationId == null || entry.generationId == generation) &&
            (client == null || entry.clientMessageId == null || entry.clientMessageId.value == client)
    }
    // 挂载时的所有正文都是基线，包括仍标作 LIVE 的缓存；只观察之后发生的增长。
    private val observed = initial.associate { it.key to Observation(it) }.toMutableMap()
    private var currentIdentity: ReplyIdentity? = null
    private var engine: StreamingPresentationState? = null
    private val source = StreamingReplySource()
    private var sourceStart = 0
    private var generationOwner: ReplyIdentity? = null
    private var nextBatch = 0L
    private var consumedHapticBatch = 0L
    private var visible = true
    private var surfaceVisible = true
    private var prepared = false
    private var preparedKey: String? = null
    internal var frame by mutableStateOf<StreamingPresentationFrame?>(null)
        private set
    internal var hasPending by mutableStateOf(false)
        private set

    /** 仅准备本次组合的投影，不写 Compose 状态或驱动动画。 */
    internal fun prepare(entries: List<TimelineEntry>, pageVisible: Boolean) {
        surfaceVisible = pageVisible
        prepared = true
        preparedKey = entries.lastOrNull(::canStart)?.key
    }

    /** 初次挂载和页面重开已有内容直接显示，不补播旧流或静态光标。 */
    internal fun isBaseline(entry: TimelineEntry): Boolean = frameFor(entry) == null

    internal fun frameFor(entry: TimelineEntry): StreamingPresentationFrame? {
        // 临时首帧投影也必须订阅已提交进度；否则组合只读普通字段，首个 accept 后
        // frame 的变化没有订阅者，正文会停在空投影而调度器仍在后台推进。
        val committed = frame
        if (!visible || !surfaceVisible || prepared && preparedKey != null && preparedKey != entry.key) return null
        if (currentIdentity == ReplyIdentity(entry) || currentIdentity?.isUpgrade(entry) == true) {
            val start = source.start(entry.text)
            return if (committed != null && committed.sourceStart != start) committed.copy(sourceStart = start) else committed
        }
        if (!canStart(entry)) return null
        // accept 在组合提交后运行；先给新 LIVE 一个仅含旧前缀的投影，首帧不能闪现整段全文。
        return StreamingPresentationFrame(baselineText(entry), active = true, sourceStart = source.start(entry.text))
    }

    internal fun accept(entries: List<TimelineEntry>, pageVisible: Boolean, now: Long, generation: GenerationState? = null) {
        if (!pageVisible || !visible) {
            clearActive()
            rememberAll(entries)
            visible = pageVisible
            return
        }
        val identity = currentIdentity
        val current = entries.firstOrNull { ReplyIdentity(it) == identity }
        val upgrade = if (current == null) entries.lastOrNull { identity?.isUpgrade(it) == true && eligible(it) } else null
        val incoming = entries.lastOrNull(::canStart) ?: current ?: upgrade
        if (incoming == null || !eligible(incoming) || incoming.presentation == null) {
            clearActive()
            rememberAll(entries)
            return
        }
        val metadata = incoming.presentation
        if (identity != ReplyIdentity(incoming)) {
            if (identity?.isUpgrade(incoming) == true && engine != null) {
                // 仅占位身份升级继承进度；两个真实消息 ID 即使正文相同也永不合并。
                engine?.adoptRevision(metadata.revision)
            } else {
                engine = StreamingPresentationState(baselineText(incoming), observationFor(incoming)?.revision ?: -1L)
                frame = null
            }
            currentIdentity = ReplyIdentity(incoming)
        } else {
            currentIdentity = ReplyIdentity(incoming)
        }
        val state = requireNotNull(engine)
        // 无轮次 ID 的全局状态只能结束此前确实观察过生成中的同一回复；旧终态不能认领新回复。
        if (generation == GenerationState.RUNNING || generation == GenerationState.QUEUED || generation == GenerationState.CANCEL_REQUESTED) {
            generationOwner = ReplyIdentity(incoming)
        }
        val scopedTerminal = incoming.executionGenerationState?.isTerminal() == true ||
            incoming.generationId?.let { id -> entries.any { it.generationId == id && it.executionGenerationState?.isTerminal() == true } } == true
        val ownedTerminal = generation?.isTerminal() == true && generationOwner?.let {
            it == ReplyIdentity(incoming) || it.isUpgrade(incoming)
        } == true
        sourceStart = source.start(incoming.text)
        val advance = state.update(incoming.text, metadata.revision, metadata.origin,
            incoming.isStreaming && !scopedTerminal && !ownedTerminal, now, revealFromOffset = sourceStart)
        publish(state, advance)
        rememberAll(entries)
    }

    internal fun advance(now: Long) {
        if (visible && surfaceVisible) engine?.let { publish(it, it.advance(now)) }
    }

    internal fun close() {
        surfaceVisible = false
        visible = false
        clearActive()
        observed.clear()
        generationOwner = null
    }

    internal fun hide() {
        // ON_STOP 时 Recomposer 可能已暂停，必须立即退役呈现，不等待下一次组合应用。
        surfaceVisible = false
        visible = false
        clearActive()
    }

    /** 一个可见批次最多消费一次；关闭设置后再打开也不会补震旧文字。 */
    internal fun consumeHaptic(batchId: Long): Boolean {
        val current = frame ?: return false
        if (!visible || !surfaceVisible || prepared && preparedKey != null && preparedKey != currentIdentity?.key ||
            current.batchId != batchId || batchId <= consumedHapticBatch) return false
        consumedHapticBatch = batchId
        return current.hapticEligible
    }

    private fun eligible(entry: TimelineEntry): Boolean = !entry.isUser && entry.sender == "assistant" &&
        !entry.executionFact && entry.approval == null && entry.systemThreadId == null

    private fun canStart(entry: TimelineEntry): Boolean {
        if (!eligible(entry) || entry.presentation?.origin != StreamPresentationOrigin.LIVE) return false
        val previous = observationFor(entry) ?: return entry.text.isNotEmpty()
        return entry.presentation.revision >= previous.revision && previous.isPrefixOf(entry)
    }

    private fun baselineText(entry: TimelineEntry): String {
        val previous = observationFor(entry) ?: return ""
        if (entry.text.length < previous.length) return entry.text
        val prefix = entry.text.substring(0, previous.length)
        return if (prefix == previous.text) prefix else entry.text
    }

    // 显示键可以在新生成中复用；只有完整身份相同的事实才允许继承正文、修订和终态时钟。
    private fun observationFor(entry: TimelineEntry): Observation? = observed[entry.key]
        ?.takeIf { it.identity == ReplyIdentity(entry) }

    private fun rememberAll(entries: List<TimelineEntry>) {
        observed.clear()
        entries.forEach { observed[it.key] = Observation(it) }
    }

    private fun clearActive() {
        currentIdentity = null
        engine = null
        frame = null
        hasPending = false
        sourceStart = 0
    }

    private fun publish(state: StreamingPresentationState, advance: StreamingRevealAdvance) {
        hasPending = state.needsFrame
        val previous = frame
        if (advance.changed) {
            frame = StreamingPresentationFrame(
                state.displayedText, state.isPresenting, ++nextBatch,
                if (advance.corrected) state.revealOffset else advance.startOffset,
                state.revealOffset, advance.hapticEligible && !advance.corrected,
                advance.advancedGraphemes,
                advance.liveSourceRanges, sourceStart,
            )
        } else if (previous == null || previous.active != state.isPresenting || previous.text != state.displayedText || previous.sourceStart != sourceStart) {
            frame = (previous ?: StreamingPresentationFrame(state.displayedText)).copy(
                text = state.displayedText, active = state.isPresenting, sourceStart = sourceStart,
            )
        }
    }
}

/** 宿主自己的生命周期控制呈现，不停止仓储接收、同步或持久化。 */
@Composable
fun rememberStreamingPresentations(
    entries: List<TimelineEntry>,
    owner: Any?,
    enabled: Boolean = true,
    generation: GenerationState? = null,
): StreamingPresentationRegistry {
    val view = LocalView.current
    val registry = remember(owner) { StreamingPresentationRegistry(entries) }
    var foreground by remember(view) { mutableStateOf(view.windowVisibility == View.VISIBLE) }
    DisposableEffect(view, registry) {
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        fun update() {
            foreground = view.windowVisibility == View.VISIBLE &&
                (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            if (!foreground) registry.hide()
        }
        val listener = LifecycleEventObserver { _, _ -> update() }
        lifecycle?.addObserver(listener)
        val visibilityListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { update() }
        val observer = view.viewTreeObserver
        observer.addOnWindowFocusChangeListener(visibilityListener)
        update()
        onDispose {
            lifecycle?.removeObserver(listener)
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(visibilityListener)
        }
    }
    DisposableEffect(registry) { onDispose { registry.close() } }
    val visible = enabled && foreground
    registry.prepare(entries, visible)
    LaunchedEffect(registry, entries, visible, generation) {
        // 接收与消费共用 Compose 帧时间基准，避免测试时钟或宿主时钟的起点不同导致永不推进。
        if (visible) withFrameNanos { registry.accept(entries, true, it, generation) }
        else registry.accept(entries, false, 0L, generation)
    }
    // 只在有待显内容时请求帧。网络空闲、后台和终态排空后不留下循环。
    val pending = registry.hasPending
    LaunchedEffect(registry, visible, pending) {
        if (visible) while (registry.hasPending) {
            withFrameNanos { registry.advance(it) }
        }
    }
    return registry
}

/** 返回一次性、可丢弃的触觉回调；不排队、不创建每字任务。 */
@Composable
internal fun rememberStreamingHapticAdvance(
    frame: StreamingPresentationFrame, registry: StreamingPresentationRegistry,
): (Long) -> Unit {
    val view = LocalView.current
    val enabled by rememberUpdatedState(LocalStreamingHapticFeedbackEnabled.current)
    val current by rememberUpdatedState(frame)
    val throttle = remember(view) { StreamingHapticThrottle() }
    DisposableEffect(view) { onDispose { throttle.reset() } }
    return remember(view, throttle, registry) {
        { batchId ->
            val value = current
            val fresh = value.batchId == batchId && registry.consumeHaptic(batchId)
            if (fresh && enabled && value.hapticEligible &&
                view.isShown && view.hasWindowFocus() && view.isHapticFeedbackEnabled &&
                view.findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false &&
                throttle.shouldTrigger(System.nanoTime(), value.advancedGraphemes.coerceAtLeast(1), true)
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }
}

/**
 * 共享正文呈现入口：事实条目保持完整，只有 displayedText 进入 Markdown 叶节点。
 * 普通消息和执行段最终回答都通过它接入，避免列表分组状态被临时吐字影响。
 */
@Composable
internal fun StreamingReplyBody(
    entry: TimelineEntry,
    registry: StreamingPresentationRegistry?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    finalOnly: Boolean = false,
    content: @Composable (List<TimelineBlock>, Boolean) -> Unit,
) {
    val frame = registry?.frameFor(entry)
    // 列表回收或呈现容器切换会重建局部绘制状态；挂载时已有的批次只作为稳定基线。
    // 取原始进度而非可能仍等待后台解析的 renderFrame，避免解析完成后补播旧范围。
    val mountedBatch = remember(registry, entry.key) { frame?.batchId ?: 0L }
    val receivedSource = remember(entry.key) { StreamingReplySource() }
    val sourceStart = if (finalOnly) frame?.sourceStart ?: receivedSource.start(entry.text) else 0
    val prefix = frame?.text ?: entry.text
    val displayed = prefix.drop(sourceStart)
    val document = rememberStreamingMarkdownDocument(displayed)
    var committedFrame by remember(entry.key, sourceStart) { mutableStateOf<StreamingPresentationFrame?>(null) }
    val renderFrame = if (document.sourceText == displayed || frame?.start == frame?.end) frame else committedFrame
    SideEffect { if (document.sourceText == displayed) committedFrame = frame }
    // 已消费完源文仍需等待最后一次 Markdown 解析真正上屏，之后才结束光标。
    val active = frame?.active == true || (frame != null && document.sourceText != displayed)
    val haptic = if (renderFrame != null) {
        rememberStreamingHapticAdvance(renderFrame, requireNotNull(registry))
    } else {
        null
    }
    val newBatch = renderFrame != null && renderFrame.batchId > mountedBatch
    val presentation = StreamingTextPresentation(
        batchId = renderFrame?.batchId ?: 0L,
        sourceRevealStart = ((if (newBatch) renderFrame.start else renderFrame?.end ?: 0) - sourceStart).coerceAtLeast(0),
        sourceRevealEnd = ((renderFrame?.end ?: 0) - sourceStart).coerceAtLeast(0),
        active = active,
        reduceMotion = com.openandroidintelligence.ui.design.LocalMotionPolicy.current.reduceMotion,
        onVisibleAdvance = { id -> if (newBatch) haptic?.invoke(id) },
        hapticSourceRanges = if (newBatch) renderFrame.liveSourceRanges.mapNotNull { range ->
            val start = maxOf(range.first, sourceStart)
            if (range.last < start) null else (start - sourceStart)..(range.last - sourceStart)
        } else emptyList(),
    )
    // 最终回答的来源起点已经由完整事实确认；半截展示前缀绝不能重新猜测执行标签。
    StreamingMarkdownContainer(
        sourceText = displayed,
        presentation = presentation,
        modifier = modifier,
        document = document,
    ) { content(document.blocks, active) }
}
