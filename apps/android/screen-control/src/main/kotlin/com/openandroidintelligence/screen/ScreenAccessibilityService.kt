/* Adapted from HermesAccessibilityService.kt, Hermes Relay da782a8d.
 * Copyright (c) 2026 Axiom Labs. MIT; see LICENSES/hermes-relay-MIT.txt. */
package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import com.openandroidintelligence.control.ScreenBounds
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.control.remote.PhoneLocalControlUiRegistry

/** The system binds this service only after the user enables it in Android Settings. */
class ScreenAccessibilityService : AccessibilityService() {
    private var overlay: PhoneControlOverlay? = null
    private var nativeScreen: NativePhoneScreen? = null
    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenAccessibilityConnection.connect(this)
        overlay?.close()
        overlay = PhoneControlOverlay(this).also { PhoneLocalControlUiRegistry.current = it }
        val screen = NativePhoneScreen(this)
        nativeScreen = screen
        com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current = screen
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 只保留窗口 ID、类型、状态和包名等失效判定所需信息，不保留事件源或界面文本。
        val observed = ScreenAccessibilityConnection.observeWindows(this)
        if (ScreenAccessibilityConnection.requiresInvalidation(
                event.eventType, event.packageName?.toString(), event.windowId, observed,
            )) ScreenAccessibilityConnection.changed(event.eventType)
    }

    override fun onInterrupt() { ScreenAccessibilityConnection.interrupt(this) }
    override fun onUnbind(intent: Intent?): Boolean {
        releaseOwnedResources()
        ScreenAccessibilityConnection.disconnect(this)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        releaseOwnedResources()
        ScreenAccessibilityConnection.disconnect(this)
        super.onDestroy()
    }

    private fun releaseOwnedResources() {
        overlay?.close()
        overlay = null
        nativeScreen?.let { own ->
            own.stop()
            if (com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current === own) {
                com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current = null
            }
        }
        nativeScreen = null
    }
}

