package com.openandroidintelligence.gateway.remote.auth

import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemoteRoleCredential
import com.openandroidintelligence.gateway.remote.identity.RemoteDeviceId
import com.openandroidintelligence.gateway.remote.identity.RemotePeerPin
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentity
import com.openandroidintelligence.gateway.remote.identity.RemoteTokenPair
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/** 生产实现用加密文件/Keystore；接口要求 writeAtomic 不得半写入新凭据。 */
interface RemoteIdentityPersistence {
    fun read(scopeKey: String): ByteArray?
    fun writeAtomic(scopeKey: String, value: ByteArray)
    fun clear(scopeKey: String)
}

/** 读路径不能创建新密钥；seal/open 必须把 associatedData 交给认证加密以绑定作用域。 */
interface RemoteIdentityProtector {
    fun hasKey(scopeKey: String): Boolean
    fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

class RemoteIdentityLockedException : IllegalStateException("REMOTE_IDENTITY_LOCKED")
class RemoteIdentityCorruptedException : IllegalStateException("REMOTE_IDENTITY_CORRUPTED")
class RemoteRegistrationSupersededException : IllegalStateException("REMOTE_REGISTRATION_SUPERSEDED")
class RemoteRegistrationPeerPinMismatchException : IllegalStateException("REMOTE_PEER_PIN_MISMATCH")
class RemoteCredentialCleanupException : IllegalStateException("REMOTE_CREDENTIAL_CLEANUP_FAILED")

/** 仅由同一 Store 创建和验证；实例身份即本轮授权代次，不随凭据清空后重用。 */
class RemoteRegistrationTicket internal constructor(internal val origin: String, internal val accountId: String)

/**
 * 按 origin、账号和角色独立保存一份凭据。登录到另一个 origin 不会读到旧身份，
 * Client/Host 的轮换也不会相互覆盖。调用方须为同一持久化仓库复用一个实例，
 * 生产持久化的跨进程互斥与原子文件替换仍由后续适配负责。
 */
class RemoteIdentityStore(
    private val persistence: RemoteIdentityPersistence,
    private val protector: RemoteIdentityProtector,
    private val codec: RemoteIdentityRecordCodec = RemoteIdentityRecordCodec,
) {
    private var activeRegistration: RemoteRegistrationTicket? = null
    private val revokedScopes = mutableSetOf<String>()
    private val pendingAccountCleanup = mutableSetOf<Pair<String, String>>()

    /** 新登录或切换账号/origin 撤销旧票据，但不清除任何已有凭据或对端 pin。 */
    @Synchronized
    fun beginRegistration(origin: String, accountId: String): RemoteRegistrationTicket {
        RemoteIdentityScope(origin, accountId, RemoteRole.CLIENT)
        activeRegistration = null
        if ((origin to accountId) in pendingAccountCleanup) throw RemoteCredentialCleanupException()
        return RemoteRegistrationTicket(origin, accountId).also { activeRegistration = it }
    }

    @Synchronized
    fun requireRegistrationCurrent(ticket: RemoteRegistrationTicket) {
        if (activeRegistration !== ticket) throw RemoteRegistrationSupersededException()
    }

    /** 回执提交、撤销票据和本地 pin 合并使用同一把锁，旧回执不能在退出后重新落盘。 */
    @Synchronized
    fun commitRegistration(ticket: RemoteRegistrationTicket, scope: RemoteIdentityScope, credential: RemoteRoleCredential): RemoteRoleCredential {
        requireRegistrationCurrent(ticket)
        if (scope.origin != ticket.origin || scope.accountId != ticket.accountId) throw RemoteRegistrationSupersededException()
        val current = load(scope)
        var merged = credential.copy(peers = current?.peers ?: emptyList())
        credential.peers.forEach { peer ->
            val known = merged.peers.firstOrNull { it.peerRole == peer.peerRole && it.peerDeviceId == peer.peerDeviceId }
            if (known != null && !known.matches(peer.publicKey)) throw RemoteRegistrationPeerPinMismatchException()
            merged = merged.pin(peer)
        }
        return save(scope, merged)
    }

    /** App 的退出和切换生命周期须调用此入口；本切片尚未连接到产品界面。 */
    @Synchronized
    fun invalidateAccount(origin: String, accountId: String) {
        val active = activeRegistration
        if (active?.origin == origin && active.accountId == accountId) activeRegistration = null
    }

    @Synchronized
    fun load(scope: RemoteIdentityScope): RemoteRoleCredential? {
        val key = scope.storageKey
        if (key in revokedScopes) return null
        val ciphertext = persistence.read(key) ?: return null
        if (!protector.hasKey(key)) throw RemoteIdentityLockedException()
        val plaintext = try { protector.open(key, ciphertext, scope.authenticatedData()) }
        catch (_: Exception) { throw RemoteIdentityCorruptedException() }
        return try { codec.decode(plaintext) }
        catch (_: Exception) { throw RemoteIdentityCorruptedException() }
        finally { plaintext.fill(0) }
    }

    @Synchronized
    fun save(scope: RemoteIdentityScope, credential: RemoteRoleCredential): RemoteRoleCredential {
        val key = scope.storageKey
        val saved = credential.nextRevision()
        val plaintext = codec.encode(saved)
        try {
            val ciphertext = protector.seal(key, plaintext, scope.authenticatedData())
            persistence.writeAtomic(key, ciphertext)
            revokedScopes.remove(key)
        } finally {
            plaintext.fill(0)
        }
        return saved
    }

    @Synchronized
    fun rotateTokens(scope: RemoteIdentityScope, expected: RemoteRoleCredential, next: RemoteRoleCredential): RemoteRoleCredential? {
        val current = load(scope) ?: return null
        if (current.revision != expected.revision || !current.identity.sameIdentity(expected.identity) ||
            current.tokens != expected.tokens || !current.identity.sameIdentity(next.identity)) return null
        return save(scope, next)
    }

    @Synchronized
    fun clear(scope: RemoteIdentityScope) {
        invalidateAccount(scope.origin, scope.accountId)
        revokedScopes += scope.storageKey
        persistence.clear(scope.storageKey)
    }

    /**
     * 先在进程内同时撤销两角色，再分别清理持久化记录；任一失败均报告未完成清理。
     * 跨重启仍需生产适配持久化撤销标记，本入口不宣称两个文件删除具有磁盘事务性。
     */
    @Synchronized
    fun clearAccount(origin: String, accountId: String) {
        val scopes = RemoteRole.entries.map { RemoteIdentityScope(origin, accountId, it) }
        invalidateAccount(origin, accountId)
        revokedScopes += scopes.map { it.storageKey }
        pendingAccountCleanup += origin to accountId
        var failed = false
        scopes.forEach { scope ->
            try { persistence.clear(scope.storageKey) }
            catch (_: Exception) { failed = true }
        }
        if (failed) throw RemoteCredentialCleanupException()
        pendingAccountCleanup -= origin to accountId
    }
}

/**
 * 编码器只使用 Remote 私有 JSON，不依赖旧 Gateway 身份类型。
 * 密钥和 pin 以无填充 Base64URL 表达，解码后由类型构造器再次校验长度。
 */
object RemoteIdentityRecordCodec {
    fun encode(value: RemoteRoleCredential): ByteArray {
        val privateKey = value.identity.privateKeyCopy()
        return try {
            Json.canonical(Json.of(mapOf(
                "v" to 1, "deviceId" to value.identity.deviceId.value,
                "privateKey" to RemoteBase64Url.encode(privateKey), "publicKey" to RemoteBase64Url.encode(value.identity.publicKey),
                "generation" to value.identity.generation.toString(), "revision" to value.revision,
                "accessToken" to value.tokens.accessToken, "accessTokenExpiresAt" to value.tokens.accessTokenExpiresAt.toString(),
                "refreshToken" to value.tokens.refreshToken, "refreshTokenExpiresAt" to value.tokens.refreshTokenExpiresAt.toString(),
                "peers" to value.peers.map { peer -> mapOf("role" to peer.peerRole.wireValue,
                    "deviceId" to peer.peerDeviceId.value, "publicKey" to RemoteBase64Url.encode(peer.publicKey)) },
            ))).encodeToByteArray()
        } finally {
            privateKey.fill(0)
        }
    }

