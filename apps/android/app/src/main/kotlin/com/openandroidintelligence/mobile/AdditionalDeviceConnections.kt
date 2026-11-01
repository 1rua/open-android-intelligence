package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.control.ScreenAuthorizationBinding
import com.openandroidintelligence.control.ScreenHostLifecycle
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.requireData
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.mobile.plugins.CapabilityPublisher
import com.openandroidintelligence.mobile.plugins.DeviceConfirmation
import com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.plugins.digestName
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Supplied only after Runtime authenticates and negotiates Android device authorization. */
data class DeviceConnectionProfile(
    val profileId: String,
    val baseUrl: String,
    val accountId: String,
    val deviceId: String,
    val generation: Int,
    val http: GatewayHttpClient,
    /** Credential/session ownership, independent of the selected conversation account. */
    val isCurrent: () -> Boolean,
    val grantRevision: Int = 0,
) {
    init {
        require(profileId.isNotBlank() && accountId.isNotBlank() && deviceId.isNotBlank())
        require(generation > 0 && grantRevision >= 0)
    }
}

/**
 * Device-only sessions for authenticated profiles not owned by the selected conversation's driver.
 * Runtime calls sync from its foreground cycle or background job; this module opens no stream or poll loop.
 * Capability policy is installation-wide; publication, receipts and encrypted journals stay per profile.
 */
