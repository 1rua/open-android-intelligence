package com.openandroidintelligence.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.voice.ActiveVoiceRecording
import com.openandroidintelligence.conversation.voice.VoiceRecordingRow
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlin.math.roundToInt

/**
 * 底部悬浮输入栏（Gemini 风格的单条胶囊）。
 *
 * 只做呈现与本地编辑：草稿状态由宿主持有（[editor]），发送、停止、录音与添加附件
 * 全部通过回调交回宿主，这里不连接 Gateway、不缓存第二份会话真相。
 *
 * 结构：
 * 1. 附件缩略条：已添加的图片/语音/文件附件一行横滑，高度变化走 [MotionSpecs.morph]；
 * 2. 输入态：多行输入（最多 6 行，高度随行数自然增长）+ 最左「＋」+ 麦克风 + 发送/停止；
 * 3. 录音态：整条内容替换为「× 取消 / 波形 + 计时 / 发送」。
 *
 * 顶部拖动横条**不在**这里：整个助手只有 [AssistantDragHandle] 一个，由
 * [AssistantSessionRoot] 在会话树最顶部渲染（需求 5.1「始终显示」）。屏幕圈选按钮也已
 * 从系统助理的输入栏移除——唤起后直接在定格截图上画圈。ASSIST 兜底页没有系统截图，
 * 仍需要一条「本入口无系统截图」的说明路径，因此 [onStartScreenSelection] 改成可空：
 * 只有兜底页传入时才会渲染那个入口，系统会话不传，输入栏里就没有圈选按钮。
 *
 * 动效：
 * - **进场**：首次组合时整条输入栏从屏幕底部之下弹出，位移、缩放与透明度共用
 *   [MotionSpecs.EntranceSpring] 的非线性弹簧（只作用于 `graphicsLayer`，不参与测量）；
 * - **展开**：输入框获得焦点或已有草稿时，卡片长高约一倍，
 *   圆角从 [AppRadius.Bubble] 过渡到 [AppRadius.ExtraLarge]；收起更快，走
 *   [MotionSpecs.CollapseSpring]。
 *
 * 视觉令牌全部来自 [Dimensions] / [AppRadius] / [MotionSpecs]；发送键在生成中形变为停止键，
 * 宽度、圆角、底色与图标在同一时间窗内一起过渡。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantCompactBar(
    editor: TextFieldState,
    onDraftChange: (String) -> Unit,
    isGenerating: Boolean,
    isStopping: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStartRecording: () -> Unit,
    /**
     * 屏幕圈选入口：**只有 ASSIST 兜底页**传入（它没有系统截图，点它给出说明）。
     *
     * 系统助理会话传 null：唤起后直接在定格截图上画圈，输入栏里不该有第二个入口。
     * 早先这个参数一直传空 lambda，兜底页的说明路径因此整体不可达。
     */
    onStartScreenSelection: (() -> Unit)? = null,
    /** 点击「＋」：打开系统文件 / 媒体选择器（ASSIST 兜底页不走这条路径）。 */
    onAddAttachment: () -> Unit = {},
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hasUnknownOutcome: Boolean = false,
    attachments: List<AssistantCompactAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    recording: ActiveVoiceRecording? = null,
    onCancelRecording: () -> Unit = {},
    onFinishRecording: () -> Unit = {},
    /**
     * 是否播放首次组合的进场动画。
     *
     * 系统唤起时输入栏单独弹出，需要 true；展开会话里的输入栏已经由面板的展开动画
     * 带出，再播一次会形成两段重复位移，因此那里传 false。
     */
    animateEntrance: Boolean = true,
    /**
     * 输入栏自身卡片的圆角形状；null 表示按展开态在
     * [AppRadius.Bubble] / [AppRadius.ExtraLarge] 之间过渡（紧凑态语义）。
     *
     * 展开会话里容器是一整张卡片：消息区在上、输入栏在下，输入栏只保留外缘底部两角，
     * 由 [InputBarShape] 传入。这样不会再套第二层 Surface（双层卡片）。
     */
    cardShape: Shape? = null,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val sendEnabled = enabled && editor.text.isNotBlank() && !isGenerating && !hasUnknownOutcome
    val density = LocalDensity.current
    val entranceRisePx = with(density) { CompactEntranceOvershoot.toPx() }
    // 减少动态或明确不要进场时，初始值就是落位态，避免第一帧闪一下完整内容。
    // 只在首次组合取值：之后切换系统「减少动态」不应重播已经播完的进场。
    val entrance = remember {
        Animatable(if (animateEntrance && !reduceMotion) 0f else 1f)
    }
    var measuredHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(entrance, animateEntrance, reduceMotion) {
        if (!animateEntrance || reduceMotion) {
            entrance.snapTo(1f)
            return@LaunchedEffect
        }
        // 先等一帧让布局测量出真实高度，起点才是「自身高度 + 32dp」。
        // 即便高度仍未测出（例如被放进零高度容器），起点也会退化为 32dp 的轻微上浮，
        // 不会因为等不到测量而永远停在透明状态。
        withFrameNanos { }
        entrance.animateTo(1f, MotionSpecs.entrance(reduced = reduceMotion))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { measuredHeightPx = it.height }
            .graphicsLayer {
                // 进度带轻微过冲（>1）时位移会略高于终点、缩放略大于 1，形成弹出回弹。
                translationY = (1f - entrance.value) * (measuredHeightPx + entranceRisePx)
                val scale = CompactEntranceStartScale +
                    (1f - CompactEntranceStartScale) * entrance.value
                scaleX = scale
                scaleY = scale
                // 透明度不允许过冲，夹在 0..1。
                alpha = entrance.value.coerceIn(0f, 1f)
            },
    ) {
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = fadeIn(MotionSpecs.fade(reduceMotion)) +
                expandVertically(animationSpec = MotionSpecs.morph(reduceMotion)),
            exit = fadeOut(MotionSpecs.fade(reduceMotion)) +
                shrinkVertically(animationSpec = MotionSpecs.morph(reduceMotion)),
        ) {
            CompactAttachmentStrip(
                attachments = attachments,
                onRemove = onRemoveAttachment,
                modifier = Modifier.padding(bottom = Dimensions.SpaceSmall),
            )
        }

        AnimatedContent(
            targetState = recording,
            // 每秒的计时更新只是同一段录音的新数据，不应重复播放进入/退出动画。
            contentKey = { it != null },
            // 两种状态高度不同：底部对齐让替换时文字与按钮不跳位。
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                fadeIn(MotionSpecs.fade(reduceMotion)) togetherWith
                    fadeOut(MotionSpecs.fade(reduceMotion))
            },
            label = "assistant-compact-mode",
        ) { activeRecording ->
            if (activeRecording != null) {
                // 录音态与主会话输入栏共用同一份 [VoiceRecordingRow]：波形、计时、取消与发送
                // 只有一个来源，两处不会各自演化成两套动画。
                VoiceRecordingRow(
                    elapsedMs = activeRecording.elapsedMs,
                    level = activeRecording.level,
                    onCancel = onCancelRecording,
                    onSend = onFinishRecording,
                    modifier = Modifier.testTag(AssistantCompactBarTags.RECORDING),
                )
            } else {
                CompactInputRow(
                    editor = editor,
                    onDraftChange = onDraftChange,
                    enabled = enabled,
                    isGenerating = isGenerating,
                    isStopping = isStopping,
                    sendEnabled = sendEnabled,
                    onSend = onSend,
                    onStop = onStop,
                    onStartRecording = onStartRecording,
                    onStartScreenSelection = onStartScreenSelection,
                    onAddAttachment = onAddAttachment,
                    cardShape = cardShape,
                )
            }
        }
    }
}

