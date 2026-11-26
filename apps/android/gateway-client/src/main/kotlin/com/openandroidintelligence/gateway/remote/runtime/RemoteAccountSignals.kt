package com.openandroidintelligence.gateway.remote.runtime

import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope

/** 已授权数据通道的真实终止；由连接代际围栏决定它是否仍影响当前工作区。 */
data class RemoteDataConnectionClosure(
    val scope: RemoteRpcConnectionScope,
    val code: String,
)
