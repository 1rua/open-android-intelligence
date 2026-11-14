package com.openandroidintelligence.gateway.remote.rpc

import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** 一条已认证连接的不可变归属；切换目标必须创建新连接或代际。 */
data class RemoteRpcConnectionScope(
    val origin: String,
    val accountId: String,
    val hostDeviceId: String,
    val clientDeviceId: String,
    val connectionId: String,
    val generation: Long,
) {
    init {
        require(listOf(origin, accountId, hostDeviceId, clientDeviceId, connectionId).all { it.isNotBlank() })
        require(generation > 0)
    }
}

data class RemoteRpcEvent(val event: String, val data: JsonValue)

/** 原始 Remote RPC；各后端适配器自行管理其真实 stream.open/close 语义。 */
interface RemoteRpcChannel {
    val scope: RemoteRpcConnectionScope
    val capabilities: Set<String>
    val events: Flow<RemoteRpcEvent>

    suspend fun invoke(
        method: String,
        params: JsonValue.JObject,
        requestId: String = UUID.randomUUID().toString(),
        timeoutMs: Long = 30_000,
    ): JsonValue

    /** 关闭后旧请求与旧事件立即失效，未知写入不得迁移或重发到新连接。 */
    suspend fun close()
}

/** Host 入站只接受来自认证通道的连接身份，不能从业务 JSON 自报身份。 */
interface RemoteRpcHostHandler {
    suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject): JsonValue
    fun events(scope: RemoteRpcConnectionScope): Flow<RemoteRpcEvent>
}
