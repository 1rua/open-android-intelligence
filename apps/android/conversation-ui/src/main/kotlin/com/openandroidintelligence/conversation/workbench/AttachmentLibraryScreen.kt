package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 附件库：当前真实附件草稿 + 历史媒体。
 *
 * 视觉与设置页/抽屉同源：品牌缝线 + 字标级标题、`surfaceContainer` 卡片、
 * 分组标题 `labelMedium` + `onSurfaceVariant`。远端历史内容没有读取端口时
 * 明确呈现边界，不伪造条目。
 */
@Composable
fun AttachmentLibraryScreen(
    attachments: List<AttachmentDraft>, onPickGallery: () -> Unit, onPickDocument: () -> Unit,
    onPickCamera: () -> Unit, onRemoveAttachment: (String) -> Unit, onRetryAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    historical: List<com.openandroidintelligence.conversation.model.TimelineAttachment> = emptyList(),
    onDownloadHistorical: ((String) -> Unit)? = null,
    onRequestOriginalHistorical: ((String) -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = Dimensions.ReadingWidth)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimensions.ScreenHorizontal,
                end = Dimensions.ScreenHorizontal,
                top = Dimensions.ScreenVertical,
                bottom = Dimensions.SpaceLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
        item(key = "library-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignalStitch(modifier = Modifier.height(LibraryStitchHeight).width(LibraryStitchWidth))
                Spacer(Modifier.width(Dimensions.SpaceCompact))
                Column(modifier = Modifier.weight(1f)) {
                    Text("附件", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "只分享你主动选择的内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimensions.SpaceTiny),
                    )
                }
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭附件库")
                    }
                }
            }
        }

        item(key = "library-pickers") {
            // 三个入口共用同一套 ListItem 节奏：32dp tonal 前置图标 + 56dp 行高。
            Surface(
                shape = RoundedCornerShape(AppRadius.Medium),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    SettingsListItem(
                        headline = "选择图片",
                        supporting = "从相册选择已有图片",
                        icon = Icons.Default.PhotoLibrary,
                        tone = SettingsTone.PRIMARY,
                        onClick = onPickGallery,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "选择文件",
                        supporting = "通过系统文件选择器添加文档",
                        icon = Icons.Default.AttachFile,
                        onClick = onPickDocument,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "拍摄照片",
                        supporting = "使用系统相机拍摄照片",
                        icon = Icons.Default.PhotoCamera,
                        onClick = onPickCamera,
                    )
                }
            }
        }

        item(key = "library-pending-header") {
            LibrarySectionHeader(title = "当前待发送", detail = if (attachments.isEmpty()) null else "${attachments.size} 个")
        }
        if (attachments.isEmpty()) {
            item(key = "library-pending-empty") {
                Surface(
                    shape = RoundedCornerShape(AppRadius.Medium),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(Dimensions.SpaceLarge),
                        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dimensions.Icon),
                        )
                        Text("还没有选择附件", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "选择后可在此查看上传与核验状态，再回到对话发送。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(attachments, key = { it.id.value }) { draft ->
            AttachmentDraftChip(
                draft = draft,
                onRemove = { onRemoveAttachment(draft.id.value) },
                onRetry = { onRetryAttachment(draft.id.value) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "library-history-header") {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LibrarySectionHeader(
                    title = "历史媒体",
                    detail = if (historical.isEmpty()) null else "${historical.size} 个",
                    modifier = Modifier.padding(top = Dimensions.SpaceSmall),
                )
                if (historical.isEmpty()) {
                    Text(
                        text = "当前对话暂无历史媒体。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(historical, key = { "history_" + it.draftId }) { media ->
            HistoricalAttachmentCard(
                media,
                onDownloadHistorical,
                onRequestOriginal = onRequestOriginalHistorical,
            )
        }
        }
    }
}

/** 附件库分组标题：labelMedium + onSurfaceVariant，右侧可选计数。 */
@Composable
private fun LibrarySectionHeader(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = AppTextStyles.MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 标题旁的品牌缝线尺寸：只做签名，不做整屏品牌墙。 */
private val LibraryStitchWidth = 8.dp
private val LibraryStitchHeight = 28.dp
