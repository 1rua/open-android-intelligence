package com.openandroidintelligence.mobile.plugins

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 问题二的回归测试：设备能力授权入口必须由插件声明**动态推导**。
 *
 * 修复前的缺陷是只处理 READ_SMS 与 READ_CALL_LOG —— 无障碍、通知读取、
 * 屏幕截图三类能力在「检查 Android 系统权限」里根本没有入口，用户点了
 * 按钮只会看到一句「已就绪」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceCapabilityPermissionsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------
    // 推导：只按声明，不硬编码 Agent / Gateway
    // ------------------------------------------------------------------

    @Test
    fun declaredPrimitivesProduceMatchingAndroidGrantRequirements() {
        val sms = androidPermissionRequirements(setOf("kernel.sms.read"))
        assertEquals(listOf("sms"), sms.map { it.key })
        assertEquals(DeviceCapabilityGrantKind.RUNTIME_PERMISSION, sms.single().grantKind)
        assertEquals(Manifest.permission.READ_SMS, sms.single().manifestPermission)

        val callLog = androidPermissionRequirements(setOf("kernel.call-log.read"))
        assertEquals(listOf("call-log"), callLog.map { it.key })
        assertEquals(Manifest.permission.READ_CALL_LOG, callLog.single().manifestPermission)
    }

    @Test
    fun accessibilityBackedPrimitivesRequireTheAccessibilitySettingsPage() {
        // 屏幕读取与控制都靠语义树，没有无障碍服务就完全不可用。
        listOf("kernel.screen.read", "kernel.screen.control").forEach { primitive ->
            val requirement = androidPermissionRequirements(setOf(primitive)).single()
            assertEquals(DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE, requirement.grantKind)
            assertTrue("应跳无障碍设置", requirement.opensAccessibilitySettings())
            assertFalse("不该跳通知使用权", requirement.opensNotificationAccessSettings())
            assertFalse("不该弹投屏对话框", requirement.opensMediaProjectionDialog())
            // 无障碍服务不是运行时权限：必须没有可申请的 manifest 权限，
            // 否则界面会错误地调用权限申请对话框。
            assertNull(requirement.manifestPermission)
        }
    }

    @Test
    fun notificationPrimitiveRequiresNotificationAccessRatherThanARuntimePrompt() {
        val requirement = androidPermissionRequirements(setOf("kernel.notifications.read")).single()
        assertEquals(DeviceCapabilityGrantKind.NOTIFICATION_ACCESS, requirement.grantKind)
        assertTrue(requirement.opensNotificationAccessSettings())
        assertNull(requirement.manifestPermission)
    }

    @Test
    fun screenCaptureRequiresTheForegroundProjectionDialogNotAnUnrelatedSettingsPage() {
        val requirement = androidPermissionRequirements(setOf("kernel.screen.capture")).single()
        assertEquals(DeviceCapabilityGrantKind.MEDIA_PROJECTION, requirement.grantKind)
        assertTrue("必须由前台 Activity 发起系统对话框", requirement.opensMediaProjectionDialog())
        // 关键回归：之前没有任何入口，或跳去无关的设置页；这三种都不是投屏。
        assertFalse(requirement.opensAccessibilitySettings())
        assertFalse(requirement.opensNotificationAccessSettings())
        assertNull(requirement.manifestPermission)
    }

    @Test
    fun undeclaredPrimitivesProduceNoEntriesAtAll() {
        // 「不请求不存在或尚不需要的权限」：空声明必须得到空列表，而不是全套默认项。
        assertTrue(androidPermissionRequirements(emptySet()).isEmpty())
        assertTrue(androidPermissionRequirements(setOf("kernel.background.run")).isEmpty())
        assertTrue(androidPermissionRequirements(setOf("kernel.unknown.future")).isEmpty())
    }

    @Test
    fun combinedDeclarationsProduceEveryRequiredEntryOnce() {
        val requirements = androidPermissionRequirements(
            setOf("kernel.sms.read", "kernel.call-log.read", "kernel.notifications.read",
                "kernel.screen.read", "kernel.screen.capture"),
        )
        assertEquals(
            listOf("sms", "call-log", "notifications", "accessibility", "screen-capture"),
            requirements.map { it.key },
        )
        // 屏幕读取与控制同时声明时只能出现一条无障碍入口。
        val both = androidPermissionRequirements(
            setOf("kernel.screen.read", "kernel.screen.control"),
        )
        assertEquals(listOf("accessibility"), both.map { it.key })
    }

    // ------------------------------------------------------------------
    // 状态：已授权不重复申请；可恢复性判定正确
    // ------------------------------------------------------------------

    private fun inspector(
        notificationAccess: Boolean = false,
        accessibility: Boolean = false,
        capture: Boolean = false,
        denied: Set<String> = emptySet(),
    ) = DeviceCapabilityGrantInspector(
        context = context,
        notificationAccessGranted = { notificationAccess },
        accessibilityEnabled = { accessibility },
        captureAuthorized = { capture },
        deniedRuntimePermissions = { it in denied },
    )

    @Test
    fun grantedRuntimePermissionIsReportedGrantedAndNeverReRequested() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_SMS)
        val requirement = androidPermissionRequirements(setOf("kernel.sms.read")).single()
        val inspector = inspector()

        assertEquals(DeviceCapabilityGrantState.GRANTED, inspector.state(requirement))
        // 已授权时不得再出现在待申请列表里 —— 避免无意义弹窗。
        assertTrue(inspector.pending(listOf(requirement)).isEmpty())
        assertTrue(inspector.runtimePermissionsToRequest(listOf(requirement)).isEmpty())
    }

    @Test
    fun ungrantedRuntimePermissionIsPendingAndRequestable() {
        val requirement = androidPermissionRequirements(setOf("kernel.call-log.read")).single()
        val inspector = inspector()

        assertEquals(DeviceCapabilityGrantState.NOT_REQUESTED, inspector.state(requirement))
        assertEquals(listOf(requirement), inspector.pending(listOf(requirement)))
        assertEquals(
            listOf(Manifest.permission.READ_CALL_LOG),
            inspector.runtimePermissionsToRequest(listOf(requirement)),
        )
    }

    @Test
    fun previouslyDeniedPermissionIsNotRequestedAgain() {
        val requirement = androidPermissionRequirements(setOf("kernel.sms.read")).single()
        val inspector = inspector(denied = setOf(Manifest.permission.READ_SMS))

        assertEquals(DeviceCapabilityGrantState.DENIED, inspector.state(requirement))
        // 永久拒绝后再弹框在多数系统上毫无反应，必须改为引导去设置页。
        assertTrue(
            "已拒绝的权限不应再次进入申请列表",
            inspector.runtimePermissionsToRequest(listOf(requirement)).isEmpty(),
        )
    }

    @Test
    fun settingsBackedGrantsReportRealStateInsteadOfAssumingPending() {
        var accessibility = false
        var notifications = false
        val live = DeviceCapabilityGrantInspector(
            context = context,
            notificationAccessGranted = { notifications },
            accessibilityEnabled = { accessibility },
            captureAuthorized = { false },
        )
        val accessibilityRequirement = androidPermissionRequirements(setOf("kernel.screen.read")).single()
        val notificationRequirement = androidPermissionRequirements(setOf("kernel.notifications.read")).single()

        assertEquals(DeviceCapabilityGrantState.REQUIRES_SETTINGS, live.state(accessibilityRequirement))
        // 从系统设置返回后重新查询就能拿到最新结果（同一个 inspector 实例）。
        accessibility = true
        notifications = true
        assertEquals(DeviceCapabilityGrantState.GRANTED, live.state(accessibilityRequirement))
        assertEquals(DeviceCapabilityGrantState.GRANTED, live.state(notificationRequirement))
        assertTrue(live.pending(listOf(accessibilityRequirement, notificationRequirement)).isEmpty())
    }

    @Test
    fun mediaProjectionIsReportedAsAskedOnUseRatherThanBlocked() {
        val requirement = androidPermissionRequirements(setOf("kernel.screen.capture")).single()
        val inspector = inspector(capture = false)

        // 投屏没有可持久查询的状态：如实标为「使用时询问」，而不是假装未授权或阻塞。
        assertEquals(DeviceCapabilityGrantState.ASKED_ON_USE, inspector.state(requirement))
        // 它不属于运行时权限，绝不能混进系统权限申请对话框。
        assertTrue(inspector.runtimePermissionsToRequest(listOf(requirement)).isEmpty())
    }

    @Test
    fun authorizedCaptureIsReportedGranted() {
        val requirement = androidPermissionRequirements(setOf("kernel.screen.capture")).single()
        assertEquals(
            DeviceCapabilityGrantState.GRANTED,
            inspector(capture = true).state(requirement),
        )
    }

    // ------------------------------------------------------------------
    // 无法自动跳转时的准确手动引导
    // ------------------------------------------------------------------

    @Test
    fun manualGuidanceNamesTheExactSystemPathForEachGrantKind() {
        val accessibility = manualGrantGuidance(
            androidPermissionRequirements(setOf("kernel.screen.read")).single(),
        )
        assertTrue(accessibility.contains("无障碍"))
        // 只说「无法打开设置」等于把用户丢在原地，必须给出可达路径。
        assertTrue("应说明入口路径", accessibility.contains("系统设置"))

        val notification = manualGrantGuidance(
            androidPermissionRequirements(setOf("kernel.notifications.read")).single(),
        )
        assertTrue(notification.contains("通知使用权"))

        val capture = manualGrantGuidance(
            androidPermissionRequirements(setOf("kernel.screen.capture")).single(),
        )
        assertTrue("投屏应引导重新发起授权而不是跳设置", capture.contains("授权"))

        val sms = manualGrantGuidance(androidPermissionRequirements(setOf("kernel.sms.read")).single())
        assertTrue(sms.contains("权限"))
    }

    @Test
    fun permissionStateReflectsRealSystemGrant() {
        // 直接验证查询走的是系统真实状态，而不是缓存或默认值。
        shadowOf(context).denyPermissions(Manifest.permission.READ_SMS)
        val requirement = androidPermissionRequirements(setOf("kernel.sms.read")).single()
        assertTrue(
            inspector().state(requirement) != DeviceCapabilityGrantState.GRANTED,
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.READ_SMS),
        )
    }
}