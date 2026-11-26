package com.openandroidintelligence.gateway.remote.authproduction

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityCorruptedException
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityLockedException
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityPersistence
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityStore
import com.openandroidintelligence.gateway.remote.auth.safeRoleErrorCode
import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.identity.RemoteRoleCredential
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
import java.security.MessageDigest
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
    private val commitRoleTerminal: (AtomicFile, FileOutputStream) -> Unit = { file, stream -> file.finishWrite(stream) },
    private val commitCredentials: (AtomicFile, FileOutputStream) -> Unit,
) : RemoteIdentityPersistence {
    private val directory = directory.apply { check(mkdirs() || isDirectory) }
    private val atomic = AtomicFile(File(directory, "remote-credentials.bin"))
    private val lockFile = File(directory, "remote-credentials.lock")
    // 只保存撤销待办位，不包含账号、凭据或业务内容；解密失败也不能恢复旧会话。
    private val revocation = AtomicFile(File(directory, "remote-revocation.bin"))
    // 独立按角色终结围栏只保存 epoch、账号指纹和固定错误码；主凭据写入失败时仍能阻止重启复活。
    private val roleTerminal = AtomicFile(File(directory, "remote-role-terminal.bin"))
    private val monitor = monitors.computeIfAbsent(lockFile.canonicalPath) { Any() }
    private val transactionMutex = transactions.computeIfAbsent(lockFile.canonicalPath) { Mutex() }
    private val loginFence = RemoteLoginAttemptFence(directory)
    private val master = "credential-vault"
    private val aad = "oai-remote-credential-vault/v1".encodeToByteArray()

    constructor(directory: File, protector: RemoteIdentityProtector) : this(directory, protector,
        { file, stream -> file.finishWrite(stream) }, { file, stream -> file.finishWrite(stream) })
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
        if (revocationPending()) null else load().active?.takeIf {
            !processFailClosed(it) && (it.loginAttemptId == null || loginFence.authorized(it.loginAttemptId))
        }
    }
    internal data class RoleFailures(val clientRegistration: String? = null, val hostRegistration: String? = null,
        val hostStop: RemoteHostFailure? = null, val clientStop: String? = null)

    internal fun roleFailures(account: RemoteActiveAccount): RoleFailures = locked {
        val state = load()
        check(state.active == account) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
        val terminal = try { loadRoleTerminal(account) } catch (_: Exception) {
            val failedClosed = RemoteHostFailure(RemoteHostFailureStage.CONTROL, ROLE_TERMINAL_UNAVAILABLE)
            return@locked state.failures.copy(clientStop = state.failures.clientStop ?: ROLE_TERMINAL_UNAVAILABLE,
                hostStop = state.failures.hostStop ?: failedClosed)
        }
        state.failures.copy(clientStop = terminal?.client ?: state.failures.clientStop,
            hostStop = terminal?.host ?: state.failures.hostStop)
    }
    internal fun stopClientUntilLogin(account: RemoteActiveAccount, code: String) = locked {
        val state = load()
        if (state.active != account) return@locked
        val safe = safeRoleErrorCode(code, "CONNECTION_FAILED")
        val updated = state.copy(failures = state.failures.copy(clientStop = safe))
        persistRoleTerminal(updated, account, { it.copy(client = safe) })
    }
    internal fun stopHostUntilLogin(account: RemoteActiveAccount, failure: RemoteHostFailure) = locked {
        val state = load()
        if (state.active != account) return@locked
        val safe = failure.copy(code = safeRoleErrorCode(failure.code, "CONTROL_CONNECTION_FAILED"))
        val updated = state.copy(failures = state.failures.copy(hostStop = safe))
        persistRoleTerminal(updated, account, { it.copy(host = safe) })
    }
    internal sealed interface HostRegistrationCommitResult {
        data object Committed : HostRegistrationCommitResult
        data object NotEligible : HostRegistrationCommitResult
        data object CredentialsLocked : HostRegistrationCommitResult
        data object ExistingHostConflict : HostRegistrationCommitResult
        data object IdentityConflict : HostRegistrationCommitResult
    }
    /**
     * 首次 Host 注册补偿只在仍有效的同一授权 epoch 内执行，并把 Host 凭据与清除暂态注册标记合并成一次提交。
     * 提交结果区分暂锁、冲突与失去资格，供运行时决定是否保留同一次注册响应。
     */
    internal fun completeHostRegistration(account: RemoteActiveAccount, credential: RemoteRoleCredential): HostRegistrationCommitResult = locked {
        if (!authorizationCurrent(account)) return@locked HostRegistrationCommitResult.NotEligible
        val state = load()
        if (state.active != account || state.failures.clientRegistration != null ||
            state.failures.clientStop != null || state.failures.hostStop != null ||
            state.failures.hostRegistration !in RETRYABLE_HOST_REGISTRATION_ERRORS) return@locked HostRegistrationCommitResult.NotEligible
        val terminal = try { loadRoleTerminal(account) } catch (_: Exception) { return@locked HostRegistrationCommitResult.NotEligible }
        if (terminal?.client != null || terminal?.host != null) return@locked HostRegistrationCommitResult.NotEligible

        val clientScope = RemoteIdentityScope(account.origin, account.accountId, RemoteRole.CLIENT)
        val hostScope = RemoteIdentityScope(account.origin, account.accountId, RemoteRole.HOST)
        if (state.records.containsKey(hostScope.storageKey)) return@locked HostRegistrationCommitResult.ExistingHostConflict
        val clientCredential = try { RemoteIdentityStore(ReadOnlyRecords(state.records), protector).load(clientScope) }
        catch (_: RemoteIdentityLockedException) { return@locked HostRegistrationCommitResult.CredentialsLocked }
        catch (_: Exception) { return@locked HostRegistrationCommitResult.NotEligible } ?: return@locked HostRegistrationCommitResult.NotEligible
        if (clientCredential.identity.deviceId == credential.identity.deviceId ||
            MessageDigest.isEqual(clientCredential.identity.publicKey, credential.identity.publicKey)) {
            return@locked HostRegistrationCommitResult.IdentityConflict
        }

        val failures = state.failures.copy(hostRegistration = null)
        val snapshot = HostRegistrationCommit(state, failures, hostScope.storageKey, ::save)
        val registrationStore = RemoteIdentityStore(snapshot, protector)
        val ticket = registrationStore.beginRegistration(account.origin, account.accountId)
        try { registrationStore.commitRegistration(ticket, hostScope, credential) }
        catch (_: RemoteIdentityLockedException) { return@locked HostRegistrationCommitResult.CredentialsLocked }
        if (snapshot.committed()) HostRegistrationCommitResult.Committed else HostRegistrationCommitResult.NotEligible
    }
    /** 仅用于角色终态围栏本身也不可用时；账号级失败关闭，下一次显式登录建立新 epoch。 */
    internal fun failClosed(account: RemoteActiveAccount) = locked {
        markFailClosed(account)
    }
    fun revokeActive(): Long = locked { revokeActiveLocked() }
    private fun revokeActiveLocked(): Long {
        saveRevocation(true)
        val state = load(); val next = Math.addExact(state.epoch, 1)
        save(state.copy(epoch = next, active = null, failures = RoleFailures()))
        saveRevocation(false)
        return next
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
        check(authorizationCurrent(account) && load().active == account) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
    }
    private fun authorizationCurrent(account: RemoteActiveAccount): Boolean =
        !revocationPending() && !processFailClosed(account) &&
            (account.loginAttemptId == null || loginFence.authorized(account.loginAttemptId))
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
    private data class RoleTerminal(val epoch: Long, val account: String,
        val client: String? = null, val host: RemoteHostFailure? = null)
    private fun loadRoleTerminal(account: RemoteActiveAccount): RoleTerminal? {
        val bytes = try { roleTerminal.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) {
            check(!roleTerminal.baseFile.exists()) { ROLE_TERMINAL_UNAVAILABLE }
            return null
        }
        val value = RemoteWireJson.decode(bytes).objectValue()
        check(value.longValue("v") == 1L) { ROLE_TERMINAL_UNAVAILABLE }
        val epoch = value.longValue("epoch")
        val accountFingerprint = value.requiredText("account")
        check(epoch >= 0 && accountFingerprint.matches(Regex("[0-9a-f]{64}"))) { ROLE_TERMINAL_UNAVAILABLE }
        fun code(name: String): String? = value.optionalText(name)?.also {
            check(it != INVALID_ROLE_ERROR && safeRoleErrorCode(it, INVALID_ROLE_ERROR) == it) { ROLE_TERMINAL_UNAVAILABLE }
        }
        val host = value.value("host").let { row ->
            if (row == JsonValue.JNull) null else row.objectValue().let {
                val code = it.requiredText("code")
                check(code != INVALID_ROLE_ERROR && safeRoleErrorCode(code, INVALID_ROLE_ERROR) == code) { ROLE_TERMINAL_UNAVAILABLE }
                RemoteHostFailure(RemoteHostFailureStage.valueOf(it.requiredText("stage")), code)
            }
        }
        val terminal = RoleTerminal(epoch, accountFingerprint, code("client"), host)
        check(epoch <= account.epoch) { ROLE_TERMINAL_UNAVAILABLE }
        return terminal.takeIf { it.epoch == account.epoch && it.account == accountKey(account) }
    }
    private fun updateRoleTerminal(account: RemoteActiveAccount, update: (RoleTerminal) -> RoleTerminal) {
        val current = try { loadRoleTerminal(account) } catch (_: Exception) {
            // 围栏损坏或超前时不能猜测旧状态，直接把本 epoch 两个角色一起失败关闭后原子重写。
            RoleTerminal(account.epoch, accountKey(account), ROLE_TERMINAL_UNAVAILABLE,
                RemoteHostFailure(RemoteHostFailureStage.CONTROL, ROLE_TERMINAL_UNAVAILABLE))
        } ?: RoleTerminal(account.epoch, accountKey(account))
        saveRoleTerminal(update(current))
    }
    private fun persistRoleTerminal(updated: State, account: RemoteActiveAccount, update: (RoleTerminal) -> RoleTerminal) {
        val terminalPersisted = try { updateRoleTerminal(account, update); true }
        catch (_: RemoteRpcException) { false }
        if (terminalPersisted) {
            // 围栏已能独立保证恢复；加密信封只作为兼容缓存，不因缓存写失败影响另一角色。
            runCatching { save(updated) }
        } else {
            try { save(updated) }
            catch (_: RemoteRpcException) { failClosedLocked(account) }
        }
    }
    private fun saveRoleTerminal(state: RoleTerminal) {
        val bytes = Json.canonical(obj("v" to 1, "epoch" to state.epoch, "account" to state.account,
            "client" to state.client, "host" to state.host?.let { obj("stage" to it.stage.name, "code" to it.code) })).encodeToByteArray()
        var stream: FileOutputStream? = null
        try {
            stream = roleTerminal.startWrite()
            stream.write(bytes)
            commitRoleTerminal(roleTerminal, stream)
            check(roleTerminal.baseFile.isFile)
            val committed = roleTerminal.openRead().use { it.readBytes() }
            try { check(committed.contentEquals(bytes)) }
            finally { committed.fill(0) }
        } catch (_: Exception) {
            stream?.let { runCatching { roleTerminal.failWrite(it) } }
            throw RemoteRpcException(ROLE_TERMINAL_UNAVAILABLE)
        } finally { bytes.fill(0) }
    }
    private fun failClosedLocked(account: RemoteActiveAccount): Nothing {
        markFailClosed(account)
        throw RemoteRpcException("REMOTE_LOGOUT_INCOMPLETE")
    }
    private fun markFailClosed(account: RemoteActiveAccount) {
        processFailedClosed += processKey(account)
        // 旧连接的清理回调可能晚于新账号登录。持锁读取到其他代次时，只封住旧账号。
        val current = runCatching { load() }.getOrNull()
        if (current != null && current.active != account) return
        var markerStaged = false
        var inactiveSaved = false
        try { saveRevocation(true); markerStaged = true } catch (_: Exception) { Unit }
        try {
            val state = current ?: return
            save(state.copy(epoch = Math.addExact(state.epoch, 1), active = null, failures = RoleFailures()))
            inactiveSaved = true
        } catch (_: Exception) { Unit }
        if (markerStaged && inactiveSaved) runCatching { saveRevocation(false) }
    }
    private fun accountKey(account: RemoteActiveAccount): String = MessageDigest.getInstance("SHA-256")
        .digest("${account.origin}\u0000${account.accountId}\u0000${account.epoch}".encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun processKey(account: RemoteActiveAccount) = "${lockFile.canonicalPath}\u0000${accountKey(account)}"
    private fun processFailClosed(account: RemoteActiveAccount) = processKey(account) in processFailedClosed
    /** 只短暂借用已受控的凭据快照解码；Store 的写入能力由单独适配器受限暴露。 */
    private class ReadOnlyRecords(private val records: Map<String, String>) : RemoteIdentityPersistence {
        override fun read(scopeKey: String): ByteArray? = records[scopeKey]?.let(RemoteBase64Url::decode)
        override fun writeAtomic(scopeKey: String, value: ByteArray): Unit = error("REMOTE_CLIENT_READ_INVALID")
        override fun clear(scopeKey: String): Unit = error("REMOTE_CLIENT_READ_INVALID")
    }
    /** 单次 Host 写入适配器复用 Store 的 peer pin 合并与编码，并原子合并清除暂态注册标记。 */
    private class HostRegistrationCommit(private val state: State, private val failures: RoleFailures,
        private val hostScopeKey: String, private val commit: (State) -> Unit) : RemoteIdentityPersistence {
        private var written = false
        override fun read(scopeKey: String): ByteArray? = state.records[scopeKey]?.let(RemoteBase64Url::decode)
        override fun writeAtomic(scopeKey: String, value: ByteArray) {
            check(scopeKey == hostScopeKey && !written) { "REMOTE_HOST_REGISTRATION_WRITE_INVALID" }
            written = true
            commit(state.copy(records = state.records + (scopeKey to RemoteBase64Url.encode(value)), failures = failures))
        }
        override fun clear(scopeKey: String): Unit = error("REMOTE_HOST_REGISTRATION_CLEAR_INVALID")
        fun committed() = written
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
                value.optionalText("hostRegistrationError"), value.optionalText("hostStop")?.let { code ->
                    RemoteHostFailure(RemoteHostFailureStage.valueOf(value.requiredText("hostStopStage")), code)
                }, value.optionalText("clientStop")))
        } catch (_: Exception) { throw RemoteIdentityCorruptedException() } finally { plain.fill(0) }
    }
    private fun save(state: State) {
        val plain = Json.canonical(obj("v" to 1, "epoch" to state.epoch,
            "clientRegistrationError" to state.failures.clientRegistration, "hostRegistrationError" to state.failures.hostRegistration,
            "hostStop" to state.failures.hostStop?.code, "hostStopStage" to state.failures.hostStop?.stage?.name,
            "clientStop" to state.failures.clientStop, "active" to state.active?.let { obj("origin" to it.origin, "accountId" to it.accountId, "loginAttemptId" to it.loginAttemptId) }, "records" to state.records)).encodeToByteArray()
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
    companion object {
        private const val ROLE_TERMINAL_UNAVAILABLE = "REMOTE_CREDENTIAL_PERSISTENCE_FAILED"
        private const val INVALID_ROLE_ERROR = "__INVALID_REMOTE_ROLE_ERROR__"
        internal val RETRYABLE_HOST_REGISTRATION_ERRORS = setOf("CONNECTION_FAILED", "RATE_LIMITED", "INTERNAL_ERROR")
        private val monitors = ConcurrentHashMap<String, Any>()
        private val transactions = ConcurrentHashMap<String, Mutex>()
        private val processFailedClosed = ConcurrentHashMap.newKeySet<String>()
    }
}
