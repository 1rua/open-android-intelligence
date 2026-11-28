/* Adapted from HermesAccessibilityService.kt, Hermes Relay da782a8d.
 * Copyright (c) 2026 Axiom Labs. MIT; see LICENSES/hermes-relay-MIT.txt. */
package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowMetrics
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
    private var overlayWindows: LocalOverlayWindowObserver? = null
    private var nativeScreen: NativePhoneScreen? = null
    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay?.close()
        ScreenAccessibilityConnection.connect(this)
        val windowManager = getSystemService(WindowManager::class.java)
        val observer = LocalOverlayWindowObserver(windowManager) { id ->
            if (ScreenAccessibilityConnection.service === this) ScreenAccessibilityConnection.overlayDetached(id)
        }
        overlayWindows = observer
        overlay = PhoneControlOverlay(this, observer).also { PhoneLocalControlUiRegistry.current = it }
        val screen = NativePhoneScreen(this)
        nativeScreen = screen
        com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current = screen
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || ScreenAccessibilityConnection.service !== this) return
        // 只保留窗口 ID、类型、状态和包名等失效判定所需信息，不保留事件源或界面文本。
        ScreenAccessibilityConnection.onWindowEvent(event.eventType, event.packageName?.toString(), event.windowId,
            isStatusTextEvent = event::isNetworkSpeedTextEvent, isStatusSubtreeEvent = event::isStatusBarSubtreeEvent) {
            ScreenAccessibilityConnection.observeWindows(this)
        }
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

    internal fun attachedOverlayWindowId(): Int? = overlayWindows?.attachedWindowId()

    private fun releaseOwnedResources() {
        overlay?.close()
        overlay = null
        overlayWindows = null
        nativeScreen?.let { own ->
            own.stop()
            if (com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current === own) {
                com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current = null
            }
        }
        nativeScreen = null
    }
}

/** 从本服务交给窗口管理器的 View 读取窗口 ID，供根节点暂不可读时复核身份。 */
internal class LocalOverlayWindowObserver(
    private val system: WindowManager,
    private val onDetached: (Int) -> Unit,
) : WindowManager by system {
    private var registeredView: View? = null
    private var registeredWindowId: Int? = null

    // Kotlin 接口委托只转发抽象方法。系统窗口尺寸是 Java 默认方法，必须取实际管理器的实现。
    override fun getMaximumWindowMetrics(): WindowMetrics = system.maximumWindowMetrics
    override fun getCurrentWindowMetrics(): WindowMetrics = system.currentWindowMetrics

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        system.addView(view, params)
        if (params is WindowManager.LayoutParams && params.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            registeredView = view
            registeredWindowId = null
        }
    }

    fun attachedWindowId(): Int? {
        val view = registeredView?.takeIf { it.isAttachedToWindow } ?: return null
        registeredWindowId?.let { return it }
        return runCatching {
            val node = view.createAccessibilityNodeInfo()
            try { node.windowId.takeIf { it in 0 until Int.MAX_VALUE } }
            finally { recycleWindowInfo(node) }
        }.getOrNull().also { registeredWindowId = it }
    }

    override fun removeViewImmediate(view: View) {
        val owned = registeredView === view
        val id = if (owned) attachedWindowId() ?: registeredWindowId else null
        system.removeViewImmediate(view)
        if (owned) {
            registeredView = null
            registeredWindowId = null
            id?.let(onDetached)
        }
    }
}

/** Process lifetime only; feedback interruption and binding loss invalidate local screen grants. */
object ScreenAccessibilityConnection {
    @Volatile private var reference = WeakReference<ScreenAccessibilityService>(null)
    private val sequence = AtomicLong()
    private val controlSequence = AtomicLong()
    private val layoutChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val layoutEvents: kotlinx.coroutines.flow.StateFlow<Long> get() = layoutChanges
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val windowStateLock = Any()
    private var snapshotTargets: List<ScreenWindowState>? = null
    private var snapshotNetworkSpeedWindows = emptySet<Int>()
    private var observedAuxiliary: Set<ScreenWindowState>? = emptySet()
    private var detachedOverlayId: Int? = null

