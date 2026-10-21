package com.openandroidintelligence.conversation.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * KaTeX 数学公式渲染。
 *
 * 设计边界：
 * - 公式由 **本地** KaTeX（MIT，0.16.22，见 `assets/katex/README.md`）在 WebView 里排版，
 *   只在 `file:///android_asset/katex/` 下取资源，禁止任何网络请求；
 * - 颜色与字号全部来自调用方（[MaterialTheme.colorScheme] 的 `onSurface` / `error`），
 *   组件内部不写死颜色，背景始终透明，深色模式不需要单独分支；
 * - WebView 只负责「画」，尺寸由 `evaluateJavascript` 读取后回报 Compose，
 *   不注册 `JavascriptInterface`，不暴露任何原生对象给网页；
 * - 失败必须**可见且诚实**：WebView 创建失败、KaTeX 缺失或 4 秒内测不到尺寸时，
 *   直接以等宽字体显示原始 LaTeX，并带上 `contentDescription = "公式渲染不可用"`，
 *   绝不留白，也不假装渲染成功。
 *
 * 单位约定（重要）：
 * - WebView 必须开启 `useWideViewPort = true`，让页面里的 `initial-scale=1` 生效：
 *   此时 1 CSS px = 1 dp，与 Compose 的 dp 同尺度（关闭时 WebView 会把布局视口当物理像素
 *   再整体缩到 1/density，实测 `visualViewport.scale≈0.381`，公式会比正文小一半以上）。
 * - [buildKaTeXHtml] 的 `fontSizePx` 是 **WebView CSS 像素**，所以调用方传 `fontPx / density`。
 * - [MarkdownMathSizeCache] 与 [MarkdownMathInline] 的 `onMeasured` 使用 **Compose 物理像素**
 *   （= CSS px × density），这样调用方可以直接 `with(LocalDensity.current) { px.toDp() }`
 *   填回 placeholder，不会在 2x/3x 屏上缩成一半。
 */

/** 数学公式渲染缓存键；[fontSizePx] 为 Compose 物理像素。 */
data class MarkdownMathKey(
    val latex: String,
    val displayMode: Boolean,
    val fontSizePx: Int,
)

/**
 * 公式尺寸缓存（LRU，最多 256 条）。
 *
 * 缓存只保存测量结果，不保存 WebView：列表滚出屏幕后 WebView 会被销毁，
 * 再滚回来时先用缓存尺寸占位，避免「先塌成 0 高再跳一下」的抖动。
 * 尺寸统一为 Compose 物理像素，非正尺寸（0 或负数）一律拒绝写入。
 */
object MarkdownMathSizeCache {

    private const val MAX_ENTRIES = 256

    // accessOrder = true：读取也会刷新顺序，淘汰的才是真正最久未使用的条目
    private val entries = LinkedHashMap<MarkdownMathKey, IntSize>(16, 0.75f, true)

    /** 读取缓存尺寸；未测量过时返回 null。 */
    @Synchronized
    fun size(key: MarkdownMathKey): IntSize? = entries[key]

    /** 记录一次测量结果；非正尺寸直接忽略，防止脏数据把占位压成 0。 */
    @Synchronized
    fun record(key: MarkdownMathKey, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        entries[key] = size
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: return
            entries.remove(oldest)
        }
    }

    /** 清空缓存；用于测试隔离与显式的内存回收。 */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    /**
     * 行内公式的首帧尺寸估算，仅供 WebView 测出真实尺寸前的 placeholder 使用。
     *
     * 纯经验公式：KaTeX 里拉丁字母与运算符的平均字宽约 0.62em；行高按 1.5em 预留——
     * 实测行内公式（含分式/根号/上下标）的 CSS 高度约 1.39em，取 1.5em 既覆盖常见情况，
     * 又不会在首帧把 26sp 的行盒撑高再回缩（1.9em 会先撑到 30sp 产生一次可见跳动）。
     * 它不追求像素级准确，真实尺寸一定由 WebView 测量覆盖；但必须保证为正数，避免占位塌陷。
     *
     * @param fontSizePx Compose 物理像素字号，与 [MarkdownMathKey.fontSizePx] 同单位。
     */
    fun estimateInlineSize(latex: String, fontSizePx: Int): IntSize {
        val safeFontPx = fontSizePx.coerceIn(1, 512)
        val glyphCount = latex.count { !it.isWhitespace() }
        val estimatedWidth = ceil(safeFontPx * (glyphCount * 0.62f + 0.4f)).toInt().coerceAtLeast(1)
        val estimatedHeight = ceil(safeFontPx * 1.5f).toInt().coerceAtLeast(1)
        return IntSize(estimatedWidth, estimatedHeight)
    }
}

