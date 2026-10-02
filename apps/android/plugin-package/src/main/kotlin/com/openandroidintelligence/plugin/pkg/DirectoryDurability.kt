package com.openandroidintelligence.plugin.pkg

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Android's FileInputStream rejects directories; fsync their native descriptor instead. */
object DirectoryDurability {
    fun sync(directory: File) {
        require(directory.isDirectory) { "DIRECTORY_SYNC_REQUIRES_DIRECTORY" }
        if (System.getProperty("java.vm.name") == "Dalvik") {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        } else {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