/** Process lifetime only; feedback interruption and binding loss invalidate local screen grants. */
object ScreenAccessibilityConnection {
    @Volatile private var reference = WeakReference<ScreenAccessibilityService>(null)
    private val sequence = AtomicLong()
    private val layoutChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val layoutEvents: kotlinx.coroutines.flow.StateFlow<Long> get() = layoutChanges
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val windowStateLock = Any()
    private var snapshotTargets: List<ScreenWindowState>? = null
    private var observedAuxiliary: Set<ScreenWindowState>? = emptySet()
    internal val generation: Long get() = sequence.get()
    internal val service: ScreenAccessibilityService?
        get() = reference.get()?.takeIf { ScreenHostRegistry.accessibilityConnected.value }
    internal fun changed(eventType: Int? = null) {
        sequence.incrementAndGet()
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> layoutChanges.value += 1
        }
    }
    internal fun connect(service: ScreenAccessibilityService) {
        reference = WeakReference(service)
        clearWindowState()
        ScreenHostRegistry.setAccessibilityConnected(true)
        changed()
    }
    internal fun interrupt(service: ScreenAccessibilityService) {
        if (reference.get() !== service) return
        // Android interrupts accessibility feedback without necessarily unbinding
        // the service. Revoke the local lease and stale references, retain the
        // system-bound instance for a later explicit local authorization.
        clearWindowState()
        changed()
        listeners.forEach { runCatching { it() } }
    }
    internal fun disconnect(service: ScreenAccessibilityService) {
        val current = reference.get()
        if (current != null && current !== service) return
        reference.clear()
        ScreenHostRegistry.setAccessibilityConnected(false)
        clearWindowState()
        changed()
        listeners.forEach { runCatching { it() } }
    }
    /** Called for feedback interruption as well as an actual service disconnect. */
    fun addDisconnectListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** 快照成功后记录其目标窗口集合，供后续 TYPE_WINDOWS_CHANGED 只做差量判定。 */
    internal fun noteSnapshotWindows(windows: List<ScreenTreeWindow>) {
        val activeService = service ?: return
        val ownPackage = activeService.packageName
        val targets = windows.filterNot { it.isOwnedAuxiliary(ownPackage) }
            .map { it.toWindowState() }
            .sortedBy { it.id }
        val auxiliary = observeWindows(activeService)?.auxiliary
        synchronized(windowStateLock) {
            snapshotTargets = targets
            if (auxiliary != null) observedAuxiliary = auxiliary
        }
    }

    /**
     * TYPE_WINDOWS_CHANGED 不保证带有效包名或窗口 ID，必须用当前窗口列表判定。
     * 只有快照目标完全未变，且自家浮层/临时窗口状态确实发生增删时才不失效。
     */
    internal fun requiresInvalidation(
        eventType: Int,
        eventPackage: String?,
        windowId: Int,
        observed: ObservedWindows?,
    ): Boolean {
        val currentServicePackage = service?.packageName
        return synchronized(windowStateLock) {
            if (observed == null) {
                observedAuxiliary = null
                return@synchronized true
            }
            val ownedOverlayEvent = eventPackage == currentServicePackage && windowId in observed.ownOverlayIds
            val previousAuxiliary = observedAuxiliary
            val targetUnchanged = snapshotTargets?.let { it == observed.targets } == true
            val auxiliaryChanged = previousAuxiliary != null && previousAuxiliary != observed.auxiliary
            observedAuxiliary = observed.auxiliary
            val invalidate = !(ownedOverlayEvent && targetUnchanged) && !(eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                targetUnchanged && auxiliaryChanged)
            if (snapshotTargets != null && Log.isLoggable(SCREEN_TRACE, Log.DEBUG)) {
                Log.d(SCREEN_TRACE, "WINDOW_EVENT type=$eventType window=$windowId sameTargets=$targetUnchanged " +
                    "ownedOverlay=$ownedOverlayEvent auxiliaryChanged=$auxiliaryChanged invalidate=$invalidate")
            }
            invalidate
        }
    }

    /** 读取当前窗口的保守指纹；任一步异常都返回 null，由调用方按未知事件失效。 */
    internal fun observeWindows(activeService: ScreenAccessibilityService): ObservedWindows? {
        val ownPackage = activeService.packageName
        val current = try { activeService.windows.orEmpty() } catch (_: RuntimeException) { return null }
        val targets = ArrayList<ScreenWindowState>()
        val auxiliary = LinkedHashSet<ScreenWindowState>()
        val ownOverlayIds = LinkedHashSet<Int>()
        try {
            for (window in current) {
                val rect = Rect()
                window.getBoundsInScreen(rect)
                val root = try { window.root } catch (_: RuntimeException) { null }
                val rootPackage = try { root?.packageName?.toString() }
                finally { root?.let(::recycleWindowInfo) }
                val state = ScreenWindowState(
                    id = window.id,
                    type = window.type,
                    layer = window.layer,
                    active = window.isActive,
                    focused = window.isFocused,
                    bounds = ScreenBounds(rect.left, rect.top, rect.right, rect.bottom),
                    packageName = rootPackage,
                )
                if (state.isOwnedAuxiliary(ownPackage)) auxiliary += state else targets += state
                if (state.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && state.packageName == ownPackage)
                    ownOverlayIds += state.id
            }
        } catch (_: RuntimeException) {
            return null
        } finally {
            current.forEach(::recycleWindowInfo)
        }
        return ObservedWindows(
            targets = targets.sortedBy { it.id },
            auxiliary = auxiliary.toSet(),
            ownOverlayIds = ownOverlayIds.toSet(),
        )
    }

    private fun clearWindowState() {
        synchronized(windowStateLock) {
            snapshotTargets = null
            observedAuxiliary = emptySet()
        }
    }
}

/** 显式启用的本机诊断只记事件分类和代次，不记录界面正文、包名或坐标。 */
internal const val SCREEN_TRACE = "OAI-Screen-Trace"

internal data class ScreenWindowState(
    val id: Int,
    val type: Int,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val bounds: ScreenBounds,
    val packageName: String?,
)

internal data class ObservedWindows(
    val targets: List<ScreenWindowState>,
    val auxiliary: Set<ScreenWindowState>,
    val ownOverlayIds: Set<Int>,
)

private fun ScreenWindowState.isOwnedAuxiliary(ownPackage: String): Boolean =
    // 只排除自家无障碍浮层，以及非活跃、非焦点的自家系统临时窗（Toast 一类）。
    // 自家普通应用窗、聚焦系统窗仍属于真实目标，不能按包名整体忽略。
    packageName == ownPackage && (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
        (type == AccessibilityWindowInfo.TYPE_SYSTEM && !active && !focused))

private fun ScreenTreeWindow.toWindowState() = ScreenWindowState(
    id = window.id,
    type = window.type,
    layer = window.layer,
    active = window.active,
    focused = window.focused,
    bounds = window.bounds,
    packageName = packageName,
)

private fun ScreenTreeWindow.isOwnedAuxiliary(ownPackage: String): Boolean =
    toWindowState().isOwnedAuxiliary(ownPackage)

@Suppress("DEPRECATION")
private fun recycleWindowInfo(value: AccessibilityWindowInfo) {
    try { value.recycle() } catch (_: RuntimeException) { }
}

@Suppress("DEPRECATION")
private fun recycleWindowInfo(value: AccessibilityNodeInfo) {
    try { value.recycle() } catch (_: RuntimeException) { }
}
