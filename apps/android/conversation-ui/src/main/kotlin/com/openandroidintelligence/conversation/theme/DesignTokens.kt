package com.openandroidintelligence.conversation.theme

import androidx.compose.ui.unit.dp

/**
 * 圆角令牌。
 *
 * 一个容器越「大」、越「外层」，圆角越大；小构件（chip、图标底衬）使用小档位。
 * 禁止界面里再手写 `RoundedCornerShape(14.dp)` 之类的散值。
 *
 * - [Compact] 12dp：思考/工具卡片等紧凑卡片；
 * - [Medium] 16dp：次级卡片、输入框、列表容器；
 * - [Bubble] 20dp：消息气泡、撰写栏；
 * - [Large] 24dp：设置分组大卡片、底部弹层卡片；
 * - [ExtraLarge] 28dp：全屏浮层、助理展开态。
 */
object AppRadius {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    /** 12dp：紧凑卡片、思考/工具卡片。 */
    val Compact = 12.dp
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
    /** 输入坞图标按钮的视觉直径（触摸目标仍由 [MinimumTouchTarget] 兜底）。 */
    val ComposerControl = 44.dp
    /** 输入坞主操作按钮（发送/停止）直径。 */
    val ComposerPrimaryAction = 48.dp
    /** 输入坞单行高度下限。 */
    val ComposerMinHeight = 48.dp
    /** M3 TopAppBar 内容高度（不含状态栏）。 */
    val TopBarContentHeight = 64.dp
    /** 悬浮球直径。 */
    val DockedBall = 48.dp
    /** 品牌标识高度（信号缝线/登录徽章）。 */
    val BrandMark = 48.dp
    /** 标准图标尺寸。 */
    val Icon = 24.dp
    /** 次级图标尺寸。 */
    val SmallIcon = 18.dp
    /** 列表前置图标容器直径。 */
    val LeadingIconContainer = 32.dp
    /** 进度指示器尺寸。 */
    val Progress = 24.dp
    /** 细分隔线粗细。 */
    val StrokeHairline = 1.dp
    /** 信号缝线粗细。 */
    val StrokeStitch = 2.dp
    /** 助手正文左侧引导线的宽度占位（含线与留白）。 */
    val StitchGutter = 12.dp
    /** 表单最大宽度。 */
    val FormWidth = 480.dp
    /** 阅读区最大宽度：正文每行不超过约 680dp，多余空间留给边距。 */
    val ReadingWidth = 680.dp
    /** 消息气泡最大宽度。 */
    val MessageWidth = 640.dp
    /** 用户气泡占可用宽度的比例上限。 */
    const val MessageWidthFraction = 0.86f
    /** 抽屉宽度。 */
    val DrawerWidth = 320.dp
    /** 抽屉项单行高度。 */
    val DrawerItemHeight = 48.dp
    /** 命令菜单最大高度。 */
    val CommandMenuHeight = 240.dp
    /** 中等窗口断点。 */
    val MediumWindow = 600.dp
    /** 展开窗口断点。 */
    val ExpandedWindow = 840.dp
    /** 时间线列表内容边距。 */
    val TimelineHorizontal = 16.dp
    /** 时间线消息之间的垂直间距。 */
    val TimelineSpacing = 20.dp
    /** 嵌套列表每加深一级的缩进量，保证多级列表在手机上仍可读。 */
    val ListIndent = 16.dp
    /** 列表/任务项标记列宽度：圆点、复选框与序号共用，正文起点因此保持对齐。 */
    val ListMarkerWidth = 20.dp
}
