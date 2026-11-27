package com.openandroidintelligence.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.kernel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 真实 Overlay 和 NativeKernel 调用链，仅系统窗口提交与像素输入使用受控替身。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class SomOverlayKernelLayoutTest {
    @Before fun prepare() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restore() {
        PhoneLocalControlUiRegistry.current = null; NativePhoneScreenRegistry.current = null
        Dispatchers.resetMain()
    }
    private class Window(private val real: WindowManager) : WindowManager by real {
        var added: View? = null
        override fun getMaximumWindowMetrics() = android.view.WindowMetrics(Rect(0, 0, 400, 800), WindowInsets.Builder().build())
        override fun addView(view: View, params: ViewGroup.LayoutParams) { view.layoutParams = params; added = view }
        override fun removeViewImmediate(view: View) { if (added === view) added = null }
        fun deliverLayout() {
            val view = requireNotNull(added)
            view.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 180, 100)
        }
    }
    private class Fixture : AutoCloseable {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val window = Window(service.getSystemService(WindowManager::class.java))
        val overlay = PhoneControlOverlay(service, window)
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val access = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://layout.example"),
            "account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "connection", 1)
        val binding = RemoteCapabilityBinding(access) { true }
        val connection = grants.attachRemoteConnection(binding)
        val request = PhoneExecutionContext(ControlRequestScope(ControlTaskScope(access.principal, "layout-task", 1, "process"),
            "layout-capture-request-01"), access, "sha256:" + "c".repeat(64), {}, {})
        var captures = 0
        var returned: PhoneControlResult? = null
        val backend = object : ScreenBackend {
            override fun availability(): String? = null
            override fun read(query: ScreenReadQuery) = ScreenSnapshot("stable", "hash", null, ScreenDisplay(400, 800, 0),
                "target", 1, listOf(ScreenWindow(1, 1, 1, true, true, ScreenBounds(0, 0, 400, 800))), emptyList(), false)
            override suspend fun execute(expectedRevision: String, action: ScreenAction, beforeDispatch: () -> Unit) = error("不执行动作")
        }
        val provider = NativePhoneScreen(service, backend, SomFrameSource { snapshot ->
            captures++
            SomFrame(Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) },
                SystemClock.uptimeMillis(), snapshot.display, ScreenBounds(0, 0, 400, 800), android.os.Build.VERSION.SDK_INT >= 34)
        })
        val primitives = setOf("kernel.screen.read", "kernel.screen.capture")
        val kernel = NativePhoneControlKernel(grants, grants.authorization, { primitives }, { primitives },
            { CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY) }, { false })
        init { PhoneLocalControlUiRegistry.current = overlay; NativePhoneScreenRegistry.current = provider }
        suspend fun capture() = kernel.execute(request, binding, ControlOperation.CaptureSom).also { returned = it }
        override fun close() { overlay.close(); connection.close() }
    }
    @Test fun freshExecutingWindowWaitsForActualLayoutBeforeCapturingOnlyOnLegacyApis() = runTest {
        Fixture().use { fixture ->
            val running = async { fixture.capture() }; runCurrent()
            if (android.os.Build.VERSION.SDK_INT < 34) {
                assertEquals(0, fixture.captures); assertNull(fixture.returned); assertFalse(running.isCompleted)
                fixture.window.deliverLayout(); runCurrent()
            }
            val result = running.await()
            assertEquals(1, fixture.captures)
            val bytes = result.artifacts.single().bytes
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals(400, decoded.width); assertEquals(800, decoded.height)
                if (android.os.Build.VERSION.SDK_INT < 34) assertEquals(Color.BLACK, decoded.getPixel(20, 20))
            } finally { decoded.recycle() }
            assertNull(fixture.overlay.protectedBounds)
        }
    }
    @Test fun waitingCancellationEmergencyAndOwnerReplacementReturnNoPixelsAndReleaseWindow() = runTest {
        if (android.os.Build.VERSION.SDK_INT >= 34) return@runTest
        for (reason in listOf("cancel", "stop", "owner")) {
            Fixture().use { fixture ->
                val running = async { runCatching { fixture.capture() } }; runCurrent()
                assertEquals(0, fixture.captures); assertFalse(running.isCompleted)
                when (reason) {
                    "cancel" -> running.cancel()
                    "stop" -> fixture.overlay.emergencyStop()
                    else -> PhoneLocalControlUiRegistry.current = null
                }
                runCurrent()
                val result = runCatching { running.await() }
                assertTrue(result.isFailure || result.getOrNull()?.isFailure == true)
                assertEquals(0, fixture.captures); assertNull(fixture.returned)
                assertNull(fixture.overlay.protectedBounds)
                // 原请求结束后再布局不能触发残留等待器或开始捕获。
                fixture.window.added?.let { fixture.window.deliverLayout() }; runCurrent()
                assertEquals(0, fixture.captures)
            }
        }
    }
    @Test fun firstLayoutDeadlineFailsClosedAndReadOperationDoesNotWaitForLayout() = runTest {
        Fixture().use { fixture ->
            if (android.os.Build.VERSION.SDK_INT < 34) {
                val running = async { runCatching { fixture.capture() } }; runCurrent()
                advanceTimeBy(PhoneControlOverlay.CAPTURE_LAYOUT_TIMEOUT_MS + 1); runCurrent()
                val failure = running.await().exceptionOrNull() as ControlRequestException
                assertEquals("SCREEN_LOCAL_UI_LAYOUT_TIMEOUT", failure.code)
                assertEquals(0, fixture.captures); assertNull(fixture.returned)
                assertNull(fixture.overlay.protectedBounds)
            }
            val read = fixture.kernel.execute(fixture.request, fixture.binding, ControlOperation.DumpTree(ScreenReadQuery()))
            assertNotNull(read.result.values["snapshotId"])
        }
    }
}
