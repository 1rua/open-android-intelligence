package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/**
 * 行内 Markdown 的配色。颜色一律来自 MaterialTheme 语义角色，解析器本身不认识主题。
 *
 * [markBackground] / [keyboardBackground] / [keyboardTextColor] 是 HTML 白名单标签
 * （`<mark>`、`<kbd>`）的专用配色，默认 Unspecified 时回退到代码块配色。
 */
data class MarkdownColors(
    val textColor: Color,
    val boldColor: Color,
    val italicColor: Color,
    val codeColor: Color,
    val codeBackground: Color,
    val linkColor: Color,
    val markBackground: Color = Color.Unspecified,
    val keyboardBackground: Color = Color.Unspecified,
    val keyboardTextColor: Color = Color.Unspecified,
) {
    val resolvedMarkBackground: Color
        get() = if (markBackground.isSpecified) markBackground else linkColor.copy(alpha = 0.16f)

    val resolvedKeyboardBackground: Color
        get() = if (keyboardBackground.isSpecified) keyboardBackground else codeBackground

    val resolvedKeyboardTextColor: Color
        get() = if (keyboardTextColor.isSpecified) keyboardTextColor else textColor
}

@Composable
fun defaultMarkdownColors(): MarkdownColors {
    val scheme = MaterialTheme.colorScheme
    return MarkdownColors(
        textColor = scheme.onSurface,
        boldColor = scheme.onSurface,
        italicColor = scheme.onSurfaceVariant,
        codeColor = scheme.primary,
        codeBackground = scheme.surfaceContainerHighest.copy(alpha = 0.7f),
        linkColor = scheme.primary,
        markBackground = scheme.primary.copy(alpha = 0.16f),
        keyboardBackground = scheme.surfaceContainerHighest.copy(alpha = 0.85f),
        keyboardTextColor = scheme.onSurface,
    )
}

/** 行内公式占位：id 由解析器生成，latex 供 WebView 渲染与无障碍替代文本使用。 */
data class MarkdownInlineMathSpan(
    val id: String,
    val latex: String,
)

/**
 * 行内解析结果：AnnotatedString 里已经挂好链接注解与公式占位，
 * [inlineMath] 交给 Compose 侧构造 `InlineTextContent`。
 */
data class MarkdownInlineResult(
    val annotated: AnnotatedString,
    val inlineMath: List<MarkdownInlineMathSpan> = emptyList(),
)

/**
 * 将一段 Markdown 行内文本解析成 AnnotatedString。
 *
 * 支持：粗体、斜体、删除线、行内代码、链接、自动链接（`<https://…>` 与裸 URL）、
 * 行内数学公式（`$…$`）以及白名单内的基础 HTML 标签（`<kbd>`、`<sub>`、`<sup>` 等）。
 * [onLinkClick] 由调用方提供：只有受支持协议（http/https/mailto/tel）才会被挂上链接注解，
 * 其他协议保留可读文本，不制造「看起来能点、其实点不动」的假链接。
 */
fun buildMarkdownInline(
    text: String,
    colors: MarkdownColors,
    onLinkClick: ((String) -> Unit)? = null,
): MarkdownInlineResult {
    val renderer = InlineRenderer(text, colors, onLinkClick)
    return renderer.render()
}

/** 链接协议白名单：只允许系统浏览器与拨号/邮件这类明确无害的外部协议。 */
fun isSupportedLinkUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    if (scheme !in SupportedLinkSchemes) return false
    return url.length > scheme.length + 1
}

private val SupportedLinkSchemes = setOf("http", "https", "mailto", "tel")

/** 基础 HTML 标签白名单：只放行不会执行脚本、不会引入外部资源的排版标签。 */
private val AllowedInlineTags = setOf(
    "a", "b", "strong", "i", "em", "u", "s", "del", "strike",
    "code", "kbd", "mark", "small", "sub", "sup", "br", "span",
)

private val HtmlTagRegex = Regex("<(/)?([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^<>]*?)?)(/)?>")
private val HrefAttributeRegex = Regex("href\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
private val AngleAutolinkRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):([^\\s<>]+)$")
private val AngleEmailRegex = Regex("^([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})$")
private val BareUrlRegex = Regex("(?i)^https?://[^\\s<>\"'`]+")

