package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.authproduction.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.gateway.schema.Json
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteLoginActivationRaceTest {
    private class Protector : RemoteIdentityProtector {
        private val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Volatile var activeWrite = false
        @Volatile var failCredentialWrites = false
        override fun hasKey(scopeKey: String) = scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            check(!failCredentialWrites) { "模拟凭据写盘失败" }
            if (scopeKey == "credential-vault") activeWrite = RemoteWireJson.decode(plaintext).objectValue().value("active") != JsonValue.JNull
            return plaintext.copyOf().also { keys += scopeKey }
        }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
    }
    private class Fixture {
        val directory = Files.createTempDirectory("remote-login-activation-race").toFile()
        val protector = Protector()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val vault = RemoteCredentialVault(directory, protector) { file, stream ->
            if (protector.activeWrite && armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "等待测试释放写盘屏障超时" }
            }
            file.finishWrite(stream)
        }
        val fence = RemoteLoginAttemptFence(directory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), scope, vault = vault, protector = protector)
        val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("phone-client"))
        val peer = RemoteStaticIdentityFactory.generate(RemoteDeviceId("host"))
        val tokens = RemoteTokenPair("a".repeat(16), System.currentTimeMillis() + 600000, "r".repeat(16), System.currentTimeMillis() + 1200000)
        fun imported() = RemotePairingImport("session", "dsh-device:test",
            RemoteRoleCredential(identity, tokens, listOf(RemotePeerPin(RemoteRole.HOST, peer.deviceId, peer.publicKey))),
            vault.authorizationEpoch(), vault.beginLoginAttempt())
        fun restarted() = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), scope,
            vault = RemoteCredentialVault(directory, protector), protector = protector)
    }
    @Test fun 最终激活写盘期间取消必须阻断成功及重启恢复且后继登录不受旧取消影响() = runBlocking {
        val fixture = Fixture(); val old = fixture.imported()
        val importing = async(Dispatchers.Default) { runCatching { fixture.runtime.importPairing(old) } }
        try {
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            // 与实际取消相同：独立持久撤销门先失效原尝试，不等待持有凭据文件锁的激活写盘。
            fixture.fence.cancelAttempt(old.loginAttemptId)
            assertFalse(fixture.fence.authorized(old.loginAttemptId))
            fixture.release.countDown()
            assertTrue(withTimeout(5000) { importing.await() }.isFailure)
            assertNull(fixture.runtime.account.value)
            assertNull(fixture.vault.activeAccount())
            assertNull(fixture.restarted().restore())
            val next = fixture.imported()
            fixture.runtime.importPairing(next)
            fixture.fence.cancelAttempt(old.loginAttemptId)
            assertEquals(next.loginAttemptId, fixture.vault.activeAccount()!!.loginAttemptId)
            assertEquals(fixture.runtime.account.value, fixture.restarted().restore())
            assertTrue(runCatching { fixture.runtime.importPairing(old) }.isFailure)
            assertEquals(next.loginAttemptId, fixture.vault.activeAccount()!!.loginAttemptId)
        } finally { fixture.release.countDown(); importing.cancelAndJoin(); fixture.scope.cancel() }
    }
    @Test fun 凭据回滚写盘失败也不能恢复已取消激活() = runBlocking {
        val fixture = Fixture(); val value = fixture.imported()
        val importing = async(Dispatchers.Default) { runCatching { fixture.runtime.importPairing(value) } }
        try {
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            fixture.fence.cancelAttempt(value.loginAttemptId)
            // 激活密文已经写入待替换文件；模拟后续凭据清理/回滚均不可写。
            fixture.protector.failCredentialWrites = true
            fixture.release.countDown()
            assertTrue(withTimeout(5000) { importing.await() }.isFailure)
            assertNull(fixture.vault.activeAccount())
            assertNull(fixture.restarted().restore())
        } finally { fixture.release.countDown(); importing.cancelAndJoin(); fixture.scope.cancel() }
    }
    @Test fun 最终提交已完成后取消不撤销已成功账号() = runBlocking {
        val fixture = Fixture(); fixture.armed.set(false)
        try {
            val value = fixture.imported()
            fixture.runtime.importPairing(value)
            fixture.fence.cancelAttempt(value.loginAttemptId)
            fixture.fence.block()
            assertTrue(fixture.fence.authorized(value.loginAttemptId))
            assertEquals(value.loginAttemptId, fixture.restarted().restore()!!.loginAttemptId)
        } finally { fixture.scope.cancel() }
    }
    @Test fun 邮箱与OAuth最终激活使用相同持久撤销边界() = runBlocking {
        for (oauth in listOf(false, true)) {
            val fixture = Fixture()
            MockWebServer().use { server ->
                server.start()
                val origin = server.url("/").toString().removeSuffix("/")
                val accountToken = "w".repeat(32)
                server.enqueue(MockResponse().setBody(Json.canonical(obj("account" to "owner", "token" to accountToken,
                    "expiresAt" to System.currentTimeMillis() + 600000))))
                repeat(2) { server.enqueue(MockResponse().setBody(Json.canonical(obj(
                    "accessToken" to fixture.tokens.accessToken, "accessTokenExpiresAt" to fixture.tokens.accessTokenExpiresAt,
                    "refreshToken" to fixture.tokens.refreshToken, "refreshTokenExpiresAt" to fixture.tokens.refreshTokenExpiresAt)))) }
                val attemptId = if (oauth) fixture.vault.beginLoginAttempt() else null
                val login = async(Dispatchers.Default) {
                    runCatching {
                        if (oauth) fixture.runtime.loginAccountToken(origin, accountToken, requireNotNull(attemptId))
                        else fixture.runtime.login(origin, "owner", "password")
                    }
                }
                try {
                    assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
                    fixture.fence.block()
                    fixture.release.countDown()
                    assertTrue(withTimeout(5000) { login.await() }.isFailure)
                    assertNull(fixture.runtime.account.value)
                    assertNull(fixture.restarted().restore())
                } finally { fixture.release.countDown(); login.cancelAndJoin(); fixture.scope.cancel() }
            }
        }
    }
}
