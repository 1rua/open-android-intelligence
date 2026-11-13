package com.openandroidintelligence.gateway.remote

import com.openandroidintelligence.gateway.remote.api.RemoteAuthCodec
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityPersistence
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityStore
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginCoordinator
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistrar
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistrationState
import com.openandroidintelligence.gateway.remote.auth.RemoteTokenRefresher
import com.openandroidintelligence.gateway.remote.auth.RemoteTokenRotator
import com.openandroidintelligence.gateway.remote.auth.RemoteTokenRotationResult
import com.openandroidintelligence.gateway.remote.crypto.NoiseIkChannel
import com.openandroidintelligence.gateway.remote.identity.RemoteAccountAuthorizationToken
import com.openandroidintelligence.gateway.remote.identity.RemoteDeviceId
import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemotePeerPin
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.identity.RemoteRoleCredential
import com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentity
import com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentityFactory
import com.openandroidintelligence.gateway.remote.identity.RemoteTokenPair
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIdentityTest {
    private val origin = "https://remote.example"
    private val clientScope = RemoteIdentityScope(origin, "account-a", RemoteRole.CLIENT)
    private val hostScope = RemoteIdentityScope(origin, "account-a", RemoteRole.HOST)

    @Test
    fun originAccountAndRoleAreIndependentStorageScopes() {
        val fixture = Fixture()
        val store = fixture.store()
        val client = credential("client-device")
        val host = credential("host-device")
        store.save(clientScope, client)
        store.save(hostScope, host)

        assertEquals(client.identity.deviceId, store.load(clientScope)!!.identity.deviceId)
        assertEquals(host.identity.deviceId, store.load(hostScope)!!.identity.deviceId)
        assertNull(store.load(clientScope.copy(origin = "https://other.example")))
        assertNull(store.load(clientScope.copy(accountId = "account-b")))
        assertFalse(fixture.persistence.values.keys.any { it.contains("client-device") || it.contains("host-device") })
    }

    @Test
    fun roleRegistrationFailureDoesNotEraseSuccessfulOtherRole() = runBlocking {
        val fixture = Fixture()
        val store = fixture.store()
        val registrar = object : RemoteRoleRegistrar {
            override suspend fun registerAccountRole(scope: RemoteIdentityScope, identity: RemoteStaticIdentity, accountToken: RemoteAccountAuthorizationToken): RemoteRoleCredential {
                if (scope.role == RemoteRole.HOST) error("HOST_REGISTRATION_UNSUPPORTED")
                return credential(identity.deviceId.value)
            }
        }
        val result = RemoteLoginCoordinator(store, registrar).registerRoles(
            origin, "account-a", RemoteAccountAuthorizationToken("account-token-123456"),
            credential("client-device").identity, credential("host-device").identity,
        )

        assertEquals(RemoteRoleRegistrationState.REGISTERED, result.client.state)
        assertEquals(RemoteRoleRegistrationState.FAILED, result.host.state)
        assertNotNull(store.load(clientScope))
        assertNull(store.load(hostScope))
    }

    @Test
    fun tokenRotationUsesExpectedRefreshAndFailedAtomicWriteLeavesPriorCredential() = runBlocking {
        val fixture = Fixture()
        val store = fixture.store()
        val current = credential("client-device")
        store.save(clientScope, current)
        fixture.persistence.failWrites = true
        val result = RemoteTokenRotator(store, RemoteTokenRefresher { _, _, _ ->
            RemoteTokenPair("access-token-2-123456", 2_000, "refresh-token-2-123456", 3_000)
        }).rotate(clientScope)

        assertTrue(result is RemoteTokenRotationResult.Failed)
        fixture.persistence.failWrites = false
        assertEquals(current.tokens.refreshToken, store.load(clientScope)!!.tokens.refreshToken)
    }

    @Test
    fun tokenRotationRejectsStaleExpectedRefreshWithoutOverwritingNewerCredential() {
        val fixture = Fixture()
        val store = fixture.store()
        val current = credential("client-device")
        val newer = current.copy(tokens = RemoteTokenPair("access-token-2-123456", 2_000, "refresh-token-2-123456", 3_000))
        store.save(clientScope, newer)

        assertFalse(store.rotateTokens(clientScope, current.tokens.refreshToken, newer.copy(tokens = RemoteTokenPair(
            "access-token-3-123456", 3_000, "refresh-token-3-123456", 4_000,
        ))))
        assertEquals(newer.tokens.refreshToken, store.load(clientScope)!!.tokens.refreshToken)
    }

    @Test
    fun peerPinMismatchIsRejectedAndPeerPinsAreRoleScoped() {
        val credential = credential("client-device").pin(RemotePeerPin(RemoteRole.HOST, RemoteDeviceId("host-device"), ByteArray(32) { 7 }))
        credential.requirePeerPin(RemoteRole.HOST, RemoteDeviceId("host-device"), ByteArray(32) { 7 })
        kotlin.runCatching {
            credential.requirePeerPin(RemoteRole.HOST, RemoteDeviceId("host-device"), ByteArray(32) { 8 })
        }.onSuccess { error("peer key mismatch must fail") }
        kotlin.runCatching {
            credential.requirePeerPin(RemoteRole.CLIENT, RemoteDeviceId("host-device"), ByteArray(32) { 7 })
        }.onSuccess { error("peer role mismatch must fail") }
    }

    @Test
    fun generatedStaticIdentityUsesX25519WireSizedKeyAndDoesNotUseLegacyP256Store() {
        val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("client-device"))
        assertEquals(NoiseIkChannel.KEY_BYTES, identity.privateKey.size)
        assertEquals(NoiseIkChannel.KEY_BYTES, identity.publicKey.size)
        assertNotEquals("legacy profile based key aliases are not part of Remote identity", "open_android_intelligence_gateway_device_client-device", "remote:${identity.deviceId.value}")
        assertArrayEquals(identity.publicKey, NoiseIkChannel.publicKeyFor(identity.privateKey))
    }

    @Test
    fun requestCodecUsesFixedUpstreamPathsAndKeepsAccountTokenOutOfBody() {
        val identity = credential("client-device").identity
        val login = RemoteAuthCodec.accountLogin(" user@example.com ", "password".toCharArray())
        assertEquals("/api/v1/auth/login", login.path)
        assertEquals("user@example.com", JsonFields.string(login.body, "email"))
        assertEquals("password", JsonFields.string(login.body, "password"))
        val registration = RemoteAuthCodec.registerAccountRole(identity, RemoteRole.CLIENT, RemoteAccountAuthorizationToken("account-token-123456"))
        assertEquals("/api/v1/devices/register", registration.path)
        assertNull(JsonFields.string(registration.body, "authorization"))
        assertEquals("account-token-123456", registration.bearerToken)
        assertEquals("/api/v1/auth/refresh", RemoteAuthCodec.refresh(identity.deviceId, "refresh-token-123456").path)
    }

    private fun credential(deviceId: String): RemoteRoleCredential {
        val privateKey = ByteArray(NoiseIkChannel.KEY_BYTES) { (it + 1).toByte() }
        val publicKey = NoiseIkChannel.publicKeyFor(privateKey)
        return RemoteRoleCredential(
            RemoteStaticIdentity(RemoteDeviceId(deviceId), privateKey, publicKey),
            RemoteTokenPair("access-token-123456", 1_000, "refresh-token-123456", 2_000),
        )
    }

    private class Fixture {
        val persistence = MemoryPersistence()
        val protector = MemoryProtector()
        fun store() = RemoteIdentityStore(persistence, protector)
    }

    private class MemoryPersistence : RemoteIdentityPersistence {
        val values = linkedMapOf<String, ByteArray>()
        var failWrites = false
        override fun read(scopeKey: String): ByteArray? = values[scopeKey]?.copyOf()
        override fun writeAtomic(scopeKey: String, value: ByteArray) {
            if (failWrites) error("ATOMIC_WRITE_FAILED")
            values[scopeKey] = value.copyOf()
        }
        override fun clear(scopeKey: String) { values.remove(scopeKey) }
    }

    private class MemoryProtector : RemoteIdentityProtector {
        private val keys = mutableSetOf<String>()
        override fun hasKey(scopeKey: String): Boolean = scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray): ByteArray {
            keys += scopeKey
            return plaintext.copyOf()
        }
        override fun open(scopeKey: String, ciphertext: ByteArray): ByteArray {
            if (scopeKey !in keys) error("MISSING_KEY")
            return ciphertext.copyOf()
        }
    }
}
