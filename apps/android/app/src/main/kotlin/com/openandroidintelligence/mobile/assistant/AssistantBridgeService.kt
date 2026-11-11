package com.openandroidintelligence.mobile.assistant

import android.app.Service
import android.content.Intent
import android.os.*
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_ALLOWED_KEYS
import com.openandroidintelligence.conversation.assistant.ASSISTANT_BRIDGE_VERSION
import com.openandroidintelligence.conversation.assistant.AssistantAccountDto
import com.openandroidintelligence.conversation.assistant.AssistantAppearanceDto
import com.openandroidintelligence.conversation.assistant.AssistantAttachmentDto
import com.openandroidintelligence.conversation.assistant.AssistantHandoffDto
import com.openandroidintelligence.conversation.assistant.AssistantHandoffState
import com.openandroidintelligence.conversation.assistant.AssistantNoticeDto
import com.openandroidintelligence.conversation.assistant.AssistantStateDto
import com.openandroidintelligence.conversation.assistant.AssistantWireBudget
import com.openandroidintelligence.conversation.assistant.encodeState
import com.openandroidintelligence.conversation.assistant.toDto
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.MAX_COMPOSER_ATTACHMENTS
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.core.model.MAX_ASSISTANT_AUDIO_BYTES
import com.openandroidintelligence.core.model.MAX_ASSISTANT_AUDIO_DURATION_MS
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.pickedAttachmentSelection
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Same-UID, unexported, versioned IPC（协议 v2）。
 *
 * 每个 IPC 客户端拥有自己的助理会话：
 * - `connect` 创建/获取会话控制器；工作区不可用时如实返回未连接；
 * - `session.new` 释放旧控制器并创建全新控制器（restoreLastThread=false）；
 * - 状态通道只发布**当前活动会话**的时间线投影，并经过 [AssistantWireBudget]
 *   的条数 / 单条正文 / 总字节预算。
 *
 * 协议只传身份与投影：不传凭据、provider URI、文件路径或附件字节。屏幕截图与
 * 原始语音由主进程读入内存后直接进入附件提交通道，字节从不进入状态帧。
 */
