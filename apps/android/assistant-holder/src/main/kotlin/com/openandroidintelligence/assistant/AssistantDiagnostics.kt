package com.openandroidintelligence.assistant

import android.util.Log

/**
 * 助理会话的截图/交接诊断日志。
 *
 * 为什么需要它：系统是否投递截图、投递的位图有多大、为什么没进定格，这三件事
 * 完全发生在应用外部或跨进程边界上，只靠界面提示无法定位。日志用一个固定 tag，
 * 每条都带明确的阶段前缀，`adb logcat -s OAI-Assistant` 就能看到完整链路。
 *
 * 只在 DEBUG 版本输出：发布包不写这些位图尺寸与会话身份，避免把诊断信息带到线上。
 */
internal object AssistantDiagnostics {

    const val TAG = "OAI-Assistant"

    @Volatile
    var enabled: Boolean = false

    /**
     * 按应用是否可调试决定是否输出。
     *
     * 用 `ApplicationInfo.FLAG_DEBUGGABLE` 而不是某个 BuildConfig 字段：助手模块是
     * 库模块，运行时的进程属于宿主 App，可调试性只有宿主自己知道。
     */
    fun configure(context: android.content.Context) {
        enabled = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun screenshot(stage: String, detail: String) {
        if (enabled) Log.d(TAG, "screenshot/$stage: $detail")
    }

    fun handoff(stage: String, detail: String) {
        if (enabled) Log.d(TAG, "handoff/$stage: $detail")
    }

    fun picker(stage: String, detail: String) {
        if (enabled) Log.d(TAG, "picker/$stage: $detail")
    }
}
