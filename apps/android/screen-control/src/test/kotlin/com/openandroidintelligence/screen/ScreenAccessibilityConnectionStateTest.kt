package com.openandroidintelligence.screen

import com.openandroidintelligence.control.ScreenHostRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenAccessibilityConnectionStateTest {

    @Before
    fun resetBefore() {
        ScreenHostRegistry.setAccessibilityConnected(false)
    }

    @After
    fun resetAfter() {
        ScreenAccessibilityConnection.service?.let { ScreenAccessibilityConnection.disconnect(it) }
        ScreenHostRegistry.setAccessibilityConnected(false)
    }

    @Test
    fun connectAndDisconnectUpdateScreenHostRegistryAccessibilityFlow() {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        assertFalse(ScreenHostRegistry.accessibilityConnected.value)

        ScreenAccessibilityConnection.connect(service)
        assertTrue(ScreenHostRegistry.accessibilityConnected.value)

        ScreenAccessibilityConnection.disconnect(service)
        assertFalse(ScreenHostRegistry.accessibilityConnected.value)
    }

    @Test
    fun staleServiceDisconnectDoesNotClearNewerConnectedService() {
        val oldService = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val newService = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()

        ScreenAccessibilityConnection.connect(oldService)
        ScreenAccessibilityConnection.connect(newService)
        assertTrue(ScreenHostRegistry.accessibilityConnected.value)

        ScreenAccessibilityConnection.disconnect(oldService)
        assertTrue("旧服务实例迟到的断开事件不得清空新实例连接态", ScreenHostRegistry.accessibilityConnected.value)

        ScreenAccessibilityConnection.disconnect(newService)
        assertFalse(ScreenHostRegistry.accessibilityConnected.value)
    }

    @Test
    fun clearingAccessibilityConnectedImmediatelyDisconnectsUnderlyingRuntimeBeforeOnUnbind() {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val runtime = AndroidAccessibilityRuntime(service)
        val screenshotRuntime = AndroidAccessibilityScreenshotRuntime(service)

        ScreenAccessibilityConnection.connect(service)
        assertTrue(screenshotRuntime.isServiceConnected())
        org.junit.Assert.assertNull(runtime.availability())

        // 模拟用户在系统设置关闭无障碍后切回 App（ON_RESUME 校准连接态为 false，此时系统 onUnbind 尚未送达）。
        ScreenHostRegistry.setAccessibilityConnected(false)
        assertFalse(screenshotRuntime.isServiceConnected())
        org.junit.Assert.assertEquals("ACCESSIBILITY_SERVICE_UNAVAILABLE", runtime.availability())

        ScreenAccessibilityConnection.disconnect(service)
    }
}
