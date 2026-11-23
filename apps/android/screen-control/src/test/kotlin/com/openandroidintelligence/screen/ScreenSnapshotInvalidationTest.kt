package com.openandroidintelligence.screen

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityRecord
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityWindowInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScreenSnapshotInvalidationTest {
    private lateinit var service: ScreenAccessibilityService
    private lateinit var shadowService: ShadowAccessibilityService

    @Before
    fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        PhoneLocalControlUiRegistry.current = null
        service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        shadowService = Shadow.extract(service)
        ScreenAccessibilityConnection.connect(service)
    }

    @After
    fun restore() {
        ScreenAccessibilityConnection.disconnect(service)
        ScreenHostRegistry.controlLeases.revokeAll()
        PhoneLocalControlUiRegistry.current = null
        Dispatchers.resetMain()
    }

    @Test fun 观察浮层卸载时仍由系统提供显示尺寸() {
        val manager = service.getSystemService(android.view.WindowManager::class.java)
        val observed = LocalOverlayWindowObserver(manager)
        assertEquals(manager.maximumWindowMetrics.bounds, observed.maximumWindowMetrics.bounds)
        assertEquals(manager.currentWindowMetrics.bounds, observed.currentWindowMetrics.bounds)
    }

    @Test
    fun overlayRemovalWindowEventDoesNotTurnFreshDumpIntoStaleTap() = runTest {
        installTargetWindow()
        val backend = GenerationBackend()
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())
        val request = request("overlay-removal-tap")
        val snapshotId = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))

        installTargetAndOverlayWindows()
        dispatchWindowsChanged()
        installTargetWindow()
        dispatchWindowsChanged()
        installTargetWindow()

        try {
            val result = port.execute(request, ControlOperation.Screen(snapshotId, ScreenAction.Global("back")), {}, { true })
            assertEquals("back", backend.effects.single())
            assertTrue(result.result.values.getValue("performed").toString().contains("true"))
        } catch (failure: ControlRequestException) {
            fail("浮层移除事件不应让真实目标快照陈旧，实际返回 ${failure.code}")
        }
    }

    @Test
    fun 本机浮层的延迟状态事件和重复无包名事件不使同一个目标失效() {
        installTargetWindow()
        GenerationBackend().read()
        installTargetAndOverlayWindows()
        val shown = ScreenAccessibilityConnection.observeWindows(service)!!
        fun invalid(type: Int, id: Int, windows: ObservedWindows) =
            ScreenAccessibilityConnection.requiresInvalidation(type, null, id, windows)
        assertTrue(!invalid(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, OVERLAY_WINDOW_ID, shown))
        assertTrue(!invalid(AccessibilityEvent.TYPE_WINDOWS_CHANGED, OVERLAY_WINDOW_ID, shown))
        ScreenAccessibilityConnection.overlayDetached(OVERLAY_WINDOW_ID)
        installTargetWindow()
        val removed = ScreenAccessibilityConnection.observeWindows(service)!!
        assertTrue(!invalid(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, OVERLAY_WINDOW_ID, removed))
        assertTrue(invalid(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, FOREIGN_WINDOW_ID, removed))
        assertTrue(invalid(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, TARGET_WINDOW_ID, removed))
    }

    @Test
    fun overlayRemovalWindowEventDoesNotTurnFreshDumpIntoStaleSomCapture() = runTest {
        installTargetWindow()
        var eventSent = false
        val backend = GenerationBackend(onRead = { read ->
            if (read == 1 && !eventSent) {
                eventSent = true
                installTargetAndOverlayWindows()
                dispatchWindowsChanged()
                installTargetWindow()
                dispatchWindowsChanged()
                installTargetWindow()
            }
        })
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())

        try {
            port.execute(request("overlay-removal-som"), ControlOperation.CaptureSom, {}, { true })
        } catch (failure: ControlRequestException) {
            fail("浮层移除事件不应让真实目标截图快照陈旧，实际返回 ${failure.code}")
        }
    }

    @Test
    fun ownedToastWindowTransitionDoesNotTurnFreshDumpIntoStaleTap() = runTest {
        installTargetWindow()
        val backend = GenerationBackend()
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())
        val request = request("toast-transition-tap")
        val snapshotId = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))

        installTargetAndToastWindows()
        dispatchWindowsChanged()
        installTargetWindow()
        dispatchWindowsChanged()
        installTargetWindow()
        val result = port.execute(request, ControlOperation.Screen(snapshotId, ScreenAction.Global("back")), {}, { true })
        assertEquals("back", backend.effects.single())
        assertTrue(result.result.values.getValue("performed").toString().contains("true"))
    }

    @Test
    fun targetWindowChangeStillTurnsFreshDumpIntoStaleTap() = runTest {
        installTargetWindow()
        val backend = GenerationBackend()
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())
        val request = request("target-change-tap")
        val snapshotId = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))

        val layoutBefore = ScreenAccessibilityConnection.layoutEvents.value
        installTargetWindow(bounds = Rect(0, 0, 90, 200))
        dispatchWindowsChanged()
        assertTrue(ScreenAccessibilityConnection.layoutEvents.value > layoutBefore)
        installTargetWindow(bounds = Rect(0, 0, 90, 200))
        assertTapStale(port, request, snapshotId)
    }

    @Test
    fun samePackageContentEventStillTurnsFreshDumpIntoStaleTap() = runTest {
        installTargetWindow(packageName = service.packageName)
        val backend = GenerationBackend(targetPackage = service.packageName)
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())
        val request = request("same-package-content-tap")
        val snapshotId = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))

        installTargetWindow(packageName = service.packageName)
        dispatchEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, service.packageName)
        installTargetWindow(packageName = service.packageName)
        assertTapStale(port, request, snapshotId)
    }

    @Test
    fun unknownWindowChangeWithoutOwnedAuxiliaryTransitionStillTurnsFreshDumpIntoStaleTap() = runTest {
        installTargetWindow()
        val backend = GenerationBackend()
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())
        val request = request("unknown-window-change-tap")
        val snapshotId = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))

        installTargetWindow()
        dispatchWindowsChanged()
        installTargetWindow()
        assertTapStale(port, request, snapshotId)
    }

    @Test
    fun foreignWindowAdditionStillTurnsFreshDumpIntoStaleTap() = runTest {
        installTargetWindow()
        val backend = GenerationBackend()
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())
        val request = request("foreign-window-tap")
        val snapshotId = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))

        val target = window(TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
            active = true, focused = true, packageName = TARGET_PACKAGE)
        val foreign = window(FOREIGN_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
            active = false, focused = false, packageName = "org.example.foreign")
        shadowService.setWindows(listOf(target, foreign))
        dispatchWindowsChanged()
        shadowService.setWindows(listOf(
            window(TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
                active = true, focused = true, packageName = TARGET_PACKAGE),
            window(FOREIGN_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
                active = false, focused = false, packageName = "org.example.foreign"),
        ))
        assertTapStale(port, request, snapshotId)
    }

    @Test
    fun ownedOverlayTransitionDoesNotHideTargetFocusChange() = runTest {
        installTargetWindow()
        val backend = GenerationBackend()
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())
        val request = request("overlay-and-focus-tap")
        val snapshotId = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))

        installTargetWindow(active = false, focused = false, withOverlay = true)
        dispatchWindowsChanged()
        installTargetWindow(active = false, focused = false)
        assertTapStale(port, request, snapshotId)
    }

    private fun installTargetWindow(packageName: String = TARGET_PACKAGE, bounds: Rect = Rect(0, 0, 100, 200),
        active: Boolean = true, focused: Boolean = true, withOverlay: Boolean = false) {
        val target = window(TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
            active = active, focused = focused, packageName = packageName, bounds = bounds)
        val overlay = if (withOverlay) listOf(window(OVERLAY_WINDOW_ID, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            active = false, focused = false, packageName = service.packageName)) else emptyList()
        shadowService.setWindows(listOf(target) + overlay)
    }

    private fun installTargetAndOverlayWindows() {
        val target = window(TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
            active = true, focused = true, packageName = TARGET_PACKAGE)
        val overlay = window(OVERLAY_WINDOW_ID, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            active = false, focused = false, packageName = service.packageName)
        shadowService.setWindows(listOf(target, overlay))
    }

    private fun installTargetAndToastWindows() {
        val target = window(TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
            active = true, focused = true, packageName = TARGET_PACKAGE)
        val toast = window(TOAST_WINDOW_ID, AccessibilityWindowInfo.TYPE_SYSTEM,
            active = false, focused = false, packageName = service.packageName, bounds = Rect(0, 150, 100, 200))
        shadowService.setWindows(listOf(target, toast))
    }

    @Test
    fun 持续第三方无障碍浮层时本机审批浮层显隐不改变代次且不扩大读取权限() = runTest {
        installTargetAndThirdPartyOverlayWindows()
        val port = realScreenPort()
        val request = request("sustained-third-party-overlay-tap")
        val baseline = port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true })
        assertBusinessTreeOnly(baseline)
        val snapshotId = snapshotId(baseline)
        val generation = ScreenAccessibilityConnection.generation

        installTargetThirdPartyAndOwnOverlayWindows()
        dispatchWindowsChanged(OVERLAY_WINDOW_ID, service.packageName)
        assertEquals(generation, ScreenAccessibilityConnection.generation)

        // 本机审批浮层也不能变成业务节点；这里同时刷新系统返回的窗口所有权对象。
        installTargetThirdPartyAndOwnOverlayWindows()
        assertBusinessTreeOnly(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))
        installTargetAndThirdPartyOverlayWindows()
        ScreenAccessibilityConnection.overlayDetached(OVERLAY_WINDOW_ID)
        dispatchWindowsChanged(OVERLAY_WINDOW_ID, service.packageName)
        assertEquals(generation, ScreenAccessibilityConnection.generation)

        installTargetThirdPartyAndOwnOverlayWindows()
        val result = port.execute(request, ControlOperation.Screen(snapshotId, ScreenAction.Global("back")), {}, { false })
        assertTrue(result.result.values.getValue("performed").toString().contains("true"))
        assertEquals(ControlValue.Text("SCREEN_READ_PERMISSION_REQUIRED"), result.ui!!.values["observationError"])
        assertEquals(ControlValue.Null, result.ui!!.values["snapshotId"])
    }

    @Test
    fun 第三方无障碍浮层新增不进入业务树但使旧快照失效() = runTest {
        assertThirdPartyOverlayChangeInvalidates(
            requestId = "third-party-overlay-added-tap",
            installInitial = { installTargetWindow() },
            installChanged = { installTargetAndThirdPartyOverlayWindows() },
        )
    }

    @Test
    fun 第三方无障碍浮层移除不进入业务树但使旧快照失效() = runTest {
        assertThirdPartyOverlayChangeInvalidates(
            requestId = "third-party-overlay-removed-tap",
            installInitial = { installTargetAndThirdPartyOverlayWindows() },
            installChanged = { installTargetWindow() },
        )
    }

    @Test
    fun 第三方无障碍浮层移动不进入业务树但使旧快照失效() = runTest {
        assertThirdPartyOverlayChangeInvalidates(
            requestId = "third-party-overlay-moved-tap",
            installInitial = { installTargetAndThirdPartyOverlayWindows(bounds = Rect(10, 10, 60, 60)) },
            installChanged = { installTargetAndThirdPartyOverlayWindows(bounds = Rect(40, 40, 90, 90)) },
        )
    }

    @Test
    fun 捕获业务窗口与窗口指纹不一致时清空基线并保守失效() = runTest {
        installTargetWindow()
        val mismatched = ObservedWindows(
            targets = listOf(ScreenWindowState(
                TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION, 1, true, true,
                ScreenBounds(0, 0, 90, 200), TARGET_PACKAGE,
            )),
            auxiliary = emptySet(),
            ownOverlayIds = emptySet(),
        )
        val backend = GenerationBackend(observedWindows = mismatched)
        val port = NativePhoneScreen(RuntimeEnvironment.getApplication(), backend, unownedFrameSource())
        val request = request("mismatched-window-fingerprint")
        port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true })
        val generation = ScreenAccessibilityConnection.generation

        installTargetAndOverlayWindows()
        dispatchWindowsChanged(OVERLAY_WINDOW_ID, service.packageName)
        assertTrue(ScreenAccessibilityConnection.generation > generation)
    }

    private fun installTargetAndThirdPartyOverlayWindows(bounds: Rect = Rect(10, 10, 60, 60)) {
        val target = window(TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
            active = true, focused = true, packageName = TARGET_PACKAGE)
        val thirdPartyOverlay = window(THIRD_PARTY_OVERLAY_WINDOW_ID,
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, active = false, focused = false,
            packageName = THIRD_PARTY_OVERLAY_PACKAGE, bounds = bounds,
            text = THIRD_PARTY_OVERLAY_TEXT, clickable = true)
        shadowService.setWindows(listOf(target, thirdPartyOverlay))
    }

    private fun installTargetThirdPartyAndOwnOverlayWindows() {
        val target = window(TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION,
            active = true, focused = true, packageName = TARGET_PACKAGE)
        val ownOverlay = window(OVERLAY_WINDOW_ID, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            active = false, focused = false, packageName = service.packageName, bounds = Rect(0, 120, 100, 200))
        val thirdPartyOverlay = window(THIRD_PARTY_OVERLAY_WINDOW_ID,
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, active = false, focused = false,
            packageName = THIRD_PARTY_OVERLAY_PACKAGE, bounds = Rect(10, 10, 60, 60),
            text = THIRD_PARTY_OVERLAY_TEXT, clickable = true)
        shadowService.setWindows(listOf(target, ownOverlay, thirdPartyOverlay))
    }

    private fun window(id: Int, type: Int, active: Boolean, focused: Boolean, packageName: String,
        bounds: Rect = Rect(0, 0, 100, 200), text: String? = null, clickable: Boolean = false): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { window ->
            val root = AccessibilityNodeInfo.obtain().apply {
                this.packageName = packageName
                this.text = text
                isClickable = clickable
                isVisibleToUser = true
                setBoundsInScreen(bounds)
            }
            Shadow.extract<ShadowAccessibilityWindowInfo>(window).apply {
                setId(id)
                setType(type)
                setActive(active)
                setFocused(focused)
                setLayer(if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) 2 else 1)
                setBoundsInScreen(bounds)
                setRoot(root)
            }
        }

    private fun dispatchWindowsChanged(windowId: Int = 0, packageName: String? = null) =
        dispatchEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED, packageName, windowId)

    private fun dispatchEvent(type: Int, packageName: String?, windowId: Int = 0) {
        val event = AccessibilityEvent.obtain(type)
        event.packageName = packageName
        Shadow.extract<ShadowAccessibilityRecord>(event).setWindowId(windowId)
        try { service.onAccessibilityEvent(event) } finally { event.recycle() }
    }

    private fun unownedFrameSource() = SomFrameSource { snapshot ->
        val bitmap = Bitmap.createBitmap(snapshot.display.widthPixels, snapshot.display.heightPixels, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.BLUE) }
        SomFrame(bitmap, SystemClock.uptimeMillis(), snapshot.display,
            ScreenBounds(0, 0, snapshot.display.widthPixels, snapshot.display.heightPixels), true)
    }

    private fun request(id: String) = ControlAccessBinding(
        RemoteControlPrincipal(
            ControlOrigin.fromCanonical("https://remote.example"),
            "account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
        ),
        1, "connection", 1,
    ).let { access ->
        PhoneExecutionContext(
            ControlRequestScope(ControlTaskScope(access.principal, "task", 1, "process"), id),
            access, "sha256:" + "c".repeat(64), {}, {},
        )
    }

    private fun snapshotId(result: PhoneControlResult): String =
        (result.result.values.getValue("snapshotId") as ControlValue.Text).value

    private fun realScreenPort(): NativePhoneScreen = NativePhoneScreen(
        RuntimeEnvironment.getApplication(),
        AndroidScreenBackend(AndroidAccessibilityRuntime(RuntimeEnvironment.getApplication())),
        unownedFrameSource(),
    )

    private suspend fun assertThirdPartyOverlayChangeInvalidates(
        requestId: String,
        installInitial: () -> Unit,
        installChanged: () -> Unit,
    ) {
        installInitial()
        val port = realScreenPort()
        val request = request(requestId)
        val baseline = port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true })
        assertBusinessTreeOnly(baseline)
        val snapshotId = snapshotId(baseline)
        val generation = ScreenAccessibilityConnection.generation

        installChanged()
        dispatchWindowsChanged(THIRD_PARTY_OVERLAY_WINDOW_ID)
        assertTrue(ScreenAccessibilityConnection.generation > generation)

        // 第三方浮层只参与窗口元数据指纹，新快照仍不得返回它的窗口、文本或可点节点。
        installChanged()
        assertBusinessTreeOnly(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))
        installChanged()
        assertTapStale(port, request, snapshotId)
    }

    private fun assertBusinessTreeOnly(result: PhoneControlResult) {
        val windows = (result.result.values.getValue("windows") as ControlValue.Array).values
            .map { it as ControlValue.Object }
        assertEquals(listOf(ControlValue.Number(TARGET_WINDOW_ID.toString())),
            windows.map { it.values.getValue("id") })

        val nodes = (result.result.values.getValue("nodes") as ControlValue.Array).values
            .map { it as ControlValue.Object }
        assertTrue(nodes.all { it.values.getValue("windowId") == ControlValue.Number(TARGET_WINDOW_ID.toString()) })
        assertTrue(nodes.none { it.values["text"] == ControlValue.Text(THIRD_PARTY_OVERLAY_TEXT) })
    }

    private suspend fun assertTapStale(port: NativePhoneScreen, request: PhoneExecutionContext, snapshotId: String) {
        try {
            port.execute(request, ControlOperation.Screen(snapshotId, ScreenAction.Global("back")), {}, { true })
            fail("真实目标或未知窗口变化后，旧快照必须返回 SNAPSHOT_STALE")
        } catch (failure: ControlRequestException) {
            assertEquals("SNAPSHOT_STALE", failure.code)
        }
    }

    private inner class GenerationBackend(
        private val targetPackage: String = TARGET_PACKAGE,
        private val onRead: (Int) -> Unit = {},
        observedWindows: ObservedWindows? = null,
    ) : ScreenBackend {
        private val runtime = TestRuntime(targetPackage, observedWindows)
        private val delegate = AndroidScreenBackend(runtime)
        var reads = 0
        val effects get() = runtime.effects

        override fun availability(): String? = null

        override fun read(query: ScreenReadQuery): ScreenSnapshot {
            val count = ++reads
            return delegate.read(query).also { onRead(count) }
        }

        override suspend fun execute(expectedRevision: String, action: ScreenAction, beforeDispatch: () -> Unit): ScreenActionResult {
            return delegate.execute(expectedRevision, action, beforeDispatch)
        }
    }

    private inner class TestRuntime(
        private val targetPackage: String,
        observedWindows: ObservedWindows?,
    ) : ScreenRuntime {
        val effects = mutableListOf<String>()

        override fun availability(): String? = null
        override fun generation(): Long = ScreenAccessibilityConnection.generation
        override fun display(): ScreenDisplay = ScreenDisplay(100, 200, 0)
        var observedOverride: ObservedWindows? = observedWindows
        override fun windows(): ScreenWindowCapture {
            val windows = listOf(ScreenTreeWindow(
                ScreenWindow(TARGET_WINDOW_ID, AccessibilityWindowInfo.TYPE_APPLICATION, 1, true, true,
                    ScreenBounds(0, 0, 100, 200)),
                TestNode(),
                targetPackage,
            ))
            val states = windows.map {
                ScreenWindowState(it.window.id, it.window.type, it.window.layer, it.window.active, it.window.focused,
                    it.window.bounds, it.packageName)
            }
            return ScreenWindowCapture(windows, observedOverride ?: buildObservedWindows(states, targetPackage))
        }
        override fun nodeAction(expected: com.openandroidintelligence.control.ScreenNode, action: NativeNodeAction,
            text: String?, generation: Long, beforeDispatch: () -> Unit): Boolean = false
        override suspend fun gesture(points: List<Pair<Int, Int>>, durationMs: Long, generation: Long,
            beforeDispatch: () -> Unit, drag: Boolean): Boolean = false
        override fun global(action: String, generation: Long, beforeDispatch: () -> Unit): Boolean {
            beforeDispatch()
            effects += action
            return true
        }
        override fun launch(packageName: String, generation: Long, beforeDispatch: () -> Unit): Boolean = false
    }

    private class TestNode : ScreenTreeNode {
        override val text: String? = null
        override val description: String? = null
        override val resourceId: String? = null
        override val className: String? = "android.view.View"
        override val bounds = ScreenBounds(0, 0, 100, 200)
        override val visible = true
        override val clickable = false
        override val longClickable = false
        override val scrollable = false
        override val editable = false
        override val focused = false
        override val selected = false
        override val enabled = true
        override val password = false
        override val childCount = 0
        override fun child(index: Int): ScreenTreeNode? = null
        override fun close() = Unit
    }

    private companion object {
        const val TARGET_PACKAGE = "org.example.target"
        const val TARGET_WINDOW_ID = 7
        const val OVERLAY_WINDOW_ID = 9
        const val TOAST_WINDOW_ID = 11
        const val FOREIGN_WINDOW_ID = 13
        const val THIRD_PARTY_OVERLAY_WINDOW_ID = 15
        const val THIRD_PARTY_OVERLAY_PACKAGE = "org.example.floating"
        const val THIRD_PARTY_OVERLAY_TEXT = "第三方浮层按钮"
    }
}
