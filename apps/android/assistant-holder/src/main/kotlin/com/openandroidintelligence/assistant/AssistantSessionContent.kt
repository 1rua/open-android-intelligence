package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.assistant.AssistantSurface
import com.openandroidintelligence.conversation.selection.ScreenSelectionOverlay
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 助理界面的宿主形态。
 *
 * [VOICE_SESSION] 是系统助理浮层：窗口尺寸由 [AssistantSession] 用 `setLayout` 控制，
 * 只能在 72dp 停靠球与展开卡片之间整窗切换，所以卡片外壳由本模块按同款设计令牌自绘；
 * [ASSIST_ACTIVITY] 是未设为默认助理时的兜底全屏页，直接复用它 App 内的
 * [AssistantSurface]，连展开/收起的几何形变都与 App 内一致。
 */
internal enum class AssistantHostMode { VOICE_SESSION, ASSIST_ACTIVITY }

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
 * 数据来自 [client]（主进程真实状态），动作交回宿主；界面自身不连接 Gateway、
 * 不写协议、不编造对话。窗口圆角背景由本层绘制，会话窗口本身是透明的。
 *
 * @param expanded 只对 [AssistantHostMode.VOICE_SESSION] 有意义：停靠球与展开卡片的
 *   切换由宿主窗口尺寸驱动；兜底页始终展开，自带 `AssistantSurface` 的形态切换。
 */
