package com.openandroidintelligence.mobile.screen

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

internal fun deviceRequestAction(capability: String, operation: String, parameters: String): String = when {
    capability.startsWith("org.openandroidintelligence.screen-control.") || capability == "kernel.screen.control" ->
        summarizeDeviceControl(capability, parameters).action
    capability.contains("screen-capture") || capability == "kernel.screen.capture" -> "获取屏幕截图"
    capability.contains("screen-reader") || capability == "kernel.screen.read" -> "读取屏幕内容"
    capability.contains("sms.query") -> "读取短信"
    capability.contains("sms.schedule") -> "设置短信后台同步任务"
    capability.contains("sms.job-status") -> "查询短信任务状态"
    capability.contains("sms.cancel") -> "取消短信后台同步任务"
    capability.contains("notifications.query") -> "读取通知"
    capability.contains("call-log.query") -> "读取通话记录"
    else -> "设备操作：${operation.take(160).ifBlank { "未说明" }}"
}

/** 展示实际请求参数，不据展示文本推断或扩大授权范围。输入正文保留在技术详情中。 */
internal fun deviceRequestParameterLines(parameters: String): List<String> {
    val fields = runCatching { JsonFields.obj(Json.parse(parameters))?.fields }.getOrNull()
        ?: return listOf("参数无法解析，请核对技术详情；授权范围以系统实际执行检查为准。")
    if (fields.isEmpty()) return listOf("未指定额外筛选参数；实际访问范围仍受本机设置限制。")
    val lines = fields.take(24).map { (key, value) ->
        val label = when (key) {
            "packageIds" -> "应用来源"
            "packageName" -> "目标应用"
            "senders" -> "短信发送方"
            "receivedAfter" -> "起始时间"
            "receivedBefore" -> "结束时间"
            "limit" -> "最多读取条数"
            "query" -> "查询条件"
            "fields", "fieldAccess" -> "读取字段"
            "scope" -> "请求范围"
            "action" -> "屏幕动作"
            "operation" -> "操作类型"
            "x" -> "横坐标"
            "y" -> "纵坐标"
            "durationMs" -> "动作时长（毫秒）"
            "nodeId" -> "目标控件"
            "expectedRevision" -> "屏幕状态校验"
            "text" -> "输入内容"
            else -> key.take(80)
        }
        val content = when (key) {
            "nodeId", "expectedRevision" -> "已指定"
            "text" -> "已提供${(value as? JsonValue.JString)?.value?.let { "（${it.length} 字符）" }.orEmpty()}，可在技术详情核对"
            else -> (if (value is JsonValue.JString) value.value else Json.canonical(value)).let {
                if (it.length > 160) it.take(160) + "…（完整内容见技术详情）" else it
            }
        }
        "$label：$content"
    }
    return if (fields.size > 24) lines + "另有 ${fields.size - 24} 项参数，请查看技术详情。" else lines
}

internal fun deviceRequestSensitiveNotice(capability: String): String = when {
    capability.contains("screen-control") || capability == "kernel.screen.control" ->
        "可能点击、输入文字或切换应用，改变手机当前状态。"
    capability.contains("screen-") || capability.startsWith("kernel.screen.") ->
        "读取内容可能包含屏幕上的个人信息；截图许可不会授予屏幕控制权限。"
    capability.contains("sms") || capability.contains("notifications") || capability.contains("call-log") ->
        "可能涉及正文、来源、号码和时间等敏感信息；仅按实际能力和本机允许范围执行。"
    else -> "可能读取或改变设备数据，请核对操作及访问范围。"
}
