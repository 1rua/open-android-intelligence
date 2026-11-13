/* Adapted from HermesAccessibilityService.kt, Hermes Relay da782a8d.
 * Copyright (c) 2026 Axiom Labs. MIT; see LICENSES/hermes-relay-MIT.txt. */
package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** The system binds this service only after the user enables it in Android Settings. */
class ScreenAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenAccessibilityConnection.connect(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only invalidate references; never retain event source, UI text, or app content.
        if (event != null) ScreenAccessibilityConnection.changed()
    }

    override fun onInterrupt() { ScreenAccessibilityConnection.interrupt(this) }
    override fun onUnbind(intent: Intent?): Boolean {
        ScreenAccessibilityConnection.disconnect(this)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        ScreenAccessibilityConnection.disconnect(this)
        super.onDestroy()
    }
}

/** Process lifetime only; feedback interruption and binding loss invalidate local screen grants. */
object ScreenAccessibilityConnection {
    @Volatile private var reference = WeakReference<ScreenAccessibilityService>(null)
    private val sequence = AtomicLong()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    internal val generation: Long get() = sequence.get()
    internal val service: ScreenAccessibilityService? get() = reference.get()
    internal fun changed() { sequence.incrementAndGet() }
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
