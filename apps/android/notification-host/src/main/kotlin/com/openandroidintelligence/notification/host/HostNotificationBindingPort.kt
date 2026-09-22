package com.openandroidintelligence.notification.host

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.openandroidintelligence.notification.control.NotificationBindingPort
import com.openandroidintelligence.notifications.OpenAndroidIntelligenceNotificationListenerService

/**
 * 系统通知监听绑定端口的真实实现。
 *
 * - 绑定状态来自系统 NotificationManager 的通知使用权查询——只有用户在
 *   系统「通知使用权」设置页显式授予后它才为 true；
 * - 「打开系统监听设置」精确指向 Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS，
 *   端口没有任何代替用户授予的捷径。
 */
class HostNotificationBindingPort(
    context: Context,
    private val listenerComponent: ComponentName = ComponentName(
        context,
        OpenAndroidIntelligenceNotificationListenerService::class.java,
    ),
) : NotificationBindingPort {

    private val appContext: Context = context.applicationContext

    override fun isListenerBound(): Boolean {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
        return manager.isNotificationListenerAccessGranted(listenerComponent)
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
}
