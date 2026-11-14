package com.openandroidintelligence.screen

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.openandroidintelligence.control.ScreenBounds
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.control.remote.PhoneLocalControlUi
import com.openandroidintelligence.control.remote.PhoneLocalControlUiRegistry
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
    // 新窗口等待首个布局时先拒绝全部远端坐标，不能留下 bounds 尚未发布的注入窗口。
    override val protectedBounds: ScreenBounds? get() = if (protecting) bounds ?: ScreenBounds(0, 0, Int.MAX_VALUE, Int.MAX_VALUE) else null
    override suspend fun confirm(requestId: String, capability: String): Boolean {
        queue.lock()
        var retained = false
        return try { withContext(Dispatchers.Main.immediate) {
            if (stopped) return@withContext false
            val pending = CompletableDeferred<Boolean>(); decision = pending; activeId = requestId
            show(service.getString(R.string.remote_phone_confirm, capability), pending)
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
    private fun show(title: String, pending: CompletableDeferred<Boolean>?) {
        protecting = true
        hide(clearProtection = false)
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            setBackgroundColor(0xff202124.toInt())
        }
        layout.addView(TextView(service).apply { text = title; setTextColor(0xffffffff.toInt()) })
        if (pending != null) { layout.addView(button(R.string.remote_phone_allow) { pending.complete(true) }); layout.addView(button(R.string.remote_phone_deny) { pending.complete(false) }) }
        layout.addView(button(R.string.remote_phone_stop) { emergencyStop() })
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END }
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (view === layout) {
                val location = IntArray(2); layout.getLocationOnScreen(location)
                bounds = ScreenBounds(location[0], location[1], location[0] + layout.width, location[1] + layout.height)
            }
        }
        manager.addView(layout, params); view = layout
    }
    private fun button(label: Int, action: () -> Unit): Button = Button(service).apply {
        setText(label); var physicalDown = false
        setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) physicalDown = event.deviceId != 0 && event.source and android.view.InputDevice.SOURCE_TOUCHSCREEN == android.view.InputDevice.SOURCE_TOUCHSCREEN; if (event.actionMasked == MotionEvent.ACTION_CANCEL) physicalDown = false; false }
        setOnClickListener { if (physicalDown) action(); physicalDown = false }
    }
    private fun hide(clearProtection: Boolean = true) {
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
