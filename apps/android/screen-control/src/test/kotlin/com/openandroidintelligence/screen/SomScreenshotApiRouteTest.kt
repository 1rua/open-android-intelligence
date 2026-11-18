package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.ControlRequestException
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/** 系统只回错误，不伪造硬件帧；验证生产壳在各 API 选择的真实系统入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34], shadows = [SomScreenshotApiRouteTest.RouteShadow::class])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SomScreenshotApiRouteTest {
    @Before fun prepare() { Dispatchers.setMain(UnconfinedTestDispatcher()); displayCalls = 0; windowCalls = 0 }
    @After fun restore() { Dispatchers.resetMain() }
    @Implements(AccessibilityService::class)
    class RouteShadow {
        @Implementation protected fun getServiceInfo(): AccessibilityServiceInfo = AccessibilityServiceInfo().also {
            ReflectionHelpers.setField(it, "mCapabilities", AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT)
        }
        @Implementation(minSdk = 30) protected fun takeScreenshot(displayId: Int, executor: Executor,
            callback: AccessibilityService.TakeScreenshotCallback) {
            displayCalls++; executor.execute { callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) }
        }
        @Implementation(minSdk = 34) protected fun takeScreenshotOfWindow(windowId: Int, executor: Executor,
            callback: AccessibilityService.TakeScreenshotCallback) {
            assertEquals(17, windowId)
            windowCalls++; executor.execute { callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) }
        }
    }
    @Test fun api31To33UsesDisplayAndApi34KeepsOverlayExcludingWindowCapture() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        ScreenAccessibilityConnection.connect(service)
        try {
            val snapshot = ScreenSnapshot("revision", "hash", null, ScreenDisplay(100, 200, 0), "target", 17,
                listOf(ScreenWindow(17, 1, 1, true, true, ScreenBounds(0, 10, 100, 190))), emptyList(), false)
            val failure = runCatching { AndroidSomFrameSource(service).capture(snapshot) }.exceptionOrNull()
            assertTrue(failure is ControlRequestException)
            assertEquals(if (android.os.Build.VERSION.SDK_INT < 34) 1 else 0, displayCalls)
            assertEquals(if (android.os.Build.VERSION.SDK_INT >= 34) 1 else 0, windowCalls)
        } finally { ScreenAccessibilityConnection.disconnect(service) }
    }
    companion object {
        private var displayCalls = 0
        private var windowCalls = 0
    }
}
