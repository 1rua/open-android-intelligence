package com.openandroidintelligence.mobile

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun 登录仍可见时请求待命而服务须等注册定案再使用持久授权() = runTest {
        val gate = CompletableDeferred<Boolean>()
        var starts = 0
        val reservation = HostRegistrationStandby { starts++ }
        val result = async { reservation.duringRegistration { gate.await() } }
        runCurrent()
        assertEquals(1, starts)
        val service = async { reservation.awaitRegistration() }
        runCurrent()
        assertFalse(service.isCompleted)
        gate.complete(false)
        assertFalse(result.await())
        service.await()
        assertEquals(1, starts)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun 系统短暂拒绝后可见状态重试而退到后台时立即取消() = runTest {
        val visible = MutableStateFlow(true)
        var attempts = 0
        var decision = StandbyStart.RESTRICTED
        backgroundScope.launch { visible.startVisibleHostStandby { attempts++; decision } }
        runCurrent()
        assertEquals(1, attempts)
        decision = StandbyStart.REQUESTED
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, attempts)
        visible.value = false; runCurrent()
        decision = StandbyStart.RESTRICTED
        visible.value = true; runCurrent()
        visible.value = false; runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, attempts)
        decision = StandbyStart.PERMISSION_DENIED
        visible.value = true; runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(4, attempts)
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
