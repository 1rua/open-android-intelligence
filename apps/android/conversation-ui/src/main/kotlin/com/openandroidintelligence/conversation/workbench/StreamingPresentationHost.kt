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
    val advancedGraphemes: Int = 0,
)

/** 列表外只保存当前活跃正文，列表项回收和执行容器切换不重新播放。 */
class StreamingPresentationRegistry internal constructor(initial: List<TimelineEntry>) {
    private data class Observation(val revision: Long, val length: Int, val hash: Int) {
        constructor(entry: TimelineEntry) : this(entry.presentation?.revision ?: -1L, entry.text.length, entry.text.hashCode())
        fun isPrefixOf(entry: TimelineEntry): Boolean = entry.text.length > length &&
            entry.text.substring(0, length).hashCode() == hash
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
        if (!visible || !surfaceVisible || prepared && preparedKey != null && preparedKey != entry.key) return null
        if (currentIdentity?.key == entry.key) return frame
        if (currentIdentity?.isUpgrade(entry) == true) return frame
        if (!canStart(entry)) return null
        // accept 在组合提交后运行；先给新 LIVE 一个仅含旧前缀的投影，首帧不能闪现整段全文。
        return StreamingPresentationFrame(baselineText(entry), active = true)
    }

    internal fun accept(entries: List<TimelineEntry>, pageVisible: Boolean, now: Long) {
        if (!pageVisible || !visible) {
            clearActive()
            rememberAll(entries)
            visible = pageVisible
            return
        }
        val identity = currentIdentity
        val current = entries.firstOrNull { it.key == identity?.key }
        val upgrade = if (current == null) entries.lastOrNull { identity?.isUpgrade(it) == true && eligible(it) } else null
        val incoming = entries.lastOrNull(::canStart) ?: current ?: upgrade
        if (incoming == null || !eligible(incoming) || incoming.presentation == null) {
            clearActive()
            rememberAll(entries)
            return
        }
        val metadata = incoming.presentation
        if (identity?.key != incoming.key) {
            if (identity?.isUpgrade(incoming) == true && engine != null) {
                // 仅占位身份升级继承进度；两个真实消息 ID 即使正文相同也永不合并。
                engine?.adoptRevision(metadata.revision)
            } else {
                engine = StreamingPresentationState(baselineText(incoming), observed[incoming.key]?.revision ?: -1L)
                frame = null
            }
            currentIdentity = ReplyIdentity(incoming)
        } else {
            currentIdentity = ReplyIdentity(incoming)
        }
        val state = requireNotNull(engine)
        val advance = state.update(incoming.text, metadata.revision, metadata.origin, incoming.isStreaming, now)
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
        val previous = observed[entry.key] ?: return entry.text.isNotEmpty()
        return entry.presentation.revision >= previous.revision && previous.isPrefixOf(entry)
    }

    private fun baselineText(entry: TimelineEntry): String {
        val previous = observed[entry.key] ?: return ""
        if (entry.text.length < previous.length) return entry.text
        val prefix = entry.text.substring(0, previous.length)
        return if (prefix.hashCode() == previous.hash) prefix else entry.text
    }

    private fun rememberAll(entries: List<TimelineEntry>) {
        observed.clear()
        entries.forEach { observed[it.key] = Observation(it) }
    }

    private fun clearActive() {
        currentIdentity = null
        engine = null
        frame = null
        hasPending = false
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
    DisposableEffect(registry) { onDispose { registry.close() } }
    val visible = enabled && foreground
    registry.prepare(entries, visible)
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
