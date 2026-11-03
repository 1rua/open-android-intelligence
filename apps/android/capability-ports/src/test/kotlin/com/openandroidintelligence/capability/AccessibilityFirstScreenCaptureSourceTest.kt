package com.openandroidintelligence.capability

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 无障碍优先截图的决策矩阵。
 *
 * 问题一的核心诉求是「Agent 高频截图不再反复弹系统确认」，但这**不能**靠
 * 绕过系统限制实现。因此这里钉住两件事：
 * 1. 无障碍服务就绪时按需取帧，不碰 MediaProjection 的系统授权；
 * 2. 无障碍不可用时如实降级，且**失败/系统拒绝/频率限制/服务断连四类
 *    必须彼此可区分** —— 否则界面无法给出正确的下一步。
 *
 * 系统壳 [com.openandroidintelligence.screen.AndroidAccessibilityScreenshotRuntime]
 * 只做 API 转发，其真机行为由设备验收覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityFirstScreenCaptureSourceTest {

    private class FakeAccessibility(
        var enabledInSettings: Boolean = true,
        var connected: Boolean = true,
        var hasCapability: Boolean = true,
        var outcome: (() -> ScreenCaptureOutcome)? = null,
    ) : AccessibilityScreenshotRuntime {
        var captures = 0
        override fun isServiceEnabledInSettings() = enabledInSettings
        override fun isServiceConnected() = connected
        override fun hasScreenshotCapability() = connected && hasCapability
        override suspend fun takeScreenshot(): ScreenCaptureOutcome {
            captures++
            return outcome?.invoke() ?: ScreenCaptureOutcome.Success(frame())
        }
    }

    private class FakeProjection(
        var available: Boolean = false,
        var frames: Int = 1,
    ) : ScreenCaptureSource {
        var captures = 0
        var releases = 0
        var authorizationRequests = 0
        override val isAvailable get() = available
        override fun createAuthorizationIntent(): Intent? {
            authorizationRequests++
            return Intent("test.projection")
        }
        override fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean {
            available = resultCode == Activity.RESULT_OK
            return available
        }
        override suspend fun capture(): ScreenCapture? {
            captures++
            return if (available && frames > 0) frame().also { frames-- } else null
        }
        override fun release() { releases++; available = false }
    }

    @Test
    fun accessibilityServiceTakesFramesWithoutAnyProjectionConsent() = runBlocking {
        val accessibility = FakeAccessibility()
        val projection = FakeProjection(available = false)
        val source = AccessibilityFirstScreenCaptureSource(accessibility, projection)

        assertTrue("无障碍就绪即视为可用，无需投屏授权", source.isAvailable)
        assertEquals(ScreenCaptureOrigin.ACCESSIBILITY, source.preferredOrigin)
        assertTrue(source.capture() != null)
        // 关键断言：一次投屏授权都没发生，用户不会被反复弹窗。
        assertEquals(0, projection.authorizationRequests)
        assertEquals(0, projection.captures)
        assertEquals(1, accessibility.captures)
    }

    @Test
    fun repeatedCapturesStayOnAccessibilityAndNeverEscalateToProjection() = runBlocking {
        val accessibility = FakeAccessibility()
        val projection = FakeProjection()
        val source = AccessibilityFirstScreenCaptureSource(accessibility, projection)

        repeat(5) { assertTrue(source.capture() != null) }
        // 高频截图必须全部走无障碍：这正是「不再反复确认」的可验证形式。
        assertEquals(5, accessibility.captures)
        assertEquals(0, projection.captures)
    }

    @Test
    fun disconnectedServiceWithSettingsEnabledReportsServiceDisconnected() = runBlocking {
        val source = AccessibilityFirstScreenCaptureSource(
            FakeAccessibility(enabledInSettings = true, connected = false),
            FakeProjection(available = false),
        )
        // 开过但没连上 ≠ 从未授权。两者引导完全不同，不能混为一谈。
        assertEquals(ScreenCaptureFailure.SERVICE_DISCONNECTED, source.diagnose())
        val result = source.captureDetailed()
        assertEquals(ScreenCaptureFailure.SERVICE_DISCONNECTED, (result as ScreenCaptureResult.Failure).reason)
        assertEquals("ACCESSIBILITY_SERVICE_UNAVAILABLE", source.diagnose()?.code)
    }

    @Test
    fun neverEnabledServiceReportsNotAuthorizedInsteadOfDisconnection() = runBlocking {
        val source = AccessibilityFirstScreenCaptureSource(
            FakeAccessibility(enabledInSettings = false, connected = false),
            FakeProjection(available = false),
        )
        // 从未申请授权是正常起点，不是错误，更不是不可恢复的阻塞。
        assertEquals(ScreenCaptureFailure.NOT_AUTHORIZED, source.diagnose())
        assertTrue(source.diagnose()!!.isRecoverable)
        assertEquals("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED", source.diagnose()?.code)
    }

    @Test
    fun systemRejectionRateLimitAndSecureWindowStayDistinguishable() = runBlocking {
        suspend fun diagnose(failure: ScreenCaptureFailure): ScreenCaptureFailure? {
            val source = AccessibilityFirstScreenCaptureSource(
                FakeAccessibility(outcome = { ScreenCaptureOutcome.Failure(failure) }),
                FakeProjection(available = false),
            )
            source.captureDetailed()
            return source.lastFailure
        }

        assertEquals(ScreenCaptureFailure.SYSTEM_REJECTED, diagnose(ScreenCaptureFailure.SYSTEM_REJECTED))
        assertEquals(ScreenCaptureFailure.RATE_LIMITED, diagnose(ScreenCaptureFailure.RATE_LIMITED))
        assertEquals(ScreenCaptureFailure.SECURE_WINDOW, diagnose(ScreenCaptureFailure.SECURE_WINDOW))
        assertEquals(ScreenCaptureFailure.CAPTURE_FAILED, diagnose(ScreenCaptureFailure.CAPTURE_FAILED))
    }

    @Test
    fun rateLimitAndSystemRejectionDoNotFallBackToProjection() = runBlocking {
        // 系统拒绝/受保护窗口换路也拿不到像素；频率限制换路会多消耗一次会话，
        // 更容易触发系统回收。因此这三类一律不回退。
        listOf(
            ScreenCaptureFailure.RATE_LIMITED,
            ScreenCaptureFailure.SYSTEM_REJECTED,
            ScreenCaptureFailure.SECURE_WINDOW,
        ).forEach { failure ->
            val accessibility = FakeAccessibility(outcome = { ScreenCaptureOutcome.Failure(failure) })
            val projection = FakeProjection(available = true)
            val source = AccessibilityFirstScreenCaptureSource(accessibility, projection)
            val result = source.captureDetailed()
            assertEquals(failure, (result as ScreenCaptureResult.Failure).reason)
            assertEquals("$failure 不应回退到投屏", 0, projection.captures)
        }
    }

    @Test
    fun captureFailureFallsBackToPreviouslyConsentedProjection() = runBlocking {
        // 只有「本次取帧失败」值得换到用户此前已明确同意过的备用路径。
        val accessibility = FakeAccessibility(outcome = { ScreenCaptureOutcome.Failure(ScreenCaptureFailure.CAPTURE_FAILED) })
        val projection = FakeProjection(available = true)
        val source = AccessibilityFirstScreenCaptureSource(accessibility, projection)

        val result = source.captureDetailed()
        assertTrue(result is ScreenCaptureResult.Success)
        assertEquals(ScreenCaptureOrigin.MEDIA_PROJECTION, (result as ScreenCaptureResult.Success).origin)
        assertEquals(1, projection.captures)
    }

    @Test
    fun lockedDeviceIsReportedBeforeAnyCaptureAttempt() = runBlocking {
        val accessibility = FakeAccessibility()
        val source = AccessibilityFirstScreenCaptureSource(
            accessibility, FakeProjection(),
            deviceLocked = { true },
        )
        val result = source.captureDetailed()
        assertEquals(ScreenCaptureFailure.DEVICE_LOCKED, (result as ScreenCaptureResult.Failure).reason)
        // 锁屏时不发系统截图请求：既拿不到画面，也会污染频率预算。
        assertEquals(0, accessibility.captures)
    }

    @Test
    fun unsupportedPlatformReportsUnsupportedRatherThanNotAuthorized() = runBlocking {
        val source = AccessibilityFirstScreenCaptureSource(
            FakeAccessibility(enabledInSettings = true, connected = false),
            FakeProjection(available = false),
            accessibilityScreenshotSupported = { false },
        )
        val diagnosis = source.diagnose()
        assertEquals(ScreenCaptureFailure.UNSUPPORTED, diagnosis)
        // 设备事实，重试没有意义 —— 这是唯一真正不可恢复的一类。
        assertFalse(diagnosis!!.isRecoverable)
    }

    @Test
    fun projectionAuthorizationStillRequiresExplicitSystemConsent() = runBlocking {
        val projection = FakeProjection(available = false)
        val source = AccessibilityFirstScreenCaptureSource(FakeAccessibility(connected = false), projection)

        // 没有系统授权时，来源不得声称自己可用。
        assertFalse(source.isAvailable)
        assertTrue(source.createAuthorizationIntent() != null)
        assertFalse("用户拒绝后仍必须不可用", source.onAuthorizationResult(Activity.RESULT_CANCELED, null))
        assertEquals(ScreenCaptureFailure.SYSTEM_REJECTED, source.lastFailure)
        assertTrue("用户同意后才可用", source.onAuthorizationResult(Activity.RESULT_OK, Intent("result")))
    }

    @Test
    fun capabilityDeclarationMissingIsReportedAsUnsupportedNotAsConsentProblem() = runBlocking {
        // 服务连着但没声明 canTakeScreenshot：这是配置事实，不是「没授权」，
        // 引导用户去申请权限只会白费一次点击。
        val source = AccessibilityFirstScreenCaptureSource(
            FakeAccessibility(enabledInSettings = true, connected = true, hasCapability = false),
            FakeProjection(available = false),
        )
        assertEquals(ScreenCaptureFailure.UNSUPPORTED, source.diagnose())
    }

    @Test
    fun failureCodesAreStableProtocolConstants() {
        // 这些码会成为 reason 出现在 Core 的 inventory 里，不能随文案改动。
        assertEquals("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED", ScreenCaptureFailure.NOT_AUTHORIZED.code)
        assertEquals("ACCESSIBILITY_SERVICE_UNAVAILABLE", ScreenCaptureFailure.SERVICE_DISCONNECTED.code)
        assertEquals("SCREEN_CAPTURE_SYSTEM_REJECTED", ScreenCaptureFailure.SYSTEM_REJECTED.code)
        assertEquals("SCREEN_CAPTURE_SECURE_WINDOW", ScreenCaptureFailure.SECURE_WINDOW.code)
        assertEquals("SCREEN_CAPTURE_RATE_LIMITED", ScreenCaptureFailure.RATE_LIMITED.code)
        assertEquals("SCREEN_CAPTURE_FAILED", ScreenCaptureFailure.CAPTURE_FAILED.code)
        assertEquals("SCREEN_DEVICE_LOCKED", ScreenCaptureFailure.DEVICE_LOCKED.code)
        assertEquals("SCREEN_CAPTURE_UNSUPPORTED", ScreenCaptureFailure.UNSUPPORTED.code)
        // Core 的 inventory 契约要求 reason 匹配 [A-Z0-9_]{0,128}。
        ScreenCaptureFailure.entries.forEach {
            assertTrue("${it.code} 必须符合协议字符集", Regex("[A-Z0-9_]{0,128}").matches(it.code))
        }
    }

    @Test
    fun onlyUnsupportedIsUnrecoverableBecauseEverythingElseHasAUserAction() {
        // 问题三的核心要求：不能把可正常申请/可换界面绕开的情形长期标成不可恢复。
        // 受保护窗口换界面即可、限速等一会即可、服务断连可重新开启、系统拒绝可重新授权。
        ScreenCaptureFailure.entries.forEach { failure ->
            assertEquals(
                "$failure 可恢复性判定错误",
                failure != ScreenCaptureFailure.UNSUPPORTED,
                failure.isRecoverable,
            )
        }
        assertTrue("受保护窗口必须可恢复", ScreenCaptureFailure.SECURE_WINDOW.isRecoverable)
        assertFalse(ScreenCaptureFailure.UNSUPPORTED.isRecoverable)
    }

    @Test
    fun systemScreenshotErrorCodesMapToTheRightDiagnosis() {
        // 数值取自 API 36 的 AccessibilityService 常量。
        assertEquals(ScreenCaptureFailure.RATE_LIMITED, accessibilityScreenshotFailure(3))
        assertEquals(ScreenCaptureFailure.SECURE_WINDOW, accessibilityScreenshotFailure(6))
        assertEquals(ScreenCaptureFailure.SYSTEM_REJECTED, accessibilityScreenshotFailure(2))
        assertEquals(ScreenCaptureFailure.CAPTURE_FAILED, accessibilityScreenshotFailure(1))
        assertEquals(ScreenCaptureFailure.CAPTURE_FAILED, accessibilityScreenshotFailure(4))
        assertEquals(ScreenCaptureFailure.CAPTURE_FAILED, accessibilityScreenshotFailure(5))
        assertEquals(ScreenCaptureFailure.CAPTURE_FAILED, accessibilityScreenshotFailure(99))
    }

    @Test
    fun releaseClearsDiagnosisAndDropsProjectionSession() = runBlocking {
        val projection = FakeProjection(available = true)
        val source = AccessibilityFirstScreenCaptureSource(FakeAccessibility(), projection)
        assertEquals(ScreenCaptureOrigin.ACCESSIBILITY, source.preferredOrigin)
        source.release()
        assertEquals(1, projection.releases)
        assertNull(source.lastFailure)
    }

    private companion object {
        fun frame(): ScreenCapture = ScreenCapture.copyOf(ByteArray(64) { 7 }, 100, 200)
    }
}