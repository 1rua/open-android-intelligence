package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.remote.ControlRequestException

/** 主动推送是独立本机许可；不继承普通读取、短信发送或短期批准。 */
data class NativePushDeliveryPolicy(val authorizationRevision: String,val enabled: Boolean)

/** 手机原生收件能力的内核门禁，真实Remote主体与本机推送许可均须在最终执行边界有效。 */
class NativePushDeliveryKernel(
    private val grants: PairingGrantStateHolder,
    private val emergencyStopped: () -> Boolean,
) {
    fun <T> execute(binding: RemoteCapabilityBinding,policy: () -> NativePushDeliveryPolicy,
        beforeDispatch: () -> Unit,perform: () -> T): T {
        val expected=policy()
        fun requireCurrent() {
            if (!binding.isCurrent() || grants.currentKernelGrant(binding.id) == null)
                throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
            if (emergencyStopped() || !expected.enabled || policy() != expected)
                throw ControlRequestException("PUSH_AUTHORIZATION_CHANGED")
        }
        requireCurrent()
        beforeDispatch()
        requireCurrent()
        val result=perform()
        requireCurrent()
        return result
    }
}
