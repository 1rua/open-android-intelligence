package com.openandroidintelligence.mobile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compare the existing plain-string renderer with the full text-layout callback path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTextLayoutSignalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun layoutSignalChecksVisibleGlyphBoundsWithoutChangingTheProductionText() {
        val callbacks = mutableMapOf<String, TextLayoutResult>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Column(Modifier.width(220.dp)) {
                        listOf("外观与交互", "系统动态取色").forEachIndexed { index, label ->
                            Box(Modifier.fillMaxWidth()) { Text(label, Modifier.testTag("plain-$index"), style = MaterialTheme.typography.bodyLarge) }
                            Box(Modifier.fillMaxWidth()) { Text(label, Modifier.testTag("callback-$index"), style = MaterialTheme.typography.bodyLarge, onTextLayout = { callbacks[label] = it }) }
                        }
                    }
                }
            }
        }
        listOf("外观与交互", "系统动态取色").forEachIndexed { index, label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("plain-$index").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val plain = layouts.single()
            val callback = checkNotNull(callbacks[label])
            println("$label plain=${plain.size} paragraph=${plain.multiParagraph.width} lines=${plain.lineCount} overflow=${plain.hasVisualOverflow}; callback=${callback.size} paragraph=${callback.multiParagraph.width} overflow=${callback.hasVisualOverflow}")
            assertEquals(label.length, plain.getLineEnd(plain.lineCount - 1, visibleEnd = true))
            assertFalse(callback.hasVisualOverflow)
            for (line in 0 until plain.lineCount) {
                assertFalse(plain.isLineEllipsized(line))
                assertTrue(plain.getLineLeft(line) >= -1f)
                assertTrue("actual glyph end ${plain.getLineRight(line)} exceeds ${plain.size.width}", plain.getLineRight(line) <= plain.size.width + 1f)
            }
        }
    }
}
