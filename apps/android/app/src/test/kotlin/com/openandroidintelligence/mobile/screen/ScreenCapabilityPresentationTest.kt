package com.openandroidintelligence.mobile.screen

import com.openandroidintelligence.control.ScreenPermission
import com.openandroidintelligence.control.ScreenSessionEndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕能力界面的呈现规则。
 *
 * 这里锁住的是「诚实性」而不是像素：
 * 1. 三层授权（系统权限 / 插件授权 / 本地会话）不能被折叠成一个布尔值；
 * 2. 权限未授予时必须指名缺的是什么，而不是笼统说「未授权」；
 * 3. 内部错误码绝不直接出现在用户可见文案里。
 */
class ScreenCapabilityPresentationTest {

    private fun readySession(vararg permissions: ScreenPermission) = ScreenSessionPresentation(
        permissions = permissions.toSet(),
        expiresAtEpochMs = 10_000L,
        remainingMillis = 5_000L,
    )

    @Test
    fun providerMissingIsReportedAsNotWiredInsteadOfUnavailablePermission() {
        val state = ScreenCapabilityUiState(providerAvailable = false)

        assertEquals(ScreenGate.PROVIDER_MISSING, state.gate)
        assertFalse(state.hasSession)
        assertFalse(state.accessibilityReady)
        assertFalse(state.captureSystemGranted)
        // 关键：不能说成「未授权」，因为这里根本没有任何可以授权的对象。
        assertTrue(screenSectionSummary(state).contains("未接入屏幕能力"))
        assertTrue(permissionSupporting(ScreenPermission.READ, state).contains("未接入屏幕能力"))
    }

    @Test
    fun disconnectedDeviceIsDistinguishedFromMissingProvider() {
        val state = ScreenCapabilityUiState(providerAvailable = true, connectionReady = false)

        assertEquals(ScreenGate.CONNECTION_MISSING, state.gate)
        assertTrue(screenSectionSummary(state).contains("没有连接到已配对的设备"))
        assertTrue(permissionSupporting(ScreenPermission.READ, state).contains("未连接设备"))
    }

    @Test
    fun eachPermissionCarriesItsOwnReasonInsteadOfOneGenericBoolean() {
        val state = ScreenCapabilityUiState(
            providerAvailable = true,
            connectionReady = true,
            accessibilityError = "ACCESSIBILITY_SERVICE_UNAVAILABLE",
            captureError = "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED",
        )

        assertEquals(ScreenGate.READY, state.gate)
        // 三项的说明必须互不相同：用户要能判断该去哪个系统页面。
        val supporting = ScreenPermission.entries.map { permissionSupporting(it, state) }
        assertEquals(supporting.size, supporting.toSet().size)
        assertFalse(state.accessibilityReady)
        assertFalse(state.captureSystemGranted)
        // 文案里不能出现原始错误码。
        supporting.forEach { text ->
            assertFalse("文案泄露内部错误码: $text", text.contains("SCREEN_"))
            assertFalse("文案泄露内部错误码: $text", text.contains("ACCESSIBILITY_"))
        }
    }

    @Test
    fun grantedPermissionIsReportedAsGrantedAndStillExplainsItsScope() {
        val state = ScreenCapabilityUiState(
            providerAvailable = true,
            connectionReady = true,
            session = readySession(ScreenPermission.READ),
        )

        assertTrue(state.has(ScreenPermission.READ))
        assertFalse(state.has(ScreenPermission.CAPTURE))
        assertFalse(state.has(ScreenPermission.CONTROL))
        val text = permissionSupporting(ScreenPermission.READ, state)
        assertTrue(text.startsWith("已允许"))
        // 读取许可不附带截图或控制：说明里必须写清这一项的范围。
        assertTrue(text.contains("界面结构"))
    }

    @Test
    fun expiredSessionDoesNotCountAsGranted() {
        val expired = ScreenSessionPresentation(
            permissions = setOf(ScreenPermission.READ, ScreenPermission.CONTROL),
            expiresAtEpochMs = 1_000L,
            remainingMillis = 0L,
        )
        val state = ScreenCapabilityUiState(
            providerAvailable = true,
            connectionReady = true,
            session = expired,
        )

        assertTrue(expired.isExpired)
        assertFalse(state.hasSession)
        assertFalse(state.has(ScreenPermission.READ))
        assertFalse(state.has(ScreenPermission.CONTROL))
    }

    @Test
    fun sessionSummaryShowsRemainingTimeSoUserKnowsItEnds() {
        val state = ScreenCapabilityUiState(
            providerAvailable = true,
            connectionReady = true,
            session = readySession(ScreenPermission.READ),
        )

        val summary = screenSectionSummary(state)
        assertTrue("会话进行中必须展示剩余时间: $summary", summary.contains("剩余"))
        assertTrue(summary.contains("结束会话"))
    }

