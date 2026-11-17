package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.openandroidintelligence.conversation.components.ExpandableRegion
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 独立调用时的思维链流式呈现驱动器：复用与正文完全一致的 [StreamingPresentationState]
 * 逐字素推进引擎与 250ms 终态排空机制，放在 [ExpandableRegion] 外层以保留进度。
 */
@Stable
internal class LocalThoughtPresentationDriver(
    initialText: String,
    initialLive: Boolean,
) {
    private val engine = StreamingPresentationState(initialText = initialText, initialRevision = 0L)
    private var revision = 0L
    private var nextBatch = 0L
    private var lastText = initialText
    private var lastLive = initialLive

    var frame by mutableStateOf(
        StreamingPresentationFrame(
            text = engine.displayedText,
            active = initialLive,
            batchId = 0L,
            start = engine.revealOffset,
            end = engine.revealOffset,
        ),
    )
        private set

    var hasPending by mutableStateOf(false)
        private set

    fun sync(text: String, live: Boolean, nowNanos: Long) {
        if (text == lastText && live == lastLive && !engine.needsFrame) {
            if (!live && !engine.isPresenting && frame.active) {
                frame = frame.copy(active = false)
            }
            return
        }
        if (text != lastText) revision++
        lastText = text
        lastLive = live
        val advance = engine.update(
            text = text,
            revision = revision,
            origin = StreamPresentationOrigin.LIVE,
            streaming = live,
            nowNanos = nowNanos,
        )
        publish(advance, live)
    }

    fun advance(nowNanos: Long) {
        publish(engine.advance(nowNanos), lastLive)
    }

    private fun publish(advance: StreamingRevealAdvance, live: Boolean) {
        hasPending = engine.needsFrame
        val active = live || engine.isPresenting
        val previous = frame
        if (advance.changed) {
            frame = StreamingPresentationFrame(
                text = engine.displayedText,
                active = active,
                batchId = ++nextBatch,
                start = if (advance.corrected) engine.revealOffset else advance.startOffset,
                end = engine.revealOffset,
            )
        } else if (previous.active != active || previous.text != engine.displayedText) {
            frame = previous.copy(text = engine.displayedText, active = active)
        }
    }
}

/**
 * 思维链流式呈现容器：对齐 [StreamingReplyBody] 的 `mountedBatch`、`committedFrame` 门控与
 * 增量 `sourceRevealStart..sourceRevealEnd` 渐显范围，且不触发触觉回馈。
 */
@Composable
internal fun StreamingThoughtBody(
    thoughtText: String,
    frame: StreamingPresentationFrame?,
    live: Boolean,
    reduceMotion: Boolean,
    identityKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable (blocks: List<TimelineBlock>, active: Boolean) -> Unit,
) {
    val mountedBatch = remember(identityKey) { frame?.batchId ?: 0L }
    val displayed = if (reduceMotion) thoughtText else (frame?.text ?: thoughtText)
    val document = rememberStreamingMarkdownDocument(displayed, narrative = true)
    var committedFrame by remember(identityKey) { mutableStateOf<StreamingPresentationFrame?>(null) }
    val renderFrame = if (document.sourceText == displayed || frame?.start == frame?.end) frame else committedFrame
    SideEffect { if (document.sourceText == displayed) committedFrame = frame }

    val active = live || frame?.active == true || (frame != null && document.sourceText != displayed)
    val newBatch = !reduceMotion && renderFrame != null && renderFrame.batchId > mountedBatch
    val revealStart = if (newBatch) renderFrame!!.start else renderFrame?.end ?: displayed.length
    val revealEnd = renderFrame?.end ?: displayed.length

    val presentation = StreamingTextPresentation(
        batchId = renderFrame?.batchId ?: 0L,
        sourceRevealStart = revealStart.coerceIn(0, displayed.length),
        sourceRevealEnd = revealEnd.coerceIn(0, displayed.length),
        active = active,
        reduceMotion = reduceMotion,
        onVisibleAdvance = {},
        hapticSourceRanges = emptyList(),
    )
    StreamingMarkdownContainer(
        sourceText = displayed,
        presentation = presentation,
        document = document,
        modifier = modifier,
    ) {
        content(document.blocks, active)
    }
}

/**
 * 思维链呈现卡片 (Thought Card)：展示推理思考过程（规格 §4.1）。
 *
 * 状态与交互：
 * - **流式**：默认展开，标题「正在思考」+ 跳动点，正文接入 [StreamingThoughtBody] 逐字素渐显与光标，liveRegion=Polite；
 * - **完成**：自动收起，标题「已思考 · N 字 · 可选耗时 · 点击展开/收起」；
 * - **中断**（未闭合但消息已不再流式）：标题「思考已中断 · N 字 · 点击展开」，warning 色 + 图标；
 * - **空内容**：流式中只显示光标，结束后显示「无思考内容」；
 * - 用户点击标题后记录 override，后续流式完成不再强制重置展开状态。
 *
 * 正文走 [MarkdownParser.parseNarrative] + [MarkdownDocumentView]：
 * 不识别 `<think>` / `<tool_call>`，避免思考正文里再嵌套出思考卡；
 * 通过 [LocalMarkdownCompact] 使用 13sp / 18.2sp 的紧凑 Markdown。
 *
 * @param timingKey 宿主提供的耗时观察 key（`<entryKey>#<blockIndex>`）；
 *   为 null 时不显示耗时。
 */
