package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.theme.Dimensions

/** 当前真实附件草稿；远端历史内容没有读取端口时明确呈现边界。 */
@Composable
fun AttachmentLibraryScreen(
    attachments: List<AttachmentDraft>, onPickGallery: () -> Unit, onPickDocument: () -> Unit,
    onPickCamera: () -> Unit, onRemoveAttachment: (String) -> Unit, onRetryAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = Dimensions.ReadingWidth).fillMaxSize(),
            contentPadding = PaddingValues(Dimensions.SpaceLarge), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium)) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("附件", style = MaterialTheme.typography.headlineMedium)
                        Text("只分享你主动选择的内容", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimensions.SpaceSmall))
                    }
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "关闭附件库")
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    FilledTonalButton(onClick = onPickGallery, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(Dimensions.SmallIcon))
                        Spacer(Modifier.width(Dimensions.SpaceSmall))
                        Text("选择图片")
                    }
                    OutlinedButton(onClick = onPickDocument, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(Dimensions.SmallIcon))
                        Spacer(Modifier.width(Dimensions.SpaceSmall))
                        Text("选择文件")
                    }
                    TextButton(onClick = onPickCamera, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(Dimensions.SmallIcon))
                        Spacer(Modifier.width(Dimensions.SpaceSmall))
                        Text("拍摄照片")
                    }
                }
            }
            item { Text("当前待发送", style = MaterialTheme.typography.titleMedium) }
            if (attachments.isEmpty()) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Dimensions.SpaceLarge), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Text("还没有选择附件", style = MaterialTheme.typography.titleMedium)
                        Text("选择后可在此查看上传与核验状态，再回到对话发送。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(attachments, key = { it.id.value }) { draft ->
                AttachmentDraftChip(draft, { onRemoveAttachment(draft.id.value) }, { onRetryAttachment(draft.id.value) }, Modifier.fillMaxWidth())
            }
            item {
                HorizontalDivider()
                Text("历史媒体", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Dimensions.SpaceLarge))
                Text("历史媒体读取暂不可用。你仍可通过系统选择器添加本地文件。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimensions.SpaceSmall))
            }
        }
    }
}
