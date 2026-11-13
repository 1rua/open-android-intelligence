package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.launch

/**
 * 登录等非工作台页面的设置承载容器：
 * - 紧凑屏/手机端：使用全屏侧滑设置页面（从左向右滑入，translationX = -screenWidth -> 0；向左滑出，0 -> -screenWidth），覆盖全部宽度。
 * - 宽屏/平板端：侧边栏抽屉展示（320dp）。
 */
@Composable
internal fun PlatformSettingsDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime,
    onRequestScreenCaptureAuthorization: () -> Unit,
    content: @Composable () -> Unit,
) {
    PlatformSettingsDrawer(
        open = open,
        onDismiss = onDismiss,
        settingsContent = { presentation, onBack ->
            SettingsScreen(
                environment = environment,
                runtime = runtime,
                onBack = onBack,
                presentation = presentation,
                onRequestScreenCaptureAuthorization = onRequestScreenCaptureAuthorization,
            )
        },
        content = content,
    )
}

/** 同一份设置内容只改变承载位置；导航、未保存表单和滚动状态随内容一起移动。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlatformSettingsDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    settingsContent: @Composable (presentation: SettingsPresentation, onBack: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val currentSettingsContent by rememberUpdatedState(settingsContent)
    val movableSettings = remember {
        movableContentOf<SettingsPresentation, () -> Unit> { presentation, onBack ->
            currentSettingsContent(presentation, onBack)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= Dimensions.ExpandedWindow

        if (expanded) {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val currentDismiss by rememberUpdatedState(onDismiss)

            LaunchedEffect(open, reduceMotion) {
                if (open) {
                    if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open()
                } else {
                    if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
                }
            }
            LaunchedEffect(open) {
                if (!open) return@LaunchedEffect
                var wasOpeningOrOpen = false
                snapshotFlow { Triple(drawer.currentValue, drawer.targetValue, drawer.isAnimationRunning) }
                    .collect { (current, target, animating) ->
                        if (current == DrawerValue.Open || target == DrawerValue.Open) wasOpeningOrOpen = true
                        if (wasOpeningOrOpen && current == DrawerValue.Closed && target == DrawerValue.Closed && !animating) {
                            currentDismiss()
                        }
                    }
            }

            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = open,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(Dimensions.DrawerWidth).testTag("platform-settings-drawer"),
                        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                    ) {
                        if (open || !drawer.isClosed || drawer.isAnimationRunning) {
                            movableSettings(SettingsPresentation.SIDEBAR) {
                                scope.launch {
                                    if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
                                }
                            }
                        }
                    }
                },
                content = content,
            )
        } else {
            // 手机/紧凑端：全屏侧滑设置（从左向右滑入，从右向左滑出），不留侧栏缝隙或残留可见会话
            Box(Modifier.fillMaxSize()) {
                content()

                AnimatedVisibility(
                    visible = open,
                    enter = AppTransitions.modalEnter(reduceMotion),
                    exit = AppTransitions.modalExit(reduceMotion),
                    modifier = Modifier.fillMaxSize().testTag("phone-fullscreen-settings-container"),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                    ) {
                        movableSettings(SettingsPresentation.SCREEN, onDismiss)
                    }
                }
            }
        }
    }
}
