package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.data.remote.RemoteModelCatalog
import com.openandroidintelligence.conversation.data.remote.RemoteWorkspace
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.remote.BackendId
import com.openandroidintelligence.conversation.remote.EndpointAvailability
import com.openandroidintelligence.conversation.remote.EndpointKey
import com.openandroidintelligence.conversation.remote.RemoteServiceOrigin
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.network.RemoteHostDevice

/**
 * 扁平 Agent 身份标识（Host + 后端），与工作区选择严格分层。
 * 同一 Agent 下切换不同工作区时该标识不变，底层连接通道保持复用。
 */
data class AgentKey(
    val origin: RemoteServiceOrigin,
    val accountId: String,
    val hostId: String,
    val backendId: BackendId,
) {
    /** 判断指定工作区端点是否归属于当前 Agent。 */
    fun matches(endpointKey: EndpointKey): Boolean =
        origin == endpointKey.origin &&
            accountId == endpointKey.accountId &&
            hostId == endpointKey.hostId &&
            backendId == endpointKey.backendId

    companion object {
        fun fromEndpointKey(key: EndpointKey): AgentKey =
            AgentKey(key.origin, key.accountId, key.hostId, key.backendId)
    }
}

/** 已发现的可连接 Agent 选项。 */
data class DiscoveredAgent(
    val key: AgentKey,
    val hostName: String,
    val backendName: String,
    val displayName: String,
    val hostOnline: Boolean = true,
    val availability: EndpointAvailability = EndpointAvailability.AVAILABLE,
)

/** Host / Agent 连接层生命周期阶段。 */
enum class AgentConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    OFFLINE,
    AUTH_REVOKED,
}

/** 第一层状态：Host/Agent 连接通道、认证凭据、连接健康与重连状态。 */
data class AgentConnectionState(
    val account: RemoteActiveAccount? = null,
    val hosts: List<RemoteHostDevice> = emptyList(),
    val agents: List<DiscoveredAgent> = emptyList(),
    val activeAgent: AgentKey? = null,
    val activeHostName: String? = null,
    val activeAgentName: String? = null,
    val phase: AgentConnectionPhase = AgentConnectionPhase.DISCONNECTED,
    val busy: Boolean = false,
    val switchingAgent: AgentKey? = null,
    val reconnectingInPlace: Boolean = false,
    val offlineConfirmed: Boolean = false,
    val errorCode: String? = null,
    val backendFailures: Map<String, String> = emptyMap(),
    val accountAvatarBase64: String? = null,
)

/** 当前 Agent 工作区目录水合阶段。 */
enum class WorkspaceCatalogPhase {
    IDLE,
    LOADING,
    READY,
    FAILED,
}

/** 第二层状态：挂靠在当前 Agent 下的工作区目录、激活工作区与各工作区会话缓存。 */
data class WorkspaceContextState(
    val agentKey: AgentKey? = null,
    val workspaces: List<RemoteWorkspace> = emptyList(),
    val catalogPhase: WorkspaceCatalogPhase = WorkspaceCatalogPhase.IDLE,
    val catalogErrorCode: String? = null,
    val activeWorkspace: EndpointKey? = null,
    val sessionsByWorkspace: Map<EndpointKey, Loadable<List<ConversationSummary>>> = emptyMap(),
    val models: RemoteModelCatalog? = null,
    val modelBusy: Boolean = false,
)

/** 冷启动宽限期与后台静默重连策略（支持测试注入）。 */
data class ConnectionRecoveryPolicy(
    val offlineGracePeriodMillis: Long = 6_000L,
    val graceRetryAttempts: Int = 2,
    val graceRetryDelayMillis: Long = 1_500L,
    val backgroundReconnectInitialDelayMillis: Long = 5_000L,
    val backgroundReconnectMaxDelayMillis: Long = 30_000L,
)

private val AUTH_REVOKED_ERROR_CODES = setOf(
    "ACCOUNT_AUTH_REQUIRED",
    "TOKEN_EXPIRED",
    "AUTH_INVALID",
    "DEVICE_REVOKED",
    "SESSION_REVOKED",
    "REFRESH_TOKEN_REUSED",
    "UNAUTHORIZED",
    "SESSION_EXPIRED",
)

/** 仅识别明确的凭据撤销或认证拒绝；普通网络中断、超时与 Host 离线一律返回 false。 */
internal fun isAuthRevokedError(code: String?): Boolean {
    val normalized = code?.trim()?.uppercase() ?: return false
    return normalized
        .split(':', ' ', ',', ';', '\t', '\n')
        .any { token -> token.isNotEmpty() && token in AUTH_REVOKED_ERROR_CODES }
}
