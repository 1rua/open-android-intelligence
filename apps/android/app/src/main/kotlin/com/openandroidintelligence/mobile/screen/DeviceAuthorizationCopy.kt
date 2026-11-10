package com.openandroidintelligence.mobile.screen

import android.content.Context
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.mobile.plugins.currentApplicationContext
import com.openandroidintelligence.ui.resources.R as UiR

internal fun deviceRequestAction(capability: String, operation: String, parameters: String, context: Context? = null): String {
    val ctx = context ?: currentApplicationContext() ?: error("Context required")
    return when {
        capability.startsWith("org.openandroidintelligence.screen-control.") || capability == "kernel.screen.control" ->
            summarizeDeviceControl(capability, parameters).action
        capability.contains("screen-capture") || capability == "kernel.screen.capture" -> ctx.getString(UiR.string.perm_auth_cap_screen_capture)
        capability.contains("screen-reader") || capability == "kernel.screen.read" -> ctx.getString(UiR.string.perm_auth_cap_screen_read)
        capability.contains("sms.query") -> ctx.getString(UiR.string.perm_auth_cap_sms_read)
        capability.contains("sms.schedule") -> ctx.getString(UiR.string.perm_auth_cap_sms_sync)
        capability.contains("sms.job-status") -> ctx.getString(UiR.string.perm_auth_cap_sms_status)
        capability.contains("sms.cancel") -> ctx.getString(UiR.string.perm_auth_cap_sms_cancel)
        capability.contains("notifications.query") -> ctx.getString(UiR.string.perm_auth_cap_notifications_read)
        capability.contains("call-log.query") -> ctx.getString(UiR.string.perm_auth_cap_call_log_read)
        else -> ctx.getString(UiR.string.perm_auth_operation_format, operation.take(160).ifBlank { ctx.getString(UiR.string.perm_auth_unspecified) })
    }
}

/** 展示实际请求参数，不据展示文本推断或扩大授权范围。输入正文保留在技术详情中。 */
internal fun deviceRequestParameterLines(parameters: String, context: Context? = null): List<String> {
    val ctx = context ?: currentApplicationContext() ?: error("Context required")
    val fields = runCatching { JsonFields.obj(Json.parse(parameters))?.fields }.getOrNull()
        ?: return listOf(ctx.getString(UiR.string.perm_auth_params_unparseable))
    if (fields.isEmpty()) return listOf(ctx.getString(UiR.string.perm_auth_params_empty))
    val lines = fields.take(24).map { (key, value) ->
        val label = when (key) {
            "packageIds" -> ctx.getString(UiR.string.perm_auth_field_app_source)
            "packageName" -> ctx.getString(UiR.string.perm_auth_field_target_app)
            "senders" -> ctx.getString(UiR.string.perm_auth_field_sms_sender)
            "receivedAfter" -> ctx.getString(UiR.string.perm_auth_field_start_time)
            "receivedBefore" -> ctx.getString(UiR.string.perm_auth_field_end_time)
            "limit" -> ctx.getString(UiR.string.perm_auth_field_limit)
            "query" -> ctx.getString(UiR.string.perm_auth_field_query)
            "fields", "fieldAccess" -> ctx.getString(UiR.string.perm_auth_field_fields)
            "scope" -> ctx.getString(UiR.string.perm_auth_field_scope)
            "action" -> ctx.getString(UiR.string.perm_auth_field_screen_action)
            "operation" -> ctx.getString(UiR.string.perm_auth_field_op_type)
            "x" -> ctx.getString(UiR.string.perm_auth_field_x)
            "y" -> ctx.getString(UiR.string.perm_auth_field_y)
            "durationMs" -> ctx.getString(UiR.string.perm_auth_field_duration)
            "nodeId" -> ctx.getString(UiR.string.perm_auth_field_target_view)
            "expectedRevision" -> ctx.getString(UiR.string.perm_auth_field_state_check)
            "text" -> ctx.getString(UiR.string.perm_auth_field_input_text)
            else -> key.take(80)
        }
        val content = when (key) {
            "nodeId", "expectedRevision" -> ctx.getString(UiR.string.perm_auth_param_specified)
            "text" -> {
                val charsText = (value as? JsonValue.JString)?.value?.let { ctx.getString(UiR.string.perm_auth_param_chars_format, it.length) }.orEmpty()
                ctx.getString(UiR.string.perm_auth_param_text_format, charsText)
            }
            else -> (if (value is JsonValue.JString) value.value else Json.canonical(value)).let {
                if (it.length > 160) it.take(160) + ctx.getString(UiR.string.perm_auth_param_ellipsis) else it
            }
        }
        "$label：$content"
    }
    return if (fields.size > 24) lines + ctx.getString(UiR.string.perm_auth_param_more_format, fields.size - 24) else lines
}

internal fun deviceRequestSensitiveNotice(capability: String, context: Context? = null): String {
    val ctx = context ?: currentApplicationContext() ?: error("Context required")
    return when {
        capability.contains("screen-control") || capability == "kernel.screen.control" ->
            ctx.getString(UiR.string.perm_auth_risk_control)
        capability.contains("screen-") || capability.startsWith("kernel.screen.") ->
            ctx.getString(UiR.string.perm_auth_risk_screen_read)
        capability.contains("sms") || capability.contains("notifications") || capability.contains("call-log") ->
            ctx.getString(UiR.string.perm_auth_risk_data_read)
        else -> ctx.getString(UiR.string.perm_auth_risk_generic)
    }
}
