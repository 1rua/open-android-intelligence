package com.openandroidintelligence.screen

import android.app.KeyguardManager
import android.content.Context
import android.graphics.*
import android.os.Build
import com.openandroidintelligence.capability.ScreenCaptureOutcome
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.os.SystemClock

/** Android 原生屏幕执行器，任务快照、SoM 与动作都共用同一真实 backend。 */
class NativePhoneScreen internal constructor(
    private val context: Context,
    private val backend: ScreenBackend,
    private val frameSource: SomFrameSource,
) : NativePhoneScreenPort {
    constructor(context: Context, backend: ScreenBackend = AndroidScreenBackend(context)) :
        this(context, backend, AndroidSomFrameSource(context))
    private data class Snapshot(val task: ControlTaskScope, val screen: ScreenSnapshot)
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    @Volatile private var stopped = false
    override fun availability(): String? = when {
        stopped -> "REMOTE_EMERGENCY_STOPPED"
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false -> "SCREEN_DEVICE_LOCKED"
        else -> backend.availability()
    }
    private fun guard(revalidate: () -> Unit) { availability()?.let { throw ControlRequestException(it) }; revalidate() }
    override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation, revalidate: () -> Unit, mayRead: () -> Boolean): PhoneControlResult {
        guard(revalidate)
        return try { when (operation) {
            is ControlOperation.DumpTree -> {
                context.beforeDispatch(); val snapshot = withContext(Dispatchers.Main.immediate) { backend.read(operation.query) }; guard(revalidate)
                PhoneControlResult(objectValue(snapshotMap(context.scope.task, snapshot)))
            }
            ControlOperation.CaptureSom -> captureSom(context, revalidate)
            is ControlOperation.Screen -> {
                val saved = snapshots[operation.snapshotId]?.takeIf { it.task == context.scope.task } ?: throw ControlRequestException("SNAPSHOT_STALE")
                ScreenHostRegistry.controlLeases.withLease("remote:${context.scope.key.storageKey}", System.currentTimeMillis() + 5 * 60_000,
                        { guard(revalidate) }) {
                    val result = backend.execute(saved.screen.revision, operation.action) { guard(revalidate); context.beforeDispatch() }
                    val settled = ScreenQuietWait(ScreenAccessibilityConnection.layoutEvents).await()
                    guard(revalidate)
                    val readAllowed = mayRead()
                    val observed = if (readAllowed) runCatching { withContext(Dispatchers.Main.immediate) { backend.read() } }.getOrNull() else null
                    guard(revalidate)
                    val ui = (observed?.let { snapshotMap(context.scope.task, it) } ?: mapOf("snapshotId" to null, "foregroundPackage" to null, "nodes" to emptyList<Any>())) +
                        mapOf("settled" to settled.settled, "reason" to settled.reason, "packageName" to observed?.foregroundPackage,
                            "observationError" to if (!readAllowed) "SCREEN_READ_PERMISSION_REQUIRED" else if (observed == null) "SCREEN_STATE_UNAVAILABLE" else null)
                    PhoneControlResult(objectValue(mapOf("performed" to result.performed)), ui = objectValue(ui))
                }
            }
            else -> throw ControlRequestException("CONTROL_OPERATION_UNSUPPORTED")
        } } catch (failure: ScreenOperationException) {
            throw ControlRequestException(if (failure.code == "SCREEN_STALE_REFERENCE") "SNAPSHOT_STALE" else failure.code)
        }
    }
    private suspend fun captureSom(request: PhoneExecutionContext, revalidate: () -> Unit): PhoneControlResult {
        val query = ScreenReadQuery(maxNodes = 512, maxDepth = 32)
        val localUi = PhoneLocalControlUiRegistry.current
        val protection = withContext(Dispatchers.Main.immediate) {
            if (Build.VERSION.SDK_INT < 34) localUi?.awaitCaptureProtection(request.scope.key.storageKey)
            else localUi?.captureProtection()
        }
        currentCoroutineContext().ensureActive(); guard(revalidate)
        val snapshot = withContext(Dispatchers.Main.immediate) { backend.read(query) }
        if (snapshot.truncated) throw ControlRequestException("SCREEN_REDACTION_UNPROVABLE")
        if (Build.VERSION.SDK_INT < 34 && (localUi == null || protection?.ready != true || protection.display != snapshot.display))
            throw ControlRequestException("SCREEN_OVERLAY_EXCLUSION_UNSUPPORTED")
        suspend fun finalGuard() {
            currentCoroutineContext().ensureActive()
            guard(revalidate)
            val current = withContext(Dispatchers.Main.immediate) { backend.read(query) }
            if (current != snapshot) throw ControlRequestException("SNAPSHOT_STALE")
            if (Build.VERSION.SDK_INT < 34) {
                val now = withContext(Dispatchers.Main.immediate) { localUi?.captureProtection() }
                if (PhoneLocalControlUiRegistry.current !== localUi || now != protection || now?.ready != true)
                    throw ControlRequestException("SCREEN_LOCAL_UI_CHANGED")
            }
        }
        request.beforeDispatch(); guard(revalidate)
        val requestedAt = SystemClock.uptimeMillis()
        val frame = frameSource.capture(snapshot)
        return frame.use {
            finalGuard()
            if (frame.capturedAtUptimeMs < requestedAt || frame.capturedAtUptimeMs > SystemClock.uptimeMillis())
                throw ControlRequestException("SCREEN_CAPTURE_STALE_FRAME")
            if (!frame.overlayExcluded && (protection?.ready != true || frame.capturedAtUptimeMs < protection.changedAtUptimeMs))
                throw ControlRequestException("SCREEN_LOCAL_UI_CHANGED")
            val pendingEncoding = java.util.concurrent.atomic.AtomicReference<SomRenderedImage?>()
            var published = false
            try {
                val rendered = withContext(Dispatchers.Default) {
                    currentCoroutineContext().ensureActive()
                    SomImageRenderer.render(snapshot, frame, if (frame.overlayExcluded) emptyList() else requireNotNull(protection).maskedRegions)
                        .also { pendingEncoding.set(it) }
                }
                finalGuard()
                val artifactId = UUID.randomUUID().toString()
                val version = MessageDigest.getInstance("SHA-256").digest(rendered.bytes).joinToString("") { "%02x".format(it) }
                val result = PhoneControlResult(objectValue(snapshotMap(request.scope.task, snapshot) + mapOf(
                    "mapping" to rendered.mapping, "semanticNodeCount" to snapshot.nodes.size,
                    "markedNodeCount" to rendered.mapping.size,
                    "coverage" to if (rendered.mapping.isEmpty()) "no_nodes" else "partial",
                    "imageOffsetX" to frame.capturedBounds.left, "imageOffsetY" to frame.capturedBounds.top)),
                    listOf(ControlArtifact(artifactId, "image/webp", version, rendered.bytes)))
                currentCoroutineContext().ensureActive()
                guard(revalidate)
                published = true
                result
            } finally { pendingEncoding.getAndSet(null)?.let { if (!published) it.bytes.fill(0) } }
        }
    }
    private fun snapshotMap(task: ControlTaskScope, screen: ScreenSnapshot): Map<String, Any?> {
        val id = UUID.randomUUID().toString(); snapshots[id] = Snapshot(task, screen)
        return screen.toMap() + ("snapshotId" to id)
    }
    override fun stop() { stopped = true; snapshots.clear(); ScreenHostRegistry.controlLeases.revokeAll() }
}

private fun objectValue(value: Map<String, Any?>): ControlValue.Object {
    fun convert(item: Any?): ControlValue = when (item) {
        null -> ControlValue.Null
        is String -> ControlValue.Text(item)
        is Boolean -> ControlValue.Boolean(item)
        is Number -> ControlValue.Number(item.toString())
        is Map<*, *> -> ControlValue.Object(item.entries.associate { it.key.toString() to convert(it.value) })
        is List<*> -> ControlValue.Array(item.map(::convert))
        else -> throw ControlRequestException("SCREEN_RESPONSE_INVALID")
    }
    return convert(value) as ControlValue.Object
}
