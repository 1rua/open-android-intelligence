package com.openandroidintelligence.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.openandroidintelligence.ui.resources.R as UiR
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_ALLOWED_KEYS
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.MAX_COMPOSER_ATTACHMENTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 助理进程的桥接客户端（不再依赖任何 View）。
 *
 * 职责边界：
 * - 只做"绑定主进程服务 + 收发 v2 Messenger 协议 + 把状态折成 [AssistantState]"；
 * - 命令字面量与 Bundle 字段严格对齐主进程 `AssistantBridgeService`（`version=2`）：
 *   `connect` / `session.new` / `presence` / `send` / `cancel` / `draft` /
 *   `screen` / `voice` / `attachment.remove` / `approval` / `retry` / `disconnect`；
 * - 屏幕附件编码继续交给 [encodeAssistantScreen]，管道写入与超时看门狗原样保留；
 * - 不初始化 Gateway、不持有凭据：助理进程只是呈现客户端。
 *
 * [state] 是唯一对外状态；Compose 直接收集它，测试也只需要这一个出口。
 */
internal class AssistantBridgeClient(
    internal val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private val current = MutableStateFlow(AssistantState())

    val state: StateFlow<AssistantState> = current.asStateFlow()

    private var bridge: Messenger? = null
    private var token: String? = null
    private var bound = false
    private var disposed = false

    /**
     * 界面最近一次声明的可见性。
     *
     * 可见性属于客户端、跨 `sessionNew()` 保留；绑定建立前调用 [setPresence] 没有
     * 通道可用（返回 false），这里记住意图，由 [onServiceConnected] 在 `connect`
     * 之后补发一次，而不是让宿主反复重试。
     */
    private var desiredPresence: Boolean? = null

    /**
     * 用户开始输入的回调（每次 `draft` 命令前触发）。
     *
     * 宿主用它在状态帧延迟期间判断「用户已经开始输入」：只看回传草稿会把刚开始打字的
     * 那几帧误判成初态，从而在全屏定格与输入之间来回切。
     */
    var onDraftDispatched: ((String) -> Unit)? = null

    /** 主进程已确认收到的可见性；`null` 表示本次绑定还没声明过。 */
    private var sentPresence: Boolean? = null

    /** 最近一次已应用的帧序号；乱序或重复帧整帧丢弃。 */
    private var lastRevision = 0L

    private val receiver = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (disposed) return@Handler true
            // 协议版本不符的消息整条丢弃：宁可停在上一帧，也不误解一次未知格式。
            if (message.data.getInt("version") != ASSISTANT_BRIDGE_VERSION) return@Handler true
            val published = message.data.getString("state") ?: return@Handler true
            val parsed = parseAssistantState(published) ?: return@Handler true
            // 状态帧按 revision 单调递增；旧帧晚到不能把界面倒退回去。
            // 首帧无条件接受：服务端首个 revision 是 1，本地从 0 起算。
            if (lastRevision != 0L && parsed.revision <= lastRevision) return@Handler true
            // token 只随**合法帧**更新：坏帧不能把后续命令的凭据换成一个未知值。
            message.data.getString("token")?.let { token = it }
            lastRevision = parsed.revision
            current.value = parsed.copy(
                bridgeAvailable = true,
                advisory = current.value.advisory,
            )
            // 首帧带回 token 后，把绑定前声明的可见性补发一次：主进程要等确认
            // 客户端仍在展示该会话后才抑制原生气泡。已经发过就不用重复。
            replayPresenceIfNeeded()
            true
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (disposed) return
            bridge = service?.let(::Messenger)
            if (bridge == null) {
                markDisconnected(context.getString(UiR.string.assistant_service_unavailable_open_app))
                return
            }
            // 重新绑定会得到新的 token 与从 1 重新开始的服务端帧序号，
            // 旧的序号门槛必须清掉，否则新会话的首帧会被当成"乱序"丢掉。
            // 新连接在主进程侧是从 `visible=false` 开始的，可见性也要重发。
            lastRevision = 0L
            sentPresence = null
            command(COMMAND_CONNECT)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            markDisconnected(context.getString(UiR.string.assistant_service_interrupted_reopen))
        }

        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)

        override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
    }

    /** 绑定主进程服务；重复调用是幂等的。 */
    fun connect() {
        if (bound || disposed) return
        bound = runCatching {
            context.bindService(
                Intent().setClassName(context.packageName, BRIDGE_SERVICE),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) {
            markDisconnected(context.getString(UiR.string.assistant_service_unavailable_open_app))
        }
    }

    /** 解除绑定并通知主进程释放这个客户端；调用后实例不可再用。 */
    fun dispose() {
        if (disposed) return
        command(COMMAND_DISCONNECT)
        disposed = true
        bridge = null
        token = null
        lastRevision = 0L
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        // 关闭后不再保留任何"已连接"的残影。
        current.value = AssistantState(bridgeAvailable = false)
    }

    /**
     * 结束当前会话并创建一条全新会话（主进程释放旧控制器后重建）。
     *
     * 返回命令是否已送达；真正的会话身份由随后下发的状态帧携带。
     */
    fun sessionNew(): Boolean = command(COMMAND_SESSION_NEW)

    /**
     * 声明界面是否正在展示当前会话（主进程据此抑制原生通知气泡）。
     *
     * 可见性属于客户端，跨 `sessionNew()` 保留；未连接时返回 false，并把这次意图
     * 记下来，绑定成功后由首帧状态补发（见 [replayPresenceIfNeeded]）。
     */
    fun setPresence(visible: Boolean): Boolean {
        desiredPresence = visible
        // 尚未绑定/还没拿到 token 时通道不存在，这不是"连接中断"：只记住意图，
        // 由首帧状态补发。直接走 command() 会被当成发送失败并错误地显示断连提示。
        if (bridge == null || token == null) return false
        // 主进程已经确认同一状态就不再重复发：宿主可能先后在 onStart 与
        // onResume 各声明一次，重复帧只会让主进程多做一次无意义的发布。
        if (sentPresence == visible) return true
        return deliverPresence(visible)
    }

    /** 把最近一次声明的可见性真正发出去；返回命令是否已送达。 */
    private fun deliverPresence(visible: Boolean): Boolean {
        val delivered = command(COMMAND_PRESENCE) { putBoolean("visible", visible) }
        if (delivered) sentPresence = visible
        return delivered
    }

    /**
     * 绑定建立后补发可见性。
     *
     * 只有"界面想要的状态"与"主进程已知的状态"不一致时才发：正常路径下
     * [setPresence] 已经送达，这里不会产生重复命令。
     */
    private fun replayPresenceIfNeeded() {
        val desired = desiredPresence ?: return
        if (desired == sentPresence) return
        deliverPresence(desired)
    }

    /**
     * 请求发送当前草稿。
     *
     * 返回**命令是否已送达主进程**：false 表示桥接不可用（未绑定/已断开），
     * 界面据此保持紧凑形态并保留草稿，而不是假装消息已经发出。
     * 真正的 Gateway 接收结果仍由主进程随状态通道下发的 notice/generation 表达。
     */
    fun send(): Boolean = command(COMMAND_SEND)

    fun cancel() {
        command(COMMAND_CANCEL)
    }

    /** 草稿同步：上限与主进程校验保持一致（50 000 字符）。 */
    fun updateDraft(text: String) {
        if (text.length > MAX_ASSISTANT_DRAFT_CHARS) {
            showAdvisory(context.getString(UiR.string.assistant_draft_exceed_limit, MAX_ASSISTANT_DRAFT_CHARS))
        }
        onDraftDispatched?.invoke(text)
        command(COMMAND_DRAFT) { putString("text", text.take(MAX_ASSISTANT_DRAFT_CHARS)) }
    }

    /** 从当前草稿移除一个附件（按附件草稿 ID）。 */
    fun removeAttachment(draftId: String): Boolean {
        if (draftId.isBlank()) return false
        return command(COMMAND_ATTACHMENT_REMOVE) { putString("draftId", draftId) }
    }

    /**
     * 提交一次审批决策。
     *
     * [choice] 是 Gateway 的审批意图（once / session / always / deny）；未知意图不发，
     * 避免把一个对端不认识的档位当成合法裁决。返回命令是否已送达。
     */
    fun decideApproval(approvalId: String, choice: ApprovalChoice): Boolean {
        if (approvalId.isBlank() || choice == ApprovalChoice.UNKNOWN) return false
        return command(COMMAND_APPROVAL) {
            putString("approvalId", approvalId)
            putString("choice", choice.wireValue)
        }
    }

    /**
     * 请求把当前助手会话 + 草稿 + 附件交给主 App（上滑交接）。
     *
     * 命令只带本次请求的代次 [handoffId]：会话身份由主进程已持有的 `sessionToken` 决定。
     * 结果随下一次状态帧的 `handoff` 字段下发（PENDING/ACCEPTED/REJECTED）并原样带回同
     * 一个代次；调用方必须等**本轮**回执，不能把「命令送达」或上一轮的终态当成成功。
     */
    fun requestHandoff(handoffId: String): Boolean {
        if (handoffId.isBlank()) return false
        return command(COMMAND_HANDOFF) { putString("handoffId", handoffId) }
    }

    /**
     * 请求把本进程选中的文件加入主进程的草稿（一次最多 [MAX_COMPOSER_ATTACHMENTS] 个）。
     *
     * 只传 URI 字符串，不传字节：同 UID + 持久化读授权让主进程直接读同一 URI，
     * 避免把 8MB 级字节跨进程复制，也避免在本进程再实现一套文件读取逻辑。
     * 空列表或超限的列表直接拒绝（返回 false），由调用方提示，不静默裁剪。
     */
    fun attachFiles(uris: List<String>): Boolean {
        val bounded = uris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (bounded.isEmpty() || bounded.size > MAX_COMPOSER_ATTACHMENTS) return false
        return command(COMMAND_ATTACHMENT) {
            putStringArray("uris", bounded.toTypedArray())
            putInt("count", bounded.size)
        }
    }

    /** 请求重试一次失败：时间线读取或未确认的发送，两者语义不同，必须由调用方明示。 */
    fun retry(target: AssistantRetryTarget): Boolean =
        command(COMMAND_RETRY) { putString("target", target.wireValue) }

    /** 一次性提示（截图缺失、附件编码失败等）；由界面显式关闭。 */
    fun showAdvisory(message: String) {
        if (disposed) return
        current.value = current.value.copy(advisory = message)
    }

    fun clearAdvisory() {
        if (disposed) return
        if (current.value.advisory == null) return
        current.value = current.value.copy(advisory = null)
    }

    /**
     * 接管所选位图；编码线程完成后会回收它。
     *
     * 没有当前绑定、已关闭或未连接时立即回收并丢弃：绝不把附件塞进别人的会话。
     */
    fun attachScreen(bitmap: Bitmap, filename: String = SCREEN_ATTACHMENT_FILENAME) {
        val expectedBridge = bridge
        val expectedToken = token
        if (!canSendAttachment(expectedBridge, expectedToken)) {
            bitmap.recycle()
            return
        }
        encodeAssistantScreen(bitmap) { bytes ->
            if (!mainHandler.post {
                    if (!canSendAttachment(expectedBridge, expectedToken)) {
                        bytes?.fill(0)
                        return@post
                    }
                    if (bytes == null) {
                        showAdvisory(context.getString(UiR.string.assistant_screen_attachment_encode_failed))
                        return@post
                    }
                    if (bytes.size > MAX_SCREEN_ATTACHMENT_BYTES) {
                        bytes.fill(0)
                        showAdvisory(context.getString(UiR.string.assistant_screen_selection_image_too_large))
                        return@post
                    }
                    val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
                        bytes.fill(0)
                        showAdvisory(context.getString(UiR.string.assistant_screen_attachment_read_failed))
                        return@post
                    }
                    val delivered = command(COMMAND_SCREEN) {
                        putParcelable("content", pipe[0])
                        putInt("size", bytes.size)
                        // 拍照来源要与圈选区分：主进程据此命名，两张附件才不会同名。
                        putString("filename", filename)
                    }
                    if (!delivered) {
                        pipe.forEach { runCatching { it.close() } }
                        bytes.fill(0)
                        return@post
                    }
                    // 主进程若 10 秒内没有读完，就关闭写端让那边失败关闭，而不是无限等待。
                    val timeout = Runnable { runCatching { pipe[1].close() } }
                    mainHandler.postDelayed(timeout, SCREEN_DELIVERY_TIMEOUT_MILLIS)
                    Thread({
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
                        } catch (_: Exception) {
                            // 读端提前关闭是允许的：主进程自己会给出失败提示。
                        } finally {
                            mainHandler.removeCallbacks(timeout)
                            bytes.fill(0)
                            runCatching { pipe[0].close() }
                        }
                    }, "assistant-screen-delivery").start()
                }
            ) {
                bytes?.fill(0)
            }
        }
    }

    /**
     * 提交一段原始语音附件（字节流 + 真实时长 + 媒体类型）。
     *
     * [content] 必须是**可读端**（与 `screen` 相同：管道读端或文件描述符），主进程用
     * `AutoCloseInputStream` 读取恰好 [size] 字节。所有权随调用移交：无论命令是否送达，
     * 本方法都会关闭这个本地描述符；Binder 在 `send` 时已把描述符复制给主进程，
     * 关闭本地副本不会截断对方。
     *
     * 返回命令是否已送达。字节、时长与媒体类型的合法性由主进程校验：被拒绝时它会
     * 通过状态帧给出明确提示，本层不伪造"已发送"。
     */
    fun sendVoice(
        content: ParcelFileDescriptor,
        size: Int,
        durationMs: Long,
        mediaType: String,
    ): Boolean {
        val expectedBridge = bridge
        val expectedToken = token
        if (!canSendAttachment(expectedBridge, expectedToken)) {
            runCatching { content.close() }
            return false
        }
        return try {
            command(COMMAND_VOICE) {
                putParcelable("content", content)
                putInt("size", size)
                putLong("durationMs", durationMs)
                putString("mediaType", mediaType)
            }
        } finally {
            runCatching { content.close() }
        }
    }

    private fun markDisconnected(message: String) {
        bridge = null
        token = null
        lastRevision = 0L
        // 连接已断：主进程侧的客户端随之消失，重新绑定后必须重发可见性。
        sentPresence = null
        // 保留上一帧的草稿与外观，只把连接轴置为不可用：界面据此显示明确引导。
        current.value = current.value.copy(
            bridgeAvailable = false,
            connected = false,
            account = null,
            sessionId = null,
            activeThread = null,
            timeline = emptyList(),
            timelineTruncated = false,
            notice = message,
        )
    }

    private fun canSendAttachment(expectedBridge: Messenger?, expectedToken: String?): Boolean =
        !disposed && current.value.canInteract && expectedBridge != null && bridge === expectedBridge &&
            expectedToken != null && token == expectedToken

    private fun command(name: String, fill: Bundle.() -> Unit = {}): Boolean {
        val body = Bundle().apply {
            putInt("version", ASSISTANT_BRIDGE_VERSION)
            putString("command", name)
            token?.let { putString("token", it) }
            fill()
        }
        // 协议字段封闭：白名单之外的键一律不发，避免把本地新字段误当成对端语义。
        // 主进程同样会整条丢弃带未知键的消息，两端保持同一份字段集合。
        if (!ALLOWED_KEYS.containsAll(body.keySet())) return false
        return runCatching {
            val target = bridge ?: error("BRIDGE_UNAVAILABLE")
            target.send(Message.obtain().apply { data = body; replyTo = receiver })
            true
        }.getOrElse {
            if (!disposed) markDisconnected(context.getString(UiR.string.assistant_connection_interrupted_reopen))
            false
        }
    }

    private companion object {
        const val BRIDGE_SERVICE =
            "com.openandroidintelligence.mobile.assistant.AssistantBridgeService"

        // 以下命令字面量是跨进程契约（v2），改名就会与主进程失配。
        const val COMMAND_CONNECT = "connect"
        const val COMMAND_SESSION_NEW = "session.new"
        const val COMMAND_PRESENCE = "presence"
        const val COMMAND_SEND = "send"
        const val COMMAND_CANCEL = "cancel"
        const val COMMAND_DRAFT = "draft"
        const val COMMAND_SCREEN = "screen"
        const val COMMAND_VOICE = "voice"
        const val COMMAND_ATTACHMENT_REMOVE = "attachment.remove"
        const val COMMAND_APPROVAL = "approval"
        const val COMMAND_RETRY = "retry"
        const val COMMAND_ATTACHMENT = "attachment"
        const val COMMAND_HANDOFF = "handoff"
        const val COMMAND_DISCONNECT = "disconnect"

        /** 与主进程共用同一份字段集合（`ASSISTANT_BRIDGE_ALLOWED_KEYS`），不可能各自漂移。 */
        val ALLOWED_KEYS = ASSISTANT_BRIDGE_ALLOWED_KEYS

        const val MAX_SCREEN_ATTACHMENT_BYTES = 8 * 1024 * 1024
        const val SCREEN_DELIVERY_TIMEOUT_MILLIS = 10_000L

        /** 圈选截图的默认展示文件名；拍照来源会传入自己的名字。 */
        const val SCREEN_ATTACHMENT_FILENAME = "assist-screen.png"
    }
}

/** `retry` 命令的两种目标；与主进程的 `target` 字面量一致。 */
internal enum class AssistantRetryTarget(val wireValue: String) {
    /** 时间线读取失败：重新拉取当前对话。 */
    TIMELINE("timeline"),

    /** 未确认的发送：重试待确认的提交。 */
    SUBMISSION("submission"),
}

/** 草稿长度上限与主进程 `AssistantBridgeService` 的校验保持一致。 */
internal const val MAX_ASSISTANT_DRAFT_CHARS = 50_000