class AssistantBridgeService : Service() {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as OpenAndroidIntelligenceApplication).gatewayRuntime

    /** 一个客户端的会话与发布状态；全部字段只在主线程读写。 */
    private class Client(
        val reply: Messenger,
        val token: String,
        val scope: AssistantClientScope,
        var sessionToken: String? = null,
        var controller: WorkbenchController? = null,
        var observer: Job? = null,
        /** 界面是否已声明正在展示当前会话（用于抑制原生气泡）。 */
        var visible: Boolean = false,
        /** 单调递增的状态帧序号；接收方据此丢弃乱序帧。 */
        var revision: Long = 0L,
        /** 最近一次交接尝试的结果；没有请求过时为 null（帧里也不出现该字段）。 */
        var handoff: AssistantHandoffResult? = null,
        /** 上述结果所属的请求代次；与客户端这一次上滑带来的 `handoffId` 对应。 */
        var handoffId: String? = null,
    )

    private val clients = linkedMapOf<IBinder, Client>()

    private val handler: Handler = Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != android.os.Process.myUid()) return@Handler true
        val reply = message.replyTo ?: return@Handler true
        val b = message.data
        // 协议字段封闭：未知字段整条丢弃，不能把新字段误当成旧语义执行。
        if (b.getInt("version") != ASSISTANT_BRIDGE_VERSION ||
            b.keySet().any { it !in ALLOWED_KEYS }) return@Handler true
        val command = b.getString("command") ?: return@Handler true
        if (command == "connect") {
            if (clients.size >= MAX_CLIENTS && !clients.containsKey(reply.binder)) return@Handler true
            val scope = AssistantClientScope(runtime.connectedAccountId)
            scope.bind(runtime.connectedAccountId)
            val client = Client(reply, UUID.randomUUID().toString(), scope)
            clients[reply.binder] = client
            runCatching {
                reply.binder.linkToDeath({
                    handler.post {
                        clients.remove(reply.binder)?.let(::releaseClient)
                    }
                }, 0)
            }
            openSession(client, UUID.randomUUID().toString())
            publish(client)
            return@Handler true
        }
        val client = clients[reply.binder] ?: return@Handler true
        if (b.getString("token") != client.token) return@Handler true
        when (command) {
            "disconnect" -> {
                clients.remove(reply.binder)
                releaseClient(client)
                return@Handler true
            }
            "session.new" -> {
                val created = openSession(client, UUID.randomUUID().toString())
                publish(client, if (created == null) "无法创建新会话，请检查当前连接" else null)
            }
            "presence" -> {
                client.visible = b.getBoolean("visible")
                runtime.setAssistantPresence(client.token, client.sessionToken, client.visible)
                publish(client)
            }
            // 交接是唯一「不靠异常报错」的命令：结果必须随状态帧回执，
            // 因为拒绝原因（账号/Agent 不一致、附件未就绪）需要原文展示给用户。
            // `handoffId` 把回执绑定到这一次请求，避免上一轮的终态被当成本轮结果。
            "handoff" -> runHandoff(client, reply, b.getString("handoffId").orEmpty())
            else -> {
                if (!client.scope.canShare(runtime.connectedAccountId)) { publish(client); return@Handler true }
                // 没有控制器（未登录 / 工作台尚未就绪）时必须说清为什么没反应。
                // 静默丢弃会让用户看到「选了文件、界面回来了、什么都没发生」——
                // 圈选与 ＋ 附件都走这条路径，两者都会变成看起来坏掉的功能。
                val controller = client.controller ?: run {
                    publish(client, "尚未连接对话，附件与草稿未能加入；请先在 App 中连接并打开一条会话")
                    return@Handler true
                }
                publish(client, handleCommand(client, controller, command, b))
            }
        }
        true
    }

    /** 执行一条控制命令；返回需要提示给用户的错误文案，成功时为 null。 */
    private fun handleCommand(client: Client, controller: WorkbenchController, command: String, b: Bundle): String? {
        try {
            when (command) {
                "draft" -> {
                    val text = b.getString("text") ?: ""
                    require(text.length <= MAX_DRAFT_CHARS) { "DRAFT_TOO_LARGE" }
                    controller.editDraft(text)
                }
                "send" -> controller.sendDraft()
                "cancel" -> controller.stopGeneration()
                "attachment.remove" -> {
                    val draftId = b.getString("draftId") ?: error("DRAFT_ID_REQUIRED")
                    controller.removeAttachment(draftId)
                }
                "approval" -> {
                    val approvalId = b.getString("approvalId") ?: error("APPROVAL_ID_REQUIRED")
                    val choice = ApprovalChoice.of(b.getString("choice"))
                    require(choice != ApprovalChoice.UNKNOWN) { "APPROVAL_CHOICE_INVALID" }
                    controller.decideApproval(approvalId, choice)
                }
                // 时间线读取失败与未确认发送失败是两类不同的重试，必须由调用方明示。
                "retry" -> when (b.getString("target")) {
                    "timeline" -> controller.retryTimeline()
                    "submission" -> controller.retryPendingSubmissions()
                    else -> error("RETRY_TARGET_INVALID")
                }
                "screen" -> readScreenAttachment(client, controller, b)
                "voice" -> readVoiceAttachment(client, controller, b)
                "attachment" -> queueFileAttachments(client, controller, b)
                else -> error("IPC_COMMAND_UNSUPPORTED")
            }
        } catch (_: Exception) {
            return "操作未完成，请检查当前连接或对话"
        }
        return null
    }

    /** 屏幕圈选 PNG：与 v1 相同的读取/校验路径，写进 v2 的普通附件通道。 */
    private fun readScreenAttachment(client: Client, controller: WorkbenchController, b: Bundle) {
        val fd = b.parcelFileDescriptor("content") ?: error("CONTENT_REQUIRED")
        val size = b.getInt("size")
        if (size !in 1..MAX_SCREEN_ATTACHMENT_BYTES) { fd.close(); error("CONTENT_TOO_LARGE") }
        // 展示文件名由助手进程给出：圈选与拍照都走这条命令，硬编码会让两张附件同名。
        // 只接受安全形状（非空、无路径分隔符、长度受限），其余回落到圈选默认名 ——
        // 这个名字会出现在附件卡与相册里，不能把任意字符串直接塞进去。
        val requested = b.getString("filename").orEmpty()
        val filename = requested.takeIf {
            it.isNotBlank() && it.length <= MAX_ATTACHMENT_FILENAME_CHARS &&
                '/' !in it && '\\' !in it && it.none { ch -> ch.isISOControl() }
        } ?: SCREEN_ATTACHMENT_FILENAME
        enqueueAttachmentRead(client, controller, fd, size, "屏幕附件无法读取，请重新圈选",
            build = { bytes ->
                check(bytes.take(PNG_SIGNATURE.size).toByteArray().contentEquals(PNG_SIGNATURE)) { "CONTENT_TYPE_INVALID" }
                LocalAttachmentSelection(
                    filename = filename,
                    mediaType = "image/png",
                    // 与 v1 一致：暂存消费完这批字节后立即清零本地副本。
                    contentSource = AttachmentContentSource.fromWriter { output ->
                        try { output.write(bytes) } finally { bytes.fill(0) }
                    },
                    recoverAfterRestart = false,
                )
            },
            consume = { target, selection, _ ->
                target.addAttachment(selection)
                null
            },
        )
    }

    /**
     * 原始语音：字节流 + 时长 + 媒体类型直达附件提交通道。
     *
     * 语音是**一次纯附件提交**，不占用文字草稿；被控制器拒绝（正在生成、会话切换等）
     * 时退回草稿，并明确提示，而不是假装已经发送。
     */
    private fun readVoiceAttachment(client: Client, controller: WorkbenchController, b: Bundle) {
        val fd = b.parcelFileDescriptor("content") ?: error("CONTENT_REQUIRED")
        val size = b.getInt("size")
        val durationMs = b.getLong("durationMs")
        val mediaType = b.getString("mediaType").orEmpty()
        if (size !in 1..MAX_ASSISTANT_AUDIO_BYTES.toInt()) { fd.close(); error("CONTENT_TOO_LARGE") }
        if (durationMs !in 1L..MAX_ASSISTANT_AUDIO_DURATION_MS) { fd.close(); error("DURATION_INVALID") }
        if (!mediaType.startsWith("audio/")) { fd.close(); error("CONTENT_TYPE_INVALID") }
        enqueueAttachmentRead(client, controller, fd, size, "语音附件无法读取，请重新录制",
            build = { bytes ->
                LocalAttachmentSelection(
                    filename = "assist-voice.${audioExtension(mediaType)}",
                    mediaType = mediaType,
                    // 每个 openStream 返回新的读流；字节由本次选择持有到草稿结束。上传失败重试
                    // 仍需同一份原始字节，因此不能提前清零，内存副本随草稿释放而回收。
                    contentSource = object : AttachmentContentSource {
                        override fun openStream() = ByteArrayInputStream(bytes)
                    },
                    recoverAfterRestart = false,
                )
            },
            consume = { target, selection, _ ->
                if (target.sendVoiceAttachment(selection, target.state.value.activeThreadId)) null
                else {
                    // 没发出去就必须退回草稿，由草稿重新上传；不能假装已经发送。
                    target.addAttachment(selection)
                    "语音未能发送，已放入附件草稿"
                }
            },
        )
    }

    /**
     * 文件附件：来自浮层的系统选择器，只带 URI 字符串。
     *
     * 不复制字节：同 UID + 持久化读授权让主进程直接读同一 URI，避免 8MB 级字节跨进程
     * 传输，也避免在本进程再实现一套文件读取逻辑（`pickedAttachmentSelection` 已在
     * app 模块里，含 `takePersistableUriPermission`）。
     *
     * 每个 URI 独立入草稿：某个文件读不出来只提示这一个，其余照常加入，不整批失败。
     *
     * **整批先登记再解析**：从「收到 URI」到「附件进草稿」之间有一段异步窗口
     * （查 provider 元数据、加载缩略图）。用户选完立刻上滑时，交接若只看当前附件列表，
     * 会在这段窗口里看到空列表并判定就绪；主进程接受、浮层关闭后解析结果才回来，
     * 客户端已被移除，刚选的文件既没迁移也没有提示。登记之后
     * `awaitHandoffAttachments` 会一直等到整批结束（有上限）。
     */
    private fun queueFileAttachments(client: Client, controller: WorkbenchController, b: Bundle) {
        val uris = fileAttachmentUris(b.getStringArray("uris"), b.getInt("count", -1))
            ?: error("ATTACHMENT_URIS_INVALID")
        val targetRevision = controller.attachmentTargetRevision
        // 按剩余槽位裁剪这一批：整批登记，整批释放，不留下永久挂起的计数。
        val accepted = uris.take(controller.availableAttachmentSlots)
        if (accepted.isEmpty()) {
            publish(client, "每条消息最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件，请先移除或发送后再添加")
            return
        }
        controller.beginAttachmentImport(accepted.size)
        jobs.launch {
            var failures = 0
            val dropped = uris.size - accepted.size
            try {
                for (raw in accepted) {
                    val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
                    val selection = if (uri != null) {
                        runCatching { withContext(Dispatchers.IO) { pickedAttachmentSelection(applicationContext, uri) } }.getOrNull()
                    } else {
                        null
                    }
                    if (selection == null) {
                        failures++
                        continue
                    }
                    withContext(Dispatchers.Main) {
                        // 会话/控制器一旦变化就停止，剩余文件不再加入别人的草稿。
                        if (clients[client.reply.binder] !== client || client.controller !== controller ||
                            controller.attachmentTargetRevision != targetRevision) return@withContext
                        if (controller.availableAttachmentSlots == 0) return@withContext
                        controller.addAttachment(selection)
                    }
                }
            } finally {
                controller.finishAttachmentImport(accepted.size)
            }
            withContext(Dispatchers.Main) {
                val reason = when {
                    failures > 0 && dropped > 0 -> "有 $failures 个附件无法读取，$dropped 个超出数量上限，请重新选择"
                    failures > 0 -> "有 $failures 个附件无法读取，请重新选择"
                    dropped > 0 -> "附件已达上限，只加入了前 ${accepted.size} 个"
                    else -> null
                }
                if (clients[client.reply.binder] !== client) return@withContext
                if (reason != null) publish(client, reason)
            }
        }
    }

    /**
     * 读取有限字节，确认客户端/账号/会话均未变化后交给 [consume]。
     *
     * 10 秒看门狗防止读端挂死；读取失败、长度不符或作用域已变化时丢弃字节并提示，
     * 绝不错写进别人的会话。
     *
     * 字节归属：[build] 成功后字节归返回的选择所有（由内容源或草稿消费），调用方
     * 不再清零；只有读取失败或作用域变化时才由这里清零。
     */
    private fun enqueueAttachmentRead(
        client: Client,
        controller: WorkbenchController,
        fd: ParcelFileDescriptor,
        size: Int,
        failureNotice: String,
        build: (ByteArray) -> LocalAttachmentSelection,
        consume: suspend (WorkbenchController, LocalAttachmentSelection, ByteArray) -> String?,
    ) {
        jobs.launch(Dispatchers.IO) {
            var bytes: ByteArray? = null
            var handedOff = false
            val watchdog = Runnable { runCatching { fd.close() } }
            handler.postDelayed(watchdog, ATTACHMENT_READ_TIMEOUT_MILLIS)
            try {
                val content = ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
                    val buffer = ByteArray(size)
                    var offset = 0
                    try {
                        while (offset < size) {
                            val count = stream.read(buffer, offset, size - offset)
                            check(count > 0) { "CONTENT_LENGTH_MISMATCH" }
                            offset += count
                        }
                        check(stream.read() == -1) { "CONTENT_LENGTH_MISMATCH" }
                        buffer
                    } catch (cause: Throwable) {
                        buffer.fill(0)
                        throw cause
                    }
                }
                bytes = content
                val selection = build(content)
                withContext(Dispatchers.Main) {
                    check(clients[client.reply.binder] === client &&
                        client.scope.canShare(runtime.connectedAccountId) &&
                        client.controller === controller) { "ASSISTANT_SCOPE_CHANGED" }
                    // 字节已归 selection 所有（内容源/草稿负责消费或回收），这里不再清零。
                    handedOff = true
                    consume(controller, selection, content)?.let { notice -> publish(client, notice) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (clients[client.reply.binder] === client) publish(client, failureNotice)
                }
            } finally {
                handler.removeCallbacks(watchdog)
                runCatching { fd.close() }
                if (!handedOff) bytes?.fill(0)
            }
        }
    }

    /**
     * 执行一次「把助手会话交给主 App」的请求。
     *
     * 严格失败：所有拒绝原因都带回浮层，草稿与附件保持在助手控制器里。
     * 身份不匹配（账号切换）时连 controller 都不该借用。
     *
     * 交接要等附件就绪（有上限），因此它在独立协程里完成；期间的重复请求直接忽略，
     * 结果随状态帧下发。
     *
     * **回执带请求代次**：客户端每次上滑带来一个新的 `handoffId`，结果原样带回。
     * 没有代次时上一轮的终态会留在状态帧里，下一轮请求会立刻把它当成自己的结果。
     */
    private fun runHandoff(client: Client, reply: Messenger, handoffId: String) {
        // 重复上滑（同一代次重复送达）直接忽略；不同代次说明这是新的一次请求，
        // 必须覆盖上一轮结果，否则客户端永远等不到本轮回执。
        if (handoffInFlight && handoffId == client.handoffId) return
        fun settle(result: AssistantHandoffResult) {
            client.handoff = result
            client.handoffId = handoffId
            publish(client)
        }
        val token = client.sessionToken ?: run {
            settle(AssistantHandoffResult.Rejected(AssistantHandoffReasons.NOT_CONNECTED))
            return
        }
        if (!client.scope.canShare(runtime.connectedAccountId)) {
            settle(AssistantHandoffResult.Rejected(AssistantHandoffReasons.ACCOUNT_MISMATCH))
            return
        }
        handoffInFlight = true
        jobs.launch {
            val result = try {
                runtime.prepareAssistantHandoff(token)
            } catch (_: CancellationException) {
                AssistantHandoffResult.Rejected(AssistantHandoffReasons.NOT_CONNECTED)
            } finally {
                handoffInFlight = false
            }
            // 会话已被换掉（session.new / disconnect）时结果不再属于当前客户端。
            if (clients[reply.binder] !== client || client.sessionToken != token) return@launch
            settle(result)
        }
    }

    /** 同一时刻只允许一次交接：重复上滑不会并发迁移同一份草稿。 */
    private var handoffInFlight = false

    /**
     * 打开一条新会话。
     *
     * 会话身份先登记、再尝试取控制器：工作区可能晚于 connect 建立（正在连接、Agent
     * 尚未发现）。此时保留 sessionToken，`onCreate` 的版本观察者会在工作区就绪后
     * 接回同一会话，而不是把界面永久停在未连接。
     */
    private fun openSession(client: Client, sessionToken: String): WorkbenchController? {
        // 会话切换不代表界面隐藏：可见性属于客户端，跨 session.new 保留。
        val wasVisible = client.visible
        releaseSession(client)
        client.sessionToken = sessionToken
        if (wasVisible) {
            client.visible = true
            runtime.setAssistantPresence(client.token, sessionToken, true)
        }
        if (!runtime.ensureAssistantWorkspace()) return null
        val controller = runtime.createAssistantSessionController(sessionToken) ?: return null
        client.controller = controller
        client.observer = jobs.launch {
            // 只发布当前活动会话；后台会话的变化不占状态通道。
            controller.state.collect { if (client.controller === controller) publish(client) }
        }
        return controller
    }

    private fun releaseSession(client: Client) {
        client.observer?.cancel()
        client.observer = null
        // 会话换掉后旧会话不再被展示；可见性标记本身留在客户端上，供新会话沿用。
        runtime.setAssistantPresence(client.token, null, false)
        client.sessionToken?.let(runtime::releaseAssistantSession)
        client.sessionToken = null
        client.controller = null
    }

    /**
     * 客户端离开：交给运行时按在途状态延迟关闭会话。
     *
     * 界面关闭不取消已被 Gateway 接受的提交；运行时在工作区里等终态到达后再释放。
     */
    private fun releaseClient(client: Client) {
        releaseSession(client)
        client.visible = false
        runtime.releaseAssistantClient(client.token)
    }

    /**
     * 发布 v2 状态帧。
     *
     * 只含当前活动会话的时间线投影；时间线经过线上预算，条数被丢弃时
     * `timelineTruncated=true`，单条正文截断由该条自己的 `textTruncated` 标记。
     */
    private fun publish(client: Client, error: String? = null) {
        bindCurrentAccount(client)
        val shared = client.scope.canShare(runtime.connectedAccountId)
        val controller = client.controller
        val state = controller?.state?.value
        val connected = controller != null && runtime.connectedAccountId != null &&
            client.scope.accountId == runtime.connectedAccountId
        val entries = if (shared) (state?.timeline as? Loadable.Ready)?.value.orEmpty() else emptyList()
        // 先按条数裁到预算上限再交给预算器：长会话不会为早已丢弃的旧条目反复序列化。
        val trimmed = if (entries.size > AssistantWireBudget.MAX_ENTRIES) {
            entries.takeLast(AssistantWireBudget.MAX_ENTRIES)
        } else entries
        val (budgeted, truncated) = AssistantWireBudget.applyBudget(trimmed)
        val dropped = truncated || trimmed.size < entries.size
        val appearance = (application as OpenAndroidIntelligenceApplication).appearancePreferences.settings.value
        val profileId = runtime.connectedProfileId
        val accountId = runtime.connectedAccountId
        val notice = (error ?: state?.notice)?.let(::noticeFor)
        val dto = AssistantStateDto(
            version = ASSISTANT_BRIDGE_VERSION,
            revision = ++client.revision,
            connected = connected,
            account = if (connected && profileId != null && accountId != null) {
                AssistantAccountDto(
                    profileId = profileId,
                    accountId = accountId,
                    agentName = runtime.connectedAgentName.orEmpty(),
                )
            } else null,
            sessionId = client.sessionToken,
            generation = (state?.generation ?: GenerationState.IDLE).name,
            composer = (state?.composer ?: ComposerState.EDITING).name,
            creatingThread = state?.creatingThread == true,
            draft = if (shared) state?.draft.orEmpty() else "",
            activeThread = if (shared) state?.activeThreadId else null,
            timeline = if (shared) budgeted.map { it.toDto() } else emptyList(),
            timelineTruncated = dropped && shared,
            attachments = if (shared) state?.attachments.orEmpty().map { it.toAssistantDto() } else emptyList(),
            notice = notice,
            appearance = AssistantAppearanceDto(
                theme = appearance.theme.name,
                dynamicColor = appearance.dynamicColor,
                palette = appearance.palette.name,
                reduceMotion = appearance.reduceMotion,
            ),
            // 交接结果随状态帧下发：浮层据此决定打开主 App 还是保留自己。
            // 代次一并下发，客户端才能区分「这一轮的回执」与「上一轮残留的终态」。
            handoff = client.handoff.takeIf { client.sessionToken != null }
                ?.toDto(accountId, profileId, client.handoffId.orEmpty()),
        )
        val payload = runCatching { encodeState(dto) }.getOrNull() ?: return
        runCatching {
            client.reply.send(Message.obtain().apply {
                data = Bundle().apply {
                    putInt("version", ASSISTANT_BRIDGE_VERSION)
                    putString("token", client.token)
                    putString("state", payload)
                }
            })
        }.onFailure { clients.remove(client.reply.binder) }
    }

    /**
     * 账号可用即绑定：会话恢复后把客户端接到当前账号。
     * 已绑定到另一个账号时保持失败关闭，等客户端重新 connect，避免旧界面继续读到新账号的内容。
     */
    private fun bindCurrentAccount(client: Client) {
        if (client.controller == null) return
        val connectedAccountId = runtime.connectedAccountId
        if (client.scope.accountId == null || client.scope.accountId == connectedAccountId) {
            client.scope.bind(connectedAccountId)
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        runtime.restoreSessionIfAvailable()
        // 工作区可能晚于 connect 建立（连接刚恢复、Agent 刚发现），也可能因账号切换
        // 退役后重建：按 token 重新取一次当前工作区持有的控制器。
        // `open` 对同一 token 是幂等的，因此重复触发不会替换仍在发送的控制器。
        jobs.launch {
            runtime.assistantWorkspaceVersion.collect {
                clients.values.toList().forEach { client ->
                    val token = client.sessionToken ?: return@forEach
                    val current = runtime.reattachAssistantSession(token)
                    if (current != null && current !== client.controller) {
                        client.observer?.cancel()
                        client.controller = current
                        client.observer = jobs.launch {
                            current.state.collect { if (client.controller === current) publish(client) }
                        }
                        if (client.visible) runtime.setAssistantPresence(client.token, token, true)
                    }
                    publish(client)
                }
            }
        }
    }

    override fun onDestroy() {
        clients.values.toList().forEach(::releaseClient)
        clients.clear()
        jobs.cancel()
        super.onDestroy()
    }

    private companion object {
        const val MAX_CLIENTS = 4
        const val MAX_DRAFT_CHARS = 50_000
        const val MAX_SCREEN_ATTACHMENT_BYTES = 8 * 1024 * 1024
        const val ATTACHMENT_READ_TIMEOUT_MILLIS = 10_000L
        /** 附件展示文件名的长度上限；超出按默认名处理，不把任意长串塞进附件卡。 */
        const val MAX_ATTACHMENT_FILENAME_CHARS = 120
        /** 圈选附件的默认展示文件名；助手进程未给名字时使用。 */
        const val SCREEN_ATTACHMENT_FILENAME = "assist-screen.png"
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val ALLOWED_KEYS = ASSISTANT_BRIDGE_ALLOWED_KEYS
    }
}

