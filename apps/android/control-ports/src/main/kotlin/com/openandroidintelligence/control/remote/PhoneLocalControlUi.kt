package com.openandroidintelligence.control.remote

import com.openandroidintelligence.control.ScreenBounds

/** 仅手机本机服务实现；远端协议没有批准、隐藏或停止胶囊的调用入口。 */
interface PhoneLocalControlUi {
    suspend fun confirm(requestId: String, capability: String, localParameters: String? = null): Boolean
    suspend fun executing(requestId: String)
    suspend fun finish(requestId: String)
    fun emergencyStop()
    val protectedBounds: ScreenBounds?
}

object PhoneLocalControlUiRegistry {
    @Volatile var current: PhoneLocalControlUi? = null
    private val stops = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun observeEmergencyStop(stop: () -> Unit): AutoCloseable { stops += stop; return AutoCloseable { stops -= stop } }
    fun notifyEmergencyStop() { stops.forEach { it() } }
}
