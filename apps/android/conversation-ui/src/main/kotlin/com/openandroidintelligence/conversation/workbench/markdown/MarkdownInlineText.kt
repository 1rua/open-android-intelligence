package com.openandroidintelligence.conversation.workbench

import android.widget.Toast
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
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
    // 正文以当前叶节点为准；来源图只能定位相同文字，不能把补齐的空单元格替换成整块原文。
    // 无匹配来源时保留空映射，避免从消息开头伪造光标、渐入和触觉范围。
    val source = LocalMarkdownLeafSource.current?.takeIf { it.text == text }
        ?: LocalMarkdownBlockSource.current?.takeIf { it.text == text }
    val document = LocalStreamingMarkdownDocument.current
    val active = isStreaming && LocalStreamingTextPresentation.current?.active == true
    val laidOutSource = source?.let { streamingInlineSource(text, it, document, active) }
    val laidOutText = laidOutSource?.text ?: text

    // 默认行为：交给系统处理（浏览器 / 邮件 / 电话），失败时给用户一个明确提示而不是静默无响应。
    val linkHandler: (String) -> Unit = remember(uriHandler, onLinkClick, context) {
        onLinkClick ?: { url: String ->
            runCatching { uriHandler.openUri(url) }.onFailure {
                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
            Unit
        }
    }

    val result = remember(laidOutText, colors, linkHandler) {
        buildMarkdownInline(laidOutText, colors, linkHandler)
    }

    val effectiveFontSize = when {
        fontSize.isSpecified -> fontSize
        style.fontSize.isSpecified -> style.fontSize
        else -> 16.sp
    }
    val mathFontPx = with(density) { effectiveFontSize.roundToPx() }

    // 同一段文本内相同 LaTeX 复用同一测量结果；WebView 异步上报后触发重新排版。
    val measuredMathSizes = remember(text) { mutableStateMapOf<String, IntSize>() }

    // 光标由正文容器的独立 Overlay 绘制，不把占位字符加入 AnnotatedString，避免改变换行、选择和复制。
    val annotated = result.annotated

    val inlineContent = buildMap {
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
                    val leaf = laidOutSource
                    val mathSource = leaf?.let { original ->
                        val start = result.sourceOffsets.getOrNull(span.start) ?: 0
                        original.slice(start.coerceAtMost(original.text.length),
                            (start + span.latex.length).coerceAtMost(original.text.length))
                    }
                    MarkdownMathInline(
                        latex = span.latex,
                        fontSize = effectiveFontSize,
                        modifier = streamingAtomicModifier(mathSource, Modifier),
                        onMeasured = { size -> measuredMathSizes[span.latex] = size },
                    )
                },
            )
        }
    }

    val sourceOffsets = remember(result, laidOutSource) { sourceOffsetsFor(result, laidOutSource) }
    val (decoratedModifier, onTextLayout) = streamingTextModifier(
        text = annotated.text,
        sourceOffsets = sourceOffsets,
        modifier = modifier,
    )

    SelectionContainer {
        Text(
            text = annotated,
            inlineContent = inlineContent,
            modifier = decoratedModifier,
            onTextLayout = onTextLayout,
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

