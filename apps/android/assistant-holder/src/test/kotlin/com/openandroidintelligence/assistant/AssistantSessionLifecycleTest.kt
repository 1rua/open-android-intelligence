package com.openandroidintelligence.assistant

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 助理展示生命周期的线上契约：进入声明可见、退出先撤销再断开、且从不取消生成。
 *
 * 这三条在真机上分别对应三种很难复现的缺陷：原生气泡与浮层重复提醒、退出时
 * 可见性来不及撤销、以及"关掉浮层顺带掐断正在输出的回复"。这里直接锁定发出
 * 的命令序列。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantSessionLifecycleTest {
    private val sent = mutableListOf<Bundle>()
    private var client: AssistantBridgeClient? = null

    /** 记录命令的假服务端；connect 时回一帧状态，模拟主进程下发 token。 */
    private class BridgeContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            this.connection = connection
            return true
        }

        override fun unbindService(connection: ServiceConnection) {}
    }

    private fun boundClient(): Pair<AssistantBridgeClient, BridgeContext> {
        val context = BridgeContext()
        val target = AssistantBridgeClient(context).also { client = it }
        val bridge = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                sent += message.data
                if (message.data.getString("command") == "connect") {
                    message.replyTo?.send(
                        android.os.Message.obtain().apply {
                            data = Bundle().apply {
                                putInt("version", com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION)
                                putString("token", "test-session")
                                putString("state", """{"version":2,"revision":1,"connected":true}""")
                            }
                        },
                    )
                }
                true
            },
        )
        target.connect()
        context.connection.onServiceConnected(ComponentName(context, "TestBridge"), bridge.binder)
        shadowOf(Looper.getMainLooper()).idle()
        return target to context
    }

    @After fun cleanup() {
        client?.dispose()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun commands() = sent.map { it.getString("command") }

    @Test fun enterBindsAndDeclaresPresence() {
        val context = BridgeContext()
        val target = AssistantBridgeClient(context).also { client = it }

        AssistantSessionLifecycle.enter(target)
        // 绑定是异步的：服务端还没回 onServiceConnected，此时一条命令也发不出去。
        assertEquals(emptyList<String>(), commands())

        val bridge = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                sent += message.data
                if (message.data.getString("command") == "connect") {
                    message.replyTo?.send(
                        android.os.Message.obtain().apply {
                            data = Bundle().apply {
                                putInt("version", com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION)
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

        // 首帧回来后补发可见性：宿主不需要自己重试。
        assertEquals(listOf("connect", "presence"), commands())
        assertTrue(sent.single { it.getString("command") == "presence" }.getBoolean("visible"))
    }

    @Test fun leaveRevokesPresenceBeforeDisconnecting() {
        val (target, _) = boundClient()

        AssistantSessionLifecycle.leave(target)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("connect", "presence", "disconnect"), commands())
        // 先 presence(false)，再 disconnect：断开后没有任何通道能撤销可见性。
        assertEquals(false, sent[1].getBoolean("visible"))
        // 退出绝不发 cancel：已提交的生成由主进程继续。
        assertTrue("dispose must never cancel generation", "cancel" !in commands())
    }

    @Test fun repeatedVisibleDeclarationDoesNotSpamTheChannel() {
        val (target, _) = boundClient()

        target.setPresence(true)   // 首次声明：真正送达
        target.setPresence(true)   // 与主进程已知状态一致，不重复发
        target.setPresence(true)
        shadowOf(Looper.getMainLooper()).idle()

        // 只有第一次 setPresence 会产生命令；重复声明不会刷屏状态通道。
        assertEquals(1, commands().count { it == "presence" })
    }

    @Test fun presenceRestoredAfterRebinding() {
        val (target, context) = boundClient()
        target.setPresence(true)
        shadowOf(Looper.getMainLooper()).idle()

        // 服务重启：新连接在主进程侧从 visible=false 开始，可见性必须重发。
        val second = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                sent += message.data
                if (message.data.getString("command") == "connect") {
                    message.replyTo?.send(
                        android.os.Message.obtain().apply {
                            data = Bundle().apply {
                                putInt("version", com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION)
                                putString("token", "test-session-2")
                                putString("state", """{"version":2,"revision":1,"connected":true}""")
                            }
                        },
                    )
                }
                true
            },
        )
        context.connection.onServiceConnected(ComponentName(context, "Second"), second.binder)
        shadowOf(Looper.getMainLooper()).idle()

        val presenceCommands = sent.filter { it.getString("command") == "presence" }
        assertTrue("re-binding must re-declare presence", presenceCommands.size >= 2)
        assertTrue(presenceCommands.all { it.getBoolean("visible") })
    }
}
