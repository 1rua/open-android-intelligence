package com.openandroidintelligence.gateway.remote.authproduction

import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.identity.*
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteCredentialVaultTest {
    private class Protector : RemoteIdentityProtector {
        val keys = mutableMapOf<String, ByteArray>()
        var failOpen = false
        var failSeal = false
        override fun hasKey(scopeKey: String) = keys.containsKey(scopeKey)
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            if (failSeal) throw java.io.IOException("模拟凭据写入失败")
            val key = keys.getOrPut(scopeKey) { ByteArray(32).also(SecureRandom()::nextBytes) }
            return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); updateAAD(associatedData); iv + doFinal(plaintext) }
        }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            if (failOpen) throw java.io.IOException("模拟凭据读取失败")
            return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.getValue(scopeKey), "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            updateAAD(associatedData); doFinal(ciphertext.copyOfRange(12, ciphertext.size))
            }
        }
    }
    @Test fun 重启恢复两个隔离角色并且持久撤销后不复活旧账号() {
        val directory = Files.createTempDirectory("remote-vault").toFile(); val protector = Protector()
        val vault = RemoteCredentialVault(directory, protector); val store = RemoteIdentityStore(vault, protector)
        val client = RemoteIdentityScope("https://remote.example", "owner", RemoteRole.CLIENT)
        val host = client.copy(role = RemoteRole.HOST)
        val clientIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("client"))
        val hostIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("host"))
        val tokens = RemoteTokenPair("a".repeat(16), 1000000, "r".repeat(16), 2000000)
        store.save(client, RemoteRoleCredential(clientIdentity, tokens)); store.save(host, RemoteRoleCredential(hostIdentity, tokens))
        val epoch = vault.revokeActive(); val active = vault.activate(client.origin, client.accountId, epoch)
        val restarted = RemoteCredentialVault(directory, protector); val restartedStore = RemoteIdentityStore(restarted, protector)
        assertEquals(active, restarted.activeAccount()); assertEquals("client", restartedStore.load(client)!!.identity.deviceId.value)
        assertEquals("host", restartedStore.load(host)!!.identity.deviceId.value)
        // 即使此刻角色文件清理尚未发生，先落盘的撤销也足以使重启失去活动账号。
        restarted.revokeActive()
        val afterLogout = RemoteCredentialVault(directory, protector)
        assertNull(afterLogout.activeAccount())
        assertThrows(IllegalStateException::class.java) { afterLogout.requireCurrent(active) }
        assertThrows(IllegalStateException::class.java) { afterLogout.activate(client.origin, client.accountId, epoch) }
        assertFalse(File(directory, "remote-credentials.bin").readText().contains("owner"))
    }
    @Test fun 撤销读写失败后重启不能恢复旧账号且重试可完成退出() {
        for (readFailure in listOf(true, false)) {
            val directory = Files.createTempDirectory("remote-vault-revoke-fault").toFile()
            val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
            val epoch = vault.revokeActive(); val active = vault.activate("https://remote.example", "owner", epoch)
            protector.failOpen = readFailure; protector.failSeal = !readFailure
            assertThrows(Exception::class.java) { vault.revokeActive() }
            protector.failOpen = false; protector.failSeal = false
            val restarted = RemoteCredentialVault(directory, protector)
            assertNull(restarted.activeAccount())
            assertThrows(IllegalStateException::class.java) { restarted.requireCurrent(active) }
            assertThrows(IllegalStateException::class.java) { restarted.activate(active.origin, active.accountId, epoch) }
            restarted.revokeActive()
            assertNull(RemoteCredentialVault(directory, protector).activeAccount())
            assertEquals(0, File(directory, "remote-revocation.bin").inputStream().use { it.read() })
        }
    }
    @Test fun 撤销标记写失败不能报告成功且不把损坏标记当作可恢复账号() {
        val directory = Files.createTempDirectory("remote-vault-marker-fault").toFile(); val protector = Protector()
        val vault = RemoteCredentialVault(directory, protector); val epoch = vault.revokeActive()
        vault.activate("https://remote.example", "owner", epoch)
        val marker = File(directory, "remote-revocation.bin")
        check(marker.renameTo(File(directory, "remote-revocation.previous")))
        check(marker.mkdir())
        File(marker, "阻止原子替换的占位").writeText("故障夹具")
        assertThrows(Exception::class.java) { vault.revokeActive() }
        assertNull(RemoteCredentialVault(directory, protector).activeAccount())
        check(marker.renameTo(File(directory, "remote-revocation.failed-marker")))
        vault.revokeActive()
        assertNull(vault.activeAccount())
    }
    @Test fun 主凭据提交替换失败保持撤销屏障且旧角色数据可安全重试() {
        val directory = Files.createTempDirectory("remote-vault-main-commit-fault").toFile()
        val protector = Protector(); val initial = RemoteCredentialVault(directory, protector)
        val record = "独立用户凭据记录".encodeToByteArray()
        initial.writeAtomic("保留的角色", record)
        val epoch = initial.revokeActive()
        val active = initial.activate("https://remote.example", "owner", epoch)
        val base = File(directory, "remote-credentials.bin")
        val previous = base.readBytes()
        val trash = File(directory, "隔离故障文件").apply { check(mkdir()) }
        var failedCommit = false
        val failing = RemoteCredentialVault(directory, protector) { atomic, stream ->
            stream.flush()
            // 主文件.new已完整写入，移动到隔离区模拟提交时源文件消失；不触碰旧凭据和撤销标记。
            val staged = File(directory, "remote-credentials.bin.new")
            assertTrue(staged.isFile)
            Files.move(staged.toPath(), File(trash, "保留待审密文.new").toPath())
            failedCommit = true
            atomic.finishWrite(stream)
        }
        val error = assertThrows(com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException::class.java) { failing.revokeActive() }
        assertEquals("REMOTE_CREDENTIAL_PERSISTENCE_FAILED", error.code)
        assertTrue(failedCommit)
        assertArrayEquals("原凭据文件不能丢失或被错误覆盖", previous, base.readBytes())
        assertArrayEquals(byteArrayOf(1), File(directory, "remote-revocation.bin").readBytes())
        val restarted = RemoteCredentialVault(directory, protector)
        assertNull(restarted.activeAccount())
        assertThrows(IllegalStateException::class.java) { restarted.requireCurrent(active) }
        assertArrayEquals(record, restarted.read("保留的角色"))
        restarted.revokeActive()
        val afterRetry = RemoteCredentialVault(directory, protector)
        assertNull(afterRetry.activeAccount())
        assertArrayEquals(byteArrayOf(0), File(directory, "remote-revocation.bin").readBytes())
        assertArrayEquals(record, afterRetry.read("保留的角色"))
        previous.fill(0); record.fill(0)
    }
    @Test fun 读时缺密钥失败且不能生成替代密钥() {
        val directory = Files.createTempDirectory("remote-vault-key").toFile(); val protector = Protector()
        val vault = RemoteCredentialVault(directory, protector); val epoch = vault.revokeActive()
        vault.activate("https://remote.example", "owner", epoch); protector.keys.clear()
        assertThrows(RemoteIdentityLockedException::class.java) { RemoteCredentialVault(directory, protector).activeAccount() }
        assertTrue(protector.keys.isEmpty())
    }
}
