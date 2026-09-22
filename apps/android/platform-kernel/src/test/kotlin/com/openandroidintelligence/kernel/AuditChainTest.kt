package com.openandroidintelligence.kernel

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit log is the evidence behind the settings page's "本地不可篡改操作
 * 流水" claim. Evidence means: append-only,每个记录带前序摘要的哈希链, and a reader
 * that reports a broken chain instead of quietly returning records as if the file
 * were still trustworthy.
 */
class AuditChainTest {

    private val clock = { Instant.parse("2026-09-04T00:00:00Z") }

    private fun event(action: String, correlationId: String = "corr-a") = AuditEvent(
        pluginId = "platform",
        accountId = "account-a",
        pairingId = "pairing-a",
        action = action,
        outcome = AuditOutcome.ALLOWED,
        correlationId = correlationId,
        timestampUtc = "2026-09-03T00:00:00.000Z",
    )

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("android-audit-chain").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun base64(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    @Test
    fun writesAppendOnlyAndChainsEveryRecordToItsPredecessor() = withDirectory { directory ->
        val file = File(directory, "events.log")
        val sink = PersistentAuditSink(file = file, clock = clock)

        sink.write(event("plugin.invoke"))
        val afterFirst = file.readText()
        sink.write(event("plugin.invoke", correlationId = "corr-b"))
        val afterSecond = file.readText()

        assertTrue("写入必须是追加，不能整文件重写", afterSecond.startsWith(afterFirst))
        val verification = sink.verifyChain()
        assertEquals(AuditChainStatus.INTACT, verification.status)
        assertEquals(2, verification.verifiedRecords)
        assertEquals(null, verification.firstBrokenRecord)
        assertNotNull("链头必须可导出，供外部锚定", verification.headHash)
    }

    @Test
    fun theChainContinuesAcrossAReload() = withDirectory { directory ->
        val file = File(directory, "events.log")
        PersistentAuditSink(file = file, clock = clock).write(event("plugin.invoke"))

        val reopened = PersistentAuditSink(file = file, clock = clock)
        reopened.write(event("plugin.invoke", correlationId = "corr-b"))

        val verification = reopened.verifyChain()
        assertEquals(AuditChainStatus.INTACT, verification.status)
        assertEquals(2, verification.verifiedRecords)
        assertEquals(setOf("corr-a", "corr-b"), reopened.events().map { it.correlationId }.toSet())
        assertEquals(2, file.readLines().size)
    }

    @Test
    fun anEditedRecordBreaksTheChainInsteadOfReadingClean() = withDirectory { directory ->
        val file = File(directory, "events.log")
        val sink = PersistentAuditSink(file = file, clock = clock)
        sink.write(event("plugin.invoke"))
        sink.write(event("plugin.invoke", correlationId = "corr-b"))
        sink.write(event("plugin.invoke", correlationId = "corr-c"))
        assertEquals(AuditChainStatus.INTACT, sink.verifyChain().status)

        val lines = file.readLines().toMutableList()
        val fields = lines[1].split('|').toMutableList()
        // 攻击者只改内容、不重算摘要：找到承载 action 的那一段并替换。
        val index = fields.indexOfFirst { field ->
            runCatching { String(Base64.getUrlDecoder().decode(field), StandardCharsets.UTF_8) }
                .getOrNull() == "plugin.invoke"
        }
        assertTrue("探针必须真的改到文件", index > 0)
        fields[index] = base64("plugin.tampered")
        lines[1] = fields.joinToString("|")
        file.writeText(lines.joinToString("\n") + "\n")

        val reloaded = PersistentAuditSink(file = file, clock = clock).verifyChain()

        assertEquals(AuditChainStatus.BROKEN, reloaded.status)
        assertEquals(2, reloaded.firstBrokenRecord)
        assertTrue("原因必须说清是摘要不符：${reloaded.reason}", reloaded.reason!!.contains("hash"))
    }

    @Test
    fun anEditedRecordWhoseDigestWasRecomputedWithoutRelinkingBreaksTheChain() = withDirectory { directory ->
        val file = File(directory, "events.log")
        val sink = PersistentAuditSink(file = file, clock = clock)
        sink.write(event("plugin.invoke"))
        sink.write(event("plugin.invoke", correlationId = "corr-b"))
        sink.write(event("plugin.invoke", correlationId = "corr-c"))

        val lines = file.readLines().toMutableList()
        val second = AuditLineCodec.decode(lines[1]) as AuditLineCodec.Decoded.Chained
        // 更聪明的攻击者重算了该行摘要，但没有（也无法）更新下一条记录里已有的前序摘要。
        lines[1] = AuditLineCodec.encodeChained(
            second.event.copy(action = "plugin.tampered"),
            second.previousHash,
        )
        file.writeText(lines.joinToString("\n") + "\n")

        val verification = PersistentAuditSink(file = file, clock = clock).verifyChain()

        assertEquals(AuditChainStatus.BROKEN, verification.status)
        assertEquals(3, verification.firstBrokenRecord)
        assertTrue("原因必须指出前序摘要不匹配：${verification.reason}", verification.reason!!.contains("chain"))
    }

    @Test
    fun aTruncatedOrForeignLineIsReportedRatherThanSkipped() = withDirectory { directory ->
        val file = File(directory, "events.log")
        val sink = PersistentAuditSink(file = file, clock = clock)
        sink.write(event("plugin.invoke"))
        file.appendText("v2|half-written-no-digest\n")

        val verification = PersistentAuditSink(file = file, clock = clock).verifyChain()

        assertEquals(AuditChainStatus.BROKEN, verification.status)
        assertEquals(2, verification.firstBrokenRecord)
        assertEquals(1, verification.unreadableRecords)
        assertTrue(
            "无法解析的记录必须如实标注：${verification.reason}",
            verification.reason!!.contains("unreadable"),
        )
    }

    @Test
    fun legacyRecordsStayReadableAndAreReportedAsUnchained() = withDirectory { directory ->
        val file = File(directory, "events.log")
        // 旧格式（v1）行：升级前的文件必须还能读，但它的完整性无法被链证明。
        val legacy = "v1|" + listOf(
            "platform",
            "account-a",
            "pairing-a",
            "plugin.invoke",
            "ALLOWED",
            "corr-legacy",
            "2026-09-03T00:00:00.000Z",
        ).joinToString("|") { base64(it) }
        file.writeText(legacy + "\n")
        PersistentAuditSink(file = file, clock = clock).write(event("plugin.invoke", "corr-new"))

        val sink = PersistentAuditSink(file = file, clock = clock)
        val events = sink.events()
        val verification = sink.verifyChain()

        assertEquals(
            "旧行必须按旧格式读出，不能因为格式升级就丢掉历史记录",
            listOf("corr-legacy", "corr-new"),
            events.map { it.correlationId },
        )
        assertEquals(AuditChainStatus.LEGACY_UNCHAINED, verification.status)
        assertEquals(1, verification.legacyRecords)
        assertEquals(1, verification.verifiedRecords)
    }

    @Test
    fun anUnchainedRecordInsideTheChainedRunIsABreak() = withDirectory { directory ->
        val file = File(directory, "events.log")
        val sink = PersistentAuditSink(file = file, clock = clock)
        sink.write(event("plugin.invoke"))
        sink.write(event("plugin.invoke", correlationId = "corr-b"))

        val lines = file.readLines().toMutableList()
        // 把一条链上的记录替换成不参与链的旧格式行：链断了，且不能算「历史遗留」。
        lines[1] = "v1|" + listOf(
            "platform", "account-a", "pairing-a", "plugin.invoke", "ALLOWED", "corr-b",
            "2026-09-03T00:00:00.000Z",
        ).joinToString("|") { base64(it) }
        file.writeText(lines.joinToString("\n") + "\n")

        val verification = PersistentAuditSink(file = file, clock = clock).verifyChain()

        assertEquals(AuditChainStatus.BROKEN, verification.status)
        assertEquals(2, verification.firstBrokenRecord)
    }
}
