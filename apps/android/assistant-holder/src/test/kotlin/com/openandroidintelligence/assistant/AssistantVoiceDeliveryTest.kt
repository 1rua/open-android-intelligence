package com.openandroidintelligence.assistant

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION
import com.openandroidintelligence.conversation.voice.RecordedVoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

/**
 * 录音交付与拒绝提示的回归。
 *
 * 钉死三件事：
 * 1. 一段结束的录音会以 `voice` 命令、真实字节数与时长交给主进程，而不是被静默丢弃；
 * 2. 交付后临时文件立即删除（不留第二份音频）；
 * 3. 每种拒绝原因都有可读的中文结论，录音态映射只认 `Recording`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantVoiceDeliveryTest {
    @get:Rule val temp = TemporaryFolder()

    private val sent = mutableListOf<Bundle>()
    private var reply: Messenger? = null
    private var client: AssistantBridgeClient? = null

    private class BridgeContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            this.connection = connection
            return true
        }

        override fun unbindService(connection: ServiceConnection) {}
    }

    private fun connect(): AssistantBridgeClient {
        val context = BridgeContext()
        val target = AssistantBridgeClient(context).also { client = it }
        target.connect()
        val bridge = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                sent += message.data
                reply = message.replyTo
                if (message.data.getString("command") == "connect") {
                    message.replyTo?.send(
                        Message.obtain().apply {
                            data = Bundle().apply {
                                putInt("version", ASSISTANT_BRIDGE_VERSION)
                                putString("token", "test-session")
                                putString("state", """{"version":2,"revision":1,"connected":true}""")
                            }
                        },
                    )
                }
                true
            },
        )
        context.connection.onServiceConnected(ComponentName(context, "TestBridge"), bridge.binder)
        shadowOf(Looper.getMainLooper()).idle()
        return target
    }

    private fun recordedVoice(bytes: ByteArray, durationMs: Long = 1_800L): Pair<RecordedVoice, File> {
        val file = temp.newFile("voice_send.m4a").apply { writeBytes(bytes) }
        return RecordedVoice(file, durationMs) to file
    }

    @After fun cleanup() {
        client?.dispose()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aFinishedVoiceIsDeliveredWithItsRealBytesAndDuration() {
        val target = connect()
        val bytes = ByteArray(64) { it.toByte() }
        val (voice, file) = recordedVoice(bytes)

        sendRecordedVoice(target, voice, handler = Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()

        val command = sent.single { it.getString("command") == "voice" }
        assertEquals(bytes.size, command.getInt("size"))
        assertEquals(1_800L, command.getLong("durationMs"))
        assertEquals("audio/mp4", command.getString("mediaType"))
        assertEquals("test-session", command.getString("token"))
        assertFalse("交付后临时音频必须删除", file.exists())
    }

    @Test fun anUnreadableVoiceIsReportedInsteadOfSentAsAnEmptyAttachment() {
        val target = connect()
        val (voice, _) = recordedVoice(ByteArray(0))

        sendRecordedVoice(target, voice, handler = Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(sent.none { it.getString("command") == "voice" })
        assertEquals("录音无法读取，请重新录制", target.state.value.advisory)
    }

    @Test fun deliveryWithoutAConnectionSendsNothingAndStillReleasesTheTemporaryFile() {
        val target = connect()
        // 断开桥接：sendVoice 会拒绝并把本地描述符关掉，不伪造一次已发送。
        target.dispose()
        client = null
        val (voice, file) = recordedVoice(ByteArray(16) { 1 })

        sendRecordedVoice(target, voice, handler = Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(sent.none { it.getString("command") == "voice" })
        assertFalse(file.exists())
        assertNull(target.state.value.advisory)
    }

    @Test fun voiceDeliveryNeverTouchesTheTextDraftOrItsAttachments() {
        val target = connect()
        val (voice, _) = recordedVoice(ByteArray(32) { 2 })

        sendRecordedVoice(target, voice, handler = Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()

        // 语音是独立的附件型单条消息：不得顺手清空草稿或移除附件。
        val commands = sent.mapNotNull { it.getString("command") }
        assertEquals(listOf("connect", "voice"), commands)
        assertTrue(sent.none { it.containsKey("text") || it.containsKey("draftId") })
    }

    @Test fun everyRejectionHasItsOwnReadableConclusion() {
        val messages = AssistantVoiceRejection.entries.map(::assistantVoiceRejectionMessage)

        assertTrue(messages.all { it.isNotBlank() })
        assertEquals(messages.size, messages.distinct().size)
        assertEquals("会话已切换，录音未发送", assistantVoiceRejectionMessage(AssistantVoiceRejection.SESSION_CHANGED))
    }

    @Test fun onlyAnActiveRecordingMapsToTheRecordingUi() {
        assertNull(AssistantVoiceState.Idle.toCompactRecording())
        assertNull(AssistantVoiceState.Sending("thread_a").toCompactRecording())

        val recording = AssistantVoiceState.Recording(sessionKey = "thread_a", elapsedMs = 2_400L, level = 0.4f)
            .toCompactRecording()
        assertEquals(2_400L, requireNotNull(recording).elapsedMs)
        assertEquals(0.4f, recording.level, 0f)
        assertNotEquals(0f, recording.level)
    }
}
