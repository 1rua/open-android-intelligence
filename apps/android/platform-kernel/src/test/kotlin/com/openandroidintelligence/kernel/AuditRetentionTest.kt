package com.openandroidintelligence.kernel

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AuditRetentionTest {
    private fun event(time: Instant, id: String) = AuditEvent("platform", "account", "pairing", "plugin.invoke", AuditOutcome.ALLOWED, id, time.toString())

    @Test fun prunesExpiredPrefixAndPreservesRetainedHashesAcrossRestart() {
        val directory = Files.createTempDirectory("audit-retention").toFile()
        try {
            val file = File(directory, "events.log")
            val clock = AtomicReference(Instant.parse("2026-08-01T00:00:00Z"))
            val sink = PersistentAuditSink(file, clock::get)
            sink.write(event(clock.get(), "old"))
            clock.set(clock.get().plus(20, ChronoUnit.DAYS))
            sink.write(event(clock.get(), "retained"))
            val retainedLine = file.readLines().last()
            clock.set(clock.get().plus(20, ChronoUnit.DAYS))
            val restored = PersistentAuditSink(file, clock::get)
            assertEquals(listOf("retained"), restored.events().map { it.correlationId })
            assertEquals(2, file.readLines().size)
            assertTrue(file.readLines().first().startsWith("v3-anchor|"))
            assertEquals(retainedLine, file.readLines().last())
            assertTrue(restored.verifyChain().isIntact)
            restored.write(event(clock.get(), "new"))
            assertTrue(PersistentAuditSink(file, clock::get).verifyChain().isIntact)
        } finally { directory.deleteRecursively() }
    }

    @Test fun retentionDoesNotRewriteAwayAnExpiredTamperedRecord() {
        val directory = Files.createTempDirectory("audit-retention-tamper").toFile()
        try {
            val file = File(directory, "events.log")
            val old = Instant.parse("2026-08-01T00:00:00Z")
            PersistentAuditSink(file, { old }).write(event(old, "old"))
            file.appendText("corrupted-record\n")
            val evidence = file.readText()
            val restored = PersistentAuditSink(file, { old.plus(40, ChronoUnit.DAYS) })
            assertEquals(AuditChainStatus.BROKEN, restored.verifyChain().status)
            assertEquals(evidence, file.readText())
        } finally { directory.deleteRecursively() }
    }
}
