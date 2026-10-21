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
    if (url.any { it.isWhitespace() }) return false
    val separator = url.indexOf(':')
    if (separator <= 0) return false
    val scheme = url.substring(0, separator).lowercase()
    val rest = url.substring(separator + 1)
    return when (scheme) {
        "http", "https" -> rest.startsWith("//") && rest.substring(2).substringBefore('/')
            .substringBefore('?').substringBefore('#').let { host -> host.isNotBlank() && host.any { it != '.' } }
        "mailto" -> rest.isNotBlank() && rest.contains('@')
        "tel" -> rest.any { it.isDigit() }
        else -> false
    }
}

/** 基础 HTML 标签白名单：只放行不会执行脚本、不会引入外部资源的排版标签。 */
private val AllowedInlineTags = setOf(
    "a", "b", "strong", "i", "em", "u", "s", "del", "strike",
    "code", "kbd", "mark", "small", "sub", "sup", "br", "span",
)

private val HrefAttributeRegex = Regex("(?:^|\\s)href\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
private val AngleAutolinkRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):([^\\s<>]+)$")
private val AngleEmailRegex = Regex("^([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})$")

/** 单个 HTML 标签的最大字符数；超过即按普通文本处理，避免畸形标签拖慢行内解析。 */
private const val MaxHtmlTagLength = 256

/** 尖括号自动链接 / 裸链接的最大长度；超出后不再尝试识别。 */
private const val MaxAutolinkLength = 2048

/** 裸链接遇到这些字符立即结束，避免把强调标记、引号等吞进 URL。 */
private const val BareUrlStopCharacters = "\t\n\r <>\"'`*~"

private val NamedEntities = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "copy" to "©",
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
                // `$$` 不构成合法公式时整体按字面量消费，避免第二个 `$` 被当成单美元开头
                if (input.startsWith("$$", index)) {
                    builder.append("$$")
                    index += 2
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
            val searchLimit = minOf(end, start + MaxAutolinkLength)
            val closing = input.indexOf("$$", start + 2)
            if (closing in (start + 2) until searchLimit) {
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
        // 先按有界扫描尝试 HTML 标签：畸形输入（如 "<a " + 大量空格）不会触发正则回溯，
        // 也不会为了找不存在的 ">" 扫完整条消息。
        val token = parseHtmlTag(start, end)

        val closingBracket = findNextUnescaped('>', start + 1, minOf(end, start + MaxAutolinkLength))
        if (closingBracket != -1) {
            val inner = input.substring(start + 1, closingBracket)
            val schemeMatch = AngleAutolinkRegex.matchEntire(inner)
            if (schemeMatch != null) {
                if (isSupportedLinkUrl(inner)) {
                    appendLink(inner, inner)
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

        if (token == null) return null
        val tag = token.tag
        if (tag == "br") {
            builder.append('\n')
            return token.nextIndex
        }

        if (token.isClosing) {
            if (stack.isEmpty() || stack.last().tag != tag) {
                builder.append(input, start, token.nextIndex)
                return token.nextIndex
            }
            val open = stack.removeAt(stack.lastIndex)
            if (open.style != null && builder.length > open.start) {
                builder.addStyle(open.style, open.start, builder.length)
            }
            if (open.href != null && builder.length > open.start) {
                builder.addLink(buildLinkAnnotation(open.href), open.start, builder.length)
            }
            return token.nextIndex
        }

        val style: SpanStyle?
        val href: String?
        if (tag == "a") {
            href = HrefAttributeRegex.find(token.attributes)?.let { attribute ->
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
        return token.nextIndex
    }

    /**
     * 有界、线性的 HTML 开/闭标签扫描：只识别白名单标签名，属性部分最多看
     * [MaxHtmlTagLength] 个字符并正确处理引号内的 `>`。解析失败返回 null，由调用方按文本处理。
     */
    private fun parseHtmlTag(start: Int, end: Int): HtmlTagToken? {
        if (start >= end || input[start] != '<') return null
        var index = start + 1
        val isClosing = index < end && input[index] == '/'
        if (isClosing) index++
        val nameStart = index
        while (index < end && input[index].isLetterOrDigit()) index++
        if (index == nameStart) return null
        val tag = input.substring(nameStart, index).lowercase()
        if (tag !in AllowedInlineTags) return null
        if (index < end) {
            val next = input[index]
            if (next != '>' && next != '/' && !next.isWhitespace()) return null
        }

        val attributeStart = index
        var quote: Char? = null
        val limit = minOf(end, start + MaxHtmlTagLength)
        var cursor = index
        while (cursor < limit) {
            val character = input[cursor]
            if (quote != null) {
                if (character == quote) quote = null
            } else {
                when (character) {
                    '"', '\'' -> quote = character
                    '<' -> return null
                    '>' -> return HtmlTagToken(
                        tag = tag,
                        isClosing = isClosing,
                        attributes = input.substring(attributeStart, cursor),
                        nextIndex = cursor + 1,
                    )
                }
            }
            cursor++
        }
        return null
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
        var semicolon = start + 1
        val entityLimit = minOf(end, start + MaxEntityLength + 1)
        while (semicolon < entityLimit && input[semicolon] != ';') semicolon++
        if (semicolon >= entityLimit || input.getOrNull(semicolon) != ';') return null
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

    private fun tryBareUrl(start: Int, end: Int): Int? {
        val limit = minOf(end, start + MaxAutolinkLength)
        var cursor = start
        while (cursor < limit && input[cursor] !in BareUrlStopCharacters) cursor++
        var candidate = input.substring(start, cursor)
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

    /** 若 [start] 是反引号 run，返回与之配对的代码段之后的下标；未闭合返回 null。 */
    private fun skipCodeSpan(start: Int, end: Int): Int? {
        var runEnd = start
        while (runEnd < end && input[runEnd] == '`') runEnd++
        val tickCount = runEnd - start
        var index = runEnd
        while (index < end) {
            if (input[index] == '`') {
                var closingEnd = index
                while (closingEnd < end && input[closingEnd] == '`') closingEnd++
                if (closingEnd - index == tickCount) return closingEnd
                index = closingEnd
            } else {
                index++
            }
        }
        return null
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
            // 行内代码是不可穿透的原子：`*a `b*c` d*` 不能在代码段内部收尾
            if (input[index] == '`') {
                val afterCode = skipCodeSpan(index, end)
                if (afterCode != null) {
                    index = afterCode
                    continue
                }
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

    private data class HtmlTagToken(
        val tag: String,
        val isClosing: Boolean,
        val attributes: String,
        val nextIndex: Int,
    )

    private data class HtmlOpenTag(
        val tag: String,
        val start: Int,
        val style: SpanStyle?,
        val href: String?,
    )
}

private const val MaxEntityLength = 12

private const val EscapableCharacters = "\\`*_{}[]()#+-.!~$<>&|\"'"

private val TrailingUrlPunctuation = charArrayOf(
    '.', ',', ';', ':', '!', '?', '。', '，', '、', '；', '：', '！', '？',
    '）', '》', '】', '」', '』', '”', '’',
)

private const val BareLinkAttachedCharacters = "._/-@:"
