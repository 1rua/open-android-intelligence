package com.openandroidintelligence.control.remote

/** 手机高频屏幕操作的原生端口；必须先通过 platform-kernel 的安装策略裁决。 */
interface NativePhoneScreenPort {
    fun availability(): String?
    suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation, revalidate: () -> Unit, mayRead: () -> Boolean): PhoneControlResult
    fun stop()
}
object NativePhoneScreenRegistry { @Volatile var current: NativePhoneScreenPort? = null }
