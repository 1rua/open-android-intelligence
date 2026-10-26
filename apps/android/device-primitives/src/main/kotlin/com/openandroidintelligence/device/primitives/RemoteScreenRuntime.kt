package com.openandroidintelligence.device.primitives

import com.openandroidintelligence.capability.ScreenCapture
import com.openandroidintelligence.capability.ScreenCaptureSource
import com.openandroidintelligence.control.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.KernelPrimitiveProvider
import com.openandroidintelligence.kernel.LocalGrantContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/** Android providers share the existing pairing and device-request context; no transport lives here. */
internal class RemoteScreenRuntime(
    private val backend: ScreenBackend,
    private val capture: ScreenCaptureSource,
    private val stopProjection: () -> Unit = capture::release,
    private val captureReadiness: () -> String? = { null },
    private val resize: (ScreenCapture, Int?) -> ScreenCapture = { frame, _ -> frame },
    private val clock: () -> Long = System::currentTimeMillis,
) : ScreenHostLifecycle {
    private data class Connection(val accountId: String, val pairingId: String,
        val pairingGeneration: Long, val localRevision: () -> Long)
    @Volatile private var connection: Connection? = null
    @Volatile private var live = false
    @Volatile private var connectionGeneration = 0uL
    private val images = ScreenImageStore(clock)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val captureRequests = ConcurrentHashMap.newKeySet<String>()
    private val deadlines = ConcurrentHashMap<String, Long>()
    override val authorization = ScreenLocalAuthorization(clock, onSessionEnded = { clearResources() })

    val providers: List<KernelPrimitiveProvider> = listOf(
        provider("kernel.screen.read", ScreenPermission.READ),
        provider("kernel.screen.capture", ScreenPermission.CAPTURE),
        provider("kernel.screen.control", ScreenPermission.CONTROL),
    )

    override fun binding(): ScreenAuthorizationBinding? {
        val current = connection ?: return null
        if (!live) return null
        val revision = current.localRevision().takeIf { it >= 0 }?.toULong() ?: return null
        return ScreenAuthorizationBinding(current.accountId, current.pairingId,
            // Gateway publication revisions may change solely because availability changes.
            // The policy here is the existing persisted phone grant revision; the driver
            // independently revalidates the signed Gateway revision on every request.
            ControlRevision(current.pairingGeneration.toULong(), connectionGeneration, revision, revision))
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
        })

    @Synchronized override fun connect(accountId: String, pairingId: String, pairingGeneration: Long, localRevision: () -> Long) {
        require(accountId.isNotBlank() && pairingId.isNotBlank() && pairingGeneration >= 0)
        authorization.revoke(ScreenSessionEndReason.ACCOUNT_CHANGED)
        clearResources()
        connectionGeneration++
        live = false
        connection = Connection(accountId, pairingId, pairingGeneration, localRevision)
    }

    @Synchronized override fun connectionChanged(live: Boolean) {
        if (this.live == live) return
        this.live = live
        if (!live) {
            connectionGeneration++
            authorization.revoke(ScreenSessionEndReason.DISCONNECTED)
            clearResources()
        }
    }

    override fun refreshAuthorization() { binding()?.let { authorization.status(it) } ?: authorization.revoke(ScreenSessionEndReason.BINDING_CHANGED) }
    @Synchronized override fun disconnect() {
        live = false
        connectionGeneration++
        connection = null
        authorization.revoke(ScreenSessionEndReason.DISCONNECTED)
        clearResources()
    }
    override fun revoke() { authorization.revoke(); clearResources() }
    override fun localSnapshot(): ScreenSnapshot = backend.read()
    override fun localTarget(): ScreenTargetIdentity = target(localSnapshot())

    override fun installConfirmation(requestId: String, parameterDigest: String, expiresAtEpochMs: Long) {
        val binding = binding() ?: throw ScreenAuthorizationException("SCREEN_CONNECTION_REQUIRED")
        val session = authorization.requirePermission(binding, ScreenPermission.CONTROL)
        val expires = minOf(expiresAtEpochMs, session.expiresAtEpochMs)
        if (expires <= clock()) throw ScreenAuthorizationException("SCREEN_REQUEST_EXPIRED")
        authorization.installConfirmation(binding, requestId, UserConfirmation(requestId, parameterDigest, clock(), expires))
        deadlines[requestId] = expires
    }
    override fun finishRequest(requestId: String) { authorization.clearConfirmation(requestId); deadlines.remove(requestId) }
    override fun cancelRequest(requestId: String) { jobs[requestId]?.cancel(); images.clear(); finishRequest(requestId) }
    override fun captureStopped() {
        images.clear()
        captureRequests.forEach { jobs[it]?.cancel() }
        runCatching { stopProjection() }
    }

    private fun clearResources() {
        images.clear()
        deadlines.clear()
        jobs.values.forEach { it.cancel() }
        runCatching { stopProjection() }
    }

    private fun provider(id: String, permission: ScreenPermission) = object : KernelPrimitiveProvider {
        override val primitiveId = id
        override fun availability(): String? = try {
            val binding = binding() ?: throw ScreenAuthorizationException("SCREEN_CONNECTION_REQUIRED")
            authorization.requirePermission(binding, permission)
            if (permission == ScreenPermission.CAPTURE) captureReadiness()
                ?: if (capture.isAvailable) null else "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED"
            else backend.availability()
        } catch (denied: ScreenAuthorizationException) { denied.code }

        override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
            if (input.size > 16_384) throw ScreenOperationException("SCHEMA_INVALID")
            val args = JsonFields.obj(Json.parse(input.decodeToString())) ?: throw ScreenOperationException("SCHEMA_INVALID")
            val expected = requireBinding(context)
            val session = authorization.requirePermission(expected, permission)
            val job = currentCoroutineContext()[Job] ?: error("SCREEN_EXECUTION_CONTEXT_REQUIRED")
            if (jobs.putIfAbsent(context.correlationId, job) != null) throw ScreenOperationException("SCREEN_REQUEST_IN_PROGRESS")
            if (permission == ScreenPermission.CAPTURE) captureRequests.add(context.correlationId)
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
                    ScreenPermission.CAPTURE -> capture(context, expected, session, args).also {
                        if ((JsonFields.string(args, "operation") ?: "capture") == "capture") createdImageId = it["imageId"] as? String
                    }
                    ScreenPermission.CONTROL -> {
                        val action = action(args)
                        val digest = Json.sha256(args)
                        val expires = deadlines[context.correlationId] ?: throw ScreenAuthorizationException("SCREEN_CONFIRMATION_REQUIRED")
                        val request = ControlAuthorizationRequest(context.correlationId, ScreenNativeAction(action, digest),
                            expected.revision, null, expires)
                        fun guard() {
                            val current = requireBinding(context)
                            authorization.requirePermission(current, ScreenPermission.CONTROL)
                            if (current != expected) throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
                            when (val decision = authorization.authorizeControl(current, request, localTarget())) {
                                is ControlAuthorization.Allowed -> Unit
                                is ControlAuthorization.Denied -> throw ScreenAuthorizationException("SCREEN_${decision.reason.name}")
                            }
                        }
                        guard()
                        val outcome = backend.execute(required(args, "expectedRevision"), action, ::guard)
                        controlPerformed = outcome.performed
                        // A control grant never implicitly releases the semantic tree.
                        val mayRead = runCatching { authorization.requirePermission(requireBinding(context), ScreenPermission.READ) }.isSuccess
                        (if (mayRead) outcome else outcome.copy(screen = null, stateError = "SCREEN_READ_PERMISSION_REQUIRED")).toMap()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (requireBinding(context) != expected || authorization.requirePermission(expected, permission).sessionId != session.sessionId) {
                    throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
                }
                if (permission == ScreenPermission.CAPTURE) {
                    captureReadiness()?.let { images.clear(); throw ScreenAuthorizationException(it) }
                    if (!capture.isAvailable) { images.clear(); throw ScreenAuthorizationException("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED") }
                } else backend.availability()?.let {
                    if (controlPerformed) throw ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN")
                    throw ScreenOperationException(it)
                }
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
                captureRequests.remove(context.correlationId)
                jobs.remove(context.correlationId, job)
            }
        }
    }

    private fun requireBinding(context: LocalGrantContext): ScreenAuthorizationBinding {
        val current = binding()?.takeIf { it.accountId == context.accountId && it.pairingId == context.pairingId }
            ?: throw ScreenAuthorizationException("SCREEN_SESSION_BINDING_CHANGED")
        // Do not short-circuit on revision mismatch: status must revoke the old
        // lease and release its pixels/projection, even while this request fails.
        authorization.status(current)
        return current
    }

    private suspend fun capture(context: LocalGrantContext, binding: ScreenAuthorizationBinding,
        session: ScreenLocalSession, args: JsonValue.JObject): Map<String, Any> {
        captureReadiness()?.let { throw ScreenAuthorizationException(it) }
        if (!capture.isAvailable) { images.clear(); throw ScreenAuthorizationException("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED") }
        return when (JsonFields.string(args, "operation") ?: "capture") {
            "capture" -> {
                val maxWidth = JsonFields.int(args, "maxWidth")
                if (maxWidth != null && maxWidth !in 64..2048) throw ScreenOperationException("SCHEMA_INVALID")
                val frame = capture.capture() ?: throw ScreenOperationException("SCREEN_CAPTURE_FAILED")
                val scaled = resize(frame, maxWidth)
                currentCoroutineContext().ensureActive()
                if (!capture.isAvailable || requireBinding(context) != binding ||
                    authorization.requirePermission(binding, ScreenPermission.CAPTURE).sessionId != session.sessionId) {
                    throw ScreenAuthorizationException("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED")
                }
                images.put(binding, session.sessionId, scaled.pngBytes(), scaled.widthPixels, scaled.heightPixels, session.expiresAtEpochMs)
            }
            "read" -> images.read(binding, session.sessionId, required(args, "imageId"), JsonFields.int(args, "offset") ?: 0)
            "release" -> images.release(binding, session.sessionId, required(args, "imageId"))
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
