package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

private val ClosedBlockSeparatorRegex = Regex("^[ \\t]*(?:\\r?\\n)[ \\t]*(?:\\r?\\n)")

/** 只属于本次排版的来源信息，不进入消息模型、协议或持久化。 */
internal class MarkdownSourceText(
    val text: String,
    val offsets: IntArray,
    val emptyOffset: Int = offsets.firstOrNull() ?: 0,
) {
    val start: Int get() = offsets.firstOrNull() ?: emptyOffset
    val end: Int get() = offsets.lastOrNull()?.plus(1) ?: emptyOffset

    fun slice(start: Int, end: Int = text.length): MarkdownSourceText = MarkdownSourceText(
        text.substring(start, end), offsets.copyOfRange(start, end),
        offsets.getOrNull(start) ?: this.end,
    )

    fun trim(): MarkdownSourceText {
        val first = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: text.length
        val end = text.indexOfLast { !it.isWhitespace() }.plus(1).coerceAtLeast(first)
        return slice(first, end)
    }

    fun trimEnd(): MarkdownSourceText = slice(0, text.indexOfLast { !it.isWhitespace() }.plus(1))

    fun lines(): List<MarkdownSourceText> {
        val lines = ArrayList<MarkdownSourceText>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] == '\r' || text[index] == '\n') {
                lines.add(slice(start, index))
                if (text[index] == '\r' && text.getOrNull(index + 1) == '\n') index++
                start = index + 1
            }
            index++
        }
        lines.add(slice(start))
        return lines
    }

    companion object {
        fun original(text: String, offset: Int = 0) =
            MarkdownSourceText(text, IntArray(text.length) { offset + it }, offset)

        fun join(parts: List<MarkdownSourceText>, separator: String = "\n"): MarkdownSourceText {
            if (parts.isEmpty()) return original("")
            val text = parts.joinToString(separator) { it.text }
            val offsets = ArrayList<Int>(text.length)
            parts.forEachIndexed { index, part ->
                if (index > 0) repeat(separator.length) { offsets.add(parts[index - 1].end) }
                part.offsets.forEach(offsets::add)
            }
            return MarkdownSourceText(text, offsets.toIntArray(), parts.first().start)
        }
    }
}

internal data class MarkdownBlockSource(
    val source: MarkdownSourceText,
    val leaves: List<MarkdownSourceText>,
    val closed: Boolean = true,
)

internal class MarkdownParseTrace {
    val blocks = IdentityHashMap<TimelineBlock, MarkdownBlockSource>()

    fun record(
        block: TimelineBlock,
        source: MarkdownSourceText?,
        leaves: List<MarkdownSourceText> = source?.let(::listOf).orEmpty(),
        closed: Boolean = true,
    ): TimelineBlock {
        if (source != null) blocks[block] = MarkdownBlockSource(source, leaves, closed)
        return block
    }
}

/** 与既有 AST 一起使用的临时来源图；历史调用方仍可直接使用 MarkdownParser.parse。 */
class StreamingMarkdownDocument internal constructor(
    val sourceText: String,
    val blocks: List<TimelineBlock>,
    internal val trace: MarkdownParseTrace,
    internal val closedThroughOffset: Int,
)

/**
 * 只缓存有顶层分隔空行且语法已经关闭的前置块；尾部列表、表格、段落和未闭合围栏始终重算。
 * 非追加快照自动使缓存失效，不用第二套语法扫描器猜测块类型。
 */
class StreamingMarkdownCache {
    private var previous: StreamingMarkdownDocument? = null
    private var previousNarrative = false

    @Synchronized
    fun parse(text: String, narrative: Boolean = false): StreamingMarkdownDocument {
        val old = previous
        if (old != null && old.sourceText == text && previousNarrative == narrative) return old
        val prefixEnd = old?.closedThroughOffset?.takeIf {
            previousNarrative == narrative && it > 0 && text.startsWith(old.sourceText.substring(0, it))
        } ?: 0
        val trace = MarkdownParseTrace()
        val prefix = if (prefixEnd > 0) old!!.blocks.takeWhile { block ->
            (old.trace.blocks[block]?.source?.end ?: Int.MAX_VALUE) <= prefixEnd
        } else emptyList()
        prefix.forEach { block -> copyTrace(block, old!!.trace, trace) }
        val tail = if (prefixEnd == text.length && prefix.isNotEmpty()) emptyList()
        else MarkdownParser.parseTraced(text.substring(prefixEnd), trace, prefixEnd, narrative)
        val blocks = prefix + tail
        var closedThrough = prefixEnd
        for (block in blocks) {
            // 分隔线是共享 data object，没有唯一的实例位置，因此不跨它缓存。
            if (block is TimelineBlock.ThematicBreak) break
            val source = trace.blocks[block] ?: break
            if (!source.closed) break
            val following = text.substring(source.source.end.coerceAtMost(text.length))
            val separator = ClosedBlockSeparatorRegex.find(following)
            if (separator != null) closedThrough = source.source.end + separator.value.length
        }
        return StreamingMarkdownDocument(text, blocks, trace, closedThrough).also {
            previous = it
            previousNarrative = narrative
        }
    }

    private fun copyTrace(block: TimelineBlock, from: MarkdownParseTrace, to: MarkdownParseTrace) {
        from.blocks[block]?.let { to.blocks[block] = it }
        if (block is TimelineBlock.Blockquote) block.blocks.forEach { copyTrace(it, from, to) }
    }
}

@Composable
fun rememberStreamingMarkdownDocument(sourceText: String, narrative: Boolean = false): StreamingMarkdownDocument {
    val cache = remember(narrative) { StreamingMarkdownCache() }
    // 首个小片段立即解析，不给首字增加异步等待；后续可变尾段由唯一的可取消收集器处理。
    var document by remember(cache) { mutableStateOf(cache.parse(sourceText, narrative)) }
    val latestText by rememberUpdatedState(sourceText)
    LaunchedEffect(cache) {
        snapshotFlow { latestText }.collectLatest { text ->
            if (document.sourceText != text) {
                val next = withContext(Dispatchers.Default) { cache.parse(text, narrative) }
                document = next
            }
        }
    }
    return document
}
