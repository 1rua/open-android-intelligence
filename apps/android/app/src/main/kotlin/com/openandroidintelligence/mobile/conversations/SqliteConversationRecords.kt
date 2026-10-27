package com.openandroidintelligence.mobile.conversations

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** SQLite 是持久权威；只缓存有界的近期明文，未读取的其他会话不参与打开存储。 */
internal class SqliteConversationRecords(
    context: Context,
    private val scopeHash: String,
    private val keyProvider: AesGcmKeyProvider,
    private val legacy: EncryptedDocuments,
    plaintextCacheBytes: Int = defaultPlaintextCacheBytes(),
) {
    private val directory = File(context.noBackupFilesDir, "conversation-mirror/$scopeHash")
    private val databaseFile = File(directory, "mirror-v1.db")
    private val helper = object : SQLiteOpenHelper(context, databaseFile.path, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA synchronous=FULL",null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA secure_delete=ON",null).use { it.moveToFirst() }
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE records(row_id TEXT PRIMARY KEY, resource_type TEXT NOT NULL, lookup_key TEXT NOT NULL UNIQUE, revision INTEGER NOT NULL, sort_key INTEGER NOT NULL, tombstone INTEGER NOT NULL, payload BLOB)")
            db.execSQL("CREATE TABLE migrations(version INTEGER PRIMARY KEY)")
        }
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            // 打开旧库时增补查询索引；失败只阻止本次打开，不清理数据或密钥。
            db.execSQL("CREATE INDEX IF NOT EXISTS records_resource_type ON records(resource_type)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("MIRROR_VERSION_UNSUPPORTED")
    }
    private data class Record(val key: String, val rowId: String, val revision: Long, val value: ByteArray)
    private data class Header(val rowId: String, val type: String, val lookup: String, val revision: Long,
        val tombstone: Boolean, val payloadBytes: Int)
    private val plaintext = object : LruCache<String, Record>(plaintextCacheBytes) {
        override fun sizeOf(key: String, value: Record): Int = cacheWeight(key,value)
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Record, newValue: Record?) {
            oldValue.value.fill(0)
        }
    }
    private var depth = 0
    private var closed = false
    private var dataVersion = -1L
    private val encryptionKey by lazy { keyProvider.getOrCreate() }

    init {
        check(directory.isDirectory || directory.mkdirs()) { "MIRROR_STORAGE_UNAVAILABLE" }
        val db = helper.writableDatabase
        try {
            dataVersion = databaseVersion(db)
            val migrated = db.rawQuery("SELECT version FROM migrations WHERE version=1", null).use { it.moveToFirst() }
            if (!migrated) {
                // 单次事务逐个认证旧文档，失败全部回滚且不触碰源文件；不同时保留所有旧正文。
                transaction {
                    legacy.keys().forEach { key ->
                        val value=legacy.read(key) ?: error("MIRROR_CORRUPTED")
                        try { write(key,value) } finally { value.fill(0) }
                    }
                    db.execSQL("INSERT INTO migrations(version) VALUES(1)")
                }
            }
        } catch (failure: Throwable) {
            closed=true
            plaintext.evictAll()
            try { helper.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    private fun database(): SQLiteDatabase {
        check(!closed) { "MIRROR_CLOSED" }
        if (!databaseFile.isFile) {
            plaintext.evictAll()
            closed=true
            helper.close()
            error("MIRROR_CLOSED")
        }
        return helper.writableDatabase
    }
    private fun databaseVersion(db: SQLiteDatabase) = db.rawQuery("PRAGMA data_version",null).use { check(it.moveToFirst()); it.getLong(0) }

    @Synchronized fun <T> transaction(block: () -> T): T {
        check(!closed) { "MIRROR_CLOSED" }
        if (depth > 0) return block()
        val db = database()
        var began=false
        try {
            db.beginTransaction()
            began=true
            depth++
            val version = databaseVersion(db)
            if (version != dataVersion) { plaintext.evictAll(); dataVersion=version }
            val result = block()
            db.setTransactionSuccessful()
            return result
        } catch (failure: Throwable) {
            // 不复制和回滚明文缓存；失败后只认 SQLite 事务的已提交数据。
            plaintext.evictAll()
            if (failure is android.database.SQLException) throw IOException("MIRROR_COMMIT_FAILED",failure)
            throw failure
        } finally {
            if (began) {
                depth--
                try { db.endTransaction() }
                catch (failure: android.database.SQLException) { plaintext.evictAll(); throw IOException("MIRROR_COMMIT_FAILED",failure) }
            }
        }
    }

    @Synchronized fun read(key: String): ByteArray? = transaction {
        val stored=readRecord(key) ?: return@transaction null
        try {
            val shape=aggregateShape(key) ?: return@transaction stored.copyOf()
            val doc=JsonFields.obj(Json.parse(stored.decodeToString())) ?: error("MIRROR_CORRUPTED")
            val index=JsonFields.strings(doc,"recordIndex")
            val bodies=index.map { id ->
                val bytes=readRecord(shape.prefix+id) ?: error("MIRROR_CORRUPTED")
                try { Json.parse(bytes.decodeToString()) } finally { bytes.fill(0) }
            }
            Json.canonical(doc.copy(fields=doc.fields.filterNot { it.first=="recordIndex" }+
                (shape.field to JsonValue.JArray(bodies)))).toByteArray()
        } finally { stored.fill(0) }
    }

    private fun readRecord(key: String): ByteArray? {
        val lookup=digest(key)
        plaintext.get(lookup)?.let { cached ->
            check(cached.key==key) { "MIRROR_SCOPE_MISMATCH" }
            return cached.value.copyOf()
        }
        val header=findHeader(lookup) ?: return null
        if (header.tombstone) return null
        val record=readRecord(header)
        if (record.key!=key) { record.value.fill(0); error("MIRROR_SCOPE_MISMATCH") }
        return record.value
    }

    private fun findHeader(lookup: String): Header? = helper.writableDatabase.rawQuery(
        "SELECT row_id,resource_type,lookup_key,revision,tombstone,length(payload) FROM records WHERE lookup_key=?",
        arrayOf(lookup)).use { if (it.moveToFirst()) header(it) else null }

    private fun header(row: Cursor): Header {
        val length=if (row.isNull(5)) 0L else row.getLong(5)
        check(length in 0L..Int.MAX_VALUE.toLong()) { "MIRROR_CORRUPTED" }
        return Header(row.getString(0),row.getString(1),row.getString(2),row.getLong(3),row.getInt(4)!=0,length.toInt())
    }

    private fun readRecord(header: Header): Record {
        plaintext.get(header.lookup)?.let { cached ->
            if (cached.rowId==header.rowId && cached.revision==header.revision) return cached.copy(value=cached.value.copyOf())
            plaintext.remove(header.lookup)
        }
        val payload=readPayload(header)
        val decoded=try { decrypt(payload,header.type,header.lookup,header.revision) } finally { payload.fill(0) }
        try {
            val doc=JsonFields.obj(Json.parse(decoded.decodeToString())) ?: error("MIRROR_CORRUPTED")
            val key=JsonFields.string(doc,"key") ?: error("MIRROR_CORRUPTED")
            check(digest(key)==header.lookup && resourceType(key)==header.type) { "MIRROR_SCOPE_MISMATCH" }
            val encoded=JsonFields.string(doc,"value") ?: error("MIRROR_CORRUPTED")
            val value=try { java.util.Base64.getDecoder().decode(encoded) }
                catch (failure: IllegalArgumentException) { throw IllegalStateException("MIRROR_CORRUPTED",failure) }
            return Record(key,header.rowId,header.revision,value).also { cache(header.lookup,it) }
        } finally { decoded.fill(0) }
    }

    /** 每个游标行只携带有界密文块，内容长度不受游标窗口或缓存预算限制。 */
    private fun readPayload(header: Header): ByteArray {
        check(!header.tombstone && header.payloadBytes>0) { "MIRROR_CORRUPTED" }
        val payload=ByteArray(header.payloadBytes)
        var offset=0
        try {
            while (offset<payload.size) {
                val requested=minOf(CIPHERTEXT_READ_CHUNK_BYTES,payload.size-offset)
                helper.readableDatabase.rawQuery("SELECT substr(payload,?,?) FROM records WHERE lookup_key=? AND revision=? AND tombstone=0",
                    arrayOf((offset.toLong()+1L).toString(),requested.toString(),header.lookup,header.revision.toString())).use { row ->
                    check(row.moveToFirst() && !row.isNull(0)) { "MIRROR_CORRUPTED" }
                    val chunk=row.getBlob(0)
                    try {
                        check(chunk.size==requested) { "MIRROR_CORRUPTED" }
                        chunk.copyInto(payload,offset)
                    } finally { chunk.fill(0) }
                }
                offset+=requested
            }
            return payload
        } catch (failure: Throwable) { payload.fill(0); throw failure }
    }

    private fun cache(lookup: String, record: Record) {
        // 大于预算的单条消息照常读取和持久保存，仅不进入临时明文缓存。
        if (cacheWeight(lookup,record)<=plaintext.maxSize()) plaintext.put(lookup,record.copy(value=record.value.copyOf()))
        else plaintext.remove(lookup)
    }

    private fun cacheWeight(lookup: String, record: Record): Int =
        (record.value.size.toLong()+2L*(lookup.length.toLong()+record.key.length+record.rowId.length)+CACHE_ENTRY_OVERHEAD_BYTES)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    @Synchronized fun write(key: String, value: ByteArray) = transaction {
        val shape = aggregateShape(key)
        if (shape == null) writeRecord(key, value)
        else {
            val doc = JsonFields.obj(Json.parse(value.decodeToString())) ?: error("MIRROR_CORRUPTED")
            val bodies = JsonFields.objects(doc, shape.field)
            val index = bodies.map { body -> JsonFields.string(body, shape.idField) ?: error("MIRROR_CORRUPTED") }
            // 服务端下发的重复 id 只保留最后一次出现：在这里抛异常会把可恢复的镜像
            // 变成无法启动的存储；去重后写入仍能保证记录集合自洽。
            val deduplicated = LinkedHashMap<String, JsonValue>()
            bodies.zip(index).forEach { (body, id) -> deduplicated[id] = body }
            val uniqueBodies = deduplicated.values.toList()
            val uniqueIndex = deduplicated.keys.toList()
            val previous=readRecord(key)?.let { bytes ->
                try { JsonFields.obj(Json.parse(bytes.decodeToString())) } finally { bytes.fill(0) }
            }
            val obsolete = JsonFields.strings(previous, "recordIndex").toSet() - uniqueIndex.toSet()
            uniqueBodies.zip(uniqueIndex).forEach { (body, id) ->
                val bytes=Json.canonical(body).toByteArray()
                try { writeRecord(shape.prefix+id,bytes) } finally { bytes.fill(0) }
            }
            obsolete.forEach { deleteRecord(shape.prefix + it) }
            val bytes=Json.canonical(doc.copy(fields=doc.fields.filterNot { it.first==shape.field }+
                ("recordIndex" to Json.of(uniqueIndex)))).toByteArray()
            try { writeRecord(key,bytes) } finally { bytes.fill(0) }
        }
    }

    private fun writeRecord(key: String, value: ByteArray) {
        val oldValue=readRecord(key)
        try { if (oldValue?.contentEquals(value)==true) return } finally { oldValue?.fill(0) }
        val type = resourceType(key)
        val lookup = digest(key)
        val old=findHeader(lookup)
        val revision=(old?.revision ?: -1L)+1L
        val plaintext = Json.canonical(Json.of(mapOf("key" to key, "value" to java.util.Base64.getEncoder().encodeToString(value)))).toByteArray()
        val payload = try { encrypt(plaintext, type, lookup, revision) } finally { plaintext.fill(0) }
        val rowId = old?.rowId ?: UUID.randomUUID().toString()
        val body = JsonFields.obj(Json.parse(value.decodeToString()))
        val sortKey = JsonFields.long(body,"timestamp") ?: JsonFields.long(body,"updatedAt") ?: 0L
        val values = ContentValues().apply {
            put("row_id", rowId); put("resource_type", type); put("lookup_key", lookup); put("revision", revision)
            put("sort_key", sortKey); put("tombstone", 0); put("payload", payload)
        }
        check(helper.writableDatabase.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "MIRROR_COMMIT_FAILED" }
        cache(lookup,Record(key,rowId,revision,value))
    }

    @Synchronized fun delete(key: String) = transaction {
        aggregateShape(key)?.let { shape ->
            readRecord(key)?.let { bytes ->
                try { JsonFields.obj(Json.parse(bytes.decodeToString())) } finally { bytes.fill(0) }
            }?.let { doc ->
                JsonFields.strings(doc, "recordIndex").forEach { deleteRecord(shape.prefix + it) }
            }
        }
        deleteRecord(key)
    }

    private fun deleteRecord(key: String) {
        val values = ContentValues().apply { put("tombstone", 1); putNull("payload") }
        val lookup=digest(key)
        helper.writableDatabase.update("records", values, "lookup_key=? AND tombstone=0", arrayOf(lookup))
        plaintext.remove(lookup)
    }

    @Synchronized fun keys(resourceType: String? = null): Set<String> = transaction {
        val keys=linkedSetOf<String>()
        // 默认排除内部正文；指定类型时先由数据库索引筛选，只认证该类记录。
        val selection=if(resourceType==null) "resource_type NOT IN (?,?,?,?)" else "resource_type=?"
        val arguments=if(resourceType==null) arrayOf("message-record","send-atom-record","thread-record","checkpoint-message") else arrayOf(resourceType)
        helper.readableDatabase.rawQuery("SELECT row_id,resource_type,lookup_key,revision,tombstone,length(payload) FROM records WHERE tombstone=0 AND $selection",
            arguments).use { rows ->
            while (rows.moveToNext()) {
                val record=readRecord(header(rows))
                try { keys.add(record.key) } finally { record.value.fill(0) }
            }
        }
        keys
    }

    /** 所有写入都是同步事务；核验数据库仍可读后才允许调用者确认发送或事件游标。 */
    @Synchronized fun flush() {
        check(depth == 0 && !closed) { "MIRROR_COMMIT_INCOMPLETE" }
        transaction {
            helper.readableDatabase.rawQuery("SELECT version FROM migrations WHERE version=1", null).use {
                check(it.moveToFirst()) { "MIRROR_MIGRATION_INCOMPLETE" }
            }
        }
    }

    @Synchronized fun close() {
        if (!closed) {
            check(depth==0) { "MIRROR_COMMIT_INCOMPLETE" }
            closed = true
            try { helper.close() } finally { plaintext.evictAll() }
        }
    }

    @Synchronized fun wipe() {
        close()
        val destination = File(directory.parentFile, "$scopeHash-trash-${UUID.randomUUID()}")
        check(!directory.exists() || directory.renameTo(destination)) { "MIRROR_ERASE_FAILED" }
        keyProvider.delete()
        plaintext.evictAll()
    }

    private data class Shape(val field: String, val idField: String, val prefix: String)
    private fun aggregateShape(key: String): Shape? = when {
        key.startsWith("timeline:") -> Shape("messages", "id", "message-record:${key.removePrefix("timeline:")}:")
        key.startsWith("send-atoms:") -> Shape("atoms", "clientMessageId", "send-atom-record:${key.removePrefix("send-atoms:")}:")
        key == "threads" -> Shape("threads", "id", "thread-record:")
        key == "workbench" -> Shape("messages", "id", "checkpoint-message:")
        else -> null
    }
    private fun resourceType(key: String) = key.substringBefore(':')
    private fun aad(type: String, lookup: String, revision: Long) =
        "open-android-intelligence:mirror:v1:$scopeHash:$type:$lookup:$revision".toByteArray()

    private fun encrypt(value: ByteArray, type: String, lookup: String, revision: Long): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad(type, lookup, revision))
        val ciphertext = cipher.doFinal(value)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output -> output.writeInt(1); output.writeInt(cipher.iv.size); output.write(cipher.iv); output.writeInt(ciphertext.size); output.write(ciphertext) }
            bytes.toByteArray()
        }
    }

    private fun decrypt(value: ByteArray, type: String, lookup: String, revision: Long): ByteArray = try {
        DataInputStream(ByteArrayInputStream(value)).use { input ->
            check(input.readInt() == 1) { "MIRROR_VERSION_UNSUPPORTED" }
            check(input.readInt() == 12) { "MIRROR_CORRUPTED" }
            val iv = ByteArray(12).also(input::readFully)
            val length = input.readInt()
            check(length >= 16 && length <= input.available()) { "MIRROR_CORRUPTED" }
            val ciphertext = ByteArray(length).also(input::readFully)
            check(input.available() == 0) { "MIRROR_CORRUPTED" }
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv))
                updateAAD(aad(type, lookup, revision)); doFinal(ciphertext)
            }
        }
    } catch (failure: Exception) { throw IllegalStateException("MIRROR_CORRUPTED", failure) }

    companion object {
        // 缓存最多使用运行时堆预算的 1/64（约 1.56%）；它不限制或淘汰磁盘历史。
        private const val PLAINTEXT_HEAP_DIVISOR=64L
        // 按数组/字符串字节计费，并为记录对象和 LRU 节点保守预留固定开销。
        private const val CACHE_ENTRY_OVERHEAD_BYTES=128L
        // 单次查询只取 64 KiB，远小于平台默认 2 MiB 游标窗口；不是消息长度上限。
        private const val CIPHERTEXT_READ_CHUNK_BYTES=64*1024
        private fun defaultPlaintextCacheBytes():Int=(Runtime.getRuntime().maxMemory()/PLAINTEXT_HEAP_DIVISOR)
            .coerceIn(1L,Int.MAX_VALUE.toLong()).toInt()
        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
