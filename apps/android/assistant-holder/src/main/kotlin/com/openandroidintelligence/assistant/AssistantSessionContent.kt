package com.openandroidintelligence.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.openandroidintelligence.conversation.assistant.AssistantAttachmentDto
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.voice.VoiceRecorder
import com.openandroidintelligence.conversation.voice.VoiceRejection
import com.openandroidintelligence.conversation.voice.VoiceRecorderState
import com.openandroidintelligence.conversation.voice.activeRecording
import com.openandroidintelligence.conversation.voice.voiceRejectionMessage
import com.openandroidintelligence.ui.resources.R as UiR

/**
 * 圈选期间由会话持有的系统截图。
 *
 * Compose 只读：位图的生命周期仍归会话（新截图、收起、隐藏时回收），
 * 这里只是同一张图的所有权凭据。
 */
internal class AssistantScreenSelection(val bitmap: Bitmap)

/**
 * 系统助理会话与 ASSIST 兜底页共用的 Compose 界面。
 *
 * 这一层只做三件事：
 * 1. 解析最终深浅色并套用 [OpenAndroidIntelligenceTheme]（外观来自主进程下发的状态，
 *    拿不到时才回落到本地偏好）；
 * 2. 圈选态时整屏换成 [AssistantSelectionStage]（窗口此时由宿主临时铺满屏幕）；
 * 3. 其余情况渲染 [AssistantSessionRoot]，窗口高度由它测量后回传宿主。
 *
 * 界面自身不连接 Gateway、不写协议、不编造对话；所有数据经 [client] 从主进程取得。
 *
 * @param onContentHeightChanged 内容测量高度（像素）：宿主据此调用 `window.setLayout`。
 */
