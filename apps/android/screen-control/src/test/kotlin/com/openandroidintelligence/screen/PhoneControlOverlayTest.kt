package com.openandroidintelligence.screen

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.openandroidintelligence.control.ScreenBounds
import com.openandroidintelligence.control.remote.ControlRequestException
import com.openandroidintelligence.control.remote.PhoneControlOverlayColors
import com.openandroidintelligence.control.remote.PhoneControlOverlayTheme
import com.openandroidintelligence.control.remote.PhoneLocalControlUiRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    class ThemedApplication : Application(), PhoneControlOverlayTheme {
        lateinit var colors: PhoneControlOverlayColors

        override fun phoneControlOverlayColors(context: Context): PhoneControlOverlayColors = colors
    }

    private class RecordingWindow(private val manager: WindowManager, private val attach: Boolean = false) : WindowManager by manager {
        val registered = mutableSetOf<View>()
        var addCount = 0
            private set
        var removeCount = 0
            private set
        var nextError: RuntimeException? = null

        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            addCount++
            nextError?.let {
                nextError = null
                throw it
            }
            if (attach) manager.addView(view, params) else view.layoutParams = params
            assertTrue(registered.add(view))
        }

        override fun removeViewImmediate(view: View) {
            removeCount++
            assertTrue(registered.remove(view))
            if (attach) manager.removeViewImmediate(view)
        }

        @Suppress("DEPRECATION")
        override fun getMaximumWindowMetrics(): WindowMetrics = WindowMetrics(Rect(0, 0, 400, 800), WindowInsets.Builder().build())

        fun layout(height: Int) {
            val window = registered.single()
            window.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            window.layout(0, 0, 320, height)
        }
    }

    @Before fun prepare() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restore() { PhoneLocalControlUiRegistry.current = null; Dispatchers.resetMain() }
    private fun view(overlay: PhoneControlOverlay): LinearLayout = ReflectionHelpers.getField<View>(overlay, "view") as LinearLayout
    private fun dispatchTouch(button: Button, action: Int) {
        val event = MotionEvent.obtain(0, 1, action, 10f, 10f, 1f, 1f, 0, 1f, 1f, 1, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        button.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun press(button: Button) {
        button.layout(0, 0, 300, 100)
        dispatchTouch(button, MotionEvent.ACTION_DOWN)
        dispatchTouch(button, MotionEvent.ACTION_UP)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    @Test fun 首帧前取消会卸载已登记但未附着的确认窗口() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val manager = RecordingWindow(service.getSystemService(WindowManager::class.java))
        val overlay = PhoneControlOverlay(service, manager)
        var stops = 0
        val stopListener = PhoneLocalControlUiRegistry.observeEmergencyStop { stops++ }
        try {
            val pending = async { overlay.confirm("cancel-before-first-frame", "ui.tap") }
            runCurrent()
            val window = manager.registered.single() as LinearLayout
            val staleStop = window.getChildAt(3) as Button
            assertFalse(window.isAttachedToWindow)
            assertNotNull(overlay.protectedBounds)
            assertFalse(pending.isCompleted)

            pending.cancelAndJoin()
            assertTrue("取消后系统窗口登记必须清除", manager.registered.isEmpty())
            assertEquals(1, manager.removeCount)
            overlay.finish("cancel-before-first-frame")
            assertEquals(1, manager.removeCount)
            assertNull(overlay.protectedBounds)
            val next = async { overlay.confirm("next", "ui.tap") }
            runCurrent()
            assertFalse(staleStop.isEnabled)
            assertFalse(staleStop.performClick())
            runCurrent()
            assertEquals(0, stops)
            assertFalse(next.isCompleted)
            next.cancelAndJoin()
        } finally {
            stopListener.close()
            overlay.close()
        }
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
    @Test fun 首次挂载被系统拒绝时不卸载未登记窗口且可继续下一请求() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        for (error in listOf(WindowManager.BadTokenException("系统拒绝窗口凭据"), WindowManager.InvalidDisplayException("显示器不可用"))) {
            val window = RecordingWindow(service.getSystemService(WindowManager::class.java)).apply { nextError = error }
            val overlay = PhoneControlOverlay(service, window)
            try {
                val failure = runCatching { overlay.confirm("owner-failed", "ui.tap") }.exceptionOrNull()
                assertEquals("LOCAL_CONFIRMATION_UI_UNAVAILABLE", (failure as ControlRequestException).code)
                assertNull(overlay.protectedBounds)
                assertEquals(0, window.removeCount)
                assertTrue(window.registered.isEmpty())

                val next = async { overlay.confirm("owner-after-failure", "ui.tap") }
                runCurrent()
                assertTrue(window.registered.isNotEmpty())
                assertFalse(next.isCompleted)
                next.cancelAndJoin()
                assertEquals(1, window.removeCount)
            } finally {
                overlay.close()
            }
        }
    }

    @Test fun 关闭已批准的胶囊会释放旧队列且迟到清理不影响新胶囊() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val manager = RecordingWindow(service.getSystemService(WindowManager::class.java), attach = true)
        val old = PhoneControlOverlay(service, manager)
        val replacement = PhoneControlOverlay(service, manager)
        PhoneLocalControlUiRegistry.current = old
        try {
            val first = async { old.confirm("active", "ui.tap") }
            runCurrent()
            press((manager.registered.single() as LinearLayout).getChildAt(1) as Button)
            runCurrent()
            assertTrue(first.await())
            val queued = backgroundScope.async { old.confirm("queued", "ui.tap") }
            runCurrent()
            assertFalse(queued.isCompleted)

            old.close()
            PhoneLocalControlUiRegistry.current = replacement
            runCurrent()
            assertTrue(queued.isCompleted)
            assertFalse(queued.await())
            assertTrue(manager.registered.isEmpty())

            val next = async { replacement.confirm("next", "ui.tap") }
            runCurrent()
            val newWindow = manager.registered.single()
            old.finish("active")
            replacement.finish("active")
            assertSame(newWindow, manager.registered.single())
            assertFalse(next.isCompleted)
            next.cancelAndJoin()
        } finally {
            old.close()
            replacement.close()
        }
    }

    @Test fun smsConfirmationShowsLocalRecipientSubscriptionAndPreviewInSecureNonFocusableWindow() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val manager = RecordingWindow(service.getSystemService(WindowManager::class.java), attach = true)
        val overlay = PhoneControlOverlay(service, manager)
        try {
            val body = "仅本机显示的短信".repeat(40)
            val parameters = com.openandroidintelligence.gateway.schema.Json.canonical(
                com.openandroidintelligence.gateway.schema.Json.of(mapOf("subscriptionId" to 7,
                    "recipient" to "+15550000777", "text" to body)))
            val waiting = async { overlay.confirm("sms-local-owner", "org.openandroidintelligence.sms.send", parameters) }
            runCurrent()
            val confirmation = manager.registered.single() as LinearLayout
            manager.layout(300)
            val before = requireNotNull(overlay.captureProtection())
            assertTrue(before.ready)
            val detail = ((confirmation.getChildAt(1) as ScrollView).getChildAt(0) as TextView).text.toString()
            assertTrue(detail.contains("+15550000777"))
            assertTrue(detail.contains("7"))
            assertTrue(detail.contains(body.length.toString()))
            assertTrue(detail.contains(body.take(240)))
            assertFalse(detail.contains(body))
            assertNotNull(overlay.protectedBounds)
            val params = confirmation.layoutParams as android.view.WindowManager.LayoutParams
            assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            val allow = confirmation.getChildAt(2) as Button
            val deny = confirmation.getChildAt(3) as Button
            val stop = confirmation.getChildAt(4) as Button
            allow.performClick(); runCurrent(); assertFalse(waiting.isCompleted)
            allow.layout(0, 0, 300, 100)
            dispatchTouch(allow, MotionEvent.ACTION_DOWN)
            // 在这次模拟触摸与窗口下一次遍历之间核对保护范围。
            allow.performClick()
            runCurrent()
            assertTrue(waiting.await())
            val executing = manager.registered.single() as LinearLayout
            assertSame(confirmation, executing)
            assertSame(params, executing.layoutParams)
            assertEquals(1, manager.addCount)
            assertEquals(0, manager.removeCount)
            assertEquals(service.getString(R.string.remote_phone_executing), (executing.getChildAt(0) as TextView).text)
            assertEquals(2, executing.childCount)
            assertSame(stop, executing.getChildAt(1))
            assertFalse(allow.isEnabled)
            assertFalse(deny.isEnabled)
            assertEquals(ScreenBounds(0, 0, Int.MAX_VALUE, Int.MAX_VALUE), overlay.protectedBounds)
            assertFalse(requireNotNull(overlay.captureProtection()).ready)
            manager.layout(120)
            val after = requireNotNull(overlay.captureProtection())
            assertTrue(after.ready)
            assertTrue(after.generation > before.generation)
            assertEquals(before.maskedRegions, after.maskedRegions)
            overlay.finish("sms-local-owner"); runCurrent()
            assertNull(overlay.protectedBounds)
            assertEquals(1, manager.removeCount)
        } finally { overlay.close() }
    }

    @Test
    @Config(sdk = [31, 34], application = ThemedApplication::class)
    fun 原生按钮和标题使用当前深浅色角色() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val nightModes = listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)
        val schemes = nightModes.mapIndexed { index, nightMode ->
            val config = Configuration(service.resources.configuration).apply {
                uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or nightMode
            }
            systemPhoneControlOverlayColors(service.createConfigurationContext(config))
                .copy(primary = if (index == 0) 0xff266128.toInt() else 0xff8fda92.toInt())
        }
        assertNotEquals(schemes.first().surfaceContainerHigh, schemes.last().surfaceContainerHigh)
        val app = service.application as ThemedApplication
        val manager = RecordingWindow(service.getSystemService(WindowManager::class.java))
        val overlay = PhoneControlOverlay(service, manager)
        try {
            for (colors in schemes) {
                app.colors = colors
                val pending = async { overlay.confirm("theme", "ui.tap") }
                runCurrent()
                val root = manager.registered.single() as LinearLayout
                fun fill(view: View) = (view.background as GradientDrawable).color?.defaultColor
                assertEquals(colors.surfaceContainerHigh, fill(root))
                assertEquals(colors.onSurface, (root.getChildAt(0) as TextView).currentTextColor)
                listOf(colors.primary to colors.onPrimary, colors.secondaryContainer to colors.onSecondaryContainer,
                    colors.errorContainer to colors.onErrorContainer).forEachIndexed { index, (container, content) ->
                    val button = root.getChildAt(index + 1) as Button
                    val ripple = button.background as RippleDrawable
                    assertTrue(ripple.isStateful)
                    assertEquals(container, (ripple.getDrawable(0) as GradientDrawable).color?.defaultColor)
                    assertEquals(content, button.currentTextColor)
                    assertEquals(0f, button.elevation)
                    assertNull(button.stateListAnimator)
                }
                pending.cancelAndJoin()
            }
        } finally {
            overlay.close()
        }
    }

    @Test fun 首次推送收件绑定显示可信主体和精确目标且合成点击不能批准() = runTest {
        val service=Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val overlay=PhoneControlOverlay(service)
        try {
            val fields=mapOf("controllerDeviceId" to "desktop-client","controllerKeyFingerprint" to "sha256:client-pin",
                "origin" to "https://remote.example","accountId" to "owner","hostDeviceId" to "target-host",
                "hostKeyFingerprint" to "sha256:host-pin","backend" to "codex","workspaceId" to "workspace-42","sessionId" to "session-77")
            val parameters=com.openandroidintelligence.gateway.schema.Json.canonical(com.openandroidintelligence.gateway.schema.Json.of(fields))
            val waiting=async { overlay.confirm("push-recipient-owner","push.bind_recipient",parameters) };runCurrent()
            val confirmation=view(overlay)
            val title=(confirmation.getChildAt(0) as TextView).text.toString()
            val detail=((confirmation.getChildAt(1) as ScrollView).getChildAt(0) as TextView).text.toString()
            assertTrue(title.contains("授权此设备读取这个目标的推送"))
            fields.values.forEach { assertTrue(detail.contains(it)) }
            assertTrue(detail.contains("不代表已证明请求来自该会话"))
            val params=confirmation.layoutParams as android.view.WindowManager.LayoutParams
            assertEquals(android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,params.type)
            assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            val allow=confirmation.getChildAt(2) as Button
            allow.performClick();runCurrent();assertFalse(waiting.isCompleted)
            press(allow);runCurrent();assertTrue(waiting.await())
            overlay.finish("push-recipient-owner")
        } finally { overlay.close() }
    }

    @Test fun 推送收件确认缺少真实目标详情时拒绝建立窗口() = runTest {
        val service=Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val overlay=PhoneControlOverlay(service)
        try {
            assertFalse(overlay.confirm("push-missing","push.bind_recipient","{}"))
            assertNull(overlay.protectedBounds)
        } finally { overlay.close() }
    }

    @Test fun smsWithoutLocalParametersCannotCreateAnApprovalWindow() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val overlay = PhoneControlOverlay(service)
        try {
            assertFalse(overlay.confirm("sms-missing-detail", "org.openandroidintelligence.sms.send"))
            assertNull(overlay.protectedBounds)
        } finally { overlay.close() }
    }

    @Test fun 相同浮窗位置的重复布局保留截图保护代次() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val manager = RecordingWindow(service.getSystemService(WindowManager::class.java))
        val overlay = PhoneControlOverlay(service, manager)
        try {
            overlay.executing("capture-repeated-layout")
            val window = manager.registered.single()
            var layouts = 0
            window.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layouts++ }
            manager.layout(200)
            val before = requireNotNull(overlay.captureProtection())
            assertTrue(before.ready)

            window.forceLayout()
            manager.layout(200)
            val after = requireNotNull(overlay.captureProtection())
            assertEquals(2, layouts)
            assertEquals(before, after)
        } finally { overlay.close() }
    }

    @Test fun protectionHistoryRetainsOldTopBandAndRejectsShadowOrTransformChanges() = runTest {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val real = service.getSystemService(android.view.WindowManager::class.java)
        val window = object : android.view.WindowManager by real {
            // Robolectric 的服务 WindowManager 没有真实显示配置；本测试明确供给系统几何形状。
            override fun getMaximumWindowMetrics() = android.view.WindowMetrics(android.graphics.Rect(0, 0, 400, 800),
                android.view.WindowInsets.Builder().build())
        }
        val overlay = PhoneControlOverlay(service, window)
        try {
            val waiting = async { overlay.confirm("capture-history", "ui.capture_som") }; runCurrent()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val layout = view(overlay)
            val first = requireNotNull(overlay.captureProtection())
            assertTrue(first.toString(), first.ready)
            assertEquals(0, first.maskedRegions.single().left)
            assertEquals(first.display.widthPixels, first.maskedRegions.single().right)
            val oldBottom = first.maskedRegions.single().bottom
            layout.layout(layout.left, layout.top, layout.right, layout.bottom - 1)
            val smaller = requireNotNull(overlay.captureProtection())
            assertTrue(smaller.generation > first.generation)
            assertTrue(smaller.maskedRegions.single().bottom >= oldBottom)
            layout.elevation = 10f
            assertFalse(requireNotNull(overlay.captureProtection()).ready)
            overlay.emergencyStop(); runCurrent(); assertFalse(waiting.await())
        } finally { overlay.close() }
    }
}
