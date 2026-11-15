package com.openandroidintelligence.gateway.remote.authproduction

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** 独立的非秘密撤销待办位；加密仓库暂时不可写也不能让旧登录票据重新生效。 */
class RemoteLoginAttemptFence(directory: File) {
    private val file = AtomicFile(File(directory.apply { check(mkdirs() || isDirectory) }, "remote-login-revocation.bin"))
    @Volatile private var blockedInProcess = false
    constructor(context: Context) : this(File(context.noBackupFilesDir, "remote"))
    @Synchronized fun blocked(): Boolean {
        if (blockedInProcess) return true
        return try { file.openRead().use { !it.readBytes().contentEquals(byteArrayOf(0)) } }
        catch (_: FileNotFoundException) { file.baseFile.exists() }
        catch (_: Exception) { true }
    }
    @Synchronized fun block() {
        blockedInProcess = true
        write(true)
    }
    /** 只在旧票据已成功清空、新尝试将开始时解封；失败仍保持拒绝。 */
    @Synchronized fun allowNewAttempt() {
        write(false)
        blockedInProcess = false
    }
    private fun write(blocked: Boolean) {
        val stream = file.startWrite()
        try {
            stream.write(if (blocked) 1 else 0)
            file.finishWrite(stream)
            check(file.baseFile.isFile && file.openRead().use { it.readBytes().contentEquals(byteArrayOf(if (blocked) 1 else 0)) }) {
                "REMOTE_LOGIN_CANCELLATION_INCOMPLETE"
            }
        } catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