/** 兜底态的无障碍描述：必须和测试、产品文案保持一致。 */
internal const val MARKDOWN_MATH_UNAVAILABLE_DESCRIPTION: String = "公式渲染不可用"

internal const val MARKDOWN_MATH_BLOCK_TAG: String = "markdown_math_block"
internal const val MARKDOWN_MATH_INLINE_TAG: String = "markdown_math_inline"
internal const val MARKDOWN_MATH_FALLBACK_TAG: String = "markdown_math_fallback"

/** 块级公式在测量完成前的占位高度。 */
private val MARKDOWN_MATH_MIN_HEIGHT = Dimensions.MathPlaceholderHeight

/** 超过该时限仍未拿到尺寸就认为渲染不可用，切到等宽字体兜底。 */
private const val MATH_RENDER_TIMEOUT_MS = 4_000L

/**
 * 页面加载完成后的测量时点（毫秒，相对 `onPageFinished`）。
 *
 * 字体是异步加载的：首帧测到的尺寸可能偏小，等 `document.fonts.ready` 之后要再测一次，
 * 因此安排多次轮询，取「最后一次非零的自然尺寸 + 历史最大的文档滚动尺寸」。
 */
private val MATH_MEASURE_DELAYS_MS = longArrayOf(0L, 80L, 180L, 320L, 600L, 1_000L, 1_600L, 2_400L, 3_200L, 3_800L)

private const val MARKDOWN_MATH_ASSET_BASE_PATH = "file:///android_asset/katex/"

/** 读取 KaTeX 页面内部测量状态的最小脚本；页面未就绪时返回空串。 */
private const val MATH_MEASURE_SCRIPT = "window.__oaiMathMeasure ? window.__oaiMathMeasure() : ''"

private val DEFAULT_MATH_FONT_SIZE = 16.sp

/**
 * 构建 KaTeX 离线渲染页面（纯函数，可单测）。
 *
 * 页面通过 [WebView.loadDataWithBaseURL] 以 `file:///android_asset/katex/` 为 baseURL 加载，
 * 所以 `katex.min.css` / `katex.min.js` 一律写相对路径，绝不出现绝对 file 路径或 http(s) 地址。
 * LaTeX 源码以 JS 字符串字面量注入，`<`、`>`、`&`、引号、反斜杠、换行都会被转义，
 * 因此 `</script>`、`<!--` 之类的注入无法截断脚本标签。
 *
 * @param latex 公式源码（不含 `$` 定界符）。
 * @param displayMode 行间公式（true）或行内公式（false）。
 * @param fontSizePx WebView CSS 像素字号。
 * @param textColorArgb 公式前景色（ARGB，来自 `MaterialTheme.colorScheme.onSurface`）。
 * @param errorColorArgb KaTeX 语法错误高亮色（ARGB，来自 `MaterialTheme.colorScheme.error`）。
 */
