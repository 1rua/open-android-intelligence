package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.authproduction.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.runtime.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemotePairingImportTest {
    private class Protector : RemoteIdentityProtector {
        private val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var failWrite = false
        override fun hasKey(scopeKey: String) = scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            check(!failWrite) { "模拟写盘失败" }
            return plaintext.copyOf().also { keys += scopeKey }
        }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
    }
    private class Fixture {
        val protector = Protector()
        val vault = RemoteCredentialVault(Files.createTempDirectory("remote-pairing-import").toFile(), protector)
        val store = RemoteIdentityStore(vault, protector)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), scope, vault = vault, protector = protector)
        val clientScope = RemoteIdentityScope(RemoteServerApi.DEFAULT_ORIGIN, "dsh-device:test", RemoteRole.CLIENT)
        val hostScope = RemoteIdentityScope(RemoteServerApi.DEFAULT_ORIGIN, "dsh-device:test", RemoteRole.HOST)
        val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("phone-client"))
        val peer = RemoteStaticIdentityFactory.generate(RemoteDeviceId("computer-host"))
        val tokens = RemoteTokenPair("a".repeat(16), System.currentTimeMillis() + 600000, "r".repeat(16), System.currentTimeMillis() + 1200000)
        fun imported(epoch: Long = vault.authorizationEpoch()) = RemotePairingImport("session", clientScope.accountId,
            RemoteRoleCredential(identity, tokens, listOf(RemotePeerPin(RemoteRole.HOST, peer.deviceId, peer.publicKey))), epoch, vault.beginLoginAttempt())
    }
    @Test fun 配对只发布安全保存的Client身份和Host固定公钥且重启可恢复() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.runtime.importPairing(fixture.imported())
            assertEquals(fixture.clientScope.accountId, fixture.runtime.account.value!!.accountId)
            assertEquals(RemoteHostConnectionState.STOPPED, fixture.runtime.hostState.value)
            assertNull(fixture.store.load(fixture.hostScope))
            val saved = fixture.store.load(fixture.clientScope)!!
            assertTrue(saved.identity.sameIdentity(fixture.identity)); assertTrue(saved.peers.single().matches(fixture.peer.publicKey))
            val restarted = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), fixture.scope, vault = fixture.vault, protector = fixture.protector)
            assertEquals(fixture.runtime.account.value, restarted.restore())
        } finally { fixture.scope.cancel() }
    }
    @Test fun 配对结果恢复不能覆盖已经续期的设备token() = runBlocking {
        val fixture = Fixture(); val original = fixture.imported()
        try {
            fixture.runtime.importPairing(original)
            val current = fixture.store.load(fixture.clientScope)!!
            val refreshed = current.copy(tokens = fixture.tokens.copy(accessToken = "b".repeat(16), refreshToken = "s".repeat(16)))
            fixture.store.rotateTokens(fixture.clientScope, current, refreshed)
            fixture.runtime.importPairing(original)
            assertEquals(refreshed.tokens, fixture.store.load(fixture.clientScope)!!.tokens)
        } finally { fixture.scope.cancel() }
    }
    @Test fun 退出后的迟到配对结果不得写入或恢复旧账号() = runBlocking {
        val fixture = Fixture(); val late = fixture.imported()
        try {
            fixture.runtime.logout()
            assertTrue(runCatching { fixture.runtime.importPairing(late) }.isFailure)
            assertNull(fixture.runtime.account.value); assertNull(fixture.vault.activeAccount())
            assertNull(fixture.store.load(fixture.clientScope)); assertNull(fixture.runtime.restore())
        } finally { fixture.scope.cancel() }
    }
    @Test fun 密文提交失败不能发布配对成功() = runBlocking {
        val fixture = Fixture(); val value = fixture.imported()
        fixture.protector.failWrite = true
        try {
            assertTrue(runCatching { fixture.runtime.importPairing(value) }.isFailure)
            assertNull(fixture.runtime.account.value)
        } finally { fixture.scope.cancel() }
    }
}