@Composable
internal fun AssistantSessionContent(
    client: AssistantBridgeClient,
    mode: AssistantHostMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
    onDock: (() -> Unit)? = null,
    onMoveDockEdge: (() -> Unit)? = null,
    onStartSelection: (() -> Unit)? = null,
    selection: AssistantScreenSelection? = null,
    onFinishSelection: () -> Unit = {},
    initialText: String? = null,
    /** 解析出的最终深浅色：宿主据此设置状态栏/导航栏图标明暗。 */
    onResolvedAppearance: (darkTheme: Boolean) -> Unit = {},
) {
    val state by client.state.collectAsState()
    // 输入框的即时回显归本地，主进程发布回来的草稿是唯一权威；两者不一致时以主进程为准
    // （换对话、外部清空都会走到这里）。
    var draft by rememberSaveable { mutableStateOf(state.draft) }
    LaunchedEffect(state.draft) {
        if (state.draft != draft) draft = state.draft
    }
    var consentPromptVisible by remember { mutableStateOf(false) }
    var threadListVisible by rememberSaveable { mutableStateOf(false) }

    val banner = state.advisory ?: state.notice.takeIf { it.isNotBlank() }
    val dismissBanner: (() -> Unit)? = if (state.advisory != null) client::clearAdvisory else null

    // 外观优先用主进程经状态通道下发的值：跨进程读 SharedPreferences 会拿到旧缓存。
    // 没有桥接状态时（尚未 connect / 旧版主进程）才回落到本地偏好。
    val appearanceContext = LocalContext.current
    val fallbackAppearance = remember(appearanceContext) { readFallbackAppearance(appearanceContext) }
    val appearance = state.appearance ?: fallbackAppearance
    val darkTheme = appearance.darkTheme(appearanceContext)
    val reduceMotion = appearance.reduceMotion
    LaunchedEffect(darkTheme) { onResolvedAppearance(darkTheme) }
    OpenAndroidIntelligenceTheme(
        darkTheme = darkTheme,
        dynamicColor = appearance.dynamicColor,
        palette = appearance.resolvedPalette(),
        reduceMotion = reduceMotion,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            when {
                selection != null -> AssistantSelectionLayer(
                    selection = selection,
                    onCropped = { cropped ->
                        client.attachScreen(cropped)
                        onFinishSelection()
                    },
                    onCropRejected = { client.showAdvisory("选区太小，请重新圈选") },
                    onCancel = onFinishSelection,
                )

                mode == AssistantHostMode.VOICE_SESSION && !expanded -> AssistantDockedBall(
                    state = state,
                    onExpand = { onExpandedChange(true) },
                    onMoveToEdge = onMoveDockEdge,
                )

                mode == AssistantHostMode.VOICE_SESSION -> {
                    AssistantWindowPanel(
                        state = state,
                        draft = draft,
                        banner = banner,
                        dismissBanner = dismissBanner,
                        initialText = initialText,
                        onDraftChange = { value ->
                            val bounded = value.take(MAX_ASSISTANT_DRAFT_CHARS)
                            draft = bounded
                            client.updateDraft(bounded)
                        },
                        onSend = { client.send() },
                        onStop = { client.cancel() },
                        onUseInitialText = { initialText?.let(client::updateDraft) },
                        consentPromptVisible = consentPromptVisible,
                        threadListVisible = threadListVisible,
                        onRequestConsent = { consentPromptVisible = true },
                        onConfirmConsent = {
                            consentPromptVisible = false
                            client.setConsent()
                        },
                        onDismissConsent = { consentPromptVisible = false },
                        onOpenThreadList = { threadListVisible = true },
                        onDismissThreadList = { threadListVisible = false },
                        onSelectThread = { threadId ->
                            threadListVisible = false
                            client.selectThread(threadId)
                        },
                        onStartSelection = onStartSelection,
                        onDock = onDock,
                        onOpenApp = onOpenApp,
                        onClose = onClose,
                    )
                }

                else -> AssistantActivityPanel(
                    state = state,
                    draft = draft,
                    banner = banner,
                    dismissBanner = dismissBanner,
                    initialText = initialText,
                    onDraftChange = { value ->
                        val bounded = value.take(MAX_ASSISTANT_DRAFT_CHARS)
                        draft = bounded
                        client.updateDraft(bounded)
                    },
                    onSend = { client.send() },
                    onStop = { client.cancel() },
                    onUseInitialText = { initialText?.let(client::updateDraft) },
                    consentPromptVisible = consentPromptVisible,
                    threadListVisible = threadListVisible,
                    onRequestConsent = { consentPromptVisible = true },
                    onConfirmConsent = {
                        consentPromptVisible = false
                        client.setConsent()
                    },
                    onDismissConsent = { consentPromptVisible = false },
                    onOpenThreadList = { threadListVisible = true },
                    onDismissThreadList = { threadListVisible = false },
                    onSelectThread = { threadId ->
                        threadListVisible = false
                        client.selectThread(threadId)
                    },
                    onStartSelection = onStartSelection,
                    onOpenApp = onOpenApp,
                    onClose = onClose,
                )
            }
        }
    }
}

/**
 * 停靠球：72dp 窗口里的品牌环（primary 圆弧 + tertiary 圆点）。
 *
 * 环的几何与 `AssistantSurface` 的收起态保持一致（同一视觉身份）；点击展开，
 * 长按交给宿主切换左右停靠位。状态通过 `stateDescription` 朗读，不靠颜色传达。
 */
@Composable
private fun AssistantDockedBall(
    state: AssistantState,
    onExpand: () -> Unit,
    onMoveToEdge: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.82f,
        animationSpec = MotionSpecs.morph(reduceMotion),
        label = "docked-ball-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = MotionSpecs.fade(reduceMotion),
        label = "docked-ball-alpha",
    )
    val status = state.gatewayStatusLabel()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimensions.SpaceTiny),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .semantics {
                    contentDescription = "智能助理，已收起"
                    stateDescription = status
                    role = Role.Button
                    onClick(label = "展开助理") {
                        onExpand()
                        true
                    }
                    onLongClick(label = "移到另一侧") {
                        val move = onMoveToEdge ?: return@onLongClick false
                        move()
                        true
                    }
                }
                .pointerInput(onExpand, onMoveToEdge) {
                    detectTapGestures(
                        onTap = { onExpand() },
                        onLongPress = { onMoveToEdge?.invoke() },
                    )
                },
        ) {
            BrandRing(modifier = Modifier.fillMaxSize().padding(Dimensions.SpaceTiny))
        }
    }
}

