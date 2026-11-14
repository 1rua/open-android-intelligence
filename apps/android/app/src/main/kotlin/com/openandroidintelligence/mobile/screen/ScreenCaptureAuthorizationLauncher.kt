package com.openandroidintelligence.mobile.screen

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.ui.resources.R as UiR

/** 只由本机设置点击发起，保留策略票据与前台服务先于系统授权结果的顺序。 */
@Composable
internal fun rememberScreenCaptureAuthorization(
    app: OpenAndroidIntelligenceApplication,
    activity: ComponentActivity,
    notice: (String) -> Unit,
): () -> Unit {
    fun enabled(): Boolean {
        val plugin = "org.openandroidintelligence.screen-capture"
        val entry = app.pluginHost.entries(plugin).firstOrNull { "kernel.screen.capture" in it.primitives } ?: return false
        return app.pluginHost.isEnabled(plugin) && app.pluginHost.capabilityPolicy(plugin, entry.key).enabled
    }
    fun ticket() = ScreenCaptureConsentTicket(app.pairingGrants.state.value?.revision ?: -1L,
        app.pluginHost.revision.value, com.openandroidintelligence.control.ScreenHostRegistry.current?.status()?.stopGeneration ?: -1L)
    return rememberScreenCaptureAuthorization(activity, ::enabled, ::ticket, { saved ->
        screenCaptureConsentCurrent(saved, app.pairingGrants.state.value?.revision ?: -1L, app.pluginHost.revision.value,
            enabled(), app.kernel.isEmergencyStopped(), com.openandroidintelligence.control.ScreenHostRegistry.current?.status()?.stopGeneration ?: -1L)
    }, notice)
}

/** 系统选择器由前台点击触发；可替换策略事实以验证真实 ActivityResult 路由。 */
@Composable
internal fun rememberScreenCaptureAuthorization(
    activity: ComponentActivity,
    captureEnabled: () -> Boolean,
    ticket: () -> ScreenCaptureConsentTicket,
    current: (ScreenCaptureConsentTicket?) -> Boolean,
    notice: (String) -> Unit,
): () -> Unit {
    var remoteCaptureTicket by remember { mutableStateOf<ScreenCaptureConsentTicket?>(null) }
    fun captureConsentCurrent() = current(remoteCaptureTicket)
    val remoteScreenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val source = com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.source
        if (source == null) {
            notice(activity.getString(UiR.string.perm_screen_capture_disabled))
            return@rememberLauncherForActivityResult
        }
        val accepted = result.resultCode == android.app.Activity.RESULT_OK
        if (!accepted) {
            remoteCaptureTicket = null
            // 用户拒绝或结果数据缺失：能力保持不可用，绝不伪造结果。
            source.release()
            com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
                .stopForeground(activity)
            notice(activity.getString(UiR.string.perm_screen_screenshot_denied))
            return@rememberLauncherForActivityResult
        }
        val stillGranted = captureConsentCurrent()
        if (!stillGranted) {
            source.release()
            com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
                .stopForeground(activity)
            remoteCaptureTicket = null
            notice(activity.getString(UiR.string.perm_screen_screenshot_changed))
            return@rememberLauncherForActivityResult
        }
        // 先等前台服务完成 startForeground，再转交授权结果：
        // Android 14+ 下顺序反了会确定性抛出 SecurityException。
        com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
            .startForeground(activity) {
                if (!captureConsentCurrent()) {
                    source.release()
                    com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.stopForeground(activity)
                    remoteCaptureTicket = null
                    notice(activity.getString(UiR.string.perm_screen_screenshot_revoked))
                    return@startForeground
                }
                val granted = source.onAuthorizationResult(result.resultCode, result.data)
                remoteCaptureTicket = null
                if (!granted) {
                    com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
                        .stopForeground(activity)
                }
                notice(
                    if (granted) activity.getString(UiR.string.perm_screen_screenshot_granted)
                    else activity.getString(UiR.string.perm_screen_screenshot_session_failed)
                )
            }
    }
    val requestRemoteScreenCaptureAuth: () -> Unit = {
        val source = com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.source
        val permitted = captureEnabled() && current(ticket())
        when {
            source == null -> notice(activity.getString(UiR.string.perm_screen_capture_disabled))
            !permitted -> notice(activity.getString(UiR.string.perm_screen_screenshot_enable_plugin_hint))
            // 截图来源已可用（无障碍按需截图已就绪，或投屏此前已授权）
            // 时不需要再走一次系统授权，如实告知即可。
            source.isAvailable -> notice(activity.getString(UiR.string.perm_screen_screenshot_ready_no_auth))
            else -> {
                val intent = source.createAuthorizationIntent()
                if (intent == null) {
                    notice(activity.getString(UiR.string.perm_screen_record_service_unavailable))
                } else {
                    remoteCaptureTicket = ticket()
                    remoteScreenCaptureLauncher.launch(intent)
                }
            }
        }
    }
    return requestRemoteScreenCaptureAuth
}
