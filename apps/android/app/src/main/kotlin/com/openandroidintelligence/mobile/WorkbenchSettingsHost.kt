package com.openandroidintelligence.mobile

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.theme.Dimensions

/** 设置承载模式只取整个工作台宽度，不能从已经收窄的设置插槽反推。 */
@Composable
internal fun WorkbenchSettingsHost(modifier: Modifier = Modifier, content: @Composable (SettingsPresentation) -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        content(if (maxWidth >= Dimensions.ExpandedWindow) SettingsPresentation.SIDEBAR else SettingsPresentation.SCREEN)
    }
}
