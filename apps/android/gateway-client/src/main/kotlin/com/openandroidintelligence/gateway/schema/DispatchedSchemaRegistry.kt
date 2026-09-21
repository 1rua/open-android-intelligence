package com.openandroidintelligence.gateway.schema

import java.io.File

/**
 * Locates the shared contract package at runtime without an absolute path, so
 * the module can be built and tested on any machine.
 */
object SharedContract {

    private const val CONTRACT_DIR_NAME = "gateway-contract"

    fun contractDir(): File {
        val candidates = mutableListOf<File>()
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            candidates += File(current, CONTRACT_DIR_NAME)
            candidates += File(current, "apps/android/$CONTRACT_DIR_NAME")
            val parent = current.parentFile ?: break
            current = parent
        }
        return candidates.firstOrNull { isContractDir(it) }
            ?: error("CONTRACT_DIR_UNAVAILABLE: could not locate $CONTRACT_DIR_NAME from ${System.getProperty("user.dir")}")
    }

    private fun isContractDir(dir: File): Boolean =
        dir.isDirectory &&
            File(dir, "vectors/dispatched-schema-fixtures.json").isFile &&
            File(dir, "schemas/envelope.schema.json").isFile
}

data class CatalogEntry(
    val key: Map<String, Any?>,
    val logicalKey: Map<String, Any?>,
    val schemaSha256: String,
    val schema: JsonValue,
)

data class SchemaBinding(
    val kind: String,
    val logicalKey: Map<String, Any?>,
    val schemaSha256: String,
)

/**
 * The one dispatched fixture registry, read from the contract package.
 *
 * Android deliberately keeps no local copy: if it did, it could validate a
 * device request against a capability sub-schema the Gateway never negotiated.
 * Every digest is recomputed here, so an edited registry fails closed.
 */
