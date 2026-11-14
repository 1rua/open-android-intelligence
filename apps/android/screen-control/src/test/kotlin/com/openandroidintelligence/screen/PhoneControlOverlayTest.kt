package com.openandroidintelligence.screen

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.openandroidintelligence.control.remote.PhoneLocalControlUiRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneControlOverlayTest {
    @Before fun prepare() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restore() { PhoneLocalControlUiRegistry.current = null; Dispatchers.resetMain() }
    private fun view(overlay: PhoneControlOverlay): LinearLayout = ReflectionHelpers.getField<View>(overlay, "view") as LinearLayout
    private fun press(button: Button) {
        button.layout(0, 0, 300, 100)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 1, action, 10f, 10f, 1f, 1f, 0, 1f, 1f, 1, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            button.dispatchTouchEvent(event); event.recycle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    @Test fun syntheticClickCannotApproveAndAnotherRequestCannotReplaceOwnedConfirmation() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val overlay = PhoneControlOverlay(service)
        try {
            val first = async { overlay.confirm("owner-first", "ui.tap") }; runCurrent()
            val firstView = view(overlay); val allow = firstView.getChildAt(1) as Button
            assertNotNull(overlay.protectedBounds)
            val params = firstView.layoutParams as android.view.WindowManager.LayoutParams
            assertEquals(android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
            assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            allow.performClick(); runCurrent(); assertFalse(first.isCompleted)
            val second = async { overlay.confirm("owner-second", "ui.tap") }; runCurrent()
            assertSame(firstView, view(overlay)); assertFalse(second.isCompleted)
            press(allow); runCurrent(); assertTrue(first.await()); assertFalse(second.isCompleted)
            overlay.finish("owner-first"); runCurrent(); assertNotSame(firstView, view(overlay))
            overlay.emergencyStop(); runCurrent(); assertFalse(second.await())
        } finally { overlay.close() }
    }
    @Test fun emergencyStopNotifiesNativeOwnersAndFailsFutureConfirmationClosed() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val overlay = PhoneControlOverlay(service); var stops = 0
        val registration = PhoneLocalControlUiRegistry.observeEmergencyStop { stops++ }
        try {
            val waiting = async { overlay.confirm("owner-stop", "ui.input_text") }; runCurrent()
            overlay.emergencyStop(); runCurrent()
            assertFalse(waiting.await()); assertEquals(1, stops)
            assertFalse(overlay.confirm("owner-later", "ui.tap"))
        } finally { registration.close(); overlay.close() }
    }
    @Test fun failedExecutingWindowInstallationReleasesOnlyItsOwnConfirmationOwner() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val real = service.getSystemService(android.view.WindowManager::class.java)
        var installs = 0
        val window = object : android.view.WindowManager by real {
            override fun addView(view: View, params: android.view.ViewGroup.LayoutParams) {
                if (++installs == 2) throw android.view.WindowManager.BadTokenException("服务已撤销")
                real.addView(view, params)
            }
        }
        val overlay = PhoneControlOverlay(service, window)
        try {
            val first = async { runCatching { overlay.confirm("owner-failed", "ui.tap") } }; runCurrent()
            press(view(overlay).getChildAt(1) as Button); runCurrent()
            assertTrue(first.await().isFailure)
            val second = async { overlay.confirm("owner-after-failure", "ui.tap") }; runCurrent()
            assertNotNull(view(overlay)); assertFalse(second.isCompleted)
            overlay.emergencyStop(); runCurrent(); assertFalse(second.await())
        } finally { overlay.close() }
    }
}