    /** 窗口移除事件可能在窗口已离开系统列表后才送达。仅保留本机刚卸下的那个窗口的 ID。 */
    internal fun overlayDetached(windowId: Int) {
        if (windowId !in 0 until Int.MAX_VALUE) return
        synchronized(windowStateLock) { detachedOverlayId = windowId }
    }
    internal val generation: Long get() = sequence.get()
    internal val controlGeneration: Long get() = controlSequence.get()
    internal val service: ScreenAccessibilityService?
        get() = reference.get()?.takeIf { ScreenHostRegistry.accessibilityConnected.value }
    internal fun changed(eventType: Int? = null, deferControlCheck: Boolean = false) {
        sequence.incrementAndGet()
        if (!deferControlCheck) controlSequence.incrementAndGet()
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

    /** 快照成功后记录与业务树同次枚举的完整窗口指纹；缺失时不建立乐观基线。 */
    internal fun noteSnapshotWindows(businessWindowCount: Int, observed: ObservedWindows?, networkSpeedWindows: Set<Int> = emptySet()) {
        if (service == null) return
        if (Log.isLoggable(SCREEN_TRACE, Log.DEBUG)) {
            val rootlessOverlays = observed?.auxiliary?.count { it.packageName == null } ?: 0
            Log.d(SCREEN_TRACE, "SNAPSHOT generation=$generation windows=$businessWindowCount " +
                "fingerprint=${observed != null} rootlessOwned=$rootlessOverlays")
        }
        synchronized(windowStateLock) {
            snapshotTargets = observed?.targets
            observedAuxiliary = observed?.auxiliary
            snapshotNetworkSpeedWindows = networkSpeedWindows
        }
    }

    internal fun onWindowEvent(
        eventType: Int,
        eventPackage: String?,
        windowId: Int,
        isStatusTextEvent: () -> Boolean = { false },
        isStatusSubtreeEvent: () -> Boolean = { false },
        observe: () -> ObservedWindows?,
    ) {
        // 没有可继续使用的快照时，窗口指纹无法豁免这次失效。
        // 仍通知布局观察者，但跳过主线程上的逐窗无障碍查询。
        val hasSnapshot = synchronized(windowStateLock) { snapshotTargets != null }
        val observed = if (hasSnapshot) observe() else null
        val statusWindow = observed?.targets?.any { it.id == windowId && it.isStatusBar() } == true
        val statusText = statusWindow && isStatusTextEvent()
        if (requiresInvalidation(eventType, eventPackage, windowId, observed, statusText)) {
            // 系统可能同时上报网速文字和状态栏根变化。取帧仍须失效，操控执行前重新读全量快照比对。
            val canDefer = statusWindow && synchronized(windowStateLock) {
                windowId in snapshotNetworkSpeedWindows && snapshotTargets == observed?.targets
            } && isStatusSubtreeEvent()
            if (!canDefer) synchronized(windowStateLock) {
                snapshotTargets = null
                observedAuxiliary = null
                snapshotNetworkSpeedWindows = emptySet()
            }
            changed(eventType, deferControlCheck = canDefer)
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
        statusText: Boolean = false,
    ): Boolean {
        val currentServicePackage = service?.packageName
        return synchronized(windowStateLock) {
            if (observed == null) {
                observedAuxiliary = null
                return@synchronized true
            }
            val windowsChanged = eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            val structural = windowsChanged || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val targetEvent = observed.targets.any { it.id == windowId }
            val liveOverlay = windowId in observed.ownOverlayIds
            val detachedOverlay = detachedOverlayId == windowId
            val ownedOverlayEvent = !targetEvent && (liveOverlay || detachedOverlay) &&
                (structural || eventPackage == null || eventPackage == currentServicePackage)
            val previousAuxiliary = observedAuxiliary
            val targetUnchanged = snapshotTargets?.let { it == observed.targets } == true
            val auxiliaryChanged = previousAuxiliary != null && previousAuxiliary != observed.auxiliary
            val auxiliaryEvent = windowsChanged && !targetEvent && auxiliaryChanged &&
                (windowId !in 1 until Int.MAX_VALUE || previousAuxiliary?.any { it.id == windowId } == true ||
                    observed.auxiliary.any { it.id == windowId })
            observedAuxiliary = observed.auxiliary
            val invalidate = !targetUnchanged || !(ownedOverlayEvent || auxiliaryEvent || statusText)
            if (snapshotTargets != null && Log.isLoggable(SCREEN_TRACE, Log.DEBUG)) {
                val rootlessTargets = observed.targets.count { it.packageName == null }
                val rootlessOverlays = observed.auxiliary.count { it.packageName == null }
                Log.d(SCREEN_TRACE, "WINDOW_EVENT type=$eventType sameTargets=$targetUnchanged ownedOverlay=$ownedOverlayEvent " +
                    "liveOverlay=$liveOverlay detachedOverlay=$detachedOverlay auxiliaryChanged=$auxiliaryChanged " +
                    "rootlessOwned=$rootlessOverlays rootlessTargets=$rootlessTargets statusText=$statusText invalidate=$invalidate")
            }
            invalidate
        }
    }

    /** 读取当前窗口的保守指纹；任一步异常都返回 null，由调用方按未知事件失效。 */
    internal fun observeWindows(activeService: ScreenAccessibilityService): ObservedWindows? {
        val current = try { activeService.windows.orEmpty() } catch (_: RuntimeException) { return null }
        val states = ArrayList<ScreenWindowState>()
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
                states += state
            }
        } catch (_: RuntimeException) {
            return null
        } finally {
            current.forEach(::recycleWindowInfo)
        }
        return classifyWindows(activeService, states)
    }

