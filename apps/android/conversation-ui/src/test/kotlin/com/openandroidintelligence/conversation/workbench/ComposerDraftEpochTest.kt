package com.openandroidintelligence.conversation.workbench

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions.RequestFocus
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.MessageDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class ComposerDraftEpochTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inputImmediatelyBeforeSendSubmitsTheCurrentEditorSnapshot() {
        var draft by mutableStateOf("before")
        var epoch by mutableStateOf(4L)
        var submitted: Triple<String, Long, MessageDeliveryMode>? = null
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = draft,
                    draftEpoch = epoch,
                    onDraftChange = { draft = it },
                    onDraftEdit = { text, expectedEpoch ->
                        if (expectedEpoch == epoch) draft = text
                    },
                    onSubmitDraft = { text, expectedEpoch, delivery ->
                        submitted = Triple(text, expectedEpoch, delivery)
                        if (expectedEpoch == epoch) {
                            draft = ""
                            epoch += 1L
                            epoch
                        } else null
                    },
                    generation = GenerationState.IDLE,
                    canSend = true,
                    onSend = { error("应通过带代次的提交接口发送") },
                    onStop = {},
                    onPickCamera = {},
                    onPickGallery = {},
                    onPickDocument = {},
                    onVoiceInput = {},
                )
            }
        }
        compose.onNode(hasSetTextAction()).performSemanticsAction(RequestFocus) { it() }
        val replace = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsActions.SetText].action ?: error("输入动作不可用")
        val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action ?: error("发送动作不可用")

        compose.runOnIdle {
            replace(AnnotatedString("latest /命令  "))
            send()
        }

        assertEquals(Triple("latest /命令  ", 4L, MessageDeliveryMode.NORMAL), submitted)
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    @Test
    fun typingAfterAcceptedSendUsesTheReturnedEpochAndSurvivesOldProps() {
        var voiceEnabled by mutableStateOf(true)
        val edits = mutableListOf<Pair<String, Long>>()
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = "first",
                    draftEpoch = 4L,
                    onDraftChange = { error("应通过带代次的编辑接口上报") },
                    onDraftEdit = { text, epoch -> edits += text to epoch },
                    onSubmitDraft = { _, _, _ ->
                        // The controller has frozen this draft; its old render is still on screen.
                        voiceEnabled = false
                        41L
                    },
                    generation = GenerationState.IDLE,
                    canSend = true,
                    onSend = { error("应通过带代次的提交接口发送") },
                    onStop = {},
                    onPickCamera = {},
                    onPickGallery = {},
                    onPickDocument = {},
                    onVoiceInput = {},
                    voiceInputEnabled = voiceEnabled,
                )
            }
        }
        compose.onNode(hasSetTextAction()).performSemanticsAction(RequestFocus) { it() }
        val insert = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsActions.InsertTextAtCursor].action ?: error("输入动作不可用")
        val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action ?: error("发送动作不可用")

        compose.runOnIdle {
            send()
            insert(AnnotatedString("next"))
        }

        assertEquals("提交后的输入必须属于返回的新代次", listOf("next" to 41L), edits)
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("next")),
        )
    }

    @Test
    fun undoImmediatelyBeforeSendSubmitsTheCurrentEditorText() {
        var draft by mutableStateOf("A")
        var sent: String? = null
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = draft,
                    onDraftChange = { draft = it },
                    generation = com.openandroidintelligence.conversation.model.GenerationState.IDLE,
                    canSend = true,
                    onSend = { sent = draft },
                    onStop = {},
                    onPickCamera = {},
                    onPickGallery = {},
                    onPickDocument = {},
                    onVoiceInput = {},
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextReplacement("AB")
        assertEquals("AB", draft)
        compose.onNode(hasSetTextAction()).performSemanticsAction(RequestFocus) { it() }
        val send = compose.onNodeWithContentDescription("发送")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action
            ?: error("发送动作不可用")

        compose.runOnIdle {
            dispatchUndoKey()
            send()
        }

        assertEquals("发送必须读取撤销后的编辑器快照", "A", sent)
        compose.onNode(hasSetTextAction()).assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("A"),
            ),
        )
    }

    @Test
    fun failedSubmitKeepsTheEditorAndItsEpoch() {
        var draft by mutableStateOf("original")
        val edits = mutableListOf<Pair<String, Long>>()
        var submitted: String? = null
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = draft, draftEpoch = 8L,
                    onDraftChange = { draft = it },
                    onDraftEdit = { text, epoch -> edits += text to epoch; draft = text },
                    onSubmitDraft = { text, _, _ -> submitted = text; null },
                    generation = GenerationState.IDLE, canSend = true,
                    onSend = {}, onStop = {}, onPickCamera = {}, onPickGallery = {},
                    onPickDocument = {}, onVoiceInput = {},
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("失败后保留  ")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals("失败后保留  ", submitted)
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("失败后保留  ")),
        )
        compose.onNode(hasSetTextAction()).performTextInput("继续")
        assertEquals("失败后保留  继续" to 8L, edits.last())
    }

    @Test fun queueSubmitsTheCurrentEditorSnapshot() = assertBusySnapshot(MessageDeliveryMode.QUEUE)

    @Test fun steerSubmitsTheCurrentEditorSnapshot() = assertBusySnapshot(MessageDeliveryMode.STEER)

    private fun assertBusySnapshot(mode: MessageDeliveryMode) {
        var submitted: Triple<String, Long, MessageDeliveryMode>? = null
        val text = " /host-command 'a b'\n 保留末尾空白  "
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = "before", draftEpoch = 8L,
                    onDraftChange = {}, onDraftEdit = { _, _ -> },
                    onSubmitDraft = { snapshot, epoch, delivery ->
                        submitted = Triple(snapshot, epoch, delivery)
                        17L
                    },
                    generation = GenerationState.RUNNING, canSend = true,
                    onSend = { error("不得走旧发送回调") }, onQueue = { error("不得走旧排队回调") },
                    onSteer = { error("不得走旧引导回调") },
                    onStop = {}, onPickCamera = {}, onPickGallery = {}, onPickDocument = {}, onVoiceInput = {},
                )
            }
        }
        compose.onNodeWithContentDescription(
            if (mode == MessageDeliveryMode.QUEUE) "排队等待当前任务完成" else "引导当前任务",
        ).performClick()
        compose.onNode(hasSetTextAction()).performSemanticsAction(RequestFocus) { it() }
        val replace = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsActions.SetText].action ?: error("输入动作不可用")
        val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action ?: error("发送动作不可用")
        compose.runOnIdle { replace(AnnotatedString(text)); send() }
        assertEquals(Triple(text, 8L, mode), submitted)
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        ).assertIsFocused()
    }

    private fun dispatchUndoKey() {
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            compose.activity.dispatchKeyEvent(object : android.view.KeyEvent(
                time,
                time,
                action,
                android.view.KeyEvent.KEYCODE_Z,
                0,
                android.view.KeyEvent.META_CTRL_ON,
            ) {
                override fun getUnicodeChar() = 0
            })
        }
    }
}
