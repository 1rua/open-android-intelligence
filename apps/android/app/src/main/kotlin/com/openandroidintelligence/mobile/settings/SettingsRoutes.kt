package com.openandroidintelligence.mobile.settings

/**
 * 设置页面路由定义（重构版）。
 *
 * 新架构采用六大分类：
 * 1. 外观与交互
 * 2. 连接与账号
 * 3. 通知与提醒
 * 4. 设备能力与授权
 * 5. 安全与隐私
 * 6. 高级设置与关于
 */
object SettingsRoutes {
    // ============ 主览 ============
    const val OVERVIEW = "settings/overview"

    // ============ 六大分类（一级页面）============
    /** 外观与交互：主题、色板、动态取色、动画 */
    const val APPEARANCE = "settings/appearance"

    /** 连接与账号：OAI Core、Agent、账号管理、连接安全 */
    const val CONNECTION = "settings/connection"

    /** 通知与提醒：Agent 任务通知、会话气泡、实时状态 */
    const val NOTIFICATIONS = "settings/notifications"

    /** 设备能力与授权：按能力组织（屏幕/短信/通知读取等）+ 主动推送 + 插件管理 */
    const val CAPABILITIES = "settings/capabilities"

    /** 安全与隐私：开发者模式、插件安全、审计日志、紧急停止 */
    const val SECURITY = "settings/security"

    /** 高级设置与关于：消息处理、应用更新、帮助反馈、关于 */
    const val ADVANCED = "settings/advanced"

    // ============ 二级页面（子功能）============
    /** Agent 管理（从连接中提取） */
    const val AGENT_MANAGEMENT = "settings/agent-management"

    /** 账号管理 */
    const val ACCOUNT_MANAGEMENT = "settings/account-management"

    /** 连接安全（解除配对移到这里） */
    const val CONNECTION_SECURITY = "settings/connection-security"

    /** 单个能力详情（屏幕读取/截图/控制等） */
    const val CAPABILITY_DETAIL = "settings/capability-detail"

    /** 插件详情 */
    fun pluginDetail(pluginId: String) = "settings/plugin/$pluginId"

    /** 主动推送配置（独立子页） */
    const val PUSH_SETTINGS = "settings/push"

    /** 审计日志 */
    const val AUDIT_LOG = "settings/audit"

    /** 关于应用 */
    const val ABOUT_APP = "settings/about"

    // ============ 兼容旧路由（保留一个版本周期）============
    /** @deprecated 使用 CONNECTION 替代 */
    @Deprecated("Use CONNECTION instead", ReplaceWith("CONNECTION"))
    const val GATEWAY = "settings/gateway"

    /** @deprecated 纯中转页已移除，功能整合到 CAPABILITIES */
    @Deprecated("Removed: functions moved to CAPABILITIES", level = DeprecationLevel.WARNING)
    const val PAIRING = "settings/pairing"

    /** @deprecated 使用 CAPABILITIES 内的插件管理区域 */
    @Deprecated("Use CAPABILITIES plugin management section", ReplaceWith("CAPABILITIES"))
    const val PLUGINS = "settings/plugins"

    /** @deprecated 使用 PUSH_SETTINGS */
    @Deprecated("Use PUSH_SETTINGS", ReplaceWith("PUSH_SETTINGS"))
    const val DEVICE_PUSH = "settings/device-push"

    /** @deprecated 使用 CAPABILITIES 内的屏幕能力区域 */
    @Deprecated("Use CAPABILITIES screen capability section", ReplaceWith("CAPABILITIES"))
    const val SCREEN_CAPABILITY = "settings/screen-capability"

    /** @deprecated 使用 ADVANCED */
    @Deprecated("Use ADVANCED", ReplaceWith("ADVANCED"))
    const val APP_UPDATES = "settings/app-updates"
}