    /** 快照和事件共用浮层判定。根为空时只使用本服务已知的确切窗口 ID。 */
    internal fun classifyWindows(activeService: ScreenAccessibilityService, states: List<ScreenWindowState>): ObservedWindows {
        val ownPackage = activeService.packageName
        val attachedId = activeService.attachedOverlayWindowId()
        return synchronized(windowStateLock) {
            if (service !== activeService) return@synchronized buildObservedWindows(states, ownPackage)
            val detachedId = detachedOverlayId
            if (detachedId != null && states.any { it.id == detachedId && !it.isCompatibleOwnOverlay(ownPackage) }) {
                detachedOverlayId = null
            }
            val knownIds = observedAuxiliary.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .mapTo(mutableSetOf()) { it.id }
            attachedId?.let(knownIds::add)
            detachedOverlayId?.let(knownIds::add)
            buildObservedWindows(states, ownPackage, knownIds)
        }
    }

    private fun clearWindowState() {
        synchronized(windowStateLock) {
            snapshotTargets = null
            snapshotNetworkSpeedWindows = emptySet()
            observedAuxiliary = emptySet()
            detachedOverlayId = null
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

internal fun buildObservedWindows(
    states: List<ScreenWindowState>,
    ownPackage: String,
    knownOverlayIds: Set<Int> = emptySet(),
): ObservedWindows {
    val targets = ArrayList<ScreenWindowState>()
    val auxiliary = LinkedHashSet<ScreenWindowState>()
    val ownOverlayIds = LinkedHashSet<Int>()
    for (state in states) {
        val ownOverlay = state.isCompatibleOwnOverlay(ownPackage) && (state.packageName == ownPackage || state.id in knownOverlayIds)
        val ownTransientWindow = state.type == AccessibilityWindowInfo.TYPE_SYSTEM && !state.active && !state.focused &&
            state.packageName == ownPackage
        if (ownOverlay || ownTransientWindow) auxiliary += state else targets += state
        if (ownOverlay) ownOverlayIds += state.id
    }
    return ObservedWindows(
        targets = targets.sortedBy { it.id },
        auxiliary = auxiliary.toSet(),
        ownOverlayIds = ownOverlayIds.toSet(),
    )
}

private fun ScreenWindowState.isStatusBar(): Boolean = isStatusBarWindow(type, packageName, bounds, active, focused)

private fun ScreenWindowState.isCompatibleOwnOverlay(ownPackage: String): Boolean =
    type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && id in 0 until Int.MAX_VALUE &&
        (packageName == null || packageName == ownPackage)

@Suppress("DEPRECATION")
private fun recycleWindowInfo(value: AccessibilityWindowInfo) {
    try { value.recycle() } catch (_: RuntimeException) { }
}

@Suppress("DEPRECATION")
private fun recycleWindowInfo(value: AccessibilityNodeInfo) {
    try { value.recycle() } catch (_: RuntimeException) { }
}
