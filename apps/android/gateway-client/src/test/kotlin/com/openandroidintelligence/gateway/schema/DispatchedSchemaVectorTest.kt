package com.openandroidintelligence.gateway.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android must consume the one shared dispatched fixture registry. It must not
 * keep its own copy: a local copy would let the Android client accept a
 * capability sub-schema the Gateway never negotiated.
 *
 * These tests read the registry directly from the contract package, so they fail
 * if the file is missing, edited, or replaced.
 */
class DispatchedSchemaVectorTest {

    private fun registry(): DispatchedSchemaRegistry =
        DispatchedSchemaRegistry.fromContractDir(SharedContract.contractDir())

    @Test
    fun readsTheSharedRegistryAndRecomputesEveryDigest() {
        val registry = registry()

        assertEquals("1.0.0", registry.formatVersion)
        assertEquals(7, registry.catalogEntries.size)
        assertEquals("gateway-core-fixtures-v1", registry.bindingSetId)
        assertEquals(
            "every binding must point at a catalog entry with a matching digest",
            7,
            registry.bindings.size,
        )
    }

    @Test
    fun theCommandResultPayloadIsBoundForThisClientToo() {
        val registry = registry()

        val resolved = registry.resolve(
            dispatch = mapOf("kind" to "event", "eventType" to "conversation.command.result"),
            bindingSetId = "gateway-core-fixtures-v1",
        )
        val entry = registry.catalogEntries.first {
            it.logicalKey["eventType"] == "conversation.command.result"
        }

        assertEquals(entry.schemaSha256, resolved.schemaSha256)
    }

    @Test
    fun recomputedDigestsMatchTheRegisteredValues() {
        for (entry in registry().catalogEntries) {
            val recomputed = CanonicalJson.sha256(entry.schema)
            assertEquals(
                "a schema whose digest was recomputed differently must be rejected",
                entry.schemaSha256,
                recomputed,
            )
        }
    }

    @Test
    fun onlyTheNegotiatedBindingSetIsAccepted() {
        val registry = registry()
        val binding = registry.bindings.first { it.kind == "event" }

        val resolved = registry.resolve(
            dispatch = mapOf("kind" to "event", "eventType" to "gateway.notice"),
            bindingSetId = "gateway-core-fixtures-v1",
        )
        assertEquals(binding.schemaSha256, resolved.schemaSha256)
    }

    @Test
    fun anyOtherBindingSetIdFailsClosed() {
        val registry = registry()
        val failure = runCatching {
            registry.resolve(
                dispatch = mapOf("kind" to "event", "eventType" to "gateway.notice"),
                bindingSetId = "android-local-fixtures-v1",
            )
        }.exceptionOrNull()

        assertTrue("only the negotiated binding set may be used", failure != null)
        assertTrue(failure!!.message!!.contains("UNKNOWN_BINDING_SET"))
    }

    @Test
    fun unknownDispatchFailsClosed() {
        val registry = registry()
        val failure = runCatching {
            registry.resolve(
                dispatch = mapOf("kind" to "event", "eventType" to "never.negotiated"),
                bindingSetId = "gateway-core-fixtures-v1",
            )
        }.exceptionOrNull()

        assertTrue("a capability with no bound sub-schema must fail closed", failure != null)
        assertTrue(failure!!.message!!.contains("UNBOUND_DISPATCH"))
    }

    @Test
    fun aDispatchMayNotCarryItsOwnSchemaOrDigest() {
        val registry = registry()
        val failure = runCatching {
            registry.resolve(
                dispatch = mapOf(
                    "kind" to "event",
                    "eventType" to "gateway.notice",
                    "schemaSha256" to "sha256:" + "a".repeat(64),
                ),
                bindingSetId = "gateway-core-fixtures-v1",
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("DISPATCH_CARRIES_SCHEMA"))
    }

    @Test
    fun aSubstitutedRegistryIsRejected() {
        val contractDir = SharedContract.contractDir()
        val registryFile = File(contractDir, "vectors/dispatched-schema-fixtures.json")

        // Exercised against a copy so the tracked contract file is never mutated.
        val tamperedDir = copyContractToTemp()
        val tamperedFile = File(tamperedDir, "gateway-contract/vectors/dispatched-schema-fixtures.json")
        val text = tamperedFile.readText()
        // Widening a schema changes its recomputed digest while leaving every
        // registered digest untouched, which is exactly the substitution the
        // digest check exists to catch.
        val replaced = text.replace("\"type\": \"object\"", "\"type\": \"object\", \"injected\": true")
        assertTrue("the probe must actually change the document", replaced != text)
        tamperedFile.writeText(replaced)

        val failure = runCatching {
            DispatchedSchemaRegistry.fromContractDir(File(tamperedDir, "gateway-contract"))
        }.exceptionOrNull()

        assertTrue("a registry edited after the fact must fail closed", failure != null)
        assertTrue(failure!!.message!!.contains("INVALID_FIXTURE_REGISTRY"))
        assertTrue(registryFile.exists())
    }

    private fun copyContractToTemp(): File {
        val contractDir = SharedContract.contractDir()
        val temp = File(System.getProperty("java.io.tmpdir"), "open-android-intelligence-android-vectors-${System.nanoTime()}")
        val target = File(temp, "gateway-contract/vectors")
        target.mkdirs()
        File(contractDir, "vectors").listFiles()!!.forEach { it.copyTo(File(target, it.name), overwrite = true) }
        return temp
    }
}
