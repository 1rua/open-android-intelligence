package com.openandroidintelligence.mobile.plugins

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.io.FileNotFoundException

internal const val NOTIFICATION_PLUGIN_ID = "org.openandroidintelligence.notifications"
internal const val BUNDLED_NOTIFICATION_ASSET = "bundled-plugins/notifications.alp"
internal const val BUNDLED_NOTIFICATION_INSTALL_TAG = "bundled-notification-install"

internal class BundledNotificationUnavailable : IllegalStateException()

/** An APK asset is only a package source; verification and installation still belong to PluginInstallStore. */
internal fun readBundledNotificationPackage(context: Context): ByteArray = try {
    context.assets.open(BUNDLED_NOTIFICATION_ASSET).use(::readPluginPackageBytes)
} catch (_: FileNotFoundException) {
    throw BundledNotificationUnavailable()
}

internal fun openNotificationAccessSettings(context: Context): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
