package com.openandroidintelligence.screen

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.control.ScreenBounds
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.control.remote.PhoneLocalControlUi
import com.openandroidintelligence.control.remote.PhoneLocalControlUiRegistry
import com.openandroidintelligence.control.remote.LocalUiCaptureProtection
import com.openandroidintelligence.control.ScreenDisplay
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** 无障碍胶囊不获取键盘焦点；触摸来源检查只是辅助，远端端口另行保护整个胶囊区域。 */
class PhoneControlOverlay(
    private val service: ScreenAccessibilityService,
    private val manager: WindowManager = service.getSystemService(WindowManager::class.java),
) : PhoneLocalControlUi, AutoCloseable {
    private val queue = Mutex(); private var view: View? = null; private var activeId: String? = null
    private var decision: CompletableDeferred<Boolean>? = null
    @Volatile private var bounds: ScreenBounds? = null
    @Volatile private var protecting = false
    @Volatile private var stopped = false
    private var protectionGeneration = 0L
    private var changedAt = SystemClock.uptimeMillis()
    private var historyDisplay: ScreenDisplay? = null
    private var historyBottom = 0
    private var geometryProvable = true
    // 新窗口等待首个布局时先拒绝全部远端坐标，不能留下 bounds 尚未发布的注入窗口。
    override val protectedBounds: ScreenBounds? get() = if (protecting) bounds ?: ScreenBounds(0, 0, Int.MAX_VALUE, Int.MAX_VALUE) else null
    @Suppress("DEPRECATION")
    override fun captureProtection(): LocalUiCaptureProtection {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val metrics = manager.maximumWindowMetrics.bounds
        val display = ScreenDisplay(metrics.width(), metrics.height(), manager.defaultDisplay.rotation)
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
        if (current != null && current.isLaidOut && current.width > 0 && current.height > 0) {
            val at = IntArray(2); current.getLocationOnScreen(at)
            val actual = ScreenBounds(at[0], at[1], at[0] + current.width, at[1] + current.height)
            if (actual != bounds) recordBounds(actual, display)
        }
        return LocalUiCaptureProtection(protectionGeneration, display, changedAt,
            if (historyBottom > 0) listOf(ScreenBounds(0, 0, display.widthPixels, historyBottom)) else emptyList(),
            geometryProvable && historyDisplay == display && (!protecting || bounds != null))
    }
    private fun changedProtection() { protectionGeneration++; changedAt = SystemClock.uptimeMillis() }
    private fun recordBounds(actual: ScreenBounds, display: ScreenDisplay) {
        if (historyDisplay == null) historyDisplay = display
        if (historyDisplay != display || actual.left < 0 || actual.top < 0 || actual.right > display.widthPixels ||
            actual.bottom > display.heightPixels || actual.right <= actual.left || actual.bottom <= actual.top) geometryProvable = false
        bounds = actual
        historyBottom = maxOf(historyBottom, actual.bottom)
        changedProtection()
    }
    override suspend fun confirm(requestId: String, capability: String, localParameters: String?): Boolean {
        queue.lock()
        var retained = false
        return try { withContext(Dispatchers.Main.immediate) {
            if (stopped) return@withContext false
            val pending = CompletableDeferred<Boolean>(); decision = pending; activeId = requestId
            val detail = if (capability == "org.openandroidintelligence.sms.send") {
                val parameters = localParameters?.let { JsonFields.obj(Json.parse(it)) }
                    ?: return@withContext false
                service.getString(R.string.remote_phone_sms_confirmation,
                    JsonFields.string(parameters, "recipient") ?: return@withContext false,
                    JsonFields.int(parameters, "subscriptionId") ?: return@withContext false,
                    JsonFields.string(parameters, "text")?.take(240) ?: return@withContext false,
                    JsonFields.string(parameters, "text")?.length ?: return@withContext false)
            } else null
            show(service.getString(R.string.remote_phone_confirm, capability), pending, detail)
            val allowed = withTimeoutOrNull(5 * 60_000) { pending.await() } ?: false
            decision = null
            if (allowed && !stopped) {
                show(service.getString(R.string.remote_phone_executing), null)
                retained = true
                true
            } else false
        } } finally { if (!retained) withContext(NonCancellable + Dispatchers.Main.immediate) {
            decision = null
            try { hide() } finally { activeId = null; queue.unlock() }
        } }
    }
    override suspend fun executing(requestId: String) {
        val owns = withContext(Dispatchers.Main.immediate) { activeId == requestId }
        if (owns) return
        queue.lock()
        try { withContext(Dispatchers.Main.immediate) {
        if (stopped) throw IllegalStateException("REMOTE_EMERGENCY_STOPPED"); activeId = requestId
        show(service.getString(R.string.remote_phone_executing), null)
        } } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                try { hide() } finally { activeId = null; queue.unlock() }
            }
            throw failure
        }
    }
    override suspend fun finish(requestId: String) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (activeId == requestId) {
            decision?.complete(false)
            try { hide() } finally { activeId = null; if (queue.isLocked) queue.unlock() }
        }
    }
    override fun emergencyStop() { stopped = true; decision?.complete(false); stopNative(); PhoneLocalControlUiRegistry.notifyEmergencyStop() }
    private fun stopNative() { com.openandroidintelligence.control.remote.NativePhoneScreenRegistry.current?.stop(); ScreenHostRegistry.current?.revoke() }
    private fun show(title: String, pending: CompletableDeferred<Boolean>?, detail: String? = null) {
        changedProtection()
        protecting = true
        hide(clearProtection = false)
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            setBackgroundColor(0xff202124.toInt())
            elevation = 0f; translationZ = 0f; outlineProvider = null
            clipChildren = true; clipToPadding = true
        }
        layout.addView(TextView(service).apply { text = title; setTextColor(0xffffffff.toInt()) })
        if (detail != null) {
            layout.addView(ScrollView(service).apply {
                addView(TextView(service).apply { text = detail; setTextColor(0xffffffff.toInt()) })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (service.resources.displayMetrics.heightPixels / 3)))
        }
        if (pending != null) { layout.addView(button(R.string.remote_phone_allow) { pending.complete(true) }); layout.addView(button(R.string.remote_phone_deny) { pending.complete(false) }) }
        layout.addView(button(R.string.remote_phone_stop) { emergencyStop() })
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.END
                windowAnimations = 0; dimAmount = 0f; setBlurBehindRadius(0)
            }
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (view === layout) {
                val location = IntArray(2); layout.getLocationOnScreen(location)
                val metrics = manager.maximumWindowMetrics.bounds
                @Suppress("DEPRECATION") val display = ScreenDisplay(metrics.width(), metrics.height(), manager.defaultDisplay.rotation)
                recordBounds(ScreenBounds(location[0], location[1], location[0] + layout.width, location[1] + layout.height), display)
            }
        }
        manager.addView(layout, params); view = layout
    }
    private fun button(label: Int, action: () -> Unit): Button = Button(service).apply {
        // 系统按钮的按压 Z 动画会在窗口外产生影子，整屏遮蔽路径不允许该类外绘。
        stateListAnimator = null; elevation = 0f; translationZ = 0f; outlineProvider = null
        setText(label); var physicalDown = false
        setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) physicalDown = event.deviceId != 0 && event.source and android.view.InputDevice.SOURCE_TOUCHSCREEN == android.view.InputDevice.SOURCE_TOUCHSCREEN; if (event.actionMasked == MotionEvent.ACTION_CANCEL) physicalDown = false; false }
        setOnClickListener { if (physicalDown) action(); physicalDown = false }
    }
    private fun hide(clearProtection: Boolean = true) {
        changedProtection()
        if (clearProtection) protecting = false
        bounds = null
        val old = view; view = null
        old?.let { if (it.isAttachedToWindow) manager.removeViewImmediate(it) }
    }
    override fun close() {
        stopped = true; decision?.complete(false); stopNative(); hide()
        if (PhoneLocalControlUiRegistry.current === this) PhoneLocalControlUiRegistry.current = null
    }
}
