package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalWindowMaxWidth
import com.openandroidintelligence.mobile.SettingsPresentation

private val LocalSettingsPageHorizontalInsets = compositionLocalOf<Dp?> { null }

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
        CompositionLocalProvider(LocalSettingsPageHorizontalInsets provides (startPadding + endPadding)) {
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
}

/**
 * 在不引入 `BoxWithConstraints`（`SubcomposeLayout`）的前提下，按呈现模式、窗口宽度、水平安全区 insets
 * 与字体缩放判断分段选择器是否需要降级为单选列表，避免在 `verticalScroll` 热路径内触发二次子组合测量。
 */
@Composable
internal fun isCompactSettingsSelector(
    presentation: SettingsPresentation?,
    horizontalChrome: Dp = if (presentation == null) {
        Dimensions.ScreenHorizontal * 2 + Dimensions.SpaceMedium * 2
    } else {
        Dimensions.SpaceMedium * 4
    },
    horizontalInsets: Dp? = null,
): Boolean {
    val viewportWidth = LocalWindowMaxWidth.current ?: LocalConfiguration.current.screenWidthDp.dp
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val layoutDirection = LocalLayoutDirection.current
    val safeAreaInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val windowHorizontalInsets = with(density) {
        (safeAreaInsets.getLeft(this, layoutDirection) + safeAreaInsets.getRight(this, layoutDirection)).toDp()
    }
    val sidebarStartInset = with(density) {
        val px = if (layoutDirection == LayoutDirection.Ltr) {
            safeAreaInsets.getLeft(this, layoutDirection)
        } else {
            safeAreaInsets.getRight(this, layoutDirection)
        }
        px.toDp()
    }
    val availableWidth = when (presentation) {
        SettingsPresentation.SIDEBAR ->
            (Dimensions.DrawerWidth - maxOf(horizontalInsets ?: 0.dp, sidebarStartInset) - horizontalChrome)
                .coerceAtLeast(0.dp)
        SettingsPresentation.SCREEN -> {
            val effectiveInsets = maxOf(
                horizontalInsets ?: LocalSettingsPageHorizontalInsets.current ?: 0.dp,
                windowHorizontalInsets,
            )
            val contentAreaWidth = (viewportWidth - effectiveInsets).coerceAtLeast(0.dp)
            (minOf(contentAreaWidth, Dimensions.ReadingWidth) - horizontalChrome).coerceAtLeast(0.dp)
        }
        null -> {
            val effectiveInsets = maxOf(horizontalInsets ?: 0.dp, windowHorizontalInsets)
            val contentAreaWidth = (viewportWidth - effectiveInsets).coerceAtLeast(0.dp)
            (minOf(contentAreaWidth, Dimensions.FormWidth) - horizontalChrome).coerceAtLeast(0.dp)
        }
    }
    return availableWidth < Dimensions.DrawerWidth * fontScale
}

/**
 * 共享自适应单项选择器：宽屏/足够宽度时渲染 [SingleChoiceSegmentedButtonRow]，
 * 窄屏、大字体或大水平安全区下自动降级为不截断标签的 [RadioButton] 列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingsAdaptiveSingleChoiceSelector(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    presentation: SettingsPresentation?,
    horizontalChrome: Dp = if (presentation == null) {
        Dimensions.ScreenHorizontal * 2 + Dimensions.SpaceMedium * 2
    } else {
        Dimensions.SpaceMedium * 4
    },
    horizontalInsets: Dp? = null,
) {
    val compact = isCompactSettingsSelector(
        presentation = presentation,
        horizontalChrome = horizontalChrome,
        horizontalInsets = horizontalInsets,
    )
    if (compact) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimensions.MinimumTouchTarget)
                        .selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option, onClick = null)
                    Text(labelOf(option), modifier = Modifier.weight(1f))
                }
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(labelOf(option))
                }
            }
        }
    }
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
