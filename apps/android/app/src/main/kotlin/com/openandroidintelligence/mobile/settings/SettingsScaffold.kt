package com.openandroidintelligence.mobile.settings

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.LocalAnimatedVisibilityScope
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.motion.SharedTransitionKeys
import com.openandroidintelligence.conversation.motion.m3SharedBounds
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced

/** 仅由一级设置分类子页注入，供 [SettingsPageScaffold] 对齐总览页对应分类卡片的共享边界。 */
internal val LocalSettingsSharedCategoryKey = compositionLocalOf<String?> { null }

/**
 * 为设置分类卡片与一级子页统一挂载共享边界。
 *
 * 在全屏设置模式下进入目标子页时，随过渡进度将覆盖层裁切圆角从 [AppRadius.Large] 平滑插值至 `0.dp`，
 * 避免全屏页在动画结束帧突然由 24dp 圆角跳变为直角；侧栏与卡片端则保持 [AppRadius.Large]。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.settingsCategorySharedBounds(
    route: String,
    presentation: SettingsPresentation,
    isTargetPage: Boolean = false,
): Modifier {
    val reduced = LocalMotionPolicy.current.isReduced
    val visibilityScope = LocalAnimatedVisibilityScope.current
    val clipCorner = if (isTargetPage && presentation == SettingsPresentation.SCREEN && visibilityScope != null) {
        val animatedCorner by visibilityScope.transition.animateDp(
            transitionSpec = { MotionSpecs.morph(reduced) },
            label = "settings-shared-clip",
        ) { state ->
            if (state == EnterExitState.Visible) 0.dp else AppRadius.Large
        }
        animatedCorner.coerceAtLeast(0.dp)
    } else {
        AppRadius.Large
    }
    return m3SharedBounds(
        key = SharedTransitionKeys.settingsCategoryCard(route),
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        clipShape = RoundedCornerShape(clipCorner),
    )
}

/** Shared settings surface. The sidebar host has already consumed the system bars. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    closeSemantics: Boolean = false,
    sharedCategoryKey: String? = LocalSettingsSharedCategoryKey.current,
    content: @Composable (PaddingValues) -> Unit,
) {
    val color = if (presentation == SettingsPresentation.SIDEBAR) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else MaterialTheme.colorScheme.surface
    val sharedModifier = if (sharedCategoryKey != null) {
        modifier.settingsCategorySharedBounds(
            route = sharedCategoryKey,
            presentation = presentation,
            isTargetPage = true,
        )
    } else {
        modifier
    }
    Scaffold(
        modifier = sharedModifier.imePadding(),
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
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier.widthIn(max = Dimensions.ReadingWidth).fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceSmall),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            content = content,
        )
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
