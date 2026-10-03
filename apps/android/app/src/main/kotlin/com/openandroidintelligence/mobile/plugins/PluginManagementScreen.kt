package com.openandroidintelligence.mobile.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
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

/** 内核未装配插件运行时这一事实的统一措辞。 */
const val KERNEL_ABSENT_NOTICE = "内核未装配，设置项不可交互"

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagementScreen(
    allowRuntimePlugins: Boolean,
    pluginRuntimesWired: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    store: PluginInstallStore? = null,
) {
    val context = LocalContext.current
    // 未显式给 store 时按应用上下文自建（真实存储：filesDir/plugins）。
    val resolvedStore = store ?: remember { PluginInstallStore(context.applicationContext) }
    val app = context.applicationContext as? OpenAndroidIntelligenceApplication
    val host = app?.pluginHost?.takeIf { pluginRuntimesWired }
    val pluginRevision = host?.revision?.collectAsState()?.value
    var installed by remember { mutableStateOf(resolvedStore.listInstalled()) }
    var pendingApproval by remember { mutableStateOf<ByteArray?>(null) }
    var grantPlugin by remember { mutableStateOf<String?>(null) }
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
    fun installBytes(bytes: ByteArray, approved: Boolean = false) {
        if (installing) return
        installing = true
        installScope.launch {
            try {
                val view = runInterruptible(Dispatchers.IO) { resolvedStore.install(bytes,approved) }
                refresh(); pendingApproval = null
                notice = "已安装 ${view.pluginId} v${view.version}，可启用并为当前配对授权。"
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
                val bytes = runInterruptible(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use {
                    val value = it.readNBytes(32*1024*1024+1); require(value.size <= 32*1024*1024); value
                } ?: error("SOURCE_UNAVAILABLE") }
                installBytes(bytes)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = installFailureNotice(cause) }
        }
    }
    fun grantNow(id: String) { runCatching { host?.grant(id,true) ?: error("RUNTIME_UNAVAILABLE") }.fold(
        onSuccess = { notice = "已为当前配对授权并选择此提供者。" },onFailure = { notice = "授权失败，请先登录 Gateway 并检查插件状态。" }) }
    var permissionPlugin by remember { mutableStateOf<String?>(null) }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val id = permissionPlugin; permissionPlugin = null
        if (id != null && result.values.all { it }) grantNow(id) else notice = "系统权限未授予，设备能力未授权。"
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
            }.fold(onSuccess = { notice = "设置已保存到当前账号。" },onFailure = { notice = "设置保存失败，请先登录 Gateway。" })
        }
    }
    pendingApproval?.let { candidate ->
        AlertDialog(onDismissRequest = { pendingApproval = null },title = { Text("确认插件更新") },
            text = { Text("该更新扩大了内核原语、联网、后台或资源范围。批准后仍需在配对中授予新增能力；作者和签名将再次核验。") },
            confirmButton = { TextButton(onClick = { installBytes(candidate,true) },enabled = !installing) { Text("批准更新") } },
            dismissButton = { TextButton(onClick = { pendingApproval = null }) { Text("取消") } })
    }
    grantPlugin?.let { id ->
        val entries = host?.entries(id).orEmpty()
        AlertDialog(onDismissRequest = { grantPlugin = null },title = { Text("授权当前 Gateway 配对") },
            text = { Text("$id\n" + entries.joinToString("\n") { "${it.key}：${it.risk}\n${it.primitives.joinToString()}" }) },
            confirmButton = { TextButton(onClick = {
                grantPlugin = null
                val primitives = entries.flatMap { it.primitives }.toSet()
                val permissions = buildList {
                    if ("kernel.sms.read" in primitives) add(android.Manifest.permission.READ_SMS)
                    if ("kernel.call-log.read" in primitives) add(android.Manifest.permission.READ_CALL_LOG)
                }.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (permissions.isEmpty()) grantNow(id) else { permissionPlugin = id; requestPermissions.launch(permissions.toTypedArray()) }
            }) { Text("授予所列能力") } },dismissButton = { TextButton(onClick = { grantPlugin = null }) { Text("取消") } })
    }
    invokePlugin?.let { id ->
        AlertDialog(onDismissRequest = { invokePlugin = null },title = { Text("调用插件能力") },
            text = { Column { Text(host?.entries(id).orEmpty().joinToString("\n") { it.key }); OutlinedTextField(invokeCapability,{invokeCapability=it},label={Text("能力 ID@版本")}); OutlinedTextField(invokeParameters,{invokeParameters=it},label={Text("JSON 参数")}) } },
            confirmButton = { TextButton(onClick = {
                val selected = invokeCapability; val parameters = invokeParameters; invokePlugin = null
                installScope.launch {
                    try {
                        val entry = host?.entries(id)?.firstOrNull { it.key == selected } ?: error("CAPABILITY_NOT_DECLARED")
                        val accountId = app?.gatewayRuntime?.connectedAccountId ?: error("PAIRING_REQUIRED")
                        val result = runInterruptible(Dispatchers.IO) { host.invoke(entry.identity,accountId,selected,Json.parse(parameters),"ui_${java.util.UUID.randomUUID()}") }
                        notice = result.decodeToString().take(4096)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "调用被拒绝或失败，请检查配对授权、系统权限和参数。" }
                }
            }) { Text("执行") } },dismissButton = { TextButton(onClick = { invokePlugin = null }) { Text("取消") } })
    }
    if (showRemote) AlertDialog(onDismissRequest = { showRemote = false },title = { Text("从远程来源安装") },text = {
        Column {
            OutlinedTextField(sourceUrl,{sourceUrl=it},label={Text("HTTPS 包或索引地址")})
            OutlinedTextField(sourcePin,{sourcePin=it},label={Text("TLS SPKI pin（sha256:…）")})
            OutlinedTextField(sourceDigest,{sourceDigest=it},label={Text("固定发布 SHA-256（可代替 pin）")})
            OutlinedTextField(indexPluginId,{indexPluginId=it},label={Text("索引中的插件 ID（直接包留空）")})
        }
    },confirmButton = { TextButton(onClick = {
        showRemote = false
        installScope.launch { try { val bytes = runInterruptible(Dispatchers.IO) { RemotePluginSource.resolve(sourceUrl,sourcePin,sourceDigest,indexPluginId) }; installBytes(bytes) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { notice = "远程来源读取失败，请检查地址、pin 或摘要。" } }
    }) { Text("下载并验签") } },dismissButton = { TextButton(onClick = { showRemote = false }) { Text("取消") } })

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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
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
            SectionLabel("插件运行时")
            SettingsSectionCard {
                SettingsListItem(
                    headline = if (pluginRuntimesWired) "插件运行时已装配" else "宿主未装配插件运行时",
                    supporting = if (pluginRuntimesWired) {
                        "已安装且启用的插件由平台内核按六项交集裁决执行"
                    } else {
                        "已安装插件不会运行，「启用」不可用；安装仍会校验并保管包内容"
                    },
                    icon = Icons.Default.Extension,
                    tone = if (pluginRuntimesWired) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                )
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
                        view.pluginId?.let { id ->
                            if (host != null) {
                                Row {
                                    TextButton(onClick = { runCatching { host.enable(id,!host.isEnabled(id)) }.fold(onSuccess={notice="插件状态已更新。"},onFailure={notice="启用失败：请检查原生信任模式、包兼容性或安全模式。"}) }) { Text(if(host.isEnabled(id)) "停用" else "启用") }
                                    TextButton(onClick = { grantPlugin = id }) { Text("配对授权") }
                                    TextButton(onClick = { runCatching { resolvedStore.rollback(id); refresh() }.fold(onSuccess={notice="已回滚到上一版本。"},onFailure={notice="没有可回滚的有效版本。"}) }) { Text("回滚") }
                                    TextButton(onClick = { runCatching { host.uninstall(id); refresh() }.fold(onSuccess={notice="已卸载并清理插件数据。"},onFailure={notice="卸载未完成，请检查存储。"}) }) { Text("卸载") }
                                }
                                if (host.hasNativeUi(id)) TextButton(onClick = { runCatching { host.selectNativeUi(id) }.fold(onSuccess={notice="原生界面已启用，可随时恢复宿主界面。"},onFailure={notice="请先开启信任模式并启用插件。"}) }) { Text("使用原生界面") }
                                if (host.isQuarantined(id)) TextButton(onClick = { host.recoverNative(id); refresh() }) { Text("解除安全模式（保持停用）") }
                                if (host.entries(id).any { "kernel.notifications.read" in it.primitives }) TextButton(onClick = { context.startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("打开通知访问设置") }
                            }
                        }
                    }
                }
            }

            // 安装入口：渠道策略真实控制可用性。
            SectionLabel("安装插件包")
            SettingsSectionCard {
                if (allowRuntimePlugins) {
                    SettingsListItem(
                        headline = "从文件安装插件包 (.alp)",
                        supporting = "选择本机 .alp 文件，先经 Ed25519 签名与契约校验再落盘",
                        icon = Icons.Default.InstallMobile,
                        onClick = { pickPackage.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag(PLUGIN_INSTALL_ENTRY_TAG),
                    )
                    SettingsListItem(headline="从 HTTPS 发布、组织来源或索引安装", supporting="来源需提供 pin 或固定摘要，包仍须通过作者验签", onClick={showRemote=true})
                } else {
                    SettingsListItem(
                        headline = "从文件安装插件包 (.alp)",
                        supporting = "当前分发渠道禁止安装插件",
                        icon = Icons.Default.InstallMobile,
                        enabled = false,
                        modifier = Modifier.testTag(PLUGIN_INSTALL_ENTRY_TAG),
                    )
                }
            }
        }
    }
}

/** 一个已安装插件的真实状态与其声明式设置贡献。 */
@Composable
private fun InstalledPluginBlock(
    view: InstalledPluginView,
    onAction: PluginUiActionSink,
    host: ProductionPluginHost? = null,
) {
    val headline = view.displayName ?: view.directory.name
    val supporting = if (view.pluginId == null) {
        "插件目录 manifest 不可解析，已按未知包如实呈现"
    } else {
        buildString {
            append(view.pluginId)
            view.version?.let { append(" · v$it") }
            view.runtimeType?.let { append(" · $it") }
            append(if (host == null) " · 已安装，未启用（宿主未装配插件运行时）" else if (host.isEnabled(view.pluginId)) " · 已启用" else " · 已停用")
        }
    }
    SettingsListItem(headline = headline, supporting = supporting)

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
                    headline = "声明式设置被拒绝",
                    supporting = "该贡献无法被宿主安全渲染：$code",
                    tone = SettingsTone.DANGER,
                )
            },
        )
    }
}

/** 安装失败的如实说明：契约拒绝码原样给出，不回显原始异常文本。 */
internal fun installFailureNotice(cause: Throwable): String = when (cause) {
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
