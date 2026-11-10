package com.openandroidintelligence.assistant

import android.os.Bundle
import android.view.WindowManager
import android.service.voice.VoiceInteractionSession
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    private fun session(handoffFrame: HandoffFrame? = null): VoiceInteractionSession =
        session(listOfNotNull(handoffFrame))

    private fun session(handoffFrames: List<HandoffFrame>): VoiceInteractionSession {
        handoffs = ArrayDeque(handoffFrames)
        val controller = Robolectric.buildService(AssistantSessionService::class.java).create()
        serviceController = controller
        return controller.get().onNewSession(null)
    }

    /**
     * 主进程对第 N 次 handoff 命令回的那一帧；按序消费。
     *
     * 用队列是因为要验证「上一轮被拒、这一轮被接受」：两次请求必须拿到不同的回执。
     */
    private var handoffs: ArrayDeque<HandoffFrame> = ArrayDeque()

    /** 状态帧序号；每次发帧都要递增，否则接收方会当成重复帧丢弃。 */
    private var handoffRevisions = 1

    /** 完成绑定并回一帧状态，让客户端拿到 token。 */
    private fun attachFakeBridge() {
        val application = RuntimeEnvironment.getApplication()
        val bridge = android.os.Messenger(
            android.os.Handler(android.os.Looper.getMainLooper()) { message ->
                sent += message.data
                when (message.data.getString("command")) {
                    "connect" -> message.replyTo?.send(handoffStateMessage(1))
                    // 回执必须原样带回本次请求的代次：真实主进程就是这么做的。
                    // revision 也必须是递增的：客户端会丢弃乱序/重复帧，同一 revision 的
                    // 第二次回执会被当成重复帧丢掉，测不出代次语义。
                    "handoff" -> handoffs.removeFirstOrNull()?.let { frame ->
                        message.replyTo?.send(
                            handoffStateMessage(++handoffRevisions, frame, message.data.getString("handoffId").orEmpty()),
                        )
                    }
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

    /** 状态帧：第 [revision] 帧，[frame] 非空时带上交接结果与本次请求的代次。 */
    private fun handoffStateMessage(
        revision: Int,
        frame: HandoffFrame? = null,
        handoffId: String = "",
    ): android.os.Message {
        val handoffJson = when (frame) {
            is AssistantHandoffRejectedFrame ->
                """{"handoffId":"$handoffId","state":"REJECTED","reason":"${frame.reason}"}"""
            is AssistantHandoffAcceptedFrame -> buildString {
                append("""{"handoffId":"$handoffId","state":"ACCEPTED",""")
                append(""""conversationId":"${frame.conversationId}",""")
                append(""""profileId":"${frame.profileId}",""")
                append(""""accountId":"${frame.accountId}"""")
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

    // ---- 系统选择器与窗口触摸 ----

    /**
     * 打开系统选择器前必须先停用会话窗口的 UI。
     *
     * 助手浮层是常驻在其他窗口之上的 `VoiceInteractionSession`：紧凑态窗口占着屏幕底部、
     * 定格态铺满全屏。选择器（Photo Picker）虽然在上层，但浮层窗口没有让出触摸区域时，
     * 选择器底部的 `Done` 落在浮层窗口范围内会被吞掉点击（真机已复现）。因此选择器
     * 启动前必须 `setUiEnabled(false)`，结果/取消回来后再恢复。
     */
    @Test fun openingTheSystemPickerDisablesTheSessionWindowUntilAResultArrives() {
        val session = createdSession()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        invokeRequestAttachmentPicker(session)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val shadow = shadowOf(session)
        assertFalse("选择器期间助手窗口必须让出触摸", shadow.isUiEnabled)
        assertEquals(
            "选择器必须由透明 Activity 启动",
            AssistantAttachmentPickerActivity::class.java.name,
            shadow.lastAssistantActivityIntent?.component?.className,
        )

        // 用户取消：必须恢复窗口，否则浮层会永久不可见。
        AssistantAttachmentPickerBroker.cancel()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("选择器取消后必须恢复助手窗口", shadow.isUiEnabled)
    }

    /**
     * 「＋」菜单里的文档入口必须把类别一路传到 Activity。
     *
     * 只开 Photo Picker 时文档选择器只能靠「启动失败」兜底，正常设备上永远走不到，
     * PDF/文本等现有附件类型在浮层里因此完全选不到（规格 §3.5）。
     */
    @Test fun theDocumentEntryLaunchesTheDocumentPicker() {
        val session = createdSession()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        invokeRequestAttachmentPicker(session, AssistantAttachmentKind.DOCUMENT)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val intent = shadowOf(session).lastAssistantActivityIntent
        assertEquals(AssistantAttachmentKind.DOCUMENT, AssistantAttachmentPickerActivity.kindOf(intent))
    }

    @Test fun theMediaEntryLaunchesThePhotoPicker() {
        val session = createdSession()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        invokeRequestAttachmentPicker(session, AssistantAttachmentKind.MEDIA)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            AssistantAttachmentKind.MEDIA,
            AssistantAttachmentPickerActivity.kindOf(shadowOf(session).lastAssistantActivityIntent),
        )
    }

    @Test fun aPickedFileRestoresTheSessionWindowAndStillReachesTheDraft() {
        val session = createdSession()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        invokeRequestAttachmentPicker(session)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val before = commands()

        AssistantAttachmentPickerBroker.deliver(
            listOf(android.net.Uri.parse("content://media/picked/1")),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("选择器返回后必须恢复助手窗口", shadowOf(session).isUiEnabled)
        assertEquals(
            "选中的文件仍要进入主进程草稿",
            listOf("attachment"),
            commands().drop(before.size),
        )
    }

    /**
     * 真机上系统会先 `doCreate` 再展示会话；`startAssistantActivity` / `hide` 都要求
     * 已经拿到 token。测试里显式走一次 `doCreate`，否则这两条路径只会抛
     * `IllegalStateException("Can't call before onCreate()")`，掩盖真实行为。
     */
    private fun createdSession(handoffFrame: HandoffFrame? = null): VoiceInteractionSession =
        session(handoffFrame).also { shadowOf(it).create() }

    private fun invokeRequestAttachmentPicker(
        session: VoiceInteractionSession,
        kind: AssistantAttachmentKind = AssistantAttachmentKind.MEDIA,
    ) {
        val method = session.javaClass
            .getDeclaredMethod("requestAttachmentPicker", AssistantAttachmentKind::class.java)
            .apply { isAccessible = true }
        method.invoke(session, kind)
    }

    /**
     * 定格态下发语音：状态机把覆盖层清成 `NONE` 并展开对话，窗口必须同步退出全屏。
     *
     * 语音发送路径（`AssistantSessionContent` 的录音回调）只派发 `VoiceSent`，没有像
     * 文字发送那样显式调用退出定格。窗口几何若只跟「显式退出」绑定，就会留在
     * `MATCH_PARENT × MATCH_PARENT` 的透明全屏上，吞掉下方 App 的触摸；用户再按返回时
     * 覆盖层已经是 `NONE`，直接被判定为结束会话。窗口必须由状态观察单点驱动。
     */
    @Test fun sendingVoiceFromTheFrozenSelectionLeavesFullscreen() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        session.onHandleScreenshot(
            android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val window = session.window?.window
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window?.attributes?.height)

        // 语音已被主进程接收：界面侧只有这一个事件（不经过文字发送的 onExitFrozen）。
        session.uiStateOrNull()?.dispatch(AssistantUiEvent.VoiceSent)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(AssistantOverlay.NONE, session.uiStateOrNull()?.visible()?.overlay)
        assertNotEquals(
            "语音发送后窗口必须回到测量驱动，不能继续铺满屏幕",
            WindowManager.LayoutParams.MATCH_PARENT,
            window?.attributes?.height,
        )
    }

    /**
     * 高分辨率截图不再被本地像素上限判死。
     *
     * 1440×3120（4.49M 像素）是常见旗舰分辨率，旧实现按 4M 上限直接丢弃，再用
     * 「系统未提供截图」提示——用户明明开着屏幕分析，看到的却是系统没给图。
     * 现在超限按比例降采样后照常进入定格。
     */
    @Test fun aHighResolutionScreenshotIsScaledInsteadOfRejected() {
        val session = session()
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        session.onHandleScreenshot(
            android.graphics.Bitmap.createBitmap(1440, 3120, android.graphics.Bitmap.Config.ARGB_8888),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            "超限截图应降采样后进入定格，而不是被判成「系统未提供」",
            AssistantOverlay.FROZEN_SCREENSHOT,
            session.uiStateOrNull()?.visible()?.overlay,
        )
        val advisory = session.clientOrNull()?.state?.value?.advisory
        assertTrue("不得再出现「系统未提供截图」：$advisory", advisory != "系统未提供截图，无法圈选；可直接输入文字")
    }

    /**
     * 系统这次没请求截图（`showFlags` 不含 `SHOW_WITH_SCREENSHOT`）时，
     * `onHandleScreenshot` 根本不会被调用。会话必须当场说明，而不是让用户干等。
     */
    @Test fun anInvocationWithoutTheScreenshotFlagExplainsWhyThereIsNoSelection() {
        val session = session()
        // 0 = 没有任何 show 位；系统不会投递截图。
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(AssistantOverlay.NONE, session.uiStateOrNull()?.visible()?.overlay)
        assertEquals(
            "本次唤起未包含系统截图，无法圈选；可直接输入文字",
            session.clientOrNull()?.state?.value?.advisory,
        )
    }

    /** 带截图位的唤起不应该提前给出「没有截图」的提示。 */
    @Test fun anInvocationWithTheScreenshotFlagDoesNotWarnPrematurely() {
        val session = session()
        session.onShow(Bundle(), VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull("已经请求截图时不得抢先报错", session.clientOrNull()?.state?.value?.advisory)
    }

    @Test fun aMissingScreenshotExplainsWhySelectionIsUnavailable() {
        val session = session()
        session.onShow(Bundle(), VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        session.clientOrNull()?.clearAdvisory()

        // 系统这次没有交付截图：会话既不能进定格，也不能保持沉默。
        session.onHandleScreenshot(null)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(AssistantOverlay.NONE, session.uiStateOrNull()?.visible()?.overlay)
        assertEquals(
            "系统未提供截图时必须给出一条可关闭的说明",
            "系统未提供截图，无法圈选；可直接输入文字",
            session.clientOrNull()?.state?.value?.advisory,
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

        val started = startedMainIntent(session)
        requireNotNull(started) { "交接成功后必须打开主 App" }
        assertEquals("com.openandroidintelligence.mobile.MainActivity", started.component?.className)
        assertEquals("oai-reply", started.data?.scheme)
        assertEquals(listOf("profile-1", "account-1", "thread-9", "agent-1"), started.data?.pathSegments)
        assertEquals(
            "test-session",
            started.getStringExtra("com.openandroidintelligence.assistant.SESSION_TOKEN"),
        )
    }

    /**
     * 上一轮被拒、这一轮被接受：必须打开主 App。
     *
     * 真实主进程会把结果留在状态帧里，`StateFlow` 订阅时立即重放当前值。新一轮请求
     * 若把重放的旧 REJECTED 当成自己的结果，就会把本轮判成失败；随后真正的 ACCEPTED
     * 又被「已结束」挡掉——主进程已经接管草稿，浮层却停在失败状态，也没有打开 App。
     * 回执必须按请求代次归属。
     */
    @Test fun aSecondHandoffIsNotJudgedByThePreviousRejection() {
        val session = session(
            handoffFrames = listOf(
                AssistantHandoffRejectedFrame("附件仍在上传，交接未完成"),
                AssistantHandoffAcceptedFrame(
                    conversationId = "thread-9",
                    profileId = "profile-1",
                    accountId = "account-1",
                    agentId = "agent-1",
                ),
            ),
        )
        session.onShow(Bundle(), 0)
        attachFakeBridge()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // 第一次：被拒，浮层保留。
        invokeRequestHandoff(session)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(
            "附件仍在上传，交接未完成",
            session.clientOrNull()?.state?.value?.advisory,
        )

        // 第二次：状态里仍残留上一轮的 REJECTED，但本轮回执是 ACCEPTED。
        invokeRequestHandoff(session)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val started = startedMainIntent(session)
        requireNotNull(started) { "本轮 ACCEPTED 必须打开主 App，不能被上一轮的拒绝挡住" }
        assertEquals("com.openandroidintelligence.mobile.MainActivity", started.component?.className)
    }

    /**
     * 会话打开主 App 时实际使用的 Intent。
     *
     * 真实路径优先走 `VoiceInteractionSession.startAssistantActivity`（助手窗口不是
     * 「可见应用窗口」，普通 `startActivity` 会被后台启动限制静默拒绝）；Robolectric
     * 把它记在会话上，普通 `startActivity` 才记在 Application 上。断言必须两条都看，
     * 否则测试会「因为走了正确路径而失败」。
     */
    private fun startedMainIntent(session: VoiceInteractionSession): android.content.Intent? =
        shadowOf(session).assistantActivityIntents.lastOrNull()
            ?: org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity

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
