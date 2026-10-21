package com.openandroidintelligence.conversation.workbench

import android.widget.Toast
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.isSpecified

/** 流式输出光标在 AnnotatedString 中的固定占位 id。 */
internal const val StreamingCursorInlineId = "streaming_cursor"

/**
 * 行内 Markdown 文本：AnnotatedString 负责样式、链接与公式占位，
 * Compose 侧只负责把行内公式占位渲染成 [MarkdownMathInline]，并补上流式光标。
 *
 * 链接点击走 Compose 1.7 的 `LinkAnnotation.Clickable`：点击后交给系统默认浏览器 /
 * 拨号 / 邮件应用打开，不再需要手工命中测试。
 */
@Composable
fun MarkdownInlineText(
    text: String,
    colors: MarkdownColors,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // 默认行为：交给系统处理（浏览器 / 邮件 / 电话），失败时给用户一个明确提示而不是静默无响应。
    val linkHandler: (String) -> Unit = remember(uriHandler, onLinkClick, context) {
        onLinkClick ?: { url: String ->
            runCatching { uriHandler.openUri(url) }.onFailure {
                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
            Unit
        }
    }

    val result = remember(text, colors, linkHandler) {
        buildMarkdownInline(text, colors, linkHandler)
    }

    val effectiveFontSize = when {
        fontSize.isSpecified -> fontSize
        style.fontSize.isSpecified -> style.fontSize
        else -> 16.sp
    }
    val mathFontPx = with(density) { effectiveFontSize.roundToPx() }

    // 同一段文本内相同 LaTeX 复用同一测量结果；WebView 异步上报后触发重新排版。
    val measuredMathSizes = remember(text) { mutableStateMapOf<String, IntSize>() }

    val annotated = remember(result, isStreaming) {
        if (!isStreaming) {
            result.annotated
        } else {
            AnnotatedString.Builder(result.annotated).apply {
                append(" ")
                appendInlineContent(StreamingCursorInlineId, "[cursor]")
            }.toAnnotatedString()
        }
    }

    val cursorHeight = (effectiveFontSize.value * 1.05f).sp
    val cursorPlaceholders = streamingCursorInlineContent(
        isStreaming = isStreaming,
        cursorHeight = cursorHeight,
    )

    val inlineContent = buildMap {
        putAll(cursorPlaceholders)
        result.inlineMath.forEach { span ->
            val key = MarkdownMathKey(
                latex = span.latex,
                displayMode = false,
                fontSizePx = mathFontPx,
            )
            val measured = measuredMathSizes[span.latex]
                ?: MarkdownMathSizeCache.size(key)
                ?: MarkdownMathSizeCache.estimateInlineSize(span.latex, mathFontPx)
            put(
                span.id,
                InlineTextContent(
                    Placeholder(
                        width = with(density) { measured.width.toSp() },
                        height = with(density) { measured.height.toSp() },
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    MarkdownMathInline(
                        latex = span.latex,
                        fontSize = effectiveFontSize,
                        onMeasured = { size -> measuredMathSizes[span.latex] = size },
                    )
                },
            )
        }
    }

    SelectionContainer {
        Text(
            text = annotated,
            inlineContent = inlineContent,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * 流式光标的 InlineTextContent。宽度按字高比例取 0.14（约等于 14sp 字号下的 2dp），
 * 高度跟随当前字号。非流式状态返回空表，避免无谓的占位与重排。
 *
 * 刻意保持非 @Composable：思考卡片在 remember 块里构造它，光标宽度又与密度无关
 * （直接按 sp 比例给出），这样调用方不需要额外提升状态。
 */
internal fun streamingCursorInlineContent(
    isStreaming: Boolean,
    cursorInlineId: String = StreamingCursorInlineId,
    cursorHeight: TextUnit = 14.sp,
): Map<String, InlineTextContent> {
    if (!isStreaming) return emptyMap()
    val cursorWidth = cursorHeight * 0.14f
    return mapOf(
        cursorInlineId to InlineTextContent(
            Placeholder(
                width = if (cursorWidth.value > 0f) cursorWidth else 2.sp,
                height = cursorHeight,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            StreamingCursor()
        },
    )
}
