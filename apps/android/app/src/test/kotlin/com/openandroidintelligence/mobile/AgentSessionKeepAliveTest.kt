package com.openandroidintelligence.mobile

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], application = Application::class)
class AgentSessionKeepAliveTest {
    @Test
    fun a_recreated_owner_can_stop_the_service_requested_by_the_previous_process() {
        val context = RecordingContext()
        AgentSessionKeepAlive(context).update(hasWork = true, appVisible = true)

        AgentSessionKeepAlive(context).stop()

        assertEquals(1, context.stops.size)
    }

    @Test
    fun only_visible_active_work_can_start_a_service() {
        val context = RecordingContext()
        val keepAlive = AgentSessionKeepAlive(context)

        keepAlive.update(hasWork = false, appVisible = true)
        keepAlive.update(hasWork = true, appVisible = false)
        assertTrue(context.starts.isEmpty())
        assertTrue(context.stops.isEmpty())

        keepAlive.update(hasWork = true, appVisible = true)
        assertEquals(1, context.starts.size)
        assertEquals(AgentSessionForegroundService::class.java.name, context.starts.single().component!!.className)
    }

    @Test
    fun active_service_is_retained_in_background_without_repeated_start_requests() {
        val context = RecordingContext()
        val keepAlive = AgentSessionKeepAlive(context)
        keepAlive.update(hasWork = true, appVisible = true)
        keepAlive.update(hasWork = true, appVisible = true)
        keepAlive.update(hasWork = true, appVisible = false)
        keepAlive.update(hasWork = true, appVisible = false)

        assertEquals(1, context.starts.size)
        assertTrue(context.stops.isEmpty())
    }

    @Test
    fun finished_work_or_explicit_stop_removes_the_service_and_allows_a_later_task() {
        val context = RecordingContext()
        val keepAlive = AgentSessionKeepAlive(context)
        keepAlive.update(hasWork = true, appVisible = true)
        keepAlive.update(hasWork = false, appVisible = false)
        assertEquals(1, context.stops.size)
        assertEquals(AgentSessionForegroundService::class.java.name, context.stops.single().component!!.className)

        keepAlive.update(hasWork = true, appVisible = false)
        assertEquals(1, context.starts.size)
        keepAlive.update(hasWork = true, appVisible = true)
        assertEquals(2, context.starts.size)
        keepAlive.stop()
        assertEquals(2, context.stops.size)
    }

    @Test
    fun a_visible_transition_reasserts_active_work_after_a_system_stop_or_timeout() {
        val context = RecordingContext()
        val keepAlive = AgentSessionKeepAlive(context)
        keepAlive.update(hasWork = true, appVisible = true)
        keepAlive.update(hasWork = true, appVisible = false)
        keepAlive.onForegrounded(hasWork = true)

        assertEquals(2, context.starts.size)
        keepAlive.onForegrounded(hasWork = false)
        assertEquals(1, context.stops.size)
    }

    @Test
    fun denied_starts_do_not_crash_or_restart_in_background_and_retry_when_visible() {
        for (denied in listOf(
            ForegroundServiceStartNotAllowedException("background race"),
            IllegalStateException("not currently allowed"),
            SecurityException("permission revoked"),
        )) {
            val context = RecordingContext().apply { startFailure = denied }
            val keepAlive = AgentSessionKeepAlive(context)
            keepAlive.update(hasWork = true, appVisible = true)
            assertEquals(1, context.starts.size)
            assertFalse(AgentSessionRecoveryState(context).hasPendingWork)
            context.startFailure = null

            keepAlive.update(hasWork = true, appVisible = false)
            assertEquals(1, context.starts.size)
            keepAlive.update(hasWork = true, appVisible = true)
            assertEquals(2, context.starts.size)
        }
    }

