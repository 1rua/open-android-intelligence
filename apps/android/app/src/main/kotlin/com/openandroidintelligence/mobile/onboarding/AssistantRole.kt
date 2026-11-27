package com.openandroidintelligence.mobile.onboarding

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 系统角色（`RoleManager` 管理的角色）的唯一映射。
 *
 * 目前只接入「默认数字助理」一项：它是本应用提供跨应用 Assist 唤起的前提。
 * 用枚举而不是裸字符串，是为了让权限目录能声明「这一项由系统角色决定」，
 * 状态查询与按钮行为都从同一处取值，避免两处各写一个 role 名。
 *
 * @param roleName 传给 [RoleManager] 的角色名。
 */
enum class SystemRole(val roleName: String) {
    ASSISTANT(RoleManager.ROLE_ASSISTANT),
    ;
}

/**
 * 查询「默认数字助理」角色，并引导用户到系统设置自行选择。
 * AOSP 禁止普通应用请求助理角色。即使角色可用且申请 Intent 可解析，
 * PermissionController 也会立即结束申请页，所以这里直接打开系统设置。
 */
object AssistantRole {
    private val assistantSettingsActions = listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
    )

    /**
     * 当前设备/系统是否提供「默认数字助理」角色。
     *
     * 判据是 [RoleManager.isRoleAvailable]：它在系统没有该角色、
     * 或当前用户空间不适用时返回 false。取不到 RoleManager 也视为不支持。
     */
    fun isAssistantRoleSupported(context: Context): Boolean = try {
        val manager = context.getSystemService(RoleManager::class.java)
        manager != null && manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    } catch (_: RuntimeException) {
        false
    }

    /**
     * 本应用当前是否**真实持有**「默认数字助理」角色。
     *
     * 只信 [RoleManager.isRoleHeld] 的返回值，不看本地缓存：
     * 用户去系统设置改过之后，这里必须立刻反映新状态。
     */
    fun isAssistantRoleHeld(context: Context): Boolean = try {
        val manager = context.getSystemService(RoleManager::class.java)
        manager != null && manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } catch (_: RuntimeException) {
        false
    }

    /** 角色查询不可用时，厂商的助理或默认应用设置仍可能存在。 */
    internal fun hasSettingsEntry(context: Context): Boolean =
        assistantSettingsActions.any { Intent(it).canOpenOnboardingSettings(context) }

    internal fun openSettings(context: Context): Boolean = openFirstOnboardingSettings(
        context,
        (assistantSettingsActions + Settings.ACTION_SETTINGS).map { Intent(it) },
    )
}
