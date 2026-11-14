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

/** Android 原生屏幕执行器，任务快照、SoM 与动作都共用同一真实 backend。 */
class NativePhoneScreen(private val context: Context, private val backend: ScreenBackend = AndroidScreenBackend(context)) : NativePhoneScreenPort {
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
        // window 取帧是 Android 34 起唯一有明确排除无障碍胶囊保证的系统路径。
        if (Build.VERSION.SDK_INT < 34) throw ControlRequestException("SCREEN_OVERLAY_EXCLUSION_UNSUPPORTED")
        val snapshot = withContext(Dispatchers.Main.immediate) { backend.read(ScreenReadQuery(maxNodes = 512, maxDepth = 32)) }
        if (snapshot.truncated) throw ControlRequestException("SCREEN_REDACTION_UNPROVABLE")
        request.beforeDispatch(); guard(revalidate)
        val captured = AndroidAccessibilityScreenshotRuntime(context, snapshot.activeWindowId ?: throw ControlRequestException("SCREEN_WINDOW_UNAVAILABLE")).takeScreenshot()
        val frame = (captured as? ScreenCaptureOutcome.Success)?.capture ?: throw ControlRequestException("SCREEN_CAPTURE_FAILED")
        val current = withContext(Dispatchers.Main.immediate) { backend.read(ScreenReadQuery(maxNodes = 512, maxDepth = 32)) }
        if (current.revision != snapshot.revision || current.display != snapshot.display) throw ControlRequestException("SNAPSHOT_STALE")
        return withContext(Dispatchers.Default) {
        val png = frame.pngBytes()
        val bitmap = try { BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw ControlRequestException("SCREEN_CAPTURE_ENCODING_FAILED") } finally { png.fill(0) }
        var mutable: Bitmap? = null
        try {
        val bounds = snapshot.windows.firstOrNull { it.id == snapshot.activeWindowId }?.bounds ?: throw ControlRequestException("SCREEN_WINDOW_UNAVAILABLE")
        if (bitmap.width != bounds.right - bounds.left || bitmap.height != bounds.bottom - bounds.top) throw ControlRequestException("SCREEN_CAPTURE_GEOMETRY_MISMATCH")
        mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: throw ControlRequestException("SCREEN_CAPTURE_ENCODING_FAILED")
        val mapping = mutableListOf<Map<String, Any>>()
            val canvas = Canvas(mutable)
            val redaction = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            snapshot.nodes.filter { it.password }.forEach { node -> canvas.drawRect(
                (node.bounds.left - bounds.left).toFloat(), (node.bounds.top - bounds.top).toFloat(),
                (node.bounds.right - bounds.left).toFloat(), (node.bounds.bottom - bounds.top).toFloat(), redaction) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f; textSize = 24f }
            snapshot.nodes.filter { it.windowId == snapshot.activeWindowId && !it.password && it.enabled && (it.clickable || it.editable || it.scrollable || it.longClickable) }.forEach { node ->
                val b = node.bounds
                if (b.left >= bounds.left && b.top >= bounds.top && b.right <= bounds.right && b.bottom <= bounds.bottom && b.right > b.left && b.bottom > b.top) {
                    val label = mapping.size + 1; canvas.drawRect((b.left - bounds.left).toFloat(), (b.top - bounds.top).toFloat(), (b.right - bounds.left).toFloat(), (b.bottom - bounds.top).toFloat(), paint)
                    canvas.drawText(label.toString(), (b.left - bounds.left).toFloat(), (b.top - bounds.top + 24).toFloat(), paint)
                    mapping += mapOf("label" to label, "nodeId" to node.nodeId)
                }
            }
            val bytes = ByteArrayOutputStream().use { out -> if (!mutable.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) throw ControlRequestException("SCREEN_CAPTURE_ENCODING_FAILED"); out.toByteArray() }
            guard(revalidate)
            val artifactId = UUID.randomUUID().toString()
            val version = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val finalSnapshot = withContext(Dispatchers.Main.immediate) { backend.read(ScreenReadQuery(maxNodes = 512, maxDepth = 32)) }
            if (finalSnapshot.revision != snapshot.revision || finalSnapshot.display != snapshot.display) throw ControlRequestException("SNAPSHOT_STALE")
            guard(revalidate)
            PhoneControlResult(objectValue(snapshotMap(request.scope.task, snapshot) + mapOf("mapping" to mapping,
                "semanticNodeCount" to snapshot.nodes.size, "markedNodeCount" to mapping.size, "coverage" to if (mapping.isEmpty()) "no_nodes" else "partial",
                "imageOffsetX" to bounds.left, "imageOffsetY" to bounds.top)), listOf(ControlArtifact(artifactId, "image/webp", version, bytes)))
        } finally { mutable?.recycle(); bitmap.recycle() }
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
