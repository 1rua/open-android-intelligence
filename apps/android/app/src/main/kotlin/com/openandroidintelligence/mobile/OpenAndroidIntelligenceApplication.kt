package com.openandroidintelligence.mobile

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PersistentAuditSink
import com.openandroidintelligence.kernel.PluginKernel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 极简 Android 宿主组合根。
 *
 * 核心架构：
 * 1. 彻底解耦内建 Collector 与旧 Bridge 依赖；
 * 2. 零插件状态下仍具备完整的 Gateway v2 连接、多账号与对话附件支持；
 * 3. 平台内核（PluginKernel）独立初始化并管理受保护插件与审计。
 */
class OpenAndroidIntelligenceApplication : Application() {

    /** User controlled shell appearance, kept outside all Gateway secrets. */
    val appearancePreferences: AppearancePreferences by lazy {
        AppearancePreferences(this)
    }

    lateinit var kernel: PluginKernel
        private set

    lateinit var trustMode: DeveloperTrustMode
        private set

    lateinit var auditStore: AndroidAuditStore
        private set

    lateinit var pairingGrants: PairingGrantStateHolder
        private set

    private lateinit var auditSink: PersistentAuditSink

    /**
     * 紧急熔断后隔离插件数的进程级持有者。
     *
     * 设置面板是 AnimatedVisibility 子树，每次打开都是新的 ViewModel；
     * 计数若只存在 ViewModel 里，面板一关一开就会把真实发生过的熔断归零。
     * 这里保存在 Application 上，与内核的熔断状态同生命周期（进程重启即清，
     * 与「唯一恢复路径是重启」的内核语义一致）。
     */
    val emergencyStoppedCount = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 进程级连接运行时：登录、协商、会话建立与工作台装配。 */
    val gatewayRuntime: GatewayRuntime by lazy {
        GatewayRuntime(
            context = this,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pairingGrants = pairingGrants,
            auditStore = auditStore,
        )
    }

    /**
     * 进程装配的插件运行时（供 [PluginKernel] 执行裁决）。
     *
     * 当前生产装配为空：还没有任何插件运行时接入。运行时装配的真实状态通过
     * [pluginRuntimesWired] 进入设置面，插件管理区域据此如实声明
     * 「已安装插件不会运行、启用不可用」，而不是把不存在的执行能力说成可用。
     * 接入运行时（如 plugin-runtime-wasm）时更新这份装配即可，界面自动跟随。
     */
    private val pluginRuntimes: Map<String, com.openandroidintelligence.kernel.PluginRuntime> = emptyMap()

    val pluginRuntimesWired: Boolean get() = pluginRuntimes.isNotEmpty()

    fun platformSettingsEnvironment(): PlatformSettingsEnvironment = PlatformSettingsEnvironment(
        trustMode = trustMode,
        audit = auditStore,
        auditSink = auditSink,
        allowDeveloperTrustMode = BuildConfig.ALLOW_DEVELOPER_TRUST_MODE,
        kernel = kernel,
        pairingGrants = pairingGrants,
        appearance = appearancePreferences,
        allowRuntimePlugins = BuildConfig.ALLOW_RUNTIME_PLUGINS,
        pluginRuntimesWired = pluginRuntimesWired,
        emergencyStoppedCount = emergencyStoppedCount,
    )

    override fun onCreate() {
        super.onCreate()
        // The transport writes diagnostics through GatewayLog so it stays testable
        // on a JVM; the app is the only place that knows what Logcat is.
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = { tag, message ->
            android.util.Log.d(tag, message)
        }
        trustMode = DeveloperTrustMode(SharedPreferencesTrustModePersistence.from(this))
        auditSink = PersistentAuditSink(File(filesDir, "platform-kernel/audit-events.log"))
        auditStore = AndroidAuditStore(sink = auditSink)
        pairingGrants = PairingGrantStateHolder(
            store = SharedPreferencesPairingGrantStore.from(this),
            audit = auditStore,
        )

        val providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap())
        kernel = PluginKernel(
            hostEnvelope = HostEnvelope(
                primitives = setOf(
                    "org.openandroidintelligence.notifications.query@1.0.0",
                    "org.openandroidintelligence.sms.query@1.0.0",
                    "org.openandroidintelligence.call-log.query@1.0.0",
                ),
            ),
            phoneLimits = PhoneLimits(primitives = emptySet()),
            runtimes = pluginRuntimes,
            audit = auditStore,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = providerSelector,
            grants = { pairingId -> pairingGrants.currentKernelGrant(pairingId) },
        )

        // Visibility is a process fact, not an Activity one: a background gap
        // can leave the event stream nominally alive while the Gateway dropped
        // frames into a queue nobody was draining, so the app re-synchronizes
        // the moment it can be seen again. A cold start reaches this with no
        // workbench yet, which is honestly nothing to fix.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    gatewayRuntime.onAppForegrounded()
                }
            },
        )
    }
}
