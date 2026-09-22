package com.openandroidintelligence.notifications

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「全仓 XML 零命中」的消除证据：系统用
 * Intent("android.service.notification.NotificationListenerService") 发现
 * 通知监听服务，这里用同一个查询验证合并 Manifest 真的可被发现，
 * 且服务受 BIND_NOTIFICATION_LISTENER_SERVICE 权限保护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationListenerServiceManifestTest {

    @Test
    fun listener_service_is_discoverable_via_the_system_listener_intent() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val resolved = context.packageManager.queryIntentServices(
            Intent("android.service.notification.NotificationListenerService"),
            PackageManager.GET_RESOLVED_FILTER,
        )

        val service = resolved.single {
            it.serviceInfo.name == "com.openandroidintelligence.notifications.OpenAndroidIntelligenceNotificationListenerService"
        }
        assertEquals(
            "监听服务必须受系统 BIND_NOTIFICATION_LISTENER_SERVICE 权限保护",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            service.serviceInfo.permission,
        )
        assertNotNull(service.filter)
        assertTrue(
            "intent-filter 必须声明 android.service.notification.NotificationListenerService",
            service.filter!!.getAction(0) == "android.service.notification.NotificationListenerService",
        )
        assertFalse(service.serviceInfo.exported && service.serviceInfo.permission == null)
    }
}
