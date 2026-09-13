package com.openandroidintelligence.gateway.schema

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Keeps [SchemaContractHash.CORE] honest.
 *
 * The constant is what the phone signs into its negotiation request, so a Schema
 * change that is not reflected here would make every negotiation fail with
 * PROTOCOL_INCOMPATIBLE. Recomputing from the contract package turns that
 * failure into a build-time one with an obvious cause.
 */
class SchemaContractHashTest {

    @Test
    fun `checked in digest matches the contract package`() {
        val schemaDirectory = File(SharedContract.contractDir(), "schemas")

        assertEquals(SchemaContractHash.CORE, SchemaContractHash.compute(schemaDirectory))
    }

    @Test
    fun `digest changes when a schema document changes`() {
        val schemaDirectory = File(SharedContract.contractDir(), "schemas")
        val copied = createTempDirectory(prefix = "schema-hash").toFile()
        try {
            schemaDirectory.listFiles()!!.forEach { file ->
                file.copyTo(File(copied, file.name), overwrite = true)
            }
            val target = File(copied, SchemaContractHash.FILE_NAMES.first())
            target.writeText(target.readText() + "\n")

            assertEquals(
                false,
                SchemaContractHash.CORE == SchemaContractHash.compute(copied),
            )
        } finally {
            copied.deleteRecursively()
        }
    }
}