fun buildKaTeXHtml(
    latex: String,
    displayMode: Boolean,
    fontSizePx: Float,
    textColorArgb: Int,
    errorColorArgb: Int,
): String {
    val fontSize = formatCssPx(fontSizePx)
    val textColor = argbToCssColor(textColorArgb)
    val errorColor = argbToCssColor(errorColorArgb)
    val latexLiteral = escapeJsStringLiteral(latex)
    val displayModeLiteral = if (displayMode) "true" else "false"
    // 行间公式：用 `width:max-content` + `min-width:100%` 同时满足三个目标 ——
    // 公式比视口窄时盒子撑满一行并居中；比视口宽时盒子等于内容宽度，只向右溢出，
    // 横向可滚动且不会被 viewport 宽度截断（否则测量会把「视口宽」误当成「内容宽」）。
    val rootStyle = if (displayMode) {
        "#math{display:inline-block;width:max-content;min-width:100%;text-align:center;}"
    } else {
        "#math{display:inline-block;width:max-content;text-align:left;}"
    }

    return buildString(latex.length + 4096) {
        append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">")
        append("<link rel=\"stylesheet\" href=\"katex.min.css\">")
        append("<style>")
        // 背景透明是硬要求：深色/浅色主题都由外部容器决定颜色。
        append("html,body{margin:0;padding:0;background:transparent;line-height:0;}")
        append("body{-webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent;}")
        append("#math{color:").append(textColor).append(";font-size:").append(fontSize)
        append(";line-height:1.2;white-space:nowrap;}")
        append(rootStyle)
        append("#math .katex{color:").append(textColor).append(";font-size:").append(fontSize).append(";}")
        append("#math .katex-display{margin:0;}")
        append("#math .katex-html{white-space:nowrap;}")
        append("#math .katex-error{font-family:monospace;white-space:pre-wrap;color:").append(errorColor).append(";}")
        append("#fallback{display:none;font-family:monospace;white-space:pre-wrap;line-height:1.4;color:")
        append(errorColor).append(";font-size:").append(fontSize).append(";}")
        append("::-webkit-scrollbar{width:0;height:0;display:none;}")
        append("</style></head><body>")
        append("<div id=\"math\"></div><div id=\"fallback\"></div>")
        append("<script src=\"katex.min.js\"></script>")
        append("<script>(function(){")
        append("var state={nw:0,nh:0,sw:0,sh:0,failed:false};")
        append("window.__oaiMath=state;")
        append("var target=document.getElementById('math');")
        append("var fallback=document.getElementById('fallback');")
        append("function fail(){")
        append("state.failed=true;")
        append("fallback.textContent=").append(latexLiteral).append(";")
        append("fallback.style.display='block';")
        append("target.style.display='none';")
        append("}")
        append("function measure(){")
        append("var doc=document.documentElement;")
        append("var body=document.body;")
        append("var rect=target.getBoundingClientRect();")
        append("var nw=Math.ceil(rect.width);")
        append("var nh=Math.ceil(rect.height);")
        append("var sw=Math.max(doc?doc.scrollWidth:0,body?body.scrollWidth:0,target.scrollWidth);")
        append("var sh=Math.max(doc?doc.scrollHeight:0,body?body.scrollHeight:0,target.scrollHeight);")
        append("if(nw>0){state.nw=nw;}")
        append("if(nh>0){state.nh=nh;}")
        append("if(sw>state.sw){state.sw=sw;}")
        append("if(sh>state.sh){state.sh=sh;}")
        append("}")
        append("window.__oaiMathMeasure=function(){")
        append("measure();")
        append("return state.nw+','+state.nh+','+state.sw+','+state.sh+','+(state.failed?1:0);")
        append("};")
        append("try{")
        append("if(typeof katex==='undefined'){fail();}else{")
        // throwOnError:false 时 KaTeX 把无法解析的源码渲染成带 errorColor 的行内错误，
        // 而不是让整页空白；颜色/字号来自上面的 CSS，KaTeX 的 HTML 输出会自然继承。
        append("katex.render(").append(latexLiteral).append(",target,{displayMode:").append(displayModeLiteral)
        append(",throwOnError:false,errorColor:\"").append(errorColor).append("\",strict:false,trust:false});")
        append("}")
        append("}catch(e){fail();}")
        append("measure();")
        append("if(document.fonts&&document.fonts.ready){")
        append("document.fonts.ready.then(measure);")
        append("}")
        append("})();</script></body></html>")
    }
}

