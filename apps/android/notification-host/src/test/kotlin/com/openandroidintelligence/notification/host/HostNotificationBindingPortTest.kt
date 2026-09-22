package com.openandroidintelligence.notification.host

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.notifications.OpenAndroidIntelligenceNotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 绑定端口的平台契约：
 * - 「打开系统通知使用权设置」必须精确指向 Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS，
 *   通知监听只能由用户在系统设置页显式授予，端口不得有任何代替授予的捷径；
 * - 绑定状态来自系统 NotificationManager 的真实查询。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostNotificationBindingPortTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun open_system_listener_settings_launches_the_system_listener_settings_intent() {
        val port = HostNotificationBindingPort(context)

        val launched = port.openSystemListenerSettings(context)

        assertTrue(launched)
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent!!.action)
        // 非 Activity 上下文启动必须自带 NEW_TASK，否则直接崩溃
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    @Test
    fun is_listener_bound_reflects_the_system_listener_access_grant() {
        val port = HostNotificationBindingPort(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val component = ComponentName(context, OpenAndroidIntelligenceNotificationListenerService::class.java)

        assertFalse("默认没有授权", port.isListenerBound())

        shadowOf(manager).setNotificationListenerAccessGranted(component, true)
        assertTrue("系统授予通知使用权后端口必须如实报告", port.isListenerBound())

        shadowOf(manager).setNotificationListenerAccessGranted(component, false)
        assertFalse("撤销后必须回落为未绑定", port.isListenerBound())
    }
}
