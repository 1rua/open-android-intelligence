package com.openandroidintelligence.assistant

import android.content.Context
import android.content.Intent

/** 主 App 的完整工作台；助理入口从这里回到 App 内。 */
private const val MAIN_ACTIVITY = "com.openandroidintelligence.mobile.MainActivity"

/**
 * 从助理进程打开主 App。
 *
 * 助理会话没有 Activity 栈，必须带 `NEW_TASK`；同一 App 内启动自己的 Activity 不需要
 * 额外权限，但系统仍可能因后台启动限制拒绝，因此失败要如实返回 false，
 * 由界面给出"请从启动器打开"的明确提示，而不是静默无反应。
 */
internal fun openAssistantMainApp(context: Context): Boolean = runCatching {
    context.startActivity(
        Intent()
            .setClassName(context.packageName, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    true
}.getOrDefault(false)
