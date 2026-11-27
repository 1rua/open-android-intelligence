package com.openandroidintelligence.assistant

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * ASSIST 兜底入口的可见性接线。
 *
 * 兜底页和系统助理会话共用同一套桥接语义：前台期间声明「正在展示当前会话」，
 * 退到后台立即撤销，且**从不**发 `cancel`。这里用真实的 Activity 生命周期
 * 驱动，锁定 onStart/onResume/onPause/onStop 的实际命令序列。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantActivityPresenceTest {
    private val sent = mutableListOf<Bundle>()

    private fun replyFrame(): Message = Message.obtain().apply {
        data = Bundle().apply {
            putInt("version", ASSISTANT_BRIDGE_VERSION)
            putString("token", "test-session")
            putString("state", """{"version":2,"revision":1,"connected":true}""")
        }
    }

    /** 用假服务端完成绑定；返回后客户端已收到首帧。 */
    private fun attachFakeBridge(application: Application) {
        val bridge = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                sent += message.data
                if (message.data.getString("command") == "connect") message.replyTo?.send(replyFrame())
                true
            },
        )
        val connection = shadowOf(application).boundServiceConnections.single()
        connection.onServiceConnected(ComponentName(application, "TestBridge"), bridge.binder)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun commands() = sent.map { it.getString("command") }

    @Test fun theFallbackEntryDeclaresAndRevokesPresenceAcrossStartStop() {
        val controller = Robolectric.buildActivity(AssistantActivity::class.java).setup()
        val application = RuntimeEnvironment.getApplication()
        attachFakeBridge(application)

        // 首帧到达后补发：界面在前台，主进程应抑制同会话的原生气泡。
        assertTrue("presence must be declared after the first frame", "presence" in commands())
        assertTrue(sent.last { it.getString("command") == "presence" }.getBoolean("visible"))

        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            false,
            sent.last { it.getString("command") == "presence" }.getBoolean("visible"),
        )
        // 退出只撤销展示，不取消任何已提交的生成。
        assertTrue("the fallback entry must never cancel generation", "cancel" !in commands())

        controller.destroy()
    }

    @Test fun resumingAgainRedeclaresPresenceWithoutDuplicateFrames() {
        val controller = Robolectric.buildActivity(AssistantActivity::class.java).setup()
        attachFakeBridge(RuntimeEnvironment.getApplication())
        val before = commands().count { it == "presence" }

        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        // onStart 声明一次，onResume 的重复声明被客户端去重。
        assertEquals(before + 2, commands().count { it == "presence" })
        assertTrue(sent.last { it.getString("command") == "presence" }.getBoolean("visible"))

        controller.destroy()
    }
}
