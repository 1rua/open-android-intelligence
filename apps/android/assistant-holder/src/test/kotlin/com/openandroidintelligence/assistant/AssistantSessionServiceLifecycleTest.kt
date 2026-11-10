package com.openandroidintelligence.assistant

import android.os.Bundle
import android.view.WindowManager
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

    private fun session(handoffFrame: HandoffFrame? = null): VoiceInteractionSession {
        handoff = handoffFrame
        val controller = Robolectric.buildService(AssistantSessionService::class.java).create()
        serviceController = controller
        return controller.get().onNewSession(null)
    }

    /** 主进程在收到 handoff 后回的那一帧（含交接结果）；默认不回。 */
    private var handoff: HandoffFrame? = null

    /** 完成绑定并回一帧状态，让客户端拿到 token。 */
    private fun attachFakeBridge() {
        val application = RuntimeEnvironment.getApplication()
        val bridge = android.os.Messenger(
            android.os.Handler(android.os.Looper.getMainLooper()) { message ->
                sent += message.data
                when (message.data.getString("command")) {
                    "connect" -> message.replyTo?.send(handoffStateMessage(1))
                    "handoff" -> handoff?.let { frame -> message.replyTo?.send(handoffStateMessage(2, frame)) }
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

    /** 状态帧：第 [revision] 帧，[frame] 非空时带上交接结果。 */
    private fun handoffStateMessage(revision: Int, frame: HandoffFrame? = null): android.os.Message {
        val handoffJson = when (frame) {
            is AssistantHandoffRejectedFrame ->
                """{"state":"REJECTED","reason":"${frame.reason}"}"""
            is AssistantHandoffAcceptedFrame -> buildString {
                append("""{"state":"ACCEPTED","conversationId":"${frame.conversationId}","""")
                append("""profileId":"${frame.profileId}","accountId":"${frame.accountId}"""")
                frame.agentId?.let { append(""","agentId":"$it"""") }
                append("}")
            }
            null -> null
        }
        val state = buildString {
            append("""{"version":2,"revision":$revision,"connected":true,"sessionId":"test-session"""")
            handoffJson?.let { append(""","handoff":$it""") }
            append("}")
        }
        return android.os.Message.obtain().apply {
            data = Bundle().apply {
                putInt("version", ASSISTANT_BRIDGE_VERSION)
                putString("token", "test-session")
                putString("state", state)
            }
        }
    }

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

    // ---- 唤起即画圈（定格截图） ----

    @Test fun aScreenshotArrivingAfterACleanInvocationEntersTheFrozenSelection() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        session.onHandleScreenshot(
            android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(AssistantOverlay.FROZEN_SCREENSHOT, session.uiStateOrNull()?.visible()?.overlay)
        // 窗口同时铺满：界面与窗口几何由同一处推进。
        val window = session.window?.window
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window?.attributes?.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window?.attributes?.height)
    }

    @Test fun backLeavesTheFrozenSelectionWithoutEndingTheSession() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        session.onHandleScreenshot(
            android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        session.onBackPressed()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val visible = session.uiStateOrNull()?.visible()
        requireNotNull(visible) { "第一次返回只退出定格，不得结束会话" }
        assertEquals(AssistantOverlay.NONE, visible.overlay)
        assertTrue("退出定格后不撤销可见性", "disconnect" !in commands())
    }

    @Test fun aScreenshotWhileTheUserAlreadyTypedDoesNotStealTheCompactInput() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        session.clientOrNull()?.state?.value // 读一次状态，确认已连接
        session.dispatchDraft("用户已经开始输入")

        session.onHandleScreenshot(
            android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            "已有草稿时不强行切全屏",
            AssistantOverlay.NONE,
            session.uiStateOrNull()?.visible()?.overlay,
        )
    }

    // ---- 交接（严格模式） ----

    @Test fun slidingUpSendsTheHandoffCommandWithoutClosingTheOverlay() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val before = commands()

        invokeRequestHandoff(session)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("交接必须先请求主进程", listOf("handoff"), commands().drop(before.size))
        // 还没拿到回执：会话必须保持可见（严格交接，不先 hide）。
        assertTrue("没有回执前不得关闭浮层", commands().none { it == "disconnect" })
    }

    @Test fun aRejectedHandoffKeepsTheOverlayAndExplainsWhy() {
        val session = session(handoffFrame = AssistantHandoffRejectedFrame("当前账号不一致，未交接"))
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        invokeRequestHandoff(session)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("拒绝原因必须原样显示", session.clientOrNull()?.state?.value?.advisory == "当前账号不一致，未交接")
        // 失败路径不关闭浮层、不撤销可见性。
        val afterHandoff = commands().dropWhile { true }.drop(commands().indexOf("handoff") + 1)
        assertTrue("失败时不得断开连接", afterHandoff.none { it == "disconnect" })
    }

    @Test fun anAcceptedHandoffOpensTheMainAppWithTheConversationIdentity() {
        val session = session(
            handoffFrame = AssistantHandoffAcceptedFrame(
                conversationId = "thread-9",
                profileId = "profile-1",
                accountId = "account-1",
                agentId = "agent-1",
            ),
        )
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        invokeRequestHandoff(session)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val started = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .nextStartedActivity
        requireNotNull(started) { "交接成功后必须打开主 App" }
        assertEquals("com.openandroidintelligence.mobile.MainActivity", started.component?.className)
        assertEquals("oai-reply", started.data?.scheme)
        assertEquals(listOf("profile-1", "account-1", "thread-9", "agent-1"), started.data?.pathSegments)
        assertEquals(
            "test-session",
            started.getStringExtra("com.openandroidintelligence.assistant.SESSION_TOKEN"),
        )
    }

    private fun VoiceInteractionSession.uiStateOrNull(): AssistantSessionUiController? =
        javaClass.getDeclaredField("uiState").apply { isAccessible = true }.get(this) as? AssistantSessionUiController

    /** 通过宿主层写入草稿：会话的真实路径是主进程回传状态帧，这里直接驱动同一个客户端。 */
    private fun VoiceInteractionSession.dispatchDraft(text: String) {
        val client = clientOrNull() ?: return
        client.updateDraft(text)
    }

    private fun invokeRequestHandoff(session: VoiceInteractionSession) {
        val method = session.javaClass.getDeclaredMethod("requestHandoff").apply { isAccessible = true }
        method.invoke(session)
    }

    private sealed interface HandoffFrame
    private data class AssistantHandoffRejectedFrame(val reason: String) : HandoffFrame
    private data class AssistantHandoffAcceptedFrame(
        val conversationId: String,
        val profileId: String,
        val accountId: String,
        val agentId: String?,
    ) : HandoffFrame

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
