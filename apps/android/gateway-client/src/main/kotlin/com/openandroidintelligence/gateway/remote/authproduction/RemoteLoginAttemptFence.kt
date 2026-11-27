package com.openandroidintelligence.gateway.remote.authproduction

import android.content.Context
import android.util.AtomicFile
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 独立保存每次登录的完成/撤销终态；新尝试不能覆盖旧撤销记录而复活旧账号。 */
class RemoteLoginAttemptFence(directory: File) {
    private val directory = directory.apply { check(mkdirs() || isDirectory) }
    private val file = AtomicFile(File(directory, "remote-login-revocation.bin"))
    private val lockFile = File(directory, "remote-login-revocation.lock")
    private val key = lockFile.canonicalPath
    private val monitor = monitors.computeIfAbsent(key) { Any() }
    constructor(context: Context) : this(File(context.noBackupFilesDir, "remote"))
    private data class State(val current: String? = null, val completed: Set<String> = emptySet(), val cancelled: Set<String> = emptySet())
    fun blocked(): Boolean = runCatching { locked { pending(load()) == null } }.getOrDefault(true)
    fun currentAttemptId(): String = locked { pending(load()) ?: error("REMOTE_AUTHORIZATION_SUPERSEDED") }
    fun block() = locked {
        val state = load()
        val id = pending(state) ?: return@locked
        cancel(state, id)
    }
    /** 精确撤销指定尝试；迟到的取消不能作用于后继新登录。已完成的尝试以提交先后为准。 */
    fun cancelAttempt(id: String) = locked {
        val state = load()
        if (id !in state.completed) cancel(state, id)
    }
    private fun cancel(state: State, id: String) {
        processCancelled.computeIfAbsent(key) { mutableSetOf() }.add(id)
        save(state.copy(cancelled = state.cancelled + id))
    }
    fun allowNewAttempt(): String = locked {
        val state = load()
        check(pending(state) == null) { "REMOTE_LOGIN_ATTEMPT_PENDING" }
        val id = UUID.randomUUID().toString()
        save(state.copy(current = id)); id
    }
    fun requirePending(id: String) = locked { check(pending(load()) == id) { "REMOTE_AUTHORIZATION_SUPERSEDED" } }
    /** 只在凭据已写盘且本次仍未取消后提交完成终态；只锁本地小文件，不跨网络。 */
    fun complete(id: String) = locked {
        val state = load()
        check(pending(state) == id) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
        save(state.copy(completed = state.completed + id))
    }
    fun authorized(id: String): Boolean = runCatching { locked {
        val state = load()
        id in state.completed && id !in state.cancelled && id !in processCancelled[key].orEmpty()
    } }.getOrDefault(false)
    private fun pending(state: State): String? = state.current?.takeUnless {
        it in state.completed || it in state.cancelled || it in processCancelled[key].orEmpty()
    }
    private fun load(): State {
        val bytes = try { file.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) { check(!file.baseFile.exists()) { "REMOTE_LOGIN_CANCELLATION_INCOMPLETE" }; return State() }
        val value = RemoteWireJson.decode(bytes).objectValue()
        check(value.longValue("v") == 2L) { "REMOTE_LOGIN_CANCELLATION_INCOMPLETE" }
        fun ids(name: String): Set<String> = value.arrayValue(name).map { (it as? JsonValue.JString)?.value ?: error("INVALID_MESSAGE") }
            .onEach { check(UUID.fromString(it).toString() == it) }.toSet()
        val current = value.optionalText("current")?.also { check(UUID.fromString(it).toString() == it) }
        return State(current, ids("completed"), ids("cancelled"))
    }
    private fun save(state: State) {
        val bytes = Json.canonical(Json.of(mapOf("v" to 2, "current" to state.current,
            "completed" to state.completed.sorted(), "cancelled" to state.cancelled.sorted()))).encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes); file.finishWrite(stream)
            check(file.baseFile.isFile && file.openRead().use { it.readBytes().contentEquals(bytes) }) { "REMOTE_LOGIN_CANCELLATION_INCOMPLETE" }
        } catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    private fun <T> locked(block: () -> T): T = synchronized(monitor) {
        RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use { block() } }
    }
    companion object {
        private val monitors = ConcurrentHashMap<String, Any>()
        private val processCancelled = ConcurrentHashMap<String, MutableSet<String>>()
    }
}
