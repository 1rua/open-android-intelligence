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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 极简 Android 宿主组合根。
 *
 * 核心架构：
 * 1. 彻底解耦内建 Collector 与旧 Bridge 依赖；
 * 2. 零插件状态下仍具备完整的 Gateway v2 连接、多账号与对话附件支持；
 * 3. 平台内核（PluginKernel）独立初始化并管理受保护插件与审计。
 */
class OpenAndroidIntelligenceApplication : Application(), RemoteStandbyOwner {

    /** User controlled shell appearance, kept outside all Gateway secrets. */
    val appearancePreferences: AppearancePreferences by lazy {
        AppearancePreferences(this)
    }

    /**
     * 首次启动引导的进度与本机状态。
     *
     * 与 [appearancePreferences] 同构：只存「用户走到哪一步」，不涉及任何凭据，
     * 也不参与连接判定——引导完成 ≠ Gateway 已连接。
     */
    val onboardingPreferences: com.openandroidintelligence.mobile.onboarding.OnboardingPreferences by lazy {
        com.openandroidintelligence.mobile.onboarding.OnboardingPreferences(this)
    }

    lateinit var kernel: PluginKernel
        private set

    lateinit var trustMode: DeveloperTrustMode
        private set

    lateinit var auditStore: AndroidAuditStore
        private set

    lateinit var pairingGrants: PairingGrantStateHolder
        private set

    lateinit var pluginHost: com.openandroidintelligence.mobile.plugins.ProductionPluginHost
        private set

    var devicePush: DevicePushComposition? = null
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

    /** 进程级 Remote 连接运行时。 */
    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val remoteRuntime: RemoteRuntime by lazy {
        RemoteRuntime(this, remoteScope, pluginHost, pairingGrants)
    }

    val pluginRuntimesWired: Boolean get() = ::pluginHost.isInitialized && pluginHost.runtimes.isNotEmpty()

    private val gatewayNetworkMonitor by lazy {
        GatewayNetworkMonitor(
            context = this,
            onAvailable = { remoteRuntime.onNetworkAvailable() },
            onLost = { remoteRuntime.onNetworkLost() },
            onRouteChanged = { remoteRuntime.onNetworkAvailable(routeChanged = true) },
        )
    }

    override suspend fun restoreHostStandby(): kotlinx.coroutines.flow.StateFlow<Boolean> {
        val account = remoteRuntime.accountRuntime
        account.restoreForStandby()
        if (account.hostStandby.value) gatewayNetworkMonitor.start()
        return account.hostStandby
    }

    /** Only an eligible, already foregrounded service reaches this recovery path. */
    internal fun onBackgroundSessionRestarted() {
        remoteRuntime.onBackgroundSessionRestarted()
        gatewayNetworkMonitor.start()
    }

    internal fun onBackgroundSessionStopped() {
        remoteRuntime.onBackgroundSessionStopped()
    }

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
        if (Application.getProcessName() != packageName) return
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidenceEnabled = BuildConfig.DEBUG
        // The transport writes diagnostics through GatewayLog so it stays testable
        // on a JVM; the app is the only place that knows what Logcat is.
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = { tag, message ->
            android.util.Log.d(tag, message)
        }
        trustMode = DeveloperTrustMode(SharedPreferencesTrustModePersistence.from(this))
        auditSink = PersistentAuditSink(File(filesDir, "platform-kernel/audit-events.log"))
        auditStore = AndroidAuditStore(sink = auditSink)
        pairingGrants = PairingGrantStateHolder(
            store = object : com.openandroidintelligence.kernel.PairingGrantStore {
                override fun load(binding: com.openandroidintelligence.kernel.PairingGrantBinding) =
                    SharedPreferencesPairingGrantStore.from(this@OpenAndroidIntelligenceApplication).load(binding)
                override fun save(binding: com.openandroidintelligence.kernel.PairingGrantBinding,
                    state: com.openandroidintelligence.kernel.PairingGrantState) =
                    SharedPreferencesPairingGrantStore.from(this@OpenAndroidIntelligenceApplication).save(binding, state)
            },
            audit = auditStore,
        )

        val installPreferences = getSharedPreferences("open_android_intelligence_runtime", MODE_PRIVATE)
        val installId = installPreferences.getString("installation_id", null) ?: java.util.UUID.randomUUID().toString().also {
            check(installPreferences.edit().putString("installation_id", it).commit())
        }
        pluginHost = com.openandroidintelligence.mobile.plugins.ProductionPluginHost(this,pairingGrants,trustMode,NativePluginLoader(trustMode),installId)
        kernel = PluginKernel(
            hostEnvelope = HostEnvelope(pluginHost.hostCapabilities),
            phoneLimits = PhoneLimits(pluginHost.phoneCapabilities),
            runtimes = pluginHost.runtimes,
            audit = auditStore,
            trustMode = trustMode,
            nativeLoader = pluginHost.nativeLoader,
            providerSelector = pluginHost.selector,
            grants = { pairingId -> pairingGrants.currentKernelGrant(pairingId) },
            mediate = pluginHost::mediate,
            authorizeInvocation = pluginHost::authorizeKernelInvocation,
        )

        pluginHost.attach(kernel)
        kernel.onEmergencyStop { stopped ->
            if (stopped) {
                pairingGrants.revokeTemporaryApprovals()
                com.openandroidintelligence.control.ScreenHostRegistry.current?.disconnectAll()
            }
        }

        devicePush = DevicePushComposition(this,installId).also { it.start() }
        val visibility = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        remoteScope.launch {
            combine(remoteRuntime.accountRuntime.hostStandby, visibility) { eligible, state ->
                eligible && state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            }.startVisibleHostStandby {
                RemoteHostStandbyService.requestStart(this@OpenAndroidIntelligenceApplication)
            }
        }

        remoteScope.launch(Dispatchers.IO) {
            runCatching { pluginHost.reconcileNotificationPolicyGrant() }
        }
        remoteScope.launch {
            com.openandroidintelligence.control.ScreenHostRegistry.accessibilityConnected.drop(1).collect {
                pluginHost.notifyCapabilityStateChanged()
            }
        }
        remoteScope.launch(Dispatchers.IO) {
            com.openandroidintelligence.notification.control.NotificationControlRegistry
                .bindingPort()
                .observeListenerConnected()
                .drop(1)
                .collect {
                    val reconciled = runCatching { pluginHost.reconcileNotificationPolicyGrant() }.getOrDefault(false)
                    if (!reconciled) pluginHost.notifyCapabilityStateChanged()
                }
        }


        // Visibility is a process fact, not an Activity one: a background gap
        // can leave the event stream nominally alive while the Gateway dropped
        // frames into a queue nobody was draining, so the app re-synchronizes
        // the moment it can be seen again. A cold start reaches this with no
        // workbench yet, which is honestly nothing to fix.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    gatewayNetworkMonitor.start()
                    remoteRuntime.onAppForegrounded()
                    devicePush?.onVisibilityChanged(true)
                    remoteScope.launch(Dispatchers.IO) {
                        runCatching { pluginHost.reconcileNotificationPolicyGrant() }
                    }
                }
                override fun onStop(owner: LifecycleOwner) {
                    remoteRuntime.onAppBackgrounded()
                    devicePush?.onVisibilityChanged(false)
                }
            },
        )
    }
    override fun onTerminate() {
        devicePush?.close()
        super.onTerminate()
    }

}
