package com.openandroidintelligence.conversation.workbench

import android.graphics.drawable.ColorDrawable
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.openandroidintelligence.conversation.model.AttachmentDownloadState
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.OriginalImageState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 预览图节点的大图语义标签：长按菜单挂在这个节点上，宿主测试也按它查找。 */
internal const val LightboxPreviewContentDescription = "大图预览"

/** 关闭按钮的语义标签。 */
internal const val LightboxCloseContentDescription = "关闭大图预览"

/** 长按图片动作的无障碍标签。 */
private const val LightboxLongClickLabel = "显示大图操作菜单"

/** 遮罩透明度：压暗宿主界面但仍能看出背后是对话，提醒用户这是浮层而不是新页面。 */
private const val ScrimAlpha = 0.94f

/**
 * 附件全屏大图预览。
 *
 * 挂载在 [HistoricalAttachmentCard] 内部：点击图片即打开，返回键或关闭按钮退出。
 * 图片优先使用控制器按需加载的 [TimelineAttachment.originalBytes]（已缩到展示尺寸），
 * 尚未就绪时回退 512px 缩略图，并明确标注「当前为预览图」，绝不画空白。
 *
 * 手势：双指缩放 1x–5x、拖拽平移、双击在 1x/2.5x 之间切换、长按弹出保存/关闭菜单。
 * 拖拽释放继承 [VelocityTracker] 的初速度做 decay 惯性；越界回弹由弹簧完成；
 * 系统开启「减少动态」时不投影速度、直接吸附，双击也直接落到目标值。
 */
@Composable
fun AttachmentLightbox(
    attachment: TimelineAttachment,
    onDismiss: () -> Unit,
    onDownload: ((String) -> Unit)?,
    onRequestOriginal: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 大图预览必须铺满整屏：默认宽度会让它缩成一张居中卡片，图片反而更小。
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DialogWindowChrome()
        LightboxContent(
            attachment = attachment,
            onDismiss = onDismiss,
            onDownload = onDownload,
            onRequestOriginal = onRequestOriginal,
            modifier = modifier,
        )
    }
}

/**
 * 把 Dialog 自己的窗口改成透明并隐藏系统栏。
 *
 * Dialog 默认带不透明白底与自己的窗口 inset 策略；不改的话遮罩后面会露出一块
 * 白板，状态栏也会把图片往下压。隐藏与恢复都作用在 Dialog 窗口上，退出时恢复
 * 系统栏，宿主窗口的可见性不受影响。
 */
