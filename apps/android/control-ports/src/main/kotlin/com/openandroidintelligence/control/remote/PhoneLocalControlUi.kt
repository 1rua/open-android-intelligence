package com.openandroidintelligence.control.remote

import com.openandroidintelligence.control.ScreenBounds
import com.openandroidintelligence.control.ScreenDisplay

/** 仅本机窗口拥有者发布；代次及历史安全带在服务存活期间不回退、不清空。 */
data class LocalUiCaptureProtection(
    val generation: Long,
    val display: ScreenDisplay,
    val changedAtUptimeMs: Long,
    val maskedRegions: List<ScreenBounds>,
    val ready: Boolean,
)

/** 仅手机本机服务实现；远端协议没有批准、隐藏或停止胶囊的调用入口。 */
interface PhoneLocalControlUi {
    suspend fun confirm(requestId: String, capability: String, localParameters: String? = null): Boolean
    suspend fun executing(requestId: String)
    suspend fun finish(requestId: String)
    fun emergencyStop()
    val protectedBounds: ScreenBounds?
    /** 缺少可核验的窗口几何时返回 null；整屏截图必须拒绝，不能把它理解为没有浮层。 */
    fun captureProtection(): LocalUiCaptureProtection? = null
    /** 只等待本请求拥有的真实布局；默认提供者没有事件协议时安全拒绝未就绪状态。 */
    suspend fun awaitCaptureProtection(requestId: String): LocalUiCaptureProtection =
        captureProtection()?.takeIf { it.ready } ?: throw ControlRequestException("SCREEN_LOCAL_UI_NOT_READY")
}

object PhoneLocalControlUiRegistry {
    private val ownerChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> get() = ownerChanges
    @Volatile var current: PhoneLocalControlUi? = null
        set(value) { field = value; ownerChanges.value += 1 }
    private val stops = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun observeEmergencyStop(stop: () -> Unit): AutoCloseable { stops += stop; return AutoCloseable { stops -= stop } }
    fun notifyEmergencyStop() { stops.forEach { it() } }
}
