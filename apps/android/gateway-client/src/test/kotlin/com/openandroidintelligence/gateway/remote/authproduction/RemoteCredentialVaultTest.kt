package com.openandroidintelligence.gateway.remote.authproduction

import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostFailure
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostFailureStage
import java.io.File
import java.io.IOException
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
    @Test fun 角色终态围栏在主凭据写失败时按epoch保留且不泄露凭据() {
        val directory = Files.createTempDirectory("remote-vault-role-terminal").toFile()
        val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
        val store = RemoteIdentityStore(vault, protector)
        val client = RemoteIdentityScope("https://remote.example", "owner", RemoteRole.CLIENT)
        val host = client.copy(role = RemoteRole.HOST)
        val clientCredential = RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("client")),
            RemoteTokenPair("client-access-secret", 1000000, "client-refresh-secret", 2000000))
        val hostCredential = RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("host")),
            RemoteTokenPair("host-access-secret", 1000000, "host-refresh-secret", 2000000))
        store.save(client, clientCredential); store.save(host, hostCredential)
        val epoch = vault.revokeActive(); val active = vault.activate(client.origin, client.accountId, epoch)
        val mainBefore = File(directory, "remote-credentials.bin").readBytes()

        val failing = RemoteCredentialVault(directory, protector,
            { atomic, stream -> atomic.finishWrite(stream) },
            { _, _ -> throw IOException("模拟加密主信封提交失败") })
        failing.stopClientUntilLogin(active, "CONNECTION_REPLACED")

        assertArrayEquals("角色围栏失败路径不能改写原加密信封", mainBefore, File(directory, "remote-credentials.bin").readBytes())
        val fence = File(directory, "remote-role-terminal.bin").readText()
        assertFalse(fence.contains("owner")); assertFalse(fence.contains("client-access-secret"))
        assertFalse(fence.contains("client-refresh-secret")); assertFalse(fence.contains("host-access-secret"))

        val restarted = RemoteCredentialVault(directory, protector)
        val failures = restarted.roleFailures(active)
        assertEquals("CONNECTION_REPLACED", failures.clientStop)
        assertNull(failures.hostStop)
        val restartedStore = RemoteIdentityStore(restarted, protector)
        assertEquals("client", restartedStore.load(client)!!.identity.deviceId.value)
        assertEquals("host", restartedStore.load(host)!!.identity.deviceId.value)

        val nextEpoch = restarted.revokeActive()
        val next = restarted.activate(client.origin, client.accountId, nextEpoch)
        assertNull("新登录 epoch 不能被旧角色终态覆盖", restarted.roleFailures(next).clientStop)
        val storageError = RemoteHostFailure(RemoteHostFailureStage.CREDENTIAL, "REMOTE_CREDENTIAL_PERSISTENCE_FAILED")
        restarted.stopHostUntilLogin(next, storageError)
        val afterStorageFailure = RemoteCredentialVault(directory, protector).roleFailures(next)
        assertEquals(storageError, afterStorageFailure.hostStop)
        assertNull(afterStorageFailure.clientStop)
        mainBefore.fill(0)
    }
    @Test fun Host首次注册补偿只原子清除正确代次的暂态标记并保留角色围栏() {
        val directory = Files.createTempDirectory("remote-vault-host-registration").toFile()
        val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
        val store = RemoteIdentityStore(vault, protector)
        val client = RemoteIdentityScope("https://remote.example", "owner", RemoteRole.CLIENT)
        val host = client.copy(role = RemoteRole.HOST)
        val tokens = RemoteTokenPair("client-access-secret", 1000000, "client-refresh-secret", 2000000)
        store.save(client, RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("client")), tokens))
        store.save(host, RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("old-host")), tokens))
        val epoch = vault.revokeActive(); val active = vault.activate(client.origin, client.accountId, epoch,
            hostRegistrationError = "CONNECTION_FAILED")
        store.clear(host)
        val expected = RemoteHostFailure(RemoteHostFailureStage.CONTROL, "DEVICE_REVOKED")
        vault.stopHostUntilLogin(active, expected)
        val hostCredential = RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("host")),
            RemoteTokenPair("host-access-secret", 1000000, "host-refresh-secret", 2000000))

        assertEquals("Host 已有角色终态时不能补注册", RemoteCredentialVault.HostRegistrationCommitResult.NotEligible,
            vault.completeHostRegistration(active, hostCredential))
        assertNull(RemoteIdentityStore(RemoteCredentialVault(directory, protector), protector).load(host))
        assertEquals(expected, RemoteCredentialVault(directory, protector).roleFailures(active).hostStop)

        val nextEpoch = vault.revokeActive(); val next = vault.activate(client.origin, client.accountId, nextEpoch,
            hostRegistrationError = "RATE_LIMITED")
        assertEquals(RemoteCredentialVault.HostRegistrationCommitResult.Committed, vault.completeHostRegistration(next, hostCredential))
        assertEquals("同一角色不能由迟到的重入结果覆盖", RemoteCredentialVault.HostRegistrationCommitResult.NotEligible,
            vault.completeHostRegistration(next, RemoteRoleCredential(
                RemoteStaticIdentityFactory.generate(RemoteDeviceId("late-host")), tokens)))
        val restarted = RemoteCredentialVault(directory, protector); val restartedStore = RemoteIdentityStore(restarted, protector)
        assertEquals("host", restartedStore.load(host)!!.identity.deviceId.value)
        assertNull(restarted.roleFailures(next).hostRegistration)
        assertEquals("client", restartedStore.load(client)!!.identity.deviceId.value)
    }
    @Test fun Host首次注册补偿拒绝错代次同身份写失败与不完整授权() {
        val directory = Files.createTempDirectory("remote-vault-host-registration-fence").toFile()
        val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
        val store = RemoteIdentityStore(vault, protector)
        val client = RemoteIdentityScope("https://remote.example", "owner", RemoteRole.CLIENT)
        val host = client.copy(role = RemoteRole.HOST)
        val clientIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("client"))
        val tokens = RemoteTokenPair("client-access-secret", 1000000, "client-refresh-secret", 2000000)
        store.save(client, RemoteRoleCredential(clientIdentity, tokens))
        val old = vault.activate(client.origin, client.accountId,
            vault.revokeActive(), hostRegistrationError = "CONNECTION_FAILED")
        val staleCredential = RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("host")), tokens)
        val current = vault.activate(client.origin, client.accountId,
            vault.revokeActive(), hostRegistrationError = "CONNECTION_FAILED")
        assertEquals("旧 epoch 迟到结果不能写入", RemoteCredentialVault.HostRegistrationCommitResult.NotEligible,
            vault.completeHostRegistration(old, staleCredential))

        assertEquals("安装 Client 与待写 Host 必须是不同身份", RemoteCredentialVault.HostRegistrationCommitResult.IdentityConflict,
            vault.completeHostRegistration(current, RemoteRoleCredential(clientIdentity, tokens)))
        store.save(host, RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("legacy-host")), tokens))
        assertEquals("磁盘存在旧 Host 记录时必须明确报冲突", RemoteCredentialVault.HostRegistrationCommitResult.ExistingHostConflict,
            vault.completeHostRegistration(current, RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("host")), tokens)))
        store.clear(host)
        store.clear(client)
        assertEquals("Client 凭据缺失时不能补注册 Host", RemoteCredentialVault.HostRegistrationCommitResult.NotEligible,
            vault.completeHostRegistration(current, RemoteRoleCredential(RemoteStaticIdentityFactory.generate(RemoteDeviceId("host")), tokens)))
        store.save(client, RemoteRoleCredential(clientIdentity, tokens))

        val mainBefore = File(directory, "remote-credentials.bin").readBytes()
        val failing = RemoteCredentialVault(directory, protector,
            { atomic, stream -> atomic.finishWrite(stream) },
            { _, _ -> throw IOException("模拟 Host 凭据提交失败") })
        assertThrows(com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException::class.java) {
            failing.completeHostRegistration(current, RemoteRoleCredential(
                RemoteStaticIdentityFactory.generate(RemoteDeviceId("host")), tokens))
        }
        assertArrayEquals("写失败不能清除暂态标记或改动 Client 凭据", mainBefore, File(directory, "remote-credentials.bin").readBytes())
        val restarted = RemoteCredentialVault(directory, protector); val restartedStore = RemoteIdentityStore(restarted, protector)
        assertEquals("client", restartedStore.load(client)!!.identity.deviceId.value)
        assertEquals("CONNECTION_FAILED", restarted.roleFailures(current).hostRegistration)
        assertNull(restartedStore.load(host))
        mainBefore.fill(0)
    }
    @Test fun 角色围栏和主凭据同时不可写时账号级失败关闭且进程重建不能恢复() {
        val directory = Files.createTempDirectory("remote-vault-role-terminal-fail-closed").toFile()
        val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
        val epoch = vault.revokeActive()
        val active = vault.activate("https://remote.example", "owner", epoch)
        val failing = RemoteCredentialVault(directory, protector,
            { _, _ -> throw IOException("模拟角色围栏不可写") },
            { _, _ -> throw IOException("模拟主凭据不可写") })

        val failure = assertThrows(com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException::class.java) {
            failing.stopHostUntilLogin(active, RemoteHostFailure(RemoteHostFailureStage.CONTROL, "DEVICE_REVOKED"))
        }
        assertEquals("REMOTE_LOGOUT_INCOMPLETE", failure.code)
        assertNull(failing.activeAccount())
        val restarted = RemoteCredentialVault(directory, protector)
        assertNull(restarted.activeAccount())

        // 即使全局撤销位意外丢失，同进程失败关闭集合仍拒绝旧 epoch 恢复。
        File(directory, "remote-revocation.bin").writeBytes(byteArrayOf(0))
        assertNull(RemoteCredentialVault(directory, protector).activeAccount())
        assertThrows(IllegalStateException::class.java) { restarted.requireCurrent(active) }
    }
    @Test fun 旧连接迟到的失败关闭保留显式重登后的账号() {
        val directory = Files.createTempDirectory("remote-vault-stale-stop").toFile()
        val protector = Protector()
        val vault = RemoteCredentialVault(directory, protector)
        val old = vault.activate("https://remote.example", "owner", vault.revokeActive())
        val current = vault.activate(old.origin, old.accountId, vault.revokeActive())
        vault.failClosed(old)
        assertEquals(current, RemoteCredentialVault(directory, protector).activeAccount())
        vault.requireCurrent(current)
    }
    @Test fun 角色围栏单独写失败时回退加密主信封且只停止目标角色() {
        val directory = Files.createTempDirectory("remote-vault-role-terminal-main-fallback").toFile()
        val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
        val epoch = vault.revokeActive()
        val active = vault.activate("https://remote.example", "owner", epoch)
        val expected = RemoteHostFailure(RemoteHostFailureStage.CONTROL, "DEVICE_REVOKED")
        val fenceFailing = RemoteCredentialVault(directory, protector,
            { _, _ -> throw IOException("模拟角色围栏不可写") },
            { atomic, stream -> atomic.finishWrite(stream) })

        fenceFailing.stopHostUntilLogin(active, expected)

        val restarted = RemoteCredentialVault(directory, protector)
        assertEquals(active, restarted.activeAccount())
        val failures = restarted.roleFailures(active)
        assertEquals(expected, failures.hostStop)
        assertNull("Host 终态不能扩大到 Client", failures.clientStop)
    }
    @Test fun stopHostUntilLogin回退未知错误码后重启仍可读取角色围栏且不影响Client() {
        val directory = Files.createTempDirectory("remote-vault-host-fallback-code").toFile()
        val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
        val active = vault.activate("https://remote.example", "owner", vault.revokeActive())

        vault.stopHostUntilLogin(active, RemoteHostFailure(RemoteHostFailureStage.REGISTRATION, "METHOD_NOT_FOUND"))

        val inProcess = vault.roleFailures(active)
        val expected = RemoteHostFailure(RemoteHostFailureStage.REGISTRATION, "CONTROL_CONNECTION_FAILED")
        assertEquals(expected, inProcess.hostStop)
        assertNull("未知 Host 错误回退不得连带停用健康 Client", inProcess.clientStop)

        val restarted = RemoteCredentialVault(directory, protector)
        val afterRestart = restarted.roleFailures(active)
        assertEquals(expected, afterRestart.hostStop)
        assertNull("重启读取角色围栏后 Client 仍须保持可用", afterRestart.clientStop)
    }
}
