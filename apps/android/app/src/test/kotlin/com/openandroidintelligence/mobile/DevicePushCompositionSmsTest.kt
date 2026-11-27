package com.openandroidintelligence.mobile

import com.openandroidintelligence.capability.*
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.mobile.push.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DevicePushCompositionSmsTest {
    private val endpoint = PushEndpoint("core", "account", "endpoint", 1)
    private val now = System.currentTimeMillis()
    private val scope = SmsPushScope(senderAddresses = setOf("retained-phone-draft"), allSenders = false,
        includeContent = false, mode = SmsScopeMode.KEYWORDS, keywords = setOf("code", "parcel"))
    private fun runtime() = DevicePushRuntime(
        EncryptedPushStateStore("composition-sms", InMemoryOutboxPersistence(), SecretKeySpec(ByteArray(32) { 3 }, "AES")),
        PushSystemAccess { true },
    ).apply {
        configure(PushConfiguration(sms = SmsPushSettings(true, scope, setOf(endpoint))))
        onLoginStateChanged(true)
    }

    @Test fun rejectedNativePageRequestsAnotherBoundedCycleAndNextMatchIsCapturedWithoutNewOsEvent() = runBlocking {
        val runtime = runtime()
        val queries = mutableListOf<DevicePushSmsQuery>()
        val source = object : TestSources() {
            override fun captureSms(query: DevicePushSmsQuery, isCurrent: () -> Boolean,
                advanceSkippedCursor: (Long, Long) -> Boolean, enqueueDurably: (DevicePushSms) -> Boolean): Boolean {
                queries += query
                assertTrue(isCurrent())
                assertFalse(query.allSenders)
                assertFalse(query.includeContent)
                assertTrue(query.senders.isEmpty())
                assertEquals(setOf("code", "parcel"), query.keywords)
                if (query.afterId == null) {
                    for (id in 1L..query.maxRecords.toLong()) assertTrue(advanceSkippedCursor(id, now))
                    return true
                }
                assertEquals(128L, query.afterId)
                assertTrue(enqueueDurably(DevicePushSms(129, now, "sender", null, null, null, setOf("code"))))
                return false
            }
        }
        val more = captureSmsPage(runtime, source) { true }
        assertTrue(more)
        assertEquals(0, runtime.snapshot().pendingDeliveries)
        assertFalse(pushCycleComplete(runtime.snapshot(), more))
        val last = captureSmsPage(runtime, source) { true }
        assertFalse(last)
        assertEquals(2, queries.size)
        assertEquals(PushSmsCursor(129, now), runtime.smsCursor())
        assertEquals(1, runtime.snapshot().pendingDeliveries)
        runtime.deliver(TestPushTransport())
        assertTrue(pushCycleComplete(runtime.snapshot(), last))
    }

    @Test fun changedScopeInvalidatesBothSkippedCursorAndEnqueueCallbacksBeforeDisclosure() {
        val runtime = runtime()
        val source = object : TestSources() {
            override fun captureSms(query: DevicePushSmsQuery, isCurrent: () -> Boolean,
                advanceSkippedCursor: (Long, Long) -> Boolean, enqueueDurably: (DevicePushSms) -> Boolean): Boolean {
                assertTrue(isCurrent())
                val configuration = runtime.snapshot().configuration
                runtime.configure(configuration.copy(sms = configuration.sms.copy(scope = SmsPushScope())))
                assertFalse(isCurrent())
                assertFalse(advanceSkippedCursor(128, now))
                // New ALL scope could otherwise accept a callback captured under the old scope.
                assertFalse(enqueueDurably(DevicePushSms(129, now, "sender", "code secret body", null, null)))
                return false
            }
        }
        captureSmsPage(runtime, source) { true }
        assertEquals(0, runtime.snapshot().queuedEvents)
        assertNull(runtime.smsCursor())
    }

    @Test fun legacyThreeArgumentTrailingLambdaStillMeansEnqueueAndDoesNotAcknowledgeSkippedRows() {
        var enqueued = 0
        val source = object : TestSources() {
            override fun captureSms(query: DevicePushSmsQuery, isCurrent: () -> Boolean,
                advanceSkippedCursor: (Long, Long) -> Boolean, enqueueDurably: (DevicePushSms) -> Boolean): Boolean {
                assertFalse(advanceSkippedCursor(1, now))
                return enqueueDurably(DevicePushSms(2, now, "sender", null, null, null))
            }
        }
        val query = DevicePushSmsQuery(0, null, null, emptySet(), true, false)
        assertTrue(source.captureSms(query, { true }) { enqueued++; true })
        assertEquals(1, enqueued)
    }

    private abstract class TestSources : DevicePushSources {
        override fun installNotificationObserver(observer: DevicePushNotificationObserver) = Unit
        override fun clearNotificationObserver(observer: DevicePushNotificationObserver) = Unit
        override fun setNotificationCollectionAccess(allowed: () -> Boolean) = Unit
        override fun captureActiveNotifications() = Unit
    }
}
