package com.openandroidintelligence.gateway.remote.authproduction

import android.provider.Settings
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteInstallationIdentityStoreTest {
    private class Protector : RemoteIdentityProtector {
        val keys = mutableMapOf<String, ByteArray>()
        override fun hasKey(scopeKey: String) = scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val key = keys.getOrPut(scopeKey) { ByteArray(32).also(SecureRandom()::nextBytes) }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); updateAAD(associatedData); iv + doFinal(plaintext)
            }
        }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.getValue(scopeKey), "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            updateAAD(associatedData); doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        }
    }

    @Test fun 首创双角色身份重启改名均稳定且密文不含名称与私钥() {
        val directory = Files.createTempDirectory("remote-installation").toFile()
        val protector = Protector()
        val first = RemoteInstallationIdentityStore(directory, protector) { "测试手机" }
        val identities = first.loadOrCreate()
        assertNotEquals(identities.client.deviceId, identities.host.deviceId)
        assertFalse(identities.client.publicKey.contentEquals(identities.host.publicKey))
        val restarted = RemoteInstallationIdentityStore(directory, protector) { "系统新名称" }
        assertTrue(identities.client.sameIdentity(restarted.loadOrCreate().client))
        assertTrue(identities.host.sameIdentity(restarted.loadOrCreate().host))
        assertEquals("测试手机", restarted.deviceName())
        restarted.updateDeviceName("  我的手机  ")
        assertEquals("我的手机", first.deviceName())
        assertTrue(identities.host.sameIdentity(first.loadOrCreate().host))
        val encrypted = File(directory, "remote-installation.bin").readBytes()
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("我的手机"))
        assertFalse(encrypted.toList().windowed(32).any { it == identities.client.privateKeyCopy().toList() })
    }

    @Test fun 注入的两个安装目录相互隔离且账号撤销后工厂恢复同一安装身份() {
        val protector = Protector()
        val firstDirectory = Files.createTempDirectory("remote-installation-first").toFile()
        val secondDirectory = Files.createTempDirectory("remote-installation-second").toFile()
        // 两个独立安装各有自己的 Keystore；不能因同一测试进程的 Android Context 混用目录。
        val firstVault = RemoteCredentialVault(firstDirectory, protector)
        val otherProtector = Protector()
        val secondVault = RemoteCredentialVault(secondDirectory, otherProtector)
        val first = firstVault.installationIdentityStore { "第一台手机" }.loadOrCreate()
        val second = secondVault.installationIdentityStore { "第二台手机" }.loadOrCreate()
        assertNotEquals(first.client.deviceId, second.client.deviceId)
        assertNotEquals(first.host.deviceId, second.host.deviceId)
        val epoch = firstVault.revokeActive()
        firstVault.activate("https://remote.example", "owner", epoch)
        val credentials = RemoteIdentityStore(firstVault, protector)
        val tokens = RemoteTokenPair("a".repeat(16), 1000000, "r".repeat(16), 2000000)
        for (role in RemoteRole.entries) credentials.save(RemoteIdentityScope("https://remote.example", "owner", role),
            RemoteRoleCredential(if (role == RemoteRole.CLIENT) first.client else first.host, tokens))
        firstVault.revokeActive()
        credentials.clearAccount("https://remote.example", "owner")
        val restoredVault = RemoteCredentialVault(firstDirectory, protector)
        val restored = restoredVault.installationIdentityStore { "不会替换原名称" }.loadOrCreate()
        assertTrue(first.client.sameIdentity(restored.client)); assertTrue(first.host.sameIdentity(restored.host))
        assertEquals("第一台手机", restoredVault.installationIdentityStore { "无关" }.deviceName())
        assertTrue(second.client.sameIdentity(secondVault.installationIdentityStore { "无关" }.loadOrCreate().client))
        assertNull(restoredVault.activeAccount())
    }

    @Test fun 当前账号升级保留原身份而退出和切账号不保留旧凭据或对端授权() {
        val directory = Files.createTempDirectory("remote-installation-account").toFile()
        val protector = Protector()
        val vault = RemoteCredentialVault(directory, protector)
        val credentials = RemoteIdentityStore(vault, protector)
        val clientScope = RemoteIdentityScope("https://remote.example", "旧账号", RemoteRole.CLIENT)
        val hostScope = clientScope.copy(role = RemoteRole.HOST)
        val client = RemoteStaticIdentityFactory.generate(RemoteDeviceId("019a0000-0000-7000-8000-000000000001"))
        val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId("019a0000-0000-7000-8000-000000000002"))
        val tokens = RemoteTokenPair("旧访问令牌".repeat(8), 1000000, "旧刷新令牌".repeat(8), 2000000)
        val peer = RemotePeerPin(RemoteRole.HOST, RemoteDeviceId("旧电脑"), RemoteStaticIdentityFactory.generate(RemoteDeviceId("peer")).publicKey)
        credentials.save(clientScope, RemoteRoleCredential(client, tokens, listOf(peer)))
        credentials.save(hostScope, RemoteRoleCredential(host, tokens))
        val epoch = vault.revokeActive()
        val active = vault.activate(clientScope.origin, clientScope.accountId, epoch)
        val installation = RemoteInstallationIdentityStore(directory, protector) { "测试手机" }
        val migrated = installation.migrateActiveAccount(vault, protector)
        assertTrue(client.sameIdentity(migrated.client)); assertTrue(host.sameIdentity(migrated.host))
        vault.revokeActive(); credentials.clearAccount(clientScope.origin, clientScope.accountId)
        val restartedVault = RemoteCredentialVault(directory, protector)
        val restartedCredentials = RemoteIdentityStore(restartedVault, protector)
        assertNull(restartedVault.activeAccount())
        assertNull(restartedCredentials.load(clientScope)); assertNull(restartedCredentials.load(hostScope))
        assertThrows(IllegalStateException::class.java) { restartedVault.requireCurrent(active) }
        val stable = RemoteInstallationIdentityStore(directory, protector) { "测试手机" }.loadOrCreate()
        assertTrue(client.sameIdentity(stable.client)); assertTrue(host.sameIdentity(stable.host))
        val other = clientScope.copy(accountId = "新账号")
        assertNull(restartedCredentials.load(other))
        val newTokens = RemoteTokenPair("新访问令牌".repeat(8), 3000000, "新刷新令牌".repeat(8), 4000000)
        restartedCredentials.save(other, RemoteRoleCredential(stable.client, newTokens))
        val newCredential = restartedCredentials.load(other)!!
        assertEquals(newTokens, newCredential.tokens); assertTrue(newCredential.peers.isEmpty())
        assertNull(restartedCredentials.load(clientScope))
    }

    @Test fun 缺失Keystore密钥或密文损坏不能悄悄生成新的安装身份() {
        for (loseKey in listOf(true, false)) {
            val directory = Files.createTempDirectory("remote-installation-corrupt").toFile()
            val protector = Protector()
            RemoteInstallationIdentityStore(directory, protector) { "测试手机" }.loadOrCreate()
            val record = File(directory, "remote-installation.bin")
            if (loseKey) protector.keys.clear() else record.writeBytes(byteArrayOf(1, 2, 3))
            val before = record.readBytes()
            assertThrows(if (loseKey) RemoteIdentityLockedException::class.java else RemoteIdentityCorruptedException::class.java) {
                RemoteInstallationIdentityStore(directory, protector) { "测试手机" }.loadOrCreate()
            }
            assertArrayEquals(before, record.readBytes())
            if (loseKey) assertTrue(protector.keys.isEmpty())
        }
    }

    @Test fun 默认名称优先读取Android系统设备名称并拒绝协议不允许的改名() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "系统自定义手机名")
        assertEquals("系统自定义手机名", RemoteInstallationIdentityStore.defaultDeviceName(context))
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, " ")
        val fallback = RemoteInstallationIdentityStore.defaultDeviceName(context)
        assertTrue(fallback.contains(android.os.Build.MODEL.trim()))
        assertFalse(fallback.isBlank())
        listOf(" ", "a".repeat(129), "中".repeat(43), "手机\n名称").forEach {
            assertThrows(IllegalArgumentException::class.java) { RemoteInstallationIdentityStore.normalizeName(it) }
        }
    }
}
