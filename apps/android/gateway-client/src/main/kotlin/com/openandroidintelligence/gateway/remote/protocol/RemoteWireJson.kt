package com.openandroidintelligence.gateway.remote.protocol

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 复用中性 JSON 语法树，并在 Remote 信任边界拒绝重复键、非法数字和过深输入。 */
object RemoteWireJson {
    private val numberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private const val MAX_PARSER_DEPTH = 128

    fun decode(bytes: ByteArray): JsonValue = try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        parse(text)
    } catch (_: Exception) { throw RemoteProtocolException("INVALID_WIRE_JSON") }

    fun parse(text: String): JsonValue = try {
        checkDepth(text)
        Json.parse(text).also(::validate)
    } catch (_: Exception) { throw RemoteProtocolException("INVALID_WIRE_JSON") }

    fun validate(value: JsonValue) {
        val pending = ArrayDeque<JsonValue>()
        pending.add(value)
        while (pending.isNotEmpty()) {
            when (val item = pending.removeLast()) {
                is JsonValue.JObject -> {
                    val seen = mutableSetOf<String>()
                    item.fields.forEach { (key, child) ->
                        if (!seen.add(key)) throw RemoteProtocolException("INVALID_WIRE_JSON")
                        pending.add(child)
                    }
                }
                is JsonValue.JArray -> pending.addAll(item.items)
                is JsonValue.JNumber -> if (!numberPattern.matches(item.raw) || !item.raw.toDouble().isFinite()) {
                    throw RemoteProtocolException("INVALID_WIRE_JSON")
                }
                else -> Unit
            }
        }
    }

    // 仅计算结构预算，不替代语法解析；避免外部输入先耗尽递归解析器栈。
    private fun checkDepth(text: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in text) {
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else when (char) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_PARSER_DEPTH) throw RemoteProtocolException("INVALID_WIRE_JSON")
                }
                '}', ']' -> depth -= 1
            }
        }
    }
}
