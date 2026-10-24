package com.openandroidintelligence.mobile.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.notification.control.NotificationBindingPort
import com.openandroidintelligence.notification.control.NotificationPolicyPort
import com.openandroidintelligence.notification.control.NotificationPolicySnapshot
import com.openandroidintelligence.notification.control.NotificationPolicyUpdate
import com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「通知采集」设置子页。
 *
 * 数据边界（条目 1-4/2-4，裁决 D6）：
 * - 所有策略读写都走 [NotificationPolicyPort]——快照与
 *   NotificationAgentQueryGateway 的授权位同源，本页不存在第二套授权账本；
 * - 系统通知使用权只经 [NotificationBindingPort] 查询与跳转，授予动作只能
 *   发生在系统设置页，本页没有任何代替用户授予的捷径；
 * - [policy] 未装配（registry deny-first）时整组如实渲染「通知采集未装配」，
 *   不渲染任何点了不生效的假开关。
 *
 * 本组件自包含：只依赖 port 接口与公共设计令牌，不反向依赖
 * SettingsScreen 的任何内部符号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationCollectionScreen(
    policy: NotificationPolicyPort,
    binding: NotificationBindingPort,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loadInstalledApps: (() -> List<InstalledNotificationApp>)? = null,
) {
    var snapshot by remember(policy) { mutableStateOf(policy.snapshot()) }
    var listenerBound by remember(binding) { mutableStateOf(binding.isListenerBound()) }
    var rejection by remember { mutableStateOf<String?>(null) }
    val packageManager = LocalContext.current.applicationContext.packageManager
    val appLoader = remember(packageManager, loadInstalledApps) {
        loadInstalledApps ?: { loadInstalledNotificationApps(packageManager) }
    }
    var installedApps by remember { mutableStateOf<List<InstalledNotificationApp>?>(null) }
    var appLoadFailed by remember { mutableStateOf(false) }
    var appRefresh by remember { mutableStateOf(0) }
    var selectedPackages by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var selectionRevision by rememberSaveable { mutableStateOf("") }
    var selectionRejection by rememberSaveable { mutableStateOf<String?>(null) }

    // 包管理器查询放在后台；回到前台后重新读取，反映应用的安装与卸载。
    LaunchedEffect(snapshot.installed, appLoader, appRefresh) {
        if (!snapshot.installed) return@LaunchedEffect
        installedApps = null
        appLoadFailed = false
        try {
            installedApps = withContext(Dispatchers.IO) { appLoader() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            appLoadFailed = true
        }
    }

    // 策略变化经端口响应式流回（与本页发起的更新同一条权威链路）。
    LaunchedEffect(policy) {
        policy.observe().collect { latest -> snapshot = latest }
    }

    // 从系统「通知使用权」设置页返回前台时刷新真实绑定状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(binding, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerBound = binding.isListenerBound()
                appRefresh += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun applyUpdate(request: NotificationPolicyUpdate): NotificationPolicyUpdateOutcome {
        val outcome = policy.update(request)
        when (outcome) {
            is NotificationPolicyUpdateOutcome.Accepted -> {
                snapshot = outcome.snapshot
                rejection = null
            }
            is NotificationPolicyUpdateOutcome.Rejected -> rejection = outcome.reason
        }
        return outcome
    }

    if (snapshot.installed && selectedPackages != null) {
        NotificationAppPicker(
            apps = installedApps,
            loadFailed = appLoadFailed,
            savedPackages = snapshot.packageIds,
            selectedPackages = selectedPackages.orEmpty(),
            rejection = selectionRejection,
            onSelectionChanged = { selectedPackages = it },
            onRetry = { appRefresh += 1 },
            onReloadSelection = {
                snapshot = policy.snapshot()
                selectedPackages = snapshot.packageIds
                selectionRevision = snapshot.revision.toString()
                selectionRejection = null
            },
            onCancel = { selectedPackages = null },
            onSave = {
                val outcome = applyUpdate(
                    NotificationPolicyUpdate(
                        expectedRevision = selectionRevision.toULong(),
                        packageIds = selectedPackages.orEmpty(),
                    ),
                )
                when (outcome) {
                    is NotificationPolicyUpdateOutcome.Accepted -> selectedPackages = null
                    is NotificationPolicyUpdateOutcome.Rejected -> selectionRejection = outcome.reason
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.testTag("notification-collection-screen"),
        topBar = {
            TopAppBar(
                title = { Text("通知读取范围") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        // 宽屏限宽居中；系统栏 inset 由 Scaffold 给出，页面左右边距由内容容器负责。
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            modifier = Modifier
                .widthIn(max = Dimensions.ReadingWidth)
                .fillMaxHeight()
                // 页面左右边距：卡片不再贴着屏幕边缘；水平 padding 在滚动容器外层，
                // 滚动内容不会画到边距里。
                .padding(horizontal = Dimensions.ScreenHorizontal)
                .verticalScroll(rememberScrollState())
                .testTag("notification-collection-content"),
        ) {
            if (selectedPackages == null) {
                rejection?.let { reason -> RejectionNotice(reason) }
            }

            if (!snapshot.installed) {
                UninstalledNotice()
            } else {
                ConsentGroup(
                    snapshot = snapshot,
                    listenerBound = listenerBound,
                    onGrantChanged = { granted ->
                        applyUpdate(
                            NotificationPolicyUpdate(
                                expectedRevision = snapshot.revision,
                                granted = granted,
                            ),
                        )
                    },
                    onOpenListenerSettings = { context ->
                        if (!binding.openSystemListenerSettings(context)) rejection = "NOTIFICATION_SETTINGS_UNAVAILABLE"
                    },
                )
                AccessScopeGroup(
                    snapshot = snapshot,
                    onFieldAccessChanged = { access ->
                        applyUpdate(
                            NotificationPolicyUpdate(
                                expectedRevision = snapshot.revision,
                                fieldAccess = access,
                            ),
                        )
                    },
                )
                DeliveryModeGroup(
                    snapshot = snapshot,
                    onModeChanged = { mode ->
                        applyUpdate(
                            NotificationPolicyUpdate(
                                expectedRevision = snapshot.revision,
                                mode = mode,
                            ),
                        )
                    },
                )
                AllowlistGroup(
                    snapshot = snapshot,
                    installedApps = installedApps,
                    onSelect = {
                        selectedPackages = snapshot.packageIds
                        selectionRevision = snapshot.revision.toString()
                        selectionRejection = null
                    },
                    onRemove = { removed ->
                        applyUpdate(
                            NotificationPolicyUpdate(
                                expectedRevision = snapshot.revision,
                                packageIds = snapshot.packageIds - removed,
                            ),
                        )
                    },
                )
            }
        }
        }
    }
}

/** 未装配时的诚实降级：整组只有一句事实陈述，没有任何可交互控件。 */
@Composable
private fun UninstalledNotice() {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            Text(
                text = "本机暂不支持读取通知",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "这个版本没有装配通知读取能力，现在无法读取或查询通知。" +
                    "因此这里不会出现任何开关；支持该能力的版本会在本页提供真实的授权与范围设置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 更新被权威拒绝时的显式反馈：绝不静默假成功，也绝不吞掉拒绝原因。
 *
 * 位置放在内容区第一项（而不是悬浮在顶栏之上）：进页即可读到，也不会挡住标题与返回。
 */
@Composable
private fun RejectionNotice(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(AppRadius.Medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (reason == "NOTIFICATION_SETTINGS_UNAVAILABLE")
                "无法打开系统通知使用权设置，请到 Android 系统设置里手动开启本应用的通知使用权。"
                else "设置没有保存成功：$reason",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(
                horizontal = Dimensions.SpaceMedium,
                vertical = Dimensions.SpaceSmall,
            ),
        )
    }
}

@Composable
private fun ConsentGroup(
    snapshot: NotificationPolicySnapshot,
    listenerBound: Boolean,
    onGrantChanged: (Boolean) -> Unit,
    onOpenListenerSettings: (android.content.Context) -> Unit,
) {
    val context = LocalContext.current
    SettingsSectionCard {
        SettingsSwitchItem(
            headline = "允许读取通知",
            supporting = if (snapshot.granted) {
                "已允许：Agent 可以按下面的范围读取通知"
            } else {
                "未允许：Agent 读取通知时会被拒绝"
            },
            checked = snapshot.granted,
            onCheckedChange = onGrantChanged,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsListItem(
            headline = "系统通知使用权",
            supporting = if (listenerBound) {
                "已开启：系统允许本应用读取通知"
            } else {
                "未开启：系统不允许本应用读取通知，因此读不到任何通知"
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(
            onClick = { onOpenListenerSettings(context) },
            shape = RoundedCornerShape(AppRadius.Small),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimensions.SpaceSmall),
        ) {
            Text(text = "前往系统设置开启通知使用权")
        }
    }
}

@Composable
private fun AccessScopeGroup(
    snapshot: NotificationPolicySnapshot,
    onFieldAccessChanged: (NotificationFieldAccess) -> Unit,
) {
    val contentAccess = snapshot.fieldAccess == NotificationFieldAccess.CONTENT
    SettingsSectionCard {
        SettingsSwitchItem(
            headline = "读取通知的标题与正文",
            supporting = if (contentAccess) {
                "已开启：通知的标题与正文会被读取"
            } else {
                "已关闭：只读取应用名、时间等基本信息，标题与正文会被丢弃"
            },
            checked = contentAccess,
            onCheckedChange = { enabled ->
                onFieldAccessChanged(
                    if (enabled) NotificationFieldAccess.CONTENT else NotificationFieldAccess.METADATA,
                )
            },
        )
    }
}

@Composable
private fun DeliveryModeGroup(
    snapshot: NotificationPolicySnapshot,
    onModeChanged: (NotificationDeliveryMode) -> Unit,
) {
    val autoSend = snapshot.mode == NotificationDeliveryMode.AUTO_SEND
    SettingsSectionCard {
        SettingsSwitchItem(
            headline = "自动把通知发送给 Gateway",
            supporting = if (autoSend) {
                "已开启：符合条件的通知会自动发送给当前 Gateway"
            } else {
                "已关闭：只有 Agent 主动查询时才读取当前通知"
            },
            checked = autoSend,
            onCheckedChange = { enabled ->
                onModeChanged(
                    if (enabled) NotificationDeliveryMode.AUTO_SEND else NotificationDeliveryMode.ON_DEMAND,
                )
            },
        )
    }
}

@Composable
private fun AllowlistGroup(
    snapshot: NotificationPolicySnapshot,
    installedApps: List<InstalledNotificationApp>?,
    onSelect: () -> Unit,
    onRemove: (String) -> Unit,
) {
    SettingsSectionCard {
        Text(
            text = "读取哪些应用",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Button(
            onClick = onSelect,
            shape = RoundedCornerShape(AppRadius.Small),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimensions.SpaceSmall)
                .testTag("notification-select-apps"),
        ) {
            Text(text = "选择应用")
        }
        if (snapshot.packageIds.isEmpty()) {
            // 空态不是"没有内容"：白名单为空时一条通知也不会采集，必须说清楚。
            Text(
                text = "还没有选择任何应用：没有选择就不会读取任何通知。" +
                    "选择应用后，只有这些应用的通知会按上面的内容与发送方式处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimensions.SpaceSmall),
            )
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        snapshot.packageIds.forEachIndexed { index, packageId ->
            val app = installedApps?.firstOrNull { it.packageId == packageId }
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            SettingsListItem(
                headline = app?.label ?: packageId,
                supporting = when {
                    app != null -> packageId
                    installedApps != null -> "未安装或当前不可见，已保留原有选择"
                    else -> "应用信息暂不可用，已保留原有选择"
                },
                trailing = {
                    IconButton(onClick = { onRemove(packageId) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "移除 $packageId",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Dimensions.SmallIcon),
                        )
                    }
                },
            )
        }
    }
}
