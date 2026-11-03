package com.openandroidintelligence.mobile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.launch

/** 登录等非工作台页面各自使用一个原生抽屉；工作台使用自己的共享抽屉。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlatformSettingsDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime,
    onRequestScreenCaptureAuthorization: () -> Unit,
    content: @Composable () -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
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
                modifier = Modifier.width(Dimensions.DrawerWidth),
                windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            ) {
                if (open || !drawer.isClosed || drawer.isAnimationRunning) {
                    SettingsScreen(
                        environment = environment,
                        runtime = runtime,
                        onBack = {
                            scope.launch {
                                if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
                            }
                        },
                        presentation = SettingsPresentation.SIDEBAR,
                        onRequestScreenCaptureAuthorization = onRequestScreenCaptureAuthorization,
                    )
                }
            }
        },
        content = content,
    )
}
