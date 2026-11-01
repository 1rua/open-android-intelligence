package com.openandroidintelligence.mobile.screen

/** A user-initiated Android consent dialog; never an Agent execution approval or pairing grant. */
data class ScreenCaptureConsentTicket(val policyRevision: Long, val hostRevision: Long)

fun screenCaptureConsentCurrent(ticket: ScreenCaptureConsentTicket?, policyRevision: Long, hostRevision: Long,
    capabilityEnabled: Boolean, emergencyStopped: Boolean): Boolean = ticket != null && capabilityEnabled &&
    !emergencyStopped && ticket.policyRevision == policyRevision && ticket.hostRevision == hostRevision
