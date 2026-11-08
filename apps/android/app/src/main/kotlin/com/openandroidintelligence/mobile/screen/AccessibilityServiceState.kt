package com.openandroidintelligence.mobile.screen

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.openandroidintelligence.control.ScreenHostRegistry

/**
 * 无障碍服务是否**真的可用**：系统设置里已开启，且服务当前确实已连接。
 *
 * 只查设置里的启用标记是不够的：用户开启过、但系统解绑之后，界面会把该项显示成
 * 「已授权」并隐藏恢复入口，恰好堵死唯一的修复路径。两个条件必须同时成立。
 */
internal fun accessibilityServiceReady(context: Context): Boolean = runCatching {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val component = ComponentName(context, accessibilityServiceClassName())
    val enabledInSettings = enabled.split(':').any {
        it.equals(component.flattenToString(), true) || it.equals(component.flattenToShortString(), true)
    }
    val status = ScreenHostRegistry.current?.status()
    enabledInSettings && status != null && status.accessibilityError == null
}.getOrDefault(false)

/**
 * 无障碍服务是否在设置里被标记为开启（不论当前是否仍连接）。
 *
 * 用来把「从未开启」和「开启过但已断连」分开：两者的下一步动作不同。
 */
internal fun accessibilityServiceEnabledInSettings(context: Context): Boolean = runCatching {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val component = ComponentName(context, accessibilityServiceClassName())
    enabled.split(':').any {
        it.equals(component.flattenToString(), true) || it.equals(component.flattenToShortString(), true)
    }
}.getOrDefault(false)

/** 服务已在设置里开启但当前未连接：用户需要回无障碍设置确认服务显示「已连接」。 */
internal fun accessibilityServiceDisconnected(context: Context): Boolean =
    accessibilityServiceEnabledInSettings(context) && !accessibilityServiceReady(context)
