package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.SettingsPresentation

/** 空窗口 insets,用于侧栏模式 */
private val NoWindowInsets = WindowInsets(0, 0, 0, 0)

/** 当前呈现方式下页面（Scaffold / 标题栏）应使用的底色。 */
@Composable
internal fun settingsContainerColor(presentation: SettingsPresentation): Color =
    if (presentation == SettingsPresentation.SIDEBAR) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surface
    }

/** 当前呈现方式下 Scaffold 内容区应扣的系统栏 inset。 */
@Composable
internal fun settingsContentWindowInsets(presentation: SettingsPresentation): WindowInsets =
    if (presentation == SettingsPresentation.SIDEBAR) {
        NoWindowInsets
    } else {
        androidx.compose.foundation.layout.WindowInsets.systemBars
    }

/**
 * 设置子页统一的 Scaffold：标题栏、底色与系统栏 inset 只在 [SettingsPresentation] 处分支一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScaffold(
    title: String,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    closeSemantics: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val containerColor = settingsContainerColor(presentation)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (closeSemantics) {
                            Icon(Icons.Default.Close, contentDescription = "关闭设置")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = containerColor,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                windowInsets = if (presentation == SettingsPresentation.SIDEBAR) {
                    NoWindowInsets
                } else {
                    TopAppBarDefaults.windowInsets
                },
            )
        },
        containerColor = containerColor,
        contentWindowInsets = settingsContentWindowInsets(presentation),
        content = content,
    )
}

/**
 * 设置页统一内容容器：宽屏限宽居中 + 页面边距 + 垂直滚动。
 *
 * 短列表（分组卡片）保留 `Column + verticalScroll`：分组是固定的 7 块、每块内部
 * 条目数量有限,懒加载不会带来收益；而既有界面测试依赖「所有分组行都在语义树里」
 * 才能 `performScrollTo`,换成 LazyColumn 会让屏外行不再被组合,测试锚点随之失效。
 * 真正无上限增长的列表（审计流水）另用 LazyColumn。
 */
@Composable
fun SettingsPageColumn(
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimensions.ReadingWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            content = content,
        )
    }
}

/**
 * 设置分组标签。
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = Dimensions.SpaceMedium,
            top = Dimensions.SpaceSmall,
            bottom = Dimensions.SpaceTiny,
        ),
    )
}
