package com.openandroidintelligence.conversation.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** 流式触觉只读偏好；默认关闭，由宿主和助理桥接实时提供。 */
val LocalStreamingHapticFeedbackEnabled = staticCompositionLocalOf { false }
