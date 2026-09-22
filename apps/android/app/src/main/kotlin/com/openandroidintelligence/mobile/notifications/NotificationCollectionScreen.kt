package com.openandroidintelligence.mobile.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun NotificationCollectionScreen(
    policy: NotificationPolicyPort,
    binding: NotificationBindingPort,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var snapshot by remember(policy) { mutableStateOf(policy.snapshot()) }
    var listenerBound by remember(binding) { mutableStateOf(binding.isListenerBound()) }
    var rejection by remember { mutableStateOf<String?>(null) }
    var draftPackage by remember { mutableStateOf("") }

    // 策略变化经端口响应式流回（与本页发起的更新同一条权威链路）。
    LaunchedEffect(policy) {
        policy.observe().collect { latest -> snapshot = latest }
    }

    // 从系统「通知使用权」设置页返回前台时刷新真实绑定状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(binding, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) listenerBound = binding.isListenerBound()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun applyUpdate(request: NotificationPolicyUpdate) {
        when (val outcome = policy.update(request)) {
            is NotificationPolicyUpdateOutcome.Accepted -> {
                snapshot = outcome.snapshot
                rejection = null
            }
            is NotificationPolicyUpdateOutcome.Rejected -> rejection = outcome.reason
        }
    }

    Scaffold(
        modifier = modifier.testTag("notification-collection-screen"),
        topBar = {
            TopAppBar(
                title = { Text("通知采集") },
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
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("notification-collection-content"),
        ) {
            rejection?.let { reason ->
                RejectionNotice(reason)
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
                        binding.openSystemListenerSettings(context)
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
                    draftPackage = draftPackage,
                    onDraftChanged = { draftPackage = it },
                    onAdd = {
                        val candidate = draftPackage.trim()
                        if (candidate.isNotEmpty()) {
                            applyUpdate(
                                NotificationPolicyUpdate(
                                    expectedRevision = snapshot.revision,
                                    packageIds = snapshot.packageIds + candidate,
                                ),
                            )
                            draftPackage = ""
                        }
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

/** 未装配时的诚实降级：整组只有一句事实陈述，没有任何可交互控件。 */
@Composable
private fun UninstalledNotice() {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            Text(
                text = "通知采集未装配",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "宿主进程没有装配通知采集能力，采集与查询当前不可用。" +
                    "这里不会出现任何开关；装配完成后本页会呈现真实的授权与范围设置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 更新被权威拒绝时的显式反馈：绝不静默假成功，也绝不吞掉拒绝原因。 */
@Composable
private fun RejectionNotice(reason: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.ScreenHorizontal),
    ) {
        Text(
            text = "更新被拒绝：$reason",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
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
            headline = "允许通知采集",
            supporting = if (snapshot.granted) {
                "已同意本地采集策略；Agent 查询走同一授权位"
            } else {
                "未同意；Agent 查询将被本地策略拒绝（LOCAL_GRANT_REQUIRED）"
            },
            checked = snapshot.granted,
            onCheckedChange = onGrantChanged,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsListItem(
            headline = "系统通知使用权",
            supporting = if (listenerBound) {
                "已授予：系统监听服务已绑定"
            } else {
                "未授予：监听服务未绑定，采集不会收到任何通知"
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
            Text(text = "打开系统通知使用权设置")
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
            headline = "包含通知内容（标题与正文）",
            supporting = if (contentAccess) {
                "内容可读：标题与正文会进入采集记录"
            } else {
                "仅元数据：应用名、时间与渠道；标题与正文即刻丢弃"
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
            headline = "采集后自动发送到已配对网关",
            supporting = if (autoSend) {
                "自动发送：策略接受的通知进入加密 outbox 等待发送"
            } else {
                "仅按需：只有 Agent 查询时才读取当前可见通知"
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
    draftPackage: String,
    onDraftChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    SettingsSectionCard {
        Text(
            text = "应用白名单",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = draftPackage,
            onValueChange = onDraftChanged,
            label = { Text("应用包名") },
            placeholder = { Text("com.example.app") },
            singleLine = true,
            shape = RoundedCornerShape(AppRadius.Small),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimensions.SpaceSmall)
                .testTag("notification-package-input"),
        )
        Button(
            onClick = onAdd,
            shape = RoundedCornerShape(AppRadius.Small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "添加")
        }
        if (snapshot.packageIds.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        snapshot.packageIds.forEachIndexed { index, packageId ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            SettingsListItem(
                headline = packageId,
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
