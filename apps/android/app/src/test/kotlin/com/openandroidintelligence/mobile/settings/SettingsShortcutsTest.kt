package com.openandroidintelligence.mobile.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsShortcutsTest {
    @Test fun nestedSameDepthShortcutsRestoreEachOriginWithoutAddingActivePages() {
        var history = SettingsShortcutHistory().open("capability-a", "plugins")
        history = history.open("plugins", "capability-b")
        assertEquals("plugins", history.sourceFor("capability-b"))
        history = history.pop()
        assertEquals("capability-a", history.sourceFor("plugins"))
        history = history.pop()
        assertTrue(history.sources.isEmpty())
        assertTrue(history.targets.isEmpty())
    }

    @Test fun openingThePreviousOriginCollapsesTheReturnCycle() {
        val history = SettingsShortcutHistory().open("plugins", "capability").open("capability", "plugins")
        assertTrue(history.sources.isEmpty())
        assertNull(history.sourceFor("plugins"))
    }
}
