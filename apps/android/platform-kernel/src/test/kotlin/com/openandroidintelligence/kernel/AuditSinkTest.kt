package com.openandroidintelligence.kernel

import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditSinkTest {

    private val event = AuditEvent(
        pluginId = "platform",
        accountId = "account-a",
        pairingId = "pairing-a",
        action = "emergency.stop",
        outcome = AuditOutcome.ALLOWED,
        correlationId = "corr-a",
        timestampUtc = "2026-09-03T00:00:00.000Z",
    )

    @Test
    fun inMemorySinkPublishesEveryRecord() {
        val sink = InMemoryAuditSink()

        assertTrue(sink.eventsFlow.value.isEmpty())
        sink.write(event)

        assertEquals(listOf(event), sink.eventsFlow.value)
        assertEquals(listOf(event), sink.events())
    }

    @Test
    fun persistentSinkReloadsRecentRecordsWithoutBodyFields() {
        val directory = Files.createTempDirectory("android-audit-test").toFile()
        try {
            val file = File(directory, "events.log")
            PersistentAuditSink(
                file = file,
                clock = { Instant.parse("2026-09-04T00:00:00Z") },
            ).write(event)

            val reloaded = PersistentAuditSink(
                file = file,
                clock = { Instant.parse("2026-09-04T00:00:00Z") },
            )

            assertEquals(listOf(event), reloaded.events())
            // 防篡改格式升级后，新记录一律是带哈希链的 v2 行；正文仍不得落盘。
            assertTrue(file.readText().startsWith("v2|"))
            assertTrue(file.readText().contains("emergency.stop").not())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun persistentSinkDropsRecordsOlderThanItsRetentionWindow() {
        val directory = Files.createTempDirectory("android-audit-retention-test").toFile()
        try {
            val file = File(directory, "events.log")
            PersistentAuditSink(
                file = file,
                clock = { Instant.parse("2026-07-02T00:00:00Z") },
            ).write(event.copy(timestampUtc = "2026-07-01T00:00:00.000Z"))

            val reloaded = PersistentAuditSink(
                file = file,
                clock = { Instant.parse("2026-08-02T00:00:00Z") },
            )

            assertTrue(reloaded.events().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun androidAuditStoreSanitizesIdentityFieldsBeforeWriting() {
        val sink = InMemoryAuditSink()
        val store = AndroidAuditStore(sink)

        store.record(
            pluginId = "plugin payload with body",
            accountId = "account-a",
            pairingId = "pairing-a",
            action = "invoke",
            outcome = AuditOutcome.ALLOWED,
            correlationId = "corr-a",
        )

        assertEquals("redacted", sink.events().single().pluginId)
    }
}
