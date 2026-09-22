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
    var installed by remember { mutableStateOf(resolvedStore.listInstalled()) }
    var notice by remember { mutableStateOf<String?>(null) }

    val pickPackage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            notice = "无法读取所选文件，安装未执行。"
            return@rememberLauncherForActivityResult
        }
        try {
            val view = resolvedStore.install(bytes)
            installed = resolvedStore.listInstalled()
            notice = "已安装 ${view.pluginId ?: view.directory.name}" +
                (view.version?.let { " v$it" } ?: "") +
                "（未启用：宿主未装配插件运行时）"
        } catch (rejected: Exception) {
            notice = installFailureNotice(rejected)
        }
    }

    val actionSink = PluginUiActionSink { componentId, action, _ ->
        // 动作必须经内核授权后才能落地；内核未装配时如实回显这次提交的去向。
        notice = "已提交 $action（$componentId）——$KERNEL_ABSENT_NOTICE"
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
                        InstalledPluginBlock(view = view, onAction = actionSink)
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
) {
    val headline = view.displayName ?: view.directory.name
    val supporting = if (view.pluginId == null) {
        "插件目录 manifest 不可解析，已按未知包如实呈现"
    } else {
        buildString {
            append(view.pluginId)
            view.version?.let { append(" · v$it") }
            view.runtimeType?.let { append(" · $it") }
            append(" · 已安装，未启用（宿主未装配插件运行时）")
        }
    }
    SettingsListItem(headline = headline, supporting = supporting)

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
