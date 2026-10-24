package com.openandroidintelligence.mobile.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** 空插件集：只有三项基础/增强权限，设备能力组整体缺席。 */
    @Test
    fun `empty plugin set exposes only base permissions`() {
        val entries = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())

        assertEquals(
            listOf("notifications", "microphone", "screen_capture"),
            entries.map { it.id },
        )
        // 设备能力组一项都不该出现 —— 不是禁用态，是不存在。
        assertTrue(entries.none { it.group == PermissionGroup.DEVICE_CAPABILITY })
    }

    /** 短信插件启用后，短信权限才出现；未启用时不得出现。 */
    @Test
    fun `sms permission appears only when plugin primitive is enabled`() {
        val withSms = buildVisiblePermissions(
            androidSdk = 34,
            enabledPluginPrimitives = setOf(KERNEL_SMS_READ),
        )
        assertTrue(withSms.any { it.id == "sms" })

        val withoutSms = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())
        assertTrue(withoutSms.none { it.id == "sms" })
    }

    /** 通知插件启用后，通知使用权才出现。 */
    @Test
    fun `notification access appears only when notifications plugin is enabled`() {
        val entries = buildVisiblePermissions(
            androidSdk = 34,
            enabledPluginPrimitives = setOf(KERNEL_NOTIFICATIONS_READ),
        )
        val entry = entries.firstOrNull { it.id == "notification_access" }
        assertTrue("通知使用权应当出现", entry != null)
        // 特殊系统访问：必须走系统设置页，而不是运行时弹窗。
        assertTrue("通知使用权应标记为特殊系统访问", entry!!.isSpecialSystemAccess)
    }

    /** 通话记录同理。 */
    @Test
    fun `call log permission follows its plugin primitive`() {
        val entries = buildVisiblePermissions(
            androidSdk = 34,
            enabledPluginPrimitives = setOf(KERNEL_CALL_LOG_READ),
        )
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
}