    @Test
    fun captureAuthorizationHintDependsOnLocalSessionFirst() {
        // 顺序约束：没有含 CAPTURE 的本地会话时，不能先去做系统投屏授权。
        val withoutSession = ScreenCapabilityUiState(providerAvailable = true, connectionReady = true)
        assertTrue(captureAuthorizationSupporting(withoutSession).contains("先开始"))

        val withSession = ScreenCapabilityUiState(
            providerAvailable = true,
            connectionReady = true,
            session = readySession(ScreenPermission.CAPTURE),
            captureError = "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED",
        )
        assertTrue(captureAuthorizationSupporting(withSession).contains("尚未授权"))
    }

    @Test
    fun accessibilityHintReportsMissingConnectionInsteadOfReady() {
        val ready = ScreenCapabilityUiState(providerAvailable = true, connectionReady = true)
        assertTrue(accessibilitySupporting(ready).contains("已连接"))

        val broken = ready.copy(accessibilityError = "ACCESSIBILITY_SERVICE_UNAVAILABLE")
        val text = accessibilitySupporting(broken)
        assertTrue(text.contains("尚未连接"))
        assertFalse(text.contains("ACCESSIBILITY_"))
    }

    @Test
    fun everyGateValueIsReachableAndDistinct() {
        val states = listOf(
            ScreenCapabilityUiState(providerAvailable = false),
            ScreenCapabilityUiState(providerAvailable = true, connectionReady = false),
            ScreenCapabilityUiState(providerAvailable = true, connectionReady = true),
        )
        assertEquals(
            listOf(ScreenGate.PROVIDER_MISSING, ScreenGate.CONNECTION_MISSING, ScreenGate.READY),
            states.map { it.gate },
        )
    }

    @Test
    fun sessionEndReasonsHaveUserReadableExplanations() {
        val reasons = ScreenSessionEndReason.entries.map { readableSessionEndReason(it) }
        // 每个原因都要有独立说法，且不能把内部枚举名直接抛给用户。
        assertEquals(reasons.size, reasons.toSet().size)
        reasons.forEach { text ->
            assertTrue(text.isNotBlank())
            assertFalse("文案泄露内部枚举: $text", text.contains("REVOKED"))
            assertFalse("文案泄露内部枚举: $text", text.contains("BINDING_CHANGED"))
        }
    }
}

/**
 * 错误码 → 文案映射。
 *
 * 交接文档的错误表逐条覆盖：每一条都必须给出非空、非原始码、可执行的说明。
 */
class ScreenErrorMessagesTest {

    private val documentedCodes = listOf(
        // 本地授权
        "SCREEN_SESSION_REQUIRED", "SCREEN_SESSION_EXPIRED", "SCREEN_SESSION_BINDING_CHANGED",
        "SCREEN_PERMISSION_REQUIRED", "SCREEN_CONNECTION_REQUIRED",
        "SCREEN_CONFIRMATION_REQUIRED", "SCREEN_REQUEST_EXPIRED",
        // 系统服务
        "ACCESSIBILITY_SERVICE_UNAVAILABLE", "SCREEN_DEVICE_LOCKED", "SCREEN_WINDOW_UNAVAILABLE",
        "SCREEN_DISPLAY_UNAVAILABLE", "SCREEN_TARGET_NOT_INTERACTABLE",
        // 截图
        "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED", "SCREEN_CAPTURE_FAILED",
        "SCREEN_CAPTURE_ENCODING_FAILED", "SCREEN_IMAGE_TOO_LARGE",
        "SCREEN_IMAGE_LIMIT_REACHED", "SCREEN_IMAGE_UNAVAILABLE",
        // 控件与版本
        "SCREEN_STALE_REFERENCE", "SCREEN_WINDOW_CHANGED", "SCREEN_NODE_NOT_FOUND",
        "SCREEN_TARGET_NOT_EDITABLE", "SCREEN_TARGET_NOT_SCROLLABLE",
        // 平台执行
        "SCREEN_COORDINATES_INVALID", "SCREEN_ACTION_REJECTED", "SCREEN_APP_NOT_FOUND",
        "SCREEN_APP_LAUNCH_DENIED", "SCREEN_ACTION_OUTCOME_UNKNOWN",
        "SCREEN_PROVIDER_UNAVAILABLE", "SCREEN_REQUEST_IN_PROGRESS",
        "SCREEN_RESPONSE_TOO_LARGE",
        // 本地确认
        "LOCAL_CONFIRMATION_REQUIRED", "LOCAL_CAPABILITY_DENIED", "GATEWAY_AUTHORIZATION_REVOKED",
    )

