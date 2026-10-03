package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.encrypted.store.AesGcmEncryptedBlobStore
import com.openandroidintelligence.encrypted.store.AndroidKeystoreOutboxKeyProvider
import com.openandroidintelligence.encrypted.store.FileEncryptedOutboxPersistence
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.kernel.PrivateStoreBackend
import java.io.File
import java.util.Base64

internal fun digestName(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** No-backup, atomic encrypted documents. The authenticated plaintext binds each filename to its scope. */
class EncryptedDocuments(context: Context, private val scope: String,
    private val keyProvider:com.openandroidintelligence.encrypted.store.AesGcmKeyProvider = AndroidKeystoreOutboxKeyProvider("oai_documents_${digestName(scope)}")) {
    private val root = File(context.noBackupFilesDir, "private-documents/${digestName(scope)}")
    private fun blob(key: String) = AesGcmEncryptedBlobStore(
        FileEncryptedOutboxPersistence(File(root, digestName(key))), keyProvider.getOrCreate())
    @Synchronized fun read(key: String): ByteArray? {
        val bytes = blob(key).readPlaintext() ?: return null
        try {
            val doc = JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("DOCUMENT_INVALID")
            check(JsonFields.string(doc,"scope") == scope && JsonFields.string(doc,"key") == key) { "DOCUMENT_SCOPE_MISMATCH" }
            return Base64.getDecoder().decode(JsonFields.string(doc,"value") ?: error("DOCUMENT_INVALID"))
        } finally { bytes.fill(0) }
    }
    @Synchronized fun write(key: String, value: ByteArray) {
        require(value.size <= 8 * 1024 * 1024) { "DOCUMENT_QUOTA" }
        blob(key).writePlaintext(Json.canonical(Json.of(mapOf("scope" to scope,"key" to key,
            "value" to Base64.getEncoder().encodeToString(value)))).toByteArray())
    }
    @Synchronized fun delete(key: String) = blob(key).clearCiphertext()
    @Synchronized fun keys(): Set<String> = root.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}")) }.map { file ->
        val bytes = AesGcmEncryptedBlobStore(FileEncryptedOutboxPersistence(file),keyProvider.getOrCreate()).readPlaintext() ?: error("DOCUMENT_INVALID")
        try {
            val doc = JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("DOCUMENT_INVALID")
            val key = JsonFields.string(doc,"key") ?: error("DOCUMENT_INVALID")
            check(JsonFields.string(doc,"scope") == scope && file.name == digestName(key)) { "DOCUMENT_SCOPE_MISMATCH" }
            key
        } finally { bytes.fill(0) }
    }.toSet()
    @Synchronized fun ciphertextBytes(): Long = root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    @Synchronized fun erase() { check(!root.exists() || root.deleteRecursively()) { "DOCUMENT_ERASE_FAILED" }; keyProvider.delete() }
}

class EncryptedPluginStateBackend(private val context: Context) : PrivateStoreBackend {
    private val index by lazy { EncryptedDocuments(context,"plugin-state-index-v1") }
    private fun documents(partition: String): EncryptedDocuments {
        index.write(partition, byteArrayOf(1))
        return EncryptedDocuments(context,"plugin-state:$partition")
    }
    override fun read(partition: String, key: String) = documents(partition).read(key)
    override fun write(partition: String, key: String, value: ByteArray) = documents(partition).write(key,value)
    override fun delete(partition: String, key: String) = documents(partition).delete(key)
    override fun keys(partition: String) = documents(partition).keys()
    override fun usedBytes(partition: String): Long = documents(partition).let { doc -> doc.keys().sumOf { doc.read(it)?.size?.toLong() ?: 0L } }
    override fun deleteAccountPartitions(scopePrefix: String) {
        index.keys().filter { it.startsWith(scopePrefix) }.forEach { partition ->
            EncryptedDocuments(context,"plugin-state:$partition").erase(); index.delete(partition)
        }
    }
    override fun deletePluginPartitions(pluginId: String, author: String) {
        index.keys().filter { it.endsWith("|$pluginId|$author") }.forEach { partition ->
            EncryptedDocuments(context,"plugin-state:$partition").erase(); index.delete(partition)
        }
    }
}
