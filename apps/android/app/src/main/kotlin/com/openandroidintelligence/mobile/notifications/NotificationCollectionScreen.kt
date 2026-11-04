package com.openandroidintelligence.mobile.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
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
 * 嵌入模式只提供原有分组，滚动与页面标题由调用方管理。
 */
@Composable
internal fun NotificationCollectionScreen(
    policy: NotificationPolicyPort,
    binding: NotificationBindingPort,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loadInstalledApps: (() -> List<InstalledNotificationApp>)? = null,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
    embedded: Boolean = false,
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
            presentation = presentation,
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

    val groups: @Composable () -> Unit = {
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
    if (embedded) {
        Column(
            modifier = modifier.testTag("notification-collection-content"),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) { groups() }
    } else {
        SettingsPageScaffold(
            title = "通知读取范围",
            presentation = presentation,
            onBack = onBack,
            modifier = modifier.testTag("notification-collection-screen"),
        ) { padding ->
            SettingsPageColumn(
                contentPadding = padding,
                modifier = Modifier.testTag("notification-collection-content"),
            ) { groups() }
        }
    }
}

/** 未装配时的诚实降级：整组只有一句事实陈述，没有任何可交互控件。 */
@Composable
private fun UninstalledNotice() {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            Text(
                text = "暂不支持读取通知",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "当前版本未包含通知读取组件，暂无法读取或查询系统通知。",
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
                "无法打开系统设置，请前往系统设置手动开启通知使用权。"
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
                "已开启：符合条件的通知使用原有自动投递配置"
            } else {
                "旧自动发送已关闭；通知查询和另行配置的主动推送不受此开关控制"
            },
            checked = autoSend,
            onCheckedChange = { enabled ->
                onModeChanged(
                    if (enabled) NotificationDeliveryMode.AUTO_SEND else NotificationDeliveryMode.ON_DEMAND,
                )
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsListItem(
            headline = "旧通知投递通道",
            supporting = when {
                !snapshot.outboxAvailable -> "加密待发队列不可用，旧通知无法排队投递。"
                !snapshot.deliveryDispatcherWired -> "加密待发队列已装配；最近创建的旧通知运行时未装配投递组件，或尚未启动监听。"
                else -> "最近创建的旧通知运行时已装配投递组件；这不表示当前正在投递或投递成功。"
            },
            modifier = Modifier.testTag("notification-legacy-delivery-status"),
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
                text = "未选择应用时不会读取任何通知。请点击上方按钮添加应用。",
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