/** Compose 测试钩子：按稳定标签断言，而不是只按文案断言。 */
internal object AssistantCompactBarTags {
    const val INPUT = "assistant_compact_input"
    const val HANDLE = "assistant_compact_handle"
    const val RECORDING = "assistant_compact_recording"
    const val ATTACHMENTS = "assistant_compact_attachments"
    fun attachment(id: String): String = "assistant_compact_attachment_$id"
}

/**
 * 已加入当前草稿的附件；不携带字节或 provider 位置，仅用于展示。
 *
 * [thumbnail] 只在宿主已经解出本地缩略图时提供；没有缩略图时显示按媒体类型区分的图标，
 * 而不是画一个假的图片占位。
 */
@Immutable
internal data class AssistantCompactAttachment(
    val id: String,
    val filename: String,
    val mediaType: String,
    val thumbnail: ImageBitmap? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactInputRow(
    editor: TextFieldState,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    isStopping: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStartRecording: () -> Unit,
    onStartScreenSelection: (() -> Unit)?,
    onAddAttachment: () -> Unit,
    cardShape: Shape?,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val reportEdits = remember(editor) {
        InputTransformation {
            currentOnDraftChange(asCharSequence().toString())
        }
    }
    // 展开态：输入框获得焦点，或草稿已有内容。两者都消失才收起。
    var focused by remember { mutableStateOf(false) }
    val expanded = focused || editor.text.isNotEmpty()
    val sizeSpec = if (expanded) {
        MotionSpecs.expand<IntSize>(reduceMotion)
    } else {
        MotionSpecs.collapse<IntSize>(reduceMotion)
    }
    val animatedCorner by animateDpAsState(
        targetValue = if (expanded) AppRadius.ExtraLarge else AppRadius.Bubble,
        animationSpec = if (expanded) {
            MotionSpecs.expand(reduceMotion)
        } else {
            MotionSpecs.collapse(reduceMotion)
        },
        label = "assistant-compact-corner",
    )
    val shape = cardShape ?: RoundedCornerShape(animatedCorner)

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(sizeSpec)
            .semantics {
                // 只给展开态一个简短状态说明，收起时不额外朗读。
                if (expanded) stateDescription = "输入栏已展开"
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .padding(
                        start = Dimensions.SpaceTiny,
                        end = Dimensions.SpaceTiny,
                        top = Dimensions.SpaceTiny,
                        bottom = Dimensions.SpaceTiny,
                    )
                    .heightIn(
                        min = if (expanded) {
                            CompactExpandedMinHeight
                        } else {
                            Dimensions.ComposerMinHeight
                        },
                    ),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
            ) {
                // 最左侧「＋」：需求要求的附件入口顺序是 ＋ / 输入框 / 麦克风 / 发送。
                CompactIconAction(
                    icon = Icons.Default.Add,
                    contentDescription = "添加附件",
                    enabled = enabled,
                    onClick = onAddAttachment,
                )
                BasicTextField(
                    state = editor,
                    inputTransformation = reportEdits,
                    enabled = enabled,
                    // 等价于 maxLines = 6：超过 6 行后输入框停止增高，改为内部滚动。
                    lineLimits = TextFieldLineLimits.MultiLine(
                        minHeightInLines = 1,
                        maxHeightInLines = CompactMaxInputLines,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        // 展开态文字贴顶、按钮贴底：输入区变高时按钮往下让位而不是整体上移。
                        .then(if (expanded) Modifier.align(Alignment.Top) else Modifier)
                        .testTag(AssistantCompactBarTags.INPUT)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "助理消息输入" },
                    decorator = { innerTextField ->
                        Box(
                            modifier = Modifier.padding(vertical = Dimensions.SpaceCompact),
                        ) {
                            if (editor.text.isEmpty()) {
                                Text(
                                    text = if (enabled) "输入消息" else "助理尚不可用",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )

                CompactIconAction(
                    icon = Icons.Outlined.Mic,
                    contentDescription = "语音输入",
                    enabled = enabled,
                    onClick = onStartRecording,
                )
                // 只有 ASSIST 兜底页会传这个回调：它没有系统截图，点这里给出说明。
                // 系统会话不传，输入栏里因此没有第二个圈选入口。
                if (onStartScreenSelection != null) {
                    CompactIconAction(
                        icon = Icons.Outlined.CropFree,
                        contentDescription = "屏幕圈选",
                        enabled = enabled,
                        onClick = onStartScreenSelection,
                    )
                }
                CompactPrimaryAction(
                    stop = isGenerating,
                    enabled = if (isGenerating) !isStopping else sendEnabled,
                    contentDescription = when {
                        isStopping -> "正在停止生成"
                        isGenerating -> "停止生成"
                        else -> "发送消息"
                    },
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

/** 附件缩略条：图片、语音与普通文件共用同一行，横向滑动查看。 */
@Composable
private fun CompactAttachmentStrip(
    attachments: List<AssistantCompactAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AssistantCompactBarTags.ATTACHMENTS),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        contentPadding = PaddingValues(horizontal = Dimensions.SpaceTiny),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            CompactAttachmentTile(attachment = attachment, onRemove = onRemove)
        }
    }
}

@Composable
private fun CompactAttachmentTile(
    attachment: AssistantCompactAttachment,
    onRemove: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .testTag(AssistantCompactBarTags.attachment(attachment.id))
            .semantics { contentDescription = "附件：${attachment.filename}" },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Dimensions.MinimumTouchTarget)
                .padding(start = Dimensions.SpaceCompact),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            val thumbnail = attachment.thumbnail
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(CompactAttachmentThumbnail)
                        .clip(RoundedCornerShape(AppRadius.Small)),
                )
            } else {
                Icon(
                    imageVector = compactAttachmentIcon(attachment.mediaType),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
            }
            Text(
                text = attachment.filename,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = CompactAttachmentNameWidth),
            )
            IconButton(
                onClick = { onRemove(attachment.id) },
                modifier = Modifier.size(Dimensions.MinimumTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "移除附件：${attachment.filename}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
            }
        }
    }
}

private fun compactAttachmentIcon(mediaType: String): ImageVector = when {
    mediaType.startsWith("audio/") -> Icons.Outlined.GraphicEq
    mediaType.startsWith("image/") -> Icons.Outlined.Image
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/**
 * 圆形图标入口：视觉直径 [Dimensions.ComposerControl]，触摸目标由
 * [Dimensions.MinimumTouchTarget] 兜底；按压走 [bouncyClickable] 的轻微内收。
 */
@Composable
private fun CompactIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(Dimensions.MinimumTouchTarget)
            .clip(CircleShape)
            .bouncyClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(Dimensions.ComposerControl),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else 0.38f),
                    modifier = Modifier.size(Dimensions.Icon),
                )
            }
        }
    }
}

