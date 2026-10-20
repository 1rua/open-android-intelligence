package com.openandroidintelligence.sync

import com.openandroidintelligence.core.model.BridgeIdentity
import com.openandroidintelligence.core.model.BridgeSession
import com.openandroidintelligence.core.model.CapabilityDurableEvent
import com.openandroidintelligence.core.model.CapabilityOutbox
import com.openandroidintelligence.core.model.CapabilityOutboxAckRejected
import com.openandroidintelligence.core.model.EnrollmentTicket
import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.PolicyAttestation
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityOutboxDispatcherTest {
    @Test
    fun acknowledges_expected_events_in_insertion_order() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport()

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

        assertEquals(CapabilityDispatchResult(2, 0, null), result)
        assertEquals(listOf("sms:1", "sms:2"), transport.sentEventIds)
        assertTrue(outbox.events.isEmpty())
    }

    @Test
    fun retains_mismatched_capability_without_egress_and_delivers_later_expected_event() {
        val other = event("calls:1", capability = "calls")
        val expected = event("sms:2")
        val outbox = RecordingOutbox(events = listOf(other, expected))
        val transport = RecordingTransport()
        val gated = mutableListOf<String>()

        val result = runSuspend {
            dispatcher(outbox, transport, CapabilityEventEgressGate { gated += it.eventId; true }).dispatchPending()
        }

        assertEquals(CapabilityDispatchResult(1, 1, CapabilityDispatchFailure.UNSUPPORTED_CAPABILITY), result)
        assertEquals(listOf("sms:2"), transport.sentEventIds)
        assertEquals(listOf("sms:2", "sms:2", "sms:2"), gated)
        assertEquals(listOf("calls:1"), outbox.events.map { it.eventId })
    }

    @Test
    fun blocked_expected_event_prevents_later_expected_event_from_overtaking_it() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport()

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending(setOf("sms:1")) }

        assertEquals(CapabilityDispatchResult(0, 1, null), result)
        assertTrue(transport.sentEventIds.isEmpty())
        assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun blocked_expected_event_after_a_mismatch_still_stops_later_expected_delivery() {
        val outbox = RecordingOutbox(events = listOf(event("calls:1", "calls"), event("sms:2"), event("sms:3")))
        val transport = RecordingTransport()

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending(setOf("sms:2")) }

        assertEquals(CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.UNSUPPORTED_CAPABILITY), result)
        assertTrue(transport.sentEventIds.isEmpty())
    }

    @Test
    fun policy_revoke_before_open_retains_expected_event_without_opening_transport() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1")))
        val transport = RecordingTransport()

        val result = runSuspend { dispatcher(outbox, transport, CapabilityEventEgressGate { false }).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 1, CapabilityDispatchFailure.POLICY_REVOKED), result)
        assertEquals(0, transport.opens)
        assertEquals(emptyList<TransportCloseReason>(), transport.closeReasons)
    }

    @Test
    fun policy_revoke_during_open_closes_session_and_retains_expected_event() {
        var allowed = true
        val outbox = RecordingOutbox(events = listOf(event("sms:1")))
        val transport = RecordingTransport(onOpen = { allowed = false })

        val result = runSuspend { dispatcher(outbox, transport, CapabilityEventEgressGate { allowed }).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 1, CapabilityDispatchFailure.POLICY_REVOKED), result)
        assertTrue(transport.sentEventIds.isEmpty())
        assertEquals(listOf(TransportCloseReason.POLICY_REVOKED), transport.closeReasons)
    }

    @Test
    fun policy_revoke_before_send_closes_session_and_retains_expected_event() {
        var gates = 0
        val outbox = RecordingOutbox(events = listOf(event("sms:1")))
        val transport = RecordingTransport()

        val result = runSuspend {
            dispatcher(outbox, transport, CapabilityEventEgressGate { ++gates < 3 }).dispatchPending()
        }

        assertEquals(CapabilityDispatchResult(0, 1, CapabilityDispatchFailure.POLICY_REVOKED), result)
        assertTrue(transport.sentEventIds.isEmpty())
        assertEquals(3, gates)
        assertEquals(listOf(TransportCloseReason.POLICY_REVOKED), transport.closeReasons)
    }

    @Test
    fun success_checks_egress_gate_exactly_three_times() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1")))
        val transport = RecordingTransport()
        val gates = mutableListOf<String>()

        val result = runSuspend {
            dispatcher(outbox, transport, CapabilityEventEgressGate { gates += it.eventId; true }).dispatchPending()
        }

        assertEquals(CapabilityDispatchResult(1, 0, null), result)
        assertEquals(listOf("sms:1", "sms:1", "sms:1"), gates)
    }

    @Test
    fun retries_check_pre_open_gate_per_attempt_and_second_success_has_its_own_three_fences() {
        val trace = mutableListOf<String>()
        val outbox = RecordingOutbox(events = listOf(event("sms:1")))
        val transport = RecordingTransport(
            openFailures = mutableListOf(IllegalStateException("first open failed")),
            trace = trace,
        )

        val result = runSuspend {
            dispatcher(
                outbox,
                transport,
                egressGate = CapabilityEventEgressGate { trace += "gate:${it.eventId}"; true },
                maxAttempts = 2,
            ).dispatchPending()
        }

        assertEquals(CapabilityDispatchResult(1, 0, CapabilityDispatchFailure.TRANSPORT_FAILURE), result)
        assertEquals(
            listOf(
                "gate:sms:1", "open", "close:FAILURE",
                "gate:sms:1", "open", "gate:sms:1", "gate:sms:1", "send:sms:1", "close:PROCESS_STOPPED",
            ),
            trace,
        )
    }

    @Test
    fun retry_pre_open_revoke_does_not_open_again_or_overtake_later_expected_event() {
        var allowed = true
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport(
            openFailures = mutableListOf(IllegalStateException("first open failed")),
            onOpenFailure = { allowed = false },
        )
        val gates = mutableListOf<String>()

        val result = runSuspend {
            dispatcher(
                outbox,
                transport,
                egressGate = CapabilityEventEgressGate { gates += it.eventId; allowed },
                maxAttempts = 2,
            ).dispatchPending()
        }

        assertEquals(CapabilityDispatchResult(0, 1, CapabilityDispatchFailure.TRANSPORT_FAILURE), result)
        assertEquals(listOf("sms:1", "sms:1"), gates)
        assertEquals(1, transport.opens)
        assertTrue(transport.sentEventIds.isEmpty())
        assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun recoverable_open_failure_cleanup_cancellation_stops_retry_and_preserves_known_pending_events() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport(
            openFailures = mutableListOf(IllegalStateException("open failed")),
            closeFailure = CancellationException("cleanup cancelled"),
        )

        val result = runSuspend { dispatcher(outbox, transport, maxAttempts = 2).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(1, transport.opens)
        assertTrue(transport.sentEventIds.isEmpty())
        assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun recoverable_send_and_receive_cleanup_cancellation_stops_retry_and_later_egress() {
        for (failure in listOf(FailurePoint.SEND, FailurePoint.RECEIVE)) {
            val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
            val transport = RecordingTransport(
                failurePoint = failure,
                closeFailure = CancellationException("cleanup cancelled"),
            )

            val result = runSuspend { dispatcher(outbox, transport, maxAttempts = 2).dispatchPending() }

            assertEquals("$failure result", CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
            assertEquals("$failure opens", 1, transport.opens)
            assertTrue("$failure must not send later event", transport.sentEventIds.none { it == "sms:2" })
            assertEquals("$failure durable events", listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
        }
    }

    @Test
    fun ack_rejection_and_recoverable_outbox_failure_cleanup_cancellation_stop_retry() {
        for (outbox in listOf(
            RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")), rejectAcks = true),
            RecordingOutbox(
                events = listOf(event("sms:1"), event("sms:2")),
                acknowledgeFailure = IllegalStateException("outbox unavailable"),
            ),
        )) {
            val transport = RecordingTransport(closeFailure = CancellationException("cleanup cancelled"))

            val result = runSuspend { dispatcher(outbox, transport, maxAttempts = 2).dispatchPending() }

            assertEquals(CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
            assertEquals(1, transport.opens)
            assertEquals(listOf("sms:1"), transport.sentEventIds)
            assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
        }
    }

    @Test
    fun post_open_and_pre_send_policy_cleanup_cancellation_is_distinct_and_stops_egress() {
        for (deniedGate in listOf(2, 3)) {
            var gates = 0
            val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
            val transport = RecordingTransport(closeFailure = CancellationException("cleanup cancelled"))

            val result = runSuspend {
                dispatcher(
                    outbox,
                    transport,
                    egressGate = CapabilityEventEgressGate { ++gates < deniedGate },
                ).dispatchPending()
            }

            assertEquals("gate $deniedGate result", CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
            assertEquals("gate $deniedGate opens", 1, transport.opens)
            assertTrue("gate $deniedGate must not send", transport.sentEventIds.isEmpty())
            assertEquals("gate $deniedGate durable events", listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
        }
    }

    @Test
    fun pairing_open_send_receive_and_ack_failures_are_retained_and_do_not_overtake_later_expected_events() {
        val pairingOutbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val pairing = runSuspend {
            dispatcher(pairingOutbox, RecordingTransport(), binding = CapabilityPairedBridgeBindingSource { null }).dispatchPending()
        }
        assertEquals(CapabilityDispatchResult(0, 1, CapabilityDispatchFailure.PAIRING_UNAVAILABLE), pairing)

        for (failure in listOf(FailurePoint.OPEN, FailurePoint.SEND, FailurePoint.RECEIVE, FailurePoint.ACK)) {
            val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")), rejectAcks = failure == FailurePoint.ACK)
            val transport = RecordingTransport(failurePoint = failure)

            val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

            val expectedFailure = if (failure == FailurePoint.ACK) {
                CapabilityDispatchFailure.ACK_REJECTED
            } else {
                CapabilityDispatchFailure.TRANSPORT_FAILURE
            }
            assertEquals(CapabilityDispatchResult(0, 1, expectedFailure), result)
            assertTrue("$failure must not overtake", transport.sentEventIds.none { it == "sms:2" })
        }
    }

    @Test
    fun outbox_recovery_failure_is_redacted_from_result() {
        val outbox = RecordingOutbox(recoveryFailure = IllegalStateException("sensitive persistence detail"))

        val result = runSuspend { dispatcher(outbox, RecordingTransport()).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 0, CapabilityDispatchFailure.OUTBOX_FAILURE), result)
        assertTrue(result.toString().contains("sensitive persistence detail").not())
    }

    @Test
    fun fatal_jvm_errors_are_not_converted_into_dispatch_results() {
        val outbox = RecordingOutbox(recoveryFatal = AssertionError("must propagate"))

        try {
            runSuspend { dispatcher(outbox, RecordingTransport()).dispatchPending() }
            throw AssertionError("fatal error must propagate")
        } catch (failure: AssertionError) {
            assertEquals("must propagate", failure.message)
        }
    }

    @Test
    fun cancellation_closes_session_and_retains_all_unacknowledged_events() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport(failurePoint = FailurePoint.CANCEL_SEND)

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(listOf(TransportCloseReason.PROCESS_STOPPED), transport.closeReasons)
        assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun recovery_cancellation_returns_a_typed_restricted_result_without_claiming_an_ack() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1")), recoveryCancellation = true)

        val result = runSuspend { dispatcher(outbox, RecordingTransport()).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 0, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(listOf("sms:1"), outbox.events.map { it.eventId })
    }

    @Test
    fun open_cancellation_performs_cleanup_and_retains_pending_events() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport(failurePoint = FailurePoint.CANCEL_OPEN)

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(listOf(TransportCloseReason.PROCESS_STOPPED), transport.closeReasons)
        assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun receive_cancellation_performs_cleanup_and_retains_pending_events() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport(
            failurePoint = FailurePoint.CANCEL_RECEIVE,
            closeFailure = CancellationException("close cancelled"),
        )

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(listOf(TransportCloseReason.PROCESS_STOPPED), transport.closeReasons)
        assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun acknowledge_cancellation_before_mutation_performs_cleanup_and_retains_pending_events() {
        val outbox = RecordingOutbox(
            events = listOf(event("sms:1"), event("sms:2")),
            acknowledgeCancellation = true,
        )
        val transport = RecordingTransport(closeFailure = CancellationException("close cancelled"))

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(listOf(TransportCloseReason.PROCESS_STOPPED), transport.closeReasons)
        assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun post_ack_close_cancellation_preserves_the_removed_event_and_reports_cancellation() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1")))
        val transport = RecordingTransport(closeFailure = CancellationException("close cancelled"))

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

        assertEquals(CapabilityDispatchResult(1, 0, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(listOf(TransportCloseReason.PROCESS_STOPPED), transport.closeReasons)
        assertTrue(outbox.events.isEmpty())
    }

    @Test
    fun post_ack_close_cancellation_stops_before_later_expected_event_without_retain_lie() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport(closeFailure = CancellationException("close cancelled"))

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

        assertEquals(CapabilityDispatchResult(1, 1, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(listOf("sms:1"), transport.sentEventIds)
        assertEquals(listOf("sms:2"), outbox.events.map { it.eventId })
        assertEquals(listOf(TransportCloseReason.PROCESS_STOPPED), transport.closeReasons)
    }

    @Test
    fun post_ack_cleanup_observes_caller_job_cancellation_after_non_cancellable_close() {
        val job = Job()
        var cleanupFinished = false
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport(
            onClose = {
                job.cancel(CancellationException("caller cancelled during close"))
                yield()
                cleanupFinished = true
            },
        )

        var boundaryCancellation: CancellationException? = null
        try {
            runBlocking {
                withContext(job) {
                    dispatcher(outbox, transport).dispatchPending()
                }
            }
        } catch (failure: CancellationException) {
            boundaryCancellation = failure
        }

        assertTrue(cleanupFinished)
        assertTrue(boundaryCancellation != null)
        assertEquals(listOf("sms:1"), transport.sentEventIds)
        assertEquals(listOf("sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun cleanup_close_cancellation_does_not_leak_or_override_pre_ack_cancellation() {
        val outbox = RecordingOutbox(events = listOf(event("sms:1"), event("sms:2")))
        val transport = RecordingTransport(
            failurePoint = FailurePoint.CANCEL_SEND,
            closeFailure = CancellationException("close cancelled"),
        )

        val result = runSuspend { dispatcher(outbox, transport).dispatchPending() }

        assertEquals(CapabilityDispatchResult(0, 2, CapabilityDispatchFailure.CANCELLED), result)
        assertEquals(listOf(TransportCloseReason.PROCESS_STOPPED), transport.closeReasons)
        assertEquals(listOf("sms:1", "sms:2"), outbox.events.map { it.eventId })
    }

    @Test
    fun validates_attempt_range() {
        for (attempts in listOf(0, 11)) {
            try {
                dispatcher(RecordingOutbox(), RecordingTransport(), maxAttempts = attempts)
                throw AssertionError("maxAttempts $attempts must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: bounded retries are part of the dispatcher contract.
            }
        }
        dispatcher(RecordingOutbox(), RecordingTransport(), maxAttempts = 1)
        dispatcher(RecordingOutbox(), RecordingTransport(), maxAttempts = 10)
    }

    private fun dispatcher(
        outbox: CapabilityOutbox,
        transport: PairedBridgeTransport,
        egressGate: CapabilityEventEgressGate = CapabilityEventEgressGate { true },
        binding: CapabilityPairedBridgeBindingSource = CapabilityPairedBridgeBindingSource { validBinding() },
        maxAttempts: Int = 1,
    ) = CapabilityOutboxDispatcher("sms", outbox, transport, binding, egressGate, maxAttempts)

    private fun event(eventId: String, capability: String = "sms") = CapabilityDurableEvent(
        eventId = eventId,
        capability = capability,
        recordId = eventId,
        policyRevision = 7u,
        eventWire = eventId.encodeToByteArray(),
    )

    private fun validBinding(): VerifiedPairingTransportBinding = VerifiedPairingTransportBinding.mint(
        ticket = EnrollmentTicket("ticket", "device", "bridge", 4u, 1u, 100),
        bridge = BridgeIdentity("bridge"),
        policy = PolicyAttestation(1u, "digest"),
        expectedPairingGeneration = 4u,
        nowEpochSeconds = 10,
    )

    private enum class FailurePoint { OPEN, SEND, RECEIVE, ACK, CANCEL_OPEN, CANCEL_SEND, CANCEL_RECEIVE }

    private class RecordingOutbox(
        events: List<CapabilityDurableEvent> = emptyList(),
        private val rejectAcks: Boolean = false,
        private val recoveryFailure: Exception? = null,
        private val recoveryFatal: Error? = null,
        private val recoveryCancellation: Boolean = false,
        private val acknowledgeCancellation: Boolean = false,
        private val acknowledgeFailure: Exception? = null,
    ) : CapabilityOutbox {
        val events = events.toMutableList()

        override suspend fun enqueueAccepted(event: CapabilityDurableEvent): CapabilityDurableEvent = event

        override suspend fun acknowledge(eventId: String, eventAckWire: ByteArray) {
            if (acknowledgeCancellation) throw CancellationException("acknowledge cancelled")
            acknowledgeFailure?.let { throw it }
            if (rejectAcks || !eventAckWire.contentEquals(byteArrayOf(9))) {
                throw CapabilityOutboxAckRejected("reject")
            }
            events.removeAll { it.eventId == eventId }
        }

        override suspend fun recoverUnacknowledged(): List<CapabilityDurableEvent> {
            if (recoveryCancellation) throw CancellationException("recovery cancelled")
            recoveryFatal?.let { throw it }
            recoveryFailure?.let { throw it }
            return events.toList()
        }

        override suspend fun clear() = Unit
    }

    private class RecordingTransport(
        private val failurePoint: FailurePoint? = null,
        private val onOpen: () -> Unit = {},
        private val openFailures: MutableList<Exception> = mutableListOf(),
        private val onOpenFailure: () -> Unit = {},
        private val closeFailure: Exception? = null,
        private val onClose: suspend () -> Unit = {},
        private val trace: MutableList<String>? = null,
    ) : PairedBridgeTransport {
        val sentEventIds = mutableListOf<String>()
        val closeReasons = mutableListOf<TransportCloseReason>()
        var opens = 0

        override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession {
            opens += 1
            trace?.add("open")
            if (failurePoint == FailurePoint.CANCEL_OPEN) throw CancellationException("open cancelled")
            if (failurePoint == FailurePoint.OPEN) throw IllegalStateException("open failed")
            if (openFailures.isNotEmpty()) {
                onOpenFailure()
                throw openFailures.removeAt(0)
            }
            onOpen()
            return object : BridgeSession {
                override val connectionGeneration: ULong = 1u

                override suspend fun sendControl(canonicalWire: ByteArray) {
                    if (failurePoint == FailurePoint.CANCEL_SEND) throw CancellationException("cancelled")
                    if (failurePoint == FailurePoint.SEND) throw IllegalStateException("send failed")
                    trace?.add("send:${canonicalWire.decodeToString()}")
                    sentEventIds += canonicalWire.decodeToString()
                }

                override suspend fun receiveControl(): ByteArray {
                    if (failurePoint == FailurePoint.CANCEL_RECEIVE) throw CancellationException("receive cancelled")
                    if (failurePoint == FailurePoint.RECEIVE) throw IllegalStateException("receive failed")
                    return byteArrayOf(9)
                }
            }
        }

        override suspend fun close(reason: TransportCloseReason) {
            closeReasons += reason
            trace?.add("close:$reason")
            onClose()
            closeFailure?.let { throw it }
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
