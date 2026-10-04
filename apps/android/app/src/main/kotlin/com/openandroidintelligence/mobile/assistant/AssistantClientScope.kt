package com.openandroidintelligence.mobile.assistant

/** A cold connection may bind at explicit consent; shared content stays with that account. */
internal class AssistantClientScope(initialAccountId: String?) {
    var accountId: String? = initialAccountId
        private set
    var consent: Boolean = false
        private set
    private var boundByConsent = false

    fun canConsent(connectedAccountId: String?): Boolean =
        connectedAccountId != null && (!boundByConsent || connectedAccountId == accountId)

    fun consentTo(connectedAccountId: String?): Boolean {
        if (!canConsent(connectedAccountId)) return false
        accountId = connectedAccountId
        boundByConsent = true
        consent = true
        return true
    }

    fun canShare(connectedAccountId: String?): Boolean {
        if (accountId == null || accountId != connectedAccountId) consent = false
        return consent
    }
}
