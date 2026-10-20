package com.openandroidintelligence.notifications

import com.openandroidintelligence.core.model.BridgeIdentity
import com.openandroidintelligence.core.model.BridgeSession
import com.openandroidintelligence.core.model.EnrollmentTicket
import com.openandroidintelligence.core.model.NotificationContent
import com.openandroidintelligence.core.model.NotificationMetadata
import com.openandroidintelligence.core.model.NotificationRecordV1
import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.PolicyAttestation
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.encrypted.store.EventAckVerifier
import com.openandroidintelligence.encrypted.store.EventIdSource
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.encrypted.store.NotificationOutboxStore
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBridgeDispatcherTest {
    @Test
    fun forged_ack_is_retried_with_the_exact_same_canonical_event_until_bound_ack() {
        val persistence = InMemoryOutboxPersistence()
        val outbox = NotificationOutboxStore(
            persistence,
            ByteArray(32) { 1 },
            EventAckVerifier { id, wire -> id == "event-a" && wire.contentEquals(byteArrayOf(7)) },
            EventIdSource { "event-a" },
        )
        val event = outbox.enqueueAcceptedBlocking(record())
        val transport = NotificationDispatcherTestTransport()
        transport.enqueueInbound(byteArrayOf(8))
        transport.enqueueInbound(byteArrayOf(7))
        val dispatcher = NotificationBridgeDispatcher(
            outbox = outbox,
            transport = transport,
            bindingSource = PairedBridgeBindingSource { validBinding() },
            egressGate = NotificationRecordEgressGate { true },
            maxAttempts = 2,
        )

        val result = runSuspend { dispatcher.dispatchPending() }

        assertEquals(1, result.acknowledged)
        assertTrue(outbox.recoverUnacknowledgedBlocking().isEmpty())
        assertEquals(2, transport.sentControl().size)
        transport.sentControl().forEach { assertArrayEquals(event.canonicalWire, it) }
    }

    @Test
    fun current_revoke_blocks_old_queued_content_before_transport_open() {
        val outbox = NotificationOutboxStore(
            InMemoryOutboxPersistence(),
            ByteArray(32) { 2 },
            eventIds = EventIdSource { "event-revoked" },
        )
        outbox.enqueueAcceptedBlocking(record())
        val transport = NotificationDispatcherTestTransport()
        val dispatcher = NotificationBridgeDispatcher(
            outbox = outbox,
            transport = transport,
            bindingSource = PairedBridgeBindingSource { validBinding() },
            egressGate = NotificationRecordEgressGate { false },
        )

        val result = runSuspend { dispatcher.dispatchPending() }

        assertEquals(0, result.sent)
        assertEquals(1, result.retainedByPolicy)
        assertTrue(transport.sentControl().isEmpty())
        assertEquals(1, outbox.recoverUnacknowledgedBlocking().size)
    }

    @Test
    fun process_restart_recovers_unacknowledged_event_and_acks_after_validation() {
        val persistence = InMemoryOutboxPersistence()
        val key = ByteArray(32) { 3 }
        val first = NotificationOutboxStore(
            persistence,
            key,
            eventIds = EventIdSource { "event-restart" },
        )
        val original = first.enqueueAcceptedBlocking(record())
        val restored = NotificationOutboxStore(
            persistence,
            key,
            ackVerifier = EventAckVerifier { id, ack ->
                id == "event-restart" && ack.contentEquals(byteArrayOf(9))
            },
        )
        val transport = NotificationDispatcherTestTransport()
        transport.enqueueInbound(byteArrayOf(9))
        val dispatcher = NotificationBridgeDispatcher(
            outbox = restored,
            transport = transport,
            bindingSource = PairedBridgeBindingSource { validBinding() },
            egressGate = NotificationRecordEgressGate { true },
        )

        val result = runSuspend { dispatcher.dispatchPending() }

        assertEquals(1, result.acknowledged)
        assertArrayEquals(original.canonicalWire, transport.sentControl().single())
        assertTrue(restored.recoverUnacknowledgedBlocking().isEmpty())
    }

    private fun record() = NotificationRecordV1.Upsert(
        sourceEpoch = 1u,
        occurrenceId = "occ",
        recordKey = "key",
        recordRevision = 1u,
        cursor = 1u,
        capturedAtEpochMs = 10,
        captureRevision = 1u,
        metadata = NotificationMetadata("mail", "Mail", "channel", 10),
        content = NotificationContent("title", "body"),
    )

    private fun validBinding() = VerifiedPairingTransportBinding.mint(
        ticket = EnrollmentTicket("ticket", "device", "bridge", 4u, 1u, 100),
        bridge = BridgeIdentity("bridge"),
        policy = PolicyAttestation(1u, "digest"),
        expectedPairingGeneration = 4u,
        nowEpochSeconds = 10,
    )
}

private class NotificationDispatcherTestTransport : PairedBridgeTransport {
    private val inbound = ArrayDeque<ByteArray>()
    private val sent = mutableListOf<ByteArray>()
    private var active = false

    override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession {
        check(!active)
        check(binding.deviceId.isNotBlank())
        active = true
        return object : BridgeSession {
            override val connectionGeneration = 1uL

            override suspend fun sendControl(canonicalWire: ByteArray) {
                check(active)
                require(canonicalWire.isNotEmpty())
                sent += canonicalWire.copyOf()
            }

            override suspend fun receiveControl(): ByteArray {
                check(active)
                return checkNotNull(inbound.removeFirstOrNull()).copyOf()
            }
        }
    }

    override suspend fun close(reason: TransportCloseReason) {
        active = false
    }

    fun enqueueInbound(wire: ByteArray) {
        inbound.addLast(wire.copyOf())
    }

    fun sentControl(): List<ByteArray> = sent.map(ByteArray::copyOf)
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
