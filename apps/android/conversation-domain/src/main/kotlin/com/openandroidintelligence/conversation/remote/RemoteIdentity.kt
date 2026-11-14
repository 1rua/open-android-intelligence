package com.openandroidintelligence.conversation.remote

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * 连接层通过成熟 URL 解析器取得的规范 origin，只用于持久身份隔离。
 *
 * 此处不校验用户输入的服务地址，也不决定 HTTP 是否获准。IDN 转换、IPv6 压缩和
 * 服务 base path 的处理属于同一个连接层规范化过程，不能由各仓储另行推断。
 */
@JvmInline
value class RemoteServiceOrigin private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromCanonicalOrigin(value: String): RemoteServiceOrigin {
            val uri = try {
                URI(value)
            } catch (cause: URISyntaxException) {
                throw IllegalArgumentException("Remote origin 格式无效", cause)
            }
            require(value.all { it.code in 0x21..0x7e }) { "Remote origin 必须是规范 ASCII 地址" }
            require(uri.scheme == "https" || uri.scheme == "http") { "Remote origin 协议无效" }
            val host = requireNotNull(uri.host) { "Remote origin 缺少主机" }
            require(host == host.lowercase(Locale.ROOT) && '%' !in host) { "Remote origin 主机未规范化" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Remote origin 不能携带凭据、查询或片段"
            }
            require(uri.rawPath.isNullOrEmpty()) { "Remote origin 不能携带路径" }
            require(uri.port == -1 || uri.port in 0..65535) { "Remote origin 端口无效" }
            require(!(uri.scheme == "https" && uri.port == 443) && !(uri.scheme == "http" && uri.port == 80)) {
                "Remote origin 不能显式携带默认端口"
            }
            val canonicalStructure = URI(uri.scheme, null, host, uri.port, null, null, null).toASCIIString()
            require(value == canonicalStructure) { "Remote origin 结构未规范化" }
            return RemoteServiceOrigin(value)
        }
    }
}

/** 后端标识来自 Host 目录，ACP 的各真实后端保留各自标识。 */
@JvmInline
value class BackendId(val value: String) {
    init { require(value.isNotEmpty()) { "后端标识不能为空" } }
}

/** 显示名与临时 connectionId 均不参与端点的持久身份。 */
data class EndpointKey(
    val origin: RemoteServiceOrigin,
    val accountId: String,
    val hostId: String,
    val backendId: BackendId,
    val workspaceId: String,
) {
    init {
        require(accountId.isNotEmpty()) { "账号标识不能为空" }
        require(hostId.isNotEmpty()) { "Host 标识不能为空" }
        require(workspaceId.isNotEmpty()) { "工作区标识不能为空" }
    }
}

enum class EndpointAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE }

/** Host 发现结果；缺少就绪事实时保留未知状态，不替换为其他后端。 */
data class AgentEndpoint(
    val key: EndpointKey,
    val displayName: String,
    val availability: EndpointAvailability = EndpointAvailability.UNKNOWN,
)

/** 原生会话标识是不透明字符串，保留 ACP 冒号前缀等真实内容。 */
data class SessionKey(val endpoint: EndpointKey, val nativeSessionId: String) {
    init { require(nativeSessionId.isNotEmpty()) { "原生会话标识不能为空" } }
}

/**
 * 活跃请求的所有者；持久会话身份不因重连变化，活跃审批、上传与事件必须重新核对代次。
 * backendInstanceId 由后端实例事实提供，connectionGeneration 由连接拥有者单调推进。
 */
data class SessionOwner(
    val session: SessionKey,
    val backendInstanceId: String,
    val connectionId: String,
    val connectionGeneration: Long,
) {
    init {
        require(backendInstanceId.isNotEmpty()) { "后端实例标识不能为空" }
        require(connectionId.isNotEmpty()) { "连接标识不能为空" }
        require(connectionGeneration >= 0) { "连接代次不能为负数" }
    }

    /** 统一供缓存更新、审批、上传回调与模型目录确认检查，不接受同账号的其他端点。 */
    fun isCurrent(current: SessionOwner): Boolean = this == current
}