    @Test
    fun everyDocumentedCodeMapsToUserReadableText() {
        documentedCodes.forEach { code ->
            val text = readableScreenError(code)
            assertTrue("$code 没有文案", text.isNotBlank())
            assertFalse("$code 的文案直接暴露了错误码: $text", text.contains(code))
            // 文案必须是中文陈述句，不能是 ERROR_LIKE 的占位。
            assertFalse("$code 的文案像错误码: $text", text.matches(Regex("[A-Z0-9_]+")))
        }
    }

    @Test
    fun codesWithDifferentCausesDoNotCollapseIntoSameAdvice() {
        // 会话失效要重新确认；设备锁屏要解锁；两者处理动作不同，不能共用一句。
        assertNotEquals(
            readableScreenError("SCREEN_SESSION_EXPIRED"),
            readableScreenError("SCREEN_DEVICE_LOCKED"),
        )
        assertNotEquals(
            readableScreenError("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED"),
            readableScreenError("SCREEN_STALE_REFERENCE"),
        )
    }

    @Test
    fun sessionFailuresAllPointAtReconfirmingConsent() {
        listOf(
            "SCREEN_SESSION_REQUIRED",
            "SCREEN_SESSION_EXPIRED",
            "SCREEN_SESSION_BINDING_CHANGED",
        ).forEach { code ->
            assertTrue(readableScreenError(code).contains("重新确认"))
        }
    }

    @Test
    fun unknownResultIsNeverPhrasedAsSuccessOrRetry() {
        val text = readableScreenError("SCREEN_ACTION_OUTCOME_UNKNOWN")
        assertTrue(text.contains("无法确认"))
        // 关键安全约束：未知结果必须明确「不要直接重试」。
        assertTrue(text.contains("不要直接重试"))
    }

    @Test
    fun nullAndUnknownCodesStillProduceSafeFallback() {
        assertTrue(readableScreenError(null).isNotBlank())
        val unknown = readableScreenError("SOME_FUTURE_CODE")
        assertTrue(unknown.isNotBlank())
        assertFalse(unknown.contains("SOME_FUTURE_CODE"))
    }

    @Test
    fun remainingTimeFormattingCoversExpiredAndMinutes() {
        assertEquals("已到期", formatRemaining(0L))
        assertEquals("已到期", formatRemaining(-5L))
        assertEquals("剩余 45 秒", formatRemaining(45_000L))
        assertEquals("剩余 4 分 5 秒", formatRemaining(245_000L))
    }

    @Test
    fun permissionCopyStatesTheIsolationBoundary() {
        // 三项权限互相独立：读取不能带出截图，文案必须写出来。
        val read = screenPermissionDescription(ScreenPermission.READ)
        assertTrue(read.contains("界面结构"))
        assertTrue(read.contains("密码"))

        val capture = screenPermissionDescription(ScreenPermission.CAPTURE)
        assertTrue(capture.contains("系统投屏"))
        assertTrue(capture.contains("不会因为允许读取而自动获得"))

        val control = screenPermissionDescription(ScreenPermission.CONTROL)
        assertTrue(control.contains("单独确认"))
    }
}

/**
 * 界面上的无障碍入口必须指向真实存在的服务组件。
 *
 * 交接文档要求前台 Activity 打开系统无障碍设置，用户在系统页里要能找到本应用的
 * 服务。如果界面里写的类名与 `screen-control` 模块 Manifest 声明的类名不一致，
 * 用户永远也开不了这个服务 —— 而界面上看不出任何异常。这里把两者钉在一起。
 */
class AccessibilityServiceWiringTest {

    @Test
    fun declaredClassNameMatchesTheAccessibilityServiceManifest() {
        val declared = accessibilityServiceClassName()
        assertEquals(
            "com.openandroidintelligence.screen.ScreenAccessibilityService",
            declared,
        )

        // 直接读 screen-control 模块的 Manifest：无障碍服务在那里声明，经 Manifest
        // 合并进入 APK。测试工作目录随 Gradle 配置变化，因此向上逐级查找，而不是
        // 赌某一个相对路径。
        val manifest = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .take(8)
            .map { java.io.File(it, "screen-control/src/main/AndroidManifest.xml") }
            .firstOrNull { it.exists() }
        org.junit.Assert.assertNotNull(
            "找不到 screen-control 的 AndroidManifest.xml（无障碍服务应在该模块声明）",
            manifest,
        )
        val xml = manifest!!.readText()
        assertTrue(
            "Manifest 中没有声明 $declared",
            xml.contains(declared),
        )
        assertTrue(
            "无障碍服务必须由 BIND_ACCESSIBILITY_SERVICE 保护",
            xml.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"),
        )
    }
}
