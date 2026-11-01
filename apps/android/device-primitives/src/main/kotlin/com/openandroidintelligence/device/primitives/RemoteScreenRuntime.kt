package com.openandroidintelligence.device.primitives

import com.openandroidintelligence.capability.ScreenCapture
import com.openandroidintelligence.capability.ScreenCaptureSource
import com.openandroidintelligence.control.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.KernelPrimitiveProvider
import com.openandroidintelligence.kernel.LocalGrantContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** Global policy decisions arrive from the trusted driver, never from remote parameters. */
internal class RemoteScreenRuntime(
    private val backend: ScreenBackend,
    private val capture: ScreenCaptureSource,
    private val stopProjection: () -> Unit = capture::release,
    private val captureReadiness: () -> String? = { null },
    private val resize: (ScreenCapture, Int?) -> ScreenCapture = { frame, _ -> frame },
    private val clock: () -> Long = System::currentTimeMillis,
    private val controlLeases: ScreenControlLeaseManager = ScreenHostRegistry.controlLeases,
) : ScreenHostLifecycle {
    private data class Connection(val accountId: String, val pairingId: String,
        val pairingGeneration: Long, val localRevision: () -> Long,
        val generation: ULong,
        val imageScope: String = UUID.randomUUID().toString())
    private data class RequestKey(
        val connectionGeneration: ULong,
        val accountId: String,
        val pairingId: String,
        val pairingGeneration: ULong,
        val requestId: String,
    ) {
        // The fixed local generation prefix is unambiguous even when a raw ID contains ':'.
        val leaseId: String get() = "screen:$connectionGeneration:$requestId"
    }
    private class RequestApproval(
        val key: RequestKey,
        val binding: ScreenAuthorizationBinding,
        val revocation: Long,
        val permissions: Set<ScreenPermission>,
        val parameterDigest: String,
        val installedAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val imageScope: String,
        val revalidate: () -> Unit,
    )
    @Volatile private var selected: Connection? = null
    private val connections = ConcurrentHashMap<ULong, Connection>()
    private val nextConnectionGeneration = AtomicLong()
    private val images = ScreenImageStore(clock)
    private val jobs = ConcurrentHashMap<RequestKey, Job>()
    private val captureRequests = ConcurrentHashMap.newKeySet<RequestKey>()
    private val requestApprovals = ConcurrentHashMap<RequestKey, RequestApproval>()
    private val requestLock = Any()
    // LocalGrantContext lacks the opaque generation. Until that contract is extended,
    // reject cross-connection raw-ID collisions rather than selecting an ambiguous owner.
    // Reservations remain until both the approval and its executing job are gone.
    private val requestOwners = mutableMapOf<String, RequestKey>()
    private val requestRevocation = AtomicLong()

    // Retained for the old local screen UI only. Expiry, replacement and binding changes
    // of that UI session cannot veto a current request authorized by the global policy.
    override val authorization = ScreenLocalAuthorization(clock, onSessionEnded = { reason ->
        if (reason == ScreenSessionEndReason.REVOKED) clearResources()
    })

    val providers: List<KernelPrimitiveProvider> = listOf(
        provider("kernel.screen.read", ScreenPermission.READ),
        provider("kernel.screen.capture", ScreenPermission.CAPTURE),
        provider("kernel.screen.control", ScreenPermission.CONTROL),
    )

    override fun binding(): ScreenAuthorizationBinding? = selected?.let { current ->
        if (connections[current.generation] === current) connectionBinding(current) else null
    }

    private fun connectionBinding(current: Connection): ScreenAuthorizationBinding? {
        val revision = current.localRevision().takeIf { it >= 0 }?.toULong() ?: return null
        return ScreenAuthorizationBinding(current.accountId, current.pairingId,
            ControlRevision(current.pairingGeneration.toULong(), current.generation, revision, revision))
    }

    private fun registeredConnection(binding: ScreenAuthorizationBinding): Connection =
        connections[binding.revision.connectionGeneration]?.takeIf {
            it.accountId == binding.accountId && it.pairingId == binding.pairingId &&
                it.pairingGeneration.toULong() == binding.revision.pairingGeneration
        } ?: throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")

    private fun requireConnection(binding: ScreenAuthorizationBinding): Connection =
        registeredConnection(binding).takeIf { connectionBinding(it) == binding }
            ?: throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")

    private fun uniqueBinding(): ScreenAuthorizationBinding {
        val registered = connections.values.toList()
        if (registered.isEmpty()) throw ScreenAuthorizationException("SCREEN_CONNECTION_REQUIRED")
        if (registered.size != 1) throw ScreenAuthorizationException("SCREEN_CONNECTION_BINDING_REQUIRED")
        return connectionBinding(registered.single()) ?: throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
    }

    override fun status(): ScreenHostStatus = ScreenHostStatus(binding(), binding()?.let(authorization::status),
        backend.availability(), captureReadiness() ?: if (capture.isAvailable) null else "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED",
        providers.associate { provider ->
            val permission = when (provider.primitiveId) {
                "kernel.screen.read" -> ScreenPermission.READ
                "kernel.screen.capture" -> ScreenPermission.CAPTURE
                else -> ScreenPermission.CONTROL
            }
            permission to provider.availability()
        }, stopGeneration = requestRevocation.get())

    @Synchronized override fun registerConnection(accountId: String, pairingId: String,
        pairingGeneration: Long, localRevision: () -> Long): ScreenAuthorizationBinding {
        require(accountId.isNotBlank() && pairingId.isNotBlank() && pairingGeneration >= 0)
        val generation = nextConnectionGeneration.incrementAndGet().also { check(it > 0L) }.toULong()
        val current = Connection(accountId, pairingId, pairingGeneration, localRevision, generation)
        val binding = connectionBinding(current) ?: throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
        connections[generation] = current
        return binding
    }

    @Synchronized override fun selectConnection(binding: ScreenAuthorizationBinding) {
        selected = registeredConnection(binding)
    }

    // Compatibility for the foreground runtime: a chat change only changes presentation.
    // Existing authenticated background registrations are left to their owning drivers.
    @Synchronized override fun connect(accountId: String, pairingId: String, pairingGeneration: Long, localRevision: () -> Long) {
        val binding = registerConnection(accountId, pairingId, pairingGeneration, localRevision)
        selected = requireConnection(binding)
    }

    @Synchronized override fun connectionChanged(live: Boolean) {
        val current = selected ?: return
        if (live) {
            if (connections[current.generation] !== current) {
                val next = registerConnection(current.accountId, current.pairingId, current.pairingGeneration, current.localRevision)
                selected = requireConnection(next)
            }
        } else removeConnection(current)
    }

    override fun refreshAuthorization() {
        connections.values.toList().forEach { current ->
            if (connectionBinding(current) == null || requestApprovals.values.any {
                it.binding.revision.connectionGeneration == current.generation && runCatching { validate(it) }.isFailure
            }) clearConnectionRequests(current)
        }
    }

    @Synchronized override fun disconnectConnection(binding: ScreenAuthorizationBinding) {
        val current = connections[binding.revision.connectionGeneration] ?: return
        // A stale policy revision may still revoke its own connection, never another owner.
        if (current.accountId != binding.accountId || current.pairingId != binding.pairingId ||
            current.pairingGeneration.toULong() != binding.revision.pairingGeneration) {
            throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
        }
        removeConnection(current)
        if (selected === current) selected = null
    }

    @Synchronized override fun disconnect() {
        selected?.let(::removeConnection)
        selected = null
    }

    @Synchronized override fun disconnectAll() {
        connections.clear()
        selected = null
        controlLeases.connectionTimedOut()
        clearResources()
    }

    private fun removeConnection(current: Connection) {
        connections.remove(current.generation, current)
        clearConnectionRequests(current)
    }

    private fun clearConnectionRequests(current: Connection) {
        requestApprovals.entries.toList().forEach { (key, approval) ->
            if (key.connectionGeneration == current.generation && removeApproval(key, approval)) {
                controlLeases.connectionTimedOut(key.leaseId)
                jobs[key]?.cancel()
            }
        }
        images.clearScope(current.imageScope)
        if (connections.isEmpty()) runCatching { stopProjection() }
    }

    override fun revoke() { authorization.revoke(); clearResources() }
    override fun localSnapshot(): ScreenSnapshot = backend.read()
    override fun localTarget(): ScreenTargetIdentity = target(localSnapshot())

    override fun installConfirmation(requestId: String, parameterDigest: String, expiresAtEpochMs: Long) {
        // Compatibility for the old manual driver; never used by the global-policy path.
        val expected = uniqueBinding()
        installControlAuthorization(requestId, parameterDigest, expiresAtEpochMs) {
            authorization.requirePermission(expected, ScreenPermission.CONTROL)
        }
    }

    override fun installControlAuthorization(requestId: String, parameterDigest: String,
        expiresAtEpochMs: Long, revalidate: () -> Unit) = installRequestAuthorization(
        requestId, setOf(ScreenPermission.CONTROL), expiresAtEpochMs, parameterDigest, revalidate)

    override fun installRequestAuthorization(requestId: String, permissions: Set<ScreenPermission>,
        expiresAtEpochMs: Long, parameterDigest: String, revalidate: () -> Unit) = installRequestAuthorization(
        requestId, permissions, expiresAtEpochMs, parameterDigest, uniqueBinding(), revalidate)

    override fun installRequestAuthorization(requestId: String, permissions: Set<ScreenPermission>,
        expiresAtEpochMs: Long, parameterDigest: String, binding: ScreenAuthorizationBinding, revalidate: () -> Unit) {
        require(requestId.isNotBlank() && permissions.isNotEmpty() && parameterDigest.isNotBlank())
        val admittedRevision = requestRevocation.get()
        val owner = registeredConnection(binding)
        // Registration identifies a live connection. Each freshly allowed request snapshots
        // the current local revision, so policy edits do not require a second registration.
        val expected = connectionBinding(owner) ?: throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
        val imageScope = owner.imageScope
        revalidate()
        val now = clock()
        if (expiresAtEpochMs <= now) throw ScreenAuthorizationException("SCREEN_REQUEST_EXPIRED")
        requireConnection(expected)
        val key = requestKey(requestId, binding)
        val approval = RequestApproval(key, expected, admittedRevision, permissions.toSet(), parameterDigest, now, expiresAtEpochMs, imageScope, revalidate)
        synchronized(requestLock) {
            val previous = requestOwners[requestId]
            if (previous != null) throw ScreenOperationException(if (previous == key)
                "SCREEN_REQUEST_IN_PROGRESS" else "SCREEN_REQUEST_ID_COLLISION")
            requestOwners[requestId] = key
            requestApprovals[key] = approval
        }
        try { validate(approval) }
        catch (failure: Exception) { removeApproval(key, approval); throw failure }
        // A connect/revoke may have cleared this entry while the policy callback ran.
        if (requestApprovals[key] !== approval) throw ScreenAuthorizationException("SCREEN_REQUEST_AUTHORIZATION_REVOKED")
    }

    private fun requestKey(requestId: String, binding: ScreenAuthorizationBinding) = RequestKey(
        binding.revision.connectionGeneration, binding.accountId, binding.pairingId,
        binding.revision.pairingGeneration, requestId)

    override fun heartbeatControl(requestId: String): Long =
        throw ScreenAuthorizationException("SCREEN_CONNECTION_BINDING_REQUIRED")
    override fun heartbeatControl(requestId: String, binding: ScreenAuthorizationBinding): Long {
        val key = requestKey(requestId, binding)
        if (!ownsRequest(key)) throw ScreenOperationException("SCREEN_CONTROL_LEASE_INACTIVE")
        return controlLeases.heartbeat(key.leaseId)
    }

    private fun ownsRequest(key: RequestKey): Boolean = synchronized(requestLock) {
        requestApprovals.containsKey(key) || jobs.containsKey(key)
    }

    override fun finishRequest(requestId: String): Unit =
        throw ScreenAuthorizationException("SCREEN_CONNECTION_BINDING_REQUIRED")
    override fun finishRequest(requestId: String, binding: ScreenAuthorizationBinding) {
        val key = requestKey(requestId, binding)
        // Use the full owner tuple, never the selected chat or a raw-ID lookup.
        if (!ownsRequest(key)) return
        controlLeases.cancel(key.leaseId)
        removeApproval(key)
    }
    override fun cancelRequest(requestId: String): Unit =
        throw ScreenAuthorizationException("SCREEN_CONNECTION_BINDING_REQUIRED")
    override fun cancelRequest(requestId: String, binding: ScreenAuthorizationBinding) {
        val key = requestKey(requestId, binding)
        if (!ownsRequest(key)) return
        controlLeases.cancel(key.leaseId)
        jobs[key]?.cancel()
        requestApprovals[key]?.let { images.clearScope(it.imageScope) }
        removeApproval(key)
    }

    private fun removeApproval(key: RequestKey, expected: RequestApproval? = null): Boolean = synchronized(requestLock) {
        val removed = if (expected == null) requestApprovals.remove(key) != null
            else requestApprovals.remove(key, expected)
        releaseOwnerIfIdle(key)
        removed
    }

    /** Called only while requestLock is held. In-flight cancellation cannot free another owner's ID. */
    private fun releaseOwnerIfIdle(key: RequestKey) {
        if (requestApprovals[key] == null && jobs[key] == null) requestOwners.remove(key.requestId, key)
    }

    override fun captureStopped() {
        images.clear()
        captureRequests.forEach { jobs[it]?.cancel() }
        runCatching { stopProjection() }
    }

    private fun clearResources() {
        requestRevocation.incrementAndGet()
        images.clear()
        controlLeases.revokeAll()
        synchronized(requestLock) {
            requestApprovals.clear()
            requestOwners.values.toList().forEach(::releaseOwnerIfIdle)
        }
        jobs.values.forEach { it.cancel() }
        runCatching { stopProjection() }
    }

    private fun provider(id: String, permission: ScreenPermission) = object : KernelPrimitiveProvider {
        override val primitiveId = id
        // Publication describes connection/system readiness. Each call still needs its
        // own trusted policy decision; pending/manual policy must be able to reach driver.
        override fun availability(): String? = when {
            connections.values.none { connectionBinding(it) != null } -> "SCREEN_CONNECTION_REQUIRED"
            permission == ScreenPermission.CAPTURE -> captureReadiness()
                ?: if (capture.isAvailable) null else "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED"
            else -> backend.availability()
        }

        override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
            if (input.size > 16_384) throw ScreenOperationException("SCHEMA_INVALID")
            val args = JsonFields.obj(Json.parse(input.decodeToString())) ?: throw ScreenOperationException("SCHEMA_INVALID")
            val digest = Json.sha256(args)
            val approval = synchronized(requestLock) {
                requestOwners[context.correlationId]?.let(requestApprovals::get)
            } ?: throw ScreenAuthorizationException("SCREEN_REQUEST_AUTHORIZATION_REQUIRED")
            val key = approval.key
            fun guard() {
                if (requestApprovals[key] !== approval) {
                    throw ScreenAuthorizationException("SCREEN_REQUEST_AUTHORIZATION_REVOKED")
                }
                if (permission !in approval.permissions) throw ScreenAuthorizationException("SCREEN_PERMISSION_REQUIRED")
                if (approval.parameterDigest != digest) throw ScreenAuthorizationException("SCREEN_CONFIRMATION_MISMATCH")
                if (context.accountId != approval.binding.accountId || context.pairingId != approval.binding.pairingId) {
                    throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
                }
                validate(approval)
                if (requestApprovals[key] !== approval) {
                    throw ScreenAuthorizationException("SCREEN_REQUEST_AUTHORIZATION_REVOKED")
                }
                if (permission == ScreenPermission.CAPTURE) {
                    captureReadiness()?.let { images.clear(); throw ScreenAuthorizationException(it) }
                    if (!capture.isAvailable) { images.clear(); throw ScreenAuthorizationException("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED") }
                } else backend.availability()?.let { throw ScreenOperationException(it) }
            }
            guard()
            val job = currentCoroutineContext()[Job] ?: error("SCREEN_EXECUTION_CONTEXT_REQUIRED")
            synchronized(requestLock) {
                if (requestApprovals[key] !== approval) throw ScreenAuthorizationException("SCREEN_REQUEST_AUTHORIZATION_REVOKED")
                if (jobs.putIfAbsent(key, job) != null) throw ScreenOperationException("SCREEN_REQUEST_IN_PROGRESS")
            }
            if (permission == ScreenPermission.CAPTURE) captureRequests.add(key)
            var controlPerformed = false
            var createdImageId: String? = null
            var delivered = false
            try {
                currentCoroutineContext().ensureActive()
                val result: Map<String, Any?> = when (permission) {
                    ScreenPermission.READ -> {
                        val query = JsonFields.obj(JsonFields.field(args, "query"))
                        backend.read(ScreenReadQuery(
                            operation = JsonFields.string(args, "operation") ?: "snapshot",
                            maxNodes = JsonFields.int(args, "maxNodes") ?: 128,
                            maxDepth = JsonFields.int(args, "maxDepth") ?: 24,
                            maxBytes = JsonFields.int(args, "maxBytes") ?: 48_000,
                            text = JsonFields.string(query, "text"), resourceId = JsonFields.string(query, "resourceId"),
                            description = JsonFields.string(query, "description"), previousHash = JsonFields.string(args, "previousHash"),
                        )).toMap()
                    }
                    ScreenPermission.CAPTURE -> capture(approval, args, ::guard).also {
                        if ((JsonFields.string(args, "operation") ?: "capture") == "capture") createdImageId = it["imageId"] as? String
                    }
                    ScreenPermission.CONTROL -> {
                        val action = action(args)
                        val outcome = controlLeases.withLease(key.leaseId, approval.expiresAtEpochMs, ::guard) { lease ->
                            val result = backend.execute(required(args, "expectedRevision"), action, lease::ensureCurrent)
                            controlPerformed = result.performed
                            result
                        }
                        // CONTROL never grants semantic screen data implicitly.
                        (if (ScreenPermission.READ in approval.permissions) outcome else
                            outcome.copy(screen = null, stateError = "SCREEN_READ_PERMISSION_REQUIRED")).toMap()
                    }
                }
                currentCoroutineContext().ensureActive()
                guard()
                val output = Json.canonical(Json.of(result)).encodeToByteArray()
                if (output.size > 60_000) throw ScreenOperationException("SCREEN_RESPONSE_TOO_LARGE")
                currentCoroutineContext().ensureActive()
                delivered = true
                return output
            } catch (failure: Exception) {
                if (controlPerformed && failure !is CancellationException) throw ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN")
                throw failure
            } finally {
                if (!delivered) createdImageId?.let(images::discard)
                captureRequests.remove(key)
                synchronized(requestLock) {
                    jobs.remove(key, job)
                    releaseOwnerIfIdle(key)
                }
            }
        }
    }

    private fun validate(approval: RequestApproval) {
        fun checkBindingAndDeadline() {
            if (approval.revocation != requestRevocation.get()) {
                throw ScreenAuthorizationException("SCREEN_REQUEST_AUTHORIZATION_REVOKED")
            }
            if (requireConnection(approval.binding).imageScope != approval.imageScope) {
                throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
            }
            val now = clock()
            if (now < approval.installedAtEpochMs || now >= approval.expiresAtEpochMs) {
                throw ScreenAuthorizationException("SCREEN_REQUEST_EXPIRED")
            }
        }
        checkBindingAndDeadline()
        approval.revalidate()
        checkBindingAndDeadline()
    }

    private suspend fun capture(approval: RequestApproval, args: JsonValue.JObject, guard: () -> Unit): Map<String, Any> {
        guard()
        return when (JsonFields.string(args, "operation") ?: "capture") {
            "capture" -> {
                val maxWidth = JsonFields.int(args, "maxWidth")
                if (maxWidth != null && maxWidth !in 64..2048) throw ScreenOperationException("SCHEMA_INVALID")
                val frame = capture.capture() ?: throw ScreenOperationException("SCREEN_CAPTURE_FAILED")
                val scaled = resize(frame, maxWidth)
                currentCoroutineContext().ensureActive()
                guard()
                images.put(approval.binding, approval.imageScope, scaled.pngBytes(), scaled.widthPixels,
                    scaled.heightPixels, approval.expiresAtEpochMs)
            }
            "read" -> images.read(approval.binding, approval.imageScope, required(args, "imageId"), JsonFields.int(args, "offset") ?: 0)
            "release" -> images.release(approval.binding, approval.imageScope, required(args, "imageId"))
            else -> throw ScreenOperationException("SCHEMA_INVALID")
        }
    }

    private fun target(snapshot: ScreenSnapshot): ScreenTargetIdentity {
        val packageName = snapshot.foregroundPackage ?: throw ScreenOperationException("SCREEN_WINDOW_UNAVAILABLE")
        val windowId = snapshot.activeWindowId ?: throw ScreenOperationException("SCREEN_WINDOW_UNAVAILABLE")
        val dimensions = "${snapshot.display.widthPixels}:${snapshot.display.heightPixels}:${snapshot.display.rotation}"
        val generation = MessageDigest.getInstance("SHA-256").digest("$packageName:$windowId:$dimensions".encodeToByteArray())
            .take(8).fold(0uL) { value, byte -> (value shl 8) or byte.toUByte().toULong() }
        return ScreenTargetIdentity(packageName, windowId.toString(), generation)
    }

    private fun required(args: JsonValue.JObject, key: String) = JsonFields.string(args, key)
        ?.takeIf { it.isNotBlank() } ?: throw ScreenOperationException("SCHEMA_INVALID")
    private fun number(args: JsonValue.JObject, key: String) = JsonFields.int(args, key) ?: throw ScreenOperationException("SCHEMA_INVALID")
    private fun action(args: JsonValue.JObject): ScreenAction = when (required(args, "action")) {
        "click" -> ScreenAction.Click(JsonFields.string(args, "nodeId"), JsonFields.int(args, "x"), JsonFields.int(args, "y"))
        "longPress" -> ScreenAction.LongPress(JsonFields.string(args, "nodeId"), JsonFields.int(args, "x"), JsonFields.int(args, "y"), JsonFields.long(args, "durationMs") ?: 600)
        "setText" -> ScreenAction.SetText(required(args, "nodeId"), JsonFields.string(args, "text") ?: throw ScreenOperationException("SCHEMA_INVALID"))
        "scroll" -> ScreenAction.Scroll(required(args, "nodeId"), required(args, "direction"))
        "swipe" -> ScreenAction.Swipe(number(args,"x"),number(args,"y"),number(args,"endX"),number(args,"endY"),JsonFields.long(args,"durationMs") ?: 400)
        "drag" -> ScreenAction.Drag(number(args,"x"),number(args,"y"),number(args,"endX"),number(args,"endY"),JsonFields.long(args,"durationMs") ?: 800)
        "global" -> ScreenAction.Global(required(args, "globalAction"))
        "launchApp" -> ScreenAction.LaunchApp(required(args, "packageName"))
        else -> throw ScreenOperationException("SCHEMA_INVALID")
    }
}
