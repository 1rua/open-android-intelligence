package com.openandroidintelligence.mobile.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 权限目录的可见性与排序测试。
 *
 * 这些用例锁定的是需求的硬约束，不是实现细节：
 * - 「不请求不存在或尚不需要的权限」→ 插件未启用时，对应卡片必须**不出现**；
 * - 「拒绝权限不应阻断无关功能」→ 任何权限都不得被标记为登录前置条件；
 * - 布局稳定 → 分组顺序固定。
 */
class PermissionCatalogTest {

    /** 引导展示完整的基础功能、可选增强以及设备能力项。 */
    @Test
    fun `visible permissions expose standard items`() {
        val entries = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())

        assertEquals(
            listOf("notifications", "microphone", "screen_capture", "sms", "call_log", "notification_access"),
            entries.map { it.id },
        )
        assertTrue(entries.any { it.group == PermissionGroup.DEVICE_CAPABILITY })
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
        assertTrue(entries.all { it.purpose.isNotBlank() })
        assertTrue(entries.all { it.displayName.isNotBlank() })
    }

    /**
     * 回归测试：通知使用权必须保留「可去系统设置开启」的路径。
     *
     * 对应缺陷：曾把「特殊系统访问 + 无 manifest 权限」统一当作「每次使用询问」，
     * 导致通知使用权卡片没有设置按钮，用户无法完成授权。
     * 判定差异：只有 screen_capture 是真正无设置开关的现场确认型权限。
     */
    @Test
    fun `notification access stays actionable while screen capture is dialog only`() {
        val entries = buildVisiblePermissions(
            androidSdk = 34,
            enabledPluginPrimitives = setOf(KERNEL_NOTIFICATIONS_READ),
        )
        val notificationAccess = entries.first { it.id == "notification_access" }
        val screenCapture = entries.first { it.id == "screen_capture" }

        // 两者都是特殊系统访问、都没有 manifest 权限……
        assertTrue(notificationAccess.isSpecialSystemAccess)
        assertTrue(notificationAccess.manifestPermissions.isEmpty())
        assertTrue(screenCapture.isSpecialSystemAccess)
        assertTrue(screenCapture.manifestPermissions.isEmpty())

        // ……但只有屏幕采集属于「每次使用现场确认」，通知使用权必须在设置里可开启。
        assertEquals("notification_access", notificationAccess.id)
        assertEquals("screen_capture", screenCapture.id)
        assertTrue(
            "通知使用权不应被当作无需操作的权限",
            notificationAccess.id != "screen_capture",
        )
    }

    @Test
    fun permissionGuidanceUsesAndroidLocalSettingsAndRetainsIndependentConsent() {
        assertTrue(PERMISSION_INTRO_BODY.contains("设备能力由本机决定"))
        assertFalse(PERMISSION_INTRO_BODY.contains("按账号单独授权"))
        assertTrue(PERMISSION_NOTICE_BODY.contains("已关闭的设置会保留"))
        assertTrue(PERMISSION_NOTICE_BODY.contains("旧设置需先在本机复核"))
        assertTrue(PERMISSION_NOTICE_BODY.contains("敏感操作确认仍分别检查"))
        assertFalse(PERMISSION_NOTICE_BODY.contains("设备能力与配对授权"))
    }
}
