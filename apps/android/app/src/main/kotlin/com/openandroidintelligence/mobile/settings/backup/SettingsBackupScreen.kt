package com.openandroidintelligence.mobile.settings.backup

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.AndroidAccountProfileStore
import com.openandroidintelligence.mobile.GatewayRuntime
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.ReplyNotificationSettings
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import com.openandroidintelligence.mobile.updates.AppUpdatePreferences
import com.openandroidintelligence.mobile.updates.defaultAppUpdateChannel
import com.openandroidintelligence.mobile.updates.installedAppBuild
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置备份与恢复页。
 *
 * 只提供两件事：把当前设置导出成一个 JSON 文件、从文件恢复。确认框列出
 * 「将被覆盖的设置」与「不会恢复的内容」，避免把导入说成一次无风险的同步。
 */
@Composable
internal fun SettingsBackupRoute(
    runtime: GatewayRuntime?,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? OpenAndroidIntelligenceApplication
    val installed = remember(context) { installedAppBuild(context.applicationContext) }
    val coordinator = remember(app, runtime) { backupCoordinator(context, app, runtime) }
    val model: SettingsBackupViewModel = viewModel(
        key = "settings-backup",
        factory = remember(coordinator, installed.versionName) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsBackupViewModel(coordinator, installed.versionName) as T
            }
        },
    )
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.prepare() }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportTo(uri) { target -> context.contentResolver.openOutputStream(target) }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.inspect(uri) { target -> readBackupText(context, target) }
    }

    SettingsBackupContent(
        state = state,
        presentation = presentation,
        onBack = onBack,
        onExport = { saveFile.launch(SettingsBackupFileNames.suggested()) },
        onImport = { openFile.launch(arrayOf("application/json", "text/plain", "*/*")) },
        onDismissNotice = model::dismissNotice,
    )

    state.pendingImport?.let { pending ->
        ImportConfirmationDialog(
            pending = pending,
            onDismiss = model::dismissPending,
            onConfirm = model::confirmImport,
        )
    }
}

/** 「备份与恢复」页面正文：只依赖状态与回调，便于按普通/侧栏、离线、失败等场景验证。 */
@Composable
internal fun SettingsBackupContent(
    state: SettingsBackupUiState,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    SettingsPageScaffold("备份与恢复", presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            val unavailable = state.unavailableReason
            if (unavailable != null) {
                SettingsSectionCard {
                    Text(unavailable, style = MaterialTheme.typography.bodyMedium)
                }
                return@SettingsPageColumn
            }
            SectionLabel("备份")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "导出设置",
                    supporting = "保存为 JSON 文件，可带到另一台设备或重装后恢复",
                    enabled = !state.busy && !state.isPreparing,
                    onClick = onExport,
                )
                SettingsListItem(
                    headline = "当前可导出内容",
                    supporting = if (state.isPreparing) "正在读取…" else {
                        "${state.exportSummary.size} 类设置：" + state.exportSummary.joinToString("；")
                    },
                )
            }

            SectionLabel("恢复")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "导入设置",
                    supporting = "选择之前导出的 JSON 文件；导入前会先列出将被覆盖的项目",
                    enabled = !state.busy && !state.isPreparing,
                    onClick = onImport,
                )
                Text(
                    "不会导入：账号密码、登录凭据、主密钥、会话历史、附件、" +
                        "系统权限与设备能力的实际授权状态。这些仍需在手机上手确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.notice?.let { notice ->
                SettingsSectionCard {
                    Text(
                        notice.displayMessage(LocalContext.current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (notice.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = onDismissNotice) { Text("知道了") }
                }
            }
        }
    }
}

/** 供 UI 测试直接渲染确认框；生产代码只经由 [SettingsBackupRoute] 使用。 */
@Composable
internal fun ImportConfirmationDialogForTest(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) = ImportConfirmationDialog(pending, onDismiss, onConfirm)

@Composable
private fun ImportConfirmationDialog(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入这份设置？") },
        text = {
            // 十类摘要 + 安全说明在多语言/大字号/横屏下会超出对话框可用高度：
            // 必须可滚动，否则用户看不到后面的「不会恢复什么」就点了确认。
            Column(
                modifier = Modifier.verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Text(
                    "来源：App ${pending.appVersionName}，导出于 ${readableTime(pending.exportedAt)}；" +
                        "本机 App ${pending.localVersionName}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "将覆盖本机的同名设置项：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                pending.summaries.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (pending.ignoredFields.isNotEmpty()) {
                    Text(
                        "文件中有 ${pending.ignoredFields.size} 个本版本不认识的字段，将被忽略。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "以下内容与当前一致的项目不会被重复写入；" +
                        "账号凭据、系统权限与设备能力总开关不会被导入修改。导入失败会自动恢复本机原设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("导入并覆盖") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun readableTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value)

/** 读取上限：设置文件只应是几十 KB；异常大的文件直接拒绝，避免把内存交给对方。 */
private const val MAX_BACKUP_BYTES = 2 * 1_024 * 1_024

private fun readBackupText(context: Context, uri: android.net.Uri): String? {
    val stream = context.contentResolver.openInputStream(uri) ?: return null
    // 逐块读取并在超限时立刻停下：既不用 InputStream.readNBytes（Android 13 才有的 API，
    // minSdk 31 上会 NoSuchMethodError），也不会把任意大的文件整个读进内存。
    val bytes = stream.use { input ->
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > MAX_BACKUP_BYTES) {
                throw SettingsBackupFormatException(
                    "文件过大，不是本 App 导出的设置文件。", BackupFormatErrorCode.FILE_TOO_LARGE,
                )
            }
        }
        buffer.toByteArray()
    }
    return String(bytes, Charsets.UTF_8)
}

/** 组合根：把各设置的真实权威交给协调器，缺一项只让对应节不参与导出/导入。 */
private fun backupCoordinator(
    context: Context,
    app: OpenAndroidIntelligenceApplication?,
    runtime: GatewayRuntime?,
): SettingsBackupCoordinator? {
    if (app == null) return null
    val installed = installedAppBuild(context.applicationContext)
    return runCatching {
        SettingsBackupCoordinator(
            context = context.applicationContext,
            appearance = app.appearancePreferences,
            profiles = AndroidAccountProfileStore(context.applicationContext),
            grants = app.pairingGrants,
            pluginHost = app.pluginHost,
            // 有运行时就用它的实例（导入后能立即刷新界面）；没有运行时则直接读同一份
            // 偏好文件——此时不存在「另一个实例的缓存值过期」的问题。
            replyNotifications = runtime?.replyNotifications ?: ReplyNotificationSettings(context.applicationContext),
            runtime = runtime,
            devicePush = app.devicePush?.runtime,
            appUpdates = AppUpdatePreferences(context.applicationContext, defaultAppUpdateChannel(installed)),
            notificationPolicy = NotificationControlRegistry.policyPort(),
        )
    }.getOrNull()
}

