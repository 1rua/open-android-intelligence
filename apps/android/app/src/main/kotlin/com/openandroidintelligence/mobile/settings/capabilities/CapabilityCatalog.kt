package com.openandroidintelligence.mobile.settings.capabilities

import com.openandroidintelligence.mobile.plugins.CALL_LOG_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.NOTIFICATION_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.SCREEN_CAPTURE_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.SCREEN_CONTROL_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.SCREEN_READER_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.SMS_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.VerifiedCapability

/**
 * 主页面的一条内建能力入口。
 *
 * [members] 是这条入口在详情页里统一管理的全部能力声明：短信一条入口背后有查询、定时任务、
 * 状态查询与取消四项，其余五项只有自己。目录是**固定入口**，不是“已安装证据”：
 * [verified] 为 null 只表示当前没有可验证的提供者，界面对此如实降级。
 */
internal data class BuiltInCapability(
    val pluginId: String,
    val id: String,
    val title: String,
    val group: String,
    val members: List<String> = listOf(id),
)

internal const val GROUP_SCREEN = "屏幕"
internal const val GROUP_COMMUNICATION = "通讯与通知"

internal val builtInCapabilities = listOf(
    BuiltInCapability(SCREEN_READER_PLUGIN_ID, "org.openandroidintelligence.screen-reader.read", "屏幕内容读取", GROUP_SCREEN),
    BuiltInCapability(SCREEN_CAPTURE_PLUGIN_ID, "org.openandroidintelligence.screen-capture.capture", "屏幕截图", GROUP_SCREEN),
    BuiltInCapability(SCREEN_CONTROL_PLUGIN_ID, "org.openandroidintelligence.screen-control.control", "屏幕控制", GROUP_SCREEN),
    BuiltInCapability(
        SMS_PLUGIN_ID, "org.openandroidintelligence.sms.query", "短信", GROUP_COMMUNICATION,
        members = listOf(
            "org.openandroidintelligence.sms.query",
            "org.openandroidintelligence.sms.schedule",
            "org.openandroidintelligence.sms.job-status",
            "org.openandroidintelligence.sms.cancel",
        ),
    ),
    BuiltInCapability(NOTIFICATION_PLUGIN_ID, "org.openandroidintelligence.notifications.query", "通知读取", GROUP_COMMUNICATION),
    BuiltInCapability(CALL_LOG_PLUGIN_ID, "org.openandroidintelligence.call-log.query", "通话记录", GROUP_COMMUNICATION),
)

/** 主页面只呈现内建能力；第三方能力由插件管理维护，不在这里另开分组。 */
internal data class CapabilityCatalogItem(
    val pluginId: String,
    val capability: String,
    val title: String,
    val group: String,
    val members: List<String>,
    val verified: VerifiedCapability?,
)

/**
 * 把已验证声明挂到固定入口上。
 *
 * 只接受与入口同插件、同能力 ID 的声明：同名能力来自别的插件时必须另算一个提供者，
 * 不能被合并进内建入口，否则用户会以为官方能力可用而实际调用落到第三方实现。
 */
internal fun capabilityCatalog(entries: List<VerifiedCapability>): List<CapabilityCatalogItem> =
    builtInCapabilities.map { builtin ->
        val matching = entries.filter { it.identity.pluginId == builtin.pluginId && it.id == builtin.id }
        val primary = matching.firstOrNull { it.key == builtin.id || it.id == builtin.id }
        CapabilityCatalogItem(
            pluginId = builtin.pluginId,
            capability = if (matching.isEmpty()) builtin.id else matching.first().key,
            title = builtin.title,
            group = builtin.group,
            members = builtin.members.map { member ->
                matching.firstOrNull { it.id == member }?.key ?: member
            },
            verified = primary ?: matching.firstOrNull(),
        )
    }

/** 该能力声明属于某个内建入口时返回它的标题；第三方能力一律显示能力 ID 的尾部。 */
internal fun capabilityTitle(entry: VerifiedCapability): String =
    builtInCapabilities.firstOrNull { it.id == entry.id }?.title ?: entry.id

internal fun isScreenCapability(entry: VerifiedCapability): Boolean =
    entry.primitives.any { it == "kernel.screen.read" || it == "kernel.screen.capture" || it == "kernel.screen.control" }

/** 该内建能力是否由本 APK 提供（用于隐藏安装/卸载入口）。 */
internal fun isBuiltInPlugin(pluginId: String): Boolean = builtInCapabilities.any { it.pluginId == pluginId }
