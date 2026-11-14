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
        override fun hasKey(scopeKey: String) = keys.containsKey(scopeKey)
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val key = keys.getOrPut(scopeKey) { ByteArray(32).also(SecureRandom()::nextBytes) }
            return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); updateAAD(associatedData); iv + doFinal(plaintext) }
        }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.getValue(scopeKey), "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            updateAAD(associatedData); doFinal(ciphertext.copyOfRange(12, ciphertext.size))
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
    @Test fun 读时缺密钥失败且不能生成替代密钥() {
        val directory = Files.createTempDirectory("remote-vault-key").toFile(); val protector = Protector()
        val vault = RemoteCredentialVault(directory, protector); val epoch = vault.revokeActive()
        vault.activate("https://remote.example", "owner", epoch); protector.keys.clear()
        assertThrows(RemoteIdentityLockedException::class.java) { RemoteCredentialVault(directory, protector).activeAccount() }
        assertTrue(protector.keys.isEmpty())
    }
}
