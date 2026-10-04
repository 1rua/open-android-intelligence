package com.openandroidintelligence.mobile.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantClientScopeTest {
    @Test fun aColdClientCanConsentAfterTheAsynchronousSessionRestores() {
        val client = AssistantClientScope(null)
        assertFalse(client.canConsent(null))
        assertFalse(client.consentTo(null))
        assertTrue(client.canConsent("restored-account"))
        assertFalse(client.canShare("restored-account"))
        assertNull(client.accountId)
        assertTrue(client.consentTo("restored-account"))
        assertEquals("restored-account",client.accountId)
        assertTrue(client.canShare("restored-account"))
    }
    @Test fun switchingAccountsBeforeConsentBindsOnlyAtTheExplicitConsentStep() {
        val client = AssistantClientScope("account-a")
        assertTrue(client.canConsent("account-b"))
        assertFalse(client.canShare("account-b"))
        assertEquals("account-a",client.accountId)
        assertTrue(client.consentTo("account-b"))
        assertEquals("account-b",client.accountId)
    }
    @Test fun aConsentedClientCannotFollowAnAccountSwitchOrTransferAnOldScreenCapture() {
        val client = AssistantClientScope(null)
        assertTrue(client.consentTo("account-a"))
        assertFalse(client.canShare("account-b"))
        assertFalse(client.canConsent("account-b"))
        assertFalse(client.consentTo("account-b"))
        assertEquals("account-a",client.accountId)
        assertFalse(client.canShare("account-a"))
        assertTrue(client.consentTo("account-a"))
        assertTrue(client.canShare("account-a"))
    }
    @Test fun reconnectingTheSameAccountRequiresFreshConsentAfterTheConnectionWasLost() {
        val client = AssistantClientScope("account-a")
        assertTrue(client.consentTo("account-a"))
        assertFalse(client.canShare(null))
        assertFalse(client.canShare("account-a"))
        assertTrue(client.canConsent("account-a"))
        assertTrue(client.consentTo("account-a"))
    }
}
