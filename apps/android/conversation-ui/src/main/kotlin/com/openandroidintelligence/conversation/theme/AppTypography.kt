package com.openandroidintelligence.conversation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 品牌排版。
 *
 * 原则（设计规格 §4.3）：
 * 1. **不指定 FontFamily**：让 Android 自己选择系统中文字体回退（Noto Sans SC / Roboto），
 *    中文与拉丁混排时的字面高度才一致；只有真正的技术元数据才用 [AppTextStyles.mono]。
 * 2. **所有尺寸都是 sp**：跟随系统字号缩放，不使用固定高度裁切多行正文。
 * 3. **正文 16sp / 行高 1.6**：长段中文阅读的主音；辅助信息 12–14sp；大标题不超过 24sp，
 *    避免营销页式的巨型标题。
 * 4. **字重只有 400 / 500 / 600**：标题与强调用 600，可点击标签用 500，正文用 400。
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * 排版之外的少量补充样式。
 *
 * 等宽字体只用于真正的技术元数据：时间戳、协议版本、附件大小、错误码。
 * 不要用它排正文，也不要为了"科技感"把普通标签改成等宽。
 */
object AppTextStyles {
    /** 时间戳 / 字节数 / 错误码：等宽、12sp。 */
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    )

    /** 更小的元数据行：等宽、11sp，用于消息下方的状态与时间。 */
    val MonoSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    )

    /** 品牌字标：只用于登录页与欢迎页的一行标识，字距略宽。 */
    val BrandWordmark = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.2.sp,
    )

    /**
     * 执行过程（思维链正文、思考/工具卡标题、紧凑叙述）专用样式：13sp / 18.2sp（1.4 倍行高）。
     *
     * 执行卡是正文里的「过程」而不是回答本身：字号比正文收一档，
     * 靠 [com.openandroidintelligence.conversation.workbench.LocalMarkdownCompact]
     * 让思维链正文里的 Markdown 全部走这套度量，不再逐块手写字号。
     */
    val Thought = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.2.sp,
        letterSpacing = 0.1.sp,
    )
}
