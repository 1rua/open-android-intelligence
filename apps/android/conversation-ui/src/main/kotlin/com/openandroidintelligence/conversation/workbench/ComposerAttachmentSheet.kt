package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R as UiR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 附件来源的三个入口。
 *
 * 用枚举而不是三个独立回调：宿主（主 App 输入坞、默认数字助手输入栏）都要把这三种
 * 来源映射到各自的启动路径，用同一份枚举可以保证两边不会漏掉某一项、也不会各自命名。
 */
enum class ComposerAttachmentSource {
    /** 拍照：由宿主的相机路径处理。 */
    CAMERA,

    /** 相册选择：系统 Photo Picker。 */
    GALLERY,

    /** 文档选择：系统 SAF 文档选择器。 */
    DOCUMENT,
}

/** Compose 测试钩子：底部卡片与三个入口各自有稳定标签。 */
object ComposerAttachmentSheetTags {
    const val SHEET = "composer_attachment_sheet"
    const val CAMERA = "composer_attachment_camera"
    const val GALLERY = "composer_attachment_gallery"
    const val DOCUMENT = "composer_attachment_document"
}

/**
 * 附件来源底部卡片：**主 App 输入坞与默认数字助手共用这一份实现**。
 *
 * 为什么抽出来：默认数字助手原先用自绘的 `DropdownMenu`，圆角、背景、间距与主 App 的
 * MD3 底部卡片完全不同，用户在两处看到的是两种交互。
 *
 * 只负责「选哪一类来源」，不负责打开系统选择器、不读字节、不碰上传：
 * 这些仍由宿主走既有路径（`ConversationAttachmentPickers` / 助手的选择器 Activity），
 * 因此附件暂存与门控提交流程一行未改。
 *
 * @param onDismiss 用户下滑关闭、点击遮罩或返回键时回调（选择某一项后也会先回调它）。
 * @param onPick 选中某一类来源；调用方应在此关闭卡片并启动对应系统选择器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerAttachmentSheet(
    onDismiss: () -> Unit,
    onPick: (ComposerAttachmentSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(ComposerAttachmentSheetTags.SHEET),
    ) {
        ComposerAttachmentSheetContent(onDismiss = onDismiss, onPick = onPick)
    }
}

/**
 * 底部卡片的**内容层**（标题 + 三个来源），不含宿主外壳。
 *
 * 拆出来的原因：`ModalBottomSheet` 内部是 `Dialog`，会以 `TYPE_APPLICATION` 新建窗口；
 * 而系统助理会话的宿主是 `VoiceInteractionSession`（窗口类型 2031），系统会直接抛
 * `Window type mismatch`。助手侧因此需要在**自己的窗口内**渲染同一份内容
 * （见 assistant-holder 里的 `ComposerAttachmentOverlay`），而不是再开一个 Dialog 窗口。
 *
 * 圆角、背景、条目节奏都在这里，两个宿主看到的视觉完全一致。
 */
