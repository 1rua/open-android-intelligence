package com.openandroidintelligence.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import com.openandroidintelligence.mobile.push.*
import com.openandroidintelligence.capability.DevicePushSourcesRegistry
import com.openandroidintelligence.capability.DevicePushSources
import com.openandroidintelligence.capability.DevicePushNotificationObserver
import com.openandroidintelligence.capability.DevicePushNotification
import com.openandroidintelligence.capability.DevicePushSmsQuery
import com.openandroidintelligence.kernel.SmsScopeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 手机原生采集、本机独立授权、Remote确切会话通知和系统调度的生产装配。 */
class DevicePushComposition(private val app: OpenAndroidIntelligenceApplication, installationId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cycle = Mutex()
    @Volatile private var started = false
    private val identity = RemotePushIdentity(app)
    private val _endpoints = MutableStateFlow<List<PushEndpointOption>>(emptyList())
    val endpoints: StateFlow<List<PushEndpointOption>> = _endpoints
    val issue = MutableStateFlow<String?>(null)
    private var observedAccount: com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount? = null
    private var visibleJob: Job? = null
    private var loginObserver: Job? = null
    private var smsObserver: ContentObserver? = null
    val runtime: DevicePushRuntime? = runCatching {
        DevicePushRuntime(EncryptedPushStateStore.fromContext(app, installationId), PushSystemAccess { kind ->
            !app.kernel.isEmergencyStopped() && when (kind) {
                PushKind.SMS -> app.pluginHost.isEnabled("org.openandroidintelligence.sms") && ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                PushKind.NOTIFICATION -> app.pluginHost.isEnabled("org.openandroidintelligence.notifications") &&
                    (if (com.openandroidintelligence.notification.control.NotificationControlRegistry.policyPort().snapshot().installed) {
                        com.openandroidintelligence.notification.control.NotificationControlRegistry.bindingPort().isListenerBound()
                    } else {
                        app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)
                    })
            }
        })
    }.getOrElse { issue.value = "PUSH_ENCRYPTED_STATE_UNAVAILABLE"; null }
    private val transport = runtime?.let { RemotePushTransport(it,app.remoteRuntime::borrowPushSession,identity::isCurrent) }
    private val recipientAuthorizer=runtime?.let(::RemotePushRecipientAuthorizer)
    private val emergencyObserver=com.openandroidintelligence.control.remote.PhoneLocalControlUiRegistry.observeEmergencyStop {
        runtime?.revokeRemoteRecipients()
    }

    private fun synchronizeLogin(): Boolean {
        val account=runCatching { identity.active() }.getOrNull()
        synchronized(this) {
            if (observedAccount != account) {
                runtime?.onRemoteAccountChanged(account?.origin,account?.accountId,account?.epoch)
                observedAccount=account
            }
        }
        runtime?.invalidateRemoteRecipients(identity::isCurrent)
        return account != null
    }

    suspend fun authorizeDelivery(access: com.openandroidintelligence.control.remote.ControlAccessBinding,
        operation: com.openandroidintelligence.control.remote.ControlOperation.Delivery,
        requireConnection: () -> Unit,awaitingConfirmation: (Boolean) -> Unit,
        confirmLocally: suspend (String) -> Boolean) {
        check(synchronizeLogin()) { "PUSH_AUTHORIZATION_CHANGED" }
        val local=runtime ?: error("PUSH_RUNTIME_UNAVAILABLE")
        val target=local.remoteReceipt(operation.deliveryId)?.endpoint?.remoteTarget ?: error("PUSH_DELIVERY_NOT_FOUND")
        fun current() {
            // 此检查会在runtime锁内调用，不能再取composition锁或同步登录状态。
            requireConnection()
            check(!app.kernel.isEmergencyStopped() && identity.accepts(access,target)) { "PUSH_AUTHORIZATION_CHANGED" }
            check(local.remoteDeliveryEnabled(operation,access.principal)) { "PUSH_AUTHORIZATION_CHANGED" }
        }
        recipientAuthorizer?.authorize(access,operation,::current,awaitingConfirmation,confirmLocally)
            ?: error("PUSH_RUNTIME_UNAVAILABLE")
    }

    /** 读取与ACK是手机能力，仍受本机独立推送开关、范围、白名单、系统权限和急停控制。 */
    fun delivery(access: com.openandroidintelligence.control.remote.ControlAccessBinding,
        operation: com.openandroidintelligence.control.remote.ControlOperation.Delivery): com.openandroidintelligence.gateway.schema.JsonValue {
        check(!app.kernel.isEmergencyStopped() && synchronizeLogin()) { "PUSH_AUTHORIZATION_CHANGED" }
        val local=runtime ?: error("PUSH_RUNTIME_UNAVAILABLE")
        val receipt=local.remoteReceipt(operation.deliveryId) ?: error("PUSH_DELIVERY_NOT_FOUND")
        val target=receipt.endpoint.remoteTarget ?: error("PUSH_REMOTE_TARGET_REQUIRED")
        check(identity.accepts(access,target)) { "PUSH_ACCOUNT_BINDING_MISMATCH" }
        fun current() { check(!app.kernel.isEmergencyStopped() && identity.accepts(access,target)) { "PUSH_AUTHORIZATION_CHANGED" } }
        val result=if (operation.acknowledge) {
            local.acknowledgeRemoteDelivery(operation.deliveryId,operation.eventId,operation.payloadDigest,access.principal,::current)
            com.openandroidintelligence.gateway.schema.Json.of(mapOf("deliveryId" to operation.deliveryId,"eventId" to operation.eventId,
                "payloadDigest" to operation.payloadDigest,"state" to "consumed"))
        } else {
            val payload=local.readRemoteDelivery(operation.deliveryId,operation.eventId,operation.payloadDigest,access.principal,::current)
            com.openandroidintelligence.gateway.schema.Json.of(mapOf("deliveryId" to operation.deliveryId,"eventId" to operation.eventId,
                "payloadDigest" to operation.payloadDigest,"state" to "accepted","payload" to com.openandroidintelligence.gateway.schema.Json.parse(payload)))
        }
        check(!app.kernel.isEmergencyStopped() && identity.accepts(access,target)) { "PUSH_AUTHORIZATION_CHANGED" }
        kick()
        return result
    }

    fun requireDeliveryContent(access: com.openandroidintelligence.control.remote.ControlAccessBinding,
        operation: com.openandroidintelligence.control.remote.ControlOperation.Delivery) {
        check(!app.kernel.isEmergencyStopped() && synchronizeLogin()) { "PUSH_AUTHORIZATION_CHANGED" }
        val local=runtime ?: error("PUSH_RUNTIME_UNAVAILABLE")
        val target=local.remoteReceipt(operation.deliveryId)?.endpoint?.remoteTarget ?: error("PUSH_DELIVERY_NOT_FOUND")
        check(identity.accepts(access,target)) { "PUSH_ACCOUNT_BINDING_MISMATCH" }
        local.requireRemoteDeliveryContent(operation,access.principal)
    }

    fun deliveryPolicy(access: com.openandroidintelligence.control.remote.ControlAccessBinding,
        operation: com.openandroidintelligence.control.remote.ControlOperation.Delivery): com.openandroidintelligence.kernel.NativePushDeliveryPolicy {
        val local=runtime ?: return com.openandroidintelligence.kernel.NativePushDeliveryPolicy("unavailable",false)
        val target=local.remoteReceipt(operation.deliveryId)?.endpoint?.remoteTarget
        return com.openandroidintelligence.kernel.NativePushDeliveryPolicy(local.authorizationDigest(),
            !app.kernel.isEmergencyStopped() && synchronizeLogin() && target != null && identity.accepts(access,target) &&
                local.remoteDeliveryEnabled(operation,access.principal))
    }

    private val notificationObserver = object : DevicePushNotificationObserver {
        override fun accepts(packageName: String): Boolean = synchronizeLogin() && runtime?.let {
            it.isCollectionAllowed(PushKind.NOTIFICATION) && packageName in it.snapshot().configuration.notifications.scope.packageIds
        } == true
        override fun includesContent(): Boolean = runtime?.snapshot()?.configuration?.notifications?.scope?.includeContent == true
        override fun onPosted(notification: DevicePushNotification) {
            if (!accepts(notification.packageName)) return
            val result = runtime?.onNotification(PushNotification(notification.notificationKey, notification.packageName,
                notification.postedAtEpochMs, notification.title, notification.body, notification.appLabel, notification.channelId))
            if (result?.status in setOf(PushCaptureStatus.QUEUE_FULL, PushCaptureStatus.TOO_LARGE)) issue.value = "PUSH_QUEUE_CAPACITY_REACHED"
            kick()
        }
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        runtime?.onWorkAvailable = ::kick
        runtime?.onRevocationAvailable = {
            scope.launch {
                runCatching { runCycle() }.onFailure { issue.value = "PUSH_REVOCATION_DELIVERY_PENDING" }
            }
            kick()
        }
        DevicePushSourcesRegistry.current?.setNotificationCollectionAccess {
            !app.kernel.isEmergencyStopped() && synchronizeLogin() &&
                app.pluginHost.isEnabled("org.openandroidintelligence.notifications")
        }
        DevicePushSourcesRegistry.current?.installNotificationObserver(notificationObserver)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { kick() }
        }
        smsObserver = observer
        app.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(PERIODIC_JOB) == null) scheduler.schedule(
            JobInfo.Builder(PERIODIC_JOB, ComponentName(app, DevicePushJobService::class.java))
                .setPeriodic(15 * 60_000L).setPersisted(true).build())
        kick()
    }

    fun kick() {
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(DELIVERY_JOB) == null) scheduler.schedule(
            JobInfo.Builder(DELIVERY_JOB, ComponentName(app, DevicePushJobService::class.java))
                .setMinimumLatency(1_000L).setOverrideDeadline(10_000L)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }

    fun onVisibilityChanged(visible: Boolean) {
        if (visible && loginObserver == null) {
            loginObserver = scope.launch {
                app.remoteRuntime.state.collectLatest { synchronizeLogin();kick() }
            }
        }
        visibleJob?.cancel()
        visibleJob = if (visible) scope.launch {
            while (isActive) {
                try { withTimeout(45_000L) { runCycle() } }
                catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                catch (_: Exception) { issue.value = "DEVICE_CONNECTION_RECOVERY_PENDING" }
                delay(10_000L)
            }
        } else null
        kick()
    }

    fun close() {
        emergencyObserver.close()
        scope.cancel()
        smsObserver?.let { app.contentResolver.unregisterContentObserver(it) }
        smsObserver = null
        DevicePushSourcesRegistry.current?.clearNotificationObserver(notificationObserver)
        runtime?.onWorkAvailable = null
        runtime?.onRevocationAvailable = null
    }

    fun refreshEndpoints() { scope.launch { runCatching { runCycle(discover = true) }.onFailure { issue.value = "PUSH_ENDPOINT_DISCOVERY_FAILED" } } }

    suspend fun runCycle(discover: Boolean = false): Boolean = cycle.withLock {
        val local=runtime ?: return@withLock true
        val loggedIn=synchronizeLogin()
        local.maintain()
        if (!loggedIn) { _endpoints.value=emptyList();return@withLock true }
        val current=app.remoteRuntime.borrowPushSession()
        _endpoints.value=current?.let { listOf(PushEndpointOption(it.target.endpoint(),it.label,it.isCurrent())) }.orEmpty()
        var moreSmsPages=false
        if (local.isCollectionAllowed(PushKind.NOTIFICATION)) DevicePushSourcesRegistry.current?.captureActiveNotifications()
        if (local.isCollectionAllowed(PushKind.SMS)) {
            moreSmsPages=DevicePushSourcesRegistry.current?.let { captureSmsPage(local,it,::synchronizeLogin) } ?: false
        }
        val remote=transport ?: return@withLock true
        // 本机撤销清除收件许可与记录；没有上传正文的远端缓冲，不再调用旧Core清理接口。
        runDevicePushCycle(local,remote,remote::revoke,{}, { false },
            onRevocationFailure={ issue.value="PUSH_REVOCATION_DELIVERY_PENDING" })
        pushCycleComplete(local.maintain(),moreSmsPages)
    }

    companion object {
        const val PERIODIC_JOB = 1874510
        const val DELIVERY_JOB = 1874511
    }
}

