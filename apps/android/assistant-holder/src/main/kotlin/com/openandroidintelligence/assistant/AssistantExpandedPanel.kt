package com.openandroidintelligence.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R as UiR
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 展开面板根节点：整块「会话框 + 输入栏」的测量边界。 */
internal const val ASSISTANT_EXPANDED_PANEL_TAG = "assistant-expanded-panel"

/** 会话框容器：与输入栏相接的一侧圆角为 0。 */
internal const val ASSISTANT_CONVERSATION_BOX_TAG = "assistant-conversation-box"

/** 输入栏容器：与消息区相接的一侧圆角为 0。 */
internal const val ASSISTANT_INPUT_BAR_TAG = "assistant-input-bar"

/** 内部滚动列表：内容超过高度上限时只在这里滚动。 */
internal const val ASSISTANT_SESSION_LIST_TAG = "assistant-session-list"

/**
 * 会话框的形状：外缘两角用 [AppRadius.ExtraLarge]，与输入栏相接的底部两角为 0。
 *
 * 两个容器上下紧贴、各自只保留外缘圆角，拼起来才是一张完整的卡片，
 * 而不是两个独立胶囊叠在一起。
 */
internal val ConversationBoxShape = RoundedCornerShape(
    topStart = AppRadius.ExtraLarge,
    topEnd = AppRadius.ExtraLarge,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)

/** 输入栏的形状：顶部两角与消息区无缝衔接（0），外缘底部用 [AppRadius.ExtraLarge]。 */
internal val InputBarShape = RoundedCornerShape(
    topStart = 0.dp,
    topEnd = 0.dp,
    bottomStart = AppRadius.ExtraLarge,
    bottomEnd = AppRadius.ExtraLarge,
)

/**
 * 可展开的会话框容器。
 *
 * 结构自上而下：
 * - 会话框：顶部可选 [header]（标题等），下面是 [LazyColumn] 消息列表；
 * - 输入栏（[composer] 槽位）：固定不滚动，随面板一起展开与收起；
 * - 列表底部浮动「回到最新」，只在用户离开最新位置时出现。
 *
 * 四条行为契约：
 * 1. **展开/收起由 [expanded] 驱动**，走 `AnimatedVisibility` 的竖向展开 + 淡化，
 *    动效令牌随系统「减少动态」降级；收起时内容不组合，滚动状态仍保留在列表状态里。
 * 2. **高度上限**：`min(可用高度 × 0.7, 可用高度 - 键盘 - 顶部安全区)`，
 *    计算交给 [AssistantSessionWindowPolicy.calculateMaxHeight] 纯函数。
 *    可用高度就是面板收到的最大高度约束；宿主应给面板有界高度（窗口级或 `fillMaxSize`
 *    容器）。约束无界（被放进纵向滚动容器）时回落到屏幕高度，保证列表仍有有限高度。
 * 3. **内部滚动**：消息列表反向布局（索引 0 = 最新，永远贴着输入栏），
 *    内容超过上限时只在列表内部滚动，面板本身不会被内容撑出屏幕。
 * 4. **智能跟随**：用户上滑查看历史后，新消息不会把阅读位置拽走；
 *    「回到最新」恢复跟随，之后的新消息自动贴底。
 *
 * 面板只负责容器与滚动策略：消息怎么渲染由 [itemContent] 决定，输入栏由 [composer]
 * 提供，数据仍来自调用方（助理进程的真实桥接状态），容器本身不伪造任何消息。
 *
 * 注意：上限公式已经扣除键盘高度，宿主在同一块区域内不要再叠加 `imePadding()`，
 * 否则键盘高度会被扣两次、面板凭空矮一截。
 *
 * @param messages 消息（时间顺序旧→新）；列表内部会反转为「最新在下」。
 * @param itemKey 稳定条目标识；为 null 时按消息对象本身作为身份（切换会话时请尽量提供）。
 * @param header 会话框顶部的固定区域（例如标题与状态），不参与滚动。
 * @param composer 输入栏内容，固定贴在面板底部。
 * @param emptyContent 没有消息时显示的空态；为 null 时消息区留白。
 * @param itemContent 单条消息的呈现。
 * @param availableHeightPx 宿主给出的**未扣 inset、未乘 0.7** 的可用高度（像素）。
 *   给出时高度上限只在这里算一次；为 null 时才回落到本条目的测量约束。
 *   两个来源不能同时生效——否则同一个 0.7 上限会被乘两次，键盘弹出后面板只剩一条消息高。
 */
