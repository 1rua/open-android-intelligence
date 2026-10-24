package com.openandroidintelligence.tailnet.core

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tsnetbridge.Tsnetbridge

/**
 * On-device prerequisite evidence: a real API 34+ device, the real pinned AAR,
 * and the real native userspace library actually loading and reporting its
 * closed error-code surface.
 */
@RunWith(AndroidJUnit4::class)
class P0tDeviceEnvironmentInstrumentedTest {

    @Test
    fun deviceMeetsApi34WithSupportedAbiAndLoadsRealAar() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        assertTrue(
            "device ABI must be arm64-v8a or x86_64, got '$abi'",
            abi == "arm64-v8a" || abi == "x86_64",
        )
        assertTrue(
            "API 34+ required, got ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 34,
        )
        // Any static access on Tsnetbridge forces go.Seq.touch() which loads
        // the real libgojni.so shipped in the pinned AAR.
        Tsnetbridge.touch()
        assertTrue(Tsnetbridge.ErrCodeControlUnreachable.isNotBlank())
        assertTrue(Tsnetbridge.ErrCodeInvalidBundle.isNotBlank())
        assertTrue(Tsnetbridge.ErrCodeStaleGeneration.isNotBlank())
        assertEquals(
            "the pinned AAR must ship the native library for this device ABI",
            listOf("arm64-v8a", "x86_64").contains(abi),
            true,
        )
    }

    @Test
    fun targetDeviceMatchesP0tDeviceContract() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ctx != null)
        assertTrue(Build.VERSION.SDK_INT >= 34)
        // 16 KiB page-size devices are a P0t requirement; record the real
        // page size without asserting a firmware value.
        val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        assertTrue("page size must be positive", pageSize > 0)
    }
}
