package com.openandroidintelligence.screen

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.control.ScreenBounds
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.control.remote.PhoneControlOverlayTheme
import com.openandroidintelligence.control.remote.PhoneLocalControlUi
import com.openandroidintelligence.control.remote.PhoneLocalControlUiRegistry
import com.openandroidintelligence.control.remote.LocalUiCaptureProtection
import com.openandroidintelligence.control.ScreenDisplay
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.openandroidintelligence.control.remote.ControlRequestException

/** 无障碍胶囊不获取键盘焦点；触摸来源检查只是辅助，远端端口另行保护整个胶囊区域。 */
class PhoneControlOverlay(
    private val service: ScreenAccessibilityService,
    private val manager: WindowManager = service.getSystemService(WindowManager::class.java),
) : PhoneLocalControlUi, AutoCloseable {
    private class Request(val id: String, val decision: CompletableDeferred<Boolean>? = null)

    private val queue = Mutex()
    private var owner: Request? = null
    // 只记录 addView 成功返回的窗口，附着与首帧是另外的状态。
    private var view: PhoneControlOverlayView? = null
    @Volatile private var bounds: ScreenBounds? = null
    @Volatile private var protecting = false
    @Volatile private var stopped = false
    private var protectionGeneration = 0L
    private var changedAt = SystemClock.uptimeMillis()
    private var historyDisplay: ScreenDisplay? = null
    private var historyBottom = 0
    private var geometryProvable = true
    private val protectionChanges = MutableStateFlow(0L)
    // 新窗口等待首个布局时先拒绝全部远端坐标，不能留下 bounds 尚未发布的注入窗口。
    override val protectedBounds: ScreenBounds? get() = if (protecting) bounds ?: ScreenBounds(0, 0, Int.MAX_VALUE, Int.MAX_VALUE) else null
    @Suppress("DEPRECATION")
    override fun captureProtection(): LocalUiCaptureProtection? {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val display = currentDisplay() ?: return null
        if (historyDisplay != null && historyDisplay != display) geometryProvable = false
        val current = view
        if (current != null) {
            val params = current.layoutParams as? WindowManager.LayoutParams
            fun hasExternalDrawing(node: View): Boolean = node.elevation != 0f || node.translationZ != 0f ||
                node.translationX != 0f || node.translationY != 0f || node.rotation != 0f || node.scaleX != 1f || node.scaleY != 1f ||
                (node is android.view.ViewGroup && (0 until node.childCount).any { hasExternalDrawing(node.getChildAt(it)) })
            if (params == null || params.windowAnimations != 0 || params.dimAmount != 0f || params.blurBehindRadius != 0 ||
                params.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 || hasExternalDrawing(current)) geometryProvable = false
        }
        if (current != null && current.isLaidOut && !current.isLayoutRequested && current.width > 0 && current.height > 0) {
            val at = IntArray(2); current.getLocationOnScreen(at)
            val actual = ScreenBounds(at[0], at[1], at[0] + current.width, at[1] + current.height)
            if (actual != bounds) recordBounds(actual, display)
        }
        return LocalUiCaptureProtection(protectionGeneration, display, changedAt,
            if (historyBottom > 0) listOf(ScreenBounds(0, 0, display.widthPixels, historyBottom)) else emptyList(),
            geometryProvable && historyDisplay == display && (!protecting || bounds != null))
    }
    override suspend fun awaitCaptureProtection(requestId: String): LocalUiCaptureProtection = withContext(Dispatchers.Main.immediate) {
        val ownedView = view ?: throw ControlRequestException("SCREEN_LOCAL_UI_NOT_READY")
        var result: LocalUiCaptureProtection? = null
        val ready = withTimeoutOrNull(CAPTURE_LAYOUT_TIMEOUT_MS) {
            combine(protectionChanges, PhoneLocalControlUiRegistry.changes) { local, owner -> local to owner }.first {
                currentCoroutineContext().ensureActive()
                if (stopped || PhoneLocalControlUiRegistry.current !== this@PhoneControlOverlay ||
                    owner?.id != requestId || view !== ownedView || !protecting) {
                    throw ControlRequestException("SCREEN_LOCAL_UI_CHANGED")
                }
                result = captureProtection()?.takeIf { it.ready }
                result != null
            }
            result
        }
        ready ?: throw ControlRequestException("SCREEN_LOCAL_UI_LAYOUT_TIMEOUT")
    }
    private fun changedProtection() {
        protectionGeneration++; changedAt = SystemClock.uptimeMillis()
        protectionChanges.value = protectionGeneration
    }
    @Suppress("DEPRECATION")
    private fun currentDisplay(): ScreenDisplay? = try {
        val metrics = manager.maximumWindowMetrics.bounds
        ScreenDisplay(metrics.width(), metrics.height(), manager.defaultDisplay.rotation)
            .takeIf { it.widthPixels > 0 && it.heightPixels > 0 }
    } catch (_: RuntimeException) { geometryProvable = false; null }
    private fun recordBounds(actual: ScreenBounds, display: ScreenDisplay) {
        // 内容触发重新布局时，只有实际保护区域或显示变化才淘汰在途截图。
        if (bounds == actual && historyDisplay == display) return
        if (historyDisplay == null) historyDisplay = display
        if (historyDisplay != display || actual.left < 0 || actual.top < 0 || actual.right > display.widthPixels ||
            actual.bottom > display.heightPixels || actual.right <= actual.left || actual.bottom <= actual.top) geometryProvable = false
        bounds = actual
        historyBottom = maxOf(historyBottom, actual.bottom)
        changedProtection()
    }
    override suspend fun confirm(requestId: String, capability: String, localParameters: String?): Boolean {
        val pending = CompletableDeferred<Boolean>()
        val request = Request(requestId, pending)
        queue.lock(request)
        var retained = false
        try {
            retained = withContext(Dispatchers.Main.immediate) {
                if (stopped) return@withContext false
                val detail = if (capability == "org.openandroidintelligence.sms.send") {
                    val parameters = localParameters?.let { JsonFields.obj(Json.parse(it)) } ?: return@withContext false
                    val text = JsonFields.string(parameters, "text") ?: return@withContext false
                    service.getString(R.string.remote_phone_sms_confirmation,
                        JsonFields.string(parameters, "recipient") ?: return@withContext false,
                        JsonFields.int(parameters, "subscriptionId") ?: return@withContext false,
                        text.take(240), text.length)
                } else if (capability == "push.bind_recipient") {
                    val parameters = localParameters?.let { JsonFields.obj(Json.parse(it)) } ?: return@withContext false
                    val keys = listOf("controllerDeviceId", "controllerKeyFingerprint", "origin", "accountId", "hostDeviceId",
                        "hostKeyFingerprint", "backend", "workspaceId", "sessionId")
                    val values = keys.map { JsonFields.string(parameters, it) ?: return@withContext false }
                    service.getString(R.string.remote_phone_push_recipient_confirmation, *values.toTypedArray())
                } else null
                val title = if (capability == "push.bind_recipient") service.getString(R.string.remote_phone_push_recipient_title)
                    else service.getString(R.string.remote_phone_confirm, capability)
                owner = request
                mount(title, detail) { pending.complete(it) }
                val allowed = withTimeoutOrNull(5 * 60_000) { pending.await() } == true
                if (!allowed || stopped || owner !== request) return@withContext false

                // 详情和确认按钮移出同一个窗口。重新布局前仍保护整个屏幕。
                bounds = null
                checkNotNull(view).showExecuting(service.getString(R.string.remote_phone_executing))
                changedProtection()
                true
            }
            return retained
        } finally {
            if (!retained) withContext(NonCancellable + Dispatchers.Main.immediate) { release(request) }
        }
    }

    override suspend fun executing(requestId: String) {
        val owns = withContext(Dispatchers.Main.immediate) { owner?.id == requestId }
        if (owns) return
        val request = Request(requestId)
        queue.lock(request)
        var retained = false
        try {
            withContext(Dispatchers.Main.immediate) {
                if (stopped) throw IllegalStateException("REMOTE_EMERGENCY_STOPPED")
                owner = request
                mount(service.getString(R.string.remote_phone_executing))
            }
            retained = true
        } finally {
            if (!retained) withContext(NonCancellable + Dispatchers.Main.immediate) { release(request) }
        }
    }

    override suspend fun finish(requestId: String) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            owner?.takeIf { it.id == requestId }?.let(::release)
        }
    }

    override fun emergencyStop() {
        stopped = true
        changedProtection()
        owner?.decision?.complete(false)
        stopNative()
        PhoneLocalControlUiRegistry.notifyEmergencyStop()
    }

    private fun stopNative() { com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current?.stop(); ScreenHostRegistry.current?.revoke() }

    private fun mount(title: String, detail: String? = null, decide: ((Boolean) -> Unit)? = null) {
        check(view == null)
        protecting = true
        changedProtection()
        val colors = (service.application as? PhoneControlOverlayTheme)?.phoneControlOverlayColors(service)
            ?: systemPhoneControlOverlayColors(service)
        val layout = PhoneControlOverlayView(service, colors, title, detail, decide, ::emergencyStop)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.END
                windowAnimations = 0; dimAmount = 0f; setBlurBehindRadius(0)
            }
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (view === layout) {
                val location = IntArray(2); layout.getLocationOnScreen(location)
                val display = currentDisplay()
                if (display != null) recordBounds(ScreenBounds(location[0], location[1], location[0] + layout.width, location[1] + layout.height), display)
                else geometryProvable = false
            }
        }
        try {
            manager.addView(layout, params)
        } catch (_: WindowManager.BadTokenException) {
            layout.deactivate()
            throw ControlRequestException("LOCAL_CONFIRMATION_UI_UNAVAILABLE")
        } catch (_: WindowManager.InvalidDisplayException) {
            layout.deactivate()
            throw ControlRequestException("LOCAL_CONFIRMATION_UI_UNAVAILABLE")
        }
        view = layout
    }

    private fun release(request: Request) {
        try {
            if (owner === request) {
                owner = null
                val registered = view
                view = null
                try {
                    registered?.deactivate()
                    // 首帧前已登记的窗口同样必须从 WindowManager 注销。
                    registered?.let(manager::removeViewImmediate)
                } finally {
                    bounds = null
                    protecting = false
                    changedProtection()
                }
            }
        } finally {
            if (queue.holdsLock(request)) queue.unlock(request)
            request.decision?.complete(false)
        }
    }

    override fun close() {
        stopped = true
        try {
            stopNative()
        } finally {
            try {
                owner?.let(::release)
            } finally {
                if (PhoneLocalControlUiRegistry.current === this) PhoneLocalControlUiRegistry.current = null
            }
        }
    }
    companion object { internal const val CAPTURE_LAYOUT_TIMEOUT_MS = 2_000L }
}
