package com.openandroidintelligence.mobile

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34, 35, 36], application = Application::class)
class AgentSessionForegroundServiceTest {
    @Test
    fun starts_data_sync_immediately_with_a_separate_silent_ongoing_notification() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        try {
            AgentSessionKeepAlive(service).update(hasWork = true, appVisible = true)
            assertEquals(Service.START_STICKY, service.onStartCommand(Intent(service, AgentSessionForegroundService::class.java), 0, 1))
            val shadow = shadowOf(service)
            val notification = requireNotNull(shadow.lastForegroundNotification)
            assertEquals(AGENT_SESSION_FOREGROUND_NOTIFICATION_ID, shadow.lastForegroundNotificationId)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
            assertEquals(AGENT_SESSION_FOREGROUND_CHANNEL_ID, notification.channelId)
            assertEquals("Agent 任务处理中", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("正在后台接收会话回复", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(Notification.CATEGORY_SERVICE, notification.category)
            assertNull(notification.bubbleMetadata)
            assertNull(notification.shortcutId)
            assertTrue(notification.contentIntent.isImmutable)
            assertEquals(MainActivity::class.java.name, shadowOf(notification.contentIntent).savedIntent.component!!.className)

            val manager = service.getSystemService(NotificationManager::class.java)
            val channel = requireNotNull(manager.getNotificationChannel(AGENT_SESSION_FOREGROUND_CHANNEL_ID))
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull(channel.sound)
            assertFalse(channel.shouldVibrate())
            assertFalse(channel.canShowBadge())
            assertNull(service.onBind(null))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun system_recreation_without_pending_user_work_stops_without_posting_a_notification() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNull(shadowOf(service).lastForegroundNotification)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun a_delayed_start_request_cannot_revive_a_task_that_was_explicitly_stopped() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        try {
            val keepAlive = AgentSessionKeepAlive(service)
            keepAlive.update(hasWork = true, appVisible = true)
            keepAlive.stop()

            val intent = Intent(service, AgentSessionForegroundService::class.java)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNull(shadowOf(service).lastForegroundNotification)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun system_recreation_of_pending_work_keeps_foreground_while_runtime_recovers() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        try {
            AgentSessionKeepAlive(service).update(hasWork = true, appVisible = true)
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            AgentSessionKeepAlive(service).update(hasWork = false, appVisible = false)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNotNull(shadowOf(service).lastForegroundNotification)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun recovery_lease_expires_and_prevents_an_unfinished_restore_from_restarting_forever() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        try {
            AgentSessionKeepAlive(service).update(hasWork = true, appVisible = true)
            service.onStartCommand(null, 0, 1)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))

            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun an_old_non_null_start_cannot_revive_an_expired_recovery_before_its_timeout_runs() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        try {
            AgentSessionKeepAlive(service).update(hasWork = true, appVisible = true)
            assertNotNull(AgentSessionRecoveryState(service).beginRecovery())
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(61))

            val intent = Intent(service, AgentSessionForegroundService::class.java)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(AgentSessionRecoveryState(service).hasPendingWork)
            assertNull(shadowOf(service).lastForegroundNotification)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun recovering_real_work_ends_the_lease_without_stopping_the_service() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        try {
            AgentSessionKeepAlive(service).update(hasWork = true, appVisible = true)
            service.onStartCommand(null, 0, 1)
            val restoredOwner = AgentSessionKeepAlive(service)

            restoredOwner.update(hasWork = true, appVisible = false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))

            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(AgentSessionRecoveryState(service).hasPendingWork)
            assertFalse(AgentSessionRecoveryState(service).isRecovering)
            restoredOwner.stop()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun process_destruction_retains_only_pending_eligibility_for_system_recreation() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        AgentSessionKeepAlive(service).update(hasWork = true, appVisible = true)
        service.onStartCommand(Intent(service, AgentSessionForegroundService::class.java), 0, 1)

        controller.destroy()

        assertTrue(AgentSessionRecoveryState(service).hasPendingWork)
        val recreated = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        try {
            assertEquals(Service.START_STICKY, recreated.get().onStartCommand(null, 0, 2))
        } finally {
            recreated.destroy()
        }
    }

    @Test
    fun repeated_visible_requests_update_one_notification_and_destroy_removes_it() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        val intent = Intent(service, AgentSessionForegroundService::class.java)
        AgentSessionKeepAlive(service).update(hasWork = true, appVisible = true)
        service.onStartCommand(intent, 0, 1)
        service.onStartCommand(intent, 0, 2)
        val manager = service.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.activeNotifications.count { it.notification.channelId == AGENT_SESSION_FOREGROUND_CHANNEL_ID })

        controller.destroy()
        service.onDestroy()

        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(manager.activeNotifications.none { it.notification.channelId == AGENT_SESSION_FOREGROUND_CHANNEL_ID })
    }

    @Test
    @Config(sdk = [35, 36])
    fun data_sync_timeout_stops_foreground_and_self_immediately_and_is_idempotent() {
        val controller = Robolectric.buildService(AgentSessionForegroundService::class.java).create()
        val service = controller.get()
        try {
            AgentSessionKeepAlive(service).update(hasWork = true, appVisible = true)
            service.onStartCommand(Intent(service, AgentSessionForegroundService::class.java), 0, 1)
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

            assertTrue(shadowOf(service).isForegroundStopped)
            assertTrue(shadowOf(service).isStoppedBySelf)
            val manager = service.getSystemService(NotificationManager::class.java)
            assertTrue(manager.activeNotifications.none { it.notification.channelId == AGENT_SESSION_FOREGROUND_CHANNEL_ID })
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        } finally {
            controller.destroy()
        }
    }
}
