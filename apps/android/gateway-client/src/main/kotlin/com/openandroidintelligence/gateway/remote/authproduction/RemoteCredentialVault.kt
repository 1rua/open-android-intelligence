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
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

data class RemoteActiveAccount(val origin: String, val accountId: String, val epoch: Long, val loginAttemptId: String? = null)

/**
 * 两个角色与活动账号/撤销代际位于同一个加密 AtomicFile 信封，所有读改写受文件锁保护。
 * 退出先提交 inactive 与更高代际；角色清理失败或进程重启都不能重新恢复旧活动账号。
 */
class RemoteCredentialVault internal constructor(
    directory: File,
    private val protector: RemoteIdentityProtector,
    private val commitCredentials: (AtomicFile, FileOutputStream) -> Unit,
) : RemoteIdentityPersistence {
    private val directory = directory.apply { check(mkdirs() || isDirectory) }
    private val atomic = AtomicFile(File(directory, "remote-credentials.bin"))
    private val lockFile = File(directory, "remote-credentials.lock")
    // 只保存撤销待办位，不包含账号、凭据或业务内容；解密失败也不能恢复旧会话。
    private val revocation = AtomicFile(File(directory, "remote-revocation.bin"))
    private val monitor = monitors.computeIfAbsent(lockFile.canonicalPath) { Any() }
    private val transactionMutex = transactions.computeIfAbsent(lockFile.canonicalPath) { Mutex() }
    private val loginFence = RemoteLoginAttemptFence(directory)
    private val master = "credential-vault"
    private val aad = "oai-remote-credential-vault/v1".encodeToByteArray()

    constructor(directory: File, protector: RemoteIdentityProtector) : this(directory, protector, { file, stream -> file.finishWrite(stream) })
    constructor(context: Context) : this(File(context.noBackupFilesDir, "remote"), AndroidRemoteIdentityProtector())

    /** 安装身份与本凭据仓库使用同一安装目录及保护器，避免注入仓库后混入另一套存储。 */
    internal fun installationIdentityStore(defaultName: () -> String) = RemoteInstallationIdentityStore(directory, protector, defaultName)

    suspend fun <T> credentialTransaction(block: suspend () -> T): T = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            RandomAccessFile(File(directory, "remote-refresh.lock"), "rw").use { file -> file.channel.lock().use { block() } }
        }
    }

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
    fun beginLoginAttempt(): String { loginFence.block(); return loginFence.allowNewAttempt() }
    fun cancelLoginAttempt(id: String) = loginFence.cancelAttempt(id)
    fun requireLoginAttempt(id: String) = loginFence.requirePending(id)
    fun authorizationEpoch(): Long = locked { load().epoch }
    fun requireEpoch(expected: Long) = locked { check(load().epoch == expected) { "REMOTE_AUTHORIZATION_SUPERSEDED" } }
    fun activeAccount(): RemoteActiveAccount? = locked {
        if (revocationPending()) null else load().active?.takeIf { it.loginAttemptId == null || loginFence.authorized(it.loginAttemptId) }
    }
    internal data class RoleFailures(val clientRegistration: String? = null, val hostRegistration: String? = null, val hostStop: String? = null)

    internal fun roleFailures(account: RemoteActiveAccount): RoleFailures = locked {
        val state = load()
        check(state.active == account) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
        state.failures
    }
    internal fun stopHostUntilLogin(account: RemoteActiveAccount, code: String) = locked {
        val state = load()
        if (state.active == account) save(state.copy(failures = state.failures.copy(hostStop = code)))
    }
    fun revokeActive(): Long = locked {
        saveRevocation(true)
        val state = load(); val next = Math.addExact(state.epoch, 1)
        save(state.copy(epoch = next, active = null, failures = RoleFailures()))
        saveRevocation(false)
        next
    }
    fun activate(origin: String, accountId: String, expectedEpoch: Long, loginAttemptId: String? = null,
        clientRegistrationError: String? = null, hostRegistrationError: String? = null): RemoteActiveAccount = locked {
        check(!revocationPending()) { "REMOTE_LOGOUT_INCOMPLETE" }
        val state = load()
        check(state.epoch == expectedEpoch) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
        loginAttemptId?.let(loginFence::requirePending)
        val account = RemoteActiveAccount(origin, accountId, expectedEpoch, loginAttemptId)
        save(state.copy(active = account, failures = RoleFailures(clientRegistrationError, hostRegistrationError)))
        try { loginAttemptId?.let(loginFence::complete) }
        catch (failure: Exception) {
            // 本临界区内没有其他账号提交；失败时只清除此尝试，撤销终态仍独立阻止重启恢复。
            runCatching { save(state.copy(active = null)) }
            throw failure
        }
        account
    }
    fun requireCurrent(account: RemoteActiveAccount) = locked {
        check(!revocationPending() && load().active == account &&
            (account.loginAttemptId == null || loginFence.authorized(account.loginAttemptId))) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
    }
    private fun revocationPending(): Boolean = try {
        revocation.openRead().use { it.read() != 0 }
    } catch (_: FileNotFoundException) { revocation.baseFile.exists() }
    private fun saveRevocation(pending: Boolean) {
        val stream = revocation.startWrite()
        try {
            stream.write(if (pending) 1 else 0); revocation.finishWrite(stream)
            // AtomicFile.finishWrite 在部分平台只记录替换错误；重新读取确认，不能据此误报退出成功。
            check(revocation.baseFile.isFile && revocation.openRead().use { it.readBytes().contentEquals(byteArrayOf(if (pending) 1 else 0)) }) { "REMOTE_LOGOUT_INCOMPLETE" }
        }
        catch (failure: Exception) { revocation.failWrite(stream); throw failure }
    }
    private data class State(val epoch: Long = 0, val active: RemoteActiveAccount? = null, val records: Map<String, String> = emptyMap(),
        val failures: RoleFailures = RoleFailures())
    private fun load(): State {
        val ciphertext = try { atomic.openRead().use { it.readBytes() } } catch (_: FileNotFoundException) { return State() }
        if (!protector.hasKey(master)) throw RemoteIdentityLockedException()
        val plain = try { protector.open(master, ciphertext, aad) } catch (_: Exception) { throw RemoteIdentityCorruptedException() }
        return try {
            val value = RemoteWireJson.decode(plain).objectValue()
            check(value.longValue("v") == 1L)
            val epoch = value.longValue("epoch"); check(epoch >= 0)
            val active = value.value("active").let { row -> if (row == JsonValue.JNull) null else row.objectValue().let {
                RemoteActiveAccount(it.requiredText("origin"), it.requiredText("accountId"), epoch, it.optionalText("loginAttemptId"))
            } }
            val records = value.value("records").objectValue().fields.associate { (key, v) -> key to (v as JsonValue.JString).value }
            State(epoch, active, records, RoleFailures(value.optionalText("clientRegistrationError"),
                value.optionalText("hostRegistrationError"), value.optionalText("hostStop")))
        } catch (_: Exception) { throw RemoteIdentityCorruptedException() } finally { plain.fill(0) }
    }
    private fun save(state: State) {
        val plain = Json.canonical(obj("v" to 1, "epoch" to state.epoch,
            "clientRegistrationError" to state.failures.clientRegistration, "hostRegistrationError" to state.failures.hostRegistration,
            "hostStop" to state.failures.hostStop, "active" to state.active?.let { obj("origin" to it.origin, "accountId" to it.accountId, "loginAttemptId" to it.loginAttemptId) }, "records" to state.records)).encodeToByteArray()
        val cipher = try { protector.seal(master, plain, aad) }
        catch (_: Exception) { throw RemoteRpcException("REMOTE_CREDENTIAL_PERSISTENCE_FAILED") }
        finally { plain.fill(0) }
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(cipher)
            commitCredentials(atomic, stream)
            check(atomic.baseFile.isFile)
            val committed = atomic.openRead().use { it.readBytes() }
            try { check(committed.contentEquals(cipher)) }
            finally { committed.fill(0) }
        } catch (_: Exception) {
            stream?.let { runCatching { atomic.failWrite(it) } }
            throw RemoteRpcException("REMOTE_CREDENTIAL_PERSISTENCE_FAILED")
        } finally { cipher.fill(0) }
    }
    private fun <T> locked(block: () -> T): T = synchronized(monitor) {
        RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use { block() } }
    }
    companion object { private val monitors = ConcurrentHashMap<String, Any>(); private val transactions = ConcurrentHashMap<String, Mutex>() }
}
