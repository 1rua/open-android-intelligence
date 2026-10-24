package com.openandroidintelligence.mobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面启动器图标动态取色管理器。
 *
 * 负责在用户切换「图标动态取色」偏好时，通过平台标准原语 [PackageManager.setComponentEnabledSetting]
 * 切换 [MainActivityThemed] 与 [MainActivityClassic] 两个启动别名：
 * - 动态取色开启（默认）：启用带 `<monochrome>` 单色图层的别名；
 * - 动态取色关闭：启用不含单色图层的别名，确保在任何开启了主题图标的系统上均保留经典原版外观；
 * - 传入 [PackageManager.DONT_KILL_APP] 标志，避免切换别名时操作系统杀死当前前台进程，保证交互流畅。
 */
object LauncherIconManager {

    const val ALIAS_THEMED = "com.openandroidintelligence.mobile.MainActivityThemed"
    const val ALIAS_CLASSIC = "com.openandroidintelligence.mobile.MainActivityClassic"

    fun applyDynamicIconPreference(context: Context, dynamicEnabled: Boolean) {
        val pm = context.packageManager ?: return
        val themedComponent = ComponentName(context, ALIAS_THEMED)
        val classicComponent = ComponentName(context, ALIAS_CLASSIC)

        val targetEnabled = if (dynamicEnabled) themedComponent else classicComponent
        val targetDisabled = if (dynamicEnabled) classicComponent else themedComponent

        runCatching {
            if (pm.getComponentEnabledSetting(targetEnabled) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    targetEnabled,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            if (pm.getComponentEnabledSetting(targetDisabled) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    targetDisabled,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
