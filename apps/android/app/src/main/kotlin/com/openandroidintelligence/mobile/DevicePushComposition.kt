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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The production seam connecting native sources, installation policy, signed HTTP and Android JobScheduler. */
class DevicePushComposition(private val app: OpenAndroidIntelligenceApplication, installationId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cycle = Mutex()
    @Volatile private var started = false
    private val connections = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, PushGatewayConnection>()
    private val _endpoints = MutableStateFlow<List<PushEndpointOption>>(emptyList())
    val endpoints: StateFlow<List<PushEndpointOption>> = _endpoints
    val issue = MutableStateFlow<String?>(null)
    private var observedLogin: Boolean? = null
    private var observedAccounts: Set<String> = emptySet()
    private var visibleJob: Job? = null
    private var loginObserver: Job? = null
    private var smsObserver: ContentObserver? = null
    val runtime: DevicePushRuntime? = runCatching {
        DevicePushRuntime(EncryptedPushStateStore.fromContext(app, installationId), PushSystemAccess { kind ->
            !app.kernel.isEmergencyStopped() && when (kind) {
                PushKind.SMS -> app.pluginHost.isEnabled("org.openandroidintelligence.sms") && ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                PushKind.NOTIFICATION -> app.pluginHost.isEnabled("org.openandroidintelligence.notifications") && app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)
            }
        })
    }.getOrElse { issue.value = "PUSH_ENCRYPTED_STATE_UNAVAILABLE"; null }
    private val transport = GatewayPushTransport(PushGatewayConnections { core, account -> connections[core to account] })

    private fun synchronizeLogin(): Boolean {
        val accounts = app.gatewayRuntime.savedProfiles.value.filter {
            app.gatewayRuntime.isPushProfileLoggedIn(it.localProfileId)
        }.map { it.localProfileId }.toSet()
        val current = accounts.isNotEmpty()
        synchronized(this) {
            if (observedLogin != current || observedAccounts != accounts) {
                runtime?.onLoginStateChanged(current)
                observedLogin = current
                observedAccounts = accounts
            }
        }
        return current
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
            !app.kernel.isEmergencyStopped() && app.gatewayRuntime.hasLoggedInPushAccount() &&
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
            val gateway = app.gatewayRuntime
            loginObserver = scope.launch {
                kotlinx.coroutines.flow.combine(gateway.loginStatus, gateway.savedProfiles) { _, _ -> Unit }
                    .collectLatest { synchronizeLogin(); kick() }
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
        scope.cancel()
        smsObserver?.let { app.contentResolver.unregisterContentObserver(it) }
        smsObserver = null
        DevicePushSourcesRegistry.current?.clearNotificationObserver(notificationObserver)
        runtime?.onWorkAvailable = null
        runtime?.onRevocationAvailable = null
    }

    fun refreshEndpoints() { scope.launch { runCatching { runCycle(discover = true) }.onFailure { issue.value = "PUSH_ENDPOINT_DISCOVERY_FAILED" } } }

    suspend fun runCycle(discover: Boolean = false): Boolean = cycle.withLock {
        val local = runtime
        val loggedIn = synchronizeLogin()
        val snapshot = local?.maintain()
        if (!loggedIn) {
            connections.clear()
            app.gatewayRuntime.additionalDeviceConnections?.cancelAll()
            return@withLock true
        }
        val enabled = snapshot?.configuration?.let { it.sms.enabled || it.notifications.enabled } == true
        val available = app.gatewayRuntime.pushConnections()
        connections.clear(); available.forEach { connections[it.coreInstanceId to it.accountScope] = it }
        app.gatewayRuntime.syncAdditionalDeviceRequests()
        if (local == null || snapshot == null) return@withLock true
        if (!enabled && !discover && snapshot.pendingDeliveries == 0 && snapshot.pendingRevocations == 0) return@withLock true
        val options = available.flatMap { connection ->
            try { transport.discover(connection) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { issue.value = "PUSH_ENDPOINT_DISCOVERY_FAILED"; emptyList() }
        }
        _endpoints.value = options
        runDevicePushCycle(local, transport,
            revoke = transport::revoke,
            captureActiveNotifications = { DevicePushSourcesRegistry.current?.captureActiveNotifications() },
            captureSmsPage = {
                DevicePushSourcesRegistry.current?.let { sources -> captureSmsPage(local, sources) { synchronizeLogin() } } ?: false
            },
            onRevocationFailure = { issue.value = "PUSH_REVOCATION_DELIVERY_PENDING" },
        )
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