class AdditionalDeviceConnections(
    context: Context,
    private val scope: CoroutineScope,
    private val host: ProductionPluginHost,
    private val holder: PairingGrantStateHolder,
    private val installationId: String,
    private val isForeground: () -> Boolean = { false },
    private val keyProvider: AesGcmKeyProvider? = null,
    private val cycleTimeoutMillis: Long = 15_000L,
    private val requestTimeoutMillis: Long = 5_000L,
) {
    private val context = context.applicationContext
    private val lock = Any()
    private val cycleLock = Mutex()
    private val epoch = AtomicLong()
    private val connections = linkedMapOf<String, Connection>()
    private val mutableConfirmation = MutableStateFlow<DeviceConfirmation?>(null)
    val confirmation: StateFlow<DeviceConfirmation?> = mutableConfirmation.asStateFlow()

    init {
        require(installationId.isNotBlank())
        require(cycleTimeoutMillis > 0 && requestTimeoutMillis > 0)
    }

    /** Each profile failure is isolated; caller cancellation still cancels the cycle. */
    suspend fun sync(profiles: List<DeviceConnectionProfile>) {
        val expectedEpoch = epoch.get()
        cycleLock.withLock {
            if (epoch.get() != expectedEpoch || scope.coroutineContext[Job]?.isActive == false) return
            if (profiles.map { it.profileId }.toSet().size != profiles.size) {
                closeProfiles()
                error("DUPLICATE_DEVICE_PROFILE")
            }
            val wanted = profiles.filter { runCatching(it.isCurrent).getOrDefault(false) }.associateBy { it.profileId }
            val retired = synchronized(lock) {
                connections.values.filter { old ->
                    val next = wanted[old.profile.profileId]
                    next == null || !old.matches(next)
                }.onEach { old -> old.active = false; connections.remove(old.profile.profileId) }
            }
            retired.forEach(::close)
            val active = wanted.values.mapNotNull { profile ->
                if (epoch.get() != expectedEpoch) return@mapNotNull null
                try {
                    synchronized(lock) {
                        if (epoch.get() != expectedEpoch) return@synchronized null
                        connections[profile.profileId]?.also { it.profile = profile } ?: create(profile).also {
                            connections[profile.profileId] = it
                            it.observeConfirmation()
                            it.job.invokeOnCompletion { _ -> releaseCompleted(it) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    diagnostic(profile.profileId, "DEVICE_PROFILE_INITIALIZATION_FAILED")
                    null
                }
            }
            supervisorScope {
                active.forEach { connection -> launch { cycle(connection) } }
            }
            publishConfirmation()
        }
    }

    /** Only the unambiguous, currently displayed request may receive a local decision. */
    fun respond(id: String, allow: Boolean, duration: Int = 0, expectedDecisionToken: String? = null) {
        require(duration in setOf(0, 5, 30, 60))
        val shown = mutableConfirmation.value ?: return
        if (shown.requestId != id || expectedDecisionToken != null && shown.decisionToken != expectedDecisionToken) return
        val pending = currentConnections().filter {
            it.executionCurrent() && it.driver.confirmation.value?.requestId == id
        }
        val owner = pending.singleOrNull() ?: return
        val current = owner.driver.confirmation.value ?: return
        if (current != shown || expectedDecisionToken != null && current.decisionToken != expectedDecisionToken) return
        if (mutableConfirmation.value == shown && owner.executionCurrent()) {
            owner.driver.respond(id, allow, duration)
        }
    }

    /** Stops owned work and fences late callbacks without deleting encrypted recovery journals. */
    fun cancelAll() = closeProfiles()

    fun closeProfiles() {
        epoch.incrementAndGet()
        val removed = synchronized(lock) {
            connections.values.toList().onEach { it.active = false }.also { connections.clear() }
        }
        removed.forEach(::close)
        mutableConfirmation.value = null
    }

    private fun create(profile: DeviceConnectionProfile): Connection {
        val binding = PairingGrantBinding(profile.baseUrl, profile.accountId, installationId)
        val job = SupervisorJob(scope.coroutineContext[Job])
        val connection = Connection(profile, binding, job, CoroutineScope(scope.coroutineContext + job))
        try {
            connection.registrationId = holder.attachConnection(binding)
            connection.screenHost = ScreenHostRegistry.current
            connection.screenBinding = connection.screenHost?.registerConnection(
                profile.accountId, binding.pairingId, profile.generation.toLong(),
            ) { holder.state.value?.revision ?: -1L }
            val scopeKey = "additional-device:v1:" + digestName(Json.canonical(Json.of(listOf(
                profile.profileId, profile.baseUrl, profile.accountId, profile.deviceId, profile.generation, installationId,
            ))))
            connection.publisher = CapabilityPublisher(context, profile.http, scopeKey, profile.grantRevision,
                localRevision = { holder.state.value?.revision ?: -1L }, host = host,
                foreignChange = { connection.driver.cancelAll() }, keyProvider = keyProvider,
                authorizationCurrent = connection::bindingCurrent, bindingCurrent = connection::bindingCurrent,
                ownerCurrent = connection::owned)
            connection.driver = DeviceExecutionDriver(context, connection.scope, profile.http, host,
                profile.accountId, profile.deviceId, profile.generation, scopeKey,
                currentGrantRevision = { connection.publisher.grantRevision }, isForeground = isForeground,
                keyProvider = keyProvider, authorizationCurrent = connection::executionCurrent,
                localAuthorizationRevision = { holder.state.value?.revision ?: -1L }, authenticatedBinding = binding,
                screenBinding = connection.screenBinding)
            return connection
        } catch (cause: Exception) {
            disconnectScreen(connection)
            job.cancel()
            detachRegistration(connection)
            throw cause
        }
    }

    private suspend fun cycle(connection: Connection) {
        try {
            withTimeout(cycleTimeoutMillis) {
                check(connection.bindingCurrent()) { "DEVICE_PROFILE_CHANGED" }
                connection.publisher.sync()
                connection.healthy = true
                check(connection.executionCurrent()) { "DEVICE_PUBLICATION_REQUIRED" }
                val response = connection.profile.http.execute(SignedGatewayRequest("GET", PENDING_REQUESTS_PATH,
                    beforeSend = { check(connection.executionCurrent()) { "DEVICE_PROFILE_CHANGED" } }))
                check(connection.executionCurrent()) { "DEVICE_PROFILE_CHANGED" }
                val data = response.requireData("DEVICE_PENDING_LOOKUP_FAILED")
                check(JsonFields.string(data, "deviceId") == connection.profile.deviceId &&
                    JsonFields.int(data, "pairingGeneration") == connection.profile.generation &&
                    JsonFields.int(data, "grantRevision") == connection.publisher.grantRevision) { "DEVICE_PENDING_BINDING_MISMATCH" }
                val pending = JsonFields.field(data, "pendingDeviceRequests") as? JsonValue.JArray
                    ?: error("DEVICE_PENDING_LIST_INVALID")
                check(pending.items.size <= MAX_PENDING_REQUESTS) { "DEVICE_PENDING_LIST_INVALID" }
                val ids = pending.items.map {
                    val id = (it as? JsonValue.JString)?.value ?: error("DEVICE_PENDING_LIST_INVALID")
                    check(REQUEST_ID.matches(id)) { "DEVICE_PENDING_LIST_INVALID" }
                    id
                }.distinct()
                ids.forEach { id ->
                    try {
                        withTimeout(requestTimeoutMillis) {
                            check(connection.executionCurrent()) { "DEVICE_PROFILE_CHANGED" }
                            connection.driver.accept(id)
                        }
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        diagnostic(connection.profile.profileId, "DEVICE_REQUEST_LOOKUP_TIMEOUT")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        diagnostic(connection.profile.profileId, "DEVICE_REQUEST_RECOVERY_PENDING")
                    }
                }
                check(connection.executionCurrent()) { "DEVICE_PROFILE_CHANGED" }
                connection.driver.recoverPending()
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            connection.healthy = false
            diagnostic(connection.profile.profileId, "DEVICE_CYCLE_TIMEOUT")
        } catch (cancelled: CancellationException) {
            connection.healthy = false
            throw cancelled
        } catch (failure: Exception) {
            connection.healthy = false
            val code = failure.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,128}")) } ?: failure.javaClass.simpleName
            diagnostic(connection.profile.profileId, "DEVICE_CYCLE_FAILED_$code")
        } finally {
            publishConfirmation()
        }
    }

    private inner class Connection(
        @Volatile var profile: DeviceConnectionProfile,
        val binding: PairingGrantBinding,
        val job: Job,
        val scope: CoroutineScope,
    ) {
        @Volatile var active = true
        @Volatile var healthy = false
        @Volatile var screenHost: ScreenHostLifecycle? = null
        @Volatile var registrationId: String? = null
        @Volatile var screenBinding: ScreenAuthorizationBinding? = null
        lateinit var publisher: CapabilityPublisher
        lateinit var driver: DeviceExecutionDriver

        fun matches(next: DeviceConnectionProfile) = profile.baseUrl == next.baseUrl &&
            profile.accountId == next.accountId && profile.deviceId == next.deviceId &&
            profile.generation == next.generation && profile.http === next.http

        fun owned(): Boolean = active && job.isActive &&
            synchronized(lock) { connections[profile.profileId] === this }

        fun bindingCurrent(): Boolean = owned() && runCatching(profile.isCurrent).getOrDefault(false) &&
            holder.isConnectionCurrent(binding)

        fun executionCurrent(): Boolean = bindingCurrent() && healthy &&
            publisher.state.value.forLocalState(holder.state.value?.revision ?: -1L, host.revision.value).status == "SYNCED"

        fun observeConfirmation() {
            scope.launch {
                combine(driver.confirmation, publisher.state, holder.state, host.revision) { _, _, _, _ -> Unit }
                    .collect { publishConfirmation() }
            }
        }
    }

    private fun currentConnections(): List<Connection> = synchronized(lock) { connections.values.toList() }

    private fun publishConfirmation() {
        val pending = currentConnections().filter { it.executionCurrent() }.mapNotNull { it.driver.confirmation.value }
        val counts = pending.groupingBy { it.requestId }.eachCount()
        mutableConfirmation.value = pending.firstOrNull { counts[it.requestId] == 1 }
    }

    private fun close(connection: Connection) {
        connection.healthy = false
        connection.driver.cancelAll()
        disconnectScreen(connection)
        connection.job.cancel()
        detachRegistration(connection)
    }

    private fun releaseCompleted(connection: Connection) {
        connection.active = false
        disconnectScreen(connection)
        synchronized(lock) {
            if (connections[connection.profile.profileId] === connection) connections.remove(connection.profile.profileId)
        }
        detachRegistration(connection)
        publishConfirmation()
    }

    /** Release the handle through its issuing runtime, even if the global registry has changed. */
    private fun disconnectScreen(connection: Connection) {
        val registration = synchronized(connection) {
            (connection.screenHost to connection.screenBinding).also {
                connection.screenHost = null
                connection.screenBinding = null
            }
        }
        val binding = registration.second ?: return
        try {
            registration.first?.disconnectConnection(binding)
        } catch (_: Exception) {
            diagnostic(connection.profile.profileId, "SCREEN_CONNECTION_DISCONNECT_FAILED")
        }
    }

    private fun detachRegistration(connection: Connection) {
        val token = synchronized(connection) { connection.registrationId.also { connection.registrationId = null } }
        if (token != null) holder.detachConnection(connection.binding, token)
    }

    private fun diagnostic(profileId: String, code: String) {
        GatewayLog.d("OaiAdditionalDevice", "profile=${digestName(profileId).take(12)} code=$code")
    }

    private companion object {
        const val PENDING_REQUESTS_PATH = "/open-android-intelligence/v2/device-requests/pending"
        const val MAX_PENDING_REQUESTS = 128
        val REQUEST_ID = Regex("[A-Za-z0-9._~-]{1,128}")
    }
}
