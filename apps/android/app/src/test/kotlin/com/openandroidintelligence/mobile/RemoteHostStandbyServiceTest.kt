package com.openandroidintelligence.mobile

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], application = RemoteHostStandbyServiceTest.Owner::class)
class RemoteHostStandbyServiceTest {
    class Owner : Application(), RemoteStandbyOwner {
        val standby = MutableStateFlow(true)
        @Volatile var restores = 0
        override suspend fun restoreHostStandby() = standby.also { restores++ }
    }

    @Test fun 系统重建时优先展示受控通知且只在授权有效时维持服务() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Owner
        val controller = Robolectric.buildService(RemoteHostStandbyService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 7))
            val shadow = shadowOf(service)
            val notification = requireNotNull(shadow.lastForegroundNotification)
            assertEquals(RemoteHostStandbyService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
            assertEquals(RemoteHostStandbyService.CHANNEL, notification.channelId)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            if (Build.VERSION.SDK_INT >= 34) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
            withTimeout(5_000) { while (app.restores == 0) { shadowOf(Looper.getMainLooper()).idle(); delay(10) } }
            assertFalse(shadow.isStoppedBySelf)
            app.standby.value = false
            withTimeout(5_000) { while (!shadow.isStoppedBySelf) { shadowOf(Looper.getMainLooper()).idle(); delay(10) } }
            assertEquals(7, shadow.stopSelfResultId)
        } finally { controller.destroy() }
    }
}
