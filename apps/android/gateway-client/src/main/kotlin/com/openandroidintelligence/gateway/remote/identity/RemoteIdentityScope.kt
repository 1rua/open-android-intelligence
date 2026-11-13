package com.openandroidintelligence.gateway.remote.identity

import com.openandroidintelligence.gateway.remote.crypto.NoiseIkChannel
import java.security.SecureRandom

enum class RemoteRole(val wireValue: String) {
    CLIENT("client"),
    HOST("host"),
}

/** 规范化 origin 由连接层提供；身份层只保留它，不重新解析用户服务地址。 */
data class RemoteIdentityScope(
    val origin: String,
    val accountId: String,
    val role: RemoteRole,
) {
    init {
        require(origin.isNotEmpty() && origin == origin.trim()) { "REMOTE_ORIGIN_REQUIRED" }
        require(accountId.isNotEmpty()) { "REMOTE_ACCOUNT_REQUIRED" }
        require('\u0000' !in origin && '\u0000' !in accountId) { "REMOTE_SCOPE_INVALID" }
    }

    val storageKey: String get() = "$origin\u0000$accountId\u0000${role.wireValue}"
}

@JvmInline
value class RemoteDeviceId(val value: String) {
    init {
        require(value.isNotEmpty()) { "REMOTE_DEVICE_REQUIRED" }
        require(value.length <= 128) { "REMOTE_DEVICE_INVALID" }
    }
}

/** X25519 静态身份；私钥只在需要加密或注册时短暂取出。 */
data class RemoteStaticIdentity(
    val deviceId: RemoteDeviceId,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val generation: Long = 1L,
) {
    init {
        require(privateKey.size == NoiseIkChannel.KEY_BYTES) { "REMOTE_X25519_PRIVATE_INVALID" }
        require(publicKey.size == NoiseIkChannel.KEY_BYTES) { "REMOTE_X25519_PUBLIC_INVALID" }
        require(generation > 0) { "REMOTE_IDENTITY_GENERATION_INVALID" }
    }

    fun privateKeyCopy(): ByteArray = privateKey.copyOf()
    fun publicKeyCopy(): ByteArray = publicKey.copyOf()
}

object RemoteStaticIdentityFactory {
    fun generate(deviceId: RemoteDeviceId, random: SecureRandom = SecureRandom(), generation: Long = 1L): RemoteStaticIdentity {
        val privateKey = ByteArray(NoiseIkChannel.KEY_BYTES)
        random.nextBytes(privateKey)
        return try {
            val publicKey = NoiseIkChannel.publicKeyFor(privateKey)
            RemoteStaticIdentity(deviceId, privateKey.copyOf(), publicKey, generation)
        } finally {
            privateKey.fill(0)
        }
    }
}

data class RemoteTokenPair(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: Long,
) {
    init {
        require(accessToken.length >= 16) { "REMOTE_ACCESS_TOKEN_INVALID" }
        require(refreshToken.length >= 16) { "REMOTE_REFRESH_TOKEN_INVALID" }
        require(accessTokenExpiresAt > 0) { "REMOTE_ACCESS_EXPIRY_INVALID" }
        require(refreshTokenExpiresAt > 0) { "REMOTE_REFRESH_EXPIRY_INVALID" }
    }
}

/** Account login 返回的短期授权，只能在内存中传给两个角色注册请求。 */
@JvmInline
value class RemoteAccountAuthorizationToken(val value: String) {
    init { require(value.length >= 16) { "REMOTE_ACCOUNT_TOKEN_INVALID" } }
}

data class RemotePeerPin(
    val peerRole: RemoteRole,
    val peerDeviceId: RemoteDeviceId,
    val publicKey: ByteArray,
) {
    init { require(publicKey.size == NoiseIkChannel.KEY_BYTES) { "REMOTE_PEER_KEY_INVALID" } }

    fun publicKeyCopy(): ByteArray = publicKey.copyOf()
}

data class RemoteRoleCredential(
    val identity: RemoteStaticIdentity,
    val tokens: RemoteTokenPair,
    val peers: List<RemotePeerPin> = emptyList(),
) {
    init {
        require(peers.map { it.peerRole to it.peerDeviceId }.distinct().size == peers.size) {
            "REMOTE_PEER_PIN_DUPLICATE"
        }
    }

    fun pin(peer: RemotePeerPin): RemoteRoleCredential =
        copy(peers = peers.filterNot { it.peerRole == peer.peerRole && it.peerDeviceId == peer.peerDeviceId } + peer)

    fun requirePeerPin(peerRole: RemoteRole, peerDeviceId: RemoteDeviceId, publicKey: ByteArray) {
        val known = peers.firstOrNull { it.peerRole == peerRole && it.peerDeviceId == peerDeviceId }
            ?: throw IllegalStateException("REMOTE_PEER_UNPINNED")
        if (!known.publicKey.contentEquals(publicKey)) throw IllegalStateException("REMOTE_PEER_PIN_MISMATCH")
    }
}
