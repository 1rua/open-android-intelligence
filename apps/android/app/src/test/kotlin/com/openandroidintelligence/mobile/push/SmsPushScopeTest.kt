package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.kernel.SmsScopeMode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmsPushScopeTest {
    private val endpoint = PushEndpoint("core", "account", "endpoint", 3)
    private val key = SecretKeySpec(ByteArray(32) { 11 }, "AES")
    private val clock = TestPushClock()
    private fun store(memory: InMemoryOutboxPersistence) = EncryptedPushStateStore("sms-scopes", memory, key)
    private fun configuration(scope: SmsPushScope) = PushConfiguration(sms = SmsPushSettings(true, scope, setOf(endpoint)))
    private fun runtime(scope: SmsPushScope, memory: InMemoryOutboxPersistence = InMemoryOutboxPersistence()) =
        DevicePushRuntime(store(memory), PushSystemAccess { true }, clock).apply {
            configure(configuration(scope)); onLoginStateChanged(true)
        }
    private fun sms(id: Long, sender: String = "sender", body: String? = "code secret body") =
        PushSms(id, clock.epoch, sender, body)
    private fun keywords(values: Set<String>, content: Boolean = true) = SmsPushScope(
        allSenders = false, includeContent = content, mode = SmsScopeMode.KEYWORDS, keywords = values)

    @Test fun newDefaultAndLegacyConstructorsRemainIndependentAndExact() {
        assertEquals(SmsScopeMode.ALL, SmsPushScope().mode)
        assertTrue(SmsPushScope().allSenders)
        assertFalse(PushConfiguration().sms.enabled)
        val old = runtime(SmsPushScope(setOf("BANK-ID", "+86 13800000000")))
        assertEquals(PushCaptureStatus.ENQUEUED, old.onSms(sms(1, "BANK-ID")).status)
        assertEquals(PushCaptureStatus.OUT_OF_SCOPE, old.onSms(sms(2, "bank-id")).status)
        assertEquals(PushCaptureStatus.OUT_OF_SCOPE, old.onSms(sms(3, "+8613800000000")).status)
        assertEquals(SmsScopeMode.PHONE_NUMBERS, SmsPushScope(allSenders = false).mode)
        val empty = runtime(SmsPushScope(allSenders = false))
        assertFalse(empty.isCollectionAllowed(PushKind.SMS))
        assertEquals(PushCaptureStatus.OUT_OF_SCOPE, empty.onSms(sms(4)).status)
    }

    @Test fun keywordOrIgnoreCaseAndSqlSpecialCharactersAreLiteral() {
        val runtime = runtime(keywords(setOf("absent", "CAFÉ", "%", "_", "'")))
        for ((index, text) in listOf("café", "10%", "a_b", "o'neal").withIndex())
            assertEquals(text, PushCaptureStatus.ENQUEUED, runtime.onSms(sms(index + 1L, body = text)).status)
        assertEquals(PushCaptureStatus.OUT_OF_SCOPE, runtime.onSms(sms(10, body = "ordinary text")).status)
        runtime.configure(configuration(keywords(emptySet())))
        assertFalse(runtime.isCollectionAllowed(PushKind.SMS))
        assertEquals(0, runtime.snapshot().queuedEvents)
        assertEquals(4, runtime.snapshot().pendingRevocations)
    }

    @Test fun metadataProofSurvivesRestartButNeverEntersDeliveredPayload() = runBlocking {
        val memory = InMemoryOutboxPersistence()
        val original = runtime(keywords(setOf("CODE", "unused"), false), memory)
        val captured = original.onSms(sms(1))
        val saved = store(memory).load().events.single()
        assertEquals(setOf("CODE"), saved.smsMatchedKeywords)
        assertFalse(saved.event.payloadJson.contains("secret body"))
        assertFalse(saved.event.payloadJson.contains("matchedKeywords"))
        assertFalse(saved.event.payloadJson.contains("CODE"))
        val restarted = DevicePushRuntime(store(memory), PushSystemAccess { true }, clock)
        restarted.onLoginStateChanged(true)
        val transport = TestPushTransport { PushReceiptState.ACCEPTED }
        assertEquals(1, restarted.deliver(transport).accepted)
        assertTrue(transport.deliveries.all { it.event.eventId == captured.eventId && it.event.payloadJson == saved.event.payloadJson })
        assertEquals("delivery", store(memory).load().events.single().targets[endpoint])
        val cursor = restarted.smsCursor()
        restarted.configure(configuration(keywords(setOf("code", "new-condition"), false)))
        assertEquals(1, restarted.snapshot().queuedEvents)
        assertEquals(cursor, restarted.smsCursor())
        restarted.configure(configuration(keywords(setOf("new-condition"), false)))
        assertEquals(0, restarted.snapshot().queuedEvents)
        assertEquals(cursor, restarted.smsCursor())
        assertEquals(setOf(captured.eventId), restarted.pendingRevocations().single().eventIds)
    }

    @Test fun legacyMetadataWithoutProofIsRevokedWhenChangingToKeywords() {
        val runtime = runtime(SmsPushScope(includeContent = false))
        val first = runtime.onSms(sms(1))
        assertEquals(1, runtime.snapshot().queuedEvents)
        val cursor = runtime.smsCursor()
        runtime.configure(configuration(keywords(setOf("code"), false)))
        assertEquals(0, runtime.snapshot().queuedEvents)
        assertEquals(setOf(first.eventId), runtime.pendingRevocations().single().eventIds)
        assertEquals(cursor, runtime.smsCursor())
    }

    @Test fun phoneAndKeywordChangesKeepOnlyProvableUnackedRowsAndExactReceiptsAndDeadlines() = runBlocking {
        val memory = InMemoryOutboxPersistence()
        val runtime = runtime(SmsPushScope(), memory)
        val a = runtime.onSms(sms(1, "A", "code apple"))
        val b = runtime.onSms(sms(2, "B", "code pear"))
        runtime.deliver(TestPushTransport { PushReceiptState.ACCEPTED })
        val before = store(memory).load()
        val aBefore = before.events.first { it.event.eventId == a.eventId }
        runtime.configure(configuration(SmsPushScope(setOf("A"))))
        assertEquals(1, runtime.snapshot().queuedEvents)
        assertEquals(aBefore, store(memory).load().events.single())
        assertEquals(setOf(b.eventId), runtime.pendingRevocations().single().eventIds)
        assertEquals(before.smsCursor, runtime.smsCursor())
        runtime.configure(configuration(keywords(setOf("APPLE"))))
        assertEquals(aBefore, store(memory).load().events.single())
        runtime.configure(configuration(keywords(setOf("pear"))))
        assertEquals(0, runtime.snapshot().queuedEvents)
        assertEquals(setOf(a.eventId, b.eventId), runtime.pendingRevocations().single().eventIds)
        assertEquals(before.seen, store(memory).load().seen)
    }

    @Test fun resendAfterRestartAndBeforeEachHttpAttemptRevalidatesScope() = runBlocking {
        val memory = InMemoryOutboxPersistence()
        val runtime = runtime(keywords(setOf("code")), memory)
        runtime.onSms(sms(1))
        runtime.deliver(TestPushTransport { PushReceiptState.ACCEPTED })
        val restarted = DevicePushRuntime(store(memory), PushSystemAccess { true }, clock)
        restarted.onLoginStateChanged(true)
        val transport = TestPushTransport()
        transport.beforeVerify = { restarted.configure(configuration(keywords(setOf("absent")))) }
        restarted.deliver(transport)
        assertTrue(transport.deliveries.isEmpty())
        assertEquals(0, restarted.snapshot().queuedEvents)
        assertEquals(1, restarted.snapshot().pendingRevocations)
    }

    @Test fun staleConfigurationCompareIsAtomicButQueueAcksAndCursorUpdatesDoNotCauseConflicts() = runBlocking {
        val memory = InMemoryOutboxPersistence()
        val runtime = runtime(SmsPushScope(), memory)
        val original = runtime.snapshot().configuration
        runtime.onSms(sms(1))
        runtime.deliver(TestPushTransport { PushReceiptState.ACCEPTED })
        val queued = store(memory).load()
        runtime.configure(original.copy(retentionHours = 96), expectedConfiguration = original)
        assertEquals(queued.events, store(memory).load().events)
        val revision = runtime.snapshot().revision
        val conflict = assertThrows(IllegalStateException::class.java) {
            runtime.configure(original.copy(retentionHours = 1), expectedConfiguration = original)
        }
        assertEquals("PUSH_CONFIGURATION_CHANGED", conflict.message)
        assertEquals(revision, runtime.snapshot().revision)
        assertEquals(96, runtime.snapshot().configuration.retentionHours)
        val expected = runtime.snapshot().configuration
        runtime.deliver(TestPushTransport())
        assertEquals(0, runtime.snapshot().queuedEvents)
        runtime.configure(expected.copy(retentionHours = 24), expectedConfiguration = expected)
        assertEquals(24, runtime.snapshot().configuration.retentionHours)
    }

    @Test fun twoEditorsWithTheSameBaseCannotOverwriteEachOther() {
        val runtime = runtime(SmsPushScope())
        val base = runtime.snapshot().configuration
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = listOf(24, 48).map { hours -> pool.submit(Callable {
                check(start.await(2, TimeUnit.SECONDS))
                runCatching { runtime.configure(base.copy(retentionHours = hours), expectedConfiguration = base); hours }
            }) }
            start.countDown()
            val values = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, values.count { it.isSuccess })
            assertEquals("PUSH_CONFIGURATION_CHANGED", values.single { it.isFailure }.exceptionOrNull()!!.message)
            assertEquals(values.single { it.isSuccess }.getOrThrow(), runtime.snapshot().configuration.retentionHours)
        } finally { pool.shutdownNow() }
    }

    @Test fun rejectedRowsAdvanceOnlyTheCurrentScopeCursorAndNeverRewindItOnEdits() {
        val scope = keywords(setOf("code"), false)
        val runtime = runtime(scope)
        assertTrue(runtime.advanceSmsCursor(PushSmsCursor(128, clock.epoch), scope))
        val cursor = runtime.smsCursor()
        val changed = keywords(setOf("other"), false)
        runtime.configure(configuration(changed))
        assertEquals(cursor, runtime.smsCursor())
        assertFalse(runtime.advanceSmsCursor(PushSmsCursor(129, clock.epoch), scope))
        assertEquals(PushCaptureStatus.OUT_OF_SCOPE,
            runtime.onSms(sms(129, body = "other"), expectedScope = scope).status)
        assertEquals(0, runtime.snapshot().queuedEvents)
        assertEquals(cursor, runtime.smsCursor())
        assertTrue(runtime.advanceSmsCursor(PushSmsCursor(129, clock.epoch), changed))
        runtime.onLoginStateChanged(false)
        assertFalse(runtime.advanceSmsCursor(PushSmsCursor(130, clock.epoch), changed))
    }
}
