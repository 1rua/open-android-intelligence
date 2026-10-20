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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 助理进程的桥接客户端（不再依赖任何 View）。
 *
 * 职责边界：
 * - 只做"绑定主进程服务 + 收发现有 Messenger 协议 + 把状态折成 [AssistantState]"；
 * - 命令字面量与 JSON 字段严格保持既有契约（`connect` / `consent` / `thread` / `send` /
 *   `cancel` / `draft` / `screen` / `disconnect`，`version=1`）；
 * - 屏幕附件编码继续交给 [encodeAssistantScreen]，管道写入与超时看门狗原样保留；
 * - 不初始化 Gateway、不持有凭据：助理进程只是呈现客户端。
 *
 * [state] 是唯一对外状态；Compose 直接收集它，旧视图或测试也只需要这一个出口。
 */
internal class AssistantBridgeClient(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private val current = MutableStateFlow(AssistantState())

    val state: StateFlow<AssistantState> = current.asStateFlow()

    private var bridge: Messenger? = null
    private var token: String? = null
    private var bound = false
    private var disposed = false

    private val receiver = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (disposed) return@Handler true
            // 协议版本不符的消息整条丢弃：宁可停在上一帧，也不误解一次未知格式。
            if (message.data.getInt("version") != 1) return@Handler true
            token = message.data.getString("token")
            val published = message.data.getString("state") ?: return@Handler true
            val parsed = parseAssistantState(published) ?: return@Handler true
            current.value = parsed.copy(
                bridgeAvailable = true,
                advisory = current.value.advisory,
            )
            true
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (disposed) return
            bridge = service?.let(::Messenger)
            if (bridge == null) {
                markDisconnected("助理服务无法连接，请打开 App")
                return
            }
            command(COMMAND_CONNECT)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            markDisconnected("助理连接已中断，请重新打开助理")
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
            markDisconnected("助理服务无法连接，请打开 App")
        }
    }

    /** 解除绑定并通知主进程释放这个客户端；调用后实例不可再用。 */
    fun dispose() {
        if (disposed) return
        command(COMMAND_DISCONNECT)
        disposed = true
        bridge = null
        token = null
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        // 关闭后不再保留任何"已连接"的残影。
        current.value = AssistantState(bridgeAvailable = false)
    }

    fun setConsent() {
        command(COMMAND_CONSENT)
    }

    fun selectThread(threadId: String) {
        if (threadId.isBlank()) return
        command(COMMAND_THREAD) { putString("threadId", threadId) }
    }

    fun send() {
        command(COMMAND_SEND)
    }

    fun cancel() {
        command(COMMAND_CANCEL)
    }

    /** 草稿同步：上限与主进程校验保持一致（50 000 字符）。 */
    fun updateDraft(text: String) {
        if (text.length > MAX_ASSISTANT_DRAFT_CHARS) {
            showAdvisory("草稿超过 $MAX_ASSISTANT_DRAFT_CHARS 字，超出部分不会同步")
        }
        command(COMMAND_DRAFT) { putString("text", text.take(MAX_ASSISTANT_DRAFT_CHARS)) }
    }

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
     * 没有当前绑定、已关闭或未同意时立即回收并丢弃：绝不把附件塞进别人的会话。
     */
    fun attachScreen(bitmap: Bitmap) {
        val expectedBridge = bridge
        val expectedToken = token
        if (!canAttachScreen(expectedBridge, expectedToken)) {
            bitmap.recycle()
            return
        }
        encodeAssistantScreen(bitmap) { bytes ->
            if (!mainHandler.post {
                    if (!canAttachScreen(expectedBridge, expectedToken)) {
                        bytes?.fill(0)
                        return@post
                    }
                    if (bytes == null) {
                        showAdvisory("屏幕附件无法编码，请重新圈选")
                        return@post
                    }
                    if (bytes.size > MAX_SCREEN_ATTACHMENT_BYTES) {
                        bytes.fill(0)
                        showAdvisory("圈选图片过大，请缩小范围")
                        return@post
                    }
                    val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
                        bytes.fill(0)
                        showAdvisory("屏幕附件无法读取，请重新圈选")
                        return@post
                    }
                    val delivered = command(COMMAND_SCREEN) {
                        putParcelable("content", pipe[0])
                        putInt("size", bytes.size)
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

    private fun markDisconnected(message: String) {
        bridge = null
        token = null
        // 保留上一帧的草稿与线程标题，只把连接轴置为不可用：界面据此显示明确引导。
        current.value = current.value.copy(
            bridgeAvailable = false,
            connected = false,
            consent = false,
            messages = emptyList(),
            threads = emptyList(),
            notice = message,
        )
    }

    private fun canAttachScreen(expectedBridge: Messenger?, expectedToken: String?): Boolean =
        !disposed && current.value.canInteract && expectedBridge != null && bridge === expectedBridge &&
            expectedToken != null && token == expectedToken

    private fun command(name: String, fill: Bundle.() -> Unit = {}): Boolean {
        val body = Bundle().apply {
            putInt("version", 1)
            putString("command", name)
            token?.let { putString("token", it) }
            fill()
        }
        return runCatching {
            val target = bridge ?: error("BRIDGE_UNAVAILABLE")
            target.send(Message.obtain().apply { data = body; replyTo = receiver })
            true
        }.getOrElse {
            if (!disposed) markDisconnected("连接已中断，请重新打开助理")
            false
        }
    }

    private companion object {
        const val BRIDGE_SERVICE =
            "com.openandroidintelligence.mobile.assistant.AssistantBridgeService"

        // 以下命令字面量是既有跨进程契约，改名就会与主进程失配。
        const val COMMAND_CONNECT = "connect"
        const val COMMAND_CONSENT = "consent"
        const val COMMAND_THREAD = "thread"
        const val COMMAND_SEND = "send"
        const val COMMAND_CANCEL = "cancel"
        const val COMMAND_DRAFT = "draft"
        const val COMMAND_SCREEN = "screen"
        const val COMMAND_DISCONNECT = "disconnect"

        const val MAX_SCREEN_ATTACHMENT_BYTES = 8 * 1024 * 1024
        const val SCREEN_DELIVERY_TIMEOUT_MILLIS = 10_000L
    }
}

/** 草稿长度上限与主进程 `AssistantBridgeService` 的校验保持一致。 */
internal const val MAX_ASSISTANT_DRAFT_CHARS = 50_000
