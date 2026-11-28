/* Adapted from ScreenReader.kt / ActionExecutor.kt / HermesAccessibilityService.kt,
 * Hermes Relay da782a8d74eb6631ea2f097b3cc69c6452ec33de.
 * Copyright (c) 2026 Axiom Labs. MIT; see LICENSES/hermes-relay-MIT.txt. */
package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.LocalUiCaptureProtection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidAccessibilityRuntime(private val context: Context) : ScreenRuntime {
    private fun service(): ScreenAccessibilityService = ScreenAccessibilityConnection.service
        ?: throw ScreenOperationException("ACCESSIBILITY_SERVICE_UNAVAILABLE")

    override fun availability(): String? = when {
        ScreenAccessibilityConnection.service == null -> "ACCESSIBILITY_SERVICE_UNAVAILABLE"
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true -> "SCREEN_DEVICE_LOCKED"
        else -> null
    }

    override fun generation(): Long = ScreenAccessibilityConnection.generation

    @Suppress("DEPRECATION")
    override fun display(): ScreenDisplay {
        val manager = context.getSystemService(WindowManager::class.java)
            ?: throw ScreenOperationException("SCREEN_DISPLAY_UNAVAILABLE")
        val bounds = manager.maximumWindowMetrics.bounds
        return ScreenDisplay(bounds.width(), bounds.height(), manager.defaultDisplay.rotation)
    }

    override fun windows(): ScreenWindowCapture = readWindows(null)

    override fun windowsForDisplaySom(protection: LocalUiCaptureProtection): ScreenWindowCapture = readWindows(protection)

    private fun readWindows(protection: LocalUiCaptureProtection?): ScreenWindowCapture {
        val service = service()
        val result = ArrayList<ScreenTreeWindow>()
        val states = ArrayList<ScreenWindowState>()
        try {
            val windows = service.windows
            try {
                for (window in windows) {
                    val root = try { window.root } catch (_: RuntimeException) { null }
                    var adopted = false
                    try {
                        val id = window.id
                        val type = window.type
                        val layer = window.layer
                        val active = window.isActive
                        val focused = window.isFocused
                        val rect = Rect()
                        window.getBoundsInScreen(rect)
                        val packageName = try { root?.packageName?.toString() } catch (_: RuntimeException) { null }
                        val bounds = rect.toScreenBounds()
                        states += ScreenWindowState(id, type, layer, active, focused, bounds, packageName)
                        if (type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && root != null &&
                            result.size < ScreenTreeReader.MAX_WINDOWS + 1) {
                            result += ScreenTreeWindow(
                                ScreenWindow(id, type, layer, active, focused, bounds),
                                AndroidTreeNode(root, requireCompleteChildren = protection != null),
                                packageName,
                            )
                            adopted = true
                        }
                    } finally {
                        if (!adopted) root?.let(::recycle)
                    }
                }
            } finally {
                @Suppress("DEPRECATION")
                windows.forEach { it.recycle() }
            }
            // 仅系统列表为空时读取活动根，避免把已过滤的浮层或不可读窗口重新引入业务树。
            if (windows.isEmpty()) {
                if (protection != null) throw ScreenOperationException("SCREEN_REDACTION_UNPROVABLE")
                val root = service.rootInActiveWindow
                if (root != null) {
                    try {
                        val rect = Rect(); root.getBoundsInScreen(rect)
                        result += ScreenTreeWindow(ScreenWindow(root.windowId, 1, 0, true, true, rect.toScreenBounds()),
                            AndroidTreeNode(root), root.packageName?.toString())
                    } catch (failure: Exception) {
                        recycle(root)
                        throw failure
                    }
                }
                // 活跃窗口回退不是同一轮系统窗口枚举，不能据此建立乐观指纹基线。
                return ScreenWindowCapture(result, observed = null)
            }
            val observed = ScreenAccessibilityConnection.classifyWindows(service, states)
            // 整屏取帧会包含树外的像素。只豁免已确认归属且整个窗口落在保护区域内的本机浮层。
            if (protection != null && states.any { state ->
                    if (state.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        state.id !in observed.ownOverlayIds || !protection.covers(state.bounds)
                    } else state.packageName.isNullOrBlank()
                }) throw ScreenOperationException("SCREEN_REDACTION_UNPROVABLE")
            return ScreenWindowCapture(result, observed.takeUnless { observation ->
                // 已确认的本机浮层允许暂时无根。其他窗口身份未知时仍不建立目标基线。
                observation.targets.any { it.packageName.isNullOrBlank() }
            })
        } catch (failure: Exception) {
            result.forEach { it.root.close() }
            throw when (failure) {
                is ScreenOperationException -> failure
                is SecurityException -> ScreenOperationException("ACCESSIBILITY_SERVICE_UNAVAILABLE")
                else -> ScreenOperationException("SCREEN_READ_FAILED")
            }
        }
    }

    override fun nodeAction(expected: ScreenNode, action: NativeNodeAction, text: String?, generation: Long,
        beforeDispatch: () -> Unit): Boolean {
        val windows = windows().windows
        val parts = expected.nodeId.substringAfter(':', "").split('.')
        if (parts.firstOrNull() != "0" || parts.size > ScreenTreeReader.MAX_DEPTH + 1) {
            windows.forEach { it.root.close() }
            throw ScreenOperationException("SCREEN_STALE_REFERENCE")
        }
        val children = ArrayList<ScreenTreeNode>()
        try {
            var target = windows.singleOrNull { it.window.id == expected.windowId }?.root
                ?: throw ScreenOperationException("SCREEN_STALE_REFERENCE")
            for (part in parts.drop(1)) {
                val index = part.toIntOrNull() ?: throw ScreenOperationException("SCREEN_STALE_REFERENCE")
                if (index !in 0 until target.childCount) throw ScreenOperationException("SCREEN_STALE_REFERENCE")
                target = target.child(index) ?: throw ScreenOperationException("SCREEN_STALE_REFERENCE")
                children += target
            }
            val node = target as AndroidTreeNode
            if (!node.raw.refresh() || !node.visible || ScreenTreeReader().describe(node, expected.nodeId, expected.windowId,
                    expected.parentId, expected.depth) != expected) throw ScreenOperationException("SCREEN_STALE_REFERENCE")
            checkGeneration(generation)
            val nativeAction = when (action) {
                NativeNodeAction.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
                NativeNodeAction.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                NativeNodeAction.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
                NativeNodeAction.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                NativeNodeAction.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                NativeNodeAction.SCROLL_UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                NativeNodeAction.SCROLL_DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                NativeNodeAction.SCROLL_LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                NativeNodeAction.SCROLL_RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            }
            val arguments = if (action == NativeNodeAction.SET_TEXT) Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            } else null
            // Resolving and refreshing can cross a local permission expiry/revocation.
            beforeDispatch()
            checkGeneration(generation)
            return node.raw.performAction(nativeAction, arguments)
        } catch (failure: ScreenOperationException) { throw failure }
        catch (failure: ScreenAuthorizationException) { throw failure }
        catch (_: SecurityException) { throw ScreenOperationException("ACCESSIBILITY_SERVICE_UNAVAILABLE") }
        catch (_: IllegalStateException) { throw ScreenOperationException("SCREEN_STALE_REFERENCE") }
        finally {
            children.asReversed().forEach { it.close() }
            windows.forEach { it.root.close() }
        }
    }

    override suspend fun gesture(points: List<Pair<Int, Int>>, durationMs: Long, generation: Long,
        beforeDispatch: () -> Unit, drag: Boolean): Boolean {
        val path = Path().apply {
            moveTo(points.first().first.toFloat(), points.first().second.toFloat())
            for (point in points.drop(1)) lineTo(point.first.toFloat(), point.second.toFloat())
        }
        if (!drag) {
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            return dispatch(stroke, durationMs, generation, beforeDispatch)
        }
        // Android's continued stroke holds the finger down before moving the same pointer.
        val holdPath = Path().apply { moveTo(points.first().first.toFloat(), points.first().second.toFloat()) }
        val hold = GestureDescription.StrokeDescription(holdPath, 0, 500, true)
        if (!dispatch(hold, 500, generation, beforeDispatch)) return false
        // The hold may already have triggered an application's long-click. A failure
        // before/during the continuation must never claim that no operation happened.
        try {
            val movement = hold.continueStroke(path, 0, durationMs, false)
            if (!dispatch(movement, durationMs, generation, beforeDispatch)) throw ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN")
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN") }
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription, durationMs: Long,
        generation: Long, beforeDispatch: () -> Unit): Boolean {
        val settled = CompletableDeferred<Unit>()
        var dispatched = false
        try {
            return withTimeout(durationMs + 1500) {
                currentCoroutineContext().ensureActive()
                beforeDispatch()
                checkGeneration(generation)
                suspendCancellableCoroutine { continuation ->
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            settled.complete(Unit)
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            settled.complete(Unit)
                            if (continuation.isActive) continuation.resumeWithException(ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN"))
                        }
                    }
                    val accepted = try {
                        service().dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
                    } catch (_: SecurityException) { throw ScreenOperationException("ACCESSIBILITY_SERVICE_UNAVAILABLE") }
                    dispatched = accepted
                    if (!accepted && continuation.isActive) continuation.resume(false)
                    // Android cannot recall an accepted stroke. Cancellation cleanup retains
                    // control ownership until the system callback or a bounded fallback.
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN")
        } finally {
            if (!currentCoroutineContext().isActive && dispatched && !settled.isCompleted) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(durationMs + 1500) { settled.await() }
                }
            }
        }
    }

    override fun global(action: String, generation: Long, beforeDispatch: () -> Unit): Boolean {
        checkGeneration(generation)
        val nativeAction = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quickSettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw ScreenOperationException("SCHEMA_INVALID")
        }
        val accessibility = service()
        beforeDispatch()
        checkGeneration(generation)
        return try { accessibility.performGlobalAction(nativeAction) }
        catch (_: SecurityException) { throw ScreenOperationException("ACCESSIBILITY_SERVICE_UNAVAILABLE") }
    }

    override fun launch(packageName: String, generation: Long, beforeDispatch: () -> Unit): Boolean {
        checkGeneration(generation)
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw ScreenOperationException("SCREEN_APP_NOT_FOUND")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val accessibility = service()
        beforeDispatch()
        checkGeneration(generation)
        return try {
            accessibility.startActivity(intent)
            true
        } catch (_: SecurityException) { throw ScreenOperationException("SCREEN_APP_LAUNCH_DENIED") }
        catch (_: android.content.ActivityNotFoundException) { throw ScreenOperationException("SCREEN_APP_NOT_FOUND") }
    }

    private fun checkGeneration(expected: Long) {
        availability()?.let { throw ScreenOperationException(it) }
        if (generation() != expected) throw ScreenOperationException("SCREEN_STALE_REFERENCE")
    }
}