/**
 * 块级公式：先按 [Dimensions.MathPlaceholderHeight] 占位，测到高度后精确设置，宽度超出的公式在 WebView 内横向滚动。
 *
 * 颜色取 `MaterialTheme.colorScheme.onSurface`，错误色取 `error`，背景透明。
 */
@Composable
fun MarkdownMathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val errorColor = MaterialTheme.colorScheme.error
    val fontPx = resolveFontSizePx(fontSize, density)
    val cssFontPx = fontPx / density.density
    val key = remember(latex, fontPx) {
        MarkdownMathKey(latex = latex, displayMode = true, fontSizePx = fontPx.roundToInt())
    }
    val html = remember(latex, cssFontPx, textColor, errorColor) {
        buildKaTeXHtml(
            latex = latex,
            displayMode = true,
            fontSizePx = cssFontPx,
            textColorArgb = textColor.toArgb(),
            errorColorArgb = errorColor.toArgb(),
        )
    }

    var measured by remember(key) { mutableStateOf(MarkdownMathSizeCache.size(key)) }
    var failed by remember(html) { mutableStateOf(false) }
    var rendered by remember(html) { mutableStateOf(false) }

    val host = rememberKaTeXWebViewSession(
        html = html,
        onMeasurement = { measurement ->
            val physical = measurement.toPhysicalSize(density.density)
            if (physical.width > 0 && physical.height > 0) {
                rendered = true
                if (physical != measured) {
                    measured = physical
                    MarkdownMathSizeCache.record(key, physical)
                }
            }
        },
        onUnavailable = { failed = true },
    )

    // 4 秒未测到尺寸就承认失败：宁可显示原始 LaTeX，也不留一块空白假装渲染成功。
    LaunchedEffect(html) {
        if (!rendered) {
            delay(MATH_RENDER_TIMEOUT_MS)
            if (!rendered) failed = true
        }
    }
    LaunchedEffect(failed) {
        if (failed) host?.dispose()
    }

    val unavailable = host == null || failed
    val renderHeight = if (unavailable) {
        null
    } else {
        measured?.height?.let { with(density) { it.toDp() } } ?: MARKDOWN_MATH_MIN_HEIGHT
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(MARKDOWN_MATH_BLOCK_TAG)
            .then(
                if (renderHeight != null) {
                    Modifier.height(renderHeight)
                } else {
                    Modifier.heightIn(min = MARKDOWN_MATH_MIN_HEIGHT)
                }
            ),
    ) {
        if (host == null || failed) {
            MarkdownMathFallback(latex = latex, displayMode = true, fontSize = fontSize)
        } else {
            key(host) {
                AndroidView(
                    factory = { host.webView.also { host.loadDataWithBaseURL(html) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 行内公式：在 placeholder 里铺满一块 WebView，测到尺寸后通过 [onMeasured] 回报
 * （Compose 物理像素），并写入 [MarkdownMathSizeCache]。
 *
 * [onMeasured] 会先收到一次估算尺寸，保证首帧不是 0×0；真实尺寸测到后再收到更新。
 */
@Composable
fun MarkdownMathInline(
    latex: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    onMeasured: (IntSize) -> Unit,
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val errorColor = MaterialTheme.colorScheme.error
    val fontPx = resolveFontSizePx(fontSize, density)
    val cssFontPx = fontPx / density.density
    val key = remember(latex, fontPx) {
        MarkdownMathKey(latex = latex, displayMode = false, fontSizePx = fontPx.roundToInt())
    }
    val html = remember(latex, cssFontPx, textColor, errorColor) {
        buildKaTeXHtml(
            latex = latex,
            displayMode = false,
            fontSizePx = cssFontPx,
            textColorArgb = textColor.toArgb(),
            errorColorArgb = errorColor.toArgb(),
        )
    }

    var measured by remember(key) {
        mutableStateOf(MarkdownMathSizeCache.size(key) ?: MarkdownMathSizeCache.estimateInlineSize(latex, key.fontSizePx))
    }
    var failed by remember(html) { mutableStateOf(false) }
    var rendered by remember(html) { mutableStateOf(false) }

    val currentOnMeasured by rememberUpdatedState(onMeasured)
    LaunchedEffect(measured) { currentOnMeasured(measured) }

    val host = rememberKaTeXWebViewSession(
        html = html,
        onMeasurement = { measurement ->
            val physical = measurement.toPhysicalSize(density.density)
            if (physical.width > 0 && physical.height > 0) {
                rendered = true
                if (physical != measured) {
                    measured = physical
                    MarkdownMathSizeCache.record(key, physical)
                }
            }
        },
        onUnavailable = { failed = true },
    )

    LaunchedEffect(html) {
        if (!rendered) {
            delay(MATH_RENDER_TIMEOUT_MS)
            if (!rendered) failed = true
        }
    }
    LaunchedEffect(failed) {
        if (failed) host?.dispose()
    }

    val widthDp = with(density) { measured.width.toDp() }
    val heightDp = with(density) { measured.height.toDp() }

    Box(
        modifier = modifier
            .size(widthDp, heightDp)
            .testTag(MARKDOWN_MATH_INLINE_TAG),
    ) {
        if (host == null || failed) {
            MarkdownMathFallback(latex = latex, displayMode = false, fontSize = fontSize)
        } else {
            key(host) {
                AndroidView(
                    factory = { host.webView.also { host.loadDataWithBaseURL(html) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 可见且诚实的失败态：等宽字体显示原始 LaTeX 源码，并显式声明「公式渲染不可用」。
 *
 * 行间公式额外显示一行说明；行内公式只显示源码，避免破坏正文行高。
 */
@Composable
internal fun MarkdownMathFallback(
    latex: String,
    displayMode: Boolean,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    if (displayMode) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = MARKDOWN_MATH_UNAVAILABLE_DESCRIPTION }
                .testTag(MARKDOWN_MATH_FALLBACK_TAG),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
        ) {
            Text(
                text = MARKDOWN_MATH_UNAVAILABLE_DESCRIPTION,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = latex,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                softWrap = true,
            )
        }
    } else {
        Text(
            text = latex,
            modifier = modifier
                .semantics(mergeDescendants = true) { contentDescription = MARKDOWN_MATH_UNAVAILABLE_DESCRIPTION }
                .testTag(MARKDOWN_MATH_FALLBACK_TAG),
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
        )
    }
}

/**
 * 一次 WebView 测量结果：自然尺寸（`#math` 盒子自身）与文档滚动尺寸（兜底参考）。
 * 单位都是 WebView CSS 像素。
 */
internal data class MathMeasurement(
    val natural: IntSize,
    val document: IntSize,
    val failed: Boolean,
)

/**
 * 解析 `evaluateJavascript` 回传的 `"nw,nh,sw,sh,failed"`。
 *
 * `evaluateJavascript` 返回的是 JSON 编码后的字符串（带外层引号），这里剥掉引号后按逗号切分。
 * 非数字、字段不足或空值一律返回 null，调用方视为「还没测到」。
 */
internal fun parseMathMeasurement(raw: String?): MathMeasurement? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "null") return null
    val unquoted = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }
    val parts = unquoted.split(',')
    if (parts.size < 5) return null
    val naturalWidth = parts[0].trim().toIntOrNull() ?: return null
    val naturalHeight = parts[1].trim().toIntOrNull() ?: return null
    val documentWidth = parts[2].trim().toIntOrNull() ?: return null
    val documentHeight = parts[3].trim().toIntOrNull() ?: return null
    return MathMeasurement(
        natural = IntSize(naturalWidth, naturalHeight),
        document = IntSize(documentWidth, documentHeight),
        failed = parts[4].trim() == "1",
    )
}

/** 优先用自然尺寸（不受 viewport 大小让位），测不到时退回文档滚动尺寸。 */
private fun MathMeasurement.toPhysicalSize(density: Float): IntSize {
    val cssWidth = if (natural.width > 0) natural.width else document.width
    val cssHeight = if (natural.height > 0) natural.height else document.height
    return IntSize(
        (cssWidth * density).roundToInt().coerceAtLeast(0),
        (cssHeight * density).roundToInt().coerceAtLeast(0),
    )
}

/**
 * 创建并持有一个仅用于公式渲染的 WebView。
 *
 * `onPageFinished` 之后按 [MATH_MEASURE_DELAYS_MS] 多次轮询尺寸（字体加载会改变排版），
 * 页面内不会回调原生：所有通信都是 Kotlin 主动 `evaluateJavascript`。
 */
private class KaTeXWebViewHost(context: Context) {

    var onMeasurement: ((MathMeasurement) -> Unit)? = null
    var onUnavailable: (() -> Unit)? = null

    val webView: WebView = createKaTeXWebView(context) { scheduleMeasurements() }

    private var disposed = false
    private var lastLoadedHtml: String? = null
    private val measureRunnable = Runnable { measureOnce() }

    fun loadDataWithBaseURL(html: String) {
        if (disposed || html == lastLoadedHtml) return
        lastLoadedHtml = html
        webView.loadDataWithBaseURL(MARKDOWN_MATH_ASSET_BASE_PATH, html, "text/html", "utf-8", null)
    }

    private fun scheduleMeasurements() {
        if (disposed) return
        for (delayMs in MATH_MEASURE_DELAYS_MS) {
            webView.postDelayed(measureRunnable, delayMs)
        }
    }

    private fun measureOnce() {
        if (disposed) return
        runCatching {
            webView.evaluateJavascript(MATH_MEASURE_SCRIPT) { raw ->
                if (disposed) return@evaluateJavascript
                val measurement = parseMathMeasurement(raw) ?: return@evaluateJavascript
                if (measurement.failed) {
                    onUnavailable?.invoke()
                } else {
                    onMeasurement?.invoke(measurement)
                }
            }
        }.onFailure {
            onUnavailable?.invoke()
        }
    }

    /** 解绑视图、取消待执行的测量并销毁 WebView；重复调用是安全的。 */
    fun dispose() {
        if (disposed) return
        disposed = true
        onMeasurement = null
        onUnavailable = null
        webView.removeCallbacks(measureRunnable)
        runCatching { webView.stopLoading() }
        (webView.parent as? ViewGroup)?.removeView(webView)
        runCatching { webView.destroy() }
    }
}

/**
 * 记住一个公式 WebView 会话：html（含 latex/颜色/字号）变化时重建，离开组合时销毁。
 */
@Composable
private fun rememberKaTeXWebViewSession(
    html: String,
    onMeasurement: (MathMeasurement) -> Unit,
    onUnavailable: () -> Unit,
): KaTeXWebViewHost? {
    val context = LocalContext.current
    val currentOnMeasurement by rememberUpdatedState(onMeasurement)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val host = remember(html) {
        runCatching { KaTeXWebViewHost(context) }.getOrNull()
    }
    DisposableEffect(host) {
        onDispose { host?.dispose() }
    }
    SideEffect {
        host?.onMeasurement = { currentOnMeasurement(it) }
        host?.onUnavailable = { currentOnUnavailable() }
    }
    return host
}

/**
 * 只加载本地资源、不能联网、不能跳转的公式 WebView。
 *
 * - `blockNetworkLoads = true`：从系统层面禁止 WebView 发起网络请求；
 * - `allowFileAccess = false` / `allowContentAccess = false`：不开放文件系统，只读 assets；
 * - 所有 URL 跳转一律拦截，且不注册 `JavascriptInterface`；
 * - 关闭滚动条、缩放、长按选择与焦点，触摸只用于横向查看超宽公式。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createKaTeXWebView(context: Context, onPageFinished: () -> Unit): WebView {
    val webView = WebView(context)
    try {
        webView.setBackgroundColor(AndroidColor.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isScrollbarFadingEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isLongClickable = false
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.isHapticFeedbackEnabled = false
        webView.settings.apply {
            javaScriptEnabled = true
            // assets 在 allowFileAccess=false 下仍可通过 file:///android_asset 读取；
            // 关闭文件系统访问与 file URL 跨源访问，进一步收窄攻击面。
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = false
            // 必须启用 wide viewport：否则 WebView 不认页面里的 viewport meta，
            // 会把布局视口当成物理像素再整体缩到 1/density（实测 visualViewport.scale≈0.381），
            // 公式就会比正文字号小一半以上。启用后 1 CSS px = 1dp，测量再乘 density 即物理像素。
            useWideViewPort = true
            textZoom = 100
            domStorageEnabled = false
            databaseEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            @Deprecated("API 24 以下仍会回调字符串版本，同样全部拦截。")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                onPageFinished()
            }
        }
    } catch (throwable: Throwable) {
        runCatching { webView.destroy() }
        throw throwable
    }
    return webView
}

/** 把 Compose 的 TextUnit 解析成物理像素；Unspecified/非法值回落到 16sp。 */
private fun resolveFontSizePx(fontSize: TextUnit, density: Density): Float {
    val resolved = if (fontSize == TextUnit.Unspecified) DEFAULT_MATH_FONT_SIZE else fontSize
    val pixels = with(density) { resolved.toPx() }
    if (pixels.isFinite() && pixels > 0f) return pixels
    return with(density) { DEFAULT_MATH_FONT_SIZE.toPx() }
}

/** ARGB → CSS 颜色；不透明时用 `#RRGGBB`，带透明度时用 CSS Color 4 的 `#RRGGBBAA`。 */
internal fun argbToCssColor(argb: Int): String {
    val alpha = (argb ushr 24) and 0xFF
    val red = (argb ushr 16) and 0xFF
    val green = (argb ushr 8) and 0xFF
    val blue = argb and 0xFF
    return if (alpha == 0xFF) {
        String.format(Locale.US, "#%02X%02X%02X", red, green, blue)
    } else {
        String.format(Locale.US, "#%02X%02X%02X%02X", red, green, blue, alpha)
    }
}

/** CSS 像素值：最多保留 4 位小数并去掉尾零，16.0f 输出 `16px`。 */
internal fun formatCssPx(value: Float): String {
    val safe = if (value.isFinite() && value > 0f) value.coerceIn(1f, 512f) else 16f
    val text = String.format(Locale.US, "%.4f", safe).trimEnd('0').trimEnd('.')
    return "$text" + "px"
}

/**
 * 把任意文本转成安全的 JS 字符串字面量（含外层双引号）。
 *
 * 关键点：`<` / `>` / `&` 也转成 `\uXXXX`，这样 LaTeX 里即使出现 `</script>`、
 * `<!--` 也无法截断 HTML 的脚本标签；反斜杠、引号、换行按 JS 规则转义；
 * U+2028/U+2029 这两个 JS 行分隔符同样显式转义。
 */
internal fun escapeJsStringLiteral(value: String): String {
    val builder = StringBuilder(value.length + 16)
    builder.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '<' -> builder.append("\\u003c")
            '>' -> builder.append("\\u003e")
            '&' -> builder.append("\\u0026")
            '\u2028' -> builder.append("\\u2028")
            '\u2029' -> builder.append("\\u2029")
            else -> if (ch < ' ') {
                builder.append(String.format(Locale.US, "\\u%04x", ch.code))
            } else {
                builder.append(ch)
            }
        }
    }
    builder.append('"')
    return builder.toString()
}
