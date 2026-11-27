package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

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
    fun duplicate_availability_and_validation_changes_do_not_restart_a_healthy_route() {
        lateinit var callback: ConnectivityManager.NetworkCallback
        var available = 0
        var changed = 0
        val monitor = GatewayNetworkMonitor(
            onAvailable = { available++ }, register = { callback = it },
            onRouteChanged = { changed++ },
        )
        monitor.start()
        val network = ShadowNetwork.newInstance(10)
        val localWifi = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, localWifi)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = true))
        callback.onCapabilitiesChanged(network, localWifi)
        assertEquals(1, available)
        assertEquals(0, changed)
    }

    @Test
    fun late_loss_and_capabilities_of_an_old_network_do_not_override_the_new_route() {
        lateinit var callback: ConnectivityManager.NetworkCallback
        var available = 0
        var changed = 0
        var lost = 0
        GatewayNetworkMonitor(
            onAvailable = { available++ }, register = { callback = it },
            onLost = { lost++ }, onRouteChanged = { changed++ },
        ).start()
        val old = ShadowNetwork.newInstance(11)
        val current = ShadowNetwork.newInstance(12)
        callback.onAvailable(old)
        callback.onAvailable(current)
        callback.onLost(old)
        callback.onCapabilitiesChanged(old, NetworkCapabilities())
        callback.onLost(current)
        callback.onLost(current)
        callback.onAvailable(current)
        assertEquals(1, available)
        assertEquals(2, changed)
        assertEquals(1, lost)
    }

    @Test
    fun same_network_suspension_recovery_and_underlying_transport_changes_wake_once() {
        lateinit var callback: ConnectivityManager.NetworkCallback
        var changed = 0
        GatewayNetworkMonitor(onAvailable = {}, register = { callback = it }, onRouteChanged = { changed++ }).start()
        val network = ShadowNetwork.newInstance(13)
        val wifi = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, wifi)
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, suspended = true))
        callback.onCapabilitiesChanged(network, wifi)
        callback.onCapabilitiesChanged(network, wifi)
        val mobile = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)
        callback.onCapabilitiesChanged(network, mobile)
        callback.onCapabilitiesChanged(network, mobile)
        assertEquals(2, changed)
    }

    @Test
    fun initial_link_properties_do_not_reconnect_but_a_new_route_does() {
        lateinit var callback: ConnectivityManager.NetworkCallback
        var changed = 0
        GatewayNetworkMonitor(onAvailable = {}, register = { callback = it }, onRouteChanged = { changed++ }).start()
        val network = ShadowNetwork.newInstance(14)
        callback.onAvailable(network)
        val wifi = LinkProperties().apply { interfaceName = "wlan0" }
        callback.onLinkPropertiesChanged(network, wifi)
        callback.onLinkPropertiesChanged(network, LinkProperties().apply { interfaceName = "wlan0" })
        callback.onLinkPropertiesChanged(network, LinkProperties().apply { interfaceName = "rmnet_data0" })
        assertEquals(1, changed)
    }

    private fun capabilities(transport: Int, suspended: Boolean = false, validated: Boolean = false): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            val shadow = shadowOf(it)
            shadow.addTransportType(transport)
            if (suspended) shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            else shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            if (validated) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            else shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
