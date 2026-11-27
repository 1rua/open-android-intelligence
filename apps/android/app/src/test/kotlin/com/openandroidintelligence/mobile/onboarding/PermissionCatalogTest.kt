package com.openandroidintelligence.mobile.onboarding

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.screen.accessibilityServiceClassName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 权限目录的可见性与排序测试。
 *
 * 这些用例锁定的是需求的硬约束，不是实现细节：
 * - 「不请求不存在或尚不需要的权限」→ 插件未启用时，对应卡片必须**不出现**；
 * - 「拒绝权限不应阻断无关功能」→ 任何权限都不得被标记为登录前置条件；
 * - 布局稳定 → 分组顺序固定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionCatalogTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    /** 引导展示完整的基础功能、可选增强以及设备能力项。 */
    @Test
    fun `visible permissions expose standard items`() {
        val entries = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())

        assertEquals(
            listOf(
                "notifications",
                "microphone",
                "screen_capture",
                "assistant_role",
                "sms",
                "call_log",
                "notification_access",
            ),
            entries.map { it.id },
        )
        assertTrue(entries.any { it.group == PermissionGroup.DEVICE_CAPABILITY })
    }

    /**
     * 默认数字助理卡片必须声明为**系统角色**而不是运行时权限。
     *
     * 这条区分直接决定界面行为：角色项走 RoleManager 对话框/系统设置，
     * 运行时权限走系统权限弹窗；混在一起就会出现「点了弹不出任何东西」。
     */
    @Test
    fun `assistant role card declares system role and no manifest permission`() {
        val entries = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())
        val role = entries.first { it.id == "assistant_role" }

        assertEquals(SystemRole.ASSISTANT, role.systemRole)
        assertEquals(PermissionGroup.OPTIONAL, role.group)
        assertTrue("只能是系统设置/角色授予", role.isSpecialSystemAccess)
        assertTrue("系统角色不是运行时权限", role.manifestPermissions.isEmpty())
        assertTrue("系统角色同样不能阻断登录", !role.requiredForLogin)
    }

    /** 只有默认数字助理是系统角色项：其余权限不得被误标。 */
    @Test
    fun `only assistant role entry uses a system role`() {
        val entries = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())
        assertEquals(
            listOf("assistant_role"),
            entries.filter { it.systemRole != null }.map { it.id },
        )
    }

    /** 短信权限与通知使用权均被声明并展示。 */
    @Test
    fun `sms and notification access are present`() {
        val entries = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())
        assertTrue(entries.any { it.id == "sms" })
        val entry = entries.firstOrNull { it.id == "notification_access" }
        assertTrue("通知使用权应当出现", entry != null)
        assertTrue("通知使用权应标记为特殊系统访问", entry!!.isSpecialSystemAccess)
    }

    /** 通话记录权限被声明并展示。 */
    @Test
    fun `call log permission is present`() {
        val entries = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())
        assertTrue(entries.any { it.id == "call_log" })
    }

    /** 分组顺序固定：REQUIRED → OPTIONAL → DEVICE_CAPABILITY。 */
    @Test
    fun `group order is stable`() {
        val entries = buildVisiblePermissions(
            androidSdk = 34,
            enabledPluginPrimitives = setOf(KERNEL_SMS_READ, KERNEL_NOTIFICATIONS_READ),
        )
        val ordinals = entries.map { it.group.ordinal }
        assertEquals("分组必须按枚举顺序排列", ordinals.sorted(), ordinals)
        assertEquals(PermissionGroup.REQUIRED, entries.first().group)
    }

    /** 任何权限都不是登录前置：拒绝权限不得阻断登录。 */
    @Test
    fun `no permission is required for login`() {
        val entries = buildVisiblePermissions(
            androidSdk = 34,
            enabledPluginPrimitives = setOf(KERNEL_SMS_READ, KERNEL_CALL_LOG_READ, KERNEL_NOTIFICATIONS_READ),
        )
        assertTrue(
            "没有任何系统权限可以阻断登录",
            entries.all { !it.requiredForLogin },
        )
    }

    /** Android 13 以下没有 POST_NOTIFICATIONS 运行时权限，不应申请它。 */
    @Test
    fun `notification permission is not requested below android 13`() {
        val entries = buildVisiblePermissions(androidSdk = 32, enabledPluginPrimitives = emptySet())
        val notification = entries.first { it.id == "notifications" }
        assertTrue(
            "API 32 及以下不应申请 POST_NOTIFICATIONS",
            notification.manifestPermissions.isEmpty(),
        )
    }

    /** Android 13+ 才申请 POST_NOTIFICATIONS。 */
    @Test
    fun `notification permission is requested from android 13`() {
        val entries = buildVisiblePermissions(androidSdk = 33, enabledPluginPrimitives = emptySet())
        val notification = entries.first { it.id == "notifications" }
        assertTrue(
            "API 33+ 应申请 POST_NOTIFICATIONS",
            notification.manifestPermissions.contains(android.Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    /** 所有 id 唯一，避免状态映射互相覆盖。 */
    @Test
    fun `permission ids are unique`() {
        val entries = buildVisiblePermissions(
            androidSdk = 34,
            enabledPluginPrimitives = setOf(KERNEL_SMS_READ, KERNEL_CALL_LOG_READ, KERNEL_NOTIFICATIONS_READ),
        )
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
    }

    /** 每项权限都必须有非空的用途说明：卡片不允许出现空白说明。 */
    @Test
    fun `every permission explains its purpose`() {
        val entries = buildVisiblePermissions(
            androidSdk = 34,
            enabledPluginPrimitives = setOf(KERNEL_SMS_READ, KERNEL_CALL_LOG_READ, KERNEL_NOTIFICATIONS_READ),
        )
        assertTrue(entries.all { it.purposeRes != 0 })
        assertTrue(entries.all { it.displayNameRes != 0 })
        assertTrue(entries.all { context.getString(it.purposeRes).isNotBlank() })
        assertTrue(entries.all { context.getString(it.displayNameRes).isNotBlank() })
    }

    /** 屏幕采集卡取本应用的无障碍开关状态，也能感知系统撤销。 */
    @Test
    fun screenCaptureFollowsTheOwnAccessibilityServiceSetting() {
        val entry = buildVisiblePermissions(34, emptySet()).first { it.id == "screen_capture" }
        val service = ComponentName(context, accessibilityServiceClassName())
        val otherService = ComponentName("other.package", "other.package.AccessibilityService")

        assertEquals(PermissionGroup.OPTIONAL, entry.group)
        assertTrue(entry.isSpecialSystemAccess)
        assertTrue(entry.manifestPermissions.isEmpty())
        assertEquals(PermissionStatus.DENIED, resolvePermissionStatus(context, entry))

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${otherService.flattenToString()}:${service.flattenToShortString()}",
        )
        assertEquals(PermissionStatus.GRANTED, resolvePermissionStatus(context, entry))

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            otherService.flattenToString(),
        )
        assertEquals(PermissionStatus.DENIED, resolvePermissionStatus(context, entry))
    }

    @Test
    fun permissionGuidanceUsesAndroidLocalSettingsAndRetainsIndependentConsent() {
        assertTrue(PERMISSION_NOTICE_TITLE_RES != 0)
        assertTrue(PERMISSION_INTRO_BODY_RES != 0)
        assertTrue(PERMISSION_NOTICE_BODY_RES != 0)

        val noticeTitle = context.getString(PERMISSION_NOTICE_TITLE_RES)
        val introBody = context.getString(PERMISSION_INTRO_BODY_RES)
        val noticeBody = context.getString(PERMISSION_NOTICE_BODY_RES)

        assertEquals("权限 ≠ 授权", noticeTitle)
        assertTrue(introBody.contains("设备能力由本机决定"))
        assertFalse(introBody.contains("按账号单独授权"))
        assertTrue(noticeBody.contains("已关闭的设置会保留"))
        assertTrue(noticeBody.contains("旧设置需先在本机复核"))
        assertTrue(noticeBody.contains("敏感操作确认仍分别检查"))
        assertFalse(noticeBody.contains("设备能力与配对授权"))
    }
}
