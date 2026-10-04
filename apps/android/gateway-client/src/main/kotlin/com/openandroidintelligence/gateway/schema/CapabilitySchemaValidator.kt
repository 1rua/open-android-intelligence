package com.openandroidintelligence.gateway.schema

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Collections
import java.util.IdentityHashMap

/** Validate finite capability inputs; reference traversal does not limit data depth. */
object CapabilitySchemaValidator {
    fun accepts(schema: JsonValue, value: JsonValue): Boolean = runCatching { Validation().check(schema,value,schema) }.getOrDefault(false)
    private class Validation {
        private val active = IdentityHashMap<JsonValue,MutableSet<JsonValue>>()
        fun check(schema: JsonValue, value: JsonValue, root: JsonValue): Boolean {
            val values = active.getOrPut(schema) { Collections.newSetFromMap(IdentityHashMap<JsonValue,Boolean>()) }
            // A reference cycle is valid when it descends into another input node.
            // Repeating the same schema/input pair cannot make progress.
            if (!values.add(value)) return false
            try { return valid(schema,value,root,this) }
            finally { values.remove(value); if (values.isEmpty()) active.remove(schema) }
        }
    }
    private fun valid(schema: JsonValue, value: JsonValue, root: JsonValue, validation: Validation): Boolean {
        val s = schema as? JsonValue.JObject ?: return (schema as? JsonValue.JBool)?.value == true
        fun f(key: String) = s.fields.firstOrNull { it.first == key }?.second
        fun number(key: String) = (f(key) as? JsonValue.JNumber)?.raw?.toBigDecimal()
        val supported = setOf("type","properties","required","additionalProperties","enum","const","minLength","maxLength","pattern","format",
            "minimum","maximum","exclusiveMinimum","exclusiveMaximum","multipleOf","minItems","maxItems","uniqueItems","items","allOf","anyOf","oneOf",
            "\$defs","\$ref","title","description","\$schema","\$id")
        if (s.fields.any { it.first !in supported } || s.fields.map { it.first }.distinct().size != s.fields.size) return false
        f("\$ref")?.let { reference ->
            val ref = (reference as? JsonValue.JString)?.value ?: return false
            if (!ref.startsWith("#/\$defs/") || ref.length <= "#/\$defs/".length) return false
            var target = root
            for (token in ref.removePrefix("#/").split('/')) {
                if (Regex("~(?![01])").containsMatchIn(token)) return false
                target = JsonFields.field(target as? JsonValue.JObject,token.replace("~1","/").replace("~0","~")) ?: return false
            }
            if (!validation.check(target,value,root)) return false
        }
        f("const")?.let { if (Json.canonical(it) != Json.canonical(value)) return false }
        (f("enum") as? JsonValue.JArray)?.let { if (it.items.none { candidate -> Json.canonical(candidate) == Json.canonical(value) }) return false }
        val type = (f("type") as? JsonValue.JString)?.value
        if (type != null && !when(type) {
            "object" -> value is JsonValue.JObject; "array" -> value is JsonValue.JArray; "string" -> value is JsonValue.JString
            "boolean" -> value is JsonValue.JBool; "null" -> value is JsonValue.JNull
            "number" -> value is JsonValue.JNumber
            "integer" -> value is JsonValue.JNumber && value.raw.toBigDecimal().stripTrailingZeros().scale() <= 0
            else -> false
        }) return false
        if (value is JsonValue.JObject) {
            if (value.fields.map { it.first }.distinct().size != value.fields.size) return false
            val props = (f("properties") as? JsonValue.JObject)?.fields?.toMap().orEmpty()
            if (f("additionalProperties") == JsonValue.JBool(false) && value.fields.any { it.first !in props }) return false
            if ((f("required") as? JsonValue.JArray)?.items.orEmpty().any { (it as? JsonValue.JString)?.value !in value.fields.map { field -> field.first } }) return false
            if (value.fields.any { (key,item) -> props[key]?.let { !validation.check(it,item,root) } == true }) return false
        }
        if (value is JsonValue.JString) {
            val length = value.value.codePointCount(0,value.value.length).toBigDecimal()
            if (number("minLength")?.let { length < it } == true || number("maxLength")?.let { length > it } == true) return false
            (f("pattern") as? JsonValue.JString)?.let { if (!Regex(it.value).containsMatchIn(value.value)) return false }
            (f("format") as? JsonValue.JString)?.let { if (it.value == "date-time") OffsetDateTime.parse(value.value) else if (it.value == "uuid") java.util.UUID.fromString(value.value) else return false }
        }
        if (value is JsonValue.JNumber) {
            val n = value.raw.toBigDecimal()
            if (number("minimum")?.let { n < it } == true || number("maximum")?.let { n > it } == true ||
                number("exclusiveMinimum")?.let { n <= it } == true || number("exclusiveMaximum")?.let { n >= it } == true ||
                number("multipleOf")?.let { it <= BigDecimal.ZERO || n.remainder(it).compareTo(BigDecimal.ZERO) != 0 } == true) return false
        }
        if (value is JsonValue.JArray) {
            val count = value.items.size.toBigDecimal()
            if (number("minItems")?.let { count < it } == true || number("maxItems")?.let { count > it } == true) return false
            if (f("uniqueItems") == JsonValue.JBool(true) && value.items.map(Json::canonical).distinct().size != value.items.size) return false
            f("items")?.let { child -> if (value.items.any { !validation.check(child,it,root) }) return false }
        }
        for (key in listOf("allOf","anyOf","oneOf")) {
            val group = f(key) as? JsonValue.JArray ?: continue
            val matches = group.items.count { validation.check(it,value,root) }
            if (key == "allOf" && matches != group.items.size || key == "anyOf" && matches == 0 || key == "oneOf" && matches != 1) return false
        }
        return true
    }
}
