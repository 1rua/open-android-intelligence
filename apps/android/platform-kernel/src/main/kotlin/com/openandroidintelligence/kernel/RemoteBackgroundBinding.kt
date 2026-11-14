package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.remote.RemoteControlPrincipal

/** 本机定时调用的授权投影，独立于短命网络连接，只在同步调用期间注册。 */
class RemoteBackgroundBinding(
    val principal: RemoteControlPrincipal,
    val accountEpoch: Long,
    val localGrantRevision: Long,
    private val current: () -> Boolean,
) {
    val id = "remote-background_${principal.storageKey}:$accountEpoch:$localGrantRevision"
    fun isCurrent(): Boolean = runCatching(current).getOrDefault(false)
}
