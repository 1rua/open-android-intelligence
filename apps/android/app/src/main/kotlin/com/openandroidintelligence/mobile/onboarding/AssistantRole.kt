package com.openandroidintelligence.mobile.onboarding

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * 「默认数字助理」系统角色的支持性 / 持有性查询与申请入口。
 *
 * 设计约束（来自 `docs/superpowers/specs/2026-08-08-agent-bridge-android-design.md`）：
 * 1. AOSP 把 Assistant role 标为**不可由普通应用直接请求**，因此不能假设
 *    [createRequestRoleIntent] 一定能弹窗。它返回 null 时，调用方必须走
 *    [fallbackSettingsIntents] 让用户自行在系统设置里选择；
 * 2. 判定必须查询系统的**真实角色状态**（[RoleManager.isRoleHeld]），
 *    不能用自己的布尔标记伪造「已设置」——用户随时可能在系统设置里撤销；
 * 3. 设备不支持该角色时如实返回「不支持」，不能把卡片渲染成「未设置」——
 *    那会让用户以为点一下就能开启，而实际上系统里根本没有这个开关。
 *
 * 所有查询都在异常时保守返回「不支持 / 未持有」：极少数 ROM 上系统服务可能
 * 不可用，此时宁可显示引导去系统设置，也不伪造成功状态。
 */
object AssistantRole {

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

    /**
     * 创建系统角色申请 Intent；设备不支持该角色时返回 null。
     *
     * 返回 null 是**预期结果而不是错误**：AOSP 不允许普通应用直接请求
     * Assistant role。调用方应改用 [createFallbackSettingsIntent]。
     */
    fun createRequestRoleIntent(context: Context): Intent? {
        if (!isAssistantRoleSupported(context)) return null
        return try {
            context.getSystemService(RoleManager::class.java)
                ?.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        } catch (_: RuntimeException) {
            // 个别 ROM 对该 API 直接抛异常：此时按「无法弹窗」处理，交给回退设置页。
            null
        }
    }

    /**
     * 回退用的系统设置 Intent 候选，按优先级排列。
     *
     * 只做「候选列表 + 可解析预检」，不替调用方决定用哪一个：
     * - `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`（API 34+）是官方「默认应用」入口，
     *   用户能在里面直接选数字助理；系统里没有这一页时 `resolveActivity` 返回 null；
     * - `ACTION_VOICE_INPUT_SETTINGS` 是历史入口，部分 ROM 仍保留；
     * - `ACTION_MANAGE_APPLICATIONS_SETTINGS` 是最后的兜底，至少能到应用管理。
     *
     * **不带 `FLAG_ACTIVITY_NEW_TASK`**：带它时 `StartActivityForResult` 拿不到结果，
     * 也无法判断用户是否真的完成设置。需要非 Activity 上下文启动的调用方自行补 flag。
     */
    fun fallbackSettingsIntents(context: Context): List<Intent> {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            add(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            add(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
        }
        return candidates.filter { it.resolveActivity(context.packageManager) != null }
    }

    /**
     * 第一个可用的回退设置 Intent；一个都不可解析时返回 null。
     *
     * 返回 null 是真实结论（系统里没有可用的设置页），调用方必须如实呈现
     * 「不支持」，而不是留给用户一个点了没反应的按钮。
     */
    fun createFallbackSettingsIntent(context: Context): Intent? =
        fallbackSettingsIntents(context).firstOrNull()

    /**
     * 打开「默认数字助理」设置页的完整尝试顺序：**角色申请优先，其次回退页**。
     *
     * 返回空列表是明确的结论：系统里既不能申请也不能打开设置页。调用方据此
     * 把状态标成「不支持」，并给出「为什么点了没反应」的说明 —— 而不是启动一个
     * 可解析却什么都不弹的页面然后假装成功。
     */
    fun roleSettingsIntents(context: Context): List<Intent> {
        val request = createRequestRoleIntent(context)
        return if (request == null) fallbackSettingsIntents(context)
        else listOf(request) + fallbackSettingsIntents(context)
    }
}
