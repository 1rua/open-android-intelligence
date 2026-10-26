package com.openandroidintelligence.transport

import com.openandroidintelligence.core.model.BridgeSession
import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.PairingTransportStatus
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.tailnet.core.ConnectionGenerationStore
import com.openandroidintelligence.tailnet.core.InMemoryConnectionGenerationStore
import com.openandroidintelligence.tailnet.core.PairingReconnectStateMachine
import com.openandroidintelligence.tailnet.core.TailscaleUserspaceCore
import com.openandroidintelligence.tailnet.core.UserspaceBridgeChannel

/**
 * Production-shaped adapter around the pinned tsnet/libtailscale spike. The
 * core itself is the only layer that knows how to establish the userspace
 * path; this class forwards one verified Bridge binding and fences sessions by
 * a persisted connection generation.
 */
class TsnetPairedBridgeTransport(
    private val core: TailscaleUserspaceCore,
    generationStore: ConnectionGenerationStore = InMemoryConnectionGenerationStore(),
) : PairedBridgeTransport {
    private val state = PairingReconnectStateMachine(generationStore)
    private var active: Session? = null

    override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession {
        check(active == null) { "a paired session is already open" }
        val generation = state.beginOpen(binding)
        return try {
            val channel = core.openPairedBridge(binding)
            val path = core.path(binding)
            if (path == TransportPath.OFFLINE) {
                channel.close()
                if (state.status !is PairingTransportStatus.Failed) {
                    state.close(TransportCloseReason.FAILURE)
                }
                throw IllegalStateException("Tailnet Bridge peer is offline")
            }
            if (!state.markConnected(generation, path)) {
                channel.close()
                error("connection generation was fenced while opening the Bridge session")
            }
            Session(generation, channel).also { active = it }
        } catch (failure: Throwable) {
            if (state.status !is PairingTransportStatus.Failed) {
                state.close(TransportCloseReason.FAILURE)
            }
            throw failure
        }
    }

    /** Fences the current session before opening the next connection generation. */
    suspend fun reconnect(
        binding: VerifiedPairingTransportBinding,
        cause: TransportCloseReason = TransportCloseReason.NETWORK_CHANGED,
        attempt: Int = 1,
    ): BridgeSession {
        require(attempt > 0) { "attempt must be positive" }
        val previous = active
        if (previous != null) {
            active = null
            state.markDisconnected(previous.connectionGeneration, cause, attempt)
            val generationWasFenced = state.status !is PairingTransportStatus.Reconnecting
            try {
                previous.channel.close()
            } catch (failure: Throwable) {
                state.close(TransportCloseReason.FAILURE)
                throw failure
            }
            check(!generationWasFenced) {
                "connection generation was fenced before reconnect"
            }
        }
        return open(binding)
    }

    override suspend fun close(reason: TransportCloseReason) {
        val previous = active
        active = null
        try {
            previous?.channel?.close()
        } finally {
            state.close(reason)
        }
    }

    fun status() = state.status

    private inner class Session(
        override val connectionGeneration: ULong,
        val channel: UserspaceBridgeChannel,
    ) : BridgeSession {
        private fun checkCurrent() {
            check(active === this) { "session is closed or fenced" }
        }

        override suspend fun sendControl(canonicalWire: ByteArray) {
            checkCurrent()
            require(canonicalWire.isNotEmpty()) { "control wire must not be empty" }
            channel.sendControl(canonicalWire.copyOf())
        }

        override suspend fun receiveControl(): ByteArray {
            checkCurrent()
            return channel.receiveControl().copyOf()
        }
    }
}
