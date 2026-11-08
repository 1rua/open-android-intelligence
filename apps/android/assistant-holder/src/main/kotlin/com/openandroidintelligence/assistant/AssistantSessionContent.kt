package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme

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
                    notice = state.advisory ?: state.notice.takeIf { it.isNotBlank() },
                    onDismissNotice = if (state.advisory != null) client::clearAdvisory else null,
                    previews = previews,
                    initialText = initialText,
                    onStartRecording = {
                        // 桥接层已支持 `voice`（见 AssistantBridgeClient.sendVoice），
                        // 但本入口还没有把录音器接到界面上；在接线完成前如实说明，
                        // 而不是放一个按了没反应的按钮。
                        client.showAdvisory("语音发送尚未接入当前版本，请改用文字输入")
                    },
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
