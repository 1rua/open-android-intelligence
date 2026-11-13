package com.openandroidintelligence.mobile.onboarding

import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
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

    /**
     * 回退设置页必须按「官方默认应用页 → 数字助理设置 → 应用管理」的优先级给出，
     * 并且只在系统真的能解析时才出现：给出一个不存在的页面等于让用户点了没反应。
     */
    @Test
    fun fallbackSettingsIntentsAreOrderedAndOnlyIncludeResolvablePages() {
        val packageManager = shadowOf(context.packageManager)

        // 只注册「默认数字助理」设置页：官方默认应用页在本机不存在。
        packageManager.addResolveInfoForIntent(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "com.android.settings.Settings\$VoiceInputSettingsActivity"
                }
            },
        )

        val intents = AssistantRole.fallbackSettingsIntents(context)

        assertEquals(listOf(Settings.ACTION_VOICE_INPUT_SETTINGS), intents.map { it.action })
        assertTrue(
            "回退页不能带 NEW_TASK：for-result 拿不到结果，也无法判断用户是否真的设置完成",
            intents.none { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 },
        )
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, AssistantRole.createFallbackSettingsIntent(context)?.action)
    }

    /** 一个设置页都解析不到时返回空/ null：调用方必须据此呈现「不支持」，而不是留个死按钮。 */
    @Test
    fun noResolvableSettingsPageIsReportedAsAbsent() {
        assertTrue(AssistantRole.fallbackSettingsIntents(context).isEmpty())
        assertNull(AssistantRole.createFallbackSettingsIntent(context))
    }

    /** 官方默认应用页可用时排在第一位（API 34+）。 */
    @Test
    @Config(sdk = [34])
    fun theOfficialDefaultAppsPageWinsWhenAvailable() {
        val packageManager = shadowOf(context.packageManager)
        listOf(
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_VOICE_INPUT_SETTINGS,
        ).forEach { action ->
            packageManager.addResolveInfoForIntent(
                Intent(action),
                ResolveInfo().apply {
                    activityInfo = ActivityInfo().apply {
                        packageName = "com.android.settings"
                        name = "com.android.settings.$action"
                    }
                },
            )
        }

        assertEquals(
            listOf(
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                Settings.ACTION_VOICE_INPUT_SETTINGS,
            ),
            AssistantRole.fallbackSettingsIntents(context).map { it.action },
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
