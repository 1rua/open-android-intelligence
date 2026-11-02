package com.openandroidintelligence.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import java.net.InetAddress

/** Reports changes of the default route to the process-owned connection owner. */
internal class GatewayNetworkMonitor(
    private val onAvailable: () -> Unit,
    private val register: (ConnectivityManager.NetworkCallback) -> Unit,
    private val onLost: () -> Unit = {},
    private val onRouteChanged: () -> Unit = onAvailable,
) {
    constructor(context: Context, onAvailable: () -> Unit) : this(
        context, onAvailable, {}, onAvailable,
    )

    constructor(
        context: Context,
        onAvailable: () -> Unit,
        onLost: () -> Unit,
        onRouteChanged: () -> Unit,
    ) : this(
        onAvailable,
        { callback -> context.applicationContext.getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(callback) },
        onLost,
        onRouteChanged,
    )

    private var registered = false
    private val routeLock = Any()
    private var currentNetwork: Network? = null
    private var hasObservedNetwork = false
    private var transports: Set<Int>? = null
    private var suspended: Boolean? = null
    private var route: Route? = null

    private data class Route(
        val interfaceName: String?,
        val addresses: Set<LinkAddress>,
        val routes: Set<RouteInfo>,
        val dns: Set<InetAddress>,
    )

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = synchronized(routeLock) {
                if (currentNetwork == network) return
                val previouslyObserved = hasObservedNetwork
                currentNetwork = network
                hasObservedNetwork = true
                transports = null
                suspended = null
                route = null
                previouslyObserved
            }
            if (changed) onRouteChanged() else this@GatewayNetworkMonitor.onAvailable()
        }

        override fun onLost(network: Network) {
            synchronized(routeLock) {
                if (currentNetwork != network) return
                currentNetwork = null
                transports = null
                suspended = null
                route = null
            }
            this@GatewayNetworkMonitor.onLost()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val changed = synchronized(routeLock) {
                if (currentNetwork != network) return
                val nextTransports = setOf(
                    NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_WIFI,
                    NetworkCapabilities.TRANSPORT_BLUETOOTH, NetworkCapabilities.TRANSPORT_ETHERNET,
                    NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI_AWARE,
                    NetworkCapabilities.TRANSPORT_LOWPAN, NetworkCapabilities.TRANSPORT_USB,
                ).filterTo(mutableSetOf()) { capabilities.hasTransport(it) }
                val nextSuspended = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                val changed = transports?.let { it != nextTransports } == true || suspended == true && !nextSuspended
                transports = nextTransports
                suspended = nextSuspended
                changed
            }
            // Public Internet validation is neither a prerequisite for a LAN
            // Core nor evidence that its existing socket needs replacing.
            if (changed) onRouteChanged()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val changed = synchronized(routeLock) {
                if (currentNetwork != network) return
                val next = Route(properties.interfaceName, properties.linkAddresses.toSet(),
                    properties.routes.toSet(), properties.dnsServers.toSet())
                val changed = route?.let { it != next } == true
                route = next
                changed
            }
            if (changed) onRouteChanged()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        try {
            register(callback)
            registered = true
        } catch (_: SecurityException) {
            // A later visible lifecycle transition may retry registration.
        } catch (_: IllegalStateException) {
            // ConnectivityService startup races must not prevent login.
        }
    }
}
