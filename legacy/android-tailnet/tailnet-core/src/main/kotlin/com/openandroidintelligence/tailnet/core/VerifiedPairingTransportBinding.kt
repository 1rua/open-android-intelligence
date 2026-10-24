package com.openandroidintelligence.tailnet.core

import com.openandroidintelligence.core.model.BridgeIdentity
import com.openandroidintelligence.core.model.EnrollmentTicket
import com.openandroidintelligence.core.model.PolicyAttestation
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding

/**
 * Tailnet owns the construction seam, while the value type remains immutable
 * and constructor-private in core-model. This keeps transports from minting a
 * binding from user-entered connection details.
 */
object VerifiedPairingTransportBindingFactory {
    fun mint(
        ticket: EnrollmentTicket,
        bridge: BridgeIdentity,
        policy: PolicyAttestation,
        expectedPairingGeneration: ULong,
        nowEpochSeconds: Long,
    ): VerifiedPairingTransportBinding = VerifiedPairingTransportBinding.mint(
        ticket = ticket,
        bridge = bridge,
        policy = policy,
        expectedPairingGeneration = expectedPairingGeneration,
        nowEpochSeconds = nowEpochSeconds,
    )
}
