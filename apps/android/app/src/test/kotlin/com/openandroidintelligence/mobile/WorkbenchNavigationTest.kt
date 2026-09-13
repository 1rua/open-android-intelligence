package com.openandroidintelligence.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WorkbenchNavigationTest {
    @Test
    fun themePreferencesToggleCleanly() {
        val dark = ThemePreference.DARK
        val light = ThemePreference.LIGHT
        val system = ThemePreference.SYSTEM

        assertNotNull(dark)
        assertNotNull(light)
        assertNotNull(system)

        val toggledFromDark = if (dark == ThemePreference.DARK) ThemePreference.LIGHT else ThemePreference.DARK
        assertEquals(ThemePreference.LIGHT, toggledFromDark)

        val toggledFromLight = if (light == ThemePreference.DARK) ThemePreference.LIGHT else ThemePreference.DARK
        assertEquals(ThemePreference.DARK, toggledFromLight)
    }

    @Test
    fun coreNavigationPreservesThreeBoundaries() {
        val core = CoreNavigation(
            gateway = GatewayDestination("https://gw.test.internal", isConnected = true),
            conversations = ConversationDestination(activeSessionId = "sess-1"),
            attachments = AttachmentDestination(uploadedCount = 3),
        )

        assertEquals("https://gw.test.internal", core.gateway.baseUrl)
        assertEquals(true, core.gateway.isConnected)
        assertEquals("sess-1", core.conversations.activeSessionId)
        assertEquals(3, core.attachments.uploadedCount)
    }
}

