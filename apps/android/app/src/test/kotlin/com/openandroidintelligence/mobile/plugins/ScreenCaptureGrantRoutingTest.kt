package com.openandroidintelligence.mobile.plugins

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 屏幕截图的系统权限引导必须与底层取帧路径一致。
 *
 * 底层早已是「无障碍优先、MediaProjection 仅作显式备用」（见
 * `AccessibilityFirstScreenCaptureSource`）。这里锁的是授权需求映射必须说同一件事：
 * Android 11+ 上截图能力的首要系统入口是无障碍设置，投屏只是备用；
 * 否则用户会在无障碍截图本来可用的情况下被推去授权屏幕共享。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenCaptureGrantRoutingTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun requirements(primitives: Set<String>, accessibilityScreenshotSupported: Boolean = true) =
        androidPermissionRequirements(primitives, accessibilityScreenshotSupported = accessibilityScreenshotSupported)

    @Test
    fun onAndroid11AndAboveCaptureLeadsWithTheAccessibilityService() {
        val capture = requirements(setOf("kernel.screen.capture"))

        val primary = capture.first { !it.isFallback }
        assertEquals(DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE, primary.grantKind)
        assertEquals("无障碍服务", primary.label)
        assertTrue("首要入口必须跳无障碍设置", primary.opensAccessibilitySettings())
        assertNull("无障碍不是运行时权限", primary.manifestPermission)
    }

    @Test
    fun mediaProjectionIsKeptOnlyAsAnExplicitFallback() {
        val capture = requirements(setOf("kernel.screen.capture"))

        val fallback = capture.single { it.grantKind == DeviceCapabilityGrantKind.MEDIA_PROJECTION }
        assertTrue("投屏必须被标记为备用方案", fallback.isFallback)
        assertTrue(fallback.opensMediaProjectionDialog())
        assertFalse(fallback.opensAccessibilitySettings())
        // 首要入口排在备用方案之前：界面据此决定显示顺序与默认按钮。
        assertTrue(capture.indexOf(capture.first { !it.isFallback }) < capture.indexOf(fallback))
    }

    @Test
    fun belowAndroid11TheOnlyAvailableRouteIsMediaProjection() {
        val capture = requirements(setOf("kernel.screen.capture"), accessibilityScreenshotSupported = false)

        assertEquals(listOf("screen-capture"), capture.map { it.key })
        val only = capture.single()
        assertEquals(DeviceCapabilityGrantKind.MEDIA_PROJECTION, only.grantKind)
        assertFalse("系统不支持无障碍截图时它不是备用方案，而是唯一可用路径", only.isFallback)
    }

    @Test
    fun screenReadAndControlStillRequireAccessibilityWithoutAnyProjectionEntry() {
        listOf("kernel.screen.read", "kernel.screen.control").forEach { primitive ->
            val single = requirements(setOf(primitive)).single()
            assertEquals(DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE, single.grantKind)
            assertFalse(single.isFallback)
        }
        // 读取与控制都声明时无障碍入口仍然只有一条。
        assertEquals(
            listOf("accessibility"),
            requirements(setOf("kernel.screen.read", "kernel.screen.control")).map { it.key },
        )
        // 屏幕能力不会因为读取/控制而附带投屏授权。
        assertTrue(requirements(setOf("kernel.screen.read", "kernel.screen.control"))
            .none { it.grantKind == DeviceCapabilityGrantKind.MEDIA_PROJECTION })
    }

    @Test
    fun declaringReadAndCaptureTogetherSharesOneAccessibilityEntry() {
        val combined = requirements(setOf("kernel.screen.read", "kernel.screen.capture"))

        assertEquals(1, combined.count { it.grantKind == DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE })
        assertEquals(listOf("accessibility", "screen-capture"), combined.map { it.key })
    }

    @Test
    fun aGrantedAccessibilityServiceMakesMediaProjectionUnnecessary() {
        val capture = requirements(setOf("kernel.screen.capture"))
        var accessibilityReady = false
        val inspector = DeviceCapabilityGrantInspector(
            context = context,
            notificationAccessGranted = { false },
            accessibilityEnabled = { accessibilityReady },
            captureAuthorized = { false },
        )

        val primary = capture.first { !it.isFallback }
        assertEquals(DeviceCapabilityGrantState.REQUIRES_SETTINGS, inspector.state(primary))
        assertTrue(
            "无障碍未就绪时备用方案仍可提示",
            needsFallbackConsent(capture) { inspector.state(it) },
        )

        accessibilityReady = true
        assertEquals(DeviceCapabilityGrantState.GRANTED, inspector.state(primary))
        assertFalse(
            "无障碍截图可用时不得再要求用户做屏幕共享授权",
            needsFallbackConsent(capture) { inspector.state(it) },
        )
    }

    @Test
    fun aDisconnectedAccessibilityServiceKeepsTheAccessibilityEntryReachable() {
        // 设置里开过但系统已解绑：界面必须仍能把用户送回无障碍设置，而不是把人推去做投屏授权。
        var enabledInSettings = true
        var connected = false
        val inspector = DeviceCapabilityGrantInspector(
            context = context,
            notificationAccessGranted = { false },
            accessibilityEnabled = { enabledInSettings && connected },
            captureAuthorized = { false },
        )
        val primary = requirements(setOf("kernel.screen.capture")).first { !it.isFallback }

        assertEquals(DeviceCapabilityGrantState.REQUIRES_SETTINGS, inspector.state(primary))
        val guidance = manualGrantGuidance(primary)
        assertTrue(guidance.contains("无障碍"))
        assertTrue(guidance.contains("已连接"))
        assertFalse("断连时不应把用户推去做投屏授权", guidance.contains("投屏"))
    }

    @Test
    fun runtimePermissionsNeverContainSettingsBackedGrants() {
        val inspector = DeviceCapabilityGrantInspector(
            context = context,
            notificationAccessGranted = { false },
            accessibilityEnabled = { false },
            captureAuthorized = { false },
        )
        val all = requirements(setOf("kernel.screen.read", "kernel.screen.capture"))

        // 无障碍与投屏都不能混进系统权限申请对话框。
        assertEquals(emptyList<String>(), inspector.runtimePermissionsToRequest(all))
    }

    @Test
    fun smsAndCallLogKeepTheirRuntimePermissionsUntouched() {
        val sms = requirements(setOf("kernel.sms.read")).single()
        assertEquals(Manifest.permission.READ_SMS, sms.manifestPermission)
        assertFalse(sms.isFallback)
        val callLog = requirements(setOf("kernel.call-log.read")).single()
        assertEquals(Manifest.permission.READ_CALL_LOG, callLog.manifestPermission)
        assertFalse(callLog.isFallback)
    }

    @Test
    fun anEmptyDeclarationStillProducesNothing() {
        assertTrue(androidPermissionRequirements(emptySet()).isEmpty())
        assertTrue(androidPermissionRequirements(emptySet(), accessibilityScreenshotSupported = false).isEmpty())
    }
}