@Composable
fun ComposerAttachmentSheetContent(
    onDismiss: () -> Unit,
    onPick: (ComposerAttachmentSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun choose(source: ComposerAttachmentSource) {
        // 先关卡片再启动选择器：卡片留在最上层会盖住系统页面，用户会以为没反应。
        onDismiss()
        onPick(source)
    }

    Column(
        modifier
            .navigationBarsPadding()
            .padding(bottom = Dimensions.SpaceLarge),
    ) {
        Text(
            stringResource(UiR.string.conv_attachment_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(
                horizontal = Dimensions.SpaceLarge,
                vertical = Dimensions.SpaceSmall,
            ),
        )
        ComposerAttachmentChoice(
            icon = Icons.Default.PhotoCamera,
            title = stringResource(UiR.string.conv_attachment_sheet_camera_title),
            description = stringResource(UiR.string.conv_attachment_sheet_camera_desc),
            tag = ComposerAttachmentSheetTags.CAMERA,
        ) { choose(ComposerAttachmentSource.CAMERA) }
        ComposerAttachmentChoice(
            icon = Icons.Default.PhotoLibrary,
            title = stringResource(UiR.string.conv_attachment_sheet_gallery_title),
            description = stringResource(UiR.string.conv_attachment_sheet_gallery_desc),
            tag = ComposerAttachmentSheetTags.GALLERY,
        ) { choose(ComposerAttachmentSource.GALLERY) }
        ComposerAttachmentChoice(
            icon = Icons.Default.AttachFile,
            title = stringResource(UiR.string.conv_attachment_sheet_doc_title),
            description = stringResource(UiR.string.conv_attachment_sheet_doc_desc),
            tag = ComposerAttachmentSheetTags.DOCUMENT,
        ) { choose(ComposerAttachmentSource.DOCUMENT) }
    }
}

/**
 * 在宿主**自己的窗口内**渲染的附件卡片（助手浮层专用）。
 *
 * 与主 App 的 `ModalBottomSheet` 是同一份内容与同一套令牌：28dp 顶部圆角、surfaceContainerLow
 * 底色、条目 56dp 高、40dp 圆形前置图标。差别只在承载方式——这里用图层叠加与位移动画，
 * 不创建 Dialog 窗口，因此不会与 `VoiceInteractionSession` 的窗口类型冲突。
 *
 * 交互对齐 M3 底部卡片：点遮罩关闭；减少动态时不做位移，只做极短淡入淡出。
 *
 * @param visible 是否展开；为 true 时覆盖整个宿主区域。
 */
@Composable
fun ComposerAttachmentOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onPick: (ComposerAttachmentSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val density = LocalDensity.current
    // 进场：遮罩淡入 + 卡片从下往上落位；减少动态时直接落位。
    var entered by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { entered = true }
    val spec = if (reduceMotion) androidx.compose.animation.core.tween(MotionSpecs.Fast) else MotionSpecs.MomentumSpring
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spec,
        label = "composer-attachment-sheet",
    )
    val shiftPx = with(density) { (SheetTravel).toPx() }

    Box(modifier.fillMaxSize()) {
        // 遮罩：点击关闭。颜色与 M3 scrim 一致，读屏不额外朗读它。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress }
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = ScrimAlpha))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        Surface(
            shape = RoundedCornerShape(topStart = AppRadius.ExtraLarge, topEnd = AppRadius.ExtraLarge),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    // 进场：卡片从下方 48dp 落位并淡入。位移只作用于绘制层，不参与测量，
                    // 因此不会改变宿主的窗口高度。
                    translationY = shiftPx * (1f - progress)
                    alpha = progress
                }
                .testTag(ComposerAttachmentSheetTags.SHEET),
        ) {
            ComposerAttachmentSheetContent(onDismiss = onDismiss, onPick = onPick)
        }
    }
}

/**
 * 底部弹层的单个选项：M3 ListItem 的视觉节奏（40dp tonal 圆形前置图标 +
 * 标题/说明两行），条目最小高度 56dp。
 */
@Composable
private fun ComposerAttachmentChoice(
    icon: ImageVector,
    title: String,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AttachmentChoiceMinHeight)
            .bouncyClickable(
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(horizontal = Dimensions.SpaceLarge, vertical = Dimensions.SpaceCompact)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(AttachmentChoiceIconDiameter),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Dimensions.Icon),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 与主 App 输入坞弹层一致的条目高度。 */
private val AttachmentChoiceMinHeight = 56.dp

/** 与主 App 输入坞弹层一致的前置圆形图标直径。 */
private val AttachmentChoiceIconDiameter = 40.dp

/** 卡片进场时从屏幕下方上移的距离；与 M3 底部卡片的手感一致。 */
private val SheetTravel = 48.dp

/** 遮罩不透明度：与 M3 modal scrim 的默认强度一致。 */
private const val ScrimAlpha = 0.32f
