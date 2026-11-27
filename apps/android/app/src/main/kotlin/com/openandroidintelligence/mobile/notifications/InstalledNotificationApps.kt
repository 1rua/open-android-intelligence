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

/**
 * 进程内的已安装应用目录缓存。
 *
 * 存在的理由是一个真实缺陷：每次重新进入设置页都要重新枚举全部应用，枚举完成之前
 * 已保存的选择没有名称可显示，界面于是先说「应用信息暂不可用」，几百毫秒后才变成真名。
 * 缓存让再次打开时立刻有名字可读，刷新在后台进行且失败时保留旧值。
 *
 * 缓存只在进程内有效：应用安装、卸载或改名后由下一次刷新自然修正，不会长期陈旧。
 */
internal object InstalledNotificationAppCache {
    @Volatile private var apps: List<InstalledNotificationApp>? = null

    /** 当前已知的应用目录；从未成功读取过时为 null。 */
    fun current(): List<InstalledNotificationApp>? = apps

    /** 记录一次成功读取；空列表也是有效事实（例如极简系统），会覆盖旧值。 */
    fun publish(next: List<InstalledNotificationApp>) {
        apps = next
    }

    /** 仅用于测试与进程级重置。 */
    fun clear() {
        apps = null
    }
}

/**
 * 供设置页使用的应用目录读取：优先复用缓存，同时刷新。
 *
 * @param loader 真实枚举实现；测试可注入固定列表。
 */
internal fun cachedInstalledNotificationApps(
    packageManager: PackageManager,
    loader: (PackageManager) -> List<InstalledNotificationApp> = ::loadInstalledNotificationApps,
): List<InstalledNotificationApp> {
    val loaded = loader(packageManager)
    InstalledNotificationAppCache.publish(loaded)
    return loaded
}
