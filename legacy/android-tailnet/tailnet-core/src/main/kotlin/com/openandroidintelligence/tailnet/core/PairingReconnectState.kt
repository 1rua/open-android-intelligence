package com.openandroidintelligence.tailnet.core

import com.openandroidintelligence.core.model.PairingTransportStatus
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.TransportFailure
import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding

interface ConnectionGenerationStore {
    fun current(): ULong
    /** Reserve and persist before exposing a BridgeSession. */
    fun reserveNext(): ULong
}

/** Durable seam supplied by the encrypted app-private state implementation. */
interface ConnectionGenerationPersistence {
    fun load(): ULong?
    fun save(generation: ULong)
}

class InMemoryConnectionGenerationStore(initial: ULong = 0uL) : ConnectionGenerationStore {
    private var generation = initial

    override fun current(): ULong = generation

    override fun reserveNext(): ULong {
        generation += 1u
        return generation
    }
}

/**
 * Connection-generation store for production adapters. The persistence seam
 * is injected so reconnect fencing survives process death and cannot silently
 * reset to generation zero.
 */
class PersistentConnectionGenerationStore(
    private val persistence: ConnectionGenerationPersistence,
) : ConnectionGenerationStore {
    private var generation: ULong = persistence.load() ?: 0uL

    override fun current(): ULong = generation

    override fun reserveNext(): ULong {
        generation += 1u
        persistence.save(generation)
        return generation
    }
}

/** Deterministic state machine used by both fake and real transports. */
class PairingReconnectStateMachine(
    private val generations: ConnectionGenerationStore,
) {
    var status: PairingTransportStatus = PairingTransportStatus.Unpaired
        private set

    fun beginOpen(_binding: VerifiedPairingTransportBinding, attempt: Int = 1): ULong {
        require(attempt > 0) { "attempt must be positive" }
        status = PairingTransportStatus.Pairing(attempt)
        val generation = generations.reserveNext()
        // The binding has already passed ticket/policy/generation verification;
        // this state machine only fences the transport connection generation.
        status = PairingTransportStatus.Connecting(generation, attempt)
        return generation
    }

    fun markConnected(generation: ULong, path: TransportPath): Boolean {
        if (generation != generations.current()) {
            status = PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION)
            return false
        }
        status = PairingTransportStatus.Connected(generation, path)
        return true
    }

    fun markDisconnected(generation: ULong, reason: TransportCloseReason, attempt: Int) {
        if (generation != generations.current()) {
            status = PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION)
            return
        }
        status = PairingTransportStatus.Reconnecting(generation, attempt, reason)
    }

    fun close(reason: TransportCloseReason) {
        status = PairingTransportStatus.Closed(reason)
    }
}
