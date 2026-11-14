package com.openandroidintelligence.gateway.remote.authproduction

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityCorruptedException
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityLockedException
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityPersistence
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 读路径只取已有密钥；缺失或失效时失败，不重新生成密钥掩盖损坏。 */
class AndroidRemoteIdentityProtector : RemoteIdentityProtector {
    private fun alias(scopeKey: String) = "oai.remote.v1.$scopeKey"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun hasKey(scopeKey: String): Boolean = store().containsAlias(alias(scopeKey))
    override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray = synchronized(keyLock) {
        val name = alias(scopeKey)
        val existing = store().getKey(name, null) as? SecretKey
        val key = existing ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key); updateAAD(associatedData)
            iv + doFinal(plaintext)
        }
    }
    override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        val key = store().getKey(alias(scopeKey), null) as? SecretKey ?: throw RemoteIdentityLockedException()
        if (ciphertext.size < 28) throw RemoteIdentityCorruptedException()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            updateAAD(associatedData); doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        }
    }
    companion object { private val keyLock = Any() }
}

data class RemoteActiveAccount(val origin: String, val accountId: String, val epoch: Long)

/**
 * 两个角色与活动账号/撤销代际位于同一个加密 AtomicFile 信封，所有读改写受文件锁保护。
 * 退出先提交 inactive 与更高代际；角色清理失败或进程重启都不能重新恢复旧活动账号。
 */
class RemoteCredentialVault(
    directory: File,
    private val protector: RemoteIdentityProtector,
) : RemoteIdentityPersistence {
    private val directory = directory.apply { check(mkdirs() || isDirectory) }
    private val atomic = AtomicFile(File(directory, "remote-credentials.bin"))
    private val lockFile = File(directory, "remote-credentials.lock")
    private val monitor = monitors.computeIfAbsent(lockFile.canonicalPath) { Any() }
    private val master = "credential-vault"
    private val aad = "oai-remote-credential-vault/v1".encodeToByteArray()

    constructor(context: Context) : this(File(context.noBackupFilesDir, "remote"), AndroidRemoteIdentityProtector())

    override fun read(scopeKey: String): ByteArray? = locked {
        val state = load()
        (state.records[scopeKey])?.let(RemoteBase64Url::decode)
    }
    override fun writeAtomic(scopeKey: String, value: ByteArray) = locked {
        val state = load()
        save(state.copy(records = state.records + (scopeKey to RemoteBase64Url.encode(value))))
    }
    override fun clear(scopeKey: String) = locked {
        val state = load()
        // 以原子替换提交清空，不删除文件；旧角色即使恢复进程也读不到旧密文。
        save(state.copy(records = state.records - scopeKey))
    }
    fun activeAccount(): RemoteActiveAccount? = locked { load().active }
    fun revokeActive(): Long = locked {
        val state = load(); val next = Math.addExact(state.epoch, 1)
        save(state.copy(epoch = next, active = null)); next
    }
    fun activate(origin: String, accountId: String, expectedEpoch: Long): RemoteActiveAccount = locked {
        val state = load()
        check(state.epoch == expectedEpoch) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
        val account = RemoteActiveAccount(origin, accountId, expectedEpoch)
        save(state.copy(active = account)); account
    }
    fun requireCurrent(account: RemoteActiveAccount) = locked {
        check(load().active == account) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
    }
    private data class State(val epoch: Long = 0, val active: RemoteActiveAccount? = null, val records: Map<String, String> = emptyMap())
    private fun load(): State {
        val ciphertext = try { atomic.openRead().use { it.readBytes() } } catch (_: FileNotFoundException) { return State() }
        if (!protector.hasKey(master)) throw RemoteIdentityLockedException()
        val plain = try { protector.open(master, ciphertext, aad) } catch (_: Exception) { throw RemoteIdentityCorruptedException() }
        return try {
            val value = RemoteWireJson.decode(plain).objectValue()
            check(value.longValue("v") == 1L)
            val epoch = value.longValue("epoch"); check(epoch >= 0)
            val active = value.value("active").let { row -> if (row == JsonValue.JNull) null else row.objectValue().let {
                RemoteActiveAccount(it.requiredText("origin"), it.requiredText("accountId"), epoch)
            } }
            val records = value.value("records").objectValue().fields.associate { (key, v) -> key to (v as JsonValue.JString).value }
            State(epoch, active, records)
        } catch (_: Exception) { throw RemoteIdentityCorruptedException() } finally { plain.fill(0) }
    }
    private fun save(state: State) {
        val plain = Json.canonical(obj("v" to 1, "epoch" to state.epoch,
            "active" to state.active?.let { obj("origin" to it.origin, "accountId" to it.accountId) }, "records" to state.records)).encodeToByteArray()
        val cipher = try { protector.seal(master, plain, aad) } finally { plain.fill(0) }
        val stream = atomic.startWrite()
        try { stream.write(cipher); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun <T> locked(block: () -> T): T = synchronized(monitor) {
        RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use { block() } }
    }
    companion object { private val monitors = ConcurrentHashMap<String, Any>() }
}
