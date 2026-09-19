package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MarkdownColors(
    val textColor: Color,
    val boldColor: Color,
    val italicColor: Color,
    val codeColor: Color,
    val codeBackground: Color,
    val linkColor: Color,
)

@Composable
fun defaultMarkdownColors(): MarkdownColors {
    return MarkdownColors(
        textColor = MaterialTheme.colorScheme.onSurface,
        boldColor = MaterialTheme.colorScheme.onSurface,
        italicColor = MaterialTheme.colorScheme.onSurfaceVariant,
        codeColor = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
        linkColor = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 将 Markdown 行内文本解析为 Compose 的 AnnotatedString，严格支持粗体、斜体、行内代码、链接与删除线。
 * 在流式输出（isStreaming == true）时支持在末尾自适应挂载光标占位符。
 */
fun buildMarkdownAnnotatedString(
    text: String,
    colors: MarkdownColors,
    isStreaming: Boolean = false,
    cursorInlineId: String = "streaming_cursor",
): AnnotatedString {
    return buildAnnotatedString {
        parseInline(text, this, colors)
        if (isStreaming) {
            append(" ")
            appendInlineContent(cursorInlineId, "[cursor]")
        }
    }
}

fun streamingCursorInlineContent(
    isStreaming: Boolean,
    cursorInlineId: String = "streaming_cursor",
    cursorHeight: TextUnit = 16.sp,
): Map<String, InlineTextContent> {
    if (!isStreaming) return emptyMap()
    return mapOf(
        cursorInlineId to InlineTextContent(
            Placeholder(
                width = 4.sp,
                height = cursorHeight,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            StreamingCursor()
        },
    )
}

internal fun parseInline(
    input: String,
    builder: AnnotatedString.Builder,
    colors: MarkdownColors,
) {
    var i = 0
    val len = input.length

    while (i < len) {
        val c = input[i]

        // 1. 转义字符
        if (c == '\\' && i + 1 < len) {
            val next = input[i + 1]
            if (next in "\\`*_{}[]()#+-.!~") {
                builder.append(next)
                i += 2
                continue
            }
        }

        // 2. 行内代码 `code`
        if (c == '`') {
            val closeIndex = findClosingDelimiter(input, i + 1, "`")
            if (closeIndex != -1) {
                val codeContent = input.substring(i + 1, closeIndex)
                val start = builder.length
                builder.append(codeContent)
                builder.addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.codeColor,
                        background = colors.codeBackground,
                    ),
                    start,
                    builder.length,
                )
                i = closeIndex + 1
                continue
            }
        }

        // 3. 超链接 [title](url)
        if (c == '[') {
            val closeBracket = findClosingDelimiter(input, i + 1, "]")
            if (closeBracket != -1 && closeBracket + 1 < len && input[closeBracket + 1] == '(') {
                val closeParen = findClosingDelimiter(input, closeBracket + 2, ")")
                if (closeParen != -1) {
                    val linkTitle = input.substring(i + 1, closeBracket)
                    val linkUrl = input.substring(closeBracket + 2, closeParen).trim()
                    val start = builder.length
                    parseInline(linkTitle, builder, colors)
                    builder.addStyle(
                        SpanStyle(
                            color = colors.linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                        start,
                        builder.length,
                    )
                    builder.addStringAnnotation("URL", linkUrl, start, builder.length)
                    i = closeParen + 1
                    continue
                }
            }
        }

        // 4. 粗斜体 ***text*** 或 ___text___
        if (input.startsWith("***", i) || input.startsWith("___", i)) {
            val delim = input.substring(i, i + 3)
            val closeIndex = findClosingDelimiter(input, i + 3, delim)
            if (closeIndex != -1) {
                val inner = input.substring(i + 3, closeIndex)
                val start = builder.length
                parseInline(inner, builder, colors)
                builder.addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        color = colors.boldColor,
                    ),
                    start,
                    builder.length,
                )
                i = closeIndex + 3
                continue
            }
        }

        // 5. 粗体 **text** 或 __text__
        if (input.startsWith("**", i) || input.startsWith("__", i)) {
            val delim = input.substring(i, i + 2)
            val closeIndex = findClosingDelimiter(input, i + 2, delim)
            if (closeIndex != -1) {
                val inner = input.substring(i + 2, closeIndex)
                val start = builder.length
                parseInline(inner, builder, colors)
                builder.addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = colors.boldColor,
                    ),
                    start,
                    builder.length,
                )
                i = closeIndex + 2
                continue
            }
        }

        // 6. 删除线 ~~text~~
        if (input.startsWith("~~", i)) {
            val closeIndex = findClosingDelimiter(input, i + 2, "~~")
            if (closeIndex != -1) {
                val inner = input.substring(i + 2, closeIndex)
                val start = builder.length
                parseInline(inner, builder, colors)
                builder.addStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                    start,
                    builder.length,
                )
                i = closeIndex + 2
                continue
            }
        }

        // 7. 斜体 *text* 或 _text_
        if (c == '*' || c == '_') {
            val delim = c.toString()
            val isUnderscoreInWord = c == '_' && i > 0 && input[i - 1].isLetterOrDigit()
            if (!isUnderscoreInWord) {
                val closeIndex = findClosingDelimiter(input, i + 1, delim)
                if (closeIndex != -1) {
                    val inner = input.substring(i + 1, closeIndex)
                    val start = builder.length
                    parseInline(inner, builder, colors)
                    builder.addStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Italic,
                            color = colors.italicColor,
                        ),
                        start,
                        builder.length,
                    )
                    i = closeIndex + 1
                    continue
                }
            }
        }

        // 普通字符
        builder.append(c)
        i++
    }
}

private fun findClosingDelimiter(input: String, startIndex: Int, delimiter: String): Int {
    var i = startIndex
    while (i <= input.length - delimiter.length) {
        if (input[i] == '\\') {
            i += 2
            continue
        }
        if (input.startsWith(delimiter, i)) {
            return i
        }
        i++
    }
    return -1
}