class DispatchedSchemaRegistry private constructor(
    val formatVersion: String,
    val bindingSetId: String,
    val catalogEntries: List<CatalogEntry>,
    val bindings: List<SchemaBinding>,
) {

    /**
     * Resolves a dispatch to a bound sub-schema digest.
     *
     * A dispatch is data: it may name a capability but must never carry the
     * schema or its digest, otherwise a caller could validate against whatever
     * it likes.
     */
    fun resolve(dispatch: Map<String, Any?>, bindingSetId: String): SchemaBinding {
        if (bindingSetId != this.bindingSetId) error("UNKNOWN_BINDING_SET:$bindingSetId")
        for (forbidden in DISPATCH_FORBIDDEN_KEYS) {
            if (dispatch.containsKey(forbidden)) error("DISPATCH_CARRIES_SCHEMA:$forbidden")
        }
        val canonicalDispatch = Json.canonical(Json.of(dispatch))
        return bindings.firstOrNull { Json.canonical(Json.of(it.logicalKey)) == canonicalDispatch }
            ?: error("UNBOUND_DISPATCH:$canonicalDispatch")
    }

    companion object {
        private val DISPATCH_FORBIDDEN_KEYS =
            setOf("schemaSha256", "schema", "binding", "resolver", "validator")
        private const val EXPECTED_FORMAT_VERSION = "1.0.0"
        private const val EXPECTED_BINDING_SET_ID = "gateway-core-fixtures-v1"
        private const val EXPECTED_ENTRY_COUNT = 7

        fun fromContractDir(contractDir: File): DispatchedSchemaRegistry {
            val file = File(contractDir, "vectors/dispatched-schema-fixtures.json")
            val document = Json.parse(file.readText(Charsets.UTF_8)) as? JsonValue.JObject
                ?: error("INVALID_FIXTURE_REGISTRY: root is not an object")

            val formatVersion = stringField(document, "formatVersion")
            if (formatVersion != EXPECTED_FORMAT_VERSION) error("INVALID_FIXTURE_REGISTRY:formatVersion")

            val catalog = (field(document, "catalogEntries") as? JsonValue.JArray)
                ?.items?.map { it as? JsonValue.JObject ?: error("INVALID_FIXTURE_REGISTRY:catalogEntry") }
                ?: error("INVALID_FIXTURE_REGISTRY:catalogEntries")
            if (catalog.size != EXPECTED_ENTRY_COUNT) error("INVALID_FIXTURE_REGISTRY:catalogCount")

            val bindingSets = (field(document, "bindingSets") as? JsonValue.JArray)?.items
                ?: error("INVALID_FIXTURE_REGISTRY:bindingSets")
            if (bindingSets.size != 1) error("INVALID_FIXTURE_REGISTRY:bindingSetCount")
            val bindingSet = bindingSets[0] as? JsonValue.JObject
                ?: error("INVALID_FIXTURE_REGISTRY:bindingSet")
            val bindingSetId = stringField(bindingSet, "id")
            if (bindingSetId != EXPECTED_BINDING_SET_ID) error("INVALID_FIXTURE_REGISTRY:bindingSetId")

            val entries = catalog.map { entry ->
                val keyObject = field(entry, "key") as? JsonValue.JObject
                    ?: error("INVALID_FIXTURE_REGISTRY:key")
                val schema = field(entry, "schema")
                val key = keyObject.fields.associate { (name, value) -> name to jsonToKotlin(value) }
                val registered = key["schemaSha256"] as? String
                    ?: error("INVALID_FIXTURE_REGISTRY:schemaSha256")
                val recomputed = Json.sha256(schema)
                if (registered != recomputed) {
                    error("INVALID_FIXTURE_REGISTRY:digestMismatch:$registered != $recomputed")
                }
                CatalogEntry(
                    key = key,
                    logicalKey = key.filterKeys { it != "schemaSha256" },
                    schemaSha256 = registered,
                    schema = schema,
                )
            }

            val rawBindings = (field(bindingSet, "bindings") as? JsonValue.JArray)?.items
                ?: error("INVALID_FIXTURE_REGISTRY:bindings")
            if (rawBindings.size != EXPECTED_ENTRY_COUNT) error("INVALID_FIXTURE_REGISTRY:bindingCount")

            val bindings = rawBindings.map { item ->
                val binding = item as? JsonValue.JObject ?: error("INVALID_FIXTURE_REGISTRY:binding")
                // A binding is `{ "key": <logical key>, "schemaSha256": <digest> }`;
                // it names a catalog entry and never carries a schema of its own.
                val logicalKeyObject = binding.fields.firstOrNull { it.first == "key" }?.second
                    as? JsonValue.JObject ?: error("INVALID_FIXTURE_REGISTRY:bindingKey")
                val digest = (binding.fields.firstOrNull { it.first == "schemaSha256" }?.second
                    as? JsonValue.JString)?.value ?: error("INVALID_FIXTURE_REGISTRY:bindingDigest")

                val logicalKey = logicalKeyObject.fields.associate { (name, value) -> name to jsonToKotlin(value) }
                if (logicalKey.containsKey("schemaSha256")) {
                    error("INVALID_FIXTURE_REGISTRY:logicalKeyCarriesDigest")
                }
                val canonicalLogical = Json.canonical(Json.of(logicalKey))
                val entry = entries.firstOrNull {
                    Json.canonical(Json.of(it.logicalKey)) == canonicalLogical
                } ?: error("INVALID_FIXTURE_REGISTRY:unboundBinding:$canonicalLogical")
                if (entry.schemaSha256 != digest) error("INVALID_FIXTURE_REGISTRY:bindingDigestMismatch")
                SchemaBinding(
                    kind = (logicalKey["kind"] as? String) ?: error("INVALID_FIXTURE_REGISTRY:kind"),
                    logicalKey = logicalKey,
                    schemaSha256 = digest,
                )
            }

            return DispatchedSchemaRegistry(formatVersion, bindingSetId, entries, bindings)
        }

        private fun field(source: JsonValue.JObject, name: String): JsonValue =
            source.fields.firstOrNull { it.first == name }?.second
                ?: error("INVALID_FIXTURE_REGISTRY:missing:$name")

        private fun stringField(source: JsonValue.JObject, name: String): String =
            (field(source, name) as? JsonValue.JString)?.value
                ?: error("INVALID_FIXTURE_REGISTRY:notAString:$name")

        private fun jsonToKotlin(value: JsonValue): Any? = when (value) {
            is JsonValue.JNull -> null
            is JsonValue.JBool -> value.value
            is JsonValue.JString -> value.value
            is JsonValue.JNumber -> value.raw.toLongOrNull() ?: value.raw.toDouble()
            is JsonValue.JArray -> value.items.map { jsonToKotlin(it) }
            is JsonValue.JObject -> value.fields.associate { (name, item) -> name to jsonToKotlin(item) }
        }
    }
}
