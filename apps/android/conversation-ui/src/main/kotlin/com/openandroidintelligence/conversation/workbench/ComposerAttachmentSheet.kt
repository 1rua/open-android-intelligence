package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.theme.Dimensions

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
 * MD3 底部卡片完全不同，用户在两处看到的是两种交互。这里保留主 App 既有的视觉与动画
 * （`ModalBottomSheet` 的圆角、拖拽关闭、进出场），由两个宿主各自提供「选完之后做什么」。
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
    fun choose(source: ComposerAttachmentSource) {
        // 先关卡片再启动选择器：卡片留在最上层会盖住系统页面，用户会以为没反应。
        onDismiss()
        onPick(source)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(ComposerAttachmentSheetTags.SHEET),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(bottom = Dimensions.SpaceLarge),
        ) {
            Text(
                "添加到当前对话",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(
                    horizontal = Dimensions.SpaceLarge,
                    vertical = Dimensions.SpaceSmall,
                ),
            )
            ComposerAttachmentChoice(
                icon = Icons.Default.PhotoCamera,
                title = "拍照",
                description = "使用系统相机拍摄照片",
                tag = ComposerAttachmentSheetTags.CAMERA,
            ) { choose(ComposerAttachmentSource.CAMERA) }
            ComposerAttachmentChoice(
                icon = Icons.Default.PhotoLibrary,
                title = "相册选择",
                description = "从相册选择一张或多张图片",
                tag = ComposerAttachmentSheetTags.GALLERY,
            ) { choose(ComposerAttachmentSource.GALLERY) }
            ComposerAttachmentChoice(
                icon = Icons.Default.AttachFile,
                title = "文档选择",
                description = "通过系统文件选择器添加文档",
                tag = ComposerAttachmentSheetTags.DOCUMENT,
            ) { choose(ComposerAttachmentSource.DOCUMENT) }
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
