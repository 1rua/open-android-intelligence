package com.openandroidintelligence.mobile.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.openandroidintelligence.mobile.screen.accessibilityServiceClassName

private const val SETTINGS_FRAGMENT_KEY = ":settings:fragment_args_key"
private const val SETTINGS_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

/** 供无障碍设置滚动定位 OAI 服务。不识别附加参数的系统仍可打开无障碍列表。 */
internal fun openOnboardingAccessibilitySettings(context: Context): Boolean {
    val serviceKey = ComponentName(context, accessibilityServiceClassName()).flattenToString()
    val accessibility = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(SETTINGS_FRAGMENT_KEY, serviceKey)
        .putExtra(SETTINGS_FRAGMENT_ARGUMENTS, Bundle().apply {
            putString(SETTINGS_FRAGMENT_KEY, serviceKey)
        })
    return openFirstOnboardingSettings(context, listOf(accessibility, Intent(Settings.ACTION_SETTINGS)))
}

internal fun Intent.canOpenOnboardingSettings(context: Context): Boolean = runCatching {
    resolveActivity(context.packageManager) != null
}.getOrDefault(false)

/** 从引导 Activity 打开设置；厂商系统即使能解析入口，也可能在启动时拒绝。 */
internal fun openFirstOnboardingSettings(context: Context, candidates: List<Intent>): Boolean =
    candidates.any { intent ->
        intent.canOpenOnboardingSettings(context) && runCatching { context.startActivity(intent) }.isSuccess
    }
