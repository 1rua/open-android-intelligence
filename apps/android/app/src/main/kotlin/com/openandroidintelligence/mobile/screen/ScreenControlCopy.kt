package com.openandroidintelligence.mobile.screen

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/** 一次待确认操作的用户可读描述。 */
data class ControlActionSummary(
    /** 一句话说明即将做什么。 */
    val action: String,
    /** 作用对象（应用名或控件说明）；无法确定时为 null。 */
    val target: String?,
    /** 是否属于屏幕控制（决定标题与严重程度措辞）。 */
    val isScreen: Boolean,
)

/**
 * 把设备请求的原始参数翻译成用户能判断的描述。
 *
 * 设计约束（见 docs/superpowers/specs/2026-10-24-settings-information-architecture.md）：
 * 普通用户不该看到 `pluginId`、能力 ID 或未经解释的 JSON。同时**绝不展示**
 * `nodeId` 原文与输入正文 —— 前者是无意义内部引用，后者属于用户内容，
 * 在确认弹窗里重复一遍等于多一次泄露面。
 */
fun summarizeDeviceControl(
    capability: String,
    parametersJson: String,
): ControlActionSummary {
    val isScreen = capability.startsWith("org.openandroidintelligence.screen-") ||
        capability.startsWith("kernel.screen.")
    val params = parseObject(parametersJson)

    if (!isScreen) return ControlActionSummary(
        action = nonScreenActionLabel(capability),
        target = null,
        isScreen = false,
    )

    val action = params?.let { JsonFields.string(it, "action") }
    val packageName = params?.let { JsonFields.string(it, "packageName") }
    val hasNode = params?.let { JsonFields.string(it, "nodeId") } != null

    val label = when (action) {
        "click" -> if (hasNode) "点击界面上的控件" else "在屏幕坐标处点击一下"
        "longPress" -> if (hasNode) "长按界面上的控件" else "在屏幕坐标处长按"
        "setText" -> "在输入框中填写文字"
        "scroll" -> "滚动界面内容"
        "swipe" -> "在屏幕上滑动"
        "drag" -> "在屏幕上拖动"
        "global" -> when (params?.let { JsonFields.string(it, "globalAction") }) {
            "back" -> "执行返回操作"
            "home" -> "回到主屏幕"
            "recents" -> "打开最近任务"
            "notifications" -> "下拉打开通知栏"
            "quickSettings" -> "打开快捷设置面板"
            else -> "执行一个系统操作"
        }
        "launchApp" -> packageName?.let { "打开应用 $it" } ?: "打开一个应用"
        null -> if (params == null) "读取屏幕内容" else "执行屏幕操作"
        else -> "执行屏幕操作"
    }

    return ControlActionSummary(
        action = label,
        target = when {
            packageName != null && action != "launchApp" -> packageName
            action == "launchApp" -> null
            else -> null
        },
        isScreen = true,
    )
}

/** 非屏幕能力（短信、日历等）的可读动作名。 */
private fun nonScreenActionLabel(capability: String): String = when {
    capability.contains("sms") -> "发送短信"
    capability.contains("call") -> "拨打电话"
    capability.contains("calendar") -> "写入日历"
    capability.contains("alarm") -> "创建或修改闹钟"
    capability.contains("clipboard") -> "写入剪贴板"
    capability.contains("notif") -> "发送系统通知"
    capability.contains("read") -> "读取设备数据"
    else -> "执行设备操作"
}

private fun parseObject(json: String): JsonValue.JObject? =
    runCatching { JsonFields.obj(Json.parse(json)) }.getOrNull()
