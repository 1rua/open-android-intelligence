package com.openandroidintelligence.conversation.workbench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
@OptIn(ExperimentalTestApi::class)
class ComposerImagePasteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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

    @Test fun switchingThreadsWithIdenticalDraftsResetsSelectionAndUndoHistory() {
        val controller = Any()
        val destination = mutableStateOf<Any?>(controller to "thread-a")
        val draft = mutableStateOf("previous conversation text")
        render(draft, destination) {}
        compose.onNode(hasSetTextAction()).performTextReplacement("same draft")
        undo()
        compose.runOnIdle { assertEquals("previous conversation text", draft.value) }
        compose.onNode(hasSetTextAction()).performTextReplacement("same draft")
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(0))
        compose.runOnIdle { destination.value = controller to "thread-b" }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.TextSelectionRange, TextRange("same draft".length)))
        undo()
        compose.runOnIdle { assertEquals("same draft", draft.value) }
        compose.onNode(hasSetTextAction()).performTextInput("!")
        compose.runOnIdle { assertEquals("same draft!", draft.value) }
    }

    @Test fun replacingTheAccountControllerResetsEditingStateEvenWithTheSameThreadIdAndDraft() {
        val destination = mutableStateOf<Any?>(Any() to "thread-a")
        val draft = mutableStateOf("same draft")
        render(draft, destination) {}
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(0, 4))
        compose.runOnIdle { destination.value = Any() to "thread-a" }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.TextSelectionRange, TextRange("same draft".length)))
    }

    @Test fun rapidEditsAndExternalSendClearCannotBeOverwrittenByDelayedEditorReports() {
        val draft = mutableStateOf("")
        render(draft) {}
        val replace = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle {
            replace(AnnotatedString("temporary"))
            replace(AnnotatedString(""))
            assertEquals("", draft.value)
        }
        compose.runOnIdle {
            replace(AnnotatedString("quick edit"))
            dispatchUndoKey()
        }
        compose.runOnIdle { assertEquals("", draft.value) }
        compose.runOnIdle {
            replace(AnnotatedString("to send"))
            assertEquals("to send", draft.value)
            draft.value = ""
        }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("")))
        undo()
        compose.runOnIdle { assertEquals("", draft.value) }
    }

    private fun render(draft: MutableState<String>, destination: State<Any?>? = null, onImages: (PastedImageContent) -> Unit) {
        compose.setContent {
            MaterialTheme {
                ComposerBar(draft.value, { draft.value = it }, GenerationState.IDLE, true, {}, {}, {}, {}, {}, {},
                    onPasteImages = onImages, editorKey = destination?.value)
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

    private fun undo() {
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { dispatchUndoKey() }
    }

    private fun dispatchUndoKey() {
        // Robolectric's character map returns printable 'z' even with Ctrl. On Android,
        // Ctrl+Z is a control character, so Compose must route it to the undo key mapping.
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            compose.activity.dispatchKeyEvent(object : android.view.KeyEvent(time, time, action,
                android.view.KeyEvent.KEYCODE_Z, 0, android.view.KeyEvent.META_CTRL_ON) {
                override fun getUnicodeChar() = 0
            })
        }
    }
}
