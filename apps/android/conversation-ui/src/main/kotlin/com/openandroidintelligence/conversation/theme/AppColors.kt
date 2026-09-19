package com.openandroidintelligence.conversation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 品牌色种子。
 *
 * 唯一职责：为 [BrandLightColorScheme] / [BrandDarkColorScheme] 提供不能动态取色时的
 * 降级配色。任何界面组件都不得直接引用这里的值 —— 界面一律通过
 * `MaterialTheme.colorScheme.*` 的语义角色取色，否则 Android 12+ 的系统动态取色
 * （Monet）会被绕过，深浅主题也无法保持一致。
 *
 * 之所以限定为 internal：让编译器替我们把住这条边界。
 */
internal object AppColors {
    val LightCanvas = Color(0xFFF1F4F1)
    val LightSurface = Color(0xFFE3ECE7)
    val LightSurfaceHigh = Color(0xFFD5E2DC)
    val LightPrimary = Color(0xFF2F645C)
    val LightAccent = Color(0xFFB8874A)
    val LightText = Color(0xFF1C2724)
    val LightMuted = Color(0xFF4D5D57)
    val LightError = Color(0xFF994E43)
    val DarkCanvas = Color(0xFF101613)
    val DarkSurface = Color(0xFF19231F)
    val DarkSurfaceHigh = Color(0xFF26332E)
    val DarkPrimary = Color(0xFF88BAAE)
    val DarkAccent = Color(0xFFD3A86F)
    val DarkText = Color(0xFFE8EFEB)
    val DarkMuted = Color(0xFFAFBDB6)
    val DarkError = Color(0xFFE1998C)
}

/**
 * 圆角令牌。
 *
 * 设置类容器一律使用 [AppRadius.Large]（24dp）或 [AppRadius.Medium]（16dp）的大圆角；
 * 只有 chips、图标底衬这类小构件才用小圆角。禁止界面里再手写 `RoundedCornerShape(14.dp)`
 * 之类的散值。
 */
object AppRadius {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    /** 16dp：次级卡片、输入框、列表容器。 */
    val Medium = 16.dp
    /** 20dp：聊天/输入类容器（气泡、撰写栏）。 */
    val Bubble = 20.dp
    /** 24dp：设置分组大卡片。 */
    val Large = 24.dp
    val ExtraLarge = 28.dp
}

/**
 * 尺寸与间距令牌。
 *
 * 间距严格落在 M3 的 8dp 栅格上（4dp 仅用于图标与文字的紧密微调，不作为独立留白）；
 * 页面外边距统一用 [ScreenHorizontal] / [ScreenVertical]，卡片内元素间距只用
 * 8 / 12 / 16dp。
 */
object Dimensions {
    /** 4dp：仅用于图标与文字这类紧密对齐的微调，不作为独立留白。 */
    val SpaceTiny = 4.dp
    /** 8dp：栅格基本单位，卡片内元素的最小间距。 */
    val SpaceSmall = 8.dp
    /** 12dp：卡片内相邻的次级间距。 */
    val SpaceCompact = 12.dp
    /** 16dp：卡片内标准间距、卡片之间的间距。 */
    val SpaceMedium = 16.dp
    /** 20dp：页面外边距。 */
    val ScreenHorizontal = 20.dp
    /** 16dp：页面垂直外边距。 */
    val ScreenVertical = 16.dp
    /** 24dp：内容区块之间的分隔留白。 */
    val SpaceLarge = 24.dp
    /** 32dp：欢迎页等宽松场景。 */
    val SpaceXLarge = 32.dp

    /** M3 最小可点击/可触摸尺寸。 */
    val MinimumTouchTarget = 48.dp
    /** 悬浮球直径。 */
    val DockedBall = 56.dp
    /** 品牌标识高度（信号缝线）。 */
    val BrandMark = 64.dp
    /** 标准图标尺寸。 */
    val Icon = 24.dp
    /** 次级图标尺寸。 */
    val SmallIcon = 20.dp
    /** 列表前置图标容器直径。 */
    val LeadingIconContainer = 40.dp
    /** 进度指示器尺寸。 */
    val Progress = 24.dp
    /** 细分隔线粗细。 */
    val StrokeHairline = 1.dp
    /** 信号缝线粗细。 */
    val StrokeStitch = 2.dp
    /** 表单最大宽度。 */
    val FormWidth = 480.dp
    /** 阅读区最大宽度。 */
    val ReadingWidth = 840.dp
    /** 消息气泡最大宽度。 */
    val MessageWidth = 640.dp
    /** 抽屉宽度。 */
    val DrawerWidth = 320.dp
    /** 命令菜单最大高度。 */
    val CommandMenuHeight = 240.dp
    /** 中等窗口断点。 */
    val MediumWindow = 600.dp
    /** 展开窗口断点。 */
    val ExpandedWindow = 840.dp
}
