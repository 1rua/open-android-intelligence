package com.openandroidintelligence.tailnet.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Shared on-device helpers for the P0t connected androidTest suite.
 *
 * Fail-closed provisioning: the ALTSNET1 blob is generated host-side by the
 * pinned Go `cmd/enrollment-bundle` (authoritative encoder) and pushed to the
 * device by `p0t-device/provision-failclosed-bundle.sh`. The path is passed
 * through the `p0tFailClosedBundle` instrumentation argument so the device
 * test consumes byte-identical authoritative provisioning input.
 */
object P0tProvisioning {
    const val ARG_FAIL_CLOSED_BUNDLE = "p0tFailClosedBundle"

    fun failClosedBundleBytes(): ByteArray? {
        val path = InstrumentationRegistry.getArguments().getString(ARG_FAIL_CLOSED_BUNDLE) ?: return null
        val file = File(path)
        return if (file.isFile) file.readBytes() else null
    }
}

/** In-process system VPN surface probe: counts active TRANSPORT_VPN networks. */
object P0tVpnAudit {
    fun vpnNetworkCount(): Int {
        val cm = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.count { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }
}

/** Runs a non-blocking suspend block to completion on the current thread. */
fun runSuspend(block: suspend () -> Unit) {
    var throwable: Throwable? = null
    var resumed = false
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {
            throwable = result.exceptionOrNull()
            resumed = true
        }
    })
    if (!resumed) error("suspend block did not complete synchronously")
    throwable?.let { throw it }
}
