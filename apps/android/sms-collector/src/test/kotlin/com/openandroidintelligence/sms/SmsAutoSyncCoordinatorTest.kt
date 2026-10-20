package com.openandroidintelligence.sms

import com.openandroidintelligence.capability.AgentDataRequest
import com.openandroidintelligence.capability.AgentRequestAuthorization
import com.openandroidintelligence.capability.AuthorizedAutoSendSubscription
import com.openandroidintelligence.capability.CapabilityAvailability
import com.openandroidintelligence.capability.CapabilityEvent
import com.openandroidintelligence.capability.CapabilityFilter
import com.openandroidintelligence.capability.CapabilityGrant
import com.openandroidintelligence.capability.CapabilityReadResult
import com.openandroidintelligence.capability.DataSyncMode
import com.openandroidintelligence.capability.DefaultAgentRequestAuthorizer
import com.openandroidintelligence.capability.MobileDataCapability
import com.openandroidintelligence.capability.SmsCapabilityProvider
import com.openandroidintelligence.capability.SmsMetadata
import com.openandroidintelligence.capability.SmsPayload
import com.openandroidintelligence.core.model.BridgeSession
import com.openandroidintelligence.core.model.CapabilityDurableEvent
import com.openandroidintelligence.core.model.CapabilityOutbox
import com.openandroidintelligence.core.model.CapabilityOutboxAckRejected
import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.sync.CapabilityEventEgressGate
import com.openandroidintelligence.sync.CapabilityPairedBridgeBindingSource
import com.openandroidintelligence.core.model.BridgeIdentity
import com.openandroidintelligence.core.model.EnrollmentTicket
import com.openandroidintelligence.core.model.PolicyAttestation
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAutoSyncCoordinatorTest {
    @Test
    fun enqueues_deterministic_sms_events_before_advancing_equal_timestamp_cursors_then_acks_in_order() {
        val trace = mutableListOf<String>()
        val outbox = RecordingOutbox(trace)
        val cursor = RecordingCursorStore(SmsCursor(providerId = 1, messageAtEpochMs = 100), trace)
        val transport = RecordingTransport()
        val coordinator = coordinator(
            provider = provider(event(providerId = 2, timestamp = 100), event(providerId = 3, timestamp = 101)),
            outbox = outbox,
            cursor = cursor,
            transport = transport,
            egressGate = CapabilityEventEgressGate { true },
        )

        val result = runSuspend { coordinator.runOnce(subscription()) }

        assertEquals(
            listOf("enqueue:sms:2", "cursor:sms:2", "enqueue:sms:3", "cursor:sms:3"),
            trace,
        )
        assertEquals(SmsCursor(providerId = 3, messageAtEpochMs = 101), cursor.current())
        assertEquals(2, result.captured)
        assertEquals(2, result.enqueued)
        assertEquals(2, result.acknowledged)
        assertEquals(0, result.retained)
        assertEquals(null, result.failure)
        assertEquals(listOf("sms:2", "sms:3"), transport.sentEventIds)
    }

    @Test
    fun transport_or_ack_failure_retains_the_enqueued_event() {
        val transportOutbox = RecordingOutbox()
        val transportFailure = coordinator(
            provider = provider(event(providerId = 2, timestamp = 100)),
            outbox = transportOutbox,
            cursor = InMemorySmsCursorStore(),
            transport = RecordingTransport(sendFailure = IllegalArgumentException("transport validation failure")),
            egressGate = CapabilityEventEgressGate { true },
        )

        val transportResult = runSuspend { transportFailure.runOnce(subscription()) }
        assertEquals(SmsSyncFailure.TRANSPORT_FAILURE, transportResult.failure)
        assertEquals(1, transportResult.retained)
        assertEquals(listOf("sms:2"), runSuspend { transportOutbox.recoverUnacknowledged() }.map { it.eventId })

        val ackOutbox = RecordingOutbox(rejectAcks = true)
        val ackFailure = coordinator(
            provider = provider(event(providerId = 3, timestamp = 100)),
            outbox = ackOutbox,
            cursor = InMemorySmsCursorStore(),
            transport = RecordingTransport(),
            egressGate = CapabilityEventEgressGate { true },
        )
        val ackResult = runSuspend { ackFailure.runOnce(subscription()) }

        assertEquals(SmsSyncFailure.ACK_REJECTED, ackResult.failure)
        assertEquals(1, ackResult.retained)
        assertEquals(listOf("sms:3"), runSuspend { ackOutbox.recoverUnacknowledged() }.map { it.eventId })
    }

    @Test
    fun policy_revoke_after_enqueue_blocks_egress_and_retains_the_event() {
        var egressAllowed = true
        val outbox = RecordingOutbox(onEnqueue = { egressAllowed = false })
        val transport = RecordingTransport()
        val coordinator = coordinator(
            provider = provider(event(providerId = 2, timestamp = 100)),
            outbox = outbox,
            cursor = InMemorySmsCursorStore(),
            transport = transport,
            egressGate = CapabilityEventEgressGate { egressAllowed },
        )

        val result = runSuspend { coordinator.runOnce(subscription()) }

        assertEquals(SmsSyncFailure.POLICY_REVOKED, result.failure)
        assertEquals(1, result.retained)
        assertTrue(transport.sentEventIds.isEmpty())
        assertEquals(1, runSuspend { outbox.recoverUnacknowledged() }.size)
    }

    @Test
    fun policy_revoke_while_opening_blocks_send_and_retains_the_event() {
        var egressAllowed = true
        val outbox = RecordingOutbox()
        val transport = RecordingTransport(onOpen = { egressAllowed = false })
        val coordinator = coordinator(
            provider = provider(event(providerId = 2, timestamp = 100)),
            outbox = outbox,
            cursor = InMemorySmsCursorStore(),
            transport = transport,
            egressGate = CapabilityEventEgressGate { egressAllowed },
        )

        val result = runSuspend { coordinator.runOnce(subscription()) }

        assertEquals(SmsSyncFailure.POLICY_REVOKED, result.failure)
        assertEquals(1, result.retained)
        assertTrue(transport.sentEventIds.isEmpty())
        assertEquals(listOf("sms:2"), runSuspend { outbox.recoverUnacknowledged() }.map { it.eventId })
    }

    @Test
    fun failed_cursor_advance_keeps_newly_enqueued_event_out_of_egress() {
        val outbox = RecordingOutbox().also {
            runSuspend {
                it.enqueueAccepted(
                    CapabilityDurableEvent("sms:1", "sms", "sms:1", 7u, "sms:1".encodeToByteArray()),
                )
            }
        }
        val transport = RecordingTransport()
        val coordinator = coordinator(
            provider = provider(event(providerId = 2, timestamp = 100)),
            outbox = outbox,
            cursor = object : SmsCursorStore {
                override fun current(): SmsCursor? = null
                override fun advance(cursor: SmsCursor): Boolean = false
            },
            transport = transport,
            egressGate = CapabilityEventEgressGate { true },
        )

        val result = runSuspend { coordinator.runOnce(subscription()) }

        assertEquals(SmsSyncFailure.CURSOR_NOT_DURABLE, result.failure)
        assertEquals(1, result.retained)
        assertEquals(listOf("sms:1"), transport.sentEventIds)
        assertEquals(listOf("sms:2"), runSuspend { outbox.recoverUnacknowledged() }.map { it.eventId })
    }

    @Test
    fun throwing_cursor_advance_keeps_newly_enqueued_event_out_of_egress() {
        val outbox = RecordingOutbox()
        val transport = RecordingTransport()
        val coordinator = coordinator(
            provider = provider(event(providerId = 2, timestamp = 100)),
            outbox = outbox,
            cursor = object : SmsCursorStore {
                override fun current(): SmsCursor? = null
                override fun advance(cursor: SmsCursor): Boolean = throw IllegalStateException("cursor storage unavailable")
            },
            transport = transport,
            egressGate = CapabilityEventEgressGate { true },
        )

        val result = runSuspend { coordinator.runOnce(subscription()) }

        assertEquals(SmsSyncFailure.CURSOR_NOT_DURABLE, result.failure)
        assertEquals(1, result.retained)
        assertTrue(transport.sentEventIds.isEmpty())
        assertEquals(listOf("sms:2"), runSuspend { outbox.recoverUnacknowledged() }.map { it.eventId })
    }

    @Test
    fun cancellation_after_accepted_enqueue_reports_the_retained_durable_event() {
        val outbox = RecordingOutbox()
        val coordinator = coordinator(
            provider = providerFlow(kotlinx.coroutines.flow.flow {
                emit(event(providerId = 2, timestamp = 100))
                throw kotlinx.coroutines.CancellationException()
            }),
            outbox = outbox,
            cursor = InMemorySmsCursorStore(),
            transport = RecordingTransport(),
            egressGate = CapabilityEventEgressGate { true },
        )

        val result = runSuspend { coordinator.runOnce(subscription()) }

        assertEquals(SmsSyncFailure.CANCELLED, result.failure)
        assertEquals(1, result.enqueued)
        assertEquals(1, result.retained)
        assertEquals(listOf("sms:2"), runSuspend { outbox.recoverUnacknowledged() }.map { it.eventId })
    }

    @Test
    fun generic_non_sms_outbox_entry_is_retained_without_sms_egress() {
        val outbox = RecordingOutbox().also {
            runSuspend {
                it.enqueueAccepted(
                    CapabilityDurableEvent("calls:1", "calls", "calls:1", 7u, "calls-wire".encodeToByteArray()),
                )
            }
        }
        val transport = RecordingTransport()
        val gateEvents = mutableListOf<String>()
        val coordinator = coordinator(
            provider = provider(event(providerId = 2, timestamp = 100)),
            outbox = outbox,
            cursor = InMemorySmsCursorStore(),
            transport = transport,
            egressGate = CapabilityEventEgressGate { event -> gateEvents += event.eventId; true },
        )

        val result = runSuspend { coordinator.runOnce(subscription()) }

        assertEquals(1, result.acknowledged)
        assertEquals(1, result.retained)
        assertEquals(listOf("sms:2"), transport.sentEventIds)
        assertEquals(listOf("sms:2", "sms:2", "sms:2"), gateEvents)
        assertEquals(listOf("calls:1"), runSuspend { outbox.recoverUnacknowledged() }.map { it.eventId })
    }

    private fun coordinator(
        provider: SmsCapabilityProvider,
        outbox: CapabilityOutbox,
        cursor: SmsCursorStore,
        transport: PairedBridgeTransport,
        egressGate: CapabilityEventEgressGate,
    ) = SmsAutoSyncCoordinator(
        provider = provider,
        outbox = outbox,
        cursorStore = cursor,
        eventEncoder = SmsEventEncoder { eventId, _, _ -> eventId.encodeToByteArray() },
        transport = transport,
        bindingSource = CapabilityPairedBridgeBindingSource { validBinding() },
        egressGate = egressGate,
        maxAttempts = 1,
    )

    private fun provider(vararg events: CapabilityEvent<SmsPayload>) = object : SmsCapabilityProvider {
        override suspend fun read(scope: com.openandroidintelligence.capability.AuthorizedReadScope): CapabilityReadResult<SmsPayload> =
            error("read is not used by auto-send")

        override fun observeAutoSend(scope: com.openandroidintelligence.capability.AuthorizedAutoSendScope): Flow<CapabilityEvent<SmsPayload>> =
            flowOf(*events)
    }

    private fun providerFlow(events: Flow<CapabilityEvent<SmsPayload>>) = object : SmsCapabilityProvider {
        override suspend fun read(scope: com.openandroidintelligence.capability.AuthorizedReadScope): CapabilityReadResult<SmsPayload> =
            error("read is not used by auto-send")

        override fun observeAutoSend(scope: com.openandroidintelligence.capability.AuthorizedAutoSendScope): Flow<CapabilityEvent<SmsPayload>> = events
    }

    private fun event(providerId: Long, timestamp: Long): CapabilityEvent<SmsPayload> = CapabilityEvent(
        capability = MobileDataCapability.SMS,
        eventId = "sms:$providerId",
        record = SmsPayload(
            metadata = SmsMetadata(
                recordId = "sms:$providerId",
                senderAddress = null,
                threadId = null,
                messageAtEpochMs = timestamp,
                observedAtEpochMs = timestamp,
                read = true,
                subscriptionId = null,
            ),
            content = releasedSmsContent("not asserted or diagnosed"),
        ),
        policyRevision = 7u,
    )

    private fun subscription(): AuthorizedAutoSendSubscription {
        val authorization = DefaultAgentRequestAuthorizer().authorize(
            request = AgentDataRequest("sms-subscription", MobileDataCapability.SMS, DataSyncMode.AUTO_SEND, CapabilityFilter.Sms, 7u),
            grant = CapabilityGrant(MobileDataCapability.SMS, CapabilityFilter.Sms, false, true, true, 7u),
            availability = CapabilityAvailability.READY,
        ) as AgentRequestAuthorization.Allowed
        return authorization.access as AuthorizedAutoSendSubscription
    }

    private fun validBinding(): VerifiedPairingTransportBinding = VerifiedPairingTransportBinding.mint(
        ticket = EnrollmentTicket("ticket", "device", "bridge", 4u, 1u, 100),
        bridge = BridgeIdentity("bridge"),
        policy = PolicyAttestation(1u, "digest"),
        expectedPairingGeneration = 4u,
        nowEpochSeconds = 10,
    )

    private class RecordingCursorStore(initial: SmsCursor?, private val trace: MutableList<String>) : SmsCursorStore {
        private val delegate = InMemorySmsCursorStore(initial)
        override fun current(): SmsCursor? = delegate.current()
        override fun advance(cursor: SmsCursor): Boolean = delegate.advance(cursor).also {
            if (it) trace += "cursor:sms:${cursor.providerId}"
        }
    }

    private class RecordingOutbox(
        private val trace: MutableList<String> = mutableListOf(),
        private val rejectAcks: Boolean = false,
        private val onEnqueue: () -> Unit = {},
    ) : CapabilityOutbox {
        private val entries = LinkedHashMap<String, CapabilityDurableEvent>()

        override suspend fun enqueueAccepted(event: CapabilityDurableEvent): CapabilityDurableEvent {
            trace += "enqueue:${event.eventId}"
            onEnqueue()
            entries.putIfAbsent(event.eventId, event)
            return entries.getValue(event.eventId)
        }

        override suspend fun acknowledge(eventId: String, eventAckWire: ByteArray) {
            if (rejectAcks || !eventAckWire.contentEquals(byteArrayOf(9))) throw CapabilityOutboxAckRejected("invalid ACK")
            entries.remove(eventId)
        }

        override suspend fun recoverUnacknowledged(): List<CapabilityDurableEvent> = entries.values.toList()

        override suspend fun clear() {
            entries.clear()
        }
    }

    private class RecordingTransport(
        private val sendFailure: Throwable? = null,
        private val onOpen: () -> Unit = {},
    ) : PairedBridgeTransport {
        val sentEventIds = mutableListOf<String>()
        override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession {
            onOpen()
            return object : BridgeSession {
            override val connectionGeneration: ULong = 1u
            override suspend fun sendControl(canonicalWire: ByteArray) {
                sendFailure?.let { throw it }
                sentEventIds += canonicalWire.decodeToString()
            }
            override suspend fun receiveControl(): ByteArray = byteArrayOf(9)
        }
        }
        override suspend fun close(reason: TransportCloseReason) = Unit
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
