package com.openandroidintelligence.mobile.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.openandroidintelligence.conversation.components.SettingsIconBadge
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.plugin.ui.UiActionId
import com.openandroidintelligence.plugin.ui.PluginUiActionValue
import com.openandroidintelligence.gateway.schema.Json
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.OperationNoticeBanner
import com.openandroidintelligence.plugin.pkg.PackageRejected
import com.openandroidintelligence.plugin.ui.DeclarativeUiSchema
import com.openandroidintelligence.plugin.ui.PluginDeclarativeUi
import com.openandroidintelligence.plugin.ui.PluginUiActionSink
import com.openandroidintelligence.plugin.ui.UiRejected

/** 安装入口条目的 testTag，供渠道策略的点击门控测试定位。 */
const val PLUGIN_INSTALL_ENTRY_TAG = "plugin-install-entry"

/** 插件运行环境未就绪这一事实的统一措辞（对用户不使用「内核」这类内部术语）。 */
const val KERNEL_ABSENT_NOTICE = "当前版本暂不支持插件运行，这里的设置无法操作"

/**
 * 设置内插件管理区域（规格 modular-plugin-architecture §4.1）。
 *
 * 诚实降级是本区域的底线：
 * - 宿主未装配插件运行时 → 明说「已安装插件不会运行，启用不可用」，不渲染
 *   一排点了没反应的启用开关；
 * - 无插件 → 「未安装任何插件」，不造假列表；
 * - 声明式设置用 [PluginDeclarativeUi] 渲染，动作经 [PluginUiActionSink]
 *   提交——内核未装配时动作无处授权，如实回显而非静默失败；
 * - 安装入口走真实 SAF 文件选择 → AlpVerifier 校验 → PluginInstaller 落盘，
 *   校验失败显示契约拒绝码；渠道策略禁止时入口禁用并说明。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginManagementScreen(
    allowRuntimePlugins: Boolean,
    pluginRuntimesWired: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    store: PluginInstallStore? = null,
    onOpenNotificationSettings: (() -> Unit)? = null,
    /** 测试注入的内置包来源；缺省时按插件各自读取 APK 资产。 */
    bundledPluginPackages: Map<BundledPlugin, () -> ByteArray> = emptyMap(),
) {
    val context = LocalContext.current
    // 未显式给 store 时按应用上下文自建（真实存储：filesDir/plugins）。
    val resolvedStore = store ?: remember { PluginInstallStore(context.applicationContext) }
    val app = context.applicationContext as? OpenAndroidIntelligenceApplication
    val host = app?.pluginHost?.takeIf { pluginRuntimesWired }
    val pluginRevision = host?.revision?.collectAsState()?.value
    val gatewayState = app?.gatewayRuntime?.deviceCapabilityState?.collectAsState()?.value ?: DeviceCapabilitySyncState()
    // 只用于隐藏旧的发布回执，不将连接或配对状态作为本机设置的门槛。
    val localAuthorizationRevision = app?.pairingGrants?.state?.collectAsState()?.value?.revision ?: -1L
    val currentGatewayState = gatewayState.forLocalState(localAuthorizationRevision, pluginRevision ?: -1L)
    var installed by remember { mutableStateOf(resolvedStore.listInstalled()) }
    var pendingApproval by remember { mutableStateOf<ByteArray?>(null) }
    var grantPlugin by remember { mutableStateOf<String?>(null) }
    var removeDamagedPlugin by remember { mutableStateOf<String?>(null) }
    var showNotificationSetup by remember { mutableStateOf(false) }
    var invokePlugin by remember { mutableStateOf<String?>(null) }
    var invokeCapability by remember { mutableStateOf("") }
    var invokeParameters by remember { mutableStateOf("{}") }
    var showRemote by remember { mutableStateOf(false) }
    var sourceUrl by remember { mutableStateOf("") }
    var sourcePin by remember { mutableStateOf("") }
    var sourceDigest by remember { mutableStateOf("") }
    var indexPluginId by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val installScope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }

    fun refresh() { host?.reload(); installed = resolvedStore.listInstalled() }
    fun installBytes(bytes: ByteArray, approved: Boolean = false, bundledPlugin: BundledPlugin? = null) {
        if (installing) return
        installing = true
        installScope.launch {
            try {
                val view = runInterruptible(Dispatchers.IO) {
                    if (bundledPlugin != null) resolvedStore.installBundledPlugin(bundledPlugin, bytes) else resolvedStore.install(bytes,approved)
                }
                refresh(); pendingApproval = null
                notice = "已安装 ${view.pluginId} v${view.version}；启用插件后，设备请求仍按各项能力的本机授权模式处理。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
                if ((cause as? PackageRejected)?.message?.startsWith("APPROVAL_REQUIRED:") == true) {
                    pendingApproval = bytes.copyOf(); notice = "更新扩大了权限或资源范围，需要确认。"
                } else notice = installFailureNotice(cause)
            } finally { installing = false }
        }
    }
    val pickPackage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installScope.launch {
            try {
                val bytes = runInterruptible(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(::readPluginPackageBytes) ?: error("SOURCE_UNAVAILABLE")
                }
                installBytes(bytes)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = installFailureNotice(cause) }
        }
    }
    fun showPermissionStatus(id: String) {
        if (host?.entries(id).orEmpty().any { "kernel.notifications.read" in it.primitives }) {
            showNotificationSetup = true
        } else {
            notice = "Android 系统权限已就绪；每项能力的本机授权模式保持原设置。"
        }
    }

    // ===== Android 系统授权：按插件实际声明的 kernel primitive 动态推导 =====
    //
    // 修复前这里只处理 READ_SMS / READ_CALL_LOG，无障碍、通知读取、屏幕截图
    // 三类能力在「检查 Android 系统权限」里没有任何入口。现在全部由声明推导，
    // 不再硬编码特定 Agent 或 Gateway。
    //
    // 每次从系统设置返回都会递增 refreshTick，令下面所有查询重新取真实状态。
    var permissionRefreshTick by remember { mutableStateOf(0) }
    fun notificationAccessGranted(): Boolean = runCatching {
        com.openandroidintelligence.notification.control.NotificationControlRegistry.bindingPort().isListenerBound()
    }.getOrDefault(false)
    fun accessibilityServiceEnabled(): Boolean = runCatching {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val own = android.content.ComponentName(
            context, com.openandroidintelligence.screen.ScreenAccessibilityService::class.java,
        )
        enabled.split(':').any { it.equals(own.flattenToString(), true) || it.equals(own.flattenToShortString(), true) }
    }.getOrDefault(false)
    fun screenCaptureAuthorized(): Boolean = runCatching {
        com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.source?.isAvailable == true
    }.getOrDefault(false)
    val grantInspector = remember(permissionRefreshTick, pluginRevision) {
        DeviceCapabilityGrantInspector(
            context = context,
            notificationAccessGranted = ::notificationAccessGranted,
            accessibilityEnabled = ::accessibilityServiceEnabled,
            captureAuthorized = ::screenCaptureAuthorized,
        )
    }
    fun requestCapabilityResync() {
        runCatching { app?.gatewayRuntime?.requestCapabilitySync() }
    }
    var permissionDialogPlugin by remember { mutableStateOf<String?>(null) }
    var pendingCapturePlugin by remember { mutableStateOf<String?>(null) }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val id = permissionDialogPlugin; permissionDialogPlugin = null
        // 逐项判断而不是要求"全部授予"：部分授予时也要把已授予的部分如实计入。
        if (id != null && result.values.any { it }) showPermissionStatus(id)
        else notice = "系统权限未授予；本机能力开关保持原状态。"
        permissionRefreshTick++
        requestCapabilityResync()
    }
    // MediaProjection 必须由前台 Activity 发起系统对话框，跳设置页是无关操作。
    val captureGrantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val source = com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.source
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            // Android 14+ 要求 mediaProjection 前台服务先完成 startForeground，
            // 再转交授权结果，否则确定性抛出 SecurityException。
            com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.startForeground(context) {
                val granted = source?.onAuthorizationResult(result.resultCode, result.data) == true
                if (!granted) {
                    com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.stopForeground(context)
                }
                notice = if (granted) {
                    "已获得屏幕截图系统授权。"
                } else {
                    "屏幕截图会话建立失败，可改用无障碍截图或稍后重试。"
                }
                permissionRefreshTick++
                requestCapabilityResync()
            }
        } else {
            source?.release()
            com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.stopForeground(context)
            notice = "未获得屏幕截图授权；截图能力暂不可用，其余能力不受影响。"
            permissionRefreshTick++
        }
        pendingCapturePlugin = null
    }
    fun openAccessibilityGrant(): Boolean = runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    fun openNotificationGrant(): Boolean = openNotificationAccessSettings(context)
    // 从系统设置返回后重新检测，并触发 Core 同步让远端看到最新能力状态。
    val grantLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(grantLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionRefreshTick++
                requestCapabilityResync()
            }
        }
        grantLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { grantLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun sink(id: String) = PluginUiActionSink { component, action, value ->
        if (host == null) notice = "已提交 $action（$component）——$KERNEL_ABSENT_NOTICE"
        else when (action) {
            UiActionId.REQUEST_GRANT, UiActionId.SELECT_PROVIDER -> grantPlugin = id
            UiActionId.INVOKE_CAPABILITY, UiActionId.REFRESH_CARD -> {
                invokePlugin = id; invokeCapability = host.entries(id).firstOrNull()?.key.orEmpty(); invokeParameters = "{}"
            }
            UiActionId.SET_SETTING -> runCatching {
                host.setting(id,app.gatewayRuntime.connectedAccountId ?: error("PAIRING_REQUIRED"),component,when(value) {
                    is PluginUiActionValue.ToggleValue -> value.checked
                    is PluginUiActionValue.ChoiceValue -> value.optionId
                    else -> error("UI_ACTION_INVALID")
                })
            }.fold(onSuccess = { notice = "设置已保存到当前账号。" },onFailure = { notice = "保存失败：请先登录 Gateway。" })
        }
    }
    pendingApproval?.let { candidate ->
        AlertDialog(onDismissRequest = { pendingApproval = null },title = { Text("确认插件更新") },
            text = { Text("该更新扩大了内核原语、联网、后台或资源范围。批准后，本机能力开关、系统权限和后台设置仍分别生效；作者和签名将再次核验。") },
            confirmButton = { TextButton(onClick = { installBytes(candidate,true) },enabled = !installing) { Text("批准更新") } },
            dismissButton = { TextButton(onClick = { pendingApproval = null }) { Text("取消") } })
    }
    removeDamagedPlugin?.let { directoryName ->
        AlertDialog(
            onDismissRequest = { removeDamagedPlugin = null },
            title = { Text("卸载损坏包") },
            text = { Text("将删除插件目录 $directoryName 的损坏包及其本机设置。重新安装后需要重新启用插件，并重新检查系统权限与通知读取范围。") },
            confirmButton = {
                TextButton(onClick = {
                    removeDamagedPlugin = null
                    runCatching {
                        require(isSafePluginDirectoryName(directoryName)) { "INVALID_PLUGIN_DIRECTORY" }
                        if (app != null) app.pluginHost.uninstall(directoryName) else resolvedStore.uninstall(directoryName)
                        refresh()
                    }.fold(onSuccess = { notice = "损坏插件包已卸载，请重新安装并启用。" },
                        onFailure = { notice = "损坏插件包未能卸载，请检查本机存储。" })
                }, modifier = Modifier.testTag("damaged-plugin-delete-confirm")) { Text("确认卸载") }
            },
            dismissButton = { TextButton(onClick = { removeDamagedPlugin = null }) { Text("取消") } },
        )
    }
    if (showNotificationSetup) {
        AlertDialog(
            onDismissRequest = { showNotificationSetup = false },
            title = { Text("完成通知读取设置") },
            text = { Text("还需要在「通知读取范围」里允许读取、选择要读取的应用与内容，并在 Android 系统设置中开启通知使用权。") },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationSetup = false
                    if (onOpenNotificationSettings != null) onOpenNotificationSettings()
                    else if (!openNotificationAccessSettings(context)) notice = "无法打开系统通知使用权设置，请到 Android 系统设置里手动开启本应用的通知使用权。"
                }) { Text(if (onOpenNotificationSettings != null) "配置通知读取范围" else "打开通知使用权设置") }
            },
            dismissButton = { TextButton(onClick = { showNotificationSetup = false }) { Text("稍后设置") } },
        )
    }
    grantPlugin?.let { id ->
        val entries = host?.entries(id).orEmpty()
        AlertDialog(onDismissRequest = { grantPlugin = null },title = { Text("权限与能力设置") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                ) {
                    Text("系统权限、后台任务与每项能力的授权模式分别管理。短信和通话记录读取权限由 Android 单独确认，检查系统权限不会修改能力授权。")
                    val activeHost = host
                    if (activeHost != null && activeHost.supportsBackground(id)) {
                        val backgroundEnabled = activeHost.isBackgroundEnabled(id)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("允许后台任务", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = when {
                                        backgroundEnabled -> "已在本机明确允许后台任务"
                                        else -> "普通查询无需此许可；定时/后台操作仍受系统调度和当前本地授权约束"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = backgroundEnabled,
                                onCheckedChange = { enabled ->
                                    runCatching { activeHost.setBackgroundEnabled(id, enabled) }
                                        .fold(
                                            onSuccess = { notice = if (enabled) "已明确允许此插件执行后台任务。" else "已在本机关闭此插件的后台任务许可。" },
                                            onFailure = { notice = "后台任务许可未能保存，请稍后重试。" },
                                        )
                                },
                                modifier = Modifier.testTag("plugin-background-toggle:$id"),
                            )
                        }
                    }
                    entries.forEach { entry ->
                        Text("${entry.key}（风险：${entry.risk}）\n需要：${entry.primitives.joinToString()}")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Android 系统授权", style = MaterialTheme.typography.titleSmall)
                    val requirements = remember(permissionRefreshTick, entries) {
                        androidPermissionRequirements(entries.flatMap { it.primitives }.toSet())
                    }
                    if (requirements.isEmpty()) {
                        Text(
                            text = "这个插件不需要额外的 Android 系统授权。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    requirements.forEach { requirement ->
                        val state = grantInspector.state(requirement)
                        DeviceGrantRow(
                            requirement = requirement,
                            state = state,
                            onAction = {
                                when {
                                    // 已授权：不再重复申请，只给出状态，避免无意义弹窗。
                                    state == DeviceCapabilityGrantState.GRANTED -> {
                                        notice = "${requirement.label}已授权，无需再次申请。"
                                    }
                                    // 运行时权限：走系统权限申请对话框。
                                    requirement.grantKind == DeviceCapabilityGrantKind.RUNTIME_PERMISSION -> {
                                        val permission = requirement.manifestPermission
                                        if (permission == null) {
                                            notice = "${requirement.label}没有可申请的系统权限。"
                                        } else {
                                            permissionDialogPlugin = id
                                            requestPermissions.launch(arrayOf(permission))
                                        }
                                    }
                                    // 无障碍：跳系统无障碍设置，由用户亲手开启。
                                    requirement.opensAccessibilitySettings() -> {
                                        if (!openAccessibilityGrant()) {
                                            notice = manualGrantGuidance(requirement)
                                        }
                                    }
                                    // 通知使用权：跳系统通知使用权设置。
                                    requirement.opensNotificationAccessSettings() -> {
                                        if (!openNotificationGrant()) {
                                            notice = manualGrantGuidance(requirement)
                                        }
                                    }
                                    // 投屏：前台 Activity 发起系统授权对话框，不跳无关设置页。
                                    requirement.opensMediaProjectionDialog() -> {
                                        val intent = runCatching {
                                            com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
                                                .source?.createAuthorizationIntent()
                                        }.getOrNull()
                                        if (intent == null) {
                                            notice = manualGrantGuidance(requirement)
                                        } else {
                                            pendingCapturePlugin = id
                                            captureGrantLauncher.launch(intent)
                                        }
                                    }
                                    else -> notice = manualGrantGuidance(requirement)
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { grantPlugin = null }) { Text("完成") } },
            dismissButton = { TextButton(onClick = { grantPlugin = null }) { Text("关闭") } })
    }
    invokePlugin?.let { id ->
        AlertDialog(onDismissRequest = { invokePlugin = null },title = { Text("调用插件能力") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(host?.entries(id).orEmpty().joinToString("\n") { it.key }); OutlinedTextField(invokeCapability,{invokeCapability=it},label={Text("能力 ID@版本")}); OutlinedTextField(invokeParameters,{invokeParameters=it},label={Text("JSON 参数")}) } },
            confirmButton = { TextButton(onClick = {
                val selected = invokeCapability; val parameters = invokeParameters; invokePlugin = null
                installScope.launch {
                    try {
                        val entry = host?.entries(id)?.firstOrNull { it.key == selected } ?: error("CAPABILITY_NOT_DECLARED")
                        val accountId = app?.gatewayRuntime?.connectedAccountId ?: error("PAIRING_REQUIRED")
                        val result = runInterruptible(Dispatchers.IO) { host.invoke(entry.identity,accountId,selected,Json.parse(parameters),"ui_${java.util.UUID.randomUUID()}") }
                        notice = result.decodeToString().take(4096)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "调用被拒绝或失败，请检查设备连接、本机能力开关、系统权限和参数。" }
                }
            }) { Text("执行") } },dismissButton = { TextButton(onClick = { invokePlugin = null }) { Text("取消") } })
    }
    if (showRemote) {
        // 四个输入框的对话框在大字号/横屏下会被挤出屏幕底部，改为可滚动的 M3 底部弹层。
        ModalBottomSheet(
            onDismissRequest = { showRemote = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(
                        start = Dimensions.ScreenHorizontal,
                        end = Dimensions.ScreenHorizontal,
                        bottom = Dimensions.SpaceLarge,
                    ),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            ) {
                Text("从网络地址安装", style = MaterialTheme.typography.titleLarge)
                Text(
                    "需要提供服务器证书的公钥指纹（SPKI，不是证书本身指纹）或文件摘要；下载后仍会校验插件作者签名与内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(sourceUrl,{sourceUrl=it},label={Text("插件包或索引的 HTTPS 地址")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(sourcePin,{sourcePin=it},label={Text("证书公钥指纹 SPKI（sha256:…）")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(sourceDigest,{sourceDigest=it},label={Text("文件摘要 SHA-256（可代替公钥指纹）")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(indexPluginId,{indexPluginId=it},label={Text("清单中的插件 ID（直接下载插件包时留空）")},modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    TextButton(onClick = { showRemote = false }) { Text("取消") }
                    Button(onClick = {
                        showRemote = false
                        installScope.launch { try { val bytes = runInterruptible(Dispatchers.IO) { RemotePluginSource.resolve(sourceUrl,sourcePin,sourceDigest,indexPluginId) }; installBytes(bytes) }
                        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { notice = "下载失败，请检查地址、公钥指纹（SPKI）或文件摘要。" } }
                    }) { Text("下载并校验") }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) { padding ->
        // 宽屏限宽居中：分组卡片不再被拉成超长的一行。
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
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
            notice?.let { text ->
                OperationNoticeBanner(text = text, onDismiss = { notice = null })
            }

            // 运行时事实：能否执行、能否启用，只由内核装配状态决定。
            SectionLabel("插件运行环境")
            SettingsSectionCard {
                SettingsListItem(
                    headline = if (pluginRuntimesWired) "插件运行环境已就绪" else "当前版本暂不支持运行插件",
                    supporting = if (pluginRuntimesWired) {
                        "已安装并启用的插件，只有在本机能力开关、应用声明和系统权限均满足时才会执行"
                    } else {
                        "已安装插件不会运行，「启用」不可用；安装仍会校验并保管包内容"
                    },
                    icon = Icons.Default.Extension,
                    tone = if (pluginRuntimesWired) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                )
                if (host != null) {
                    TextButton(
                        onClick = {
                            runCatching { host.revokeTemporaryApprovals() }
                                .fold(
                                    onSuccess = { notice = "已撤销本机全部临时授权；各项能力的长期设置保持原样。" },
                                    onFailure = { notice = "临时授权未能撤销，请稍后重试。" },
                                )
                        },
                        modifier = Modifier.testTag("revoke-temporary-approvals"),
                    ) { Text("撤销临时授权") }
                }
                if (host?.loadFailures?.containsKey("*") == true) {
                    SettingsListItem(headline = "无法读取插件列表", supporting = "无法确认已安装的插件，请检查手机存储后重试。", tone = SettingsTone.DANGER)
                }
            }

            // 内置插件安装入口：每个插件一条，独立判断安装状态；
            // 已安装或渠道禁止时如实说明，不渲染可点但无效的条目。
            SectionLabel("内置插件")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    BundledPlugin.entries.forEach { plugin ->
                        val alreadyInstalled = installed.any { it.pluginId == plugin.pluginId || it.directory.name == plugin.pluginId }
                        SettingsListItem(
                            headline = if (alreadyInstalled) "${plugin.displayName}插件已安装" else "安装${plugin.displayName}插件",
                            supporting = when {
                                !allowRuntimePlugins -> "当前版本不支持内置插件，请使用支持插件的完整版本"
                            alreadyInstalled && host?.loadFailures?.containsKey(plugin.pluginId) == true -> "现有的包已损坏，请先卸载它，再重新安装"
                            alreadyInstalled -> when (plugin) {
                                BundledPlugin.NOTIFICATIONS -> "请在下方启用插件，并检查通知读取范围与系统通知使用权"
                                BundledPlugin.SMS, BundledPlugin.CALL_LOG -> "请在下方启用插件；短信或通话记录还需要对应的系统权限"
                                BundledPlugin.SCREEN_READER, BundledPlugin.SCREEN_CONTROL ->
                                    "请在下方启用插件，并检查 Android 无障碍服务；请求仍按本机授权模式处理"
                                BundledPlugin.SCREEN_CAPTURE ->
                                    "请在下方启用插件；截图还需 Android 屏幕捕获同意，请求仍按本机授权模式处理"
                            }
                            else -> "安装应用自带的插件，安装后可在下方启用并设置本机能力开关"
                            },
                            icon = Icons.Default.InstallMobile,
                            enabled = allowRuntimePlugins && !alreadyInstalled && !installing,
                            onClick = if (allowRuntimePlugins && !alreadyInstalled && !installing) ({
                                installScope.launch {
                                    try {
                                        val bytes = runInterruptible(Dispatchers.IO) {
                                            bundledPluginPackages[plugin]?.invoke() ?: readBundledPluginPackage(context, plugin)
                                        }
                                        installBytes(bytes, bundledPlugin = plugin)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (cause: Exception) { notice = installFailureNotice(cause) }
                                }
                            }) else null,
                            modifier = Modifier.testTag(plugin.installTag),
                        )
                    }
                }
            }

            // 已安装列表：真实存储扫描。
            SectionLabel("已安装插件")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    if (installed.isEmpty()) {
                        Text(
                            text = "未安装任何插件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Dimensions.SpaceSmall),
                        )
                    }
                    installed.forEachIndexed { index, view ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        InstalledPluginBlock(view = view, onAction = sink(view.pluginId ?: view.directory.name),host = host)
                        if (pluginLoadFailure(view,host) != null) {
                            TextButton(
                                onClick = { removeDamagedPlugin = view.directory.name },
                                enabled = isSafePluginDirectoryName(view.directory.name),
                                modifier = Modifier.testTag("damaged-plugin-delete:${view.directory.name}"),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text("卸载损坏包") }
                        } else view.pluginId?.let { id ->
                            if (host != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                                    // 能力状态：图标 + 语义色 + 文字三件套，颜色不是唯一信号。
                                    val entries = remember(host, id, pluginRevision) { host.entries(id) }
                                    entries.forEach { entry ->
                                        CapabilityAuthorizationControls(
                                            pluginId = id,
                                            capability = entry.key,
                                            readOnly = entry.risk == "read" || entry.key.startsWith("org.openandroidintelligence.screen-capture.capture@"),
                                            policy = host.capabilityPolicy(id, entry.key),
                                            onPolicyChange = { policy ->
                                                runCatching { host.setCapabilityPolicy(id, entry.key, policy) }
                                                    .fold(
                                                        onSuccess = { notice = "已保存本机授权设置，切换 Core 或账号后仍然有效。" },
                                                        onFailure = { notice = "本机授权设置未能保存，请稍后重试。" },
                                                    )
                                            },
                                        )
                                        val diagnostic = currentGatewayState.diagnostic(DeviceCapabilityIdentity(id, entry.id, entry.version))
                                        CapabilityStatusRow(entryId = entry.id, status = diagnostic.status, reason = diagnostic.reason)
                                        if (diagnostic.status == "PROVIDER_NOT_SELECTED" && host.capabilityPolicy(id, entry.key).enabled) {
                                            TextButton(onClick = {
                                                runCatching { host.setCapabilityPolicy(id, entry.key, host.capabilityPolicy(id, entry.key)) }
                                                    .fold(
                                                        onSuccess = { notice = "已选择此插件提供这项能力，授权模式保持原设置。" },
                                                        onFailure = { notice = "未能选择提供者，请检查插件状态后重试。" },
                                                    )
                                            }) { Text("使用此插件提供这项能力") }
                                        }
                                    }
                                    // 常规动作：FlowRow 自动换行，窄屏不会再被挤成一条溢出屏幕的长行。
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                                        TextButton(onClick = { runCatching { host.enable(id,!host.isEnabled(id)) }.fold(onSuccess={notice="插件状态已更新。"},onFailure={notice="启用失败：请检查开发者信任模式、插件兼容性或安全限制。"}) }) { Text(if(host.isEnabled(id)) "停用" else "启用") }
                                        TextButton(onClick = { grantPlugin = id }) { Text("系统权限与后台任务") }
                                        if (host.hasNativeUi(id)) TextButton(onClick = { runCatching { host.selectNativeUi(id) }.fold(onSuccess={notice="已切换为该插件自带的界面，随时可以切回来。"},onFailure={notice="需要先开启开发者信任模式并启用插件。"}) }) { Text("使用插件界面") }
                                        if (host.isQuarantined(id)) TextButton(onClick = { host.recoverNative(id); refresh() }) { Text("解除安全限制（保持停用）") }
                                        if (host.entries(id).any { "kernel.notifications.read" in it.primitives }) {
                                            onOpenNotificationSettings?.let { openSettings ->
                                                TextButton(onClick = openSettings) { Text("通知读取范围") }
                                            }
                                            TextButton(onClick = {
                                                if (!openNotificationAccessSettings(context)) notice = "无法打开系统通知使用权设置，请到 Android 系统设置里手动开启本应用的通知使用权。"
                                            }) { Text("打开通知访问设置") }
                                        }
                                    }
                                    // 卸载与日常可逆设置分开呈现。
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                                        val dangerColors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        )
                                        TextButton(
                                            onClick = { runCatching { host.uninstall(id); refresh() }.fold(onSuccess={notice="已卸载并清理插件数据。"},onFailure={notice="卸载未完成，请检查存储。"}) },
                                            colors = dangerColors,
                                        ) { Text("卸载") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 安装入口：渠道策略真实控制可用性。
            SectionLabel("安装新插件")
            SettingsSectionCard {
                if (allowRuntimePlugins) {
                    SettingsListItem(
                        headline = "从文件安装插件",
                        supporting = "选择一个插件包文件，应用会先校验它的签名与内容再安装",
                        icon = Icons.Default.InstallMobile,
                        onClick = { pickPackage.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag(PLUGIN_INSTALL_ENTRY_TAG),
                    )
                    SettingsListItem(headline="从网络地址安装", supporting="需要提供服务器证书公钥指纹或文件摘要，安装前仍会校验插件作者签名", onClick={showRemote=true})
                } else {
                    SettingsListItem(
                        headline = "从文件安装插件",
                        supporting = "当前版本不允许安装插件",
                        icon = Icons.Default.InstallMobile,
                        enabled = false,
                        modifier = Modifier.testTag(PLUGIN_INSTALL_ENTRY_TAG),
                    )
                }
            }
        }
        }
    }
}

internal fun capabilityStatusLabel(status: String, reason: String? = null): String {
    val label = when(status) {
    "READY" -> "服务端报告已就绪；本机权限与数据范围仍单独检查"
    "GATEWAY_DISABLED" -> "服务端仍使用旧账号能力策略，请升级 OAI Core"
    "AUTHORIZATION_MODEL_UNSUPPORTED" -> "服务端尚不支持 Android 统一授权，请升级 OAI Core；当前设备能力不可用"
    "PLUGIN_NOT_INSTALLED" -> "Android 未安装对应插件"
    "PLUGIN_DISABLED" -> "Android 插件未启用"
    "LOCAL_GRANT_REQUIRED" -> "本机能力尚未开启或需要重新同步"
    "ANDROID_PERMISSION_REQUIRED" -> "需要 Android 系统权限"
    "PROVIDER_UNAVAILABLE" -> "本机采集器或监听服务未就绪"
    "PROVIDER_NOT_SELECTED" -> "尚未选择此能力提供者"
    "SYNCING" -> "正在同步本机能力设置，尚未确认服务端状态"
    "SYNC_RETRYING" -> "正在重试同步，尚未确认当前能力状态"
    "SYNC_FAILED" -> "设备能力同步失败，正在重试"
    "ANDROID_STATE_UNKNOWN" -> "Android 版本未提供详细授权状态"
    "STATUS_UNAVAILABLE" -> "Gateway 未提供能力状态；请检查连接与 OAI Core 版本"
    else -> "等待同步当前设备能力状态"
    }
    val detail = capabilityBlockDetail(status, reason)
    return if (detail == null) label else "$label；$detail"
}

/**
 * 追加「现在的具体处境 + 下一步该做什么」。
 *
 * 问题三要求区分未申请、等待用户操作、服务未连接、系统拒绝与真正不可用。
 * 这里只对有实际阻塞的状态追加说明，READY 与同步中不啰嗦。
 */
internal fun capabilityBlockDetail(status: String, reason: String?): String? {
    if (status == "READY" || status == "SYNCING" || status == "SYNC_RETRYING") return null
    val block = classifyCapabilityBlock(status, reason)
    val cause = when (block) {
        DeviceCapabilityBlock.NOT_YET_REQUESTED -> "尚未申请授权"
        DeviceCapabilityBlock.AWAITING_USER_ACTION -> "等待你在系统里操作"
        DeviceCapabilityBlock.SERVICE_DISCONNECTED -> "服务未连接"
        DeviceCapabilityBlock.SYSTEM_REJECTED -> "系统拒绝了授权"
        DeviceCapabilityBlock.RATE_LIMITED -> "系统暂时限速"
        DeviceCapabilityBlock.LOCAL_SWITCH_OFF -> "本机能力开关已关闭"
        DeviceCapabilityBlock.PLUGIN_INACTIVE -> "插件未安装或未启用"
        DeviceCapabilityBlock.PROVIDER_UNSELECTED -> "尚未选择提供者"
        DeviceCapabilityBlock.SYNCING -> "等待同步"
        DeviceCapabilityBlock.UNAVAILABLE -> "当前不可用"
    }
    val next = capabilityBlockNextStep(block)
    val recoverable = if (block.isRecoverable) "" else "（这一项不能在本机恢复）"
    return "$cause$recoverable，$next"
}

/** 一个已安装插件的真实状态与其声明式设置贡献。 */
@Composable
private fun InstalledPluginBlock(
    view: InstalledPluginView,
    onAction: PluginUiActionSink,
    host: ProductionPluginHost? = null,
) {
    val failure = pluginLoadFailure(view,host)
    val headline = if (failure != null) view.directory.name else view.displayName ?: view.directory.name
    val supporting = if (view.pluginId == null) {
        "这个插件的配置文件无法读取，已按未知插件列出"
    } else {
        buildString {
            append(view.pluginId)
            view.version?.let { append(" · v$it") }
            view.runtimeType?.let { append(" · $it") }
            append(when {
                host == null -> " · 已安装，未启用（当前版本暂不支持运行插件）"
                host.loadFailures.containsKey(view.directory.name) -> " · 载入失败"
                host.isEnabled(view.pluginId) -> " · 已启用"
                else -> " · 已停用"
            })
        }
    }
    SettingsListItem(headline = headline, supporting = supporting)

    if (failure != null) {
        SettingsListItem(headline = "这个插件无法载入", supporting = "请先卸载它，再重新安装并检查本机能力开关和所需系统权限。", tone = SettingsTone.DANGER)
        return
    }

    if (host != null) {
        host.contributions(view.pluginId ?: "").forEach { contribution -> PluginDeclarativeUi(contribution = contribution,onAction = onAction) }
        return
    }
    view.declarationFiles.forEach { file ->
        val parsed = remember(file.absolutePath) {
            runCatching { DeclarativeUiSchema.parse(file.readText()) }
        }
        parsed.fold(
            onSuccess = { contribution ->
                Column {
                    Text(
                        text = KERNEL_ABSENT_NOTICE,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimensions.SpaceTiny),
                    )
                    PluginDeclarativeUi(contribution = contribution, onAction = onAction)
                }
            },
            onFailure = { cause ->
                val code = (cause as? UiRejected)?.message ?: cause::class.java.simpleName
                SettingsListItem(
                    headline = "插件设置无法显示",
                    supporting = "这个插件提供的设置项无法被安全显示：$code",
                    tone = SettingsTone.DANGER,
                )
            },
        )
    }
}

private fun pluginLoadFailure(view: InstalledPluginView, host: ProductionPluginHost?): String? =
    host?.loadFailures?.get(view.directory.name) ?: if (view.pluginId == null || view.pluginId != view.directory.name)
        "PLUGIN_MANIFEST_INVALID" else null

private fun isSafePluginDirectoryName(value: String): Boolean = Regex("[A-Za-z0-9][A-Za-z0-9.-]*").matches(value)

/**
 * 能力状态行：图标 + 语义色 + 文字。
 *
 * 状态只靠颜色是读不出来的：每种状态都同时有图标和文字说明，颜色取自
 * [LocalAppStateColors] 的 success / warning 与 M3 的 error，不再借用品牌 primary。
 */
@Composable
private fun CapabilityStatusRow(entryId: String, status: String, reason: String? = null) {
    val stateColors = LocalAppStateColors.current
    val icon: ImageVector
    val tone: SettingsTone
    val textColor: Color
    when (status) {
        "READY" -> {
            icon = Icons.Default.CheckCircle
            tone = SettingsTone.PRIMARY
            textColor = stateColors.success
        }
        "SYNC_FAILED", "STATUS_UNAVAILABLE" -> {
            icon = Icons.Default.SyncProblem
            tone = SettingsTone.DANGER
            textColor = MaterialTheme.colorScheme.error
        }
        "GATEWAY_DISABLED", "AUTHORIZATION_MODEL_UNSUPPORTED", "PLUGIN_NOT_INSTALLED", "PLUGIN_DISABLED", "LOCAL_GRANT_REQUIRED",
        "ANDROID_PERMISSION_REQUIRED", "PROVIDER_UNAVAILABLE", "PROVIDER_NOT_SELECTED" -> {
            icon = Icons.Default.Warning
            tone = SettingsTone.NEUTRAL
            textColor = stateColors.warning
        }
        else -> {
            icon = Icons.Default.HourglassEmpty
            tone = SettingsTone.NEUTRAL
            textColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconBadge(icon = icon, tone = tone)
        Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
        Column(modifier = Modifier.weight(1f)) {
            // 能力 ID 是技术元数据，用等宽样式；状态说明走正文辅助样式。
            Text(
                text = entryId,
                style = AppTextStyles.MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = capabilityStatusLabel(status, reason),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
        }
    }
}

/** 安装失败的如实说明：契约拒绝码原样给出，不回显原始异常文本。 */
internal fun installFailureNotice(cause: Throwable): String = when (cause) {
    is BundledPluginUnavailable -> "当前安装包未附带${cause.plugin.displayName}插件，请从文件导入已签名的 .alp 插件包。"
    is PackageRejected -> "插件包校验未通过：${cause.message ?: "未知拒绝码"}"
    is PluginInstallRefused -> "安装被拒绝：${cause.message ?: cause::class.java.simpleName}"
    else -> "安装失败：无法读取或处理所选插件包。"
}

/** 分组标题，与设置主页面同款式。 */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(
            start = Dimensions.SpaceMedium,
            top = Dimensions.SpaceSmall,
            bottom = Dimensions.SpaceTiny,
        ),
    )
}

/**
 * 一条 Android 系统授权需求：名称 + 状态 + 可执行动作。
 *
 * 已授权时按钮禁用并显示「已授权」，避免用户反复点击触发无意义的系统申请
 * ——这是问题二明确要求的「已授权时正确显示状态」。
 *
 * 每行都带稳定 testTag，供 UI 测试定位不同类型的授权入口。
 */
@Composable
private fun DeviceGrantRow(
    requirement: AndroidPermissionRequirement,
    state: DeviceCapabilityGrantState,
    onAction: () -> Unit,
) {
    val granted = state == DeviceCapabilityGrantState.GRANTED
    val statusText = when (state) {
        DeviceCapabilityGrantState.GRANTED -> "已授权"
        DeviceCapabilityGrantState.NOT_REQUESTED -> "未授权 · 可以现在申请"
        DeviceCapabilityGrantState.DENIED -> "上次被拒绝 · 需要去系统设置开启"
        DeviceCapabilityGrantState.REQUIRES_SETTINGS -> "需要在系统设置中开启"
        DeviceCapabilityGrantState.ASKED_ON_USE -> "使用时由系统询问 · 无需现在授权"
    }
    val actionText = when (state) {
        DeviceCapabilityGrantState.GRANTED -> "已授权"
        DeviceCapabilityGrantState.DENIED -> "前往设置"
        // 投屏没有可持久状态：正确动作是发起系统对话框，而不是跳设置页。
        DeviceCapabilityGrantState.ASKED_ON_USE -> "发起系统授权"
        DeviceCapabilityGrantState.REQUIRES_SETTINGS -> when {
            requirement.opensAccessibilitySettings() -> "打开无障碍设置"
            requirement.opensNotificationAccessSettings() -> "打开通知使用权设置"
            else -> "前往设置"
        }
        DeviceCapabilityGrantState.NOT_REQUESTED -> "申请权限"
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(requirement.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = requirement.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(
            onClick = onAction,
            enabled = !granted,
            modifier = Modifier.testTag("plugin-grant:${requirement.key}"),
        ) { Text(actionText) }
    }
}
