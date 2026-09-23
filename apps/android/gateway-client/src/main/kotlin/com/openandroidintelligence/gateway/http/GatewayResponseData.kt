package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/** Gateway Protocol v2 §2: successful endpoint payloads live inside `data`. */
fun GatewayResponse.requireData(operation: String): JsonValue.JObject {
    val envelope = runCatching { JsonFields.obj(Json.parse(String(body, Charsets.UTF_8))) }.getOrNull()
    if (status !in 200..299) {
        val error = JsonFields.obj(JsonFields.field(envelope, "error"))
        val code = JsonFields.string(error, "code")?.takeIf { Regex("[A-Z0-9_]+").matches(it) }
        throw IllegalStateException("$operation:${code ?: status}")
    }
    if (JsonFields.string(envelope, "protocol") != "2.1") {
        throw IllegalStateException("$operation:invalid-envelope")
    }
    return JsonFields.obj(JsonFields.field(envelope, "data"))
        ?: throw IllegalStateException("$operation:missing-data")
}