/**
 * 文件附件批次的校验与去重（纯函数，便于直接单测）。
 *
 * 与屏幕/语音的字节管道不同，文件附件只传 URI：主进程按 URI 直接读文件
 * （同 UID + 持久化读授权）。`count` 必须等于去重后的实际条数 —— 不一致说明发送方
 * 与本端对批次的理解已经分裂，宁可整批拒绝，也不猜哪一半是对的。
 *
 * @return 去重后的 URI 列表；空列表、超过上限或计数不一致时返回 null。
 */
internal fun fileAttachmentUris(uris: Array<String>?, count: Int): List<String>? {
    val distinct = uris.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
    if (distinct.isEmpty() || distinct.size > MAX_COMPOSER_ATTACHMENTS) return null
    if (count != distinct.size) return null
    return distinct
}

/** 把运行时提示折成稳定诊断码 + 原文；非诊断码前缀统一为 `ASSISTANT_NOTICE`。 */
internal fun noticeFor(message: String): AssistantNoticeDto {
    val separator = message.indexOf(':')
    val candidate = if (separator in 1..64) message.substring(0, separator) else ""
    val stable = candidate.isNotEmpty() && candidate.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    return if (stable) AssistantNoticeDto(candidate, message) else AssistantNoticeDto("ASSISTANT_NOTICE", message)
}