/** One native page only; the scheduler owns the next cycle, not a synchronous scan loop. */
internal fun captureSmsPage(local: DevicePushRuntime, sources: DevicePushSources, loginCurrent: () -> Boolean): Boolean {
    val settings = local.snapshot().configuration
    val scope = settings.sms.scope
    val cursor = local.smsCursor()
    return sources.captureSms(DevicePushSmsQuery(
        fromEpochMs = (System.currentTimeMillis() - settings.retentionHours * 3_600_000L).coerceAtLeast(0),
        afterId = cursor?.providerId, afterEpochMs = cursor?.receivedAtEpochMs,
        senders = scope.senderAddresses.takeIf { scope.mode == SmsScopeMode.PHONE_NUMBERS }.orEmpty(),
        allSenders = scope.mode == SmsScopeMode.ALL,
        includeContent = scope.includeContent,
        keywords = scope.keywords.takeIf { scope.mode == SmsScopeMode.KEYWORDS }.orEmpty(),
    ), isCurrent = { loginCurrent() && local.isCollectionAllowed(PushKind.SMS) &&
        local.snapshot().configuration.sms.scope == scope }, advanceSkippedCursor = { id, receivedAt ->
        local.advanceSmsCursor(PushSmsCursor(id, receivedAt), scope)
    }, enqueueDurably = { row ->
        loginCurrent() && local.onSms(PushSms(row.providerId, row.receivedAtEpochMs, row.senderAddress, row.body, row.threadId,
            row.subscriptionId, row.matchedKeywords), expectedScope = scope)
            .status in setOf(PushCaptureStatus.ENQUEUED, PushCaptureStatus.DUPLICATE)
    })
}

class DevicePushJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    override fun onStartJob(parameters: JobParameters): Boolean {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                retry = withTimeout(120_000) {
                    (application as? OpenAndroidIntelligenceApplication)?.devicePush?.runCycle() == false
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                retry = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { retry = true }
            finally {
                val owned = currentCoroutineContext()[Job]
                if (owned != null && running.remove(parameters.jobId, owned)) jobFinished(parameters, retry)
            }
        }
        running[parameters.jobId] = job
        job.start()
        return true
    }
    override fun onStopJob(parameters: JobParameters): Boolean {
        running.remove(parameters.jobId)?.cancel()
        return true
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
