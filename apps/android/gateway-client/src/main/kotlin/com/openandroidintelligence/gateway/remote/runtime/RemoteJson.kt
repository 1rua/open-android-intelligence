package com.openandroidintelligence.gateway.remote.runtime

import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

internal fun obj(vararg fields: Pair<String, Any?>): JsonValue.JObject = Json.of(linkedMapOf(*fields)) as JsonValue.JObject
internal fun JsonValue.objectValue(): JsonValue.JObject = this as? JsonValue.JObject ?: throw RemoteRpcException("INVALID_MESSAGE")
internal fun JsonValue.JObject.requiredText(key: String): String = JsonFields.string(this, key)?.takeIf { it.isNotEmpty() }
    ?: throw RemoteRpcException("INVALID_MESSAGE")
internal fun JsonValue.JObject.longValue(key: String): Long = (JsonFields.field(this, key) as? JsonValue.JNumber)?.raw?.toLongOrNull()
    ?: throw RemoteRpcException("INVALID_MESSAGE")
internal fun JsonValue.JObject.optionalText(key: String): String? = JsonFields.string(this, key)
internal fun JsonValue.JObject.value(key: String): JsonValue = JsonFields.field(this, key) ?: throw RemoteRpcException("INVALID_MESSAGE")
internal fun JsonValue.JObject.arrayValue(key: String): List<JsonValue> = (value(key) as? JsonValue.JArray)?.items
    ?: throw RemoteRpcException("INVALID_MESSAGE")
internal fun JsonValue.JObject.booleanValue(key: String): Boolean = (value(key) as? JsonValue.JBool)?.value
    ?: throw RemoteRpcException("INVALID_MESSAGE")
