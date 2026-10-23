package com.openandroidintelligence.mobile.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openandroidintelligence.conversation.components.SettingsCardHeader
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions

internal const val APP_RELEASES_URL = "https://github.com/1rua/open-android-intelligence/releases"

@Composable
fun AppUpdateRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val installed = installedAppBuild(context.applicationContext)
                return AppUpdateViewModel(
                    installed = installed,
                    preferences = AppUpdatePreferences(context, defaultAppUpdateChannel(installed)),
                ) as T
            }
        }
    }
    val model: AppUpdateViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    var openFailed by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.checkIfNeeded() }
    AppUpdateScreen(
        installed = model.installed,
        state = state,
        onBack = onBack,
        onSelectChannel = model::selectChannel,
        onCheck = model::check,
        onOpenRelease = { url ->
            openFailed = try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                false
            } catch (_: ActivityNotFoundException) {
                true
            }
        },
        openFailed = openFailed,
        onOpenFailedShown = { openFailed = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppUpdateScreen(
    installed: InstalledAppBuild,
    state: AppUpdateUiState,
    onBack: () -> Unit,
    onSelectChannel: (AppUpdateChannel) -> Unit,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
    openFailed: Boolean = false,
    onOpenFailedShown: () -> Unit = {},
) {
    // 「打不开浏览器」属于与按钮就近的操作结果：走 Scaffold 的 Snackbar，
    // 不再挂在页面最底部、离触发的按钮隔着一整屏。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(openFailed) {
        if (openFailed) {
            snackbarHostState.showSnackbar(
                message = "未找到可打开发布页的浏览器，请确认系统浏览器已安装。",
                withDismissAction = true,
            )
            // 消费即复位：否则第二次、第三次失败时布尔值一直是 true，
            // LaunchedEffect 不会重启，用户再也看不到提示。
            onOpenFailedShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用更新") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = Dimensions.ReadingWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimensions.ScreenHorizontal, vertical = Dimensions.SpaceSmall),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            ) {
                SettingsSectionCard {
                    SettingsCardHeader("当前版本")
                    Text(installed.versionName, style = MaterialTheme.typography.titleLarge)
                    Text(appBuildChannelLabel(installed.channel, installed.versionName), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingsSectionCard {
                    SettingsCardHeader("更新渠道")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppUpdateChannel.entries.forEachIndexed { index, channel ->
                            SegmentedButton(
                                selected = state.channel == channel,
                                onClick = { onSelectChannel(channel) },
                                shape = SegmentedButtonDefaults.itemShape(index, AppUpdateChannel.entries.size),
                            ) {
                                Text(if (channel == AppUpdateChannel.STABLE) "正式版" else "预览版")
                            }
                        }
                    }
                    Text(
                        text = if (state.channel == AppUpdateChannel.STABLE) {
                            "仅检查已发布的正式版本。"
                        } else {
                            "跟踪开发预览测试版和带版本号的测试版（如 Beta、RC），也会提示后续可升级的正式版。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onCheck, enabled = !state.isChecking, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isChecking) "正在检查更新…" else "检查更新")
                    }
                    // 进度条放在按钮区：检查期间只表达「正在进行」，
                    // 上一次的结果卡片保持常驻，不再被进度条顶掉（避免读起来像结果被清空）。
                    if (state.isChecking) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "正在获取发布信息，下方保留上次结果…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.preferenceSaveFailed) {
                        Text(
                            "更新渠道未能保存，下次打开时可能恢复原设置。请重试。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Column(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                ) {
                    state.result?.let { result ->
                        AppUpdateResultCard(result, state.channel, onOpenRelease)
                    }
                }
                OutlinedButton(onClick = { onOpenRelease(APP_RELEASES_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text("查看所有发布")
                }
                Text(
                    "更新信息来自本项目的 GitHub 发布页，打开此页面会自动检查，也可手动重查。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppUpdateResultCard(
    result: AppUpdateResult,
    channel: AppUpdateChannel,
    onOpenRelease: (String) -> Unit,
) {
    SettingsSectionCard {
        when (result) {
            is AppUpdateResult.Available -> {
                val release = result.release
                SettingsCardHeader("发现新版本")
                Text(release.versionName, style = MaterialTheme.typography.titleLarge)
                Text("${appBuildChannelLabel(release.channel, release.versionName)} · ${release.releaseName}")
                if (release.summary.isNotBlank()) {
                    Text(
                        release.summary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!release.signingMatches) {
                    Text(
                        "此版本的签名与当前安装不同或无法核对，不能确认可直接覆盖安装。请先查看发行说明中的迁移安排。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = { onOpenRelease(release.releaseUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Text("前往发布页")
                }
            }
            is AppUpdateResult.UpToDate -> {
                SettingsCardHeader("暂无可升级版本")
                Text("此渠道最新可检测版本为 ${result.latestVersionName}，当前安装无需升级。")
            }
            AppUpdateResult.NotPublished -> {
                SettingsCardHeader(if (channel == AppUpdateChannel.STABLE) "尚未发布正式版" else "尚未发布预览版")
                Text("发布后可在这里检查更新。")
            }
            is AppUpdateResult.Unsupported -> {
                SettingsCardHeader("暂无法检查此版本")
                Text(when (result.reason) {
                    AppUpdateUnsupportedReason.DISTRIBUTION -> "当前分发渠道请通过原安装来源更新。"
                    AppUpdateUnsupportedReason.METADATA_MISSING -> "发布版本尚未提供可验证的更新信息，请查看发布页。"
                })
            }
            is AppUpdateResult.Unavailable -> {
                SettingsCardHeader("检查更新失败")
                Text(
                    text = when (result.reason) {
                        AppUpdateFailure.NETWORK -> "网络连接失败，请检查网络后重试。"
                        AppUpdateFailure.RATE_LIMITED -> "更新服务的访问次数已达上限，请稍后重试。"
                        AppUpdateFailure.SERVICE -> "更新服务暂时不可用，请稍后重试。"
                        AppUpdateFailure.INVALID_RESPONSE -> "发布信息不完整或无法验证，请稍后重试或查看发布页。"
                        AppUpdateFailure.INCOMPLETE_RELEASE_LIST -> "预览发布列表过长，未能完整检查；请稍后重试或查看全部发布。"
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