    @Test
    fun logout_ignores_a_recovery_lease_and_clears_durable_eligibility() {
        val context = RecordingContext()
        AgentSessionKeepAlive(context).update(hasWork = true, appVisible = true)
        val state = AgentSessionRecoveryState(context)
        assertNotNull(state.beginRecovery())
        val restoredOwner = AgentSessionKeepAlive(context)

        restoredOwner.update(hasWork = false, appVisible = false)
        restoredOwner.onForegrounded(hasWork = false)
        assertTrue(context.stops.isEmpty())
        assertTrue(state.hasRecoveryLease)

        restoredOwner.stop()

        assertEquals(1, context.stops.size)
        assertFalse(state.hasPendingWork)
        assertFalse(state.hasRecoveryLease)
        assertNull(state.beginRecovery())
    }

    @Test
    fun repeated_recovery_cannot_renew_the_original_lease() {
        val context = RecordingContext()
        AgentSessionKeepAlive(context).update(hasWork = true, appVisible = true)
        val state = AgentSessionRecoveryState(context)
        val initialLease = requireNotNull(state.beginRecovery())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(20))

        val remainingLease = requireNotNull(AgentSessionRecoveryState(context).beginRecovery())

        assertTrue(remainingLease <= initialLease - 20_000L)
    }

    @Test
    fun late_background_work_cannot_convert_an_expired_lease_to_durable_running_work() {
        val context = RecordingContext()
        AgentSessionKeepAlive(context).update(hasWork = true, appVisible = true)
        val state = AgentSessionRecoveryState(context)
        assertNotNull(state.beginRecovery())
        val restoredOwner = AgentSessionKeepAlive(context)
        // Advance the clock without running a Service's main-thread timeout.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61))

        restoredOwner.update(hasWork = true, appVisible = false)

        assertFalse(state.hasPendingWork)
        assertFalse(state.isRecovering)
        assertEquals(1, context.stops.size)
        assertEquals(1, context.starts.size)
    }

    @Test
    fun real_visible_work_can_make_a_new_start_after_the_recovery_lease_expired() {
        val context = RecordingContext()
        AgentSessionKeepAlive(context).update(hasWork = true, appVisible = true)
        val state = AgentSessionRecoveryState(context)
        assertNotNull(state.beginRecovery())
        val restoredOwner = AgentSessionKeepAlive(context)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61))

        restoredOwner.update(hasWork = true, appVisible = true)

        assertTrue(state.hasPendingWork)
        assertFalse(state.isRecovering)
        assertEquals(2, context.starts.size)
    }

    @Test
    fun stopping_clears_the_local_request_even_when_the_system_rejects_the_stop() {
        val context = RecordingContext()
        val keepAlive = AgentSessionKeepAlive(context)
        keepAlive.update(hasWork = true, appVisible = true)
        context.stopFailure = SecurityException("permission changed")
        keepAlive.stop()
        context.stopFailure = null
        keepAlive.update(hasWork = true, appVisible = true)

        assertEquals(2, context.starts.size)
        assertEquals(1, context.stops.size)
    }

    @Test
    fun a_rejected_refresh_does_not_forget_the_already_running_service() {
        for (denied in listOf(
            ForegroundServiceStartNotAllowedException("background race"),
            SecurityException("refresh rejected"),
        )) {
            val context = RecordingContext()
            val keepAlive = AgentSessionKeepAlive(context)
            keepAlive.update(hasWork = true, appVisible = true)
            context.startFailure = denied
            keepAlive.onForegrounded(hasWork = true)
            context.startFailure = null
            keepAlive.update(hasWork = false, appVisible = false)
            assertEquals(1, context.stops.size)

            keepAlive.update(hasWork = true, appVisible = true)
            assertEquals(3, context.starts.size)
        }
    }

    private class RecordingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val starts = mutableListOf<Intent>()
        val stops = mutableListOf<Intent>()
        var startFailure: RuntimeException? = null
        var stopFailure: RuntimeException? = null

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName {
            starts += Intent(service)
            startFailure?.let { throw it }
            return requireNotNull(service.component)
        }

        override fun stopService(service: Intent): Boolean {
            stops += Intent(service)
            stopFailure?.let { throw it }
            return true
        }
    }
}
