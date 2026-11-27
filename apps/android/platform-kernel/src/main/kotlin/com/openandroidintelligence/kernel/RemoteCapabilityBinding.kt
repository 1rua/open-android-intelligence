package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.remote.ControlAccessBinding

/** Remote 的独立本机授权命名空间，不构造旧 Gateway 配对或把同账号设备合并。 */
class RemoteCapabilityBinding(val access: ControlAccessBinding, private val current: () -> Boolean) {
    val id: String = "remote_${access.principal.storageKey}"
    fun isCurrent(): Boolean = runCatching(current).getOrDefault(false)
    fun requestKey(requestId: String): String = "$id:${access.authorizationEpoch}:${access.connectionGeneration}:$requestId"
}
