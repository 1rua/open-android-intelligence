package com.openandroidintelligence.mobile

import android.app.Application
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

    /** 进程级连接运行时：登录、协商、会话建立与工作台装配。 */
    val gatewayRuntime: GatewayRuntime by lazy {
        GatewayRuntime(
            context = this,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pairingGrants = pairingGrants,
        )
    }

    fun platformSettingsEnvironment(): PlatformSettingsEnvironment = PlatformSettingsEnvironment(
        trustMode = trustMode,
        audit = auditStore,
        auditSink = auditSink,
        allowDeveloperTrustMode = BuildConfig.ALLOW_DEVELOPER_TRUST_MODE,
        kernel = kernel,
        pairingGrants = pairingGrants,
        appearance = appearancePreferences,
    )

    override fun onCreate() {
        super.onCreate()
        // The transport writes diagnostics through GatewayLog so it stays testable
        // on a JVM; the app is the only place that knows what Logcat is.
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = { tag, message ->
            android.util.Log.d(tag, message)
        }
        trustMode = DeveloperTrustMode()
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
            runtimes = emptyMap(),
            audit = auditStore,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = providerSelector,
            grants = { pairingId -> pairingGrants.currentKernelGrant(pairingId) },
        )
    }
}
