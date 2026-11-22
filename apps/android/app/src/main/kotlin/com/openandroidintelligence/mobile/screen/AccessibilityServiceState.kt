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
 * 无障碍服务是否**真的可用**：系统设置里已开启，且服务当前确实已连接。
 *
 * 两个条件必须同时成立：
 * - 仅设置里已开启但服务未连接（断连）→ 必须返回 false，让界面保留恢复引导；
 * - 用户刚在系统设置里关闭服务、而 `onUnbind()` 延迟送达 → 必须随系统设置立即返回 false。
 */
internal fun accessibilityServiceReady(context: Context): Boolean = runCatching {
    accessibilityServiceEnabledInSettings(context) && accessibilityServiceConnected()
}.getOrDefault(false)

/**
 * 在返回前台（`ON_RESUME`）时主动探测系统无障碍设置与内存绑定态，双向校准滞后的连接态：
 * - 若系统设置已关闭但 `onUnbind()` 尚未回调，立即将内存连接态校准为 false；
 * - 若系统设置已开启且宿主报告无障碍后端无错，补齐内存连接态为 true。
 */
internal fun probeAccessibilityBinding(context: Context): Boolean = runCatching {
    val enabledInSettings = accessibilityServiceEnabledInSettings(context)
    if (!enabledInSettings) {
        if (ScreenHostRegistry.accessibilityConnected.value) {
            ScreenHostRegistry.setAccessibilityConnected(false)
        }
        return@runCatching false
    }
    if (!ScreenHostRegistry.accessibilityConnected.value) {
        val status = runCatching { ScreenHostRegistry.current?.status() }.getOrNull()
        if (status != null && status.accessibilityError == null) {
            ScreenHostRegistry.setAccessibilityConnected(true)
        }
    }
    accessibilityServiceConnected()
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
