package com.openandroidintelligence.mobile.plugins

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.io.FileNotFoundException

internal const val NOTIFICATION_PLUGIN_ID = "org.openandroidintelligence.notifications"
internal const val SMS_PLUGIN_ID = "org.openandroidintelligence.sms"
internal const val CALL_LOG_PLUGIN_ID = "org.openandroidintelligence.call-log"

/**
 * Full APK 可携带的官方插件包。
 *
 * 每项都固定了期望的插件 ID：安装仍走 [PluginInstallStore] 的完整验签，
 * 但「内置包」只允许装上这一项自己声明的 ID，绝不能让名称写错的资产
 * 冒充另一个插件。
 */
enum class BundledPlugin(
    val pluginId: String,
    val assetPath: String,
    val installTag: String,
    val displayName: String,
) {
    NOTIFICATIONS(
        pluginId = NOTIFICATION_PLUGIN_ID,
        assetPath = "bundled-plugins/notifications.alp",
        installTag = "bundled-notification-install",
        displayName = "通知读取",
    ),
    SMS(
        pluginId = SMS_PLUGIN_ID,
        assetPath = "bundled-plugins/sms.alp",
        installTag = "bundled-sms-install",
        displayName = "短信读取",
    ),
    CALL_LOG(
        pluginId = CALL_LOG_PLUGIN_ID,
        assetPath = "bundled-plugins/call-log.alp",
        installTag = "bundled-call-log-install",
        displayName = "通话记录读取",
    ),
}

/** 当前安装包未附带这一项内置插件包；界面据此如实说明，而不是假装安装成功。 */
internal class BundledPluginUnavailable(val plugin: BundledPlugin) : IllegalStateException()

/** APK 资产只是包来源；验签与安装仍属于 [PluginInstallStore]。 */
internal fun readBundledPluginPackage(context: Context, plugin: BundledPlugin): ByteArray = try {
    context.assets.open(plugin.assetPath).use(::readPluginPackageBytes)
} catch (_: FileNotFoundException) {
    throw BundledPluginUnavailable(plugin)
}

internal fun openNotificationAccessSettings(context: Context): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
