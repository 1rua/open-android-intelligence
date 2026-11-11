package com.openandroidintelligence.conversation.workbench

import android.icu.text.BreakIterator
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import java.util.Locale
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

/** 一次实际可见的正文推进；触觉资格只来自实时新增字素。 */
data class StreamingRevealAdvance(
    val startOffset: Int,
    val endOffset: Int,
    val hapticEligible: Boolean = false,
    val corrected: Boolean = false,
    val advancedGraphemes: Int = 0,
) {
    val changed: Boolean get() = corrected || endOffset > startOffset
}

/**
 * 单条活跃正文的临时呈现状态。全文只保留接收方交入的一个引用，缓冲和可见内容按偏移派生。
 * 调用方拥有帧时钟与页面生命周期；这里不启动协程、计时器或触觉任务。
 */
class StreamingPresentationState(initialText: String = "", initialRevision: Long = -1L) {
    var receivedText: String = initialText
        private set
    var revealOffset: Int = initialText.length
        private set
    val displayedText: String get() = receivedText.substring(0, revealOffset)
    val pendingBuffer: String get() = receivedText.substring(revealOffset)
    val isPresenting: Boolean get() = enrolled && (streaming || revealOffset < receivedText.length)
    val needsFrame: Boolean get() = enrolled && revealOffset < safeEnd()

    private var revision = initialRevision
    private var enrolled = false
    private var streaming = false
    private var lastFrameNanos: Long? = null
    private var drainDeadlineNanos: Long? = null
    private var speed = 40.0
    private var credit = 0.0
    private val boundaries = mutableListOf(0)
    private val sources = ArrayDeque<SourceRange>()
    private data class SourceRange(val start: Int, var end: Int, val live: Boolean)

    init { rebuildBoundaries(0) }

    fun update(
        text: String,
        revision: Long,
        origin: StreamPresentationOrigin,
        streaming: Boolean,
        nowNanos: Long,
    ): StreamingRevealAdvance {
        val unchanged = StreamingRevealAdvance(revealOffset, revealOffset)
        // 旧事件可能没有内容修订号（统一为 0）；同修订的合法前缀追加仍须接收，
        // 但同修订的非前缀改写不能覆盖已展示内容。
        if (revision < this.revision ||
            (revision == this.revision && text != receivedText &&
                !text.startsWith(receivedText))) {
            return unchanged
        }
        val oldText = receivedText
        val oldOffset = revealOffset
        this.revision = revision
        this.streaming = streaming
        if (!streaming && enrolled && drainDeadlineNanos == null) drainDeadlineNanos = nowNanos + DrainNanos
        if (streaming) drainDeadlineNanos = null
        if (text == oldText) return unchanged
        val append = text.startsWith(oldText)
        receivedText = text
        if (!append) {
            revealOffset = text.length
            enrolled = false
            sources.clear()
            credit = 0.0
            rebuildBoundaries(0)
            lastFrameNanos = nowNanos
            return StreamingRevealAdvance(oldOffset, revealOffset, corrected = true)
        }
        val restart = boundaries.getOrElse((boundaries.size - 2).coerceAtLeast(0)) { 0 }
        rebuildBoundaries(restart)
        if (!enrolled && origin == StreamPresentationOrigin.SNAPSHOT) {
            revealOffset = text.length
            lastFrameNanos = nowNanos
            return StreamingRevealAdvance(oldOffset, revealOffset, corrected = true)
        }
        val firstLive = !enrolled
        enrolled = true
        appendSource(oldText.length, text.length, origin == StreamPresentationOrigin.LIVE)
        // 新分块可能续接已经显示的末字素：原位补齐，不把组合符或 ZWJ 当成新打字。
        var extensionEnd = oldOffset
        if (oldOffset > 0 && oldOffset !in boundaries) {
            extensionEnd = boundaries.firstOrNull { it > oldOffset && it <= safeEnd() } ?: oldOffset
            revealOffset = extensionEnd
            discardConsumedSources()
        }
        if (firstLive) {
            lastFrameNanos = nowNanos
            if (!streaming) drainDeadlineNanos = nowNanos + DrainNanos
            val next = boundaries.firstOrNull { it > revealOffset && it <= safeEnd() }
            if (next != null) return revealTo(next, extensionEnd)
        }
        return StreamingRevealAdvance(oldOffset, revealOffset)
    }

