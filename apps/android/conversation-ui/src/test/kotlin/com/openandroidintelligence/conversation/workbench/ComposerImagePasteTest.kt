package com.openandroidintelligence.conversation.workbench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.GenerationState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerImagePasteTest {
    @get:Rule val compose = createComposeRule()
    private val image = Uri.parse("content://clipboard/images/1")

    @Test fun nativePasteAddsImagesToAttachmentsAndKeepsTheTextDraft() {
        val received = mutableListOf<Uri>()
        val draft = mutableStateOf("未发送的文字")
        render(draft) { received += it.uris }
        paste(ClipData("image", arrayOf("image/png"), ClipData.Item(image)))
        compose.runOnIdle {
            assertEquals(listOf(image), received)
            assertEquals("未发送的文字", draft.value)
        }
    }

    @Test fun mixedImageAndTextPasteKeepsTextEditingAndExternalDraftUpdatesWorking() {
        val received = mutableListOf<Uri>()
        val draft = mutableStateOf("")
        render(draft) { received += it.uris }
        paste(ClipData("mixed", arrayOf("image/png", "text/plain"), ClipData.Item(image)).apply {
            addItem(ClipData.Item("图片说明"))
        })
        compose.runOnIdle {
            assertEquals(listOf(image), received)
            assertEquals("图片说明", draft.value)
            draft.value = ""
        }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNode(hasSetTextAction()).performTextInput("/help")
        compose.runOnIdle { assertEquals("/help", draft.value) }
    }

    @Test fun ordinaryTextPasteRemainsTextAndDoesNotAddAttachments() {
        val received = mutableListOf<Uri>()
        val draft = mutableStateOf("")
        render(draft) { received += it.uris }
        paste(ClipData.newPlainText("text", "正常文本"))
        compose.runOnIdle {
            assertEquals("正常文本", draft.value)
            assertTrue(received.isEmpty())
        }
    }

    private fun render(draft: MutableState<String>, onImages: (PastedImageContent) -> Unit) {
        compose.setContent {
            MaterialTheme {
                ComposerBar(draft.value, { draft.value = it }, GenerationState.IDLE, true, {}, {}, {}, {}, {}, {},
                    onPasteImages = onImages)
            }
        }
    }

    private fun paste(clip: ClipData) {
        compose.onNode(hasSetTextAction()).performClick()
        compose.runOnIdle {
            (RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.waitForIdle()
    }
}
