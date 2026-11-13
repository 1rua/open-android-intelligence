package com.openandroidintelligence.mobile.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenCaptureSystemConsentTest {
    @Test fun dialogDoesNotDependOnCoreAccountOrOldScreenSessionGrant() {
        val ticket = ScreenCaptureConsentTicket(4, 7)
        assertTrue(screenCaptureConsentCurrent(ticket, 4, 7, true, false))
    }
    @Test fun revocationEmergencyAndHostChangeFenceDelayedSystemCallbacks() {
        val ticket = ScreenCaptureConsentTicket(4, 7)
        assertFalse(screenCaptureConsentCurrent(ticket, 5, 7, true, false))
        assertFalse(screenCaptureConsentCurrent(ticket, 4, 8, true, false))
        assertFalse(screenCaptureConsentCurrent(ticket, 4, 7, false, false))
        assertFalse(screenCaptureConsentCurrent(ticket, 4, 7, true, true))
        assertFalse(screenCaptureConsentCurrent(null, 4, 7, true, false))
    }
    @Test fun stopDuringTheSystemDialogInvalidatesItsDelayedConsentWithoutResettingGlobalMode() {
        val ticket = ScreenCaptureConsentTicket(4, 7, stopGeneration = 2)
        assertTrue(screenCaptureConsentCurrent(ticket, 4, 7, true, false, stopGeneration = 2))
        assertFalse(screenCaptureConsentCurrent(ticket, 4, 7, true, false, stopGeneration = 3))
    }

}
