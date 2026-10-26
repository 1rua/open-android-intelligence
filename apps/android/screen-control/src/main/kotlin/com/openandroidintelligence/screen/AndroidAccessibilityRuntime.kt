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
import com.openandroidintelligence.control.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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

    override fun windows(): List<ScreenTreeWindow> {
        val service = service()
        val result = ArrayList<ScreenTreeWindow>()
        try {
            val windows = service.windows.orEmpty()
            try {
                for (window in windows.take(ScreenTreeReader.MAX_WINDOWS + 1)) {
                    val root = window.root ?: continue
                    try {
                        val rect = Rect()
                        window.getBoundsInScreen(rect)
                        result += ScreenTreeWindow(ScreenWindow(window.id, window.type, window.layer,
                            window.isActive, window.isFocused, rect.toScreenBounds()), AndroidTreeNode(root))
                    } catch (failure: Exception) { recycle(root); throw failure }
                }
            } finally {
                @Suppress("DEPRECATION")
                windows.forEach { it.recycle() }
            }
            if (result.isEmpty()) {
                val root = service.rootInActiveWindow
                if (root != null) {
                    val rect = Rect(); root.getBoundsInScreen(rect)
                    result += ScreenTreeWindow(ScreenWindow(root.windowId, 1, 0, true, true, rect.toScreenBounds()), AndroidTreeNode(root))
                }
            }
            return result
        } catch (_: SecurityException) {
            result.forEach { it.root.close() }
            throw ScreenOperationException("ACCESSIBILITY_SERVICE_UNAVAILABLE")
        } catch (_: Exception) {
            result.forEach { it.root.close() }
            throw ScreenOperationException("SCREEN_READ_FAILED")
        }
    }

    override fun foregroundPackage(): String? = service().rootInActiveWindow?.let { root ->
        try { root.packageName?.toString() } finally { recycle(root) }
    }
    override fun activeWindowId(): Int? = service().rootInActiveWindow?.let { root ->
        try { root.windowId } finally { recycle(root) }
    }

    override fun nodeAction(expected: ScreenNode, action: NativeNodeAction, text: String?, generation: Long,
        beforeDispatch: () -> Unit): Boolean {
        val windows = windows()
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
        try {
            return withTimeout(durationMs + 1500) {
                currentCoroutineContext().ensureActive()
                beforeDispatch()
                checkGeneration(generation)
                suspendCancellableCoroutine { continuation ->
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resumeWithException(ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN"))
                        }
                    }
                    val accepted = try {
                        service().dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
                    } catch (_: SecurityException) { throw ScreenOperationException("ACCESSIBILITY_SERVICE_UNAVAILABLE") }
                    if (!accepted && continuation.isActive) continuation.resume(false)
                    // Android cannot recall an already dispatched stroke. Cancellation stops waiting;
                    // OAI records the uncertain result and never replays it with a new request ID.
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN")
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

internal class AndroidTreeNode(val raw: AccessibilityNodeInfo) : ScreenTreeNode {
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
    override fun child(index: Int): ScreenTreeNode? = raw.getChild(index)?.let(::AndroidTreeNode)
    override fun close() = recycle(raw)
}

private fun Rect.toScreenBounds() = ScreenBounds(left, top, right, bottom)
@Suppress("DEPRECATION")
private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
