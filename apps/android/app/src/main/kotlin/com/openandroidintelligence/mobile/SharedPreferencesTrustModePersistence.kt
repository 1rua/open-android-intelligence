package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.kernel.TrustModePersistence

/**
 * 开发者信任模式开关的 SharedPreferences 持久化（条目 2-5）。
 *
 * 只存「上次进程退出时开关是什么状态」这一个布尔；每次开启所需的确认文本
 * 校验仍由 [DeveloperTrustMode] 在当次真实交互中完成，持久化不构成豁免。
 *
 * 读取失败/数据损坏（键被写成别的类型等）以异常表达，由内核统一按
 * 「未开启」处理——损坏的记录绝不会把插件后门重新打开。
 */
class SharedPreferencesTrustModePersistence(
    private val preferences: android.content.SharedPreferences,
) : TrustModePersistence {

    override fun load(): Boolean {
        // getBoolean 遇到类型不符会抛 ClassCastException：即「损坏 → 未开启」。
        return preferences.getBoolean(KEY_ENABLED, false)
    }

    override fun save(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "TRUST_MODE_PERSISTENCE_FAILED"
        }
    }

    companion object {
        fun from(context: Context): SharedPreferencesTrustModePersistence =
            SharedPreferencesTrustModePersistence(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        private const val PREFERENCES_NAME = "open_android_intelligence_platform"
        private const val KEY_ENABLED = "developer_trust_mode_enabled"
    }
}
