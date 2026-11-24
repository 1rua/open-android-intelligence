package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalWindowMaxWidth
import com.openandroidintelligence.mobile.SettingsPresentation

/** Shared settings surface. The sidebar host has already consumed the system bars. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    closeSemantics: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val color = if (presentation == SettingsPresentation.SIDEBAR) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else MaterialTheme.colorScheme.surface
    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        containerColor = color,
        contentWindowInsets = if (presentation == SettingsPresentation.SIDEBAR) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (closeSemantics) Icon(Icons.Default.Close, "关闭设置")
                        else Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = color),
                windowInsets = if (presentation == SettingsPresentation.SIDEBAR) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
            )
        },
        content = content,
    )
}

@Composable
internal fun SettingsPageColumn(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = contentPadding.calculateStartPadding(layoutDirection)
    val endPadding = contentPadding.calculateEndPadding(layoutDirection)
    val topPadding = contentPadding.calculateTopPadding()
    val bottomPadding = contentPadding.calculateBottomPadding()
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding, top = topPadding, end = endPadding)
            .consumeWindowInsets(contentPadding)
            .verticalScroll(scrollState),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimensions.ReadingWidth)
                .fillMaxWidth()
                .padding(
                    start = Dimensions.SpaceMedium,
                    top = Dimensions.SpaceSmall,
                    end = Dimensions.SpaceMedium,
                    bottom = Dimensions.SpaceSmall + bottomPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            content = content,
        )
    }
}

/**
 * 在不引入 `BoxWithConstraints`（`SubcomposeLayout`）的前提下，按呈现模式、窗口宽度与字体缩放
 * 判断分段选择器是否需要降级为单选列表，避免在 `verticalScroll` 热路径内触发二次子组合测量。
 */
@Composable
internal fun isCompactSettingsSelector(
    presentation: SettingsPresentation?,
    horizontalChrome: Dp = Dimensions.SpaceMedium * 4,
): Boolean {
    val viewportWidth = LocalWindowMaxWidth.current ?: LocalConfiguration.current.screenWidthDp.dp
    val fontScale = LocalDensity.current.fontScale
    val availableWidth = when (presentation) {
        SettingsPresentation.SIDEBAR -> Dimensions.DrawerWidth - horizontalChrome
        SettingsPresentation.SCREEN -> minOf(viewportWidth, Dimensions.ReadingWidth) - horizontalChrome
        null -> minOf(viewportWidth, Dimensions.FormWidth) - horizontalChrome
    }
    return availableWidth < Dimensions.DrawerWidth * fontScale
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Dimensions.SpaceMedium, top = Dimensions.SpaceSmall),
    )
}
