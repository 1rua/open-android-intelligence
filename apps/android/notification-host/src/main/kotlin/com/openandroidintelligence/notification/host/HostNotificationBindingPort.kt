package com.openandroidintelligence.notification.host

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.openandroidintelligence.notification.control.NotificationBindingPort
import com.openandroidintelligence.notifications.NotificationRuntimeFactoryRegistry
import com.openandroidintelligence.notifications.OpenAndroidIntelligenceNotificationListenerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 系统通知监听绑定端口的真实实现。
 *
 * - 使用 `ComponentName(appContext, OpenAndroidIntelligenceNotificationListenerService::class.java)`
 *   动态核验系统 `NotificationManager` 授权与 `Settings.Secure` 中的
 *   `enabled_notification_listeners`，排除包名后缀（如 debug）或重构改名导致的误判；
 * - 同时暴露 `NotificationListenerService` 真实连接态与 `requestRebind` 主动重连探测，
 *   并在跳转系统设置页时精确指向 `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`。
 */
class HostNotificationBindingPort(
    context: Context,
    private val listenerComponent: ComponentName = ComponentName(
        context.applicationContext,
        OpenAndroidIntelligenceNotificationListenerService::class.java,
    ),
    private val listenerConnected: () -> Boolean = { NotificationRuntimeFactoryRegistry.isListenerConnected() },
    private val listenerConnectedFlow: StateFlow<Boolean> = NotificationRuntimeFactoryRegistry.listenerConnectedFlow,
    private val rebindRequester: (ComponentName) -> Unit = { component ->
        NotificationListenerService.requestRebind(component)
    },
) : NotificationBindingPort {

    private val appContext: Context = context.applicationContext

    override fun isListenerBound(): Boolean {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (manager != null && runCatching { manager.isNotificationListenerAccessGranted(listenerComponent) }.getOrDefault(false)) {
            return true
        }
        return isEnabledInSecureSettings()
    }

    override fun isListenerConnected(): Boolean = listenerConnected()

    override fun observeListenerConnected(): Flow<Boolean> = listenerConnectedFlow

    override fun requestListenerRebind(): Boolean {
        if (!isListenerBound() || isListenerConnected()) return false
        return runCatching {
            rebindRequester(listenerComponent)
            true
        }.getOrDefault(false)
    }

    override fun openSystemListenerSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (failure: ActivityNotFoundException) {
            // 系统没有该设置页（罕见定制 ROM）：如实报告未发起，而不是假成功。
            false
        }
    }

    private fun isEnabledInSecureSettings(): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS,
        ).orEmpty()
        com.openandroidintelligence.notification.control.NotificationControlRegistry
            .isComponentInColonSeparatedSetting(enabled, listenerComponent)
    }.getOrDefault(false)

    private companion object {
        const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    }
}
