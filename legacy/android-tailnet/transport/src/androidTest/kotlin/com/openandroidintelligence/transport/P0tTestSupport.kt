package com.openandroidintelligence.transport

import com.openandroidintelligence.core.model.BridgeIdentity
import com.openandroidintelligence.core.model.EnrollmentTicket
import com.openandroidintelligence.core.model.PolicyAttestation
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.tailnet.core.VerifiedPairingTransportBindingFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Runs a non-blocking suspend block to completion on the current thread. */
fun runSuspend(block: suspend () -> Unit) {
    var throwable: Throwable? = null
    var resumed = false
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {
            throwable = result.exceptionOrNull()
            resumed = true
        }
    })
    if (!resumed) error("suspend block did not complete synchronously")
    throwable?.let { throw it }
}

/** Deterministic ticket-bound binding for lifecycle (non-network) tests. */
fun p0tValidBinding(): VerifiedPairingTransportBinding {
    val ticket = EnrollmentTicket(
        id = "p0t-ticket",
        deviceId = "p0t-device",
        bridgeIdentity = "bridge-fp",
        pairingGeneration = 7u,
        minimumPolicyRevision = 2u,
        expiresAtEpochSeconds = Long.MAX_VALUE,
    )
    return VerifiedPairingTransportBindingFactory.mint(
        ticket = ticket,
        bridge = BridgeIdentity("bridge-fp"),
        policy = PolicyAttestation(2u, "digest"),
        expectedPairingGeneration = 7u,
        nowEpochSeconds = 1,
    )
}