/** 交接结果 → 状态帧投影；身份字段供浮层构造打开主 App 的深链。 */
internal fun AssistantHandoffResult.toDto(
    accountId: String?,
    profileId: String?,
    handoffId: String = "",
): AssistantHandoffDto = when (this) {
    is AssistantHandoffResult.Accepted -> AssistantHandoffDto(
        handoffId = handoffId,
        state = AssistantHandoffState.ACCEPTED.wireValue,
        conversationId = target.conversationId,
        profileId = target.profileId.ifBlank { profileId.orEmpty() },
        accountId = target.accountId.ifBlank { accountId.orEmpty() },
        agentId = target.agentId,
        skippedAttachments = skippedAttachments,
    )
    is AssistantHandoffResult.Rejected -> AssistantHandoffDto(
        handoffId = handoffId,
        state = AssistantHandoffState.REJECTED.wireValue,
        reason = reason,
        profileId = profileId.orEmpty(),
        accountId = accountId.orEmpty(),
    )
}

/** 草稿附件投影；线上只传元数据，不传字节或路径。 */
internal fun com.openandroidintelligence.conversation.model.AttachmentDraft.toAssistantDto(): AssistantAttachmentDto =
    AssistantAttachmentDto(
        draftId = id.value,
        filename = filename,
        mediaType = mediaType,
        isImage = mediaType.startsWith("image/", ignoreCase = true),
        localKey = id.value,
        localState = when (state) {
            AttachmentState.RETRYABLE_FAILURE, AttachmentState.TERMINAL_FAILURE,
            AttachmentState.CANCELLED -> AttachmentLocalState.FAILED
            AttachmentState.OUTCOME_UNKNOWN -> AttachmentLocalState.UNAVAILABLE
            else -> AttachmentLocalState.PENDING
        }.name,
    )

/** 语音媒体类型到文件扩展名；未知类型保持 m4a（AAC 容器），不编造格式。 */
internal fun audioExtension(mediaType: String): String = when (mediaType.lowercase()) {
    "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
    "audio/aac" -> "aac"
    "audio/mpeg" -> "mp3"
    "audio/ogg", "audio/opus" -> "ogg"
    "audio/webm" -> "webm"
    "audio/wav", "audio/x-wav" -> "wav"
    else -> "m4a"
}
