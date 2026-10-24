package com.openandroidintelligence.mobile.notifications

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.BuildConfig

/** 选择只编辑草稿；保存成功前不改变通知采集白名单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationAppPicker(
    apps: List<InstalledNotificationApp>?,
    loadFailed: Boolean,
    savedPackages: List<String>,
    selectedPackages: List<String>,
    rejection: String?,
    onSelectionChanged: (List<String>) -> Unit,
    onRetry: () -> Unit,
    onReloadSelection: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = remember(apps, savedPackages) {
        val installedIds = apps.orEmpty().map { it.packageId }.toSet()
        apps.orEmpty() + savedPackages.filterNot { it in installedIds }.map {
            InstalledNotificationApp(packageId = it, label = it)
        }
    }
    val filtered = remember(candidates, query) {
        val search = query.trim()
        candidates.filter {
            it.label.contains(search, ignoreCase = true) || it.packageId.contains(search, ignoreCase = true)
        }
    }
    val installedIds = remember(apps) { apps.orEmpty().map { it.packageId }.toSet() }
    val selectedIds = selectedPackages.toSet()

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.TopCenter) {
                Scaffold(
                    modifier = Modifier
                        .widthIn(max = Dimensions.ReadingWidth)
                        .fillMaxSize()
                        .testTag("notification-app-picker"),
                    topBar = {
                        TopAppBar(
                            title = { Text("选择应用") },
                            navigationIcon = {
                                IconButton(onClick = onCancel) {
                                    Icon(Icons.Default.Close, contentDescription = "取消选择")
                                }
                            },
                            actions = {
                                TextButton(
                                    onClick = onSave,
                                    enabled = apps != null && !loadFailed && rejection == null,
                                    modifier = Modifier.testTag("notification-app-save"),
                                ) { Text("保存") }
                            },
                        )
                    },
                ) { padding ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                        modifier = Modifier
                            .padding(padding)
                            .imePadding()
                            .padding(horizontal = Dimensions.ScreenHorizontal)
                            .fillMaxSize(),
                    ) {
                        Text(
                            text = "已选 ${selectedIds.size} 个应用；未选择任何应用时不会采集通知。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (BuildConfig.FLAVOR == "play") {
                            Text(
                                text = "此发行版仅显示系统允许查询的已安装应用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text("搜索应用名称或包名") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().testTag("notification-app-search"),
                        )
                        if (rejection != null) {
                            Text(
                                text = "设置没有保存成功：$rejection",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onReloadSelection) { Text("重新载入已保存选择") }
                        }
                        when {
                            loadFailed -> {
                                Text("无法读取本机应用列表，请重试。", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("重试") }
                            }
                            apps == null -> {
                                CircularProgressIndicator(modifier = Modifier.size(Dimensions.Progress))
                                Text("正在读取本机应用…")
                            }
                            filtered.isEmpty() -> Text(
                                text = if (query.isBlank()) "未找到可选择的已安装应用。" else "没有匹配的应用。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> LazyColumn(
                                modifier = Modifier.weight(1f).testTag("notification-app-list"),
                            ) {
                                items(filtered, key = { it.packageId }) { app ->
                                    val checked = app.packageId in selectedIds
                                    ListItem(
                                        headlineContent = {
                                            Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        },
                                        supportingContent = {
                                            Text(
                                                if (app.packageId in installedIds) app.packageId
                                                else "未安装或当前不可见，可以保留或取消原有选择",
                                            )
                                        },
                                        leadingContent = { ApplicationIcon(app) },
                                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                                        modifier = Modifier
                                            .testTag("notification-app-${app.packageId}")
                                            .toggleable(value = checked, role = Role.Checkbox) { selected ->
                                                onSelectionChanged(
                                                    if (selected) selectedPackages + app.packageId
                                                    else selectedPackages - app.packageId,
                                                )
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplicationIcon(app: InstalledNotificationApp) {
    val size = with(LocalDensity.current) { Dimensions.LeadingIconContainer.roundToPx() }
    val bitmap = remember(app.icon, size) { app.icon?.toBitmap(width = size, height = size)?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(Dimensions.LeadingIconContainer))
    } else {
        Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(Dimensions.LeadingIconContainer))
    }
}
