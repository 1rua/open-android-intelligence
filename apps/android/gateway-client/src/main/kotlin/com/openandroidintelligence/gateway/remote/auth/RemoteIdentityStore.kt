package com.openandroidintelligence.gateway.remote.auth

import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemoteRoleCredential

/** 生产实现用加密文件/Keystore；接口要求 writeAtomic 不得半写入新凭据。 */
interface RemoteIdentityPersistence {
    fun read(scopeKey: String): ByteArray?
    fun writeAtomic(scopeKey: String, value: ByteArray)
    fun clear(scopeKey: String)
}

/** 读路径不能创建新密钥；缺少包装密钥时必须显式报告锁定。 */
interface RemoteIdentityProtector {
    fun hasKey(scopeKey: String): Boolean
    fun seal(scopeKey: String, plaintext: ByteArray): ByteArray
    fun open(scopeKey: String, ciphertext: ByteArray): ByteArray
}

class RemoteIdentityLockedException : IllegalStateException("REMOTE_IDENTITY_LOCKED")
class RemoteIdentityCorruptedException : IllegalStateException("REMOTE_IDENTITY_CORRUPTED")

/**
 * 按 origin、账号和角色独立保存一份凭据。登录到另一个 origin 不会读到旧身份，
 * Client/Host 的轮换也不会相互覆盖。
 */
class RemoteIdentityStore(
    private val persistence: RemoteIdentityPersistence,
    private val protector: RemoteIdentityProtector,
    private val codec: RemoteIdentityRecordCodec = RemoteIdentityRecordCodec,
) {
    @Synchronized
    fun load(scope: RemoteIdentityScope): RemoteRoleCredential? {
        val key = scope.storageKey
        val ciphertext = persistence.read(key) ?: return null
        if (!protector.hasKey(key)) throw RemoteIdentityLockedException()
        val plaintext = try { protector.open(key, ciphertext) }
        catch (_: Exception) { throw RemoteIdentityCorruptedException() }
        return try { codec.decode(plaintext) }
        catch (_: Exception) { throw RemoteIdentityCorruptedException() }
        finally { plaintext.fill(0) }
    }

    @Synchronized
    fun save(scope: RemoteIdentityScope, credential: RemoteRoleCredential) {
        val key = scope.storageKey
        val plaintext = codec.encode(credential)
        try {
            val ciphertext = protector.seal(key, plaintext)
            persistence.writeAtomic(key, ciphertext)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun rotateTokens(scope: RemoteIdentityScope, expectedRefreshToken: String, next: RemoteRoleCredential): Boolean {
        val current = load(scope) ?: return false
        if (current.tokens.refreshToken != expectedRefreshToken) return false
        save(scope, next)
        return true
    }

    @Synchronized
    fun clear(scope: RemoteIdentityScope) = persistence.clear(scope.storageKey)
}

/**
 * 编码器只使用 Remote 私有 JSON，不依赖旧 Gateway 身份类型。
 * 密钥和 pin 以无填充 Base64URL 表达，解码后由类型构造器再次校验长度。
 */
object RemoteIdentityRecordCodec {
    private val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
    private val decoder = java.util.Base64.getUrlDecoder()

    fun encode(value: RemoteRoleCredential): ByteArray {
        val peerText = value.peers.joinToString(";") { peer ->
            listOf(peer.peerRole.wireValue, peer.peerDeviceId.value, encoder.encodeToString(peer.publicKey)).joinToString(",")
        }
        return listOf(
            "1", value.identity.deviceId.value, encoder.encodeToString(value.identity.privateKey),
            encoder.encodeToString(value.identity.publicKey), value.identity.generation.toString(),
            value.tokens.accessToken, value.tokens.accessTokenExpiresAt.toString(),
            value.tokens.refreshToken, value.tokens.refreshTokenExpiresAt.toString(), peerText,
        ).joinToString("\n").encodeToByteArray()
    }

    fun decode(bytes: ByteArray): RemoteRoleCredential {
        val fields = bytes.decodeToString().split('\n')
        if (fields.size != 10 || fields[0] != "1") throw IllegalArgumentException("RECORD_VERSION")
        fun b64(index: Int) = decoder.decode(fields[index]).also { if (it.isEmpty()) throw IllegalArgumentException("RECORD_FIELD") }
        val privateKey = b64(2)
        val publicKey = b64(3)
        val peers = if (fields[9].isEmpty()) emptyList() else fields[9].split(';').map { item ->
            val pieces = item.split(',')
            if (pieces.size != 3) throw IllegalArgumentException("RECORD_PEER")
            com.openandroidintelligence.gateway.remote.identity.RemotePeerPin(
                com.openandroidintelligence.gateway.remote.identity.RemoteRole.entries.first { it.wireValue == pieces[0] },
                com.openandroidintelligence.gateway.remote.identity.RemoteDeviceId(pieces[1]),
                b64From(pieces[2]),
            )
        }
        return try {
            RemoteRoleCredential(
                com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentity(
                    com.openandroidintelligence.gateway.remote.identity.RemoteDeviceId(fields[1]), privateKey.copyOf(), publicKey.copyOf(), fields[4].toLong(),
                ),
                com.openandroidintelligence.gateway.remote.identity.RemoteTokenPair(
                    fields[5], fields[6].toLong(), fields[7], fields[8].toLong(),
                ), peers,
            )
        } finally {
            privateKey.fill(0); publicKey.fill(0)
        }
    }

    private fun b64From(value: String): ByteArray = decoder.decode(value)
}