/**
 * 发送 / 停止主操作。
 *
 * 容器宽度、圆角、底色与图标在同一时间窗内一起过渡；减少动态时宽度与圆角直接落位，
 * 只保留极短颜色过渡。录音态的发送与输入态共用一个构件，只换图标语义。
 */
@Composable
private fun CompactPrimaryAction(
    stop: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val width by animateDpAsState(
        targetValue = if (stop) CompactStopActionWidth else Dimensions.ComposerPrimaryAction,
        animationSpec = MotionSpecs.snappy(reduced),
        label = "assistant-compact-action-width",
    )
    val cornerPercent by animateFloatAsState(
        targetValue = if (stop) CompactStopActionCornerPercent else 50f,
        animationSpec = MotionSpecs.snappy(reduced),
        label = "assistant-compact-action-corner",
    )
    val colorSpec = if (reduced) snap<Color>() else tween(MotionSpecs.Fast, easing = MotionSpecs.StandardEasing)
    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainer
            stop -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = colorSpec,
        label = "assistant-compact-action-container",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            stop -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = colorSpec,
        label = "assistant-compact-action-content",
    )
    val shape = RoundedCornerShape(percent = cornerPercent.roundToInt())

    Box(
        modifier = modifier
            .width(width)
            .height(Dimensions.ComposerPrimaryAction)
            .clip(shape)
            .bouncyClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = if (stop) onStop else onSend,
            )
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = stop,
            animationSpec = if (reduced) {
                snap()
            } else {
                tween(MotionSpecs.Fast, easing = MotionSpecs.LinearOutSlowInEasing)
            },
            label = "assistant-compact-send-stop",
        ) { showStop ->
            Icon(
                imageVector = if (showStop) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(Dimensions.Icon),
            )
        }
    }
}

/** 输入框最多显示的行数；超过后改为内部滚动，输入栏不再增高。 */
private const val CompactMaxInputLines = 6

/** 展开态输入区的最小高度：紧凑横条的两倍，给输入留出更明显的空间。 */
private val CompactExpandedMinHeight = Dimensions.ComposerMinHeight * 2

/** 进场起点：整条输入栏位于自身高度 + 32dp 的屏幕底部之下。 */
private val CompactEntranceOvershoot = 32.dp

/** 进场起点缩放：0.86 → 1.0 的「弹出」感。 */
private const val CompactEntranceStartScale = 0.86f

/** 停止态比发送态略宽，形成可见的容器尺寸变化。 */
private val CompactStopActionWidth = 56.dp

/** 停止态的圆角百分比（相对短边），由圆形渐变到胶囊。 */
private const val CompactStopActionCornerPercent = 36f

/** 附件文件名最大宽度，避免长文件名把移除按钮挤出屏幕。 */
private val CompactAttachmentNameWidth = 120.dp

/** 附件缩略图边长。 */
private val CompactAttachmentThumbnail = 32.dp