    fun decode(bytes: ByteArray): RemoteRoleCredential {
        val fields = RemoteWireJson.decode(bytes) as? JsonValue.JObject ?: invalid()
        if (fields.fields.map { it.first }.toSet() != setOf("v", "deviceId", "privateKey", "publicKey", "generation", "revision",
                "accessToken", "accessTokenExpiresAt", "refreshToken", "refreshTokenExpiresAt", "peers") || JsonFields.int(fields, "v") != 1) invalid()
        val privateKey = RemoteBase64Url.decode(text(fields, "privateKey"))
        return try {
            val publicKey = RemoteBase64Url.decode(text(fields, "publicKey"))
            val peers = (JsonFields.array(JsonFields.field(fields, "peers")) ?: invalid()).items.map { item ->
                val peer = item as? JsonValue.JObject ?: invalid()
                if (peer.fields.map { it.first }.toSet() != setOf("role", "deviceId", "publicKey")) invalid()
                RemotePeerPin(RemoteRole.entries.first { it.wireValue == text(peer, "role") }, RemoteDeviceId(text(peer, "deviceId")),
                    RemoteBase64Url.decode(text(peer, "publicKey")))
            }
            RemoteRoleCredential(
                RemoteStaticIdentity(RemoteDeviceId(text(fields, "deviceId")), privateKey, publicKey, text(fields, "generation").toLong()),
                RemoteTokenPair(text(fields, "accessToken"), text(fields, "accessTokenExpiresAt").toLong(),
                    text(fields, "refreshToken"), text(fields, "refreshTokenExpiresAt").toLong()), peers, text(fields, "revision"),
            )
        } finally {
            privateKey.fill(0)
        }
    }

    private fun text(value: JsonValue.JObject, name: String): String = JsonFields.string(value, name) ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("REMOTE_RECORD_INVALID")
}