@Composable
internal fun AssistantSessionContent(
    client: AssistantBridgeClient,
    controller: AssistantSessionUiController,
    previews: AssistantLocalPreviews,
    modifier: Modifier = Modifier,
    selection: AssistantScreenSelection? = null,
    onFinishSelection: () -> Unit = {},
    onSelectionReady: (AssistantSelectionResult) -> Unit = {},
    onStartSelection: () -> Unit = {},
    onOpenApp: () -> Unit = {},
    onExit: () -> Unit = {},
    initialText: String? = null,
    onContentHeightChanged: (Int) -> Unit = {},
    /** 见 [AssistantSessionRoot] 的同名参数：兜底页不是系统唤起，不重置为紧凑输入。 */
    resetOnEntry: Boolean = true,
    /** 解析出的最终深浅色：宿主据此设置状态栏/导航栏图标明暗。 */
    onResolvedAppearance: (darkTheme: Boolean) -> Unit = {},
) {
    val state by client.state.collectAsState()
    val context = LocalContext.current
    // 录音器跟随客户端实例创建：会话窗口收起、组合销毁时由 DisposableEffect 关闭，
    // 麦克风与临时音频都不会跨会话残留。
    val recorder = remember(client, context, controller) {
        VoiceRecorder.create(
            context = context,
            // 结束录音与写文件都在后台线程回调；真正的发送（读字节 + 管道）也在这里。
            // 只有主进程真正接收这条语音时才派发 `VoiceSent`：界面据此展开会话并
            // 显示 Agent 的回复；失败时保持紧凑形态，与文字发送的 `SendRequested`
            // 语义一致。
            onRecorded = { voice, _ ->
                sendRecordedVoice(
                    client = client,
                    voice = voice,
                    onSent = { controller.dispatch(AssistantUiEvent.VoiceSent) },
                )
            },
            onRejected = { rejection ->
                client.showAdvisory(voiceRejectionMessage(rejection))
            },
        )
    }
    DisposableEffect(recorder) {
        onDispose { recorder.close() }
    }
    val voiceState by recorder.state.collectAsState()
    // 会话身份一变就丢弃进行中的录音：音频只属于录制它的那条对话，绝不带到新会话。
    LaunchedEffect(state.sessionId) { recorder.onSessionChanged(state.sessionId) }
    // 只有「正在录音」才替换输入栏；发送中的过渡态仍显示输入态，重复点击因此没有可乘之机。
    val recording = voiceState.activeRecording()
    // 缩略图按 previews 修订号重算：本地圈选晚到时，附件条不必等下一次桥接帧。
    // 键用附件的稳定字段而不是列表实例：每帧都会生成新的 DTO 列表，按实例缓存会让
    // 流式输出期间每帧都在主线程重复解码同一张缩略图。
    val attachmentKeys = state.attachments.map {
        listOf(it.draftId, it.filename, it.mediaType, it.isImage, it.localKey)
    }
    val previewsRevision = previews.revision
    val attachments = remember(attachmentKeys, previews, previewsRevision) {
        state.attachments.map { it.toCompactAttachment(previews) }
    }
    // 圈选产物在助理进程里只能先落在临时键上（`SCREEN_SELECTION_LOCAL_KEY`）：真正的
    // 附件草稿 ID 由主进程的附件协调器生成，回传的 `localKey` 就是那个 ID。两者不一致，
    // 缩略图就永远查不到。这里盯住「本次状态帧里新出现的图片附件」，把待认领的字节改挂
    // 到权威 ID 上，圈选完的缩略图才会在输入栏与时间线里真正显示出来。
    // 初值先记下已有附件：重连后回放的旧附件不该抢走新的待认领字节。
    var knownAttachmentIds by remember(client) {
        mutableStateOf(state.attachments.map { it.draftId }.toSet())
    }
    LaunchedEffect(state.attachments) {
        val current = state.attachments
        current
            .filter { it.isImage && it.draftId !in knownAttachmentIds }
            .forEach { previews.adoptPendingImage(it.draftId) }
        knownAttachmentIds = current.map { it.draftId }.toSet()
    }
    AssistantAppearanceScope(
        state = state,
        onResolvedAppearance = onResolvedAppearance,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            if (selection != null) {
                AssistantSelectionStage(
                    screenshot = selection.bitmap,
                    previews = previews,
                    localKey = SCREEN_SELECTION_LOCAL_KEY,
                    onSelectionReady = { result ->
                        // 产物已由舞台写入本进程预览；这里只把它交给宿主发送，舞台随后关闭。
                        // 登记这个临时键：主进程建好附件后会回传权威草稿 ID，届时把缩略图
                        // 改挂过去，输入栏与时间线才查得到这张图。
                        previews.markPendingImage(result.localKey)
                        onSelectionReady(result)
                        onFinishSelection()
                    },
                    onCancel = onFinishSelection,
                    onCropRejected = { client.showAdvisory("选区太小，请重新圈选") },
                )
            } else {
                AssistantSessionRoot(
                    draft = state.draft,
                    onDraftChange = client::updateDraft,
                    // 未连接时不禁用输入：发送失败会保留草稿并给出提示。
                    enabled = state.bridgeAvailable,
                    isGenerating = state.isGenerating,
                    isStopping = state.isStopping,
                    hasUnknownOutcome = state.hasUnknownOutcome,
                    // 主进程是否真正接收决定形态：accepted 才展开会话。
                    onSend = client::send,
                    onStop = client::cancel,
                    onExit = onExit,
                    controller = controller,
                    // 时间线由 v2 协议整条投影（执行步骤、审批卡、附件元数据）直接还原，
                    // 不再经过本进程的摘要拼装。
                    timeline = state.timeline,
                    // 会话身份用于隔离解析缓存与执行耗时：v2 支持切换对话，
                    // 不传就会让新对话沿用上一个对话的耗时记录。
                    threadId = state.activeThread,
                    // 生成状态必须一起传：执行段的终态折叠与流式光标都依赖它。
                    generation = state.generationState,
                    notice = state.advisory ?: state.notice.takeIf { it.isNotBlank() },
                    onDismissNotice = if (state.advisory != null) client::clearAdvisory else null,
                    previews = previews,
                    initialText = initialText,
                    onStartRecording = {
                        // 上一段录音还在交付时，start() 会静默返回 false；这里先给出原因，
                        // 避免麦克风键按下去没有任何反应。
                        if (voiceState is VoiceRecorderState.Sending) {
                            client.showAdvisory(
                                voiceRejectionMessage(VoiceRejection.ALREADY_SENDING),
                            )
                        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            // 权限必须在这里显式检查：录音器只会把设备异常折成 RECORDING_FAILED，
                            // 不会替用户区分「没授权」和「麦克风被占用」。
                            client.showAdvisory(context.getString(UiR.string.perm_audio_in_session_required))
                        } else {
                            // start() 自己负责防重入与失败清理；失败原因经 onRejected 提示。
                            recorder.start(state.sessionId)
                        }
                    },
                    recording = recording,
                    onCancelRecording = { recorder.cancel() },
                    // 发送时再读一次当前会话：录音期间用户切走会话，这段音频必须丢弃。
                    onFinishRecording = { recorder.send { state.sessionId } },
                    attachments = attachments,
                    onRemoveAttachment = { draftId -> client.removeAttachment(draftId) },
                    onDecide = { approvalId, choice -> client.decideApproval(approvalId, choice) },
                    onStartScreenSelection = onStartSelection,
                    onOpenApp = onOpenApp,
                    onContentHeightChanged = onContentHeightChanged,
                    resetOnEntry = resetOnEntry,
                )
            }
        }
    }
}

