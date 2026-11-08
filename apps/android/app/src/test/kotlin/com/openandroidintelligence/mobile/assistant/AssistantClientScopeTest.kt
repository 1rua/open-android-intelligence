package com.openandroidintelligence.mobile.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantClientScopeTest {
    @Test fun anAvailableAccountBindsWithoutAnyConsentStep() {
        val client = AssistantClientScope(null)
        assertFalse(client.canShare(null))
        assertFalse(client.canShare("account-a"))
        assertTrue(client.bind("account-a"))
        assertEquals("account-a",client.accountId)
        assertTrue(client.canShare("account-a"))
    }

    @Test fun anInitiallyUnboundClientBindsOnceTheSessionRestores() {
        val client = AssistantClientScope(null)
        assertFalse(client.bind(null))
        assertNull(client.accountId)
        assertFalse(client.canShare(null))
        assertTrue(client.bind("restored-account"))
        assertEquals("restored-account",client.accountId)
        assertTrue(client.canShare("restored-account"))
    }

    @Test fun anInitialAccountIdAloneGrantsNothingUntilTheClientBinds() {
        val client = AssistantClientScope("account-a")
        assertFalse(client.canShare("account-a"))
        assertTrue(client.bind("account-a"))
        assertTrue(client.canShare("account-a"))
    }

    @Test fun switchingAccountsInvalidatesTheBindingImmediately() {
        val client = AssistantClientScope(null)
        assertTrue(client.bind("account-a"))
        assertTrue(client.canShare("account-a"))
        assertTrue(client.accountChanged("account-b"))
        assertEquals("account-a",client.accountId)
        assertFalse(client.canShare("account-b"))
        // 失效之后旧账号也不能再复用，必须重新绑定。
        assertFalse(client.accountChanged("account-b"))
        assertFalse(client.canShare("account-a"))
        assertTrue(client.bind("account-b"))
        assertTrue(client.canShare("account-b"))
        assertFalse(client.canShare("account-a"))
    }

    @Test fun aCrossAccountRequestFailsClosedForEveryLaterCheck() {
        val client = AssistantClientScope(null)
        assertTrue(client.bind("account-a"))
        assertFalse(client.canShare("account-b"))
        assertFalse(client.canShare("account-a"))
        assertEquals("account-a",client.accountId)
        assertTrue(client.bind("account-a"))
        assertTrue(client.canShare("account-a"))
    }

    @Test fun aDisconnectedClientCannotReuseItsOldBinding() {
        val client = AssistantClientScope(null)
        assertTrue(client.bind("account-a"))
        assertTrue(client.canShare("account-a"))
        assertFalse(client.canShare(null))
        assertFalse(client.canShare("account-a"))
        assertFalse(client.accountChanged(null))
        assertTrue(client.bind("account-a"))
        assertTrue(client.canShare("account-a"))
    }
}
