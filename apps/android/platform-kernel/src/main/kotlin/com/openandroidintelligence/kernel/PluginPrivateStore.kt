package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.util.Base64

/** Raised when a plugin reaches for storage that is not its own. */
class StorageDenied(code: String) : IllegalArgumentException("STORAGE_DENIED:$code")

/**
 * Where a plugin's mutable data lives: partitioned by plugin identity, Gateway
 * account and this Android installation.
 *
 * Code and static assets are shared at phone level, but mutable data must never
 * leak across accounts implicitly, so the partition key is part of every
 * operation and is re-checked on every call rather than trusted from the caller.
 */
interface PrivateStoreBackend {
    fun read(partition: String, key: String): ByteArray?
    fun write(partition: String, key: String, value: ByteArray)
    fun delete(partition: String, key: String)
    fun usedBytes(partition: String): Long
    fun keys(partition: String): Set<String>
    /** Must physically delete every partition in this encoded account scope, or throw. */
    fun deleteAccountPartitions(scopePrefix: String)
    fun deletePluginPartitions(pluginId: String, author: String) { throw StorageDenied("PLUGIN_ERASE_UNAVAILABLE") }
}

/** An in-process backend. The shipped host replaces it with an encrypted store. */
class InMemoryPrivateStoreBackend : PrivateStoreBackend {
    private val data = HashMap<String, MutableMap<String, ByteArray>>()

    override fun read(partition: String, key: String): ByteArray? =
        data[partition]?.get(key)?.copyOf()

    override fun write(partition: String, key: String, value: ByteArray) {
        data.getOrPut(partition) { HashMap() }[key] = value.copyOf()
    }

    override fun delete(partition: String, key: String) {
        data[partition]?.remove(key)
    }

    override fun usedBytes(partition: String): Long =
        data[partition]?.values?.sumOf { it.size.toLong() } ?: 0L

    override fun keys(partition: String): Set<String> =
        data[partition]?.keys?.toSet() ?: emptySet()

    /** Wipes one plugin's data for one account, used when that account is removed. */
    override fun deleteAccountPartitions(scopePrefix: String) {
        data.keys.filter { it.startsWith(scopePrefix) }.forEach { data.remove(it) }
    }
    override fun deletePluginPartitions(pluginId: String, author: String) {
        data.keys.filter { it.endsWith("|$pluginId|$author") }.forEach { data.remove(it) }
    }
}

/**
 * An open partition, bound to one plugin and one account.
 *
 * The handle is a capability: holding it lets you act on exactly that
 * partition, and every call re-checks the account so a handle captured under
 * one account cannot be replayed against another.
 */
class StorageHandle internal constructor(
    internal val partition: String,
    val pluginId: String,
    val accountId: String,
    val installId: String,
    internal val owner: Any,
    internal val generation: Long,
)

class PluginPrivateStore(
    private val installId: String,
    private val backend: PrivateStoreBackend,
    private val maxBytesPerPartition: Long,
    private val maxKeyLength: Int = 256,
) {
    private val owner = Any()
    private val generations = HashMap<String, Long>()
    private val deletionPending = HashSet<String>()

    @Synchronized
    fun open(identity: PluginIdentity, accountId: String, pairingId: String = ""): StorageHandle {
        if (accountId in deletionPending) throw StorageDenied("ACCOUNT_DELETION_PENDING")
        return StorageHandle(
            partition = accountPrefix(accountId) + (if (pairingId.isEmpty()) "" else encode(pairingId) + "|") + encode(identity.pluginId) + "|" + encode(identity.authorKeyFingerprint),
            pluginId = identity.pluginId,
            accountId = accountId,
            installId = installId,
            owner = owner,
            generation = generations[accountId] ?: 0L,
        )
    }

    @Synchronized fun read(handle: StorageHandle, accountId: String, key: String): ByteArray? {
        checkScope(handle, accountId, key)
        return backend.read(handle.partition, key)
    }

    @Synchronized fun write(handle: StorageHandle, accountId: String, key: String, value: ByteArray) {
        checkScope(handle, accountId, key)
        val current = backend.usedBytes(handle.partition)
        val existing = backend.read(handle.partition, key)?.size?.toLong() ?: 0L
        if (current - existing + value.size > maxBytesPerPartition) {
            throw StorageDenied("QUOTA")
        }
        backend.write(handle.partition, key, value)
    }

    @Synchronized fun delete(handle: StorageHandle, accountId: String, key: String) {
        checkScope(handle, accountId, key)
        backend.delete(handle.partition, key)
    }

    @Synchronized fun keys(handle: StorageHandle, accountId: String): Set<String> {
        checkHandle(handle, accountId)
        return backend.keys(handle.partition)
    }

    /** Removes every partition belonging to one account, on account removal. */
    @Synchronized fun eraseAccount(accountId: String) {
        generations[accountId] = (generations[accountId] ?: 0L) + 1L
        deletionPending.add(accountId)
        backend.deleteAccountPartitions(accountPrefix(accountId))
        deletionPending.remove(accountId)
    }

    @Synchronized fun erasePairing(accountId:String,pairingId:String) {
        require(pairingId.isNotEmpty())
        generations[accountId]=(generations[accountId] ?: 0L)+1L
        backend.deleteAccountPartitions(accountPrefix(accountId)+encode(pairingId)+"|")
    }

    private fun checkScope(handle: StorageHandle, accountId: String, key: String) {
        checkHandle(handle, accountId)
        if (key.isEmpty() || key.length > maxKeyLength) throw StorageDenied("BAD_KEY")
    }

    private fun checkHandle(handle: StorageHandle, accountId: String) {
        if (handle.accountId != accountId) throw StorageDenied("ACCOUNT_MISMATCH")
        if (handle.installId != installId) throw StorageDenied("INSTALL_MISMATCH")
        if (handle.owner !== owner || handle.generation != (generations[accountId] ?: 0L) || accountId in deletionPending) throw StorageDenied("STALE_HANDLE")
    }

    private fun accountPrefix(accountId: String) = encode(installId) + "|" + encode(accountId) + "|"
    @Synchronized fun erasePlugin(identity: PluginIdentity) {
        backend.deletePluginPartitions(encode(identity.pluginId),encode(identity.authorKeyFingerprint))
    }
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
