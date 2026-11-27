package com.openandroidintelligence.mobile.screen

import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/** Display metadata never retains input text, phone numbers, queries, or unknown field values. */
internal fun temporaryApprovalParameterSummary(parameters: JsonValue): List<String> {
    val fields = JsonFields.obj(parameters)?.fields ?: return listOf("参数按本次批准的完整请求精确匹配")
    if (fields.isEmpty()) return listOf("未指定额外筛选；仍受本机保存的数据范围限制")
    return fields.take(24).map { (key, value) ->
        when (key) {
            "packageIds" -> "应用来源：${(value as? JsonValue.JArray)?.items?.size ?: 0} 项已批准条件"
            "packageName" -> "目标应用：已指定"
            "senders", "phoneNumbers" -> "电话号码：已指定筛选条件"
            "query", "keywords", "text" -> "查询或输入内容：已指定（不在设置中保留正文）"
            "receivedAfter", "receivedBefore" -> "时间范围：已指定"
            "limit" -> "读取数量：已指定"
            "action", "operation" -> "操作参数：已指定"
            "nodeId", "x", "y", "expectedRevision" -> "目标界面与控件：已指定"
            "fields", "fieldAccess" -> "读取字段：已指定"
            else -> "另有一项批准参数，其值仅用于精确校验"
        }
    }.distinct()
}
