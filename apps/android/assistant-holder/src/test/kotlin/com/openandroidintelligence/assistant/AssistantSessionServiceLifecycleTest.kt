package com.openandroidintelligence.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION
import org.junit.After
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
import org.robolectric.android.controller.ServiceController

/**
 * 系统助理会话的生命周期动作。
 *
 * `onShow` 是一次新的系统唤起：会话必须声明「正在展示当前会话」，让主进程抑制
 * 同会话的原生气泡；`onHide` 先撤销可见性再断开，且绝不发 `cancel`。这里直接
 * 驱动会话的 show/hide 钩子（真机由系统调用同一组方法），断言真实的命令序列。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantSessionServiceLifecycleTest {
    private val sent = mutableListOf<Bundle>()
    private var serviceController: ServiceController<AssistantSessionService>? = null

    private fun session(): VoiceInteractionSession {
        val controller = Robolectric.buildService(AssistantSessionService::class.java).create()
        serviceController = controller
        return controller.get().onNewSession(null)
    }

    /** 完成绑定并回一帧状态，让客户端拿到 token。 */
    private fun attachFakeBridge() {
        val application = RuntimeEnvironment.getApplication()
        val bridge = android.os.Messenger(
            android.os.Handler(android.os.Looper.getMainLooper()) { message ->
                sent += message.data
                if (message.data.getString("command") == "connect") {
                    message.replyTo?.send(android.os.Message.obtain().apply {
                        data = Bundle().apply {
                            putInt("version", ASSISTANT_BRIDGE_VERSION)
                            putString("token", "test-session")
                            putString("state", """{"version":2,"revision":1,"connected":true}""")
                        }
                    })
                }
                true
            },
        )
        // 先冲掉 bindService 自身排队的那次"默认连接"回调（binder 为 null，
        // 只会让客户端进入未连接态），再把我们自己的假服务接上。
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // 会话在 onCreateContentView 时绑定桥接；此时已有连接。
        shadowOf(application).boundServiceConnections.last()
            .onServiceConnected(android.content.ComponentName(application, "TestBridge"), bridge.binder)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun commands() = sent.map { it.getString("command") }

    @After fun cleanup() {
        serviceController?.destroy()
    }

    @Test fun showingTheSessionDeclaresPresence() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("a new invocation must declare presence: ${commands()}", "presence" in commands())
        assertTrue(sent.last { it.getString("command") == "presence" }.getBoolean("visible"))
    }

    @Test fun hidingTheSessionRevokesPresenceBeforeDisconnectingAndNeverCancels() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val before = commands()

        session.onHide()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val after = commands().drop(before.size)

        // 先撤销可见性，再断开连接。
        assertEquals(listOf("presence", "disconnect"), after)
        assertEquals(false, sent.last { it.getString("command") == "presence" }.getBoolean("visible"))
        assertTrue("hiding the session must never cancel generation", "cancel" !in after)
    }

    @Test fun aNewInvocationGetsAFreshSessionThroughConnectNotSessionNew() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // `connect` 已在主进程侧建了新会话（openSession + random UUID）；界面不需要
        // 再发 `session.new`。给出这条约束，避免以后重复发命令造成会话被重建两次。
        assertEquals(1, commands().count { it == "connect" })
        assertTrue("connect already opens a new session", "session.new" !in commands())
    }

    @Test fun reInvokingWhileVisibleDropsThePreviousScreenshot() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // 上一轮拿到一张截图（真机由系统在唤起时交付）。
        session.onHandleScreenshot(
            android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888),
        )

        // 浮层仍可见时再次唤起：系统这次还没有交付新截图。
        session.onShow(Bundle(), 0)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // 旧截图必须已经作废：新会话若还能圈选，就能把上一轮的屏幕内容提交出去。
        // 行为层面观察——此时点圈选只能得到「没有截图」的提示，而不是进入圈选态。
        invokeBeginScreenSelection(session)
        assertTrue(
            "a re-invocation must not keep the previous screenshot",
            session.clientOrNull()?.state?.value?.advisory?.contains("没有提供截图") == true,
        )
    }

    /** 通过反射调用私有的圈选入口：它是界面按钮的唯一后端，行为即用户所见。 */
    private fun invokeBeginScreenSelection(session: VoiceInteractionSession) {
        val method = session.javaClass.getDeclaredMethod("beginScreenSelection").apply { isAccessible = true }
        method.invoke(session)
    }

    private fun VoiceInteractionSession.clientOrNull(): AssistantBridgeClient? =
        javaClass.getDeclaredField("client").apply { isAccessible = true }.get(this) as? AssistantBridgeClient
}
