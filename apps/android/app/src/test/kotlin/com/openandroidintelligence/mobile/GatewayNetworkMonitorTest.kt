package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GatewayNetworkMonitorTest {
    @Test
    fun foreground_transitions_register_once_and_network_switches_wake_the_existing_owner() {
        val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
        var wakeups = 0
        val monitor = GatewayNetworkMonitor(onAvailable = { wakeups++ }, register = { callbacks += it })

        monitor.start()
        monitor.start()
        assertEquals(1, callbacks.size)
        assertEquals(0, wakeups)

        callbacks.single().onAvailable(ShadowNetwork.newInstance(1))
        callbacks.single().onLost(ShadowNetwork.newInstance(1))
        callbacks.single().onAvailable(ShadowNetwork.newInstance(2))

        assertEquals(2, wakeups)
        assertEquals(1, callbacks.size)
    }

    @Test
    fun rejected_registration_can_retry_on_a_later_visible_transition() {
        for (failure in listOf(SecurityException("permission changed"), IllegalStateException("service unavailable"))) {
            var attempts = 0
            var wakeups = 0
            val monitor = GatewayNetworkMonitor(onAvailable = { wakeups++ }, register = {
                attempts++
                if (attempts == 1) throw failure
            })

            monitor.start()
            monitor.start()
            monitor.start()

            assertEquals(2, attempts)
            assertEquals(0, wakeups)
        }
    }

    @Test
    fun production_registration_uses_the_declared_network_state_permission() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.ACCESS_NETWORK_STATE))

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val monitor = GatewayNetworkMonitor(context) {}
        monitor.start()
        monitor.start()

        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)
    }
}
