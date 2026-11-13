package com.openandroidintelligence.mobile.settings

import android.net.Uri
import com.openandroidintelligence.mobile.SettingsRoutes

/** Display ownership and canonical destinations; none of these values are configuration storage. */
internal enum class SettingsCategory(val label: String, val route: String, val summary: String) {
    APPEARANCE("外观与交互", SettingsRoutes.APPEARANCE, "主题、配色与动画"),
    CONNECTION("连接与账号", SettingsRoutes.GATEWAY, "Core、Agent 和已保存账号"),
    NOTIFICATIONS("通知与提醒", SettingsRoutes.REMINDERS, "任务通知、会话气泡与系统权限"),
    CAPABILITIES("设备能力与授权", SettingsRoutes.PAIRING, "设备能力、主动推送与插件"),
    SECURITY("安全与隐私", SettingsRoutes.SECURITY, "运行策略、操作记录与紧急停止"),
    ADVANCED("高级设置与关于", SettingsRoutes.ADVANCED, "消息处理、应用维护与帮助"),
}

internal fun capabilitySettingsRoute(pluginId: String, capability: String): String =
    "settings/capability/${Uri.encode(pluginId)}/${Uri.encode(capability)}"

internal val notificationReadingRoute: String get() = capabilitySettingsRoute("org.openandroidintelligence.notifications", "org.openandroidintelligence.notifications.query")

internal fun canonicalSettingsRoute(route: String): String = when (route) {
    SettingsRoutes.NOTIFICATIONS -> notificationReadingRoute
    else -> route
}

internal fun settingsOwner(route: String): String? = when {
    route == SettingsRoutes.OVERVIEW -> null
    route == SettingsRoutes.CONNECTION_DETAILS -> SettingsRoutes.GATEWAY
    route in setOf(SettingsRoutes.AUDIT_LOG) -> SettingsRoutes.SECURITY
    route in setOf(SettingsRoutes.PLUGINS, SettingsRoutes.DEVICE_PUSH, SettingsRoutes.NOTIFICATIONS, SettingsRoutes.SCREEN_CAPABILITY) || route.startsWith("settings/capability/") -> SettingsRoutes.PAIRING
    route in setOf(SettingsRoutes.APP_UPDATES, SettingsRoutes.BUILD_DETAILS, SettingsRoutes.LICENSES, SettingsRoutes.BACKUP) ->
        SettingsRoutes.ADVANCED
    SettingsCategory.entries.any { it.route == route } -> SettingsRoutes.OVERVIEW
    else -> null
}
