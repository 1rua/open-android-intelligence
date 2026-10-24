package com.openandroidintelligence.tailnet.core

import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import java.nio.charset.StandardCharsets

/**
 * ALBIND1 codec for the ticket-bound Bridge binding. Native validates these
 * bytes against the enrollment before any Tailnet dial; this file never
 * accepts an endpoint, port, scheme, or host.
 */
internal object NativeBindingCodec {
    private val MAGIC = "ALBIND1".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 1

    fun encode(binding: VerifiedPairingTransportBinding): ByteArray {
        require(binding.enrollmentTicketDigest.isNotBlank()) { "native binding ticket digest is missing" }
        require(binding.policyAttestationDigest.isNotBlank()) { "native binding policy digest is missing" }
        require(binding.bridgeIdentity.isNotBlank()) { "native binding Bridge identity is missing" }

        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        writeField(out, binding.deviceId)
        writeField(out, binding.bridgeIdentity)
        writeField(out, binding.enrollmentTicketDigest)
        writeField(out, binding.pairingGeneration.toString())
        writeField(out, binding.policyAttestationRevision.toString())
        writeField(out, binding.policyAttestationDigest)
        return out.toByteArray()
    }

    private fun writeField(out: java.io.ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 0xffff_ffffL) { "native binding field is too large" }
        out.write(bytes.size ushr 24)
        out.write(bytes.size ushr 16 and 0xff)
        out.write(bytes.size ushr 8 and 0xff)
        out.write(bytes.size and 0xff)
        out.write(bytes)
    }
}
