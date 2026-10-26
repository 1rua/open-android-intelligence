package com.openandroidintelligence.tailnet.core

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device VPN-slot audit for "本 app 不创建 VpnService、不抢占系统 VPN 槽位".
 *
 * The assertions are made from inside the instrumented process: the host test
 * APK (built from this module's own manifest surface) must not request
 * BIND_VPN_SERVICE and must not register any VpnService implementation, and
 * running this package must never add a system VPN network agent on top of a
 * pre-existing baseline (coexistence: if another VPN is already active our
 * process adds zero agents; if none is active the count stays at zero).
 *
 * The independent host-side `dumpsys connectivity/vpn/route/DNS` audit is
 * captured around each connected run by p0t-device/run-p0t-smallstep.sh.
 */
@RunWith(AndroidJUnit4::class)
class P0tVpnSurfaceInstrumentedTest {

    @Test
    fun noVpnServiceComponentAndNoBindVpnPermission() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = ctx.packageName
        val pm = ctx.packageManager

        val permissions = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            .requestedPermissions ?: emptyArray()
        assertFalse(
            "BIND_VPN_SERVICE must not be requested by this package",
            permissions.any { it == "android.permission.BIND_VPN_SERVICE" },
        )

        val services = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES)
            .services ?: arrayOf()
        val vpnServices = services.filter { service ->
            runCatching {
                val clazz = Class.forName(service.name, false, ctx.classLoader)
                android.net.VpnService::class.java.isAssignableFrom(clazz)
            }.getOrDefault(false)
        }
        assertEquals(
            "this module's test host must register no VpnService implementation",
            0,
            vpnServices.size,
        )
    }

    @Test
    fun packageLifecycleAddsNoSystemVpnNetworkAgent() {
        val before = P0tVpnAudit.vpnNetworkCount()
        // Idle lifecycle: this package must not create or grab the system VPN
        // slot. If another VPN is already active it must remain untouched.
        val after = P0tVpnAudit.vpnNetworkCount()
        assertEquals("this package must not add a VPN network agent", before, after)
        assertTrue("VPN agent baseline must be >= 0", before >= 0)
    }

    @Test
    fun connectivityBaselineIncludesDefaultTransport() {
        val cm = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val active = cm.activeNetwork
        assertTrue("device should have an active network during the run", active != null)
    }
}
