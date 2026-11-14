package com.openandroidintelligence.sms

import android.app.Activity
import com.openandroidintelligence.capability.SmsSendRequest
import com.openandroidintelligence.capability.SmsSendStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSmsSendProviderTest {
    @Test
    fun segmented_send_aggregates_all_system_sent_results() = runBlocking {
        val sent = mutableListOf<List<String>>()
        val provider = AndroidSmsSendProvider(portFactory = {
            object : SmsSendPort {
                override fun divideMessage(text: String) = listOf("a", "b", "c")
                override fun sendMultipartTextMessage(recipient: String, parts: List<String>, beforeSystemEffect: SmsSendBeforeSystemEffect, callback: SmsSegmentCallback): AutoCloseable {
                    beforeSystemEffect.check()
                    sent += parts
                    parts.indices.forEach { callback.onResult(it, Activity.RESULT_OK) }
                    return AutoCloseable { }
                }
            }
        })

        val result = provider.send(SmsSendRequest(2, "+15550001", "secret")) { }

        assertEquals(SmsSendStatus.SENT, result.status)
        assertEquals(3, result.segmentCount)
        assertEquals(3, result.sentSegments)
        assertEquals(listOf("a", "b", "c"), sent.single())
    }

    @Test
    fun missing_callback_is_outcome_unknown_and_does_not_retry() = runBlocking {
        var sends = 0
        var closed = 0
        val provider = AndroidSmsSendProvider(portFactory = {
            object : SmsSendPort {
                override fun divideMessage(text: String) = listOf("a", "b")
                override fun sendMultipartTextMessage(recipient: String, parts: List<String>, beforeSystemEffect: SmsSendBeforeSystemEffect, callback: SmsSegmentCallback): AutoCloseable {
                    beforeSystemEffect.check()
                    sends++
                    callback.onResult(0, Activity.RESULT_OK)
                    return AutoCloseable { closed++ }
                }
            }
        }, timeoutMs = 20)

        val result = provider.send(SmsSendRequest(0, "+15550002", "secret")) { }

        assertEquals(SmsSendStatus.OUTCOME_UNKNOWN, result.status)
        assertEquals("OUTCOME_UNKNOWN", result.failureCode)
        assertEquals(1, sends)
        assertEquals(1, result.sentSegments)
        assertEquals(1, closed)
    }

    @Test fun mixed_segment_results_are_not_reported_as_whole_message_sent() = runBlocking {
        val provider = AndroidSmsSendProvider { object : SmsSendPort {
            override fun divideMessage(text: String) = listOf("a", "b")
            override fun sendMultipartTextMessage(recipient: String, parts: List<String>, beforeSystemEffect: SmsSendBeforeSystemEffect,
                callback: SmsSegmentCallback): AutoCloseable {
                beforeSystemEffect.check()
                callback.onResult(0, Activity.RESULT_OK)
                callback.onResult(0, 999)
                callback.onResult(1, 3)
                return AutoCloseable { }
            }
        } }
        val result = provider.send(SmsSendRequest(2, "+15550004", "secret")) { }
        assertEquals(SmsSendStatus.FAILED, result.status)
        assertEquals(1, result.sentSegments)
        assertEquals(1, result.failedSegments)
    }

    @Test fun final_fence_denial_never_calls_the_system_and_cancel_after_dispatch_is_unknown() = runBlocking {
        var effects = 0
        var closed = 0
        val started = CompletableDeferred<Unit>()
        val provider = AndroidSmsSendProvider { object : SmsSendPort {
            override fun divideMessage(text: String) = listOf("a")
            override fun sendMultipartTextMessage(recipient: String, parts: List<String>, beforeSystemEffect: SmsSendBeforeSystemEffect,
                callback: SmsSegmentCallback): AutoCloseable {
                beforeSystemEffect.check(); effects++; started.complete(Unit)
                return AutoCloseable { closed++ }
            }
        } }
        assertTrue(runCatching { provider.send(SmsSendRequest(2, "+15550005", "secret")) {
            throw IllegalStateException("本机急停")
        } }.isFailure)
        assertEquals(0, effects)
        var result: com.openandroidintelligence.capability.SmsSendResult? = null
        val sending = async { provider.send(SmsSendRequest(2, "+15550005", "secret")) { }.also { result = it } }
        started.await(); sending.cancel(); runCatching { sending.await() }
        assertEquals(SmsSendStatus.OUTCOME_UNKNOWN, result?.status)
        assertEquals(1, effects); assertEquals(1, closed)
    }

    @Test
    fun final_fence_is_checked_at_the_system_dispatch_boundary() = runBlocking {
        var checks = 0
        val provider = AndroidSmsSendProvider(portFactory = {
            object : SmsSendPort {
                override fun divideMessage(text: String) = listOf("a")
                override fun sendMultipartTextMessage(recipient: String, parts: List<String>, beforeSystemEffect: SmsSendBeforeSystemEffect, callback: SmsSegmentCallback): AutoCloseable {
                    beforeSystemEffect.check()
                    callback.onResult(0, Activity.RESULT_OK)
                    return AutoCloseable { }
                }
            }
        })
        val result = provider.send(SmsSendRequest(1, "+15550003", "secret")) {
            checks++
        }
        assertEquals(1, checks)
        assertEquals(SmsSendStatus.SENT, result.status)
    }

    @Test
    fun request_requires_explicit_nonnegative_subscription_and_redacts_body_in_string() {
        val failure = runCatching { SmsSendRequest(-1, "+1", "secret") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(SmsSendRequest(3, "+1", "secret").toString().contains("secret").not())
    }
}
