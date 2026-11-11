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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
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
)

/** 列表外只保存当前活跃正文，列表项回收和执行容器切换不重新播放。 */
class StreamingPresentationRegistry internal constructor(initial: List<TimelineEntry>) {
    // 首次挂载时，已有 LIVE 尾条仍可建立事实基线；SNAPSHOT 则保持静默完整展示。
    private val observed = initial.associate {
        it.key to if (it.presentation?.origin == StreamPresentationOrigin.SNAPSHOT) {
            it.presentation.revision
        } else {
            -1L
        }
    }.toMutableMap()
    private var currentKey: String? = null
    private var engine: StreamingPresentationState? = null
    private var nextBatch = 0L
    private var visible = true
    internal var frame by mutableStateOf<StreamingPresentationFrame?>(null)
        private set
    internal var hasPending by mutableStateOf(false)
        private set
    private var baseline = initial.lastOrNull { !it.isUser && it.isStreaming && !it.executionFact }

    internal fun frameFor(entry: TimelineEntry): StreamingPresentationFrame? =
        frame.takeIf { currentKey == entry.key }

    internal fun accept(entries: List<TimelineEntry>, pageVisible: Boolean, now: Long) {
        if (!pageVisible) {
            observed.clear()
            entries.forEach { observed[it.key] = it.presentation?.revision ?: -1L }
            baseline = entries.lastOrNull { !it.isUser && it.isStreaming && !it.executionFact }
            currentKey = null
            engine = null
            frame = null
            hasPending = false
            visible = pageVisible
            return
        }
        if (!visible) {
            // 页面重开只建立当前事实基线：显示已有全文，但不恢复旧吐字、渐入、拖尾或触觉。
            val active = entries.lastOrNull { !it.isUser && it.isStreaming && !it.executionFact }
            currentKey = active?.key
            engine = active?.let {
                StreamingPresentationState(it.text, it.presentation?.revision ?: -1L)
            }
            frame = active?.let { StreamingPresentationFrame(it.text, active = false) }
            hasPending = false
            observed.clear()
            entries.forEach { observed[it.key] = it.presentation?.revision ?: -1L }
            baseline = null
            visible = true
            return
        }
        val current = entries.firstOrNull { it.key == currentKey }
        val incoming = entries.lastOrNull { entry ->
            !entry.isUser && !entry.executionFact && entry.presentation?.origin == StreamPresentationOrigin.LIVE &&
                (entry.presentation.revision > (observed[entry.key] ?: -1L))
        } ?: current
        val retainedKeys = entries.mapTo(hashSetOf()) { it.key }
        observed.keys.retainAll(retainedKeys)
        if (incoming == null) {
            if (currentKey != null) { currentKey = null; engine = null; frame = null; hasPending = false }
            entries.forEach { observed[it.key] = it.presentation?.revision ?: -1L }
            return
        }
        val metadata = incoming.presentation
        if (metadata == null) {
            currentKey = null
            engine = null
            frame = null
        } else {
            if (currentKey != incoming.key) {
                val initial = baseline?.takeIf { it.key == incoming.key }
                engine = StreamingPresentationState(initial?.text.orEmpty(), initial?.presentation?.revision ?: -1L)
                currentKey = incoming.key
                baseline = null
            }
            val state = requireNotNull(engine)
            val advance = state.update(incoming.text, metadata.revision, metadata.origin, incoming.isStreaming, now)
            publish(state, advance)
        }
        entries.forEach { observed[it.key] = it.presentation?.revision ?: -1L }
    }

    internal fun advance(now: Long) {
        engine?.let { publish(it, it.advance(now)) }
    }

    private fun publish(state: StreamingPresentationState, advance: StreamingRevealAdvance) {
        hasPending = state.needsFrame
        val previous = frame
        if (advance.changed || advance.corrected) {
            frame = StreamingPresentationFrame(
                state.displayedText, state.isPresenting, ++nextBatch,
                if (advance.corrected) state.revealOffset else advance.startOffset,
                state.revealOffset, advance.hapticEligible && !advance.corrected,
            )
        } else if (previous == null || previous.active != state.isPresenting || previous.text != state.displayedText) {
            frame = (previous ?: StreamingPresentationFrame(state.displayedText)).copy(
                text = state.displayedText, active = state.isPresenting,
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
): StreamingPresentationRegistry {
    val view = LocalView.current
    var foreground by remember(view) { mutableStateOf(view.windowVisibility == View.VISIBLE) }
    DisposableEffect(view) {
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        fun update() {
            foreground = view.windowVisibility == View.VISIBLE &&
                (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
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
    val registry = remember(owner) { StreamingPresentationRegistry(entries) }
    val visible = enabled && foreground
    LaunchedEffect(registry, entries, visible) {
        registry.accept(entries, visible, System.nanoTime())
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
            if (enabled && registry.frame?.batchId == batchId && value.batchId == batchId && value.hapticEligible &&
                view.isShown && view.hasWindowFocus() && view.isHapticFeedbackEnabled &&
                view.findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false &&
                throttle.shouldTrigger(System.nanoTime(), (value.end - value.start).coerceAtLeast(1), true)
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
    val displayed = frame?.text ?: entry.text
    val active = frame?.active == true
    val haptic = if (frame != null) {
        rememberStreamingHapticAdvance(frame, requireNotNull(registry))
    } else {
        null
    }
    val presentation = StreamingTextPresentation(
        batchId = frame?.batchId ?: 0L,
        sourceRevealStart = frame?.start ?: 0,
        sourceRevealEnd = frame?.end ?: 0,
        active = active,
        reduceMotion = com.openandroidintelligence.ui.design.LocalMotionPolicy.current.reduceMotion,
        onVisibleAdvance = { id -> haptic?.invoke(id) },
    )
    val document = rememberStreamingMarkdownDocument(displayed)
    // 执行事实仍按完整消息分组；最终正文仅使用同一次解析中最后一个执行块之后的节点，
    // 不能把整条 entry 重新当 narrative 解析，也不能按字符串相似度寻找正文。
    val blocks = remember(document, finalOnly) {
        if (finalOnly) document.blocks.drop(document.blocks.indexOfLast(::isExecutionBlock) + 1)
        else document.blocks
    }
    StreamingMarkdownContainer(
        sourceText = displayed,
        presentation = presentation,
        modifier = modifier,
        document = document,
    ) { content(blocks, active) }
}