@Composable
private fun DialogWindowChrome() {
    val view = LocalView.current
    DisposableEffect(view) {
        // Dialog 的内容视图由 Compose 放进一个实现了 DialogWindowProvider 的窗口容器；
        // 取不到时保持默认外观即可，不能让一个装饰性效果把预览本身弄崩。
        val window = runCatching { (view.parent as? DialogWindowProvider)?.window }.getOrNull()
        val controller = window?.let { runCatching { it.insetsController }.getOrNull() }
        if (window != null) {
            runCatching {
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
            runCatching {
                controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(AndroidWindowInsets.Type.systemBars())
            }
        }
        onDispose {
            runCatching { controller?.show(AndroidWindowInsets.Type.systemBars()) }
        }
    }
}

@Composable
private fun LightboxContent(
    attachment: TimelineAttachment,
    onDismiss: () -> Unit,
    onDownload: ((String) -> Unit)?,
    onRequestOriginal: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    var menuOpen by remember { mutableStateOf(false) }

    // 打开时若原图还没请求过，只请求一次；已有结果或失败时不再反复触发。
    LaunchedEffect(attachment.draftId) {
        if (attachment.originalState == OriginalImageState.IDLE) {
            onRequestOriginal?.invoke(attachment.draftId)
        }
    }

    val originalBytes = if (attachment.originalState == OriginalImageState.READY) {
        attachment.originalBytes
    } else {
        null
    }
    // 原图和预览各解一次：原图字节损坏/方向解码失败时仍然要退回缩略图，
    // 不能因为「原图状态是 READY」就给用户一块空白。
    val originalBitmap = rememberDecodedPreview(originalBytes)
    val previewBitmap = rememberDecodedPreview(attachment.previewBytes)
    val bitmap = originalBitmap ?: previewBitmap

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = ScrimAlpha))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LightboxTopBar(
            attachment = attachment,
            onDismiss = onDismiss,
            onDownload = onDownload,
            saveActionVisible = !menuOpen,
        )
        LightboxImageStage(
            bitmap = bitmap,
            attachment = attachment,
            reduceMotion = reduceMotion,
            menuOpen = menuOpen,
            onMenuOpen = { menuOpen = true },
            onMenuDismiss = { menuOpen = false },
            onDismiss = onDismiss,
            onDownload = onDownload,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 顶栏：关闭、文件名、「保存至本地」。下载状态就地反馈，不依赖会被遮住的 Snackbar。 */
@Composable
private fun LightboxTopBar(
    attachment: TimelineAttachment,
    onDismiss: () -> Unit,
    onDownload: ((String) -> Unit)?,
    saveActionVisible: Boolean,
) {
    // 顶栏用主题表面色，而不是在半透明遮罩上直接写字：M3 没有 onScrim 角色，
    // 深浅两套主题里唯一都保证正文对比度的做法就是一块真正的 surface。
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimensions.TopBarContentHeight)
                .padding(horizontal = Dimensions.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(Dimensions.MinimumTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = LightboxCloseContentDescription,
                    modifier = Modifier.size(Dimensions.Icon),
                )
            }
            Text(
                text = attachment.filename.ifBlank { "图片" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimensions.SpaceSmall),
            )
            if (onDownload != null && saveActionVisible) {
                SaveActionButton(attachment = attachment, onDownload = onDownload)
            }
        }
    }
}

/**
 * 「保存至本地」按钮。
 *
 * 下载中禁用并显示「保存中…」，成功后变成「已保存到下载目录」，失败则给出可重试
 * 的文案：Dialog 会盖住宿主的 Snackbar，保存结果必须在这里就地说明。
 */
@Composable
private fun SaveActionButton(
    attachment: TimelineAttachment,
    onDownload: (String) -> Unit,
) {
    val canSave = attachment.downloadState == AttachmentDownloadState.IDLE ||
        attachment.downloadState == AttachmentDownloadState.FAILED
    TextButton(
        onClick = { onDownload(attachment.draftId) },
        enabled = canSave,
        modifier = Modifier.heightIn(min = Dimensions.MinimumTouchTarget),
    ) {
        Icon(
            imageVector = Icons.Default.Download,
            contentDescription = null,
            modifier = Modifier.size(Dimensions.SmallIcon),
        )
        Spacer(Modifier.width(Dimensions.SpaceTiny))
        Text(
            text = saveActionLabel(attachment.downloadState),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** 图片区：优先原图，未就绪/失败时保留预览并给出明确状态文案。 */
@Composable
private fun LightboxImageStage(
    bitmap: ImageBitmap?,
    attachment: TimelineAttachment,
    reduceMotion: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onDismiss: () -> Unit,
    onDownload: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ZoomableImageStage(
            bitmap = bitmap,
            reduceMotion = reduceMotion,
            onLongPress = onMenuOpen,
            modifier = Modifier.fillMaxSize(),
        )

        if (bitmap == null) {
            ImageStagePlaceholder(
                attachment = attachment,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Dimensions.SpaceLarge),
            )
        } else {
            originalStatusText(attachment, hasPreview = true)?.let { text ->
                StageStatusChip(
                    text = text,
                    showProgress = attachment.originalState == OriginalImageState.LOADING,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Dimensions.SpaceMedium),
                )
            }
        }

        // 长按菜单在这个 Box 内展开；下载中禁用保存项并显示「保存中…」。
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
            if (onDownload != null) {
                val canSave = attachment.downloadState == AttachmentDownloadState.IDLE ||
                    attachment.downloadState == AttachmentDownloadState.FAILED
                DropdownMenuItem(
                    text = { Text(saveActionLabel(attachment.downloadState)) },
                    onClick = {
                        onMenuDismiss()
                        onDownload(attachment.draftId)
                    },
                    enabled = canSave,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(Dimensions.SmallIcon),
                        )
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("关闭") },
                onClick = {
                    onMenuDismiss()
                    onDismiss()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                },
            )
        }
    }
}

