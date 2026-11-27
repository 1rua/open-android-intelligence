package com.openandroidintelligence.conversation.workbench

import android.content.res.Configuration
import com.openandroidintelligence.ui.resources.R as UiR
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationTitleResolverTest {
    @Test
    fun onlyEmptyTitlesRepresentAnUnnamedConversation() {
        assertTrue(ConversationTitleResolver.isPlaceholderTitle(null))
        assertTrue(ConversationTitleResolver.isPlaceholderTitle("  "))
        assertFalse(ConversationTitleResolver.isPlaceholderTitle("新对话"))
        assertFalse(ConversationTitleResolver.isPlaceholderTitle("用户标题"))
    }

    @Test
    fun missingTranslationsFallBackToChineseAndKeepNamedTitlesUntouched() {
        val application = RuntimeEnvironment.getApplication()
        for (locale in listOf(Locale.ENGLISH, Locale.FRENCH)) {
            val configuration = Configuration(application.resources.configuration).apply {
                setLocale(locale)
            }
            val context = application.createConfigurationContext(configuration)
            assertEquals("新对话", context.getString(UiR.string.conv_title_new_default))
            assertEquals("新对话", ConversationTitleResolver.resolve("", context))
            for (title in listOf("用户标题", "Agent 生成的标题", "服务端标题", "新对话")) {
                assertEquals(title, ConversationTitleResolver.resolve(title, context))
            }
        }
    }
}
