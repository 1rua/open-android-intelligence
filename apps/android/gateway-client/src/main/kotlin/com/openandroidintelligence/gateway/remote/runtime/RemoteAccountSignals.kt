package com.openandroidintelligence.gateway.remote.runtime

import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope

/** Client 终态及其准确的账号授权代际。 */
data class RemoteClientFailure(val account: RemoteActiveAccount, val code: String)

/** 已授权数据通道的真实终止；由连接代际围栏决定它是否仍影响当前工作区。 */
data class RemoteDataConnectionClosure(
    val scope: RemoteRpcConnectionScope,
    val code: String,
)

/** 控制面不可恢复的授权、身份、加密与协议终态；传输、清理和未知内部错误继续退避。 */
internal val remoteTerminalControlCodes = setOf(
    "CONNECTION_REPLACED", "DEVICE_REVOKED", "TOKEN_REUSED", "TOKEN_EXPIRED", "AUTH_INVALID",
    "MEMBERSHIP_REQUIRED", "PERMISSION_DENIED", "DEVICE_OWNERSHIP_REQUIRED", "REMOTE_IDENTITY_CORRUPTED",
    "REMOTE_CREDENTIAL_PERSISTENCE_FAILED", "REMOTE_REFRESH_PERSISTENCE_FAILED", "PEER_IDENTITY_MISMATCH",
    "SECURE_CHANNEL_FAILED", "NOISE_AUTHENTICATION_FAILED", "NOISE_INITIALIZATION_FAILED", "UNSUPPORTED_VERSION",
    "INVALID_MESSAGE",
)