@Composable
fun MarkdownThoughtBlockView(
    block: TimelineBlock.ThoughtBlock,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    timingKey: String? = null,
    thoughtKey: String? = timingKey,
    presentations: StreamingPresentationRegistry? = LocalStreamingPresentationRegistry.current,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val stateColors = LocalAppStateColors.current

    val live = isStreaming && !block.isComplete
    val interrupted = !isStreaming && !block.isComplete
    val hasContent = block.thought.isNotBlank()
    val expandable = hasContent || live

    // 用户手动展开/收起优先于自动状态；live 结束时不覆盖用户选择。
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: live

    val timingStore = LocalExecutionTiming.current
    LaunchedEffect(timingKey, live) {
        if (timingKey != null) timingStore?.observe(timingKey, live)
    }
    // 运行中的卡片被移出组合（滚动/折叠）时在此结账，避免把离开期间也算进耗时。
    DisposableEffect(timingKey, timingStore) {
        onDispose {
            if (timingKey != null) timingStore?.settleOnDispose(timingKey)
        }
    }
    val durationMillis = timingKey?.let { timingStore?.durationOf(it) }
    val durationSuffix = durationMillis?.let { " · ${formatExecutionDuration(it)}" } ?: ""

    val useHostedRegistry = presentations != null && thoughtKey != null
    val hostedFrame = if (useHostedRegistry) presentations!!.thoughtFrameFor(thoughtKey, live) else null
    val localDriver = if (!useHostedRegistry) {
        remember(thoughtKey ?: Unit) {
            LocalThoughtPresentationDriver(
                initialText = if (live && !reduceMotion) "" else block.thought,
                initialLive = live,
            )
        }
    } else {
        null
    }
    if (localDriver != null) {
        LaunchedEffect(localDriver, block.thought, live, reduceMotion) {
            if (!reduceMotion) {
                withFrameNanos { localDriver.sync(block.thought, live, it) }
            }
        }
        val pending = localDriver.hasPending
        LaunchedEffect(localDriver, pending, reduceMotion) {
            if (!reduceMotion) {
                while (localDriver.hasPending) {
                    withFrameNanos { localDriver.advance(it) }
                }
            }
        }
    }

    val activeFrame = hostedFrame ?: localDriver?.frame
    // 与 StreamingReplyBody 对齐：一旦已进入流式帧驱动（activeFrame != null），排空末帧
    // active 翻转为 false 时继续保持 StreamingThoughtBody / StreamingMarkdownContainer 挂载，
    // 让最终批次的 120ms 淡入动画与状态自然收敛，避免末帧硬切销毁容器。
    val useStreamingContainer = !reduceMotion && (live || activeFrame != null)
    val narrativeBlocks = remember(block.thought, useStreamingContainer) {
        if (useStreamingContainer) emptyList() else MarkdownParser.parseNarrative(block.thought)
    }

    val actionSuffix = if (expanded) " · 点击收起" else " · 点击展开"
    val headerText = when {
        live -> if (expanded) "正在思考" else "正在思考 · 点击展开"
        hasContent && interrupted -> "思考已中断 · ${block.thought.length} 字$durationSuffix$actionSuffix"
        hasContent -> "已思考 · ${block.thought.length} 字$durationSuffix$actionSuffix"
        interrupted -> "思考已中断 · 无思考内容"
        else -> "无思考内容"
    }
    val headerColor = when {
        live -> MaterialTheme.colorScheme.primary
        interrupted -> stateColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val chevronRotation = if (reduceMotion) {
        if (expanded) 180f else 0f
    } else {
        animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = MotionSpecs.spatial(reduceMotion),
            label = "thought-chevron",
        ).value
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Compact),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimensions.MinimumTouchTarget)
                    .clip(RoundedCornerShape(AppRadius.Small))
                    .then(
                        if (expandable) {
                            Modifier
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (expanded) "收起思考过程" else "展开思考过程",
                                ) {
                                    userExpanded = !expanded
                                }
                                .semantics {
                                    stateDescription = if (expanded) "已展开" else "已收起"
                                }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Text(
                    text = headerText,
                    style = AppTextStyles.Thought,
                    color = headerColor,
                    modifier = Modifier.weight(1f),
                )
                if (live) {
                    TypingDots(reduced = reduceMotion)
                }
                if (interrupted) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "思考已中断",
                        tint = headerColor,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                }
                if (expandable) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(Dimensions.SmallIcon)
                            .rotate(chevronRotation),
                    )
                }
            }

            ExpandableRegion(expanded = expandable && expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimensions.SpaceCompact,
                            end = Dimensions.SpaceCompact,
                            bottom = Dimensions.SpaceCompact,
                        ),
                ) {
                    Spacer(Modifier.height(Dimensions.SpaceSmall))
                    if (!hasContent && live) {
                        // 流式中还没有任何思考文本：只保留一个光标表达「还在想」。
                        StreamingCursor()
                    } else if (hasContent) {
                        CompositionLocalProvider(LocalMarkdownCompact provides true) {
                            val bodyModifier = if (live) {
                                Modifier
                                    .fillMaxWidth()
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            } else {
                                Modifier.fillMaxWidth()
                            }
                            if (useStreamingContainer) {
                                StreamingThoughtBody(
                                    thoughtText = block.thought,
                                    frame = activeFrame,
                                    live = live,
                                    reduceMotion = reduceMotion,
                                    identityKey = thoughtKey ?: localDriver ?: Unit,
                                    modifier = bodyModifier,
                                ) { blocks, active ->
                                    MarkdownDocumentView(
                                        blocks = blocks,
                                        isStreaming = active,
                                    )
                                }
                            } else {
                                MarkdownDocumentView(
                                    blocks = narrativeBlocks,
                                    isStreaming = false,
                                    modifier = bodyModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
