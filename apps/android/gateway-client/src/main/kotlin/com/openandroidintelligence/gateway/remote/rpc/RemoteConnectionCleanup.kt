package com.openandroidintelligence.gateway.remote.rpc

import kotlinx.coroutines.CancellationException

/** 清理失败不能跳过其他连接资源；全部尝试后只向外暴露固定错误码。 */
fun closeRemoteConnectionResources(vararg actions: () -> Unit) {
    var failed = false
    var cancelled: CancellationException? = null
    actions.forEach { action ->
        try { action() }
        catch (failure: CancellationException) { if (cancelled == null) cancelled = failure }
        catch (_: Exception) { failed = true }
    }
    cancelled?.let { throw it }
    if (failed) throw RemoteRpcException("CONNECTION_CLEANUP_FAILED")
}
