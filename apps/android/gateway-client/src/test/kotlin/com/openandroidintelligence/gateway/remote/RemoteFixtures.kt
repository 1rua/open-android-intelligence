package com.openandroidintelligence.gateway.remote

import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

internal object RemoteFixtures {
    fun bytes(path: String): ByteArray = javaClass.getResourceAsStream("/dsh-remote/$path")
        ?.use { it.readBytes() } ?: error("缺少固定向量文件：$path")

    fun json(path: String): JsonValue.JObject = RemoteWireJson.decode(bytes(path)) as JsonValue.JObject
    fun obj(value: JsonValue.JObject, key: String): JsonValue.JObject = JsonFields.obj(JsonFields.field(value, key))!!
    fun text(value: JsonValue.JObject, key: String): String = JsonFields.string(value, key)!!
    fun list(value: JsonValue.JObject, key: String): List<JsonValue> = JsonFields.array(JsonFields.field(value, key))!!.items

    val noise: JsonValue.JObject get() = list(json("fixtures/crypto/v1/noise-ik.json"), "vectors").single() as JsonValue.JObject
}
