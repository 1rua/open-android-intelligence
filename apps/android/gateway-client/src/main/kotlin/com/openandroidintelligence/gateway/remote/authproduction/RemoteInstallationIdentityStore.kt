package com.openandroidintelligence.gateway.remote.authproduction

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityCorruptedException
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityLockedException
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityStore
import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.identity.RemoteDeviceId
import com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentity
import com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentityFactory
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RemoteInstallationIdentities(val client: RemoteStaticIdentity, val host: RemoteStaticIdentity) {
    init {
        require(client.deviceId != host.deviceId && !MessageDigest.isEqual(client.publicKey, host.publicKey)) {
            "REMOTE_ROLE_IDENTITY_CONFLICT"
        }
    }
}

/** 安装级双身份独立于账号凭据；退出登录只清账号，不能重置这里的静态密钥。 */
class RemoteInstallationIdentityStore(
    directory: File,
    private val protector: RemoteIdentityProtector,
    private val defaultName: () -> String,
) {
    private val directory = directory.apply { check(mkdirs() || isDirectory) }
    private val atomic = AtomicFile(File(directory, "remote-installation.bin"))
    // 只标记这次安装已开始保存身份，不含账号、token 或密钥；失败后不能误当首次安装。
    private val initialized = AtomicFile(File(directory, "remote-installation-initialized.bin"))
    private val lockFile = File(directory, "remote-installation.lock")
    private val monitor = monitors.computeIfAbsent(lockFile.canonicalPath) { Any() }

    constructor(context: Context, protector: RemoteIdentityProtector = AndroidRemoteIdentityProtector()) : this(File(context.noBackupFilesDir, "remote"), protector,
        { defaultDeviceName(context) })

    internal fun existingIdentities(): RemoteInstallationIdentities? = locked { load()?.identities }

    /** 升级时只迁入当前已验证角色的密钥，不迁入其 token、账号或对端授权。 */
    fun loadOrCreate(client: RemoteStaticIdentity? = null, host: RemoteStaticIdentity? = null): RemoteInstallationIdentities = locked {
        val existing = load()
        if (existing != null) return@locked existing.identities
        val identities = RemoteInstallationIdentities(client ?: generate(), host ?: generate())
        save(Record(identities, normalizeName(defaultName())))
        identities
    }

    fun migrateActiveAccount(vault: RemoteCredentialVault, protector: RemoteIdentityProtector): RemoteInstallationIdentities {
        val active = vault.activeAccount()
        val credentials = RemoteIdentityStore(vault, protector)
        fun current(role: RemoteRole) = active?.let {
            credentials.load(RemoteIdentityScope(it.origin, it.accountId, role))?.identity
        }
        return loadOrCreate(current(RemoteRole.CLIENT), current(RemoteRole.HOST))
    }

    fun deviceName(): String = locked { load()?.name ?: normalizeName(defaultName()) }

    fun updateDeviceName(name: String) = locked {
        val normalized = normalizeName(name)
        val existing = load() ?: Record(RemoteInstallationIdentities(generate(), generate()), normalized)
        save(existing.copy(name = normalized))
    }

    private data class Record(val identities: RemoteInstallationIdentities, val name: String)

    private fun load(): Record? {
        val encrypted = try { atomic.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) {
            // 已有文件或 Keystore 密钥却读不到记录时，不把损坏误认作首次安装。
            if (atomic.baseFile.exists() || initializationStarted() || protector.hasKey(KEY)) throw RemoteIdentityCorruptedException()
            return null
        }
        if (!protector.hasKey(KEY)) throw RemoteIdentityLockedException()
        val plain = try { protector.open(KEY, encrypted, AAD) }
        catch (_: Exception) { throw RemoteIdentityCorruptedException() }
        finally { encrypted.fill(0) }
        return try {
            val row = RemoteWireJson.decode(plain).objectValue()
            check(row.longValue("v") == 1L)
            Record(RemoteInstallationIdentities(identity(row.value("client")), identity(row.value("host"))),
                normalizeName(row.requiredText("name")))
        } catch (_: Exception) { throw RemoteIdentityCorruptedException() }
        finally { plain.fill(0) }
    }

    private fun identity(value: JsonValue): RemoteStaticIdentity {
        val row = value.objectValue()
        val key = RemoteBase64Url.decode(row.requiredText("privateKey"))
        return try {
            RemoteStaticIdentity(RemoteDeviceId(row.requiredText("deviceId")), key,
                RemoteBase64Url.decode(row.requiredText("publicKey")), row.longValue("generation"))
        } finally { key.fill(0) }
    }

    private fun identityValue(identity: RemoteStaticIdentity): Map<String, Any> {
        val key = identity.privateKeyCopy()
        return try { mapOf("deviceId" to identity.deviceId.value, "privateKey" to RemoteBase64Url.encode(key),
            "publicKey" to RemoteBase64Url.encode(identity.publicKey), "generation" to identity.generation) }
        finally { key.fill(0) }
    }

    private fun save(record: Record) {
        markInitializationStarted()
        val plain = Json.canonical(Json.of(mapOf("v" to 1, "name" to record.name,
            "client" to identityValue(record.identities.client), "host" to identityValue(record.identities.host)))).encodeToByteArray()
        val encrypted = try { protector.seal(KEY, plain, AAD) } finally { plain.fill(0) }
        val stream = atomic.startWrite()
        try {
            stream.write(encrypted)
            atomic.finishWrite(stream)
            val committed = atomic.openRead().use { it.readBytes() }
            try { check(committed.contentEquals(encrypted)) { "REMOTE_INSTALLATION_PERSISTENCE_FAILED" } }
            finally { committed.fill(0) }
        } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
        finally { encrypted.fill(0) }
    }

    private fun initializationStarted(): Boolean = try {
        // 此标记没有“清除”状态，空文件或损坏内容同样不能被当作从未初始化。
        initialized.openRead().use { true }
    } catch (_: FileNotFoundException) { initialized.baseFile.exists() }

    private fun markInitializationStarted() {
        if (initializationStarted()) return
        val stream = initialized.startWrite()
        try {
            stream.write(1)
            initialized.finishWrite(stream)
            check(initialized.baseFile.isFile && initialized.openRead().use { it.readBytes().contentEquals(byteArrayOf(1)) }) {
                "REMOTE_INSTALLATION_PERSISTENCE_FAILED"
            }
        } catch (failure: Exception) { initialized.failWrite(stream); throw failure }
    }

    private fun <T> locked(block: () -> T): T = synchronized(monitor) {
        RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use { block() } }
    }

    companion object {
        private const val KEY = "installation-identity-v1"
        private val AAD = "oai-remote-installation/v1".encodeToByteArray()
        private val monitors = ConcurrentHashMap<String, Any>()
        private fun generate() = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))

        fun defaultDeviceName(context: Context): String {
            val configured = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            if (!configured.isNullOrBlank()) return normalizeName(configured)
            val maker = Build.MANUFACTURER.trim()
            val model = Build.MODEL.trim()
            val fallback = if (model.startsWith(maker, ignoreCase = true)) model
                else listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ")
            return normalizeName(fallback.ifBlank { "Android" })
        }

        /** 与上游 descriptor.name 的 128 字节 UTF-8 边界一致。 */
        fun normalizeName(value: String): String = value.trim().also {
            require(it.isNotBlank() && it.encodeToByteArray().size <= 128 && it.none(Char::isISOControl)) { "REMOTE_DEVICE_NAME_INVALID" }
        }
    }
}
