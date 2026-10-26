package com.openandroidintelligence.mobile

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

/**
 * 设置底板包装器（Modal BottomSheet），内容主体由标准 M3 [SettingsScreen] 驱动。
 *
 * 安全区归属（规格 §5.3）：
 * - 系统栏 inset 由**弹层**负责：`contentWindowInsets` 用 M3 默认值扣一次状态栏/手势条；
 * - 内层 [SettingsScreen] 以 [SettingsPresentation.BOTTOM_SHEET] 呈现，它的 `Scaffold`
 *   与 `TopAppBar` 显式使用 `WindowInsets(0)`，因此不会出现「父子各扣一次」导致内容
 *   被凭空推下去一截的问题；
 * - 首屏标题栏的返回箭头改为「关闭」语义：它关掉的是整个弹层，而不是退子页。
 *
 * 配色：弹层底色取 `surfaceContainerLow`（比页面低一档），内部的分组卡片
 * `surfaceContainer` 才浮得起来；没有把手之外的额外覆盖物。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingsBottomSheet(
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime,
    onDismissRequest: () -> Unit,
    /** 远程屏幕截图需要前台 Activity 发起系统投屏授权：弹层本身没有稳定的宿主。 */
    onRequestScreenCaptureAuthorization: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { BottomSheetDefaults.windowInsets },
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        SettingsScreen(
            environment = environment,
            runtime = runtime,
            onBack = onDismissRequest,
            presentation = SettingsPresentation.BOTTOM_SHEET,
            onRequestScreenCaptureAuthorization = onRequestScreenCaptureAuthorization,
        )
    }
}