internal class AndroidTreeNode(
    val raw: AccessibilityNodeInfo,
    private val requireCompleteChildren: Boolean = false,
) : ScreenTreeNode {
    override val text get() = raw.text?.toString()
    override val description get() = raw.contentDescription?.toString()
    override val resourceId get() = raw.viewIdResourceName
    override val className get() = raw.className?.toString()
    override val bounds get() = Rect().also { raw.getBoundsInScreen(it) }.toScreenBounds()
    override val visible get() = raw.isVisibleToUser
    override val clickable get() = raw.isClickable
    override val longClickable get() = raw.isLongClickable
    override val scrollable get() = raw.isScrollable
    override val editable get() = raw.isEditable
    override val focused get() = raw.isFocused
    override val selected get() = raw.isSelected
    override val enabled get() = raw.isEnabled
    override val password get() = raw.isPassword
    override val childCount get() = raw.childCount
    override fun child(index: Int): ScreenTreeNode? {
        val child = try { raw.getChild(index) } catch (failure: RuntimeException) {
            if (requireCompleteChildren) throw ScreenOperationException("SCREEN_REDACTION_UNPROVABLE")
            throw failure
        }
        if (child == null && requireCompleteChildren) throw ScreenOperationException("SCREEN_REDACTION_UNPROVABLE")
        return child?.let { AndroidTreeNode(it, requireCompleteChildren) }
    }
    override fun close() = recycle(raw)
}

private fun LocalUiCaptureProtection.covers(bounds: ScreenBounds): Boolean =
    ready && bounds.left < bounds.right && bounds.top < bounds.bottom && maskedRegions.any { mask ->
        mask.left <= bounds.left && mask.top <= bounds.top && mask.right >= bounds.right && mask.bottom >= bounds.bottom
    }

private fun Rect.toScreenBounds() = ScreenBounds(left, top, right, bottom)
@Suppress("DEPRECATION")
private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
