package com.openandroidintelligence.conversation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 品牌基础色；界面一律通过 MaterialTheme 的语义角色取色。 */
object AppColors {
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

/** 4dp 细分、8dp 主网格。窗口断点使用 Material 紧凑/中等/扩展定义。 */
object Dimensions {
    val SpaceTiny = 4.dp
    val SpaceSmall = 8.dp
    val SpaceCompact = 12.dp
    val SpaceMedium = 16.dp
    val SpaceLarge = 24.dp
    val SpaceXLarge = 32.dp
    val MinimumTouchTarget = 48.dp
    val DockedBall = 56.dp
    val BrandMark = 64.dp
    val Icon = 24.dp
    val SmallIcon = 20.dp
    val Progress = 24.dp
    val StrokeHairline = 1.dp
    val StrokeStitch = 2.dp
    val FormWidth = 480.dp
    val ReadingWidth = 840.dp
    val MessageWidth = 640.dp
    val DrawerWidth = 320.dp
    val CommandMenuHeight = 240.dp
    val MediumWindow = 600.dp
    val ExpandedWindow = 840.dp
}
