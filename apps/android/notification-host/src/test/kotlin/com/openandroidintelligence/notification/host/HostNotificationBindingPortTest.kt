package com.openandroidintelligence.notification.host

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.notifications.OpenAndroidIntelligenceNotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * - 绑定状态使用 `ComponentName(context, OpenAndroidIntelligenceNotificationListenerService::class.java)`
 *   动态核验 NotificationManager 与 enabled_notification_listeners，同时暴露真实连接态与重绑探测。
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

    @Test
    fun is_listener_bound_validates_enabled_notification_listeners_by_exact_component_name() {
        val debugComponent = ComponentName(
            "com.openandroidintelligence.mobile.debug",
            OpenAndroidIntelligenceNotificationListenerService::class.java.name,
        )
        val port = HostNotificationBindingPort(context, listenerComponent = debugComponent)

        // 其他包名或同包但类名重构前旧名均不得误判为已授权。
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "com.other.app/${OpenAndroidIntelligenceNotificationListenerService::class.java.name}:" +
                "com.openandroidintelligence.mobile.debug/com.openandroidintelligence.notifications.OldListenerService",
        )
        assertFalse(port.isListenerBound())

        // 动态匹配带 debug 包名后缀的完整组件名。
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "com.other.app/.OtherListener:${debugComponent.flattenToString()}",
        )
        assertTrue(port.isListenerBound())

        // 清空后恢复未授权。
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        assertFalse(port.isListenerBound())
    }

    @Test
    fun listener_connection_flow_and_rebind_probe_respect_grant_and_connection_state() = runBlocking {
        val connected = MutableStateFlow(false)
        val rebindTargets = mutableListOf<ComponentName>()
        val component = ComponentName(context, OpenAndroidIntelligenceNotificationListenerService::class.java)
        val port = HostNotificationBindingPort(
            context = context,
            listenerComponent = component,
            listenerConnected = { connected.value },
            listenerConnectedFlow = connected,
            rebindRequester = { rebindTargets += it },
        )

        // 未获系统授权时不请求重绑。
        assertFalse(port.isListenerConnected())
        assertFalse(port.observeListenerConnected().first())
        assertFalse(port.requestListenerRebind())
        assertTrue(rebindTargets.isEmpty())

        // 系统已授权但服务未连接时，主动触发一次 requestRebind。
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", component.flattenToString())
        assertTrue(port.requestListenerRebind())
        assertEquals(listOf(component), rebindTargets)

        // 服务连接成功后不再重复触发 rebind，流同步反映已连接。
        connected.value = true
        assertTrue(port.isListenerConnected())
        assertTrue(port.observeListenerConnected().first())
        assertFalse(port.requestListenerRebind())
        assertEquals(1, rebindTargets.size)
    }
}
