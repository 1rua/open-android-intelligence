package com.openandroidintelligence.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.openandroidintelligence.control.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidTreeNodeTest {
    @Suppress("DEPRECATION")
    @Test fun platformNodePropertiesAreReadAndPasswordsAreRedacted() {
        val raw = AccessibilityNodeInfo.obtain().apply {
            text = "private password"
            contentDescription = "private description"
            viewIdResourceName = "org.example:id/password"
            className = "android.widget.EditText"
            isVisibleToUser = true
            isEditable = true
            isEnabled = true
            isPassword = true
            setBoundsInScreen(Rect(10, 20, 300, 80))
        }
        val node = AndroidTreeNode(raw)
        try {
            val snapshot = ScreenTreeReader().capture(listOf(ScreenTreeWindow(
                ScreenWindow(7, 1, 0, true, true, ScreenBounds(0, 0, 1080, 1920)), node)),
                ScreenDisplay(1080, 1920, 0), 1, "org.example", 7)
            val record = snapshot.nodes.single()
            assertEquals("w7:0", record.nodeId)
            assertEquals("org.example:id/password", record.resourceId)
            assertEquals(ScreenBounds(10, 20, 300, 80), record.bounds)
            assertTrue(record.editable)
            assertTrue(record.password)
            assertNull(record.text)
            assertNull(record.description)
        } finally { node.close() }
    }
}
