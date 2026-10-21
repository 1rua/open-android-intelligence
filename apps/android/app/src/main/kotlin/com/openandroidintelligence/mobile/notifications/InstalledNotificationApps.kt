package com.openandroidintelligence.mobile.notifications

import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import java.text.Collator

internal data class InstalledNotificationApp(
    val packageId: String,
    val label: String,
    val icon: Drawable? = null,
)

/** 查询当前 Android 用户可见的已安装应用，不要求应用拥有桌面启动入口。 */
internal fun loadInstalledNotificationApps(packageManager: PackageManager): List<InstalledNotificationApp> {
    val collator = Collator.getInstance()
    return packageManager.getInstalledApplications(0)
        .asSequence()
        .distinctBy { it.packageName }
        .mapNotNull { listed ->
            val packageId = listed.packageName
            try {
                // 枚举后应用可能被卸载，重新查询以免把失效记录作为可选应用。
                val application = packageManager.getApplicationInfo(packageId, 0)
                val label = try {
                    packageManager.getApplicationLabel(application).toString().ifBlank { packageId }
                } catch (_: Resources.NotFoundException) {
                    packageId
                }
                val icon = try {
                    packageManager.getApplicationIcon(packageId)
                } catch (_: Resources.NotFoundException) {
                    null
                }
                InstalledNotificationApp(packageId = packageId, label = label, icon = icon)
            } catch (_: PackageManager.NameNotFoundException) {
                // 只忽略这一项的卸载竞态，列表查询失败仍交给界面如实呈现。
                null
            }
        }
        .sortedWith { left, right ->
            val labelOrder = collator.compare(left.label, right.label)
            if (labelOrder != 0) labelOrder else left.packageId.compareTo(right.packageId)
        }
        .toList()
}