    fun advance(nowNanos: Long): StreamingRevealAdvance {
        val oldOffset = revealOffset
        val previous = lastFrameNanos
        if (!enrolled || previous == null) return StreamingRevealAdvance(oldOffset, oldOffset)
        val elapsed = (nowNanos - previous).coerceAtLeast(0L)
        if (elapsed < FrameNanos) return StreamingRevealAdvance(oldOffset, oldOffset)
        lastFrameNanos = nowNanos
        val safeEnd = safeEnd()
        val remaining = boundaries.count { it > oldOffset && it <= safeEnd }
        if (remaining == 0) {
            credit = 0.0
            return StreamingRevealAdvance(oldOffset, oldOffset)
        }
        val seconds = elapsed.coerceAtMost(100_000_000L) / 1_000_000_000.0
        val desired = 40.0 + remaining / 0.35
        speed += (desired - speed) * (1.0 - exp(-seconds / 0.12))
        val deadline = drainDeadlineNanos
        val effectiveSpeed = if (deadline == null) speed else max(speed,
            remaining / ((deadline - nowNanos).coerceAtLeast(1L) / 1_000_000_000.0))
        credit += effectiveSpeed * seconds
        val count = if (deadline != null && nowNanos >= deadline) remaining else floor(credit).toInt().coerceAtMost(remaining)
        if (count == 0) return StreamingRevealAdvance(oldOffset, oldOffset)
        credit = (credit - count).coerceAtLeast(0.0)
        val end = boundaries.asSequence().filter { it > oldOffset && it <= safeEnd }.take(count).last()
        return revealTo(end, oldOffset)
    }

    private fun revealTo(end: Int, start: Int): StreamingRevealAdvance {
        val live = sources.any { it.live && it.end > start && it.start < end }
        val count = boundaries.count { it > start && it <= end }
        revealOffset = end
        discardConsumedSources()
        return StreamingRevealAdvance(start, end, live && count > 0, advancedGraphemes = count)
    }

    private fun appendSource(start: Int, end: Int, live: Boolean) {
        val last = sources.lastOrNull()
        if (last != null && last.live == live && last.end == start) last.end = end
        else sources.addLast(SourceRange(start, end, live))
    }

    private fun discardConsumedSources() {
        while (sources.firstOrNull()?.end?.let { it <= revealOffset } == true) sources.removeFirst()
    }

    private fun safeEnd(): Int {
        if (!streaming || receivedText.isEmpty()) return receivedText.length
        var end = receivedText.length
        if (receivedText.last().isHighSurrogate()) end--
        // 已知未闭合的连接符留到下一次接收；正常末字素无需预等待。
        if (end > 0 && receivedText[end - 1] == '\u200D') {
            end = boundaries.lastOrNull { it < end } ?: 0
        }
        return end
    }

    private fun rebuildBoundaries(start: Int) {
        while (boundaries.lastOrNull()?.let { it >= start } == true) boundaries.removeAt(boundaries.lastIndex)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(receivedText.substring(start))
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            boundaries += start + boundary
            boundary = iterator.next()
        }
    }

    private companion object {
        const val FrameNanos = 33_333_333L
        const val DrainNanos = 250_000_000L
    }
}

/** 对已显示字素采样；不保存待补的振动事件，停顿后也不会集中补震。 */
class StreamingHapticThrottle {
    private var lastTriggerNanos: Long? = null
    private var lastAdvanceNanos: Long? = null
    private var recentRate = 0.0

    fun shouldTrigger(nowNanos: Long, advancedGraphemes: Int, eligible: Boolean = true): Boolean {
        if (!eligible || advancedGraphemes <= 0) return false
        lastAdvanceNanos?.let { previous ->
            val seconds = (nowNanos - previous).coerceAtLeast(1L) / 1_000_000_000.0
            recentRate += (advancedGraphemes / seconds - recentRate) * (1.0 - exp(-seconds / 0.12))
        }
        lastAdvanceNanos = nowNanos
        val interval = (100_000_000.0 + 50_000_000.0 * recentRate / (recentRate + 80.0)).toLong()
        if (lastTriggerNanos?.let { nowNanos - it < interval } == true) return false
        lastTriggerNanos = nowNanos
        return true
    }

    fun reset() {
        lastTriggerNanos = null
        lastAdvanceNanos = null
        recentRate = 0.0
    }
}