/** 品牌环：与 `AssistantSurface` 收起态完全相同的弧线与圆点几何。 */
@Composable
private fun BrandRing(modifier: Modifier = Modifier) {
    val arcColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val stroke = Dimensions.StrokeStitch.toPx()
        val diameter = (minOf(size.width, size.height) - Dimensions.SpaceSmall.toPx())
            .coerceAtLeast(stroke * 4f)
        val topLeft = Offset(
            x = (size.width - diameter) / 2f,
            y = (size.height - diameter) / 2f,
        )
        drawArc(
            color = arcColor,
            startAngle = 28f,
            sweepAngle = 296f,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawCircle(
            color = dotColor,
            radius = stroke,
            center = Offset(
                x = topLeft.x + diameter * 0.78f,
                y = topLeft.y + diameter * 0.22f,
            ),
        )
    }
}

/**
 * 会话浮层里的展开卡片。
 *
 * 窗口本身已经是卡片大小（会话用 `setLayout` 精确设置），所以这里直接铺满窗口：
 * 圆角之外透明，窗口背景在会话侧被设为透明，卡片由 Compose 自己画。
 */
@Composable
private fun AssistantWindowPanel(
    state: AssistantState,
    draft: String,
    banner: String?,
    dismissBanner: (() -> Unit)?,
    initialText: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onUseInitialText: () -> Unit,
    consentPromptVisible: Boolean,
    threadListVisible: Boolean,
    onRequestConsent: () -> Unit,
    onConfirmConsent: () -> Unit,
    onDismissConsent: () -> Unit,
    onOpenThreadList: () -> Unit,
    onDismissThreadList: () -> Unit,
    onSelectThread: (String) -> Unit,
    onStartSelection: (() -> Unit)?,
    onDock: (() -> Unit)?,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val density = LocalDensity.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = MotionSpecs.fade(reduceMotion),
        label = "assistant-panel-alpha",
    )
    val enterOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else Dimensions.SpaceLarge,
        animationSpec = MotionSpecs.emphasized(reduceMotion),
        label = "assistant-panel-offset",
    )
    val offsetPx = with(density) { enterOffset.toPx() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(AppRadius.ExtraLarge),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enterAlpha
                translationY = offsetPx
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 会话窗口可能落在导航栏/挖孔之上，也可能已经避让；inset 为 0 时这两层是空操作。
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.displayCutout),
                )
                .imePadding()
                .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceCompact),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            AssistantPanelHeader(state = state)
            AssistantConversationArea(
                state = state,
                consentPromptVisible = consentPromptVisible,
                threadListVisible = threadListVisible,
                onConfirmConsent = onConfirmConsent,
                onDismissConsent = onDismissConsent,
                onRequestConsent = onRequestConsent,
                onSelectThread = onSelectThread,
                onDismissThreadList = onDismissThreadList,
                onOpenApp = onOpenApp,
                modifier = Modifier.weight(1f),
            )
            if (banner != null) {
                AssistantBanner(text = banner, onDismiss = dismissBanner)
            }
            AssistantComposerDock(
                state = state,
                draft = draft,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                initialText = initialText,
                onUseInitialText = onUseInitialText,
            )
            AssistantActionRow(
                state = state,
                showScreenSelection = onStartSelection != null,
                showDockAction = onDock != null,
                showCloseAction = true,
                onOpenThreadList = onOpenThreadList,
                onStartSelection = { onStartSelection?.invoke() },
                onDock = { onDock?.invoke() },
                onOpenApp = onOpenApp,
                onClose = onClose,
            )
        }
    }
}

/**
 * 兜底 ASSIST 页：全屏使用 App 内同一个 [AssistantSurface]。
 *
 * 这样"未设为默认助理"时的入口与 App 内浮动助理拥有同一套形变与视觉身份，
 * 而不是另一套拼接界面；内容层、输入坞和动作行与会话浮层完全复用。
 */
