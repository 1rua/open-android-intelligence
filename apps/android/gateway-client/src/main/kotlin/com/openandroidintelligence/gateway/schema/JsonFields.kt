package com.openandroidintelligence.gateway.schema

/**
 * Typed reads over the parsed JSON model.
 *
 * Every read is positional-safe and returns null instead of guessing: a Gateway
 * response that omits a field must surface as a missing field, not as an empty
 * string or zero that later looks like a real value the server sent.
 */
object JsonFields {

    fun obj(value: JsonValue?): JsonValue.JObject? = value as? JsonValue.JObject

    fun array(value: JsonValue?): JsonValue.JArray? = value as? JsonValue.JArray

    fun field(source: JsonValue.JObject?, name: String): JsonValue? =
        source?.fields?.firstOrNull { it.first == name }?.second

    fun string(source: JsonValue.JObject?, name: String): String? =
        (field(source, name) as? JsonValue.JString)?.value

    fun bool(source: JsonValue.JObject?, name: String): Boolean? =
        (field(source, name) as? JsonValue.JBool)?.value

    fun long(source: JsonValue.JObject?, name: String): Long? =
        (field(source, name) as? JsonValue.JNumber)?.raw?.toLongOrNull()

    fun double(source: JsonValue.JObject?, name: String): Double? =
        (field(source, name) as? JsonValue.JNumber)?.raw?.toDoubleOrNull()

    fun int(source: JsonValue.JObject?, name: String): Int? =
        long(source, name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** Objects nested inside an array, skipping anything that is not an object. */
    fun objects(source: JsonValue.JObject?, name: String): List<JsonValue.JObject> =
        array(field(source, name))?.items?.mapNotNull { it as? JsonValue.JObject } ?: emptyList()

    fun strings(source: JsonValue.JObject?, name: String): List<String> =
        array(field(source, name))?.items?.mapNotNull { (it as? JsonValue.JString)?.value } ?: emptyList()
}
