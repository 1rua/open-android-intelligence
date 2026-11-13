package com.openandroidintelligence.mobile.screen

/** A user-initiated Android consent dialog; never an Agent execution approval or pairing grant. */
data class ScreenCaptureConsentTicket(val policyRevision: Long, val hostRevision: Long, val stopGeneration: Long = 0L)

fun screenCaptureConsentCurrent(ticket: ScreenCaptureConsentTicket?, policyRevision: Long, hostRevision: Long,
    capabilityEnabled: Boolean, emergencyStopped: Boolean, stopGeneration: Long = 0L): Boolean = ticket != null && capabilityEnabled &&
    !emergencyStopped && ticket.policyRevision == policyRevision && ticket.hostRevision == hostRevision && ticket.stopGeneration == stopGeneration
