package com.openandroidintelligence.gateway.remote.identity

import com.openandroidintelligence.gateway.remote.crypto.NoiseIkChannel
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.schema.Json
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.Collections

enum class RemoteRole(val wireValue: String) {
    CLIENT("client"),
    HOST("host"),
}

/** 只接受连接层已规范化的 origin；拒绝大小写和默认端口等别名。 */
data class RemoteIdentityScope(
    val origin: String,
    val accountId: String,
    val role: RemoteRole,
) {
    init {
        require(origin.isNotEmpty() && origin == origin.trim()) { "REMOTE_ORIGIN_REQUIRED" }
        require(accountId.isNotEmpty()) { "REMOTE_ACCOUNT_REQUIRED" }
        require('\u0000' !in origin && '\u0000' !in accountId) { "REMOTE_SCOPE_INVALID" }
        val uri = try { URI(origin) } catch (_: Exception) { throw IllegalArgumentException("REMOTE_ORIGIN_INVALID") }
        require(uri.scheme in setOf("https", "http") && uri.host != null && uri.host == uri.host.lowercase() &&
            uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port != (if (uri.scheme == "https") 443 else 80) &&
            origin == "${uri.scheme}://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}") { "REMOTE_ORIGIN_NOT_CANONICAL" }
    }

    fun authenticatedData(): ByteArray = Json.canonical(Json.of(listOf("remote-identity/v1", origin, accountId, role.wireValue))).encodeToByteArray()

    val storageKey: String get() = RemoteBase64Url.encode(MessageDigest.getInstance("SHA-256").digest(authenticatedData()))
}

@JvmInline
value class RemoteDeviceId(val value: String) {
    init {
        require(value.isNotEmpty()) { "REMOTE_DEVICE_REQUIRED" }
        require(value.length <= 128) { "REMOTE_DEVICE_INVALID" }
    }
}

/** X25519 静态身份；私钥只在需要加密或注册时短暂取出。 */
class RemoteStaticIdentity(
    val deviceId: RemoteDeviceId,
    privateKey: ByteArray,
    publicKey: ByteArray,
    val generation: Long = 1L,
) {
    private val privateBytes = privateKey.copyOf()
    private val publicBytes = publicKey.copyOf()

    init {
        require(privateKey.size == NoiseIkChannel.KEY_BYTES) { "REMOTE_X25519_PRIVATE_INVALID" }
        require(publicKey.size == NoiseIkChannel.KEY_BYTES) { "REMOTE_X25519_PUBLIC_INVALID" }
        require(generation > 0) { "REMOTE_IDENTITY_GENERATION_INVALID" }
        require(MessageDigest.isEqual(NoiseIkChannel.publicKeyFor(privateBytes), publicBytes)) { "REMOTE_X25519_KEY_PAIR_INVALID" }
    }

    val privateKey: ByteArray get() = privateKeyCopy()
    val publicKey: ByteArray get() = publicKeyCopy()
    fun privateKeyCopy(): ByteArray = privateBytes.copyOf()
    fun publicKeyCopy(): ByteArray = publicBytes.copyOf()
    fun sameIdentity(other: RemoteStaticIdentity): Boolean = deviceId == other.deviceId && generation == other.generation &&
        MessageDigest.isEqual(privateBytes, other.privateBytes) && MessageDigest.isEqual(publicBytes, other.publicBytes)
    override fun toString(): String = "RemoteStaticIdentity(deviceId=$deviceId,generation=$generation,密钥=已隐藏)" // i18n-ignore: 仅内部诊断脱敏文本
}

object RemoteStaticIdentityFactory {
    fun generate(deviceId: RemoteDeviceId, generation: Long = 1L): RemoteStaticIdentity = generateForTest(deviceId, SecureRandom(), generation)

    internal fun generateForTest(deviceId: RemoteDeviceId, random: SecureRandom, generation: Long = 1L): RemoteStaticIdentity {
        val privateKey = ByteArray(NoiseIkChannel.KEY_BYTES)
        random.nextBytes(privateKey)
        return try {
            val publicKey = NoiseIkChannel.publicKeyFor(privateKey)
            RemoteStaticIdentity(deviceId, privateKey, publicKey, generation)
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
    override fun toString(): String = "RemoteTokenPair(凭据=已隐藏)" // i18n-ignore: 仅内部诊断脱敏文本
}

/** Account login 返回的短期授权，只能在内存中传给两个角色注册请求。 */
@JvmInline
value class RemoteAccountAuthorizationToken(val value: String) {
    init { require(value.length >= 16) { "REMOTE_ACCOUNT_TOKEN_INVALID" } }
    override fun toString(): String = "RemoteAccountAuthorizationToken(凭据=已隐藏)" // i18n-ignore: 仅内部诊断脱敏文本
}

class RemotePeerPin(
    val peerRole: RemoteRole,
    val peerDeviceId: RemoteDeviceId,
    publicKey: ByteArray,
) {
    private val publicBytes = publicKey.copyOf()
    init { require(publicKey.size == NoiseIkChannel.KEY_BYTES) { "REMOTE_PEER_KEY_INVALID" } }

    val publicKey: ByteArray get() = publicKeyCopy()
    fun publicKeyCopy(): ByteArray = publicBytes.copyOf()
    fun matches(value: ByteArray): Boolean = MessageDigest.isEqual(publicBytes, value)
    override fun toString(): String = "RemotePeerPin(peerRole=$peerRole,peerDeviceId=$peerDeviceId,公钥=已隐藏)" // i18n-ignore: 仅内部诊断脱敏文本
}

class RemoteRoleCredential(
    val identity: RemoteStaticIdentity,
    val tokens: RemoteTokenPair,
    peers: List<RemotePeerPin> = emptyList(),
    internal val revision: String = UUID.randomUUID().toString(),
) {
    val peers: List<RemotePeerPin> = Collections.unmodifiableList(peers.toList())
    init {
        require(revision.isNotEmpty()) { "REMOTE_CREDENTIAL_REVISION_INVALID" }
        require(peers.map { it.peerRole to it.peerDeviceId }.distinct().size == peers.size) {
            "REMOTE_PEER_PIN_DUPLICATE"
        }
    }

    fun copy(identity: RemoteStaticIdentity = this.identity, tokens: RemoteTokenPair = this.tokens, peers: List<RemotePeerPin> = this.peers): RemoteRoleCredential =
        RemoteRoleCredential(identity, tokens, peers, revision)

    internal fun nextRevision(): RemoteRoleCredential = RemoteRoleCredential(identity, tokens, peers)

    fun pin(peer: RemotePeerPin): RemoteRoleCredential {
        val known = peers.firstOrNull { it.peerRole == peer.peerRole && it.peerDeviceId == peer.peerDeviceId }
        if (known != null) {
            requirePeerPin(peer.peerRole, peer.peerDeviceId, peer.publicKey)
            return this
        }
        return copy(peers = peers + peer)
    }

    fun requirePeerPin(peerRole: RemoteRole, peerDeviceId: RemoteDeviceId, publicKey: ByteArray) {
        val known = peers.firstOrNull { it.peerRole == peerRole && it.peerDeviceId == peerDeviceId }
            ?: throw IllegalStateException("REMOTE_PEER_UNPINNED")
        if (!known.matches(publicKey)) throw IllegalStateException("REMOTE_PEER_PIN_MISMATCH")
    }
    override fun toString(): String = "RemoteRoleCredential(identity=$identity,peers=${peers.size},凭据=已隐藏)" // i18n-ignore: 仅内部诊断脱敏文本
}
