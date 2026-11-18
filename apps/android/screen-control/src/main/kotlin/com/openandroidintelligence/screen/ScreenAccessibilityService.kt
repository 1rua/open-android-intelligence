/* Adapted from HermesAccessibilityService.kt, Hermes Relay da782a8d.
 * Copyright (c) 2026 Axiom Labs. MIT; see LICENSES/hermes-relay-MIT.txt. */
package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import com.openandroidintelligence.control.remote.PhoneLocalControlUiRegistry

/** The system binds this service only after the user enables it in Android Settings. */
class ScreenAccessibilityService : AccessibilityService() {
    private var overlay: PhoneControlOverlay? = null
    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenAccessibilityConnection.connect(this)
        overlay?.close()
        overlay = PhoneControlOverlay(this).also { PhoneLocalControlUiRegistry.current = it }
        com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current = NativePhoneScreen(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only invalidate references; never retain event source, UI text, or app content.
        if (event != null) {
            val currentWindows = windows.orEmpty()
            val overlayEvent = try { event.packageName?.toString() == packageName && currentWindows.any {
                it.id == event.windowId && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            } } finally { @Suppress("DEPRECATION") currentWindows.forEach { it.recycle() } }
            if (!overlayEvent) ScreenAccessibilityConnection.changed(event.eventType)
        }
    }

    override fun onInterrupt() { ScreenAccessibilityConnection.interrupt(this) }
    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.close(); overlay = null
        com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current?.stop()
        com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current = null
        ScreenAccessibilityConnection.disconnect(this)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        overlay?.close()
        overlay = null
        com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current?.stop()
        com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current = null
        ScreenAccessibilityConnection.disconnect(this)
        super.onDestroy()
    }
}

/** Process lifetime only; feedback interruption and binding loss invalidate local screen grants. */
object ScreenAccessibilityConnection {
    @Volatile private var reference = WeakReference<ScreenAccessibilityService>(null)
    private val sequence = AtomicLong()
    private val layoutChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val layoutEvents: kotlinx.coroutines.flow.StateFlow<Long> get() = layoutChanges
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    internal val generation: Long get() = sequence.get()
    internal val service: ScreenAccessibilityService? get() = reference.get()
    internal fun changed(eventType: Int? = null) {
        sequence.incrementAndGet()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            layoutChanges.value += 1
    }
    internal fun connect(service: ScreenAccessibilityService) {
        reference = WeakReference(service)
        changed()
    }
    internal fun interrupt(service: ScreenAccessibilityService) {
        if (reference.get() !== service) return
        // Android interrupts accessibility feedback without necessarily unbinding
        // the service. Revoke the local lease and stale references, retain the
        // system-bound instance for a later explicit local authorization.
        changed()
        listeners.forEach { runCatching { it() } }
    }
    internal fun disconnect(service: ScreenAccessibilityService) {
        if (reference.get() !== service) return
        reference.clear()
        changed()
        listeners.forEach { runCatching { it() } }
    }
    /** Called for feedback interruption as well as an actual service disconnect. */
    fun addDisconnectListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}