@Composable
private fun AssistantActivityPanel(
    state: AssistantState,
    draft: String,
    banner: String?,
    dismissBanner: (() -> Unit)?,
    initialText: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onUseInitialText: () -> Unit,
    consentPromptVisible: Boolean,
    threadListVisible: Boolean,
    onRequestConsent: () -> Unit,
    onConfirmConsent: () -> Unit,
    onDismissConsent: () -> Unit,
    onOpenThreadList: () -> Unit,
    onDismissThreadList: () -> Unit,
    onSelectThread: (String) -> Unit,
    onStartSelection: (() -> Unit)?,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Box(modifier = modifier.fillMaxSize()) {
        // 兜底页是透明窗口：自己画一层 scrim，底下的 App 仍可辨认，
        // 用户能看出"助理浮在刚才那个界面之上"，而不是切到了另一个全屏页。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "回到上一个应用并关闭助理",
                    role = Role.Button,
                    onClick = onClose,
                ),
        )
        AssistantSurface(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            onClose = onClose,
            onStartSelection = { onStartSelection?.invoke() },
            title = state.title.ifBlank { "智能助理" },
            subtitle = state.gatewayStatusLabel(),
            footer = {
                if (banner != null) {
                    AssistantBanner(text = banner, onDismiss = dismissBanner)
                }
                AssistantComposerDock(
                    state = state,
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onStop = onStop,
                    initialText = initialText,
                    onUseInitialText = onUseInitialText,
                )
                AssistantActionRow(
                    state = state,
                    // 圈选、收起、关闭由 AssistantSurface 的表头承担，动作行不重复给入口。
                    showScreenSelection = false,
                    showDockAction = false,
                    showCloseAction = false,
                    onOpenThreadList = onOpenThreadList,
                    onStartSelection = { onStartSelection?.invoke() },
                    onDock = {},
                    onOpenApp = onOpenApp,
                    onClose = onClose,
                )
            },
        ) {
            AssistantConversationArea(
                state = state,
                consentPromptVisible = consentPromptVisible,
                threadListVisible = threadListVisible,
                onConfirmConsent = onConfirmConsent,
                onDismissConsent = onDismissConsent,
                onRequestConsent = onRequestConsent,
                onSelectThread = onSelectThread,
                onDismissThreadList = onDismissThreadList,
                onOpenApp = onOpenApp,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 屏幕圈选层：直接复用 App 内同一个 [ScreenSelectionOverlay]。
 *
 * 截图是输入的位图（系统在唤起时提供），确认后才裁剪并交给桥接层；
 * 裁剪失败时保持圈选层打开并给出提示，绝不把"没裁出来"当成已提交。
 */
@Composable
private fun AssistantSelectionLayer(
    selection: AssistantScreenSelection,
    onCropped: (Bitmap) -> Unit,
    onCropRejected: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(selection.bitmap) { selection.bitmap.asImageBitmap() }
    var canvasSize by remember(selection.bitmap) { mutableStateOf(IntSize.Zero) }
    ScreenSelectionOverlay(
        onCropConfirmed = { left, top, right, bottom ->
            val cropped = cropAssistantSelection(
                source = selection.bitmap,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                canvasWidth = canvasSize.width.toFloat(),
                canvasHeight = canvasSize.height.toFloat(),
            )
            if (cropped != null) onCropped(cropped) else onCropRejected()
        },
        onFreehandConfirmed = { points ->
            val cropped = cropAssistantFreehand(
                source = selection.bitmap,
                points = points,
                canvasWidth = canvasSize.width.toFloat(),
                canvasHeight = canvasSize.height.toFloat(),
            )
            if (cropped != null) onCropped(cropped) else onCropRejected()
        },
        onCancel = onCancel,
        screenshot = image,
        onCanvasSizeChanged = { canvasSize = it },
        modifier = modifier.fillMaxSize(),
    )
}
