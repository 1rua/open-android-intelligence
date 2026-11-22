package com.openandroidintelligence.mobile.screen

import android.content.ComponentName
import android.content.Context
import com.openandroidintelligence.control.ScreenHostRegistry

/**
 * 无障碍服务当前是否已与系统建立真实连接。
 *
 * 优先读 [ScreenHostRegistry.accessibilityConnected]（由 `ScreenAccessibilityService`
 * 生命周期回调与 `probeAccessibilityBinding` 直接维护）；在仅注入 `ScreenHostRegistry.current`
 * 的测试替身环境下回退检查 `accessibilityError != "ACCESSIBILITY_SERVICE_UNAVAILABLE"`，
 * 避免将锁屏态 `SCREEN_DEVICE_LOCKED` 误判为无障碍未连接。
 */
internal fun accessibilityServiceConnected(): Boolean {
    if (ScreenHostRegistry.accessibilityConnected.value) return true
    val status = runCatching { ScreenHostRegistry.current?.status() }.getOrNull() ?: return false
    return status.accessibilityError != "ACCESSIBILITY_SERVICE_UNAVAILABLE"
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
 * - 若系统设置已关闭但 `onUnbind()` 尚未回调，立即将内存连接态校准为 false（连带使底层屏幕原语立即报告未连接）；
 * - 若系统设置已开启且宿主报告无障碍服务已连接，补齐内存连接态为 true。
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
        if (status != null && status.accessibilityError != "ACCESSIBILITY_SERVICE_UNAVAILABLE") {
            ScreenHostRegistry.setAccessibilityConnected(true)
        }
    }
    accessibilityServiceConnected()
}.getOrDefault(false)

/**
 * 无障碍服务是否在设置里被标记为开启（不论当前是否仍连接）。
 *
 * 复用 [ScreenHostRegistry.isAccessibilityServiceEnabledInSettings] 统一解析冒号分隔组件列表与
 * `AccessibilityManager.getEnabledAccessibilityServiceList`。
 */
internal fun accessibilityServiceEnabledInSettings(context: Context): Boolean =
    ScreenHostRegistry.isAccessibilityServiceEnabledInSettings(
        context,
        ComponentName(context, accessibilityServiceClassName()),
    )

/** 服务已在设置里开启但当前未连接：用户需要回无障碍设置确认服务显示「已连接」。 */
internal fun accessibilityServiceDisconnected(context: Context): Boolean =
    accessibilityServiceEnabledInSettings(context) && !accessibilityServiceReady(context)