/**
 * 主题与外观解析：系统会话与兜底页共用同一套"深浅色/动态色/色板/减少动态"。
 *
 * 外观优先用主进程经状态通道下发的值：跨进程读 SharedPreferences 会拿到旧缓存。
 * 没有桥接状态时（尚未 connect / 旧版主进程）才回落到本地偏好，并且只读一次。
 * 解析结果通过 [onResolvedAppearance] 交回宿主，系统栏图标据此保持可读。
 */
@Composable
internal fun AssistantAppearanceScope(
    state: AssistantState,
    onResolvedAppearance: (darkTheme: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val appearanceContext = LocalContext.current
    val fallbackAppearance = remember(appearanceContext) { readFallbackAppearance(appearanceContext) }
    val appearance = state.appearance ?: fallbackAppearance
    val darkTheme = appearance.darkTheme(appearanceContext)
    val currentOnResolvedAppearance by rememberUpdatedState(onResolvedAppearance)
    // 只按真正的解析结果通知：重复回调会让宿主反复重设系统栏外观。
    LaunchedEffect(darkTheme) { currentOnResolvedAppearance(darkTheme) }
    OpenAndroidIntelligenceTheme(
        darkTheme = darkTheme,
        dynamicColor = appearance.dynamicColor,
        palette = appearance.resolvedPalette(),
        reduceMotion = appearance.reduceMotion,
        content = content,
    )
}

/** 圈选产物的本地定位键：一次会话内只保留最近一次圈选结果。 */
private const val SCREEN_SELECTION_LOCAL_KEY = "assistant-screen-selection"

/**
 * 草稿附件投影 → 输入栏的缩略条条目。
 *
 * 字节不走 IPC：图片缩略图按协议里的 `localKey` 从本进程 [AssistantLocalPreviews] 取回并解码；
 * 取不到（主进程发布的附件、旧会话）或解码失败时 `thumbnail = null`，输入栏按 `mediaType`
 * 回退到图标，而不是画一张假的图片占位。
 */
internal fun AssistantAttachmentDto.toCompactAttachment(
    previews: AssistantLocalPreviews,
): AssistantCompactAttachment = AssistantCompactAttachment(
    id = draftId,
    filename = filename.ifBlank { draftId },
    mediaType = mediaType,
    thumbnail = if (isImage) previews.thumbnail(localKey)?.toImageBitmap() else null,
)

/** 把本进程保存的缩略图字节解码成可绘制的位图；坏字节返回 null。 */
internal fun ByteArray.toImageBitmap(): ImageBitmap? = runCatching {
    BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
}.getOrNull()