/**
 * 可缩放/平移的图片本体。
 *
 * 语义节点固定是「大图预览」，即使解码失败也保留：长按菜单与无障碍焦点都挂
 * 在这个节点上，图片没有渲染出来也不能让用户失去可操作区域。
 */
@Composable
private fun ZoomableImageStage(
    bitmap: ImageBitmap?,
    reduceMotion: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 手势期间只写普通状态：pointerInput 的受限协程 scope 不允许调用任意 suspend 函数。
    // 只有松手收尾的惯性动画才从外部协程动画到终点。
    var scale by remember { mutableFloatStateOf(ImageViewerGeometry.MinScale) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // 换了图（预览 → 原图也算换）就回到 1x 居中，上一张的缩放不能带到下一张。
    LaunchedEffect(bitmap) {
        settleJob?.cancel()
        scale = ImageViewerGeometry.MinScale
        offsetX = 0f
        offsetY = 0f
    }
    DisposableEffect(Unit) {
        onDispose { settleJob?.cancel() }
    }

    fun contentSize(): Size {
        val image = bitmap ?: return Size.Zero
        return ImageViewerGeometry.fittedContentSize(
            viewport = viewport,
            intrinsic = Size(image.width.toFloat(), image.height.toFloat()),
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .semantics {
                contentDescription = LightboxPreviewContentDescription
                // 长按菜单同时暴露为无障碍自定义动作：TalkBack 与测试都能稳定触发，
                // 不依赖「按住多久」这种只能靠真实触摸复现的时序。
                onLongClick(label = LightboxLongClickLabel) {
                    onLongPress()
                    true
                }
            }
            .pointerInput(bitmap, reduceMotion) {
                detectTapGestures(
                    onDoubleTap = {
                        if (bitmap != null) {
                            settleJob?.cancel()
                            val targetScale = ImageViewerGeometry.doubleTapTarget(scale)
                            val spec: FiniteAnimationSpec<Float> = MotionSpecs.snappy(reduceMotion)
                            settleJob = scope.launch {
                                launch {
                                    AnimationState(initialValue = scale)
                                        .animateTo(targetScale, spec) { scale = value }
                                }
                                launch {
                                    AnimationState(initialValue = offsetX)
                                        .animateTo(0f, spec) { offsetX = value }
                                }
                                launch {
                                    AnimationState(initialValue = offsetY)
                                        .animateTo(0f, spec) { offsetY = value }
                                }
                            }
                        }
                    },
                    onLongPress = { onLongPress() },
                )
            }
            .pointerInput(bitmap, reduceMotion) {
                // 单指拖拽与双指缩放走同一条事件循环：分开两个 detector 会互相抢事件，
                // 也无法在释放瞬间拿到真实初速度。
                awaitEachGesture {
                    val tracker = VelocityTracker()
                    val touchSlop = viewConfiguration.touchSlop
                    var totalPan = Offset.Zero
                    var dragging = false
                    var canceled = false
                    awaitFirstDown(requireUnconsumed = false)
                    // 新手势接管：上一段惯性动画必须立刻让位，否则会继续写坐标。
                    settleJob?.cancel()
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) {
                            canceled = true
                            break
                        }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (bitmap != null && (zoom != 1f || pan != Offset.Zero)) {
                            totalPan += pan
                            if (!dragging) {
                                val pointers = event.changes.count { it.pressed }
                                dragging = pointers > 1 || totalPan.getDistance() > touchSlop
                            }
                            if (dragging) {
                                val newScale = ImageViewerGeometry.clampScale(scale * zoom)
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val anchor = Offset(viewport.width / 2f, viewport.height / 2f)
                                val zoomed = if (newScale != scale) {
                                    ImageViewerGeometry.zoomedOffset(
                                        currentOffset = Offset(offsetX, offsetY),
                                        centroid = centroid,
                                        viewportCenter = anchor,
                                        scaleFactor = newScale / scale,
                                    )
                                } else {
                                    Offset(offsetX, offsetY)
                                }
                                scale = newScale
                                val clamped = ImageViewerGeometry.clampOffset(
                                    offset = zoomed + pan,
                                    viewport = viewport,
                                    content = contentSize(),
                                    scale = newScale,
                                )
                                offsetX = clamped.x
                                offsetY = clamped.y
                                tracker.addPosition(event.changes.first().uptimeMillis, centroid)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!canceled && dragging && bitmap != null) {
                        val velocity = tracker.calculateVelocity()
                        val target = ImageViewerGeometry.releaseTarget(
                            offset = Offset(offsetX, offsetY),
                            velocity = Offset(velocity.x, velocity.y),
                            viewport = viewport,
                            content = contentSize(),
                            scale = scale,
                            reduceMotion = reduceMotion,
                        )
                        settleJob?.cancel()
                        if (reduceMotion) {
                            // 减少动态：不投影速度、不做惯性，直接吸附到边界内的终点。
                            offsetX = target.x
                            offsetY = target.y
                        } else {
                            settleJob = scope.launch {
                                launch {
                                    AnimationState(initialValue = offsetX, initialVelocity = velocity.x)
                                        .animateTo(target.x, MotionSpecs.MomentumSpring) { offsetX = value }
                                }
                                launch {
                                    AnimationState(initialValue = offsetY, initialVelocity = velocity.y)
                                        .animateTo(target.y, MotionSpecs.MomentumSpring) { offsetY = value }
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            )
        }
    }
}

/** 没有可解码图片时的诚实占位：说明为什么看不到，而不是留一块空白。 */
@Composable
private fun ImageStagePlaceholder(
    attachment: TimelineAttachment,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(AppRadius.Compact),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.SpaceMedium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            val status = originalStatusText(attachment, hasPreview = attachment.previewBytes != null)
            Text(
                text = status ?: localStateText(attachment.localState),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (attachment.originalState == OriginalImageState.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimensions.Progress),
                    strokeWidth = Dimensions.StrokeStitch,
                )
            }
        }
    }
}

/** 遮罩上的状态提示条：加载中带小进度，失败/回退时说明当前看到的是什么。 */
@Composable
private fun StageStatusChip(
    text: String,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(AppRadius.Compact),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimensions.SmallIcon),
                    strokeWidth = Dimensions.StrokeStitch,
                )
            }
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 原图状态文案；[hasPreview] 决定失败时是否还能说「当前为预览图」。 */
private fun originalStatusText(attachment: TimelineAttachment, hasPreview: Boolean): String? =
    when (attachment.originalState) {
        OriginalImageState.LOADING -> "正在加载原图…"
        OriginalImageState.FAILED ->
            if (hasPreview) "原图暂不可用，当前为预览图" else "原图暂不可用，图片预览也无法显示"
        OriginalImageState.READY, OriginalImageState.IDLE -> null
    }

/** 原图不可用又没有任何可解码字节时，按本地状态说明真实原因。 */
private fun localStateText(localState: AttachmentLocalState): String = when (localState) {
    AttachmentLocalState.PENDING -> "正在读取图片…"
    AttachmentLocalState.UNAVAILABLE -> "原件不可用，暂时无法查看大图"
    AttachmentLocalState.FAILED -> "本地保存失败，请检查手机存储空间或连接"
    AttachmentLocalState.SAVED -> "图片预览不可用"
}

/** 保存动作的文案随下载状态变化；失败文案必须包含「重试」，用户才知道还能再保存一次。 */
private fun saveActionLabel(state: AttachmentDownloadState): String = when (state) {
    AttachmentDownloadState.IDLE -> "保存至本地"
    AttachmentDownloadState.DOWNLOADING -> "保存中…"
    AttachmentDownloadState.SAVED -> "已保存到下载目录"
    AttachmentDownloadState.FAILED -> "保存失败，重试保存"
}
