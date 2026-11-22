package com.openandroidintelligence.mobile.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.openandroidintelligence.control.ScreenHostRegistry

/**
 * 无障碍服务当前是否已与系统建立真实连接。
 *
 * 优先读 [ScreenHostRegistry.accessibilityConnected]（由 `ScreenAccessibilityService`
 * 生命周期回调直接维护，不会被锁屏态 `SCREEN_DEVICE_LOCKED` 混淆）；在未接入真实服务的
 * 测试环境回退读取 `ScreenHostRegistry.current?.status()`。
 */
internal fun accessibilityServiceConnected(): Boolean {
    if (ScreenHostRegistry.accessibilityConnected.value) return true
    val status = runCatching { ScreenHostRegistry.current?.status() }.getOrNull() ?: return false
    return status.accessibilityError == null
}

/**
 * 无障碍服务是否**真的可用**：服务当前确实已连接，或在测试/兼容环境中设置已启用且宿主就绪。
 *
 * 只查设置里的启用标记是不够的：用户开启过、但系统解绑之后，界面会把该项显示成
 * 「已授权」并隐藏恢复入口，恰好堵死唯一的修复路径。反之，若系统已通过
 * `BIND_ACCESSIBILITY_SERVICE` 真实绑定本进程服务实例（`accessibilityConnected == true`），
 * 即使部分 ROM 限制了 `ENABLED_ACCESSIBILITY_SERVICES` 设置串读取，也应如实判定已就绪。
 */
internal fun accessibilityServiceReady(context: Context): Boolean = runCatching {
    ScreenHostRegistry.accessibilityConnected.value ||
        (accessibilityServiceEnabledInSettings(context) && accessibilityServiceConnected())
}.getOrDefault(false)

/**
 * 无障碍服务是否在设置里被标记为开启（不论当前是否仍连接）。
 *
 * 同时使用 `ComponentName.unflattenFromString` 解析 `ENABLED_ACCESSIBILITY_SERVICES`
 * 与 `AccessibilityManager.getEnabledAccessibilityServiceList` 交叉核验，兼容带包名后缀
 * （如 debug）和不同 ROM 的组件名格式。
 */
internal fun accessibilityServiceEnabledInSettings(context: Context): Boolean = runCatching {
    val component = ComponentName(context, accessibilityServiceClassName())
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val inSettings = enabled.split(':').any { raw ->
        val entry = raw.trim()
        if (entry.isEmpty()) return@any false
        ComponentName.unflattenFromString(entry) == component ||
            entry.equals(component.flattenToString(), ignoreCase = true) ||
            entry.equals(component.flattenToShortString(), ignoreCase = true)
    }
    if (inSettings) return@runCatching true
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return@runCatching false
    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
        val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
        serviceInfo.packageName == component.packageName && serviceInfo.name == component.className
    }
}.getOrDefault(false)

/** 服务已在设置里开启但当前未连接：用户需要回无障碍设置确认服务显示「已连接」。 */
internal fun accessibilityServiceDisconnected(context: Context): Boolean =
    accessibilityServiceEnabledInSettings(context) && !accessibilityServiceReady(context)
