package com.openandroidintelligence.mobile.onboarding

import android.app.Application
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

/** 验证默认助理状态查询及设置入口的实际启动顺序。 */
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
        assertEquals(false, AssistantRole.isAssistantRoleHeld(context))
    }

    /** 系统提供该角色但未授予：支持、未持有。 */
    @Test
    fun supportedButNotHeldWhenRoleIsAvailableOnly() {
        shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_ASSISTANT)

        assertTrue(AssistantRole.isAssistantRoleSupported(context))
        assertEquals(false, AssistantRole.isAssistantRoleHeld(context))
    }

    /** 角色已授予：支持且持有。 */
    @Test
    fun heldWhenSystemReportsTheRoleIsHeld() {
        shadowOf(roleManager).addHeldRole(RoleManager.ROLE_ASSISTANT)

        assertTrue(AssistantRole.isAssistantRoleSupported(context))
        assertEquals(true, AssistantRole.isAssistantRoleHeld(context))
    }

    /** 撤销后必须立刻变成「未持有」：不允许任何本地缓存把状态粘住。 */
    @Test
    fun revocationIsReflectedImmediately() {
        shadowOf(roleManager).addHeldRole(RoleManager.ROLE_ASSISTANT)
        assertEquals(true, AssistantRole.isAssistantRoleHeld(context))

        shadowOf(roleManager).removeHeldRole(RoleManager.ROLE_ASSISTANT)
        assertEquals(false, AssistantRole.isAssistantRoleHeld(context))
    }

    @Test
    fun assistantSettingsPrecedesDefaultAppsAndGeneralSettings() {
        registerSettingsActions(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_ASSISTANT)
        val starter = SettingsStarter(context)

        assertTrue(AssistantRole.openSettings(starter))
        assertEquals(listOf(Settings.ACTION_VOICE_INPUT_SETTINGS), starter.attempts)
    }

    @Test
    fun assistantSettingsSkipsMissingVoicePageAndOpensDefaultApps() {
        registerSettingsActions(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
        val starter = SettingsStarter(context)

        assertTrue(AssistantRole.openSettings(starter))
        assertEquals(listOf(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), starter.attempts)
    }

    @Test
    @Config(sdk = [34])
    fun assistantSettingsContinuesAfterLaunchExceptions() {
        registerSettingsActions(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        val starter = SettingsStarter(
            context,
            mapOf(
                Settings.ACTION_VOICE_INPUT_SETTINGS to ActivityNotFoundException(),
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS to SecurityException(),
            ),
        )

        assertTrue(AssistantRole.openSettings(starter))
        assertEquals(
            listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS),
            starter.attempts,
        )
    }

    @Test
    @Config(sdk = [34])
    fun assistantSettingsReportsWhenNoPageCanStart() {
        registerSettingsActions(Settings.ACTION_SETTINGS)
        val starter = SettingsStarter(context, mapOf(Settings.ACTION_SETTINGS to SecurityException()))

        assertFalse(AssistantRole.openSettings(starter))
        assertEquals(listOf(Settings.ACTION_SETTINGS), starter.attempts)
    }

    @Test
    @Config(sdk = [34])
    fun settingsRemainsActionableWhenTheRoleQueryIsUnavailable() {
        val entry = buildVisiblePermissions(34, emptySet()).first { it.id == "assistant_role" }
        registerSettingsActions(Settings.ACTION_SETTINGS)
        val starter = SettingsStarter(context)

        assertFalse(AssistantRole.isAssistantRoleSupported(context))
        assertEquals(PermissionStatus.DENIED, resolvePermissionStatus(context, entry))
        assertTrue(AssistantRole.openSettings(starter))
        assertEquals(listOf(Settings.ACTION_SETTINGS), starter.attempts)
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

    private fun registerSettingsActions(vararg actions: String) {
        val packageManager = shadowOf(context.packageManager)
        actions.forEach { action ->
            packageManager.addResolveInfoForIntent(Intent(action), ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "com.android.settings.$action"
                }
            })
        }
    }

    private class SettingsStarter(
        base: Context,
        private val failures: Map<String, RuntimeException> = emptyMap(),
    ) : ContextWrapper(base) {
        val attempts = mutableListOf<String>()

        override fun startActivity(intent: Intent) {
            val action = requireNotNull(intent.action)
            attempts += action
            failures[action]?.let { throw it }
        }
    }

}