private val NamedEntities = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to "\u00A0",
    "mdash" to "—",
    "ndash" to "–",
    "hellip" to "…",
    "times" to "×",
    "divide" to "÷",
    "plusmn" to "±",
    "le" to "≤",
    "ge" to "≥",
    "ne" to "≠",
    "infin" to "∞",
    "sum" to "∑",
    "int" to "∫",
    "radic" to "√",
    "pi" to "π",
    "alpha" to "α",
    "beta" to "β",
    "gamma" to "γ",
    "delta" to "δ",
    "Delta" to "Δ",
    "omega" to "ω",
    "Omega" to "Ω",
)

/** 单次行内解析的状态：builder、公式占位表与行内 HTML 栈。 */
private class InlineRenderer(
    private val input: String,
    private val colors: MarkdownColors,
    private val onLinkClick: ((String) -> Unit)?,
) {
    private val builder = AnnotatedString.Builder()
    private val mathSpans = mutableListOf<MarkdownInlineMathSpan>()

    fun render(): MarkdownInlineResult {
        parseRange(0, input.length)
        return MarkdownInlineResult(builder.toAnnotatedString(), mathSpans)
    }

    private fun parseRange(start: Int, end: Int) {
        val htmlStack = ArrayList<HtmlOpenTag>()
        var index = start

        while (index < end) {
            val character = input[index]

            // 1. 反斜杠转义
            if (character == '\\' && index + 1 < end) {
                val next = input[index + 1]
                if (next in EscapableCharacters) {
                    builder.append(next)
                    index += 2
                    continue
                }
            }

            // 2. 行内代码
            if (character == '`') {
                index = appendCodeSpan(index, end)
                continue
            }

            // 3. 行内数学公式（必须在代码之后，避免 `$` 在代码里被当公式）
            if (character == '$') {
                val nextIndex = tryInlineMath(index, end)
                if (nextIndex != null) {
                    index = nextIndex
                    continue
                }
            }

            // 4. 自动链接 / HTML 标签
            if (character == '<') {
                val nextIndex = tryAngleConstruct(index, end, htmlStack)
                if (nextIndex != null) {
                    index = nextIndex
                    continue
                }
            }

            // 5. Markdown 链接 [文本](url)
            if (character == '[') {
                val nextIndex = tryMarkdownLink(index, end)
                if (nextIndex != null) {
                    index = nextIndex
                    continue
                }
            }

            // 6. 粗体 / 斜体 / 删除线
            if (character == '*' || character == '_' || character == '~') {
                val nextIndex = tryEmphasis(index, end)
                if (nextIndex != null) {
                    index = nextIndex
                    continue
                }
            }

            // 7. HTML 实体
            if (character == '&') {
                val nextIndex = tryAppendEntity(index, end)
                if (nextIndex != null) {
                    index = nextIndex
                    continue
                }
            }

            // 8. 裸 URL 自动链接
            if ((character == 'h' || character == 'H') && isBareLinkStart(index, start)) {
                val nextIndex = tryBareUrl(index, end)
                if (nextIndex != null) {
                    index = nextIndex
                    continue
                }
            }

            builder.append(character)
            index++
        }
    }

    private fun appendCodeSpan(start: Int, end: Int): Int {
        var runEnd = start
        while (runEnd < end && input[runEnd] == '`') runEnd++
        val tickCount = runEnd - start

        var searchIndex = runEnd
        var closingStart = -1
        while (searchIndex < end) {
            if (input[searchIndex] == '`') {
                var closingEnd = searchIndex
                while (closingEnd < end && input[closingEnd] == '`') closingEnd++
                if (closingEnd - searchIndex == tickCount) {
                    closingStart = searchIndex
                    break
                }
                searchIndex = closingEnd
            } else {
                searchIndex++
            }
        }

        if (closingStart == -1) {
            builder.append(input, start, runEnd)
            return runEnd
        }

        var content = input.substring(runEnd, closingStart)
        if (content.length >= 2 && content.startsWith(" ") && content.endsWith(" ") && content.isNotBlank()) {
            content = content.substring(1, content.length - 1)
        }
        val styleStart = builder.length
        builder.append(content)
        if (builder.length > styleStart) {
            builder.addStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.92.em,
                    fontWeight = FontWeight.Medium,
                    color = colors.codeColor,
                    background = colors.codeBackground,
                ),
                styleStart,
                builder.length,
            )
        }
        return closingStart + tickCount
    }

    private fun tryInlineMath(start: Int, end: Int): Int? {
        // $$...$$ 出现在正文行内时同样按公式处理（块级由 MarkdownParser 先消费）
        if (input.startsWith("$$", start)) {
            val closing = input.indexOf("$$", start + 2)
            if (closing in (start + 2) until end) {
                val latex = input.substring(start + 2, closing).trim()
                if (latex.isNotEmpty() && !latex.contains('\n')) {
                    appendMath(latex)
                    return closing + 2
                }
            }
            return null
        }

        val next = input.getOrNull(start + 1) ?: return null
        if (next.isWhitespace() || next == '$') return null

        var index = start + 1
        while (index < end) {
            val character = input[index]
            if (character == '\\') {
                index += 2
                continue
            }
            if (character == '$') {
                val content = input.substring(start + 1, index)
                val valid = index > start + 1 &&
                    !input[index - 1].isWhitespace() &&
                    content.isNotBlank() &&
                    !content.contains('\n') &&
                    !content.contains('$')
                if (valid) {
                    appendMath(content)
                    return index + 1
                }
            }
            index++
        }
        return null
    }

    private fun appendMath(latex: String) {
        val id = "md_math_${mathSpans.size}"
        mathSpans.add(MarkdownInlineMathSpan(id, latex))
        builder.appendInlineContent(id, latex)
    }

    private fun tryAngleConstruct(start: Int, end: Int, stack: MutableList<HtmlOpenTag>): Int? {
        val closingBracket = findNextUnescaped('>', start + 1, end)
        if (closingBracket != -1) {
            val inner = input.substring(start + 1, closingBracket)
            val schemeMatch = AngleAutolinkRegex.matchEntire(inner)
            if (schemeMatch != null) {
                val candidate = inner
                if (isSupportedLinkUrl(candidate)) {
                    appendLink(candidate, candidate)
                    return closingBracket + 1
                }
            }
            val emailMatch = AngleEmailRegex.matchEntire(inner)
            if (emailMatch != null) {
                val email = emailMatch.groupValues[1]
                appendLink("mailto:$email", email)
                return closingBracket + 1
            }
        }

        val match = HtmlTagRegex.matchAt(input, start) ?: return null
        if (match.range.last >= end) return null
        val isClosing = match.groups[1] != null
        val tag = match.groupValues[2].lowercase()
        if (tag !in AllowedInlineTags) return null
        val nextIndex = match.range.last + 1

        if (tag == "br") {
            builder.append('\n')
            return nextIndex
        }

        if (isClosing) {
            if (stack.isEmpty() || stack.last().tag != tag) {
                builder.append(match.value)
                return nextIndex
            }
            val open = stack.removeAt(stack.lastIndex)
            if (open.style != null && builder.length > open.start) {
                builder.addStyle(open.style, open.start, builder.length)
            }
            if (open.href != null && builder.length > open.start) {
                builder.addLink(buildLinkAnnotation(open.href), open.start, builder.length)
            }
            return nextIndex
        }

        val style: SpanStyle?
        val href: String?
        if (tag == "a") {
            href = HrefAttributeRegex.find(match.value)?.let { attribute ->
                attribute.groups[2]?.value?.takeIf { it.isNotBlank() }
                    ?: attribute.groups[3]?.value?.takeIf { it.isNotBlank() }
            }
            style = null
        } else {
            href = null
            style = spanStyleForTag(tag)
        }

        // 即使没有样式也要入栈：否则 `</span>` 或 `</a>` 会被当成普通文本原样输出
        val supportedHref = href?.takeIf { isSupportedLinkUrl(it) }
        stack.add(HtmlOpenTag(tag = tag, start = builder.length, style = style, href = supportedHref))
        return nextIndex
    }

    private fun spanStyleForTag(tag: String): SpanStyle? = when (tag) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold, color = colors.boldColor)
        "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic, color = colors.italicColor)
        "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "code" -> SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 0.92.em,
            fontWeight = FontWeight.Medium,
            color = colors.codeColor,
            background = colors.codeBackground,
        )
        "kbd" -> SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 0.88.em,
            fontWeight = FontWeight.Medium,
            color = colors.resolvedKeyboardTextColor,
            background = colors.resolvedKeyboardBackground,
        )
        "mark" -> SpanStyle(
            color = colors.textColor,
            background = colors.resolvedMarkBackground,
        )
        "small" -> SpanStyle(fontSize = 0.85.em)
        "sub" -> SpanStyle(fontSize = 0.78.em, baselineShift = BaselineShift.Subscript)
        "sup" -> SpanStyle(fontSize = 0.78.em, baselineShift = BaselineShift.Superscript)
        else -> null
    }

    private fun tryMarkdownLink(start: Int, end: Int): Int? {
        val closingBracket = findClosingBracket(start + 1, end)
        if (closingBracket == -1 || closingBracket + 1 >= end || input[closingBracket + 1] != '(') {
            return null
        }
        val destination = parseLinkDestination(closingBracket + 2, end) ?: return null

        if (!isSupportedLinkUrl(destination.url)) {
            // 不支持协议：直接输出链接文本，避免伪链接
            parseRange(start + 1, closingBracket)
            return destination.end
        }

        val linkStart = builder.length
        parseRange(start + 1, closingBracket)
        if (builder.length == linkStart) {
            builder.append(destination.url)
        }
        builder.addStyle(
            SpanStyle(color = colors.linkColor, textDecoration = TextDecoration.Underline),
            linkStart,
            builder.length,
        )
        builder.addLink(buildLinkAnnotation(destination.url), linkStart, builder.length)
        return destination.end
    }

    private fun tryEmphasis(start: Int, end: Int): Int? {
        val character = input[start]
        var runLength = 0
        while (start + runLength < end && input[start + runLength] == character) runLength++

        if (character == '~' && runLength < 2) return null
        val markerLength = if (character == '~' || runLength >= 2) 2 else 1
        val contentStart = start + markerLength
        if (contentStart >= end || input[contentStart].isWhitespace()) return null
        if (character == '_' && start > 0 && input[start - 1].isLetterOrDigit()) return null

        val closingStart = findClosingMarker(character, contentStart, end, markerLength)
        if (closingStart <= contentStart) return null
        if (input[closingStart - 1].isWhitespace()) return null
        if (character == '_' && closingStart + markerLength < end && input[closingStart + markerLength].isLetterOrDigit()) {
            return null
        }

        val styleStart = builder.length
        parseRange(contentStart, closingStart)
        if (builder.length == styleStart) return null

        val style = when {
            character == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            markerLength == 2 -> SpanStyle(fontWeight = FontWeight.Bold, color = colors.boldColor)
            else -> SpanStyle(fontStyle = FontStyle.Italic, color = colors.italicColor)
        }
        builder.addStyle(style, styleStart, builder.length)
        return closingStart + markerLength
    }

    private fun tryAppendEntity(start: Int, end: Int): Int? {
        val semicolon = input.indexOf(';', start + 1)
        if (semicolon == -1 || semicolon >= end || semicolon - start > MaxEntityLength) return null
        val body = input.substring(start + 1, semicolon)
        if (body.startsWith("#")) {
            val codePoint = if (body.startsWith("#x", ignoreCase = true)) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            }
            if (codePoint != null && codePoint in 1..0x10FFFF) {
                builder.append(String(Character.toChars(codePoint)))
                return semicolon + 1
            }
            return null
        }
        val decoded = NamedEntities[body] ?: return null
        builder.append(decoded)
        return semicolon + 1
    }

    private fun tryBareUrl(start: Int, rangeStart: Int): Int? {
        val match = BareUrlRegex.find(input.substring(start)) ?: return null
        if (match.range.first != 0) return null
        var candidate = match.value
        // 去掉句末标点，保留成对括号里的内容
        while (candidate.isNotEmpty() && candidate.last() in TrailingUrlPunctuation) {
            candidate = candidate.dropLast(1)
        }
        while (candidate.endsWith(")") && candidate.count { it == '(' } < candidate.count { it == ')' }) {
            candidate = candidate.dropLast(1)
        }
        if (!isSupportedLinkUrl(candidate)) return null
        appendLink(candidate, candidate)
        return start + candidate.length
    }

    private fun isBareLinkStart(index: Int, rangeStart: Int): Boolean {
        if (!input.regionMatches(index, "http://", 0, 7, ignoreCase = true) &&
            !input.regionMatches(index, "https://", 0, 8, ignoreCase = true)
        ) {
            return false
        }
        if (index == rangeStart) return true
        val previous = input[index - 1]
        return !previous.isLetterOrDigit() && previous !in BareLinkAttachedCharacters
    }

    private fun appendLink(url: String, displayText: String) {
        val linkStart = builder.length
        builder.append(displayText)
        builder.addStyle(
            SpanStyle(color = colors.linkColor, textDecoration = TextDecoration.Underline),
            linkStart,
            builder.length,
        )
        builder.addLink(buildLinkAnnotation(url), linkStart, builder.length)
    }

    private fun buildLinkAnnotation(url: String): LinkAnnotation.Clickable = LinkAnnotation.Clickable(
        tag = url,
        styles = TextLinkStyles(
            style = SpanStyle(color = colors.linkColor, textDecoration = TextDecoration.Underline),
            pressedStyle = SpanStyle(color = colors.linkColor.copy(alpha = 0.6f)),
        ),
        linkInteractionListener = LinkInteractionListener { onLinkClick?.invoke(url) },
    )

    private fun parseLinkDestination(start: Int, end: Int): LinkDestination? {
        var index = start
        while (index < end && input[index] == ' ') index++

        val url: String
        if (index < end && input[index] == '<') {
            val closing = findNextUnescaped('>', index + 1, end)
            if (closing == -1) return null
            url = input.substring(index + 1, closing).trim()
            index = closing + 1
        } else {
            val urlStart = index
            var depth = 0
            while (index < end) {
                val character = input[index]
                if (character == '\\' && index + 1 < end) {
                    index += 2
                    continue
                }
                if (character == '(') {
                    depth++
                } else if (character == ')') {
                    if (depth == 0) break
                    depth--
                } else if (character.isWhitespace()) {
                    break
                }
                index++
            }
            url = input.substring(urlStart, index).trim()
        }

        while (index < end && input[index] == ' ') index++
        // 可选标题："title" 或 'title'
        if (index < end && (input[index] == '"' || input[index] == '\'')) {
            val quote = input[index]
            val closingQuote = findNextUnescaped(quote, index + 1, end)
            if (closingQuote == -1) return null
            index = closingQuote + 1
            while (index < end && input[index] == ' ') index++
        }

        if (index >= end || input[index] != ')') return null
        if (url.isEmpty()) return null
        return LinkDestination(url = url, end = index + 1)
    }

    private fun findClosingBracket(from: Int, end: Int): Int {
        var depth = 1
        var index = from
        while (index < end) {
            when (input[index]) {
                '\\' -> {
                    index += 2
                    continue
                }
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun findNextUnescaped(target: Char, from: Int, end: Int): Int {
        var index = from
        while (index < end) {
            if (input[index] == '\\') {
                index += 2
                continue
            }
            if (input[index] == target) return index
            index++
        }
        return -1
    }

    /**
     * 查找与开启标记配对的收尾标记。
     * 返回收尾 run 中「最后一个标记」的起点：这样 `***x***` 这类多重标记
     * 会把多余的星号留给内层递归解析，避免出现悬挂的 `*`。
     */
    private fun findClosingMarker(character: Char, from: Int, end: Int, markerLength: Int): Int {
        var index = from
        while (index < end) {
            if (input[index] == '\\') {
                index += 2
                continue
            }
            if (input[index] == character) {
                var runEnd = index
                while (runEnd < end && input[runEnd] == character) runEnd++
                if (runEnd - index >= markerLength && !input[index - 1].isWhitespace()) {
                    return runEnd - markerLength
                }
                index = runEnd
                continue
            }
            index++
        }
        return -1
    }

    private data class LinkDestination(val url: String, val end: Int)

    private data class HtmlOpenTag(
        val tag: String,
        val start: Int,
        val style: SpanStyle?,
        val href: String?,
    )
}

private const val MaxEntityLength = 12

private const val EscapableCharacters = "\\`*_{}[]()#+-.!~$<>&|\"'"

private val TrailingUrlPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', '。', '，', '、', '；', '：', '！', '？', '）', '》', '”', '’')

private const val BareLinkAttachedCharacters = "._/-@:"
