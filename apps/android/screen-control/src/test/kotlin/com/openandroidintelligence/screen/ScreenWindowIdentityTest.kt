package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openandroidintelligence.control.ScreenOperationException
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityWindowInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ScreenWindowIdentityTest.WindowServiceShadow::class])
class ScreenWindowIdentityTest {
    @Implements(AccessibilityService::class)
    class WindowServiceShadow {
        var listed = emptyList<AccessibilityWindowInfo>()
        var activeRoot: () -> AccessibilityNodeInfo? = { null }
        @Implementation protected fun getWindows() = listed
        @Implementation protected fun getRootInActiveWindow() = activeRoot()
    }

    private lateinit var service: ScreenAccessibilityService
    private lateinit var system: WindowServiceShadow
    private lateinit var backend: AndroidScreenBackend

    @Before fun connect() {
        service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        ScreenAccessibilityConnection.connect(service)
        system = Shadow.extract(service)
        backend = AndroidScreenBackend(service)
    }

    @After fun disconnect() = ScreenAccessibilityConnection.disconnect(service)

    @Test fun 已枚举第三方浮层或不可读窗口时拒绝从活动根返回浮层文本() {
        fun overlay() = window(23, "org.example.overlay", type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        system.activeRoot = { overlay().root }
        for (listed in listOf(overlay(), window(31, "org.example.app", readable = false))) {
            system.listed = listOf(listed)
            val read = runCatching { backend.read() }
            assertEquals("返回了 ${read.getOrNull()?.nodes?.map { it.text }}", "SCREEN_WINDOW_UNAVAILABLE",
                (read.exceptionOrNull() as? ScreenOperationException)?.code)
        }
    }

    @Test fun 空窗口列表只用首次活动根的节点包名和窗口号() {
        var reads = 0
        system.activeRoot = {
            if (++reads == 1) window(17, "org.example.original").root else window(19, "org.example.later").root
        }
        val snapshot = backend.read()
        assertEquals("窗口17", snapshot.nodes.single().text)
        assertEquals("org.example.original" to 17, snapshot.foregroundPackage to snapshot.activeWindowId)
        assertEquals(1, reads)
    }

    @Test fun 活动身份取自本轮唯一活动业务窗口() {
        system.listed = listOf(window(12, "org.example.background", active = false), window(17, "org.example.original"))
        var reads = 0
        system.activeRoot = {
            if (++reads == 1) window(21, "org.example.next").root else window(22, "org.example.last").root
        }
        val snapshot = backend.read()
        assertEquals(listOf(12, 17), snapshot.nodes.map { it.windowId })
        assertEquals("org.example.original" to 17, snapshot.foregroundPackage to snapshot.activeWindowId)
        assertEquals(0, reads)
    }

    @Test fun 活动业务窗口缺失不唯一或身份不全时保留业务树并留空身份对() {
        system.activeRoot = { window(27, "org.example.later").root }
        for (listed in listOf(listOf(window(17, "org.example.one", active = false)),
            listOf(window(17, "org.example.one"), window(18, "org.example.two")),
            listOf(window(17, null)), listOf(window(-1, "org.example.one")),
            listOf(window(Int.MAX_VALUE, "org.example.one")))) {
            system.listed = listed
            val snapshot = backend.read()
            assertEquals(listed.size, snapshot.nodes.size)
            assertNull(snapshot.foregroundPackage)
            assertNull(snapshot.activeWindowId)
        }
    }

    @Suppress("DEPRECATION")
    private fun window(id: Int, pkg: String?, active: Boolean = true, type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        readable: Boolean = true): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { window ->
        val bounds = Rect(0, 0, 100, 200)
        Shadow.extract<ShadowAccessibilityWindowInfo>(window).apply {
            setId(id)
            setType(type)
            setActive(active)
            setBoundsInScreen(bounds)
            if (readable) setRoot(AccessibilityNodeInfo.obtain().apply {
                packageName = pkg
                text = "窗口$id"
                isVisibleToUser = true
                setBoundsInScreen(bounds)
                Shadow.extract<ShadowAccessibilityNodeInfo>(this).setAccessibilityWindowInfo(window)
            })
        }
    }
}
