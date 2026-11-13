package com.openandroidintelligence.gateway.remote

import com.openandroidintelligence.gateway.remote.api.RemoteAuthCodec
import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.crypto.NoiseIkChannel
import com.openandroidintelligence.gateway.remote.crypto.NoiseRole
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.RemoteAccountCodec
import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding
import com.openandroidintelligence.gateway.remote.protocol.RemoteProtocolException
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RemoteIdentityTest {
    private val origin = "https://remote.example"
    private val clientId = "0198f7c0-1234-7abc-8def-0123456789ab"
    private val hostId = "550e8400-e29b-41d4-a716-446655440000"
    private val clientScope = RemoteIdentityScope(origin, "account-a", RemoteRole.CLIENT)
    private val hostScope = RemoteIdentityScope(origin, "account-a", RemoteRole.HOST)

    @Test
    fun origin账号与角色隔离并且切换后可回到原身份() {
        val fixture = Fixture()
        val scopes = listOf(clientScope, hostScope, clientScope.copy(origin = "https://other.example"), clientScope.copy(accountId = "account-b"))
        scopes.forEachIndexed { index, scope -> fixture.store().save(scope, credential(if (index % 2 == 0) clientId else hostId, index + 1L)) }
        assertEquals(4, scopes.map { it.storageKey }.distinct().size)
        scopes.forEachIndexed { index, scope -> assertEquals(index + 1L, fixture.store().load(scope)!!.identity.generation) }
        assertEquals(1L, fixture.store().load(clientScope)!!.identity.generation)
    }

    @Test
    fun scope拒绝分隔符碰撞和未经规范化的origin别名() {
        listOf("https://REMOTE.example", "https://remote.example:443", "https://remote.example/", "https://u@remote.example", "https://remote.example?q=x").forEach {
            assertThrows(IllegalArgumentException::class.java) { RemoteIdentityScope(it, "account-a", RemoteRole.CLIENT) }
        }
        assertThrows(IllegalArgumentException::class.java) { clientScope.copy(accountId = "a\u0000host") }
        assertFalse(clientScope.storageKey.contains("account-a"))
        assertFalse(clientScope.authenticatedData().contentEquals(hostScope.authenticatedData()))
    }

    @Test
    fun 复制另一作用域的密文无法通过认证() {
        val fixture = Fixture()
        fixture.store().save(clientScope, credential(clientId))
        fixture.store().save(hostScope, credential(hostId))
        fixture.persistence.values[hostScope.storageKey] = fixture.persistence.values[clientScope.storageKey]!!.copyOf()
        assertThrows(RemoteIdentityCorruptedException::class.java) { fixture.store().load(hostScope) }
        assertNotNull(fixture.store().load(clientScope))
    }

    @Test
    fun 一角色失败不覆盖另一角色的成功凭据或原有凭据() = runBlocking {
        listOf(RemoteRole.CLIENT, RemoteRole.HOST).forEach { failingRole ->
            val fixture = Fixture()
            val client = credential(clientId)
            val host = credential(hostId)
            val failedScope = if (failingRole == RemoteRole.CLIENT) clientScope else hostScope
            val original = fixture.store().save(failedScope, if (failingRole == RemoteRole.CLIENT) client else host)
            val result = RemoteLoginCoordinator(fixture.store(), registrar { scope, identity ->
                if (scope.role == failingRole) error("包含短期密码和token的上游异常")
                RemoteRoleCredential(identity, tokens(2))
            }).registerRoles(origin, "account-a", accountToken(), client.identity, host.identity)
            val failed = if (failingRole == RemoteRole.CLIENT) result.client else result.host
            val succeeded = if (failingRole == RemoteRole.CLIENT) result.host else result.client
            assertEquals(RemoteRoleRegistrationState.FAILED, failed.state)
            assertEquals("REMOTE_ROLE_REGISTRATION_FAILED", failed.errorCode)
            assertEquals(RemoteRoleRegistrationState.REGISTERED, succeeded.state)
            assertEquals(original.revision, fixture.store().load(failedScope)!!.revision)
        }
    }

    @Test
    fun 双角色拒绝复用设备ID或同一静态公钥() = runBlocking {
        val fixture = Fixture()
        val coordinator = RemoteLoginCoordinator(fixture.store(), registrar { _, identity -> RemoteRoleCredential(identity, tokens()) })
        val client = credential(clientId).identity
        val sameKey = RemoteStaticIdentity(RemoteDeviceId(hostId), client.privateKey, client.publicKey)
        listOf(client, sameKey).forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { coordinator.registerRoles(origin, "account-a", accountToken(), client, host) } }
        }
        assertTrue(fixture.persistence.values.isEmpty())
    }

    @Test
    fun 注册器回传另一身份时该角色失败且不能落盘() = runBlocking {
        val fixture = Fixture()
        val result = RemoteLoginCoordinator(fixture.store(), registrar { scope, identity ->
            if (scope.role == RemoteRole.CLIENT) credential(hostId) else RemoteRoleCredential(identity, tokens())
        }).registerRoles(origin, "account-a", accountToken(), credential(clientId).identity, credential(hostId).identity)
        assertEquals("REMOTE_REGISTERED_IDENTITY_MISMATCH", result.client.errorCode)
        assertEquals(RemoteRoleRegistrationState.REGISTERED, result.host.state)
        assertNull(fixture.store().load(clientScope))
    }

    @Test
    fun 协程取消会停止角色注册而不当成业务失败() {
        val fixture = Fixture()
        var registrations = 0
        val coordinator = RemoteLoginCoordinator(fixture.store(), registrar { _, _ -> registrations += 1; throw CancellationException("测试取消") })
        assertThrows(CancellationException::class.java) { runBlocking {
            coordinator.registerRoles(origin, "account-a", accountToken(), credential(clientId).identity, credential(hostId).identity)
        } }
        assertEquals(1, registrations)
        assertTrue(fixture.persistence.values.isEmpty())
    }

    @Test
    fun 轮换原子写失败后重新打开仍得到完整旧凭据() = runBlocking {
        val fixture = Fixture()
        val current = fixture.store().save(clientScope, credential(clientId))
        fixture.persistence.failWrites = true
        val result = RemoteTokenRotator(fixture.store(), RemoteTokenRefresher { _, _, _ -> tokens(2) }).rotate(clientScope)
        assertEquals("REMOTE_REFRESH_PERSISTENCE_FAILED", (result as RemoteTokenRotationResult.Failed).code)
        fixture.persistence.failWrites = false
        assertEquals(current.tokens, fixture.store().load(clientScope)!!.tokens)
        assertTrue(current.identity.sameIdentity(fixture.store().load(clientScope)!!.identity))
        assertEquals(current.revision, fixture.store().load(clientScope)!!.revision)
    }

    @Test
    fun 成功轮换只替换该角色token并留下可重开的一份凭据() = runBlocking {
        val fixture = Fixture()
        val client = fixture.store().save(clientScope, credential(clientId))
        val host = fixture.store().save(hostScope, credential(hostId))
        val result = RemoteTokenRotator(fixture.store(), RemoteTokenRefresher { scope, deviceId, refreshToken ->
            assertEquals(clientScope, scope); assertEquals(clientId, deviceId); assertEquals(client.tokens.refreshToken, refreshToken)
            tokens(2)
        }).rotate(clientScope) as RemoteTokenRotationResult.Rotated
        assertEquals(tokens(2), fixture.store().load(clientScope)!!.tokens)
        assertEquals(result.credential.revision, fixture.store().load(clientScope)!!.revision)
        assertNotEquals(client.revision, result.credential.revision)
        assertEquals(host.revision, fixture.store().load(hostScope)!!.revision)
    }

    @Test
    fun 轮换拒绝旧快照旧身份代次以及退出重登后的同token响应() {
        val fixture = Fixture()
        val store = fixture.store()
        val old = store.save(clientScope, credential(clientId))
        val newer = store.save(clientScope, credential(clientId, 2))
        assertNull(store.rotateTokens(clientScope, old, old.copy(tokens = tokens(2))))
        assertEquals(newer.revision, store.load(clientScope)!!.revision)
        store.clear(clientScope)
        val relogged = store.save(clientScope, newer)
        assertNull(store.rotateTokens(clientScope, newer, newer.copy(tokens = tokens(2))))
        assertEquals(relogged.revision, store.load(clientScope)!!.revision)
        assertNull(store.rotateTokens(clientScope, relogged, credential(hostId)))
    }

    @Test
    fun 刷新异常不暴露上游正文而取消继续向上传播() {
        val fixture = Fixture()
        fixture.store().save(clientScope, credential(clientId))
        val failed = runBlocking { RemoteTokenRotator(fixture.store(), RemoteTokenRefresher { _, _, _ -> error("secret-token-and-password") }).rotate(clientScope) }
        assertEquals("REMOTE_REFRESH_FAILED", (failed as RemoteTokenRotationResult.Failed).code)
        assertThrows(CancellationException::class.java) { runBlocking {
            RemoteTokenRotator(fixture.store(), RemoteTokenRefresher { _, _, _ -> throw CancellationException("测试取消") }).rotate(clientScope)
        } }
    }

    @Test
    fun 包装密钥丢失时锁定且读取不创建新密钥() {
        val fixture = Fixture()
        fixture.store().save(clientScope, credential(clientId))
        fixture.protector.keys.clear()
        assertThrows(RemoteIdentityLockedException::class.java) { fixture.store().load(clientScope) }
        assertTrue(fixture.protector.keys.isEmpty())
    }

    @Test
    fun 已确认peer公钥变化时拒绝覆盖且角色分别匹配() {
        val bytes = ByteArray(32) { 7 }
        val pinned = credential(clientId).pin(RemotePeerPin(RemoteRole.HOST, RemoteDeviceId(hostId), bytes))
        bytes.fill(0)
        pinned.peers.single().publicKey.fill(0)
        pinned.requirePeerPin(RemoteRole.HOST, RemoteDeviceId(hostId), ByteArray(32) { 7 })
        assertSame(pinned, pinned.pin(RemotePeerPin(RemoteRole.HOST, RemoteDeviceId(hostId), ByteArray(32) { 7 })))
        assertThrows(IllegalStateException::class.java) { pinned.pin(RemotePeerPin(RemoteRole.HOST, RemoteDeviceId(hostId), ByteArray(32) { 8 })) }
        assertThrows(IllegalStateException::class.java) { pinned.requirePeerPin(RemoteRole.CLIENT, RemoteDeviceId(hostId), ByteArray(32) { 7 }) }
    }

    @Test
    fun 生成与构造静态身份复制密钥数组且公私钥始终匹配() {
        val generated = RemoteStaticIdentityFactory.generate(RemoteDeviceId(clientId))
        assertTrue(generated.privateKeyCopy().any { it != 0.toByte() })
        assertArrayEquals(generated.publicKeyCopy(), NoiseIkChannel.publicKeyFor(generated.privateKeyCopy()))
        val privateKey = generated.privateKeyCopy()
        val publicKey = generated.publicKeyCopy()
        val identity = RemoteStaticIdentity(generated.deviceId, privateKey, publicKey)
        privateKey.fill(0); publicKey.fill(0); identity.privateKey.fill(0); identity.publicKey.fill(0)
        assertTrue(generated.sameIdentity(identity))
        assertArrayEquals(identity.publicKeyCopy(), NoiseIkChannel.publicKeyFor(identity.privateKeyCopy()))
    }

    @Test
    fun 旧P256身份和不匹配的X25519公私钥均被拒绝() {
        val old = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) { RemoteStaticIdentity(RemoteDeviceId(clientId), old.private.encoded, old.public.encoded) }
        val current = credential(clientId).identity
        assertThrows(IllegalArgumentException::class.java) { RemoteStaticIdentity(current.deviceId, current.privateKey, current.publicKey.also { it[0] = (it[0].toInt() xor 1).toByte() }) }
    }

    @Test
    fun 记录往返保留分隔符token与密钥并能建立Noise握手() {
        val client = credential(clientId).copy(tokens = RemoteTokenPair("access\nwith,delimiters;", 1_000, "refresh\nwith,delimiters;", 2_000))
        val restored = RemoteIdentityRecordCodec.decode(RemoteIdentityRecordCodec.encode(client))
        assertEquals(client.tokens, restored.tokens)
        assertTrue(client.identity.sameIdentity(restored.identity))
        val host = credential(hostId).identity
        val binding = RemoteConnectionBinding("identity-roundtrip", hostId, clientId)
        val a = NoiseIkChannel.create(NoiseRole.CLIENT_INITIATOR, binding, restored.identity.privateKey, host.publicKey)
        val b = NoiseIkChannel.create(NoiseRole.HOST_RESPONDER, binding, host.privateKey, restored.identity.publicKey)
        try {
            b.readHandshake(a.writeHandshake()); a.readHandshake(b.writeHandshake())
            assertArrayEquals("身份恢复".encodeToByteArray(), b.decrypt(a.encrypt("身份恢复".encodeToByteArray())))
        } finally { a.close(); b.close() }
    }

    @Test
    fun 账号授权密码和原始token不写入持久化且默认文本隐藏秘密() = runBlocking {
        val fixture = Fixture()
        val password = "password-for-login".toCharArray()
        val login = RemoteAuthCodec.accountLogin(" user@example.com ", password)
        assertTrue(password.all { it == '\u0000' })
        assertEquals("password-for-login", JsonFields.string(login.body, "password"))
        assertFalse(login.toString().contains("password-for-login"))
        val token = accountToken()
        val result = RemoteLoginCoordinator(fixture.store(), registrar { _, identity -> RemoteRoleCredential(identity, tokens()) })
            .registerRoles(origin, "account-a", token, credential(clientId).identity, credential(hostId).identity)
        val disk = fixture.persistence.values.values.joinToString { it.decodeToString() }
        listOf("password-for-login", token.value, tokens().accessToken, tokens().refreshToken).forEach { assertFalse(disk.contains(it)) }
        assertTrue(fixture.protector.plaintextBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
        assertFalse(result.toString().contains(tokens().accessToken))
        assertFalse(token.toString().contains(token.value))
    }

    @Test
    fun 请求codec通过固定上游注册刷新校验且ownedRole仅构建请求() {
        val identity = credential(clientId).identity
        RemoteRole.entries.forEach { role ->
            val request = RemoteAuthCodec.registerAccountRole(identity, role, accountToken())
            assertEquals("/api/v1/devices/register", request.path)
            assertEquals(Json.canonical(request.body), Json.canonical(RemoteAccountCodec.parseRegistration(request.body)))
            assertEquals(role.wireValue, JsonFields.string(JsonFields.obj(JsonFields.field(request.body, "device")), "role"))
            assertNull(JsonFields.string(request.body, "authorization"))
            assertEquals(accountToken().value, request.bearerToken)
        }
        assertEquals("/api/v1/devices/register-owned-role", RemoteAuthCodec.registerOwnedRole(identity, RemoteRole.HOST, tokens().accessToken).path)
        assertEquals("/api/v1/auth/refresh", RemoteAuthCodec.refresh(identity.deviceId, tokens().refreshToken).path)
        assertThrows(RemoteProtocolException::class.java) { RemoteAuthCodec.refresh(RemoteDeviceId("invalid-device"), tokens().refreshToken) }
    }

    @Test
    fun token回执codec复用严格固定上游字段与整数时间校验() {
        val pair = tokens()
        val json = Json.of(mapOf("accessToken" to pair.accessToken, "accessTokenExpiresAt" to pair.accessTokenExpiresAt,
            "refreshToken" to pair.refreshToken, "refreshTokenExpiresAt" to pair.refreshTokenExpiresAt)) as JsonValue.JObject
        assertEquals(pair, RemoteAuthCodec.parseTokenPair(json))
        assertThrows(RemoteProtocolException::class.java) { RemoteAuthCodec.parseTokenPair(JsonValue.JObject(json.fields + ("account" to Json.of("account-a")))) }
        assertThrows(RemoteProtocolException::class.java) { RemoteAuthCodec.parseTokenPair(JsonValue.JObject(json.fields.map {
            if (it.first == "accessTokenExpiresAt") it.first to JsonValue.JNumber("1.5") else it
        })) }
    }

    private fun credential(deviceId: String, generation: Long = 1): RemoteRoleCredential {
        val privateKey = MessageDigest.getInstance("SHA-256").digest(deviceId.encodeToByteArray())
        return RemoteRoleCredential(RemoteStaticIdentity(RemoteDeviceId(deviceId), privateKey, NoiseIkChannel.publicKeyFor(privateKey), generation), tokens())
    }
    private fun tokens(version: Int = 1) = RemoteTokenPair("access-token-$version-123456", 1_000L * version, "refresh-token-$version-123456", 2_000L * version)
    private fun accountToken() = RemoteAccountAuthorizationToken("account-token-123456")
    private fun registrar(block: suspend (RemoteIdentityScope, RemoteStaticIdentity) -> RemoteRoleCredential) = object : RemoteRoleRegistrar {
        override suspend fun registerAccountRole(scope: RemoteIdentityScope, identity: RemoteStaticIdentity, accountToken: RemoteAccountAuthorizationToken) = block(scope, identity)
    }

    private class Fixture {
        val persistence = MemoryPersistence()
        val protector = TestProtector()
        fun store() = RemoteIdentityStore(persistence, protector)
    }
    private class MemoryPersistence : RemoteIdentityPersistence {
        val values = linkedMapOf<String, ByteArray>()
        var failWrites = false
        override fun read(scopeKey: String): ByteArray? = values[scopeKey]?.copyOf()
        override fun writeAtomic(scopeKey: String, value: ByteArray) {
            if (failWrites) error("含敏感上游正文的写入失败")
            values[scopeKey] = value.copyOf()
        }
        override fun clear(scopeKey: String) { values.remove(scopeKey) }
    }
    /** 只验证存储边界确实交付 AAD；这不是 Android Keystore 的生产实现。 */
    private class TestProtector : RemoteIdentityProtector {
        val keys = mutableSetOf<String>()
        val plaintextBuffers = mutableListOf<ByteArray>()
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun hasKey(scopeKey: String) = scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            keys += scopeKey
            plaintextBuffers += plaintext
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(associatedData); iv + doFinal(plaintext)
            }
        }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            check(scopeKey in keys)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12))); updateAAD(associatedData)
                doFinal(ciphertext.copyOfRange(12, ciphertext.size))
            }
        }
    }
}
