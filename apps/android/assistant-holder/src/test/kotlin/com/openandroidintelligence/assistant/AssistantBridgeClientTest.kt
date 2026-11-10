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
import android.os.ParcelFileDescriptor
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_ALLOWED_KEYS
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.MAX_COMPOSER_ATTACHMENTS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

/**
 * 桥接客户端 v2 协议的线上契约回归。
 *
 * 握手失败（客户端发 v1、主进程只认 v2）在真机上只表现为"永远连不上"，很难定位；
 * 这里锁定客户端实际发出的 `version`、命令字面量与字段名，与
 * `AssistantBridgeService` 的白名单一一对应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantBridgeClientTest {
    private val sent = mutableListOf<Bundle>()

    /** 客户端注册的回信 Messenger；假服务端用它下发状态帧。 */
    private var reply: Messenger? = null
    private var client: AssistantBridgeClient? = null

    /** 假服务端：记录每条命令，并在 connect 时回一帧状态。 */
    private class BridgeContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            this.connection = connection
            return true
        }

        override fun unbindService(connection: ServiceConnection) {}
    }

    private fun connect(
        replyState: String? = """{"version":2,"revision":1,"connected":true}""",
    ): AssistantBridgeClient {
        val context = BridgeContext()
        val target = AssistantBridgeClient(context).also { client = it }
        target.connect()
        val bridge = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                sent += message.data
                reply = message.replyTo
                if (message.data.getString("command") == "connect" && replyState != null) {
                    message.replyTo?.send(frame(ASSISTANT_BRIDGE_VERSION, replyState))
                }
                true
            },
        )
        context.connection.onServiceConnected(ComponentName(context, "TestBridge"), bridge.binder)
        shadowOf(Looper.getMainLooper()).idle()
        return target
    }

    private fun frame(version: Int, state: String): Message = Message.obtain().apply {
        data = Bundle().apply {
            putInt("version", version)
            putString("token", "test-session")
            putString("state", state)
        }
    }

    private fun publish(version: Int, state: String) {
        reply?.send(frame(version, state))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun cleanup() {
        client?.dispose()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun handshakeAndEveryCommandCarryTheV2VersionAndWhitelistedFields() {
        val target = connect()
        assertTrue(target.state.value.connected)

        target.sessionNew()
        target.setPresence(true)
        target.send()
        target.cancel()
        target.updateDraft("草稿")
        target.removeAttachment("draft-1")
        target.decideApproval("apr-1", ApprovalChoice.ONCE)
        target.retry(AssistantRetryTarget.TIMELINE)
        target.retry(AssistantRetryTarget.SUBMISSION)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(
                "connect", "session.new", "presence", "send", "cancel", "draft",
                "attachment.remove", "approval", "retry", "retry",
            ),
            sent.map { it.getString("command") },
        )
        // 每条命令都必须是 v2：客户端与服务端的版本判断是握手能否成立的全部依据。
        assertTrue(sent.all { it.getInt("version") == ASSISTANT_BRIDGE_VERSION })
        // 连接后的命令都要带上服务端下发的 token。
        assertTrue(sent.drop(1).all { it.getString("token") == "test-session" })

        assertTrue(sent[2].getBoolean("visible"))
        assertEquals("草稿", sent[5].getString("text"))
        assertEquals("draft-1", sent[6].getString("draftId"))
        assertEquals("apr-1", sent[7].getString("approvalId"))
        assertEquals("once", sent[7].getString("choice"))
        assertEquals("timeline", sent[8].getString("target"))
        assertEquals("submission", sent[9].getString("target"))
    }

    /**
     * 每条命令的 Bundle 键都必须落在**两端共用的**白名单里。
     *
     * 守卫必须真正触发全部命令：早先的用例只发了旧命令，`handoff` 与 `attachment`
     * 两条新命令的字段（`uris` / `count`）从没被这条断言覆盖过，等于新命令不受保护。
     * 白名单直接取协议常量，不再在测试里手写第二份。
     */
    @Test fun commandsOnlyContainFieldsTheServiceAccepts() {
        val target = connect()
        target.sessionNew()
        target.setPresence(false)
        target.updateDraft("x")
        target.removeAttachment("draft-1")
        target.decideApproval("apr-1", ApprovalChoice.DENY)
        target.retry(AssistantRetryTarget.TIMELINE)
        target.requestHandoff()
        target.attachFiles(listOf("content://media/picked/1"))
        shadowOf(Looper.getMainLooper()).idle()

        // 覆盖检查本身要成立：新命令确实发出去了，白名单断言才有意义。
        val commands = sent.map { it.getString("command") }
        assertTrue("交接命令必须被触发：$commands", "handoff" in commands)
        assertTrue("附件命令必须被触发：$commands", "attachment" in commands)

        val allowed = ASSISTANT_BRIDGE_ALLOWED_KEYS
        assertTrue(sent.isNotEmpty())
        assertTrue(sent.all { allowed.containsAll(it.keySet()) })
    }

    @Test fun unknownApprovalChoiceOrBlankIdIsNeverSent() {
        val target = connect()
        assertFalse(target.decideApproval("apr-1", ApprovalChoice.UNKNOWN))
        assertFalse(target.decideApproval("", ApprovalChoice.ONCE))
        assertFalse(target.removeAttachment(""))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("connect"), sent.map { it.getString("command") })
    }

    @Test fun v1StateFramesAreDroppedAndThePreviousFrameIsKept() {
        val target = connect()
        val before = target.state.value
        assertTrue(before.connected)

        // 旧协议帧整帧丢弃：宁可停在上一帧，也不把 v1 字段误读成 v2。
        publish(1, """{"connected":true,"consent":true,"draft":"v1 草稿"}""")
        assertEquals(before, target.state.value)

        // 版本对但 JSON 坏的帧同样丢弃。
        publish(ASSISTANT_BRIDGE_VERSION, "不是 JSON")
        assertEquals(before, target.state.value)
    }

    @Test fun staleRevisionsNeverRollTheStateBack() {
        val target = connect(
            replyState = """{"version":2,"revision":5,"connected":true,"draft":"新"}""",
        )
        assertEquals("新", target.state.value.draft)
        assertEquals(5L, target.state.value.revision)

        publish(ASSISTANT_BRIDGE_VERSION, """{"version":2,"revision":4,"connected":true,"draft":"旧"}""")
        assertEquals("新", target.state.value.draft)

        publish(ASSISTANT_BRIDGE_VERSION, """{"version":2,"revision":6,"connected":true,"draft":"更新"}""")
        assertEquals("更新", target.state.value.draft)
        assertEquals(6L, target.state.value.revision)
    }

    @Test fun rebindingAcceptsTheNewSessionsFirstFrameAgain() {
        val context = BridgeContext()
        val target = AssistantBridgeClient(context).also { client = it }
        target.connect()
        val firstServer = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                sent += message.data
                reply = message.replyTo
                if (message.data.getString("command") == "connect") {
                    message.replyTo?.send(
                        frame(ASSISTANT_BRIDGE_VERSION, """{"version":2,"revision":9,"connected":true,"draft":"旧会话"}"""),
                    )
                }
                true
            },
        )
        context.connection.onServiceConnected(ComponentName(context, "First"), firstServer.binder)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("旧会话", target.state.value.draft)

        // 服务重启后新会话的序号又从 1 开始：重新绑定必须接受它，而不是被旧门槛挡掉。
        val secondServer = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                sent += message.data
                reply = message.replyTo
                if (message.data.getString("command") == "connect") {
                    message.replyTo?.send(
                        frame(ASSISTANT_BRIDGE_VERSION, """{"version":2,"revision":1,"connected":true,"draft":"新会话"}"""),
                    )
                }
                true
            },
        )
        context.connection.onServiceConnected(ComponentName(context, "Second"), secondServer.binder)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("新会话", target.state.value.draft)
        assertEquals(1L, target.state.value.revision)
    }

    // ---- 交接与文件附件 ----

    @Test fun handoffCommandCarriesNoExtraFields() {
        val target = connect()

        assertTrue(target.requestHandoff())
        shadowOf(Looper.getMainLooper()).idle()

        val handoff = sent.single { it.getString("command") == "handoff" }
        assertEquals(
            "交接命令不需要新字段：会话身份由主进程已持有的 token 决定",
            setOf("version", "token", "command"),
            handoff.keySet(),
        )
    }

    @Test fun fileAttachmentsSendOnlyUriStringsInBoundedBatches() {
        val target = connect()

        assertTrue(target.attachFiles(listOf(" content://a/1 ", "content://a/2", "content://a/1")))
        shadowOf(Looper.getMainLooper()).idle()

        val attachment = sent.single { it.getString("command") == "attachment" }
        // 去重后只剩两个；数组按插入顺序保留。
        assertEquals(listOf("content://a/1", "content://a/2"), attachment.getStringArray("uris")?.toList())
        assertEquals(2, attachment.getInt("count"))
        // 只传 URI 与数量：不传字节、路径或 MIME。
        assertEquals(
            setOf("version", "token", "command", "uris", "count"),
            attachment.keySet(),
        )

        // 空列表与超过上限的批次直接拒绝，不静默裁剪。
        assertFalse(target.attachFiles(emptyList()))
        assertFalse(target.attachFiles(List(MAX_COMPOSER_ATTACHMENTS + 1) { "content://a/$it" }))
    }

    @Test fun voiceCarriesBytesMetadataAndReleasesTheLocalDescriptor() {
        val target = connect()
        val file = File.createTempFile("voice_", ".m4a").apply { writeBytes(ByteArray(42) { it.toByte() }) }
        val content = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        assertTrue(target.sendVoice(content, size = 42, durationMs = 1_500L, mediaType = "audio/mp4"))
        shadowOf(Looper.getMainLooper()).idle()

        val voice = sent.single { it.getString("command") == "voice" }
        assertEquals(ASSISTANT_BRIDGE_VERSION, voice.getInt("version"))
        assertEquals(42, voice.getInt("size"))
        assertEquals(1_500L, voice.getLong("durationMs"))
        assertEquals("audio/mp4", voice.getString("mediaType"))
        assertEquals("test-session", voice.getString("token"))
        // 本地描述符随调用释放；`fd` 在已关闭时抛 IllegalStateException。
        assertTrue(runCatching { content.fd }.isFailure)
        file.delete()
    }

    @Test fun voiceWithoutAnActiveConnectionIsRejectedAndReleasesTheDescriptor() {
        val target = connect(replyState = """{"version":2,"revision":1,"connected":false}""")
        val pipe = ParcelFileDescriptor.createPipe()

        assertFalse(target.sendVoice(pipe[0], size = 8, durationMs = 100L, mediaType = "audio/mp4"))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(sent.none { it.getString("command") == "voice" })
        assertTrue(runCatching { pipe[0].fd }.isFailure)
        runCatching { pipe[1].close() }
    }
}
