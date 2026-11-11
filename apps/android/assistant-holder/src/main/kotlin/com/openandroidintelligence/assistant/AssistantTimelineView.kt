package com.openandroidintelligence.assistant

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.workbench.AttachmentPreviewHost
import com.openandroidintelligence.conversation.workbench.ScrollToLatestButton as WorkbenchScrollToLatestButton
import com.openandroidintelligence.conversation.workbench.ThinkingIndicator
import com.openandroidintelligence.conversation.workbench.TimelineDisplayItem
import com.openandroidintelligence.conversation.workbench.rememberStreamingPresentations
import com.openandroidintelligence.conversation.workbench.TimelineDisplayRow
import com.openandroidintelligence.conversation.workbench.rememberExecutionTimingStore
import com.openandroidintelligence.conversation.workbench.rememberTimelineDisplayItems
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Compose 测试钩子：按稳定标签断言，而不是只按可见文案断言。 */
internal object AssistantTimelineTags {
    const val ROOT = "assistant_timeline"
    const val LIST = "assistant_timeline_list"
    const val THINKING = "assistant_timeline_thinking"
}

/**
 * 时间线的渲染输入：附件已补回本进程的缩略图/时长后的条目，以及折叠后的展示项。
 *
 * 单独暴露它的意义：宿主如果已经拥有自己的 `LazyColumn`（例如
 * [AssistantExpandedPanel] 的消息列表），可以直接用 [rememberAssistantTimelineContent]
 * 拿到这两个列表，再自行调用 [TimelineDisplayRow]；不必为了复用而嵌套第二层滚动容器。
 */
internal class AssistantTimelineContent(
    /** 已补回本地缩略图与语音时长的条目；用于附件预览宿主与空态判断。 */
    val entries: List<TimelineEntry>,
    /** 折叠后的展示项（单条消息 / 执行段），按时间顺序旧 → 新。 */
    val displayItems: List<TimelineDisplayItem>,
)

/**
 * 计算一次时间线的渲染输入。
 *
 * 解析结果与附件投影都按 `(entries, previews 修订号)` 缓存：流式增量只重算变化的那一帧，
 * 本地缩略图晚到时也会主动失效一次。
 */
@Composable
internal fun rememberAssistantTimelineContent(
    entries: List<TimelineEntry>,
    previews: AssistantLocalPreviews,
    threadId: String?,
    generation: GenerationState?,
): AssistantTimelineContent {
    // 本地缩略图/时长是 Compose 状态：写入后读取它的组合会重新解析附件投影。
    // previews 本身也在 key 里：换会话会换掉整个存储实例，即使修订号恰好相同。
    val previewsRevision = previews.revision
    val resolvedEntries = remember(entries, previews, previewsRevision) {
        entries.withLocalPreviews(previews)
    }
    val displayItems = rememberTimelineDisplayItems(resolvedEntries, threadId = threadId, generation = generation)
    return remember(resolvedEntries, displayItems) { AssistantTimelineContent(resolvedEntries, displayItems) }
}

/**
 * 系统助理会话的时间线。
 *
 * 数据来自主进程经桥接协议 v2 发布、再由
 * [com.openandroidintelligence.conversation.assistant.decodeTimeline] 还原的
 * [TimelineEntry] 列表：这里只负责呈现，不持有第二份会话真相，也不连接 Gateway。
 *
 * 复用 App 内同一批渲染组件，助理界面因此不是"另一套聊天界面"：
 * - [rememberTimelineDisplayItems]：连续的助手执行过程折叠成执行段；
 * - [TimelineDisplayRow]：流式文本、Markdown、思考块与工具调用块、审批卡；
 * - [AttachmentPreviewHost]：宿主级图片大图预览（列表项回收时预览不消失）；
 * - [rememberExecutionTimingStore]：会话级执行耗时；
 * - [WorkbenchScrollToLatestButton]：与工作台同一个「回到最新」按钮。
 *
 * 附件字节不走 IPC。协议只传身份与元数据，因此图片缩略图与语音时长由本进程的
 * [AssistantLocalPreviews] 按 `localKey`（当前等于附件草稿 ID）补回；没有本地记录时
 * 界面如实显示"预览不可用 / 不显示时长"，不会伪造内容。
 *
 * 智能跟随（规格 M05 / M12 / M13）：
 * 1. 列表反向布局，索引 0 是最新一条，永远贴着输入栏；
 * 2. 用户手指落下即停止跟随，流式增量不会把正在阅读的内容拽走；
 * 3. 用户自己滑回最新处后恢复跟随；「回到最新」按钮同样恢复跟随；
 * 4. 只有仍处于跟随时，新尾巴才会平滑滚到最新；用户翻历史时保持原位。
 *
 * @param entries 当前会话的完整时间线（旧 → 新）。
 * @param previews 会话级本地缩略图与语音时长；随会话销毁。
 * @param threadId 会话身份：解析缓存与耗时记录按它隔离，切换会话时重建。
 * @param generation 当前生成状态；用于在还没有流式正文时显示"正在思考"，
 *   并让已结束的执行段立刻折叠。
 * @param onDecide 提交一次审批决策；卡片自己把 `approvalId` 绑定进回调。
 * @param listState 宿主可注入的列表状态（例如需要与其它滚动逻辑共享时）；
 *   为 null 时由本组件自行 remember。
 * @param emptyContent 没有消息且不在思考时的空态；为 null 时留白，
 *   绝不显示"示例对话"或占位消息。
 */
