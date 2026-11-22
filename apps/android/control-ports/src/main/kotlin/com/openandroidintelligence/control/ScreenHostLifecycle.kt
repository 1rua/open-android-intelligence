package com.openandroidintelligence.control

data class ScreenHostStatus(
    val binding: ScreenAuthorizationBinding?,
    val local: ScreenLocalStatus?,
    val accessibilityError: String?,
    val captureError: String?,
    val capabilityErrors: Map<ScreenPermission, String?>,
    val stopGeneration: Long = 0L,
)

/** Trusted, in-process host integration. None of these methods is a remote capability. */
interface ScreenHostLifecycle {
    val authorization: ScreenLocalAuthorization
    fun binding(): ScreenAuthorizationBinding?
    fun status(): ScreenHostStatus
    fun connect(accountId: String, pairingId: String, pairingGeneration: Long, localRevision: () -> Long)
    /** Registers one authenticated connection; the returned opaque generation distinguishes Cores. */
    fun registerConnection(accountId: String, pairingId: String, pairingGeneration: Long,
        localRevision: () -> Long): ScreenAuthorizationBinding
    /** Presentation only; selecting a chat never disconnects another registered connection. */
    fun selectConnection(binding: ScreenAuthorizationBinding)
    fun disconnectConnection(binding: ScreenAuthorizationBinding)
    fun disconnectAll()
    fun connectionChanged(live: Boolean)
    fun refreshAuthorization()
    fun disconnect()
    fun revoke()
    fun localSnapshot(): ScreenSnapshot
    fun localTarget(): ScreenTargetIdentity
    fun installConfirmation(requestId: String, parameterDigest: String, expiresAtEpochMs: Long)
    /** Request-scoped global policy decision; identity binding is not another capability policy. */
    fun installRequestAuthorization(
        requestId: String,
        permissions: Set<ScreenPermission>,
        expiresAtEpochMs: Long,
        parameterDigest: String,
        revalidate: () -> Unit,
    )
    /** Exact registered connection, supplied by its owning authenticated driver. */
    fun installRequestAuthorization(
        requestId: String,
        permissions: Set<ScreenPermission>,
        expiresAtEpochMs: Long,
        parameterDigest: String,
        binding: ScreenAuthorizationBinding,
        revalidate: () -> Unit,
    )
    /** CONTROL-only compatibility entry; does not show UI or grant Android permissions. */
    fun installControlAuthorization(
        requestId: String,
        parameterDigest: String,
        expiresAtEpochMs: Long,
        revalidate: () -> Unit,
    )
    /** Trusted driver heartbeat; never accepts authority from remote operation parameters. */
    fun heartbeatControl(requestId: String): Long
    fun heartbeatControl(requestId: String, binding: ScreenAuthorizationBinding): Long
    fun finishRequest(requestId: String)
    fun finishRequest(requestId: String, binding: ScreenAuthorizationBinding)
    fun cancelRequest(requestId: String)
    fun cancelRequest(requestId: String, binding: ScreenAuthorizationBinding)
    fun captureStopped()
}

/** Populated by the Android primitive bootstrap; absence means no provider, never implicit grant. */
object ScreenHostRegistry {
    /** Shared by every connection/Agent in this App process, independent of read/capture. */
    val controlLeases = ScreenControlLeaseManager()
    @Volatile var current: ScreenHostLifecycle? = null
    private val _accessibilityConnected = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 无障碍服务真实连接态：由无障碍服务生命周期回调即时驱动，不受锁屏状态混淆。 */
    val accessibilityConnected: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _accessibilityConnected

    fun setAccessibilityConnected(connected: Boolean) {
        _accessibilityConnected.value = connected
    }

    /**
     * 检查给定组件名是否存在于系统设置（如 `enabled_accessibility_services`）的冒号分隔列表中，
     * 兼容完整类名、简写类名及不同大小写格式。
     */
    fun isComponentInColonSeparatedSetting(
        rawList: String,
        target: android.content.ComponentName,
    ): Boolean {
        if (rawList.isBlank()) return false
        val full = target.flattenToString()
        val short = target.flattenToShortString()
        return rawList.split(':').any { raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) return@any false
            android.content.ComponentName.unflattenFromString(entry) == target ||
                entry.equals(full, ignoreCase = true) ||
                entry.equals(short, ignoreCase = true)
        }
    }

    /**
     * 交叉核验系统 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 与
     * `AccessibilityManager.getEnabledAccessibilityServiceList`，判断指定无障碍服务是否已在系统设置中开启。
     */
    fun isAccessibilityServiceEnabledInSettings(
        context: android.content.Context,
        serviceComponent: android.content.ComponentName,
    ): Boolean = runCatching {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (isComponentInColonSeparatedSetting(enabled, serviceComponent)) return@runCatching true
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?: return@runCatching false
        manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            serviceInfo.packageName == serviceComponent.packageName && serviceInfo.name == serviceComponent.className
        }
    }.getOrDefault(false)
}
