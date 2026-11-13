package com.openandroidintelligence.gateway.remote.transport

import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding

/** 只在进程内区分连接与本次 carrier，不增加 RTCH 线格式字段。 */
data class RtcChannelIdentity(val binding: RemoteConnectionBinding, val generation: Long) {
    init { require(generation >= 0) { "RTC_GENERATION_INVALID" } }
}

enum class RtcChannelState { CONNECTING, OPEN, CLOSING, CLOSED }

/** 已建立或正在建立的数据通道；实现须复制接收字节并串行回调，SDP、ICE 和选路由上层负责。 */
interface RtcDataChannelPort : AutoCloseable {
    val identity: RtcChannelIdentity
    val label: String
    val state: RtcChannelState
    val bufferedAmount: Long
    fun observe(observer: Observer?)
    fun sendBinary(bytes: ByteArray): Boolean

    interface Observer {
        fun onStateChanged(identity: RtcChannelIdentity, state: RtcChannelState)
        fun onMessage(identity: RtcChannelIdentity, bytes: ByteArray)
        fun onBufferedAmountChanged(identity: RtcChannelIdentity)
    }
}