@Composable
internal fun <T> AssistantExpandedPanel(
    expanded: Boolean,
    messages: List<T>,
    modifier: Modifier = Modifier,
    itemKey: ((T) -> Any)? = null,
    header: (@Composable () -> Unit)? = null,
    composer: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    availableHeightPx: Int? = null,
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 跟随开关归面板所有：收起再展开时用户仍停留在原来的阅读位置，
    // 只有「确实回到最新」或点了按钮才恢复自动贴底。
    var followLatest by rememberSaveable { mutableStateOf(true) }
    val atLatest by remember(listState) {
        derivedStateOf { AssistantSessionWindowPolicy.shouldFollowLatest(listState) }
    }

    // 手指一落下就停止跟随，避免流式输出在用户阅读时继续把内容往下拽。
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        if (dragging) followLatest = false
    }
    // 用户自己滑回最新处（或点按钮滚回）后恢复跟随，避免「已经贴底却还显示回到最新」。
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to AssistantSessionWindowPolicy.shouldFollowLatest(listState)
        }.distinctUntilChanged().collect { (scrolling, latest) ->
            if (!scrolling) followLatest = latest
        }
    }

    // 尾巴身份而不是条目数：流式输出可能不新增条目，但最后一条的身份会变。
    val tailSignature = messages.size to messages.lastOrNull()?.let { message ->
        itemKey?.invoke(message) ?: message
    }
    var previousTailSignature by remember { mutableStateOf(tailSignature) }
    LaunchedEffect(tailSignature) {
        if (tailSignature == previousTailSignature) return@LaunchedEffect
        // 先记录再判断：用户正在看历史时新消息同样要「已知」，否则回到最新后
        // 下一条消息会被误判成第一次出现而少一次贴底。
        previousTailSignature = tailSignature
        if (!followLatest || messages.isEmpty()) return@LaunchedEffect
        if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(
            animationSpec = MotionSpecs.emphasized<IntSize>(reduceMotion),
            expandFrom = Alignment.Bottom,
        ) + fadeIn(animationSpec = MotionSpecs.fade(reduceMotion)),
        exit = shrinkVertically(
            animationSpec = MotionSpecs.emphasized<IntSize>(reduceMotion),
            shrinkTowards = Alignment.Bottom,
        ) + fadeOut(animationSpec = MotionSpecs.fade(reduceMotion)),
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 上限只有一个计算点：
            // - 宿主显式给了可用高度 → 用它算一次（它未扣 inset、未乘 0.7）；
            // - 否则用本条目的测量约束；无穷大时退回屏幕高度，保证 LazyColumn 有界。
            val baseHeightPx = availableHeightPx ?: if (constraints.maxHeight == Constraints.Infinity) {
                with(density) { configuration.screenHeightDp.dp.toPx() }.roundToInt()
            } else {
                with(density) { maxHeight.toPx() }.roundToInt()
            }
            val imeHeightPx = WindowInsets.ime.getBottom(density)
            val topInsetPx = WindowInsets.safeDrawing.getTop(density)
            val maxPanelHeight = with(density) {
                AssistantSessionWindowPolicy
                    .calculateMaxHeight(baseHeightPx, imeHeightPx, topInsetPx)
                    .toDp()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .testTag(ASSISTANT_EXPANDED_PANEL_TAG),
            ) {
                Surface(
                    shape = ConversationBoxShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
                    // 会话框吃掉上限内除输入栏外的全部高度：整块面板永远不超过上限，
                    // 内容再多也只在内部 LazyColumn 里滚动。
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(ASSISTANT_CONVERSATION_BOX_TAG),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (header != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = Dimensions.SpaceMedium,
                                        end = Dimensions.SpaceMedium,
                                        top = Dimensions.SpaceCompact,
                                        bottom = Dimensions.SpaceSmall,
                                    ),
                            ) {
                                header()
                            }
                        }
                        if (messages.isEmpty() && emptyContent != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Dimensions.SpaceMedium),
                            ) {
                                emptyContent()
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                LazyColumn(
                                    state = listState,
                                    // 反向布局：索引 0 是最新一条，永远贴着输入栏；
                                    // 新消息追加时不需要整段跳转就可见。
                                    reverseLayout = true,
                                    contentPadding = PaddingValues(
                                        // 横向必须留白：会话框是 28dp 圆角的 Surface，会按形状裁剪；
                                        // 没有内边距时气泡两侧会直接顶到卡片边缘被切掉。
                                        start = Dimensions.TimelineHorizontal,
                                        end = Dimensions.TimelineHorizontal,
                                        top = Dimensions.SpaceSmall,
                                        bottom = Dimensions.SpaceSmall,
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(Dimensions.TimelineSpacing),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag(ASSISTANT_SESSION_LIST_TAG),
                                ) {
                                    items(
                                        items = messages.asReversed(),
                                        key = itemKey,
                                        itemContent = itemContent,
                                    )
                                }
                                ScrollToLatestButton(
                                    visible = !atLatest && messages.isNotEmpty(),
                                    onClick = {
                                        followLatest = true
                                        scope.launch {
                                            if (reduceMotion) {
                                                listState.scrollToItem(0)
                                            } else {
                                                listState.animateScrollToItem(0)
                                            }
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
                        }
                    }
                }
                if (composer != null) {
                    // 输入栏自带卡片外观（见 `AssistantCompactBar`）：这里不再套第二层
                    // Surface，否则展开态会出现双边框/双圆角（需求 5.3「只有一个输入栏」）。
                    // 标签仍由这一层提供，既有容器契约与测试保持不变。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ASSISTANT_INPUT_BAR_TAG),
                    ) {
                        composer()
                    }
                }
            }
        }
    }
}

/**
 * 「回到最新」：用户离开最新位置后出现（对齐 App 工作台的同名按钮与规格 §6 M13）。
 *
 * 150ms 淡入 + 0.9→1 缩放；开启减少动态时只做短暂淡化，不做缩放。
 * 工作台里的同名组件是 `conversation-ui` 模块 internal 的，本模块无法直接引用，
 * 所以这里沿用同一套动效令牌重新实现，而不是自造另一套时长与曲线。
 */
@Composable
internal fun ScrollToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MotionSpecs.fade(reduced)) + if (reduced) {
            EnterTransition.None
        } else {
            scaleIn(
                animationSpec = tween(MotionSpecs.Fast, easing = MotionSpecs.EmphasizedDecelerateEasing),
                initialScale = 0.9f,
            )
        },
        exit = fadeOut(MotionSpecs.fade(reduced)) + if (reduced) {
            ExitTransition.None
        } else {
            scaleOut(
                animationSpec = tween(MotionSpecs.Fast, easing = MotionSpecs.EmphasizedAccelerateEasing),
                targetScale = 0.9f,
            )
        },
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.padding(Dimensions.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(Modifier.width(Dimensions.SpaceSmall))
            Text(stringResource(UiR.string.assistant_expanded_scroll_to_bottom))
        }
    }
}
