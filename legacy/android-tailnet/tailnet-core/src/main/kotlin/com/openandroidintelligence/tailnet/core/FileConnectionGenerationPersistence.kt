package com.openandroidintelligence.tailnet.core

import java.io.File

/**
 * App-private, monotonic connection-generation persistence. The generation is
 * metadata, not secret material; it lives in noBackupFilesDir so reconnect
 * fencing survives process death but not device backup restore.
 */
class FileConnectionGenerationPersistence(
    private val file: File,
) : ConnectionGenerationPersistence {
    override fun load(): ULong? {
        if (!file.isFile) return null
        return file.readText().trim().toULongOrNull()
            ?: throw IllegalStateException("connection generation file is corrupted")
    }

    override fun save(generation: ULong) {
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: file.absoluteFile.parentFile ?: error("generation file has no parent")
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeText(generation.toString())
        check(temporary.renameTo(file)) { "unable to atomically persist connection generation" }
    }
}
