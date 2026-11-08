package com.openandroidintelligence.mobile.onboarding

import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

/**
 * 「默认数字助理」系统角色适配层的契约测试。
 *
 * 锁定的硬约束：
 * 1. 支持性与持有性必须**分开**判定：系统没有该角色时是「不支持」（UNSUPPORTED），
 *    不能混成「未设置」，否则界面会给用户一个点了没反应的按钮；
 * 2. 持有状态只信系统的 `isRoleHeld`，不使用本地缓存；
 * 3. 拿不到角色申请 Intent 时必须给出可用的回退设置页，不能两头都断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34, 35, 36], application = Application::class)
class AssistantRoleTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val roleManager get() = context.getSystemService(Context.ROLE_SERVICE) as RoleManager

    @Before
    fun clearRoles() {
        // Robolectric 的角色状态是跨用例共享的静态表：不清掉会串到别的用例。
        ShadowRoleManager.reset()
    }

    @Test
    fun roleNameIsTheSystemAssistantRole() {
        assertEquals(RoleManager.ROLE_ASSISTANT, SystemRole.ASSISTANT.roleName)
        assertEquals("android.app.role.ASSISTANT", SystemRole.ASSISTANT.roleName)
    }

    /** 系统里没有该角色时：不支持，且不声称持有。 */
    @Test
    fun unsupportedWhenSystemDoesNotExposeTheRole() {
        assertFalse(AssistantRole.isAssistantRoleSupported(context))
        assertFalse(AssistantRole.isAssistantRoleHeld(context))
    }

    /** 系统提供该角色但未授予：支持、未持有。 */
    @Test
    fun supportedButNotHeldWhenRoleIsAvailableOnly() {
        shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_ASSISTANT)

        assertTrue(AssistantRole.isAssistantRoleSupported(context))
        assertFalse(AssistantRole.isAssistantRoleHeld(context))
    }

    /** 角色已授予：支持且持有。 */
    @Test
    fun heldWhenSystemReportsTheRoleIsHeld() {
        shadowOf(roleManager).addHeldRole(RoleManager.ROLE_ASSISTANT)

        assertTrue(AssistantRole.isAssistantRoleSupported(context))
        assertTrue(AssistantRole.isAssistantRoleHeld(context))
    }

    /** 撤销后必须立刻变成「未持有」：不允许任何本地缓存把状态粘住。 */
    @Test
    fun revocationIsReflectedImmediately() {
        shadowOf(roleManager).addHeldRole(RoleManager.ROLE_ASSISTANT)
        assertTrue(AssistantRole.isAssistantRoleHeld(context))

        shadowOf(roleManager).removeHeldRole(RoleManager.ROLE_ASSISTANT)
        assertFalse(AssistantRole.isAssistantRoleHeld(context))
    }

    /** 不支持时不提供角色申请 Intent：调用方必须能识别「弹不出来」并走回退。 */
    @Test
    fun requestIntentIsNullWhenRoleIsUnsupported() {
        assertNull(AssistantRole.createRequestRoleIntent(context))
    }

    /** 支持时给出真正的 REQUEST_ROLE Intent，且带上角色名。 */
    @Test
    fun requestIntentTargetsRequestRoleWithAssistantRoleName() {
        shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_ASSISTANT)

        val intent = AssistantRole.createRequestRoleIntent(context)

        assertNotNull("支持该角色时必须给出申请入口", intent)
        assertEquals("android.app.role.action.REQUEST_ROLE", intent!!.action)
        assertEquals(RoleManager.ROLE_ASSISTANT, intent.getStringExtra("android.intent.extra.ROLE_NAME"))
    }

    /** 回退设置页始终可用（不需要设备支持角色）：直达「默认数字助理」设置。 */
    @Test
    fun fallbackSettingsIntentOpensVoiceInputSettingsAsNewTask() {
        val intent = AssistantRole.createFallbackSettingsIntent()

        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, intent.action)
        assertTrue(
            "非 Activity 上下文也要能启动",
            intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    /** 状态映射：不支持 → UNSUPPORTED，支持未持有 → DENIED，已持有 → GRANTED。 */
    @Test
    fun permissionStatusMapsRoleStatesWithoutConflatingUnsupportedAndDenied() {
        val entry = buildVisiblePermissions(androidSdk = 34, enabledPluginPrimitives = emptySet())
            .first { it.id == "assistant_role" }

        assertEquals(PermissionStatus.UNSUPPORTED, resolvePermissionStatus(context, entry))

        shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_ASSISTANT)
        assertEquals(PermissionStatus.DENIED, resolvePermissionStatus(context, entry))

        shadowOf(roleManager).addHeldRole(RoleManager.ROLE_ASSISTANT)
        assertEquals(PermissionStatus.GRANTED, resolvePermissionStatus(context, entry))
    }
}
