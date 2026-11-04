package com.openandroidintelligence.mobile.settings.capabilities

import com.openandroidintelligence.mobile.plugins.SCREEN_READER_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.SCREEN_CAPTURE_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.SCREEN_CONTROL_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.SMS_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.NOTIFICATION_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.CALL_LOG_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.VerifiedCapability

/** Built-in declarations are entry points, not evidence that a provider is installed. */
internal data class BuiltInCapability(val pluginId: String, val id: String, val title: String, val group: String)

internal val builtInCapabilities = listOf(
    BuiltInCapability(SCREEN_READER_PLUGIN_ID, "org.openandroidintelligence.screen-reader.read", "屏幕内容读取", "屏幕"),
    BuiltInCapability(SCREEN_CAPTURE_PLUGIN_ID, "org.openandroidintelligence.screen-capture.capture", "屏幕截图", "屏幕"),
    BuiltInCapability(SCREEN_CONTROL_PLUGIN_ID, "org.openandroidintelligence.screen-control.control", "屏幕控制", "屏幕"),
    BuiltInCapability(SMS_PLUGIN_ID, "org.openandroidintelligence.sms.query", "短信读取", "短信"),
    BuiltInCapability(SMS_PLUGIN_ID, "org.openandroidintelligence.sms.schedule", "短信定时任务设置", "短信"),
    BuiltInCapability(SMS_PLUGIN_ID, "org.openandroidintelligence.sms.job-status", "短信任务状态查询", "短信"),
    BuiltInCapability(SMS_PLUGIN_ID, "org.openandroidintelligence.sms.cancel", "短信任务取消", "短信"),
    BuiltInCapability(NOTIFICATION_PLUGIN_ID, "org.openandroidintelligence.notifications.query", "通知读取", "通知"),
    BuiltInCapability(CALL_LOG_PLUGIN_ID, "org.openandroidintelligence.call-log.query", "通话记录读取", "通话"),
)

internal data class CapabilityCatalogItem(
    val pluginId: String,
    val capability: String,
    val title: String,
    val group: String,
    val verified: VerifiedCapability?,
)

/** Do not merge providers just because their displayed capability names match. */
internal fun capabilityCatalog(entries: List<VerifiedCapability>): List<CapabilityCatalogItem> {
    val builtIns = builtInCapabilities.flatMap { builtin ->
        val matching = entries.filter { it.identity.pluginId == builtin.pluginId && it.id == builtin.id }
        if (matching.isEmpty()) listOf(CapabilityCatalogItem(builtin.pluginId, builtin.id, builtin.title, builtin.group, null))
        else matching.map { CapabilityCatalogItem(it.identity.pluginId, it.key, builtin.title, builtin.group, it) }
    }
    val others = entries.filter { entry ->
        builtInCapabilities.none { it.pluginId == entry.identity.pluginId && it.id == entry.id }
    }.sortedWith(compareBy({ it.id }, { it.version }, { it.identity.pluginId })).map { entry ->
        val known = builtInCapabilities.firstOrNull { it.id == entry.id }
        CapabilityCatalogItem(entry.identity.pluginId, entry.key, known?.title ?: entry.id,
            known?.group ?: "其他已验证能力", entry)
    }
    return builtIns + others
}

internal fun capabilityTitle(entry: VerifiedCapability): String =
    builtInCapabilities.firstOrNull { it.id == entry.id }?.title ?: entry.id

internal fun isScreenCapability(entry: VerifiedCapability): Boolean =
    entry.primitives.any { it == "kernel.screen.read" || it == "kernel.screen.capture" || it == "kernel.screen.control" }
