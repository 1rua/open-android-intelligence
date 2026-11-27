package com.openandroidintelligence.mobile.plugins

import android.app.Application
import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.authproduction.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.kernel.RemoteBackgroundBinding
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun testBackgroundSubject(revision: Long, account: String = "account-client") = RemoteBackgroundSubject(RemoteControlPrincipal(
    ControlOrigin.fromCanonical("https://client.example"), account, "controller", "phone-host",
    "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64)), 3L, revision)
internal fun testBackgroundBinding(revision: Long, account: String = "account-client", current: () -> Boolean = { true }) =
    testBackgroundSubject(revision,account).let { RemoteBackgroundBinding(it.principal,it.accountEpoch,it.localGrantRevision,current) }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteBackgroundAuthorityTest {
    private class Protector : RemoteIdentityProtector {
        val keys = mutableMapOf<String, ByteArray>()
        var locked = false
        override fun hasKey(scopeKey: String) = !locked && scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val key = keys.getOrPut(scopeKey) { ByteArray(32).also(SecureRandom()::nextBytes) }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key,"AES")); updateAAD(associatedData); iv + doFinal(plaintext)
            }
        }
        override fun open(scopeKey: String,ciphertext: ByteArray,associatedData: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.getValue(scopeKey),"AES"),GCMParameterSpec(128,ciphertext.copyOfRange(0,12)))
            updateAAD(associatedData); doFinal(ciphertext.copyOfRange(12,ciphertext.size))
        }
    }
    private class Fixture {
        val directory = Files.createTempDirectory("remote-background-vault").toFile()
        val protector = Protector()
        val vault = RemoteCredentialVault(directory,protector)
        val store = RemoteIdentityStore(vault,protector)
        val scope = RemoteIdentityScope("https://remote.example","owner",RemoteRole.HOST)
        val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId("phone-host"))
        val client = RemoteStaticIdentityFactory.generate(RemoteDeviceId("controller"))
        val tokens = RemoteTokenPair("a".repeat(16),1_000_000,"r".repeat(16),2_000_000)
        val epoch = vault.revokeActive()
        val subject = RemoteBackgroundSubject(RemoteControlPrincipal(ControlOrigin.fromCanonical(scope.origin),scope.accountId,
            client.deviceId.value,host.deviceId.value,fingerprint(client.publicKey),fingerprint(host.publicKey)),epoch,9L)
        init {
            store.save(scope,RemoteRoleCredential(host,tokens,listOf(RemotePeerPin(RemoteRole.CLIENT,client.deviceId,client.publicKey))))
            vault.activate(scope.origin,scope.accountId,epoch)
        }
        fun restarted(): ProductionRemoteBackgroundAuthority {
            val restored = RemoteCredentialVault(directory,protector)
            return ProductionRemoteBackgroundAuthority(restored,RemoteIdentityStore(restored,protector))
        }
        private fun fingerprint(key: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
    }
    @Test fun 重启无需网络即可恢复已固定控制者的本机任务主体() {
        val f=Fixture()
        assertEquals(RemoteBackgroundAdmission.Ready,f.restarted().revalidate(f.subject))
        val restored=RemoteBackgroundSubject.decode(Json.parse(Json.canonical(f.subject.encode())))
        assertEquals(f.subject,restored)
        assertEquals(RemoteBackgroundAdmission.Ready,f.restarted().revalidate(restored))
    }
    @Test fun 退出先持久撤销即使旧身份文件仍存在也拒绝旧任务() {
        val f=Fixture(); f.vault.revokeActive()
        assertNotNull(f.store.load(f.scope))
        assertEquals(RemoteBackgroundAdmission.Denied("REMOTE_BACKGROUND_ACCOUNT_CHANGED"),f.restarted().revalidate(f.subject))
        val next=f.vault.activeAccount()
        assertNull(next)
    }
    @Test fun 账号代次本机Host身份与控制者Pin变更分别拒绝旧任务() {
        val f=Fixture()
        assertTrue(f.restarted().revalidate(f.subject.copy(accountEpoch=f.subject.accountEpoch+1)) is RemoteBackgroundAdmission.Denied)
        val changedHost=RemoteStaticIdentityFactory.generate(RemoteDeviceId("phone-host"))
        f.store.save(f.scope,RemoteRoleCredential(changedHost,f.tokens))
        assertEquals(RemoteBackgroundAdmission.Denied("REMOTE_BACKGROUND_HOST_CHANGED"),f.restarted().revalidate(f.subject))
        f.store.save(f.scope,RemoteRoleCredential(f.host,f.tokens))
        assertEquals(RemoteBackgroundAdmission.Denied("REMOTE_BACKGROUND_CONTROLLER_CHANGED"),f.restarted().revalidate(f.subject))
    }
    @Test fun 密钥暂不可读明确等待恢复后可再次复核() {
        val f=Fixture(); val authority=f.restarted(); f.protector.locked=true
        assertEquals(RemoteBackgroundAdmission.Waiting("REMOTE_IDENTITY_LOCKED"),authority.revalidate(f.subject))
        f.protector.locked=false
        assertEquals(RemoteBackgroundAdmission.Ready,authority.revalidate(f.subject))
    }
}