@Composable
internal fun AssistantTimelineView(
    entries: List<TimelineEntry>,
    previews: AssistantLocalPreviews,
    modifier: Modifier = Modifier,
    threadId: String? = null,
    generation: GenerationState? = null,
    onDecide: (approvalId: String, choice: ApprovalChoice) -> Unit = { _, _ -> },
    onDownloadAttachment: ((String) -> Unit)? = null,
    onRequestOriginalAttachment: ((String) -> Unit)? = null,
    listState: LazyListState? = null,
    emptyContent: (@Composable () -> Unit)? = null,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val isThinking = generation.isGenerating() && entries.none { !it.isUser && it.isStreaming }

    val content = rememberAssistantTimelineContent(
        entries = entries,
        previews = previews,
        threadId = threadId,
        generation = generation,
    )
    val resolvedEntries = content.entries
    val displayItems = content.displayItems
    val timing = rememberExecutionTimingStore(threadId = threadId)
    val presentations = rememberStreamingPresentations(resolvedEntries, threadId)
    val state = listState ?: rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 跟随开关归本组件所有：用户往上翻后就不再自动贴底，直到自己回到最新处。
    var followLatest by remember(threadId) { mutableStateOf(true) }
    val atLatest by remember(state) {
        derivedStateOf { AssistantSessionWindowPolicy.shouldFollowLatest(state) }
    }

    // M12：手指一落下就停止跟随，而不是等滚动结束 —— 否则流式增长会继续把内容往下拽。
    val dragging by state.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        if (dragging) followLatest = false
    }
    // 用户自己滑回最新处（手势或惯性）后恢复跟随，避免"已经贴底却还显示回到最新"。
    LaunchedEffect(state) {
        snapshotFlow {
            state.isScrollInProgress to AssistantSessionWindowPolicy.shouldFollowLatest(state)
        }.distinctUntilChanged().collect { (scrolling, latest) ->
            if (!scrolling) followLatest = latest
        }
    }

    // 尾巴身份而不是条目数：流式输出可能不新增条目，但最后一条的身份会变。
    val tailSignature = Triple(
        displayItems.size,
        displayItems.lastOrNull()?.key,
        isThinking,
    )
    var previousTailSignature by remember(threadId) { mutableStateOf(tailSignature) }
    LaunchedEffect(threadId, tailSignature) {
        val previous = previousTailSignature
        previousTailSignature = tailSignature
        // 首帧不滚动：反向布局本身就从最新一条开始，历史不会重放"跳到底部"。
        if (previous == tailSignature) return@LaunchedEffect
        // 先记录再判断：用户正在看历史时新消息同样算"已知"，
        // 回到最新后的下一条消息才不会少一次贴底。
        if (!followLatest) return@LaunchedEffect
        if (displayItems.isEmpty() && !isThinking) return@LaunchedEffect
        if (reduceMotion) state.scrollToItem(0) else state.animateScrollToItem(0)
    }

    AttachmentPreviewHost(
        attachments = resolvedEntries.flatMap { it.attachments },
        onDownload = onDownloadAttachment,
        onRequestOriginal = onRequestOriginalAttachment,
        modifier = modifier.fillMaxSize().testTag(AssistantTimelineTags.ROOT),
    ) { openPreview ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (displayItems.isEmpty() && !isThinking) {
                emptyContent?.invoke()
            } else {
                LazyColumn(
                    state = state,
                    // 反向布局：索引 0 是最新一条，永远贴着输入栏；
                    // 追加新消息时不需要整段跳转就可见（规格 M12）。
                    reverseLayout = true,
                    contentPadding = PaddingValues(
                        start = Dimensions.TimelineHorizontal,
                        end = Dimensions.TimelineHorizontal,
                        top = Dimensions.SpaceSmall,
                        bottom = Dimensions.SpaceSmall,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.TimelineSpacing),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(AssistantTimelineTags.LIST),
                ) {
                    if (isThinking) {
                        // 还没有任何流式正文时，用真实的生成状态说明"在等什么"；
                        // 不是一条消息，因此没有时间线条目身份。
                        item(key = AssistantTimelineTags.THINKING) {
                            ThinkingIndicator(
                                modifier = Modifier
                                    .testTag(AssistantTimelineTags.THINKING)
                                    .padding(vertical = Dimensions.SpaceSmall),
                            )
                        }
                    }
                    items(
                        // 反向布局的索引 0 必须是最新一条。
                        items = displayItems.asReversed(),
                        key = { it.key },
                        contentType = { it.contentType },
                    ) { item ->
                        TimelineDisplayRow(
                            item = item,
                            presentations = presentations,
                            // 助理会话与工作台共用同一套审批语义：被按下的那张卡片
                            // 就是被回答的那张审批，宿主拿到的是它的 approvalId。
                            onDecide = onDecide,
                            timing = timing,
                            onDownloadAttachment = onDownloadAttachment,
                            onRequestOriginalAttachment = onRequestOriginalAttachment,
                            onOpenAttachmentPreview = openPreview,
                            evidenceOwner = threadId,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            WorkbenchScrollToLatestButton(
                visible = !atLatest && (displayItems.isNotEmpty() || isThinking),
                onClick = {
                    followLatest = true
                    scope.launch {
                        if (reduceMotion) state.scrollToItem(0) else state.animateScrollToItem(0)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 生成中且没有终态：与工作台判断"正在思考"的口径一致，只认明确的中间状态。 */
private fun GenerationState?.isGenerating(): Boolean =
    this == GenerationState.QUEUED ||
        this == GenerationState.RUNNING ||
        this == GenerationState.CANCEL_REQUESTED

/**
 * 把本进程的本地缩略图与语音时长补回附件投影。
 *
 * - 桥接协议不传字节：[AssistantLocalPreviews.thumbnail] 按 `localKey`（当前等于
 *   附件草稿 ID）取回本进程圈选/裁剪出的图片；取不到时保持 null，渲染层显示
 *   "预览不可用"；
 * - 语音时长同理：只有录下这段音频的进程才知道真实时长，没有记录就不显示；
 * - 已经带着 previewBytes / 时长的附件（例如主 App 内直接构造的条目）保持原值；
 * - 图片且没有任何可用字节时，把本地状态如实标成 [AttachmentLocalState.UNAVAILABLE]：
 *   助理进程没有媒体端口，这类图永远不会来字节，显示一个永久转圈等于说谎。
 */
private fun List<TimelineEntry>.withLocalPreviews(
    previews: AssistantLocalPreviews,
): List<TimelineEntry> = map { entry ->
    if (entry.attachments.isEmpty()) return@map entry
    entry.copy(
        attachments = entry.attachments.map { attachment ->
            val localPreview = previews.thumbnail(attachment.draftId)
            val localDuration = previews.voiceDuration(attachment.draftId)
            val previewBytes = attachment.previewBytes ?: localPreview
            val duration = attachment.audioDurationMs ?: localDuration
            when {
                // 助理进程没有媒体端口，图片字节永远不会经 IPC 到达；本地也没有副本时，
                // 如实标成"原件不可用"，而不是留一个永远不会结束的"正在读取"。
                previewBytes == null && duration == null && attachment.isImage ->
                    attachment.copy(localState = AttachmentLocalState.UNAVAILABLE)
                previewBytes == null && duration == null -> attachment
                else -> attachment.copy(
                    previewBytes = previewBytes,
                    audioDurationMs = duration,
                )
            }
        },
    )
}
