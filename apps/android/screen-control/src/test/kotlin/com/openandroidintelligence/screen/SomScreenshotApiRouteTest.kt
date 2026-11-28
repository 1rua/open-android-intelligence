package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.*
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
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityWindowInfo
import org.robolectric.util.ReflectionHelpers

/** 系统只回错误，不伪造硬件帧；验证生产壳在各 API 选择的真实系统入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34], shadows = [SomScreenshotApiRouteTest.RouteShadow::class,
    SomScreenshotApiRouteTest.WindowShadow::class])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SomScreenshotApiRouteTest {
    @Before fun prepare() { Dispatchers.setMain(UnconfinedTestDispatcher()); displayCalls = 0; windowCalls = 0 }
    @After fun restore() {
        windowsForRead = null
        activeRootFallback = null
        PhoneLocalControlUiRegistry.current = null
        Dispatchers.resetMain()
    }
    @Implements(AccessibilityService::class)
    class RouteShadow {
        @Implementation protected fun getWindows(): List<AccessibilityWindowInfo> = windowsForRead?.invoke().orEmpty()
        @Implementation protected fun getRootInActiveWindow(): AccessibilityNodeInfo? = activeRootFallback?.invoke()
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

    @Implements(AccessibilityWindowInfo::class)
    class WindowShadow : ShadowAccessibilityWindowInfo() {
        @RealObject private lateinit var window: AccessibilityWindowInfo
        private var recycled = false

        @Implementation override fun getBoundsInScreen(outBounds: Rect) = super.getBoundsInScreen(outBounds)

        @Implementation override fun recycle() {
            // 合成窗口不进入系统对象池，防止其他测试取得仍绑定本影子的窗口。
            assertFalse("合成窗口不能重复回收", recycled)
            recycled = true
        }

        @Implementation override fun getRoot(): AccessibilityNodeInfo? {
            if (window.id == UNREADABLE_WINDOW) throw IllegalStateException("合成根读取失败")
            return super.getRoot()
        }
    }

    @Test @Config(sdk = [31, 32, 33]) fun 缺失的业务窗根阻止整屏取帧() = runTest {
        assertRejectedBeforeCapture { listOf(window(17, targetRoot()), window(18)) }
    }

    @Test @Config(sdk = [31, 32, 33]) fun 不可读的业务窗根阻止整屏取帧() = runTest {
        assertRejectedBeforeCapture { listOf(window(17, targetRoot()), window(UNREADABLE_WINDOW, targetRoot())) }
    }

    @Test @Config(sdk = [33]) fun 活动根回退不能证明整屏窗口完整() = runTest {
        activeRootFallback = { targetRoot() }
        assertRejectedBeforeCapture { emptyList() }
    }

    @Test @Config(sdk = [31, 32, 33]) fun 已声明却取不到的后代阻止整屏取帧() = runTest {
        assertRejectedBeforeCapture {
            val root = targetRoot()
            val parent = targetRoot()
            // 默认节点影子按列表长度报告子节点数，空槽模拟系统已声明却取不到的后代。
            ReflectionHelpers.setField(Shadow.extract<ShadowAccessibilityNodeInfo>(parent), "children",
                listOf<AccessibilityNodeInfo?>(null))
            Shadow.extract<ShadowAccessibilityNodeInfo>(root).addChild(parent)
            listOf(window(17, root))
        }
    }

    @Test @Config(sdk = [31, 32, 33]) fun 无根浮层只有同时满足归属和几何证明才允许整屏取帧() = runTest {
        val overlayType = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        withService { service ->
            windowsForRead = { listOf(window(17, targetRoot()), window(18, type = overlayType, bounds = Rect(10, 5, 30, 25))) }
            assertEquals("SCREEN_REDACTION_UNPROVABLE", captureFailure(service))
            assertEquals(0, displayCalls)

            ScreenAccessibilityConnection.overlayDetached(18)
            windowsForRead = { listOf(window(17, targetRoot()), window(18, type = overlayType, bounds = Rect(10, 5, 30, 65))) }
            assertEquals("SCREEN_REDACTION_UNPROVABLE", captureFailure(service))
            assertEquals(0, displayCalls)

            windowsForRead = { listOf(window(17, targetRoot()), window(18, type = overlayType, bounds = Rect(10, 5, 30, 25))) }
            assertEquals("SCREEN_CAPTURE_FAILED", captureFailure(service))
            assertEquals(1, displayCalls)
            assertEquals(0, windowCalls)
        }
    }

    @Test @Config(sdk = [34]) fun 其他窗口无根时仍向系统请求指定业务窗截图() = runTest {
        withService { service ->
            PhoneLocalControlUiRegistry.current = null
            windowsForRead = { listOf(window(17, targetRoot()), window(18)) }
            assertEquals("SCREEN_CAPTURE_FAILED", captureFailure(service))
            assertEquals(0, displayCalls)
            assertEquals(1, windowCalls)
        }
    }

    @Test @Config(sdk = [33]) fun 整屏取帧后根节点丢失时回收位图并拒绝交付() = runTest {
        withService { service ->
            windowsForRead = { listOf(window(17, targetRoot())) }
            lateinit var bitmap: Bitmap
            val port = NativePhoneScreen(service, AndroidScreenBackend(runtime(service)), SomFrameSource { snapshot ->
                windowsForRead = { listOf(window(17, targetRoot()), window(18)) }
                val display = snapshot.display
                bitmap = Bitmap.createBitmap(display.widthPixels, display.heightPixels, Bitmap.Config.ARGB_8888)
                SomFrame(bitmap, SystemClock.uptimeMillis(), display,
                    ScreenBounds(0, 0, display.widthPixels, display.heightPixels), false)
            })
            assertEquals("SCREEN_REDACTION_UNPROVABLE", captureFailure(port))
            assertTrue(bitmap.isRecycled)
        }
    }

    @Test @Config(sdk = [33]) fun 原始帧代次变化即使操控修订不变也拒绝交付() = runTest {
        withService { service ->
            windowsForRead = { listOf(window(17, targetRoot())) }
            lateinit var bitmap: Bitmap
            val port = NativePhoneScreen(service, AndroidScreenBackend(runtime(service)), SomFrameSource { snapshot ->
                ScreenAccessibilityConnection.changed(deferControlCheck = true)
                val display = snapshot.display
                bitmap = Bitmap.createBitmap(display.widthPixels, display.heightPixels, Bitmap.Config.ARGB_8888)
                SomFrame(bitmap, SystemClock.uptimeMillis(), display,
                    ScreenBounds(0, 0, display.widthPixels, display.heightPixels), false)
            })
            assertEquals("SNAPSHOT_STALE", captureFailure(port))
            assertTrue(bitmap.isRecycled)
        }
    }

    private suspend fun assertRejectedBeforeCapture(windows: () -> List<AccessibilityWindowInfo>) = withService { service ->
        windowsForRead = windows
        var dispatches = 0
        assertEquals("SCREEN_REDACTION_UNPROVABLE", captureFailure(service) { dispatches++ })
        assertEquals(0, dispatches)
        assertEquals(0, displayCalls)
        assertEquals(0, windowCalls)
    }

    private suspend fun captureFailure(service: ScreenAccessibilityService, beforeDispatch: () -> Unit = {}): String =
        captureFailure(NativePhoneScreen(service, AndroidScreenBackend(runtime(service)), AndroidSomFrameSource(service)), beforeDispatch)

    private suspend fun captureFailure(port: NativePhoneScreen, beforeDispatch: () -> Unit = {}): String {
        val access = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://som.example"),
            "account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)),
            1, "connection", 1)
        val request = PhoneExecutionContext(ControlRequestScope(ControlTaskScope(access.principal, "task", 1, "process"),
            "som-capture-request-01"), access, "sha256:" + "c".repeat(64), beforeDispatch, {})
        try {
            port.execute(request, ControlOperation.CaptureSom, {}, { true })
        } catch (failure: ControlRequestException) {
            return failure.code
        }
        error("预期截图请求失败")
    }

    @Suppress("DEPRECATION")
    private fun runtime(service: ScreenAccessibilityService): AndroidAccessibilityRuntime {
        val manager = object : WindowManager by service.getSystemService(WindowManager::class.java) {
            override fun getMaximumWindowMetrics() = WindowMetrics(Rect(0, 0, 100, 200), WindowInsets.Builder().build())
        }
        val context = object : ContextWrapper(service) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }
        return AndroidAccessibilityRuntime(context)
    }

    private suspend fun withService(block: suspend (ScreenAccessibilityService) -> Unit) {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        ScreenAccessibilityConnection.connect(service)
        try {
            val display = runtime(service).display()
            PhoneLocalControlUiRegistry.current = object : PhoneLocalControlUi {
                override val protectedBounds = ScreenBounds(0, 0, display.widthPixels, 40)
                override fun captureProtection() = LocalUiCaptureProtection(1, display, 0, listOf(protectedBounds), true)
                override suspend fun confirm(requestId: String, capability: String, localParameters: String?) = error("无远端确认入口")
                override suspend fun executing(requestId: String) = Unit
                override suspend fun finish(requestId: String) = Unit
                override fun emergencyStop() = Unit
            }
            block(service)
        } finally { ScreenAccessibilityConnection.disconnect(service) }
    }

    @Suppress("DEPRECATION")
    private fun targetRoot() = AccessibilityNodeInfo.obtain().apply {
        packageName = "org.example.synthetic"
        isVisibleToUser = true
        setBoundsInScreen(Rect(0, 40, 100, 200))
    }

    private fun window(id: Int, root: AccessibilityNodeInfo? = null, type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        bounds: Rect = Rect(0, 40, 100, 200)): AccessibilityWindowInfo =
        ReflectionHelpers.callConstructor(AccessibilityWindowInfo::class.java).also {
            Shadow.extract<WindowShadow>(it).apply {
                setId(id)
                setType(type)
                setActive(id == 17)
                setFocused(id == 17)
                setBoundsInScreen(bounds)
                if (root != null) setRoot(root)
            }
            assertEquals(bounds, Rect().also(it::getBoundsInScreen))
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

    @Test @Config(sdk = [34]) fun 精确目标未知时在系统取帧前拒绝截图() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        ScreenAccessibilityConnection.connect(service)
        try {
            val snapshot = ScreenSnapshot("revision", "hash", null, ScreenDisplay(100, 200, 0), null, null,
                listOf(ScreenWindow(17, 1, 1, false, true, ScreenBounds(0, 0, 100, 200))), emptyList(), false)
            val failure = runCatching { AndroidSomFrameSource(service).capture(snapshot) }.exceptionOrNull()
            assertEquals(0, displayCalls)
            assertEquals(0, windowCalls)
            assertEquals("SCREEN_WINDOW_UNAVAILABLE", (failure as? ControlRequestException)?.code)
        } finally { ScreenAccessibilityConnection.disconnect(service) }
    }
    companion object {
        private const val UNREADABLE_WINDOW = 19
        private var windowsForRead: (() -> List<AccessibilityWindowInfo>)? = null
        private var activeRootFallback: (() -> AccessibilityNodeInfo)? = null
        private var displayCalls = 0
        private var windowCalls = 0
    }
}
