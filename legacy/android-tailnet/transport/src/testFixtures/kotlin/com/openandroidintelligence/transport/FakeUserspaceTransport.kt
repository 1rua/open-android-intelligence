package com.openandroidintelligence.transport

import com.openandroidintelligence.core.model.BridgeSession
import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.tailnet.core.ConnectionGenerationStore
import com.openandroidintelligence.tailnet.core.InMemoryConnectionGenerationStore
import com.openandroidintelligence.tailnet.core.PairingReconnectStateMachine

/**
 * Deterministic userspace fake for contract and reconnect tests. It has no
 * network stack and accepts only a verified pairing binding.
 */
class FakeUserspaceTransport(
    generationStore: ConnectionGenerationStore = InMemoryConnectionGenerationStore(),
    private val path: TransportPath = TransportPath.DIRECT,
) : PairedBridgeTransport {
    private val state = PairingReconnectStateMachine(generationStore)
    private val inbound = ArrayDeque<ByteArray>()
    private val sent = mutableListOf<ByteArray>()
    private var active: FakeSession? = null

    override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession {
        check(active == null) { "a paired session is already open" }
        val generation = state.beginOpen(binding)
        check(state.markConnected(generation, path)) {
            "connection generation was fenced while opening the Bridge session"
        }
        return FakeSession(generation).also { active = it }
    }

    override suspend fun close(reason: TransportCloseReason) {
        active = null
        state.close(reason)
    }

    fun disconnect(reason: TransportCloseReason = TransportCloseReason.NETWORK_CHANGED) {
        val current = active ?: return
        state.markDisconnected(current.connectionGeneration, reason, attempt = 1)
        active = null
    }

    fun enqueueInbound(canonicalWire: ByteArray) {
        inbound += canonicalWire.copyOf()
    }

    fun sentControl(): List<ByteArray> = sent.map(ByteArray::copyOf)

    fun status() = state.status

    private inner class FakeSession(
        override val connectionGeneration: ULong,
    ) : BridgeSession {
        private fun checkCurrent() {
            check(active === this) { "session is closed or fenced" }
        }

        override suspend fun sendControl(canonicalWire: ByteArray) {
            checkCurrent()
            require(canonicalWire.isNotEmpty()) { "control wire must not be empty" }
            sent += canonicalWire.copyOf()
        }

        override suspend fun receiveControl(): ByteArray {
            checkCurrent()
            return checkNotNull(inbound.removeFirstOrNull()) { "no inbound control available" }.copyOf()
        }
    }
}
