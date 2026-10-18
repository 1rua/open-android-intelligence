package com.openandroidintelligence.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Tracks Android's default route, including Wi-Fi/cellular switches. */
internal class GatewayNetworkMonitor(
    private val onAvailable: () -> Unit,
    private val register: (ConnectivityManager.NetworkCallback) -> Unit,
) {
    constructor(context: Context, onAvailable: () -> Unit) : this(
        onAvailable,
        { callback -> context.applicationContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) },
    )

    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Availability wakes the existing connection owner; it never starts
            // an Android service or creates a second independent connection.
            this@GatewayNetworkMonitor.onAvailable()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        try {
            register(callback)
            registered = true
        } catch (_: SecurityException) {
            // A future visible lifecycle transition may retry registration.
        } catch (_: IllegalStateException) {
            // Process/ConnectivityService startup races must not prevent login.
        }
    }
}
