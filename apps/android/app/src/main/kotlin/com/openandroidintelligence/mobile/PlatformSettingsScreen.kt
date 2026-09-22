package com.openandroidintelligence.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.ObservableAuditSink
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PluginKernel
import kotlinx.coroutines.flow.MutableStateFlow

/** Distribution policy from the build flavor; the Play build cannot unlock trust mode. */
data class DistributionPolicy(
    val allowRuntimePlugins: Boolean,
    val allowDeveloperTrustMode: Boolean,
)

/** Everything the settings screen reads, wired from the composition root. */
data class PlatformSettingsEnvironment(
    val trustMode: DeveloperTrustMode,
    val audit: AndroidAuditStore,
    val auditSink: ObservableAuditSink,
    val allowDeveloperTrustMode: Boolean,
    /** The kernel the emergency cut-off acts on. */
    val kernel: PluginKernel,
    val pairingGrants: PairingGrantStateHolder,
    val appearance: AppearancePreferences,
    /**
     * 紧急熔断后隔离插件数的持久持有者（Application 级注入）。
     * 设置面板是 AnimatedVisibility 子树，随时销毁重建；没有这个持有者，
     * 计数就会跟着面板归零，把一次真实发生过的熔断显示成从未发生。
     */
    val emergencyStoppedCount: MutableStateFlow<Int> = MutableStateFlow(0),
)

/**
 * 平台管理主界面包装器，委托至重构后的 M3 标准 [SettingsScreen]。
 * 保持源码级与二进制级向前兼容。
 */
@Composable
fun PlatformSettingsScreen(
    environment: PlatformSettingsEnvironment,
    onBack: () -> Unit,
    runtime: GatewayRuntime? = null,
    modifier: Modifier = Modifier,
) {
    SettingsScreen(
        environment = environment,
        onBack = onBack,
        runtime = runtime,
        modifier = modifier,
    )
}
