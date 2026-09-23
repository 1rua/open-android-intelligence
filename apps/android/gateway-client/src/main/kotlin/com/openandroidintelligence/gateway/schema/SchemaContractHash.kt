package com.openandroidintelligence.gateway.schema

import java.io.File
import java.security.MessageDigest

/**
 * The core Schema digest the phone presents during negotiation (contract §4).
 *
 * The value is derived from the checked-in Schema documents exactly as the
 * Gateway derives it: one line per document — `name`, a tab, `sha256:` and the
 * digest of that document's bytes — in a fixed name order, all hashed under a
 * domain separator. [SchemaContractHashTest] recomputes it from the contract
 * package, so a Schema change cannot silently leave a stale constant here.
 */
object SchemaContractHash {

    const val DOMAIN = "open-android-intelligence/v2/core-schema-hash"

    val FILE_NAMES = listOf(
        "attachment.schema.json",
        "conversation.schema.json",
        "device-request.schema.json",
        "envelope.schema.json",
        "event.schema.json",
        "negotiate.schema.json",
        "session.schema.json",
    )

    /** Recomputed and verified by `SchemaContractHashTest` on every build. */
    const val CORE = "sha256:b84d7ee1efacf538e12af5fca7dd55f3123fba42c98f4ffddd20a5483fe46dba"

    fun compute(schemaDirectory: File): String {
        val lines = mutableListOf(DOMAIN)
        for (name in FILE_NAMES) {
            lines += "$name\tsha256:${sha256Hex(File(schemaDirectory, name).readBytes())}"
        }
        return "sha256:" + sha256Hex((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
